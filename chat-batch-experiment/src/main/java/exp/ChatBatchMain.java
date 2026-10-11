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
 * 对话（文字）入口「多笔录入」三种策略的对比实验。
 *
 * <pre>
 * S0 现状：单笔工具 + 单笔 prompt（"只解析第一笔，报 transactionCount"）→ 只落一笔，其余靠用户手填
 * A  批量：批量工具（drafts 数组）+ 覆写【多笔】段的 prompt → 一次拆出 N 笔
 * B  两步：先用 S0；若它报的笔数 &gt; 1，再补一次 A 的调用，采用 A 的结果
 * </pre>
 *
 * <p>三条策略吃同一个产品 {@link StructuredExtractor}（含修正型重试与降级）、同一个模型
 * （产品默认的 {@code deepseek-chat}）、同一批样本与同一份人工基准，只换"工具定义 + 提示词"。
 *
 * <p><b>判据分四层</b>，因为"能不能用"和"用起来贵不贵"是两件事：
 * <ol>
 *   <li><b>笔数</b>：策略认定的笔数是否等于人工基准（并区分过拆 / 漏拆）</li>
 *   <li><b>逐笔正确</b>：笔数对齐时，逐笔金额与逐笔方向是否都对（顺序按句中出现次序）</li>
 *   <li><b>首笔正确</b>：最先落的那一笔对不对——S0 只能落一笔，这一项才对它有意义</li>
 *   <li><b>代价</b>：首次通过率、模型调用次数、耗时；以及"多笔样本里最终能直接落库的笔数"</li>
 * </ol>
 *
 * <p>金额与方向是唯一"错一个字就变成另一笔账"的字段，所以逐笔比对只对它们做；
 * 时间短语与商户只并列展示——多笔句里各笔的时间归属人工也难判到逐字，自动判分会自造误差
 * （与 {@code p2-experiment} 对时间列的处理保持一致）。
 */
public final class ChatBatchMain {

    /** 产品 DeepSeek 默认模型：跑它等于跑产品真实链路 */
    private static final String TEXT_MODEL = "deepseek-chat";
    private static final Path OUT_DIR = Paths.get("D:/HuiJi/chat-batch-experiment/results");
    private static final int MAX_ATTEMPTS = 2;

    static final String ROUTE_S0 = "S0 现状（单笔工具 + 只报笔数）";
    static final String ROUTE_A = "A 批量工具（数组输出）";
    static final String ROUTE_B = "B 两步（先单笔，多笔时补一次批量）";

    public static void main(String[] args) throws Exception {
        String key = Env.deepSeekKey();
        if (key == null) {
            System.err.println("local.properties 里没有 wisebook.deepseek.key，无法实验");
            return;
        }

        List<Samples.Sample> samples = Samples.all();
        System.out.println("samples=" + samples.size() + " routes=3 model=" + TEXT_MODEL);

        List<Row> rows = new ArrayList<>();
        for (Samples.Sample sample : samples) {
            rows.add(printAndKeep(runSingle(sample, key), "S0", rows.size()));
        }
        for (Samples.Sample sample : samples) {
            rows.add(printAndKeep(runBatch(sample, key), "A", rows.size()));
        }
        for (Samples.Sample sample : samples) {
            rows.add(printAndKeep(runTwoStep(sample, key), "B", rows.size()));
        }

        Files.createDirectories(OUT_DIR);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path report = OUT_DIR.resolve("run-" + stamp + ".md");
        Path detail = OUT_DIR.resolve("run-" + stamp + ".json");
        Files.writeString(report, renderMarkdown(rows), StandardCharsets.UTF_8);
        Files.writeString(detail, renderJson(rows), StandardCharsets.UTF_8);

        System.out.println("report=" + report);
        System.out.println("detail=" + detail);
        for (String route : new String[]{ROUTE_S0, ROUTE_A, ROUTE_B}) {
            System.out.println(asciiSummary(route, rows));
        }
    }

    /** 控制台只打印 ASCII：PowerShell 默认代码页会让中文输出变乱码，中文都在报告文件里 */
    private static Row printAndKeep(Row row, String routeCode, int done) {
        System.out.println("[" + (done + 1) + "] " + routeCode + " " + row.brief());
        return row;
    }

    // ------------------------------------------------------------------ 三种策略

