package com.wisebook.money;

import org.junit.AfterClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.fail;

/**
 * 金额规则用例集（Java 版）。
 *
 * <p>除 JUnit 断言外，本类还会把一份完整的中文报告写入
 * {@code build/reports/amount-rules.txt}：
 * <ul>
 *   <li>JUnit 断言只保证失败能被捕捉</li>
 *   <li>但「通过数」掩盖不了「输出文案写错」这类问题，所以报告必须逐行可读</li>
 * </ul>
 *
 * <p>已知未覆盖（详见 D1-A §5.3 / §7）：
 * <ul>
 *   <li>方言约数词：块把、块出头、约莫</li>
 *   <li>「三四块钱」这类区间后缀带「钱」的写法（「三四块」已支持）</li>
 *   <li>「一百二三」这类省略一个数量级的表达（当前按 130 解析）</li>
 * </ul>
 */
public class AmountRuleTest {

    // ------------------------------------------------------------ 用例结构

    private static final class ExactCase {
        final String input;
        final long cents;
        final String display;

        ExactCase(String input, long cents, String display) {
            this.input = input;
            this.cents = cents;
            this.display = display;
        }
    }

    private static final class RangeCase {
        final String input;
        final long lower;
        final long upper;
        final long cents;
        final String display;

        RangeCase(String input, long lower, long upper, long cents, String display) {
            this.input = input;
            this.lower = lower;
            this.upper = upper;
            this.cents = cents;
            this.display = display;
        }
    }

    private static final class CheckCase {
        final String name;
        final Long modelCents;
        final String rawSpan;
        final String expect;

        CheckCase(String name, Long modelCents, String rawSpan, String expect) {
            this.name = name;
            this.modelCents = modelCents;
            this.rawSpan = rawSpan;
            this.expect = expect;
        }
    }

    private static final class RiskCase {
        final String name;
        final String text;
        final boolean fromVoice;
        final Set<AmountRisk> expect;

        RiskCase(String name, String text, boolean fromVoice, AmountRisk... expect) {
            this.name = name;
            this.text = text;
            this.fromVoice = fromVoice;
            this.expect = expect.length == 0
                    ? EnumSet.noneOf(AmountRisk.class)
                    : EnumSet.copyOf(Arrays.asList(expect));
        }
    }

    // ------------------------------------------------------------ 用例数据

    /** 精确金额 */
    private static final List<ExactCase> EXACT_CASES = Arrays.asList(
            new ExactCase("三十五块", 3500, "35"),
            new ExactCase("三百五", 35000, "350"),
            new ExactCase("三百五块", 35000, "350"),
            new ExactCase("三百五十块", 35000, "350"),
            new ExactCase("三百零五", 30500, "305"),
            new ExactCase("三百五十六", 35600, "356"),
            new ExactCase("三十块五", 3050, "30.5"),
            new ExactCase("三十块钱", 3000, "30"),
            new ExactCase("五块二毛", 520, "5.2"),
            new ExactCase("五块二毛三分", 523, "5.23"),
            new ExactCase("五块半", 550, "5.5"),
            new ExactCase("半块", 50, "0.5"),
            new ExactCase("一块五", 150, "1.5"),
            new ExactCase("两块", 200, "2"),
            new ExactCase("两百五", 25000, "250"),
            new ExactCase("十块", 1000, "10"),
            new ExactCase("十五块", 1500, "15"),
            new ExactCase("十", 1000, "10"),
            new ExactCase("一千五", 150000, "1500"),
            new ExactCase("一万五", 1500000, "15000"),
            new ExactCase("一百零八", 10800, "108"),
            new ExactCase("一千零五十", 105000, "1050"),
            new ExactCase("100块", 10000, "100"),
            new ExactCase("35", 3500, "35"),
            new ExactCase("35块5", 3550, "35.5"),
            new ExactCase("五毛", 50, "0.5"),
            new ExactCase("三分", 3, "0.03")
    );

    /** 区间 / 约数金额 */
    private static final List<RangeCase> RANGE_CASES = Arrays.asList(
            // 「左右」「来」：浮动半径 = min(数量级步长/2, N/6)
            new RangeCase("三十左右", 2500, 3500, 3000, "≈30"),
            new RangeCase("差不多三十块", 2500, 3500, 3000, "≈30"),
            new RangeCase("六十左右", 5500, 6500, 6000, "≈60"),
            new RangeCase("一百左右", 8334, 11666, 10000, "≈100"),
            new RangeCase("三百左右", 25000, 35000, 30000, "≈300"),
            new RangeCase("三十来块", 3000, 3500, 3300, "≈33"),
            // 「多」「几」：推进一个完整数量级
            new RangeCase("三百多", 30000, 40000, 35000, ">300"),
            new RangeCase("一百多块", 10000, 20000, 15000, ">100"),
            new RangeCase("一千多", 100000, 200000, 150000, ">1000"),
            new RangeCase("十几块", 1000, 2000, 1500, "10~20"),
            new RangeCase("三十几", 3000, 4000, 3500, "30~40"),
            // 双数字区间：带「十」与不带「十」
            new RangeCase("五六十", 5000, 6000, 5500, "50~60"),
            new RangeCase("五六十块", 5000, 6000, 5500, "50~60"),
            new RangeCase("三四块", 300, 400, 350, "3~4"),
            new RangeCase("三五块", 300, 500, 400, "3~5"),
            new RangeCase("三五毛", 30, 50, 40, "0.3~0.5"),
            // 只有上界
            new RangeCase("不到三十", 0, 3000, 3000, "<30"),
            new RangeCase("三十以内", 0, 3000, 3000, "≤30"),
            new RangeCase("最多三十", 0, 3000, 3000, "≤30")
    );

