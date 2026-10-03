package com.wisebook.app.domain.report;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 一个自然月的半开区间 {@code [fromInclusive, toExclusive)}。
 *
 * <p>用<b>半开区间</b>而不是 {@code [1 号 00:00, 月末 23:59:59]}：
 * 后者在月末那一秒会发生两种错误——既可能漏掉 23:59:59.500 的账，
 * 也可能把下月 1 号 00:00:00 的账算进来。半开区间没有这个边界问题。
 *
 * <p>换算用 {@link LocalDate#atStartOfDay(ZoneId)} 而不是无参的
 * {@code atStartOfDay()}：前者在夏令时切换日会正确给出当天的真实起点，
 * 后者固定按 00:00 算。
 */
public final class MonthRange {

    public final int year;
    /** 1–12 */
    public final int month;
    public final long fromInclusive;
    public final long toExclusive;

    private MonthRange(int year, int month, long fromInclusive, long toExclusive) {
        this.year = year;
        this.month = month;
        this.fromInclusive = fromInclusive;
        this.toExclusive = toExclusive;
    }

    public static MonthRange of(int year, int month, ZoneId zone) {
        LocalDate firstDay = LocalDate.of(year, month, 1);
        LocalDate nextMonthFirstDay = firstDay.plusMonths(1);
        return new MonthRange(
                year,
                month,
                firstDay.atStartOfDay(zone).toInstant().toEpochMilli(),
                nextMonthFirstDay.atStartOfDay(zone).toInstant().toEpochMilli());
    }

    public static MonthRange current(ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        return of(today.getYear(), today.getMonthValue(), zone);
    }

    /** 某个时刻是否落在这个月里 */
    public boolean contains(long millis) {
        return millis >= fromInclusive && millis < toExclusive;
    }

    public String label() {
        return year + " 年 " + month + " 月";
    }

    @Override
    public String toString() {
        return label() + "[" + fromInclusive + ", " + toExclusive + ")";
    }
}
