package exp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wisebook.llm.ExtractionAttempt;
import com.wisebook.llm.ExtractionResult;
import com.wisebook.llm.LlmClient;
import com.wisebook.llm.LlmModelConfig;
import com.wisebook.llm.OpenAiCompatibleLlmClient;
import com.wisebook.llm.StructuredExtractor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * P2 截图路线 A/B 对比实验。
 *
 * <p>两条路线吃<b>同一份</b>工具定义、同一个 {@link StructuredExtractor}（含修正型重试）、
 * 同一套评估判据，只换"图怎么变成结构化输入"这一段：
 *
 * <pre>
 * 路线 A：图 ──(多模态 + tool call)──────────────→ 结构化
 * 路线 B：图 ──(转写器)──→ 文本 ──(文本模型 + tool call)──→ 结构化
 * </pre>
 *
 * <p>判据只看四列，且以<b>金额与商户</b>为主：它们是"错一个字就变成另一个数/另一个人"的字段，
 * 也是 D2 §6.3 点名要看的两列。时间列只并列展示不自动判分——图上的时间可能是
 * 分组标签、相对词或带秒的绝对时间，自动判定会引入误差。
 */
public final class ExpMain {

    private static final String VISION_MODEL = "deepseek-flash";
    /** 产品 DeepSeekProvider 的默认模型名；用它跑第二步，等于跑产品真实链路 */
    private static final String TEXT_MODEL = "deepseek-chat";
    /** D2 §6 指定的 OCR 专用模型：3B、限免、**仅 8K 上下文**，挂在硅基流动下 */
    private static final String OCR_MODEL = "deepseek-ai/DeepSeek-OCR";
    private static final Path OUT_DIR = Paths.get("D:/HuiJi/p2-experiment/results");
    private static final int MAX_ATTEMPTS = 2;

    public static void main(String[] args) throws Exception {
        String key = Env.deepSeekKey();
        if (key == null) {
            System.err.println("local.properties 里没有 wisebook.deepseek.key，无法实验");
            return;
        }
        String siliconFlowKey = Env.siliconFlowKey();
        System.out.println("硅基流动 Key：" + (siliconFlowKey == null ? "未配置（OCR 专用模型暂不可用）" : "已配置"));

        List<Samples.Sample> samples = Samples.all();
        List<Row> rows = new ArrayList<>();

        for (Samples.Sample sample : samples) {
            rows.add(runRouteA(sample, key));
        }
        for (Samples.Sample sample : samples) {
            rows.add(runRouteB1(sample, key));
        }
        for (Samples.Sample sample : samples) {
            rows.add(runRouteB2(sample, key, siliconFlowKey));
        }

        Files.createDirectories(OUT_DIR);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path report = OUT_DIR.resolve("run-" + stamp + ".md");
        Path detail = OUT_DIR.resolve("run-" + stamp + ".json");
        Files.writeString(report, renderMarkdown(rows, siliconFlowKey != null), StandardCharsets.UTF_8);
        Files.writeString(detail, renderJson(rows), StandardCharsets.UTF_8);

        System.out.println("报告：" + report);
        System.out.println("明细：" + detail);
        System.out.println(summary(rows));
    }

    // ------------------------------------------------------------------ 两条路线

    /** 路线 A：图 → 多模态模型 + tool call → 结构化 */
    private static Row runRouteA(Samples.Sample sample, String key) {
        LlmClient client = new VisionLlmClient(LlmModelConfig.deepSeek(key, VISION_MODEL));
        StructuredExtractor extractor = new StructuredExtractor(client, MAX_ATTEMPTS);
        String userContent = VisionLlmClient.IMAGE_PREFIX + sample.path() + "\n"
                + "请把这张支付截图转成账目草稿。";

        Row row = new Row("A 多模态直读", sample, VISION_MODEL);
        long start = System.currentTimeMillis();
        try {
            ExtractionResult result = extractor.extract(ExpPrompt.IMAGE_SYSTEM, userContent, ExpSchema.build());
            row.fill(result);
        } catch (RuntimeException e) {
            row.fail("未预期的异常：" + e);
        }
        row.millis = System.currentTimeMillis() - start;
        row.evaluate();
        return row;
    }

