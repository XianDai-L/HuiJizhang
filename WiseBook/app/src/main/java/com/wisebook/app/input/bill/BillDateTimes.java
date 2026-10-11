package com.wisebook.app.input.bill;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 账单里的「交易时间」→ 毫秒（纯 JVM）。
 *
 * <p>同一个字段在两种文件里长得完全不一样，这件事必须在一个地方收口：
 * <ul>
 *   <li><b>CSV</b>：文本，{@code 2026-10-08 08:20:39}</li>
 *   <li><b>xlsx</b>：一个<b>数字</b>，{@code 45999.3478…}——xlsx 内部把日期存成
 *       "从 1900 年起过了多少天"，小数部分是一天里的时刻，与时区无关</li>
 * </ul>
 * 所以先试文本格式，认不出来再按序列号解。
 *
 * <p>时区用<b>本机时区</b>：账单上写的是"用户当时看到的墙上时间"，
 * 而全项目的时间模型就是"存毫秒、按本机时区渲染"。用固定 +08:00 反而会让
 * 换过时区的设备显示出两个口径的时间。国内用户两者相同。
 */
public final class BillDateTimes {

    /**
     * Excel 日期序列号的基准日。
     *
     * <p>取 1899-12-30 而不是 1900-01-01，是为了同时吃掉 Excel 那个著名的历史 bug：
     * 它认为 1900 年是闰年（为了兼容 Lotus 1-2-3），于是序列号 60 对应一个
     * <b>并不存在的 1900-02-29</b>，之后的日期整体比真实多了 1 天。
     * 以 1899-12-30 为基准，序列号 ≥ 61（也就是 1900-03-01 以后）全部落在正确日期上
     * ——而账单里的日期全是 2000 年以后的，序列号都在 4 万以上。
     *
     * <p>序列号 1~59（1900 年 1~2 月）会因此差一天。这笔账我们不算：
     * 为了一个不可能出现在账单里的日期区间，引入分支去处理"Excel 相信 1900 年是闰年"，
     * 只会让这段代码更难读。
     */
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    /** 一天里的秒数 × 用于把小数部分四舍五入成整秒 */
    private static final double SECONDS_PER_DAY = 86400d;

    /** 9999-12-31 的序列号。比它大说明这不是日期，而是被误当成时间列的普通数字 */
    private static final double MAX_SERIAL = 2958465d;

    /** 带时刻的写法 */
    private static final String[] DATE_TIME_PATTERNS = {
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-M-d HH:mm:ss",
            "yyyy-M-d HH:mm",
            "yyyy/M/d HH:mm:ss",
            "yyyy/M/d HH:mm"
    };

    /**
     * 只有日期的写法。
     *
     * <p>必须与上面分开：{@code LocalDateTime.parse} 遇到只有日期的串会抛异常
     * （它要求时刻字段齐全），所以这一组要按 {@code LocalDate} 解、再补零点。
     * 合成一组处理的话，"只有日期"会静默走到"认不出来"那条路。
     */
    private static final String[] DATE_PATTERNS = {
            "yyyy-MM-dd",
            "yyyy-M-d",
            "yyyy/M/d"
    };

    private BillDateTimes() {
    }

    /** @return 毫秒；认不出来返回 {@code null}（交给上层跳过这一行并计数） */
    public static Long read(String text, ZoneId zone) {
        Long parsed = parseText(text, zone);
        return parsed != null ? parsed : fromExcelSerial(text, zone);
    }

    /** 文本日期 → 毫秒。逐个格式试，全都不行返回 {@code null} */
    public static Long parseText(String text, ZoneId zone) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        for (String pattern : DATE_TIME_PATTERNS) {
            try {
                LocalDateTime dateTime = LocalDateTime.parse(value,
                        DateTimeFormatter.ofPattern(pattern));
                return dateTime.atZone(zone).toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {
                // 换下一个格式继续试
            }
        }
        for (String pattern : DATE_PATTERNS) {
            try {
                LocalDate date = LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern));
                return date.atStartOfDay(zone).toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {
                // 换下一个格式继续试
            }
        }
        return null;
    }

    /**
     * Excel 日期序列号 → 毫秒。
     *
     * <p>这里出现 {@code double} 不违反项目「金额只用整数」那条：
     * 序列号在 xlsx 里本来就是个浮点数（天 + 一天内的小数比例），
     * 而它换算完立刻变成毫秒整数，不参与任何金额计算。
     */
    public static Long fromExcelSerial(String text, ZoneId zone) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        double serial;
        try {
            serial = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
        if (!(serial > 0d) || serial > MAX_SERIAL) {
            return null;
        }
        long days = (long) Math.floor(serial);
        double fraction = serial - days;
        // 四舍五入到秒：序列号的小数是二进制浮点，直接截断会把 08:20:39 变成 08:20:38
        long seconds = Math.round(fraction * SECONDS_PER_DAY);
        LocalDateTime dateTime = EXCEL_EPOCH.plusDays(days).atStartOfDay().plusSeconds(seconds);
        return dateTime.atZone(zone).toInstant().toEpochMilli();
    }
}