    /**
     * S0：产品现状。单笔工具 + 单笔 prompt，模型只报 {@code transactionCount}，
     * <b>系统最终只能落一笔</b>（产品 {@code DraftRepository.submit} 就是只 persist 第一张）。
     */
    private static Row runSingle(Samples.Sample sample, String key) {
        Row row = new Row(ROUTE_S0, sample, TEXT_MODEL);
        long start = System.currentTimeMillis();
        try {
            ExtractionResult result = extractor(key)
                    .extract(ExpPrompt.SINGLE, sample.text, ExpSchema.single());
            row.fillExtraction(result);
            if (result.isOk()) {
                row.entries.add(entryOf(result.payload()));
                row.reportedCount = readTransactionCount(result.payload());
            }
        } catch (RuntimeException e) {
            row.fail("未预期的异常：" + e);
        }
        row.millis = System.currentTimeMillis() - start;
        row.evaluate();
        return row;
    }

    /** A：批量工具 + 覆写【多笔】段的 prompt，一次拆出 N 笔 */
    private static Row runBatch(Samples.Sample sample, String key) {
        Row row = new Row(ROUTE_A, sample, TEXT_MODEL);
        long start = System.currentTimeMillis();
        try {
            ExtractionResult result = extractor(key)
                    .extract(ExpPrompt.BATCH, sample.text, ExpSchema.batch());
            row.fillExtraction(result);
            if (result.isOk()) {
                for (Entry entry : entriesOf(result.payload())) {
                    row.entries.add(entry);
                }
                row.reportedCount = row.entries.size();
            }
        } catch (RuntimeException e) {
            row.fail("未预期的异常：" + e);
        }
        row.millis = System.currentTimeMillis() - start;
        row.evaluate();
        return row;
    }

    /**
     * B：两步。先按 S0 问一次；只有它报出 &gt; 1 笔时才补一次批量调用。
     *
     * <p>它要回答的问题是："绝大多数句子只有一笔，能不能不为少数多笔句子付代价。"
     * 所以索引里最重要的两项是<b>第二步触发率</b>与<b>多笔句的两步总耗时</b>。
     */
    private static Row runTwoStep(Samples.Sample sample, String key) {
        Row first = runSingle(sample, key);
        Row row = new Row(ROUTE_B, sample, TEXT_MODEL);
        row.absorb(first);

        if (first.degraded) {
            row.degraded = true;
            row.degradeReason = first.degradeReason;
            row.evaluate();
            return row;
        }

        if (first.reportedCount <= 1) {
            // 单笔（绝大多数句子）：直接沿用第一步的结果，没有额外代价
            row.payload = first.payload;
            row.entries.addAll(first.entries);
            row.reportedCount = 1;
            row.evaluate();
            return row;
        }

        row.secondStep = true;
        Row second = runBatch(sample, key);
        row.absorb(second);
        if (second.degraded) {
            // 第二步失败就退回第一步：宁可用"只落一笔"的现状，也不要两手空空
            row.secondStepFailed = true;
            row.payload = first.payload;
            row.entries.addAll(first.entries);
            row.reportedCount = 1;
        } else {
            row.payload = second.payload;
            row.entries.addAll(second.entries);
            row.reportedCount = second.reportedCount;
        }
        row.evaluate();
        return row;
    }

    private static StructuredExtractor extractor(String key) {
        LlmClient client = new OpenAiCompatibleLlmClient(LlmModelConfig.deepSeek(key, TEXT_MODEL));
        return new StructuredExtractor(client, MAX_ATTEMPTS);
    }

    // ------------------------------------------------------------------ 结果模型

    /** 归一化后的一笔"能落成账目的东西" */
    static final class Entry {

        final Long amountCents;
        final String direction;
        final String occurredAtText;
        final String merchant;

        Entry(Long amountCents, String direction, String occurredAtText, String merchant) {
            this.amountCents = amountCents;
            this.direction = direction;
            this.occurredAtText = occurredAtText;
            this.merchant = merchant;
        }
    }

    private static final class Row {

        final String route;
        final Samples.Sample sample;
        final String model;

        boolean degraded;
        String degradeReason;
        String failure;
        int attempts;
        boolean firstPass;
        List<ExtractionAttempt> attemptRecords = new ArrayList<>();
        JsonObject payload;
        final List<Entry> entries = new ArrayList<>();
        /** 该策略"认定"的笔数：S0 = transactionCount，A = 数组长度，B = 最终采用者 */
        int reportedCount;
        boolean secondStep;
        /** 第二步（批量调用）失败、退回了第一步结果——必须如实记下，否则报告会把失败藏起来 */
        boolean secondStepFailed;
        boolean absorbedOnce;
        long millis;

