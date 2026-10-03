package com.wisebook.app.domain.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 月份区间的边界测试。
 *
 * <p>这类"差一毫秒"的 bug 在界面上完全看不出来——报表少算一笔，
 * 用户只会觉得"记错了"，不会想到是区间端点的问题。所以这里盯死边界。
 */
public class MonthRangeTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static long millisAt(int year, int month, int day) {
        return LocalDateTime.of(year, month, day, 0, 0).atZone(SHANGHAI).toInstant().toEpochMilli();
    }

    @Test
    public void rangeStartsAtTheFirstMomentOfTheMonth() {
        MonthRange range = MonthRange.of(2026, 9, SHANGHAI);
        assertEquals(millisAt(2026, 9, 1), range.fromInclusive);
    }

    @Test
    public void rangeEndsAtTheFirstMomentOfNextMonth() {
        MonthRange range = MonthRange.of(2026, 9, SHANGHAI);
        assertEquals(millisAt(2026, 10, 1), range.toExclusive);
    }

    @Test
    public void nextMonthStartIsNotIncluded() {
        // 半开区间：下月 1 号 00:00:00 这一毫秒必须排除，
        // 否则同一条账会同时出现在两个月的报表里
        MonthRange range = MonthRange.of(2026, 9, SHANGHAI);
        assertFalse(range.contains(range.toExclusive));
    }

    @Test
    public void lastMillisecondOfTheMonthIsIncluded() {
        MonthRange range = MonthRange.of(2026, 9, SHANGHAI);
        assertTrue(range.contains(range.toExclusive - 1L));
    }

    @Test
    public void firstMomentOfTheMonthIsIncluded() {
        MonthRange range = MonthRange.of(2026, 9, SHANGHAI);
        assertTrue(range.contains(range.fromInclusive));
    }

    @Test
    public void decemberRollsOverIntoNextYear() {
        MonthRange range = MonthRange.of(2026, 12, SHANGHAI);
        assertEquals(millisAt(2027, 1, 1), range.toExclusive);
    }

    @Test
    public void februaryOfALeapYearHasTwentyNineDays() {
        // 2028 是闰年。这一条防的是"用固定 30 天推算月份长度"这类写法
        MonthRange range = MonthRange.of(2028, 2, SHANGHAI);
        long days = (range.toExclusive - range.fromInclusive) / (24L * 60L * 60L * 1000L);
        assertEquals(29L, days);
    }

    @Test
    public void currentMonthContainsNow() {
        MonthRange range = MonthRange.current(SHANGHAI);
        assertTrue(range.contains(System.currentTimeMillis()));
    }

    @Test
    public void labelIsReadable() {
        assertEquals("2026 年 9 月", MonthRange.of(2026, 9, SHANGHAI).label());
    }
}