    /** 必须反问 */
    private static final List<String> CLARIFY_CASES = Arrays.asList(
            "至少三十", "三十以上", "最少五十块", "两百往上"
    );

    /** 无法解析 */
    private static final List<String> REJECT_CASES = Arrays.asList(
            "", "   ", "abc", "块", "钱", "哈哈"
    );

    /** 双通道校验器 */
    private static final List<CheckCase> VALIDATOR_CASES = Arrays.asList(
            new CheckCase("模型与规则一致", 35000L, "三百五", "Consistent"),
            new CheckCase("模型算错金额", 30500L, "三百五", "Inconsistent"),
            new CheckCase("模型值落在约数区间内", 3200L, "三十来块", "Consistent"),
            new CheckCase("模型值超出约数区间", 9000L, "三十来块", "Inconsistent"),
            new CheckCase("模型未给金额", null, "三十块", "ModelMissing"),
            new CheckCase("规则无法解析", 3000L, "哈哈", "RuleUnparseable"),
            new CheckCase("无可信片段", 3000L, "", "RuleUnparseable"),
            new CheckCase("只有下界必须反问", 3000L, "至少三十", "NeedClarification")
    );

    /** 风险检测 */
    private static final List<RiskCase> RISK_CASES = Arrays.asList(
            new RiskCase("十四 → 四/十混淆", "十四块", true, AmountRisk.ASR_FOUR_TEN),
            new RiskCase("四十 → 四/十混淆", "四十块", true, AmountRisk.ASR_FOUR_TEN),
            new RiskCase("单个四 → 四/十混淆", "四块", true, AmountRisk.ASR_FOUR_TEN),
            new RiskCase("单个十 → 四/十混淆", "十块", true, AmountRisk.ASR_FOUR_TEN),
            new RiskCase("十五不误报", "十五块", true),
            new RiskCase("三十五不误报", "三十五块", true),
            new RiskCase("非语音入口不判 ASR 风险", "十四块", false),
            new RiskCase("数量×单价", "三杯咖啡一杯15", true, AmountRisk.QUANTITY_PRICING),
            new RiskCase("分摊", "AA 一共 200 元", true, AmountRisk.SHARED_SPLIT),
            new RiskCase("多笔输入", "早饭 8 块，打车 17", false, AmountRisk.MULTI_TRANSACTION)
    );

    // ------------------------------------------------------------ 报告累积

    private static final List<String> REPORT_LINES = new ArrayList<>();
    private static int totalCases = 0;
    private static int passedCases = 0;

    private static void tally(boolean ok) {
        totalCases++;
        if (ok) {
            passedCases++;
        }
    }

    private static void section(String title, List<String> lines, List<String> failures) {
        REPORT_LINES.add("--- " + title + " (" + lines.size() + ") ---");
        REPORT_LINES.addAll(lines);
        REPORT_LINES.add("");
        if (!failures.isEmpty()) {
            List<String> shown = failures.size() > 20 ? failures.subList(0, 20) : failures;
            fail(title + "：失败 " + failures.size() + " 条\n" + String.join("\n", shown));
        }
    }

    // ------------------------------------------------------------ 测试用例

    @Test
    public void exactAmounts() {
        List<String> lines = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int index = 0;
        for (ExactCase c : EXACT_CASES) {
            index++;
            AmountParseResult r = AmountParser.parse(c.input);
            boolean ok = r != null
                    && !r.isNeedsClarification()
                    && r.getCents() == c.cents
                    && c.display.equals(r.getDisplay())
                    && !r.isEstimated();
            String actual = r == null
                    ? "null"
                    : "cents=" + r.getCents() + " display=" + r.getDisplay();
            lines.add((ok ? "[PASS]" : "[FAIL]") + " #" + index + " 「" + c.input
                    + "」 → 期望 cents=" + c.cents + " display=" + c.display + "；实际 " + actual);
            tally(ok);
            if (!ok) {
                failures.add("#" + index + " expected=" + c.cents
                        + " actual=" + (r == null ? "null" : r.getCents()));
            }
        }
        section("精确金额", lines, failures);
    }