        // 评估（null 表示该样本上不适用/无法判定，不计入分母）
        Boolean countHit;
        boolean overSplit;
        boolean underSplit;
        Boolean firstAmountHit;
        Boolean firstDirectionHit;
        Boolean amountAllHit;
        Boolean directionAllHit;

        Row(String route, Samples.Sample sample, String model) {
            this.route = route;
            this.sample = sample;
            this.model = model;
        }

        void fillExtraction(ExtractionResult result) {
            this.attempts = result.attemptCount();
            this.firstPass = result.firstAttemptSucceeded();
            this.attemptRecords = result.attempts();
            if (result.isOk()) {
                this.payload = result.payload();
            } else {
                this.degraded = true;
                this.degradeReason = result.degradeReason();
            }
        }

        /**
         * 把 B 的两步合并成一行的口径：调用次数相加、耗时相加、两步都首次通过才算首次通过。
         *
         * <p><b>刻意不带 {@code degraded}</b>：B 的"最终结局"与"某一步失败"是两件事
         * ——第二步失败时会退回第一步的结果，此时这一行不该被标成降级，
         * 但要如实记下 {@link #secondStepFailed}，否则报告会把失败藏起来。
         */
        void absorb(Row other) {
            this.attempts += other.attempts;
            this.firstPass = absorbedOnce ? (this.firstPass && other.firstPass) : other.firstPass;
            this.absorbedOnce = true;
            this.attemptRecords.addAll(other.attemptRecords);
            this.millis += other.millis;
        }

        void fail(String reason) {
            this.degraded = true;
            this.degradeReason = reason;
        }

        void evaluate() {
            int expected = sample.expectedCount();
            countHit = reportedCount == expected;
            overSplit = reportedCount > expected;
            underSplit = reportedCount < expected;

            if (!entries.isEmpty()) {
                firstAmountHit = sample.amountMatches(0, entries.get(0).amountCents);
                firstDirectionHit = sample.truths.get(0).direction.equals(entries.get(0).direction);
            }
            // 长度不一致时逐笔比对没有意义（会与"笔数错"重复计一次），所以空着
            if (entries.size() == expected) {
                boolean amountOk = true;
                boolean directionOk = true;
                for (int i = 0; i < expected; i++) {
                    if (!sample.amountMatches(i, entries.get(i).amountCents)) {
                        amountOk = false;
                    }
                    if (!sample.truths.get(i).direction.equals(entries.get(i).direction)) {
                        directionOk = false;
                    }
                }
                amountAllHit = amountOk;
                directionAllHit = directionOk;
            }
        }

        /** 控制台一行，纯 ASCII */
        String brief() {
            StringBuilder text = new StringBuilder();
            text.append(sample.id).append(" count=").append(reportedCount)
                    .append('/').append(sample.expectedCount())
                    .append(" firstAmt=").append(firstAmountHit)
                    .append(" calls=").append(attempts)
                    .append(" ms=").append(millis);
            if (degraded) {
                text.append(" DEGRADED");
            }
            if (secondStep) {
                text.append(secondStepFailed ? " twoStep(failed)" : " twoStep");
            }
            return text.toString();
        }
    }

    // ------------------------------------------------------------------ 汇总

    private static final class Stats {

        int count = 0;
        int countHit = 0;
        int overSplit = 0;
        int underSplit = 0;
        int firstAmountHit = 0;
        int firstDirectionHit = 0;
        int allCompared = 0;
        int amountAllHit = 0;
        int directionAllHit = 0;
        int firstPass = 0;
        int degraded = 0;
        int totalAttempts = 0;
        long totalMillis = 0L;
        int secondStep = 0;
        int secondStepFailed = 0;
        /** 多笔样本（真值 &gt; 1 笔）里"最终能直接落库的笔数"合计，与期望合计对比 */
        int multiLanded = 0;
        int multiExpected = 0;

