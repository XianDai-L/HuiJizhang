package com.wisebook.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 结构化提取流水线——本模块的核心。
 *
 * <p>它实现 D1 定下的那道防线（针对「模型输出非 JSON / JSON 被截断 / 字段不合规」）：
 *
 * <pre>
 * 调用模型
 *    ↓
 * 取 tool_calls 的 arguments；若为空，降级从 content 正文里抽 JSON
 *    ↓
 * JSON 解析失败（截断、格式不完整） → 记一次失败，把错误反馈给模型后重试
 *    ↓
 * 结构校验（必填 / 类型 / 枚举）失败 → 记一次失败，把问题清单反馈给模型后重试
 *    ↓
 * 通过 → OK         重试用尽 → DEGRADED（转人工填表，属正常路径）
 * </pre>
 *
 * <p><b>关键点：重试时会把上一次的具体错误回传给模型。</b>裸重试（原样再问一遍）
 * 成功率很低，因为模型不知道自己哪里错了；带上问题清单后，修正型重试的成功率显著提高。
 *
 * <p>传输层错误（网络、鉴权、限流）<b>不在这里重试</b>：那是换厂商或退避的问题，
 * 应由调用方处理，不混进结构校验的重试预算里。
 *
 * <p>本类不依赖 Android 也不需要网络，因此可以用假客户端把每条失败路径都测到。
 */
public final class StructuredExtractor {

    /** 默认 2 次：一次原始 + 一次带错误反馈的修正 */
    public static final int DEFAULT_MAX_ATTEMPTS = 2;

    private final LlmClient client;
    private final int maxAttempts;

    public StructuredExtractor(LlmClient client) {
        this(client, DEFAULT_MAX_ATTEMPTS);
    }

    public StructuredExtractor(LlmClient client, int maxAttempts) {
        this.client = client;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public ExtractionResult extract(String systemPrompt, String userContent, ToolSchema tool) {
        List<ExtractionAttempt> attempts = new ArrayList<>();
        List<String> previousIssues = null;

        for (int index = 1; index <= maxAttempts; index++) {
            String user = previousIssues == null
                    ? userContent
                    : userContent + "\n\n" + buildFeedback(previousIssues);

            LlmRawResponse raw;
            try {
                raw = client.chatWithTool(systemPrompt, user, tool);
            } catch (LlmException e) {
                attempts.add(ExtractionAttempt.transportError(index, e.kind() + "：" + e.getMessage()));
                return ExtractionResult.degraded(attempts,
                        "模型调用失败（" + e.kind() + "）：" + e.getMessage());
            }

            String payloadText = firstNonBlank(raw.toolArguments(), jsonObjectIn(raw.content()));
            if (payloadText == null) {
                previousIssues = Collections.singletonList(
                        "上一次没有返回任何 JSON。请只输出符合工具参数结构的 JSON 对象，"
                                + "不要附带解释文字，也不要用 Markdown 代码块包裹。");
                attempts.add(ExtractionAttempt.schemaError(index, "未返回可解析的 JSON", raw));
                continue;
            }

            JsonObject parsed;
            try {
                JsonElement element = JsonParser.parseString(payloadText);
                if (!element.isJsonObject()) {
                    throw new IllegalStateException("顶层不是 JSON 对象");
                }
                parsed = element.getAsJsonObject();
            } catch (RuntimeException e) {
                previousIssues = Collections.singletonList(
                        "上一次返回的内容无法解析为合法 JSON（常见原因是输出被截断或格式不完整）："
                                + e.getMessage() + "。请重新输出一个完整、合法的 JSON 对象。");
                attempts.add(ExtractionAttempt.schemaError(index, "JSON 解析失败：" + e.getMessage(), raw));
                continue;
            }

            List<String> issues = JsonPayloadValidator.validate(tool.parameters(), parsed);
            if (!issues.isEmpty()) {
                previousIssues = issues;
                attempts.add(ExtractionAttempt.schemaError(index, String.join("；", issues), raw));
                continue;
            }

            attempts.add(ExtractionAttempt.ok(index, raw));
            return ExtractionResult.ok(parsed, attempts);
        }

        return ExtractionResult.degraded(attempts,
                "连续 " + maxAttempts + " 次输出均未通过校验，转人工填写");
    }

    // ------------------------------------------------------------ 辅助

    /**
     * 取第一个非空白串。
     *
     * <p>用 {@code trim().isEmpty()} 而不是 {@code String.isBlank()}：
     * 后者在 Android 上是 API 33 才有的，而本项目 minSdk 26。
     * 同理这里也不用 {@code List.of}（API 30）——整个模块都在为 Android 服务，
     * 这个纯 JVM 模块的代码同样会打进 APK。原因详见 HANDOFF §8。
     */
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a;
        }
        if (b != null && !b.trim().isEmpty()) {
            return b;
        }
        return null;
    }

    /**
     * 从正文里抽出第一个 {@code {} 到最后一个 {@code }} 之间的内容。
     *
     * <p>降级路径：有些模型在给了 tools 的情况下仍把 JSON 写进正文，
     * 或包在 ```json 代码块里。
     */
    static String jsonObjectIn(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return content.substring(start, end + 1);
    }

    /** 把上一次的问题回传给模型，这是修正型重试有效的关键 */
    private static String buildFeedback(List<String> issues) {
        StringBuilder builder = new StringBuilder("【上一次输出未通过校验，请修正后重新输出】\n");
        for (String issue : issues) {
            builder.append("- ").append(issue).append('\n');
        }
        builder.append("只返回符合工具参数结构的 JSON 对象，不要包含解释文字或 Markdown 代码块标记。");
        return builder.toString();
    }
}