    /** 路线 B1：图 → 通用视觉模型转写 → 文本 → 现有文本管线 → 结构化 */
    private static Row runRouteB1(Samples.Sample sample, String key) {
        Row row = new Row("B1 视觉转写 + 文本管线", sample, TEXT_MODEL);
        long start = System.currentTimeMillis();
        try {
            Transcriber transcriber = new Transcriber(LlmModelConfig.deepSeek(key, VISION_MODEL));
            row.ocrText = transcriber.transcribe(sample.path(), ExpPrompt.TRANSCRIBE);
            row.step1Millis = System.currentTimeMillis() - start;

            LlmClient client = new OpenAiCompatibleLlmClient(LlmModelConfig.deepSeek(key, TEXT_MODEL));
            StructuredExtractor extractor = new StructuredExtractor(client, MAX_ATTEMPTS);
            ExtractionResult result = extractor.extract(ExpPrompt.TEXT_SYSTEM, row.ocrText, ExpSchema.build());
            row.fill(result);
        } catch (Exception e) {
            row.fail("第一步或第二步失败：" + e);
        }
        row.millis = System.currentTimeMillis() - start;
        row.evaluate();
        return row;
    }

    /**
     * 路线 B2：图 → **真正的 OCR 专用模型**（DeepSeek-OCR）→ 文本 → 同一文本管线 → 结构化。
     *
     * <p>它是 D2 §6.3 里"路线 B"的原意（OCR 3B 且限免）。B1 只是在没有 OCR Key 时的替代，
     * 两者结构相同、只换转写器——这正好演示了 B 路线"OCR 可替换"这一点。
     */
    private static Row runRouteB2(Samples.Sample sample, String key, String siliconFlowKey) {
        Row row = new Row("B2 DeepSeek-OCR + 文本管线", sample, TEXT_MODEL);
        if (siliconFlowKey == null) {
            row.fail("硅基流动 Key 未配置，本路线跳过");
            return row;
        }
        long start = System.currentTimeMillis();
        try {
            Transcriber transcriber = new Transcriber(
                    LlmModelConfig.siliconFlow(siliconFlowKey, OCR_MODEL), true);
            row.ocrText = transcriber.transcribe(sample.path(), ExpPrompt.TRANSCRIBE);
            row.step1Millis = System.currentTimeMillis() - start;

            LlmClient client = new OpenAiCompatibleLlmClient(LlmModelConfig.deepSeek(key, TEXT_MODEL));
            StructuredExtractor extractor = new StructuredExtractor(client, MAX_ATTEMPTS);
            ExtractionResult result = extractor.extract(ExpPrompt.TEXT_SYSTEM, row.ocrText, ExpSchema.build());
            row.fill(result);
        } catch (Exception e) {
            row.fail("第一步或第二步失败：" + e);
        }
        row.millis = System.currentTimeMillis() - start;
        row.evaluate();
        return row;
    }

    // ------------------------------------------------------------------ 结果模型

    private static final class Row {

        final String route;
        final Samples.Sample sample;
        final String model;

        boolean ok;
        boolean degraded;
        String degradeReason;
        int attempts;
        /** 首次尝试即通过——论文口径的「首次通过率」 */
        boolean firstPass;
        /** 每一次尝试的记录，用来回答"为什么重试" */
        List<ExtractionAttempt> attemptRecords = new ArrayList<>();
        JsonObject payload;
        String ocrText;
        String failure;
        long millis;
        long step1Millis;

        // 评估结果
        Boolean amountHit;
        Boolean merchantExact;
        Boolean merchantPartial;
        Boolean directionHit;
        Boolean countHit;

