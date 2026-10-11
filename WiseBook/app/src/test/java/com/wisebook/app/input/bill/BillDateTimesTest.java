package com.wisebook.app.input.bill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 账单时间解析。
 *
 * <p><b>两个序列号是从真实的微信账单 xlsx 里扒出来的</b>（{@code xl/worksheets/sheet1.xml}
 * 里 {@code A19} 的 {@code <v>}）。用真值而不是自己算一个：
 * Excel 日期换算最容易错的就是基准日（它还有个 1900 闰年的历史 bug），
 * 自己算一个来断言自己，等于把同一个错误验两遍。
 */
public class BillDateTimesTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    private static long millis(int year, int month, int day, int hour, int minute, int second) {
        return LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(UTC).toInstant().toEpochMilli();
    }

    // ------------------------------------------------------------ Excel 序列号

    @Test
    public void realSerialFromWechatBillBecomesTheRightMoment() {
        // A19 = 46303.34767361111，账单上写的是 2026-10-08 08:20:39
        Long millis = BillDateTimes.fromExcelSerial("46303.34767361111", UTC);

        assertEquals(Long.valueOf(millis(2026, 10, 8, 8, 20, 39)), millis);
    }

    @Test
    public void secondRealSerialAlsoLandsOnTheSecond() {
        // A20 = 46302.71193287037 → 2026-10-07 17:05:11
        Long millis = BillDateTimes.fromExcelSerial("46302.71193287037", UTC);

        assertEquals(Long.valueOf(millis(2026, 10, 7, 17, 5, 11)), millis);
    }

    /**
     * 小数部分是二进制浮点，直接截断会把 :39 变成 :38。
     *
     * <p>用 {@code 46303.34767361111} 这个真值正好能测出来：它乘 86400 是
     * 30038.99999…，四舍五入才对得上账单上的 08:20:39。
     */
    @Test
    public void fractionIsRoundedToTheNearestSecondNotTruncated() {
        Long millis = BillDateTimes.fromExcelSerial("46303.34767361111", UTC);

        assertEquals(39, LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), UTC)
                .getSecond());
    }

    @Test
    public void absurdSerialIsNotADate() {
        // 比 9999-12-31 还大：那是被误当成时间列的普通数字，不该硬解成日期
        assertNull(BillDateTimes.fromExcelSerial("99999999", UTC));
        assertNull(BillDateTimes.fromExcelSerial("-1", UTC));
        assertNull(BillDateTimes.fromExcelSerial("abc", UTC));
    }

    // ------------------------------------------------------------------ 文本

    @Test
    public void textDateTimeIsParsed() {
        assertEquals(Long.valueOf(millis(2026, 10, 8, 8, 20, 39)),
                BillDateTimes.read("2026-10-08 08:20:39", UTC));
    }

    @Test
    public void textWithoutSecondsIsParsed() {
        assertEquals(Long.valueOf(millis(2026, 10, 8, 8, 20, 0)),
                BillDateTimes.read("2026-10-08 08:20", UTC));
    }

    @Test
    public void slashSeparatedDateIsParsed() {
        assertEquals(Long.valueOf(millis(2026, 10, 8, 0, 0, 0)),
                BillDateTimes.read("2026/10/8", UTC));
    }

    @Test
    public void unreadableTimeReturnsNullSoTheRowGetsSkippedAndCounted() {
        assertNull(BillDateTimes.read("", UTC));
        assertNull(BillDateTimes.read(null, UTC));
        assertNull(BillDateTimes.read("昨天", UTC));
    }
}
