package com.wisebook.app.input.bill;

import static org.junit.Assert.assertEquals;

import com.wisebook.app.data.transfer.EntryCsv;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 导入文件的来源识别。
 *
 * <p>要守住的两件事：
 * <ol>
 *   <li><b>表头不在第 1 行也能找到</b>：微信账单前 17 行全是说明与分隔线</li>
 *   <li><b>微信不会被误判成慧记</b>：两者都有「金额(元)」列，
 *       混淆的后果是拿账单去跑账本 CSV 的解析器，然后报一句莫名其妙的"没找到表头"</li>
 * </ol>
 */
public class BillFormatTest {

    /** 微信账单的真实开头：16 行说明 + 分隔线 + 表头（用下标数就是第 18 行） */
    private static List<List<String>> wechatPreamble() {
        List<List<String>> cells = new ArrayList<>();
        cells.add(row("微信支付账单明细"));
        cells.add(row("微信昵称：[某某]"));
        cells.add(row("起始时间：[2026-09-08 00:00:00] 终止时间：[2026-10-08 08:26:28]"));
        cells.add(row("共111笔记录"));
        cells.add(row("注："));
        cells.add(row("1. 充值/提现/理财通购买/零钱通存取/信用卡还款等交易，将计入中性交易"));
        cells.add(row("----------------------微信支付账单明细列表--------------------"));
        cells.add(row("交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)",
                "支付方式", "当前状态", "交易单号", "商户单号", "备注"));
        cells.add(row("2026-10-08 08:20:39", "商户消费", "便利超人", "某饮料;", "支出", "3.50",
                "零钱", "支付成功", "4500", "P1", "/"));
        return cells;
    }

    private static List<String> row(String... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    @Test
    public void wechatHeaderIsFoundBelowThePreamble() {
        BillFormat.Detected detected = BillFormat.detect(wechatPreamble());

        assertEquals(BillFormat.WECHAT_BILL, detected.format);
        assertEquals("表头在第 8 行（下标 7），前 7 行是说明", 7, detected.headerRow);
    }

    @Test
    public void wisebookExportIsRecognized() {
        List<List<String>> cells = new ArrayList<>();
        cells.add(row("日期", "方向", "金额(元)", "分类路径", "商户", "支付方式", "备注",
                "商品明细", "来源", "是否约数", "金额区间(元)", "原文金额", "原话",
                "记账时间", "id"));
        cells.add(row("2026-10-08 08:20", "支出", "3.50", "餐饮>饮品", "便利超人", "微信", "无",
                "某饮料", "文字", "否", "无", "三块五", "买了瓶水", "2026-10-08 08:21", "1"));

        BillFormat.Detected detected = BillFormat.detect(cells);

        assertEquals(BillFormat.WISEBOOK_CSV, detected.format);
        assertEquals(0, detected.headerRow);
    }

    /**
     * 微信与慧记都有「金额(元)」这一列，所以判定顺序必须是"先微信"。
     *
     * <p>反了的话，一份微信账单会被当成账本 CSV 走错解析器——而那条路只会回一句
     * 「这不是慧记导出的 CSV」，用户根本不知道发生了什么。
     */
    @Test
    public void wechatIsNotMistakenForTheAppOwnExport() {
        assertEquals(BillFormat.WECHAT_BILL, BillFormat.detect(wechatPreamble()).format);
    }

    @Test
    public void alipayHeaderIsRecognizedSoWeCanSaySo() {
        List<List<String>> cells = new ArrayList<>();
        cells.add(row("支付宝交易记录明细查询"));
        cells.add(row("交易号", "商家订单号", "交易创建时间", "付款时间", "最近修改时间",
                "交易来源地", "类型", "交易对方", "商品名称", "金额（元）", "收/支",
                "交易状态", "服务费（元）", "成功退款（元）", "备注", "资金状态"));

        assertEquals(BillFormat.ALIPAY_BILL, BillFormat.detect(cells).format);
    }

    @Test
    public void anythingElseIsUnknown() {
        List<List<String>> cells = new ArrayList<>();
        cells.add(row("这是一份随便什么表格"));
        cells.add(row("姓名", "年龄"));

        BillFormat.Detected detected = BillFormat.detect(cells);

        assertEquals(BillFormat.UNKNOWN, detected.format);
        assertEquals(-1, detected.headerRow);
    }

    /** 表头里夹了空白也要认得出（不同导出工具的空格用法不一致） */
    @Test
    public void headerNamesAreComparedIgnoringWhitespace() {
        List<List<String>> cells = new ArrayList<>();
        cells.add(row("交易时间 ", " 交易类型", "交易对方", "商品", "收/支", "金额(元)"));

        assertEquals(BillFormat.WECHAT_BILL, BillFormat.detect(cells).format);
    }

    /**
     * 与 {@code EntryCsv} 的切分必须一致：账单也是先切格再认表头。
     *
     * <p>这条用真是 CSV 文本走一遍（而不是手搭矩阵），验的是"文本 → 矩阵 → 识别"整条链。
     */
    @Test
    public void textFilePathEndsUpWithTheSameAnswer() {
        String csv = "微信支付账单明细,,,,,,,,,,\r\n"
                + "微信昵称：[某某],,,,,,,,,,\r\n"
                + "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\r\n"
                + "2026-10-08 08:20:39,商户消费,便利超人,某饮料;,支出,¥3.50,零钱,支付成功,4500,P1,/\r\n";

        List<List<String>> cells = EntryCsv.parseCells(csv);
        BillFormat.Detected detected = BillFormat.detect(cells);

        assertEquals(BillFormat.WECHAT_BILL, detected.format);
        assertEquals(2, detected.headerRow);
    }
}
