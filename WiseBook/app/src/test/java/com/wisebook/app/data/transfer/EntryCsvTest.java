package com.wisebook.app.data.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.PaymentMethod;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 账本 CSV 的读写（2026-10-08，导入导出功能）。
 *
 * <p>这里盯的是 CSV 最容易出事的三处，它们都属于"写错了也不会报错、只是数据悄悄变形"：
 * <ol>
 *   <li><b>转义</b>：原话里出现逗号 / 引号 / 换行（截图转写文本里很常见）时，
 *       一行不能被切成好几格、更不能被切成两行——那等于凭空多出/合并掉几笔账</li>
 *   <li><b>金额精度</b>：元 ↔ 分必须走 {@code BigDecimal}，浮点会算出 0.29 → 28 分这种账</li>
 *   <li><b>表头映射</b>：导入按表头名取列，所以少列、多列、换顺序都不该错位</li>
 * </ol>
 */
public class EntryCsvTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    // ------------------------------------------------------------ 往返

    @Test
    public void roundTripKeepsEveryField() {
        EntryCsv.OutRow row = sampleRow();

        EntryCsv.ParseResult parsed = EntryCsv.parse(EntryCsv.write(Collections.singletonList(row)));

        assertTrue(parsed.headerFound);
        assertEquals(1, parsed.rows.size());
        assertEquals(0, parsed.skippedRows);

        EntryCsv.InRow back = parsed.rows.get(0);
        assertEquals(Direction.TRANSFER, back.direction());
        assertEquals(Long.valueOf(12345L), back.amountCents());
        assertEquals("人情>红包", back.categoryPath());
        assertEquals("张三", back.merchant());
        assertEquals(PaymentMethod.WECHAT, back.paymentMethod());
        assertEquals("八月红包", back.note());
        assertEquals(Long.valueOf(10000L), back.lowerCents());
        assertEquals(Long.valueOf(15000L), back.upperCents());
        assertEquals("120左右", back.amountRaw());
        assertEquals("给张三转了 120 左右", back.rawInput());
        assertEquals(DraftSource.TEXT_CHAT, back.source());
        assertEquals("42", back.id());
        assertFalse(back.estimated());

        // 时间：导出的是给 Excel 看的文本，导入回来必须落回同一毫秒
        LocalDateTime occurredAt = LocalDateTime.of(2026, 10, 7, 21, 9);
        assertEquals(Long.valueOf(occurredAt.atZone(ZONE).toInstant().toEpochMilli()),
                back.occurredAtMillis(ZONE));
    }

    @Test
    public void itemsAreJoinedByEnumerationComma() {
        EntryCsv.OutRow row = sampleRow();
        row.items = "苹果、牛奶";

        EntryCsv.InRow back = EntryCsv.parse(EntryCsv.write(Collections.singletonList(row)))
                .rows.get(0);

        assertEquals(2, back.items().size());
        assertEquals("苹果", back.items().get(0));
        assertEquals("牛奶", back.items().get(1));
    }

    /**
     * <b>本类最要紧的一条</b>：原话里有逗号、引号和换行时，往返之后必须一字不差。
     *
     * <p>截图入口的"原话"就是一整段 OCR 转写文本——里面有换行是常态。
     * 若解析按行切，一笔账会被读成两笔；若按逗号切，一句话会被读成好几格。
     */
    @Test
    public void commasQuotesAndNewlinesSurviveTheRoundTrip() {
        String nasty = "微信支付\n收款方,\"甲\"\n￥7.00\n\n收款方乙 ￥15.00";
        EntryCsv.OutRow first = sampleRow();
        first.rawInput = nasty;
        EntryCsv.OutRow second = sampleRow();
        second.rawInput = "普通一句话";

        EntryCsv.ParseResult parsed = EntryCsv.parse(EntryCsv.write(
                java.util.Arrays.asList(first, second)));

        assertEquals("带换行的原话不该被拆成两行数据", 2, parsed.rows.size());
        assertEquals(nasty, parsed.rows.get(0).rawInput());
        assertEquals("普通一句话", parsed.rows.get(1).rawInput());
    }

    @Test
    public void bomIsIgnoredWhenParsing() {
        String csv = EntryCsv.write(Collections.singletonList(sampleRow()));

        EntryCsv.ParseResult parsed = EntryCsv.parse(EntryCsv.BOM + csv);

        assertTrue(parsed.headerFound);
        assertEquals(1, parsed.rows.size());
    }

    // ------------------------------------------------------------ 表头

    @Test
    public void headerOrderAndMissingColumnsAreTolerated() {
        // 用户可能用 Excel 删掉几列、或把列拖了顺序；只要表头名字还在就该读得出来
        String csv = "备注,金额(元),日期,方向\n随手一记,28,2026-10-07 21:09,支出\n";

        EntryCsv.ParseResult parsed = EntryCsv.parse(csv);

        assertTrue(parsed.headerFound);
        assertEquals(1, parsed.rows.size());
        EntryCsv.InRow row = parsed.rows.get(0);
        assertEquals(Long.valueOf(2800L), row.amountCents());
        assertEquals(Direction.EXPENSE, row.direction());
        assertEquals("随手一记", row.note());
        assertNull("没有的列应当是空，而不是空串", row.merchant());
    }

    @Test
    public void plainTextIsRejectedInsteadOfGuessed() {
        EntryCsv.ParseResult parsed = EntryCsv.parse("这不是 CSV，只是一段话\n随便写点什么");

        assertFalse("认不出表头就该整体拒绝，不能让用户以为导进去了", parsed.headerFound);
        assertTrue(parsed.rows.isEmpty());
    }

    @Test
    public void badRowsAreSkippedWithoutKillingTheFile() {
        String csv = "日期,方向,金额(元)\n"
                + "2026-10-07 21:09,支出,28\n"
                + "看不懂的日期,支出,30\n"
                + "2026-10-07 22:00,不明方向,30\n"
                + "2026-10-07 22:30,收入,\n";

        EntryCsv.ParseResult parsed = EntryCsv.parse(csv);

        assertEquals("只有第一行是完整的", 1, parsed.rows.size());
        assertEquals("三行有问题的都要计数，用户才知道丢了多少", 3, parsed.skippedRows);
    }

    @Test
    public void trailingBlankLinesAreNotCountedAsBadRows() {
        String csv = "日期,方向,金额(元)\n2026-10-07 21:09,支出,28\n\n";

        EntryCsv.ParseResult parsed = EntryCsv.parse(csv);

        assertEquals(1, parsed.rows.size());
        assertEquals(0, parsed.skippedRows);
    }

    // ------------------------------------------------------------ 金额与枚举

    @Test
    public void yuanToCentsNeverUsesFloatingPoint() {
        // 0.29 用 double 乘 100 会得到 28.999…，四舍五入成 29 分看似没事，
        // 但同一套算法跑在报表上就是累计误差（D1 §3.6 的原因）
        assertEquals(Long.valueOf(29L), EntryCsv.yuanToCents("0.29"));
        assertEquals(Long.valueOf(28340L), EntryCsv.yuanToCents("283.4"));
        assertEquals(Long.valueOf(123456L), EntryCsv.yuanToCents("1,234.56"));
        assertEquals(Long.valueOf(2800L), EntryCsv.yuanToCents("￥28"));
        assertEquals(Long.valueOf(6600L), EntryCsv.yuanToCents("66元"));
    }

    @Test
    public void invalidAmountsBecomeNull() {
        assertNull(EntryCsv.yuanToCents(null));
        assertNull(EntryCsv.yuanToCents(""));
        assertNull(EntryCsv.yuanToCents("随便给"));
        assertNull("0 元不是一笔账", EntryCsv.yuanToCents("0"));
        assertNull("负金额在本系统里不存在（方向由方向列表达）",
                EntryCsv.yuanToCents("-5"));
    }

    @Test
    public void directionAcceptsBothLabelAndCode() {
        assertEquals(Direction.TRANSFER, directionOf("转账", "2026-10-07 21:09,转账,1\n"));
        assertEquals(Direction.TRANSFER, directionOf("transfer", "2026-10-07 21:09,transfer,1\n"));
        assertNull(directionOf(null, "2026-10-07 21:09,,1\n"));
    }

    private static Direction directionOf(String expected, String dataLine) {
        EntryCsv.ParseResult parsed = EntryCsv.parse("日期,方向,金额(元)\n" + dataLine);
        // 非法方向的行会被跳过，所以这里两种结果都可能是"没解析出来"
        return parsed.rows.isEmpty() ? null : parsed.rows.get(0).direction();
    }

    @Test
    public void estimatedFlagAcceptsChineseAndBoolean() {
        assertEquals(Boolean.TRUE, estimatedOf("是"));
        assertEquals(Boolean.TRUE, estimatedOf("true"));
        assertEquals(Boolean.FALSE, estimatedOf("否"));
    }

    private static Boolean estimatedOf(String value) {
        String csv = "日期,方向,金额(元),是否约数\n2026-10-07 21:09,支出,28," + value + "\n";
        return EntryCsv.parse(csv).rows.get(0).estimated();
    }

    @Test
    public void rangeIsOptional() {
        EntryCsv.OutRow row = sampleRow();
        row.amountRange = null;

        EntryCsv.InRow back = EntryCsv.parse(EntryCsv.write(Collections.singletonList(row)))
                .rows.get(0);

        assertNull(back.lowerCents());
        assertNull(back.upperCents());
    }

    @Test
    public void quotedCellKeepsEmptyFieldsInPlace() {
        // 引号里的空内容不能把后面的列"挤"到前面去
        String csv = "日期,方向,金额(元),商户,备注\n2026-10-07 21:09,支出,28,\"\",备注在这\n";

        EntryCsv.InRow row = EntryCsv.parse(csv).rows.get(0);

        assertNull(row.merchant());
        assertEquals("备注在这", row.note());
    }

    // ------------------------------------------------------------ 夹具

    private static EntryCsv.OutRow sampleRow() {
        EntryCsv.OutRow row = new EntryCsv.OutRow();
        row.occurredAt = "2026-10-07 21:09";
        row.direction = Direction.TRANSFER.label();
        row.amountYuan = "123.45";
        row.categoryPath = "人情>红包";
        row.merchant = "张三";
        row.paymentMethod = PaymentMethod.WECHAT.label();
        row.note = "八月红包";
        row.items = "红包";
        row.source = DraftSource.TEXT_CHAT.label();
        row.estimated = "否";
        row.amountRange = "100.00 ~ 150.00";
        row.amountRaw = "120左右";
        row.rawInput = "给张三转了 120 左右";
        row.createdAt = "2026-10-07 21:10";
        row.id = "42";
        return row;
    }

    /** 表头只写一次、每笔一行：行数对不上就说明有行被合并或拆开了 */
    @Test
    public void writeProducesOneLinePerRowPlusHeader() {
        List<EntryCsv.OutRow> rows = new ArrayList<>();
        rows.add(sampleRow());
        rows.add(sampleRow());

        String csv = EntryCsv.write(rows);

        // 用默认的 split（丢掉末尾空段）：文本以 CRLF 结尾，否则会多算一个空行
        assertEquals("表头 + 2 行数据 = 3 行", 3, csv.split("\r\n").length);
        assertTrue(csv.startsWith("日期,方向,金额(元)"));
    }
}