        void add(Row row) {
            count++;
            if (Boolean.TRUE.equals(row.countHit)) {
                countHit++;
            }
            if (row.overSplit) {
                overSplit++;
            }
            if (row.underSplit) {
                underSplit++;
            }
            if (Boolean.TRUE.equals(row.firstAmountHit)) {
                firstAmountHit++;
            }
            if (Boolean.TRUE.equals(row.firstDirectionHit)) {
                firstDirectionHit++;
            }
            if (row.amountAllHit != null) {
                allCompared++;
                if (row.amountAllHit) {
                    amountAllHit++;
                }
            }
            if (Boolean.TRUE.equals(row.directionAllHit)) {
                directionAllHit++;
            }
            if (row.firstPass) {
                firstPass++;
            }
            if (row.degraded) {
                degraded++;
            }
            totalAttempts += row.attempts;
            totalMillis += row.millis;
            if (row.secondStep) {
                secondStep++;
            }
            if (row.secondStepFailed) {
                secondStepFailed++;
            }
            if (row.sample.isMulti()) {
                multiExpected += row.sample.expectedCount();
                multiLanded += row.entries.size();
            }
        }
    }

    private static Stats statsOf(List<Row> rows, String route) {
        Stats stats = new Stats();
        for (Row row : rows) {
            if (row.route.equals(route)) {
                stats.add(row);
            }
        }
        return stats;
    }

    private static String asciiSummary(String route, List<Row> rows) {
        Stats s = statsOf(rows, route);
        return "route=" + route.substring(0, 2)
                + " countHit=" + s.countHit + "/" + s.count
                + " overSplit=" + s.overSplit
                + " underSplit=" + s.underSplit
                + " amountAll=" + s.amountAllHit + "/" + s.allCompared
                + " calls=" + s.totalAttempts
                + " avgMs=" + (s.count == 0 ? 0 : s.totalMillis / s.count)
                + " degraded=" + s.degraded;
    }

    // ------------------------------------------------------------------ 渲染

