package com.wisebook.app.domain.time;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.wisebook.app.domain.model.OccurredAtSource;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 口语时间解析（D1 §3.6）。
 *
 * <p>参照时间写死成 <b>2026-09-24 20:30（周四）</b>，
 * 让「上周一」这类相对表达的结果一旦算错就立刻暴露，而不是随运行时刻漂移。
 */
public class RelativeTimeResolverTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime REF = LocalDateTime.of(2026, 9, 24, 20, 30);

    private static RelativeTimeResolver.Result resolve(String phrase) {
        return RelativeTimeResolver.resolve(phrase, REF, ZONE);
    }

    private static LocalDateTime at(String phrase) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(resolve(phrase).occurredAtMillis), ZONE);
    }

    /** 换一个参照时间算——「10月」这类断言用 {@link #REF} 会撞上"未来当去年"的规则 */
    private static LocalDateTime at(String phrase, LocalDateTime reference) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(
                        RelativeTimeResolver.resolve(phrase, reference, ZONE).occurredAtMillis),
                ZONE);
    }

    // ------------------------------------------------------------ 兜底

    @Test
    public void blankInputFallsBackToNow() {
        assertEquals(REF, at(""));
        assertEquals(REF, at(null));
        assertEquals(REF, at("   "));
        assertEquals(OccurredAtSource.FALLBACK, resolve("").source);
    }

    @Test
    public void unrecognizedTextFallsBackToNow() {
        assertEquals(REF, at("随便吧"));
        assertEquals(OccurredAtSource.FALLBACK, resolve("随便吧").source);
    }

    // ------------------------------------------------------------ 相对日

    @Test
    public void todayKeepsReferenceClock() {
        // 「今天」没说几点，就沿用当前时刻——比强行落到 00:00 更符合直觉
        assertEquals(LocalDateTime.of(2026, 9, 24, 20, 30), at("今天"));
        assertEquals(OccurredAtSource.EXPLICIT, resolve("今天").source);
    }

    @Test
    public void relativeDays() {
        assertEquals(LocalDateTime.of(2026, 9, 23, 20, 30), at("昨天"));
        assertEquals(LocalDateTime.of(2026, 9, 22, 20, 30), at("前天"));
        assertEquals(LocalDateTime.of(2026, 9, 21, 20, 30), at("大前天"));
        assertEquals(LocalDateTime.of(2026, 9, 25, 20, 30), at("明天"));
        assertEquals(LocalDateTime.of(2026, 9, 26, 20, 30), at("后天"));
        assertEquals(LocalDateTime.of(2026, 9, 27, 20, 30), at("大后天"));
    }

    @Test
    public void dayPlusPeriodInOneWord() {
        // 「今晚」「昨晚」「今早」是一个词里同时含日期和时段
        assertEquals(LocalDateTime.of(2026, 9, 24, 20, 0), at("今晚"));
        assertEquals(LocalDateTime.of(2026, 9, 23, 20, 0), at("昨晚"));
        assertEquals(LocalDateTime.of(2026, 9, 24, 8, 0), at("今早"));
    }

    // ------------------------------------------------------------ 周内

    @Test
    public void weekdayWithoutPrefixMeansThisWeek() {
        assertEquals(LocalDateTime.of(2026, 9, 23, 20, 30), at("周三"));
        assertEquals(LocalDateTime.of(2026, 9, 23, 20, 30), at("星期三"));
        assertEquals(LocalDateTime.of(2026, 9, 23, 20, 30), at("本周三"));
        assertEquals(LocalDateTime.of(2026, 9, 23, 20, 30), at("这周三"));
    }

    @Test
    public void lastWeekAndNextWeek() {
        assertEquals(LocalDateTime.of(2026, 9, 14, 20, 30), at("上周一"));
        assertEquals(LocalDateTime.of(2026, 9, 13, 20, 30), at("上上周日"));
        assertEquals(LocalDateTime.of(2026, 9, 29, 20, 30), at("下周二"));
    }

    @Test
    public void weekdayWithExplicitPeriod() {
        assertEquals(LocalDateTime.of(2026, 9, 23, 20, 0), at("周三晚上"));
    }

    // ------------------------------------------------------------ 月日

    @Test
    public void monthDayWithinThisYear() {
        assertEquals(LocalDateTime.of(2026, 9, 5, 20, 30), at("9月5号"));
        assertEquals(LocalDateTime.of(2026, 9, 5, 20, 30), at("九月五号"));
    }

    @Test
    public void monthDayInTheFutureMeansLastYear() {
        // 记账记的是已经花掉的钱，算出未来就当是去年
        assertEquals(LocalDateTime.of(2025, 12, 31, 20, 30), at("12月31号"));
    }

    // ------------------------------------------------------------ 时刻

    @Test
    public void colonTime() {
        assertEquals(LocalDateTime.of(2026, 9, 24, 15, 30), at("15:30"));
        assertEquals(OccurredAtSource.INFERRED, resolve("15:30").source);
    }

    @Test
    public void hourWithChineseNumeral() {
        assertEquals(LocalDateTime.of(2026, 9, 24, 9, 0), at("上午九点"));
        assertEquals(LocalDateTime.of(2026, 9, 24, 12, 0), at("中午十二点"));
    }

    @Test
    public void halfPastAndMinutes() {
        assertEquals(LocalDateTime.of(2026, 9, 24, 15, 30), at("下午3点半"));
        assertEquals(LocalDateTime.of(2026, 9, 24, 20, 20), at("晚上8点20分"));
        assertEquals(LocalDateTime.of(2026, 9, 24, 20, 45), at("晚上八点四十五"));
    }

    @Test
    public void afternoonPeriodShiftsTwelveHourClock() {
        assertEquals(LocalDateTime.of(2026, 9, 24, 15, 0), at("下午3点"));
        assertEquals(LocalDateTime.of(2026, 9, 24, 8, 0), at("早上8点"));
        assertEquals(LocalDateTime.of(2026, 9, 24, 0, 0), at("凌晨12点"));
        assertEquals(LocalDateTime.of(2026, 9, 24, 23, 0), at("夜里11点"));
    }

    @Test
    public void periodOnlyUsesDefaultHour() {
        assertEquals(LocalDateTime.of(2026, 9, 24, 15, 0), at("下午"));
        assertEquals(LocalDateTime.of(2026, 9, 24, 12, 0), at("中午"));
        assertEquals(OccurredAtSource.INFERRED, resolve("下午").source);
    }

    // ------------------------------------------------------------ 组合

    @Test
    public void dayPlusPeriodPlusHour() {
        // D1 §3.6 的那个例子：今晚录入「昨天下午打车」
        assertEquals(LocalDateTime.of(2026, 9, 23, 15, 0), at("昨天下午3点"));
        assertEquals(OccurredAtSource.EXPLICIT, resolve("昨天下午3点").source);
        assertEquals(LocalDateTime.of(2026, 9, 23, 15, 0), at("昨天下午3点"));
    }

    @Test
    public void fuzzyWordsUseReferenceTime() {
        assertEquals(REF, at("刚才"));
        assertEquals(REF, at("刚刚"));
        assertEquals(OccurredAtSource.INFERRED, resolve("刚才").source);
    }

    // ------------------------------------------------------------ 片段记录

    @Test
    public void matchedTextIsRecordedForTroubleshooting() {
        assertEquals("昨天", resolve("昨天").matchedText);
        assertEquals("今早", resolve("今早").matchedText);
        assertEquals("没命中任何片段时为空串", "", resolve("").matchedText);

        // 多个片段全部记下，便于看出结果是怎么拼出来的；
        // 这里只断言「都记到了」而不断言顺序，免得以后调整解析顺序时测假失败
        String matched = resolve("昨天下午3点").matchedText;
        assertTrue(matched.contains("昨天"));
        assertTrue(matched.contains("下午"));
        assertTrue(matched.contains("3点"));
    }

    // ------------------------------------------------------------ 相对月

    /**
     * 「上个月花了 50 块」这类说法。
     *
     * <p>2026-10-07 补：在那之前「上个月」不在覆盖范围内，会落到 fallback 用当前时间，
     * 于是上个月花的钱被记到了本月。用 {@code REF} 的偏移做断言，不写死具体日期，
     * 免得以后换参照时间还要改一遍测试。
     */
    @Test
    public void relativeMonthMovesByWholeMonths() {
        // 只说了"哪个月"、没说哪天，取那个月的同一天；时刻沿用参照时间
        assertEquals(REF.minusMonths(1), at("上个月"));
        assertEquals(REF.minusMonths(2), at("上上个月"));
        assertEquals(REF.plusMonths(1), at("下个月"));
        assertEquals(REF.minusMonths(1), at("上月"));
        assertEquals(OccurredAtSource.EXPLICIT, resolve("上个月").source);
        assertEquals("上个月", resolve("上个月").matchedText);
    }

    @Test
    public void relativeMonthCombinesWithTimeOfDay() {
        assertEquals(REF.minusMonths(1).withHour(15).withMinute(0), at("上个月下午3点"));
    }

    /** 往回一个月时"那天不存在"（3 月 31 日 → 2 月）不该抛异常，按 java.time 的规则落到月末 */
    @Test
    public void monthOverflowFallsBackToMonthEnd() {
        LocalDateTime march31 = LocalDateTime.of(2026, 3, 31, 10, 0);
        RelativeTimeResolver.Result result =
                RelativeTimeResolver.resolve("上个月", march31, ZONE);
        assertEquals(LocalDateTime.of(2026, 2, 28, 10, 0),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(result.occurredAtMillis), ZONE));
    }

    /** 「9月5号」不能被相对月的规则抢走：它没有"上/这/下"前缀 */
    @Test
    public void monthDayIsNotHijackedByRelativeMonth() {
        assertEquals(LocalDateTime.of(2026, 9, 5, 0, 0), at("9月5号").withHour(0).withMinute(0));
    }

    // ------------------------------------------------------------ 账单版面（2026-10-08）

    /**
     * 账单截图的分组标题写成「10月1日 星期四」时，要按月日算，不能按星期算。
     *
     * <p>2026-10-08 修：在那之前 WEEKDAY 排在 MONTH_DAY 前面，而它的前缀
     * （这/本/上/下）本来就是可选的——「星期四」被它当成「本周四」抢走，
     * 明确写着的月日再没机会命中，截图整批账目的日期会整体偏掉一周。
     *
     * <p>参照日换成 10 月，是因为用 {@link #REF}（9月24日）时「10月1日」落在未来，
     * 会撞上「算出来在未来就当去年」那条规则，断言就得写成去年、读不出本意。
     */
    @Test
    public void monthDayWinsOverWeekdayInBillHeader() {
        LocalDateTime october = LocalDateTime.of(2026, 10, 8, 20, 30);
        assertEquals(LocalDateTime.of(2026, 10, 1, 20, 30), at("10月1日 星期四", october));
        assertEquals(OccurredAtSource.EXPLICIT,
                RelativeTimeResolver.resolve("10月1日 星期四", october, ZONE).source);

        // 只有星期时照旧走周内，不能被这次顺序调整带跑
        assertEquals(LocalDateTime.of(2026, 10, 7, 20, 30), at("星期三", october));
    }

    /** 分组标题的日期 + 行内时刻（截图下推之后模型抄下来的就是这个形状） */
    @Test
    public void billHeaderDateCombinesWithRowClock() {
        LocalDateTime october = LocalDateTime.of(2026, 10, 8, 20, 30);
        assertEquals(LocalDateTime.of(2026, 10, 1, 18, 34), at("10月1日 18:34", october));
    }
}