        Row(String route, Samples.Sample sample, String model) {
            this.route = route;
            this.sample = sample;
            this.model = model;
        }

        void fill(ExtractionResult result) {
            this.attempts = result.attemptCount();
            this.firstPass = result.firstAttemptSucceeded();
            this.attemptRecords = result.attempts();
            if (result.isOk()) {
                this.ok = true;
                this.payload = result.payload();
            } else {
                this.degraded = true;
                this.degradeReason = result.degradeReason();
            }
        }

        void fail(String reason) {
            this.degraded = true;
            this.degradeReason = reason;
        }

        void evaluate() {
            if (payload == null) {
                return;
            }
            Samples.Truth expect = sample.first();
            Long amount = readLong(payload, "amountCents");
            amountHit = amount != null && amount == expect.amountCents;

            if (expect.merchant != null) {
                String merchant = readString(payload, "merchant");
                String gotNormalized = normalize(merchant);
                String expectNormalized = normalize(expect.merchant);
                merchantExact = gotNormalized.equals(expectNormalized);
                merchantPartial = merchantExact || gotNormalized.contains(expectNormalized)
                        || expectNormalized.contains(gotNormalized);
            }

            directionHit = expect.direction.equals(readString(payload, "direction"));

            Long count = readLong(payload, "transactionCount");
            int expected = sample.truths.size();
            countHit = count != null && count.intValue() == expected;
        }
    }

    // ------------------------------------------------------------------ 渲染

    private static String summary(List<Row> rows) {
        int amountHits = 0;
        int merchantTotal = 0;
        int merchantHits = 0;
        int directionHits = 0;
        int countHits = 0;
        for (Row row : rows) {
            if (Boolean.TRUE.equals(row.amountHit)) {
                amountHits++;
            }
            if (row.merchantPartial != null) {
                merchantTotal++;
                if (row.merchantPartial) {
                    merchantHits++;
                }
            }
            if (Boolean.TRUE.equals(row.directionHit)) {
                directionHits++;
            }
            if (Boolean.TRUE.equals(row.countHit)) {
                countHits++;
            }
        }
        int firstPass = 0;
        for (Row row : rows) {
            if (row.firstPass) {
                firstPass++;
            }
        }
        return "汇总：" + rows.size() + " 次解析；金额命中 " + amountHits + "/" + rows.size()
                + "；商户命中 " + merchantHits + "/" + merchantTotal
                + "；方向命中 " + directionHits + "/" + rows.size()
                + "；笔数命中 " + countHits + "/" + rows.size()
                + "；首次通过（无需重试）" + firstPass + "/" + rows.size();
    }