    private static String renderMarkdown(List<Row> rows) {
        StringBuilder out = new StringBuilder();
        out.append("# 对话多笔录入策略实验 · 运行结果\n\n");
        out.append("> 本文件由 `chat-batch-experiment` 生成，属 AI 产物区，不进产品代码。\n\n");
        out.append("- 运行时间：").append(LocalDateTime.now()).append('\n');
        out.append("- 模型：`").append(TEXT_MODEL).append("`（产品 DeepSeek 默认模型，即产品真实链路）\n");
        out.append("- 结构化重试：产品 `StructuredExtractor`，上限 ").append(MAX_ATTEMPTS).append(" 次（含修正型重试）\n");
        out.append("- 样本：").append(Samples.all().size()).append(" 句（单笔 7 / 多笔 7 / 陷阱 1，另含单笔陷阱 1）\n");
        out.append("- 三条策略：\n");
        out.append("  - `S0` 现状：单笔工具 + 单笔 prompt（只报 `transactionCount`），系统最终只落一笔\n");
        out.append("  - `A` 批量：批量工具（`drafts` 数组）+ 覆写【多笔】段的 prompt\n");
        out.append("  - `B` 两步：先 S0，报出 >1 笔时才补一次批量调用\n");
        out.append('\n');

        out.append("## 一、逐样本结果\n\n");
        out.append("| 策略 | 样本 | 类型 | 原话 | 笔数 期望→认定 | 过拆 | 漏拆 | 首笔金额 | 逐笔金额 | 逐笔方向 | 调用 | 用时(ms) |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Row row : rows) {
            out.append("| ").append(row.route.substring(0, 2))
                    .append(" | ").append(row.sample.id)
                    .append(" | ").append(row.sample.kind)
                    .append(" | ").append(row.sample.text)
                    .append(" | ").append(row.sample.expectedCount()).append(" → ").append(row.reportedCount)
                    .append(" | ").append(row.overSplit ? "过拆" : "")
                    .append(" | ").append(row.underSplit ? "漏拆" : "")
                    .append(" | ").append(mark(row.firstAmountHit))
                    .append(" | ").append(mark(row.amountAllHit))
                    .append(" | ").append(mark(row.directionAllHit))
                    .append(" | ").append(row.attempts)
                    .append(" | ").append(row.millis)
                    .append(" |\n");
        }
        out.append('\n');

        out.append("## 二、汇总\n\n");
        out.append("| 策略 | 笔数全对 | 过拆 | 漏拆 | 首笔金额对 | 逐笔金额全对 | 逐笔方向全对 | 首次通过 | 降级 | 总调用 | 平均用时(ms) | 第二步触发 | 多笔可落笔数 |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (String route : new String[]{ROUTE_S0, ROUTE_A, ROUTE_B}) {
            Stats s = statsOf(rows, route);
            out.append("| ").append(route.substring(0, 2))
                    .append(" | ").append(s.countHit).append('/').append(s.count)
                    .append(" | ").append(s.overSplit)
                    .append(" | ").append(s.underSplit)
                    .append(" | ").append(s.firstAmountHit).append('/').append(s.count)
                    .append(" | ").append(s.amountAllHit).append('/').append(s.allCompared)
                    .append(" | ").append(s.directionAllHit).append('/').append(s.allCompared)
                    .append(" | ").append(s.firstPass).append('/').append(s.count)
                    .append(" | ").append(s.degraded)
                    .append(" | ").append(s.totalAttempts)
                    .append(" | ").append(s.count == 0 ? 0 : s.totalMillis / s.count)
                    .append(" | ").append(s.secondStep)
                    .append(s.secondStepFailed > 0 ? "（失败 " + s.secondStepFailed + "）" : "")
                    .append(" | ").append(s.multiLanded).append('/').append(s.multiExpected)
                    .append(" |\n");
        }
        out.append('\n');
        out.append("> 「多笔可落笔数」= 真值多于一笔的样本上，系统最终能直接落进账本的笔数合计 / 期望合计。\n");
        out.append("> S0 每条多笔句都只能落 1 笔，剩下的必须由用户在确认页手填——这一列就是它的全部代价。\n\n");

        out.append("## 三、多笔样本拆分对照\n\n");
        for (Row row : rows) {
            if (!row.sample.isMulti()) {
                continue;
            }
            out.append("### ").append(row.sample.id).append(" · ").append(row.route)
                    .append("（").append(row.sample.text).append("）\n\n");
            out.append("- 真值：").append(entriesText(row, true)).append('\n');
            out.append("- 实际：").append(entriesText(row, false)).append('\n');
            out.append("- 原话拆分：").append(row.sample.note).append('\n');
            if (row.secondStep) {
                out.append("- 该样本触发了第二步").append(row.secondStepFailed ? "（失败，已退回第一步）" : "").append('\n');
            }
            out.append('\n');
        }

        out.append("## 四、陷阱样本\n\n");
        for (Row row : rows) {
            if (!"s06".equals(row.sample.id) && !"m08".equals(row.sample.id)) {
                continue;
            }
            out.append("- **").append(row.sample.id).append(" · ").append(row.route).append("**：")
                    .append(row.sample.text).append(" → 认定 ").append(row.reportedCount)
                    .append(" 笔（期望 ").append(row.sample.expectedCount()).append("）")
                    .append(row.overSplit ? "，**过拆**" : "")
                    .append(row.underSplit ? "，漏拆" : "")
                    .append("；实际读出 ").append(entriesText(row, false)).append('\n');
        }
        out.append('\n');

        out.append("## 五、尝试明细（回答「为什么重试」）\n\n");
        for (Row row : rows) {
            out.append("- **").append(row.sample.id).append(" · ").append(row.route.substring(0, 2)).append("**：");
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

        out.append("## 六、模型原始返回\n\n");
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

    private static String entriesText(Row row, boolean truth) {
        StringBuilder text = new StringBuilder();
        int count = truth ? row.sample.expectedCount() : row.entries.size();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                text.append(" ； ");
            }
            if (truth) {
                Samples.Truth item = row.sample.truths.get(i);
                text.append(amount(item.amountCents)).append(' ').append(item.direction);
                if (item.occurredAtText != null) {
                    text.append(" [").append(item.occurredAtText).append(']');
                }
            } else {
                Entry item = row.entries.get(i);
                text.append(amount(item.amountCents)).append(' ')
                        .append(item.direction == null ? "（未填方向）" : item.direction);
                if (item.occurredAtText != null) {
                    text.append(" [").append(item.occurredAtText).append(']');
                }
            }
        }
        if (!truth && row.entries.isEmpty()) {
            text.append(row.degraded ? "（降级：" + row.degradeReason + "）" : "（未读出）");
        }
        return text.toString();
    }

