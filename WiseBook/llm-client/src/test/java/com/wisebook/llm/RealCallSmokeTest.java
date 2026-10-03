package com.wisebook.llm;

import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 真实调用冒烟测试——<b>未提供 Key 时自动跳过</b>。
 *
 * <p>启用方式（会消耗真实额度）：</p>
 * <pre>
 * $env:WISEBOOK_SILICONFLOW_KEY="sk-..."
 * $env:WISEBOOK_DEEPSEEK_KEY="sk-..."
 * .\gradlew.bat :llm-client:test --tests "*RealCallSmokeTest*" --no-daemon
 * </pre>
 *
 * <p>它在整个测试集中的定位：其余测试用假客户端验证<b>逻辑</b>，
 * 它只负责回答假客户端回答不了的问题——<b>真实服务端的返回结构与参数约定，
 * 是否和我们的假设一致</b>。例如 {@code tool_choice} 指定具体函数是否被支持、
 * 响应里 {@code tool_calls[0].function.arguments} 是否为合法 JSON 字符串。
 */
public class RealCallSmokeTest {

    private static final String DEFAULT_SILICONFLOW_MODEL = "Qwen/Qwen3.6-35B-A3B";
    private static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-chat";

    private static final String INPUT = "今天打车28块";

    private static ToolSchema draftTool() {
        return ToolSchema.builder("create_draft")
                .description("把一句口语记账转换成结构化账目草稿")
                .integer("amountCents", "金额（单位：分）", ToolSchema.Requirement.REQUIRED)
                .enumOf("direction", "收/支方向", ToolSchema.Requirement.REQUIRED, "expense", "income")
                .string("merchant", "商户或对方", ToolSchema.Requirement.OPTIONAL)
                .build();
    }

    @Test
    public void realCallSiliconFlow() throws Exception {
        String apiKey = System.getenv("WISEBOOK_SILICONFLOW_KEY");
        Assume.assumeTrue("未设置 WISEBOOK_SILICONFLOW_KEY，跳过", apiKey != null && !apiKey.isBlank());
        run("siliconflow", LlmModelConfig.siliconFlow(apiKey, envOr("WISEBOOK_SILICONFLOW_MODEL", DEFAULT_SILICONFLOW_MODEL)));
    }

    @Test
    public void realCallDeepSeek() throws Exception {
        String apiKey = System.getenv("WISEBOOK_DEEPSEEK_KEY");
        Assume.assumeTrue("未设置 WISEBOOK_DEEPSEEK_KEY，跳过", apiKey != null && !apiKey.isBlank());
        run("deepseek", LlmModelConfig.deepSeek(apiKey, envOr("WISEBOOK_DEEPSEEK_MODEL", DEFAULT_DEEPSEEK_MODEL)));
    }

    private void run(String tag, LlmModelConfig config) throws Exception {
        LlmClient client = new OpenAiCompatibleLlmClient(config);
        ExtractionResult result = new StructuredExtractor(client)
                .extract("你是记账助手，只输出结构化结果。", INPUT, draftTool());

        System.out.println("[" + tag + "] model=" + config.model()
                + " status=" + result.status()
                + " attempts=" + result.attemptCount()
                + " payload=" + result.payload()
                + (result.isOk() ? "" : " reason=" + result.degradeReason()));

        for (ExtractionAttempt attempt : result.attempts()) {
            if (attempt.kind() != ExtractionAttempt.Kind.OK) {
                System.out.println("[" + tag + "]   " + attempt + " rawArgs=" + attempt.rawToolArguments());
            }
        }

        assertTrue("[" + tag + "] 真实调用应通过 schema 校验："
                        + (result.isOk() ? "" : result.degradeReason()),
                result.isOk());
        assertEquals("[" + tag + "] amountCents",
                2800, result.payload().get("amountCents").getAsInt());
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