    private static String renderMarkdown(List<Row> rows, boolean siliconFlowAvailable) {
        StringBuilder out = new StringBuilder();
        out.append("# P2 截图解析 A/B 实验 · 运行结果\n\n");
        out.append("> 本文件由 `p2-experiment` 生成，属 AI 产物区，不进产品代码。\n\n");
        out.append("- 运行时间：").append(LocalDateTime.now()).append('\n');
        out.append("- 路线 A 模型：`").append(VISION_MODEL).append("`（多模态，一步直读）\n");
        out.append("- 路线 B1：转写用 `").append(VISION_MODEL).append("`（通用视觉模型），结构化用 `")
                .append(TEXT_MODEL).append("`（两步）\n");
        out.append("- 路线 B2：转写用 `").append(OCR_MODEL)
                .append("`（硅基流动，D2 §6 指定的 OCR 专用模型），结构化同上（两步）\n");
        out.append("- 结构化重试用产品 `StructuredExtractor`（上限 ").append(MAX_ATTEMPTS).append(" 次，含修正型重试）\n");
        out.append("- 路线 A 额外参数：`thinking:{\"type\":\"disabled\"}`——V4 系列默认思考模式，"
                + "而思考模式不接受「指定具体函数」的 tool_choice（实测 400），"
                + "这是模型侧的新限制，产品的 `OpenAiCompatibleLlmClient` 目前没有这个字段\n");
        out.append("- 路线 B 第二步用 `").append(TEXT_MODEL)
                .append("`（产品 DeepSeek 默认模型），即产品真实链路\n");
        out.append("- 硅基流动 Key：")
                .append(siliconFlowAvailable ? "已配置，B2 路线已跑" : "**未配置，B2 路线未跑**").append('\n');
        out.append('\n');

        out.append("## 一、逐次结果\n\n");
        out.append("| 路线 | 样本 | 版面 | 金额 | 商户 | 方向 | 笔数 | attempts | 用时(ms) |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Row row : rows) {
            out.append("| ").append(row.route)
                    .append(" | ").append(row.sample.id)
                    .append(" | ").append(row.sample.kind)
                    .append(" | ").append(mark(row.amountHit))
                    .append(" | ").append(row.merchantPartial == null ? "—"
                            : (Boolean.TRUE.equals(row.merchantPartial)
                            ? (Boolean.TRUE.equals(row.merchantExact) ? "✅" : "🟡(部分)")
                            : "❌"))
                    .append(" | ").append(mark(row.directionHit))
                    .append(" | ").append(mark(row.countHit))
                    .append(" | ").append(row.attempts)
                    .append(" | ").append(row.millis)
                    .append(" |\n");
        }
        out.append('\n');
        out.append(summary(rows)).append("\n\n");

        out.append("### 尝试明细（回答「为什么重试」）\n\n");
        for (Row row : rows) {
            out.append("- **").append(row.sample.id).append(" · ").append(row.route).append("**：");
            if (row.attemptRecords.isEmpty()) {
                out.append(row.degraded ? "请求未发出（" + row.degradeReason + "）" : "（无记录）");
            } else {
                for (ExtractionAttempt attempt : row.attemptRecords) {
                    out.append("\n  - 第 ").append(attempt.index()).append(" 次 `")
                            .append(attempt.kind()).append("`");
                    if (!attempt.detail().isEmpty()) {
                        out.append("：").append(attempt.detail());
                    }
                }
            }
            out.append('\n');
        }
        out.append('\n');

        out.append("## 二、期望值 vs 实际值\n\n");
        for (Row row : rows) {
            Samples.Truth expect = row.sample.first();
            out.append("### ").append(row.sample.id).append(" · ").append(row.route).append("\n\n");
            out.append("- 期望金额：").append(expect.amountCents).append(" 分；实际：")
                    .append(value(row, "amountCents")).append('\n');
            out.append("- 期望商户：").append(nullToDash(expect.merchant)).append("；实际：")
                    .append(nullToDash(readString(row.payload, "merchant"))).append('\n');
            out.append("- 期望方向：").append(expect.direction).append("；实际：")
                    .append(nullToDash(readString(row.payload, "direction"))).append('\n');
            out.append("- 期望笔数：").append(row.sample.truths.size()).append("；实际：")
                    .append(value(row, "transactionCount")).append('\n');
            out.append("- 金额原文抄录：").append(nullToDash(readString(row.payload, "amountRaw"))).append('\n');
            out.append("- 时间抄录：").append(nullToDash(readString(row.payload, "occurredAtText")))
                    .append("（期望 ").append(nullToDash(expect.occurredAtText));
            if (!row.sample.timeScorable) {
                out.append("，该样本时间不计分：").append(row.sample.timeNote);
            }
            out.append("）\n");
            out.append("- 分类：").append(nullToDash(readString(row.payload, "categoryPath")))
                    .append("；支付方式：").append(nullToDash(readString(row.payload, "paymentMethod")))
                    .append("；备注：").append(nullToDash(readString(row.payload, "note"))).append('\n');
            if (row.ocrText != null) {
                out.append("- 第一步转写文本：\n\n```text\n").append(row.ocrText).append("\n```\n");
            }
            out.append('\n');
        }

        out.append("## 三、模型结构化解构（原始 JSON）\n\n");
        for (Row row : rows) {
            out.append("### ").append(row.sample.id).append(" · ").append(row.route).append('\n');
            if (row.payload != null) {
                out.append("\n```json\n")
                        .append(new GsonBuilder().setPrettyPrinting().create().toJson(row.payload))
                        .append("\n```\n");
            } else {
                out.append("\n未通过校验：").append(row.degradeReason).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String renderJson(List<Row> rows) {
        JsonArray array = new JsonArray();
        for (Row row : rows) {
            JsonObject item = new JsonObject();
            item.addProperty("route", row.route);
            item.addProperty("sample", row.sample.id);
            item.addProperty("kind", row.sample.kind);
            item.addProperty("model", row.model);
            item.addProperty("ok", row.ok);
            item.addProperty("attempts", row.attempts);
            item.addProperty("firstPass", row.firstPass);
            item.addProperty("millis", row.millis);
            JsonArray attemptArray = new JsonArray();
            for (ExtractionAttempt attempt : row.attemptRecords) {
                JsonObject record = new JsonObject();
                record.addProperty("index", attempt.index());
                record.addProperty("kind", attempt.kind().name());
                record.addProperty("detail", attempt.detail());
                attemptArray.add(record);
            }
            item.add("attemptRecords", attemptArray);
            if (row.degradeReason != null) {
                item.addProperty("degradeReason", row.degradeReason);
            }
            if (row.ocrText != null) {
                item.addProperty("ocrText", row.ocrText);
                item.addProperty("step1Millis", row.step1Millis);
            }
            if (row.payload != null) {
                item.add("payload", row.payload);
            }
            JsonObject truth = new JsonObject();
            truth.addProperty("amountCents", row.sample.first().amountCents);
            truth.addProperty("direction", row.sample.first().direction);
            truth.addProperty("merchant", row.sample.first().merchant);
            truth.addProperty("count", row.sample.truths.size());
            item.add("truth", truth);

            JsonObject hit = new JsonObject();
            put(hit, "amount", row.amountHit);
            put(hit, "merchantExact", row.merchantExact);
            put(hit, "merchantPartial", row.merchantPartial);
            put(hit, "direction", row.directionHit);
            put(hit, "count", row.countHit);
            item.add("hit", hit);
            array.add(item);
        }
        return new GsonBuilder().setPrettyPrinting().create().toJson(array);
    }

    private static void put(JsonObject object, String key, Boolean value) {
        if (value == null) {
            object.add(key, com.google.gson.JsonNull.INSTANCE);
        } else {
            object.addProperty(key, value);
        }
    }

    private static String mark(Boolean hit) {
        if (hit == null) {
            return "—";
        }
        return hit ? "✅" : "❌";
    }

    private static String value(Row row, String field) {
        if (row.payload == null) {
            return "—";
        }
        JsonElement element = row.payload.get(field);
        return element == null || element.isJsonNull() ? "（未填）" : element.toString();
    }

    private static String nullToDash(String text) {
        return text == null ? "（未填）" : text;
    }

    // ------------------------------------------------------------------ 读取辅助

    private static String readString(JsonObject payload, String field) {
        if (payload == null) {
            return null;
        }
        JsonElement element = payload.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        String value = element.getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    private static Long readLong(JsonObject payload, String field) {
        if (payload == null) {
            return null;
        }
        JsonElement element = payload.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 商户名归一：去空白与常见标点、统一全角、转小写，再比相等/包含 */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (ch <= ' ') {
                continue;
            }
            char lower = Character.toLowerCase(ch);
            if ("（）()【】[]·、,，.。-—_:：;；\"'“”‘’".indexOf(lower) >= 0) {
                continue;
            }
            builder.append(lower);
        }
        return builder.toString();
    }
}