    private static String renderJson(List<Row> rows) {
        JsonArray array = new JsonArray();
        for (Row row : rows) {
            JsonObject item = new JsonObject();
            item.addProperty("route", row.route);
            item.addProperty("sample", row.sample.id);
            item.addProperty("kind", row.sample.kind);
            item.addProperty("text", row.sample.text);
            item.addProperty("model", row.model);
            item.addProperty("expectedCount", row.sample.expectedCount());
            item.addProperty("reportedCount", row.reportedCount);
            item.addProperty("attempts", row.attempts);
            item.addProperty("firstPass", row.firstPass);
            item.addProperty("secondStep", row.secondStep);
            item.addProperty("secondStepFailed", row.secondStepFailed);
            item.addProperty("millis", row.millis);
            if (row.degradeReason != null) {
                item.addProperty("degradeReason", row.degradeReason);
            }
            JsonArray attemptArray = new JsonArray();
            for (ExtractionAttempt attempt : row.attemptRecords) {
                JsonObject record = new JsonObject();
                record.addProperty("index", attempt.index());
                record.addProperty("kind", attempt.kind().name());
                record.addProperty("detail", attempt.detail());
                attemptArray.add(record);
            }
            item.add("attemptRecords", attemptArray);

            JsonArray got = new JsonArray();
            for (Entry entry : row.entries) {
                JsonObject one = new JsonObject();
                if (entry.amountCents == null) {
                    one.add("amountCents", com.google.gson.JsonNull.INSTANCE);
                } else {
                    one.addProperty("amountCents", entry.amountCents);
                }
                if (entry.direction == null) {
                    one.add("direction", com.google.gson.JsonNull.INSTANCE);
                } else {
                    one.addProperty("direction", entry.direction);
                }
                if (entry.occurredAtText == null) {
                    one.add("occurredAtText", com.google.gson.JsonNull.INSTANCE);
                } else {
                    one.addProperty("occurredAtText", entry.occurredAtText);
                }
                if (entry.merchant == null) {
                    one.add("merchant", com.google.gson.JsonNull.INSTANCE);
                } else {
                    one.addProperty("merchant", entry.merchant);
                }
                got.add(one);
            }
            item.add("entries", got);

            JsonArray truthArray = new JsonArray();
            for (Samples.Truth truth : row.sample.truths) {
                JsonObject one = new JsonObject();
                one.addProperty("amountCents", truth.amountCents);
                one.addProperty("direction", truth.direction);
                if (truth.occurredAtText == null) {
                    one.add("occurredAtText", com.google.gson.JsonNull.INSTANCE);
                } else {
                    one.addProperty("occurredAtText", truth.occurredAtText);
                }
                truthArray.add(one);
            }
            item.add("truth", truthArray);

            JsonObject hit = new JsonObject();
            put(hit, "countHit", row.countHit);
            hit.addProperty("overSplit", row.overSplit);
            hit.addProperty("underSplit", row.underSplit);
            put(hit, "firstAmountHit", row.firstAmountHit);
            put(hit, "firstDirectionHit", row.firstDirectionHit);
            put(hit, "amountAllHit", row.amountAllHit);
            put(hit, "directionAllHit", row.directionAllHit);
            item.add("hit", hit);

            if (row.payload != null) {
                item.add("payload", row.payload);
            }
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

    private static String amount(Long cents) {
        if (cents == null) {
            return "（未填金额）";
        }
        return String.format(java.util.Locale.CHINA, "%d.%02d 元", cents / 100, Math.abs(cents % 100));
    }

    // ------------------------------------------------------------------ 读取辅助

    /** S0 的笔数口径：`transactionCount`，非法值按产品口径当 1 */
    private static int readTransactionCount(JsonObject payload) {
        Long value = readLong(payload, "transactionCount");
        if (value == null || value < 1L || value > 20L) {
            return 1;
        }
        return value.intValue();
    }

    private static List<Entry> entriesOf(JsonObject payload) {
        List<Entry> entries = new ArrayList<>();
        if (payload == null) {
            return entries;
        }
        JsonElement element = payload.get(ExpSchema.FIELD_DRAFTS);
        if (element == null || !element.isJsonArray()) {
            return entries;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonObject()) {
                entries.add(entryOf(item.getAsJsonObject()));
            }
        }
        return entries;
    }

    private static Entry entryOf(JsonObject payload) {
        if (payload == null) {
            return new Entry(null, null, null, null);
        }
        return new Entry(readLong(payload, "amountCents"), readString(payload, "direction"),
                readString(payload, "occurredAtText"), readString(payload, "merchant"));
    }

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
}
