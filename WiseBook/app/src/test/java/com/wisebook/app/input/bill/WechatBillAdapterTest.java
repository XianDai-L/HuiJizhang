package com.wisebook.app.input.bill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.wisebook.app.data.transfer.EntryCsv;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.PaymentMethod;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 微信账单 → {@link BillRow}。
 *
 * <p>样本是按真实微信账单的列与取值<b>仿造</b>的（收款方与金额都换掉了）：
 * 这份仓库是公开的，真实的支付记录不该进来。
 * 但列名、取值写法（{@code ¥3.50}、{@code /}、{@code 转账备注:微信转账}）都照原样保留，
 * 因为要验的正是这些真实写法。
 */
public class WechatBillAdapterTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    /** 商品里带「茶」、商户是便利店——两条路都指向「餐饮>饮品」，但那是分类层的事 */
    private static final String HEADER =
            "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注";

    private static String csv(String... dataRows) {
        StringBuilder text = new StringBuilder();
        text.append("微信支付账单明细,,,,,,,,,,\r\n");
        text.append("微信昵称：[某某],,,,,,,,,,\r\n");
        text.append("共111笔记录,,,,,,,,,,\r\n");
        text.append("----------------------微信支付账单明细列表--------------------,,,,,,,,,,\r\n");
        text.append(HEADER).append("\r\n");
        for (String row : dataRows) {
            text.append(row).append("\r\n");
        }
        return text.toString();
    }

    /** 非 ASCII 的空格：微信在收款方昵称两侧塞的就是这个，{@code trim()} 清不掉 */
    private static final String NBSP = "\u00A0";

    private static WechatBillAdapter.Parsed parse(String... dataRows) {
        List<List<String>> cells = EntryCsv.parseCells(csv(dataRows));
        BillFormat.Detected detected = BillFormat.detect(cells);
        assertEquals("夹具自己得先被认成微信账单", BillFormat.WECHAT_BILL, detected.format);
        return WechatBillAdapter.parse(cells, detected.headerRow, UTC);
    }

    private static long millis(int year, int month, int day, int hour, int minute, int second) {
        return LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(UTC).toInstant().toEpochMilli();
    }

    // ------------------------------------------------------------------ 基本映射

    @Test
    public void merchantPurchaseBecomesAnExpense() {
        WechatBillAdapter.Parsed parsed = parse(
                "2026-10-08 08:20:39,商户消费,便利超人,康师傅茉莉蜜茶500mlX1;,支出,¥3.50,零钱,支付成功,4500,P1,/");

        assertEquals(1, parsed.rows.size());
        BillRow row = parsed.rows.get(0);
        assertEquals(Direction.EXPENSE, row.direction);
        assertEquals("¥3.50 → 350 分（不能用浮点：0.29 那种会算出 28.999…）",
                350L, row.amountCents);
        assertEquals("便利超人", row.merchant);
        assertEquals(Arrays.asList("康师傅茉莉蜜茶500mlX1"), row.items);
        assertEquals(PaymentMethod.WECHAT, row.paymentMethod);
        assertNull("备注列的 / 就是「没有」，不该存一个字面量斜杠", row.note);
        assertNull("分类由规则表给，适配器不管", row.categoryPath);
        assertEquals(millis(2026, 10, 8, 8, 20, 39), row.occurredAt);
    }

    /** 账单里商品用分号分隔，且末尾常带一个空段 */
    @Test
    public void multipleProductsBecomeSeveralItems() {
        WechatBillAdapter.Parsed parsed = parse(
                "2026-10-08 08:20:39,商户消费,某超市,可乐X1;薯片X2;,支出,¥9.90,零钱,支付成功,4500,P1,/");

        assertEquals(Arrays.asList("可乐X1", "薯片X2"), parsed.rows.get(0).items);
    }

    /**
     * 「转账备注:微信转账」「收款方备注:二维码收款」这类不是"买了什么"，
     * 是这一笔怎么付的说明，进 items 只会让详情页变成噪声。
     */
    @Test
    public void paymentNotesAreNotProducts() {
        WechatBillAdapter.Parsed parsed = parse(
                "2026-10-07 17:05:11,转账,某某,转账备注:微信转账,支出,¥5.80,零钱,对方已收钱,5301,1000,/",
                "2026-09-11 19:52:12,二维码收款,某店,收款方备注:二维码收款,收入,¥10.00,/,已收钱,1000,/,/");

        assertNull(parsed.rows.get(0).items);
        assertNull(parsed.rows.get(1).items);
    }

    // ------------------------------------------------------------ 转账的两种方向

    /** 支出的转账：按转账落，报表默认把转账计入支出（HANDOFF 决策 22） */
    @Test
    public void outgoingTransferKeepsTheTransferDirection() {
        WechatBillAdapter.Parsed parsed = parse(
                "2026-10-07 17:05:11,转账,Felicity.,转账备注:微信转账,支出,¥5.80,零钱,对方已收钱,5301,1000,/");

        assertEquals(Direction.TRANSFER, parsed.rows.get(0).direction);
        assertNull("转账的分类交给分类器的兜底（人情）", parsed.rows.get(0).categoryPath);
    }

    /**
     * 收入的转账落「红包」。
     *
     * <p>这一条不是随手定的：一律映射成 transfer 会让"别人转给我的钱"进本月支出，
     * 报表收入也会凭空少一大截——而微信账单自己给出的收入合计会对不上。
     */
    @Test
    public void incomingTransferBecomesIncomeInRedPacket() {
        WechatBillAdapter.Parsed parsed = parse(
                "2026-10-07 15:47:30,转账,张三,转账备注:微信转账,收入,¥3.00,/,已存入零钱,1000,/,/");

        BillRow row = parsed.rows.get(0);
        assertEquals(Direction.INCOME, row.direction);
        assertEquals("红包", row.categoryPath);
    }

    // ------------------------------------------------------------------ 中性交易

    @Test
    public void neutralTransactionsAreSkippedAndCounted() {
        WechatBillAdapter.Parsed parsed = parse(
                "2026-09-20 19:20:50,零钱充值,建设银行(8730),/,/,¥18.00,/,充值完成,1102,/,/");

        assertEquals("充值/提现/零钱通存取这类只是钱换个口袋，不入账",
                0, parsed.rows.size());
        assertEquals(1, parsed.neutralSkipped);
        assertEquals(0, parsed.badRows);
    }

    // ------------------------------------------------------------------ 退款

    /** 「退款原样导入」：支出那笔与退款收入那笔都留着（2026-10-08 用户拍板） */
    @Test
    public void refundsAreImportedOnBothSides() {
        WechatBillAdapter.Parsed parsed = parse(
                "2026-10-07 11:50:52,商户消费,某平台,某订单,支出,¥15.88,零钱,已全额退款,4500,0002N,/",
                "2026-10-07 14:23:34,某平台-退款,某平台,某订单,收入,¥15.88,零钱,已全额退款,5030,/,/");

        assertEquals("退款那笔照常进来，方向看「收/支」列就够了，不必看「当前状态」",
                2, parsed.rows.size());
        assertEquals(Direction.EXPENSE, parsed.rows.get(0).direction);
        assertEquals(Direction.INCOME, parsed.rows.get(1).direction);
        assertNull("退款收入没有专属分类，交给分类器兜底", parsed.rows.get(1).categoryPath);
    }

    // ------------------------------------------------------------------ 清洗

    @Test
    public void wechatNbspPaddingIsCleanedOffTheMerchant() {
        WechatBillAdapter.Parsed parsed = parse(
                "2026-10-02 18:49:52,商户消费," + NBSP + NBSP + "邱湘程 (" + NBSP + NBSP + "),某商品,支出,¥7.00,零钱,支付成功,4500,P2,/");

        assertEquals("U+00A0 不是 Java 意义上的空白，trim() 清不掉——必须显式处理；"
                        + "内容全空的尾括号也一并剪掉",
                "邱湘程", parsed.rows.get(0).merchant);
    }

    @Test
    public void nonBlankParenthesisInTheMerchantSurvives() {
        // 括号里是备注名时那对括号是有信息的，不能一起剪掉
        WechatBillAdapter.Parsed parsed = parse(
                "2026-10-02 18:49:52,商户消费,梁洪荣 (糖诗宋词缘曲),某商品,支出,¥7.00,零钱,支付成功,4500,P2,/");

        assertEquals("梁洪荣 (糖诗宋词缘曲)", parsed.rows.get(0).merchant);
    }

    @Test
    public void bankCardIsMappedWithoutTheBankName() {
        WechatBillAdapter.Parsed parsed = parse(
                "2026-10-07 14:31:11,商户消费,某平台,12306消费,支出,¥90.00,建设银行储蓄卡(8730),支付成功,4500,M1,/");

        assertEquals("卡里的银行名不保留：项目只有 5 个支付方式取值",
                PaymentMethod.BANK_CARD, parsed.rows.get(0).paymentMethod);
    }

    @Test
    public void missingPaymentMethodStaysEmpty() {
        WechatBillAdapter.Parsed parsed = parse(
                "2026-10-07 15:47:30,转账,张三,转账备注:微信转账,收入,¥3.00,/,已存入零钱,1000,/,/");

        assertNull("支付方式本来就是可选的（HANDOFF 决策 20）",
                parsed.rows.get(0).paymentMethod);
    }

    // ------------------------------------------------------------------ 坏行与表头

    @Test
    public void unreadableAmountSkipsOnlyThatRow() {
        WechatBillAdapter.Parsed parsed = parse(
                "2026-10-08 08:20:39,商户消费,某店,某商品,支出,¥3.50,零钱,支付成功,4500,P1,/",
                "2026-10-08 08:21:39,商户消费,某店,某商品,支出,不是数字,零钱,支付成功,4501,P2,/");

        assertEquals("一行坏掉不该毁掉整份导入", 1, parsed.rows.size());
        assertEquals(1, parsed.badRows);
    }

    @Test
    public void blankLinesAreIgnoredRatherThanCountedAsErrors() {
        WechatBillAdapter.Parsed parsed = parse(
                "2026-10-08 08:20:39,商户消费,某店,某商品,支出,¥3.50,零钱,支付成功,4500,P1,/",
                ",,,,,,,,,,");

        assertEquals(1, parsed.rows.size());
        assertEquals(0, parsed.badRows);
        assertEquals(0, parsed.neutralSkipped);
    }

    /** xlsx 里的时间是数字序列号，适配器要认得它（真值取自真实账单，见 BillDateTimesTest） */
    @Test
    public void excelSerialTimeIsUnderstood() {
        List<List<String>> cells = new ArrayList<>();
        cells.add(Arrays.asList(HEADER.split(",")));
        cells.add(Arrays.asList("46303.34767361111", "商户消费", "某店", "某商品", "支出",
                "3.5", "零钱", "支付成功", "4500", "P1", "/"));

        BillRow row = WechatBillAdapter.parse(cells, 0, UTC).rows.get(0);

        assertEquals(millis(2026, 10, 8, 8, 20, 39), row.occurredAt);
        assertEquals(350L, row.amountCents);
    }

    @Test
    public void missingRequiredColumnsFailLoudlyInsteadOfImportingNothing() {
        List<List<String>> cells = new ArrayList<>();
        cells.add(Arrays.asList("交易时间", "交易对方", "商品"));
        cells.add(Arrays.asList("2026-10-08 08:20:39", "某店", "某商品"));

        try {
            WechatBillAdapter.parse(cells, 0, UTC);
            fail("缺列时应当报错，而不是静默导入 0 笔让用户以为文件是空的");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("缺少必要的列"));
        }
    }
}