    @Test
    public void rangeAndApproxAmounts() {
        List<String> lines = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int index = 0;
        for (RangeCase c : RANGE_CASES) {
            index++;
            AmountParseResult r = AmountParser.parse(c.input);
            boolean ok = r != null
                    && !r.isNeedsClarification()
                    && r.getCents() == c.cents
                    && r.getLowerCents() != null && r.getLowerCents() == c.lower
                    && r.getUpperCents() != null && r.getUpperCents() == c.upper
                    && c.display.equals(r.getDisplay())
                    && r.isEstimated();
            String actual = r == null
                    ? "null"
                    : "cents=" + r.getCents() + " range=[" + r.getLowerCents()
                    + "," + r.getUpperCents() + "] display=" + r.getDisplay();
            lines.add((ok ? "[PASS]" : "[FAIL]") + " #" + index + " 「" + c.input
                    + "」 → 期望 cents=" + c.cents + " range=[" + c.lower + "," + c.upper
                    + "] display=" + c.display + "；实际 " + actual);
            tally(ok);
            if (!ok) {
                failures.add("#" + index + " expected=" + c.cents
                        + " actual=" + (r == null ? "null" : r.getCents()));
            }
        }
        section("区间 / 约数金额", lines, failures);
    }

    @Test
    public void clarifyIsRequired() {
        List<String> lines = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int index = 0;
        for (String input : CLARIFY_CASES) {
            index++;
            AmountParseResult r = AmountParser.parse(input);
            boolean ok = r != null && r.isNeedsClarification();
            lines.add((ok ? "[PASS]" : "[FAIL]") + " #" + index + " 「" + input
                    + "」 → 期望 needsClarification=true；实际="
                    + (r == null ? "null" : r.isNeedsClarification()));
            tally(ok);
            if (!ok) {
                failures.add("#" + index + " expected=clarify");
            }
        }
        section("必须反问", lines, failures);
    }

    @Test
    public void unparseableInputs() {
        List<String> lines = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int index = 0;
        for (String input : REJECT_CASES) {
            index++;
            AmountParseResult r = AmountParser.parse(input);
            boolean ok = r == null;
            lines.add((ok ? "[PASS]" : "[FAIL]") + " #" + index + " 「" + input
                    + "」 → 期望 null；实际=" + r);
            tally(ok);
            if (!ok) {
                failures.add("#" + index + " expected=null");
            }
        }
        section("无法解析", lines, failures);
    }

    @Test
    public void dualChannelCheck() {
        List<String> lines = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        AmountRuleValidator validator = new AmountRuleValidator();
        int index = 0;
        for (CheckCase c : VALIDATOR_CASES) {
            index++;
            DualCheckResult result = validator.check(c.modelCents, c.rawSpan);
            String actual = result.getClass().getSimpleName();
            boolean ok = c.expect.equals(actual);
            lines.add((ok ? "[PASS]" : "[FAIL]") + " #" + index + " " + c.name
                    + " (model=" + c.modelCents + ", raw=「" + c.rawSpan + "」) → 期望 "
                    + c.expect + "；实际 " + actual);
            tally(ok);
            if (!ok) {
                failures.add("#" + index + " expected=" + c.expect + " actual=" + actual);
            }
        }
        section("双通道校验器", lines, failures);
    }

    @Test
    public void riskDetection() {
        List<String> lines = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int index = 0;
        for (RiskCase c : RISK_CASES) {
            index++;
            Set<AmountRisk> actual = AmountRiskDetector.detect(c.text, c.fromVoice);
            boolean ok = c.expect.equals(actual);
            lines.add((ok ? "[PASS]" : "[FAIL]") + " #" + index + " " + c.name
                    + " (voice=" + c.fromVoice + ", 「" + c.text + "」) → 期望 "
                    + c.expect + "；实际 " + actual);
            tally(ok);
            if (!ok) {
                failures.add("#" + index + " expected=" + c.expect + " actual=" + actual);
            }
        }
        section("风险检测", lines, failures);
    }

    @AfterClass
    public static void writeReport() {
        List<String> out = new ArrayList<>();
        out.add("=== 慧记 金额规则用例报告（Java） ===");
        out.add("");
        out.addAll(REPORT_LINES);
        out.add("=== 总计 " + totalCases + " 条，通过 " + passedCases
                + " 条，失败 " + (totalCases - passedCases) + " 条 ===");

        Path path = Paths.get("build", "reports", "amount-rules.txt");
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, out, StandardCharsets.UTF_8);
            System.out.println("cases=" + totalCases + " passed=" + passedCases
                    + " failed=" + (totalCases - passedCases));
            System.out.println("report=" + path.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("写报告失败：" + e);
        }
    }
}
