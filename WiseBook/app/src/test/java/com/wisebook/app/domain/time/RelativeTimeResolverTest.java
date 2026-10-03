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
}
