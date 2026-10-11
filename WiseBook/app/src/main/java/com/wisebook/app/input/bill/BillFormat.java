package com.wisebook.app.input.bill;

import java.util.List;

/**
 * 导入文件的来源识别（纯 JVM）。
 *
 * <p>设置页只留一个「导入」入口，用户拿什么文件来由他定，<b>认格式是应用的事</b>
 * （2026-10-08 用户拍板，见 {@code docs/HANDOFF.md} 决策 56）。
 * 所以这里要回答两件事：这是谁的文件、真表头在第几行。
 *
 * <p><b>为什么要容忍"表头不在第 1 行"</b>：微信账单前 17 行是账单说明
 * （昵称、时间范围、汇总、四条注释）和一条 {@code ------微信支付账单明细列表------}
 * 分隔线，第 18 行才是真表头。所以只能扫前若干行去找，不能假设第一行。
 *
 * <p>判定只认<b>列名的组合</b>，不认文件后缀也不认 MIME：后缀是用户改名的，
 * MIME 是文件管理器报的（已实测有的把 CSV 报成 {@code application/octet-stream}），
 * 只有列名是文件里真实存在的东西。
 */
public enum BillFormat {

    /** 微信支付账单（xlsx 与 csv 内容一致，共用同一个适配器） */
    WECHAT_BILL("微信账单"),

    /**
     * 支付宝账单。
     *
     * <p><b>只识别、暂不导入</b>：它的列名与微信完全不同
     * （{@code 交易号 / 交易创建时间 / 商品名称 / 金额（元）…}），
     * 而手头没有真实样本，硬写一个没验过的适配器就等于埋雷。
     * 认出来的意义是能给用户一句准确的话，而不是让他以为"文件坏了"。
     */
    ALIPAY_BILL("支付宝账单"),

    /** 慧记自己导出的账本 CSV（备份与设备转移用） */
    WISEBOOK_CSV("慧记账本"),

    /** 认不出来——大概率是选错了文件 */
    UNKNOWN("未知文件");

    /** 扫描表头的最大行数。微信 18 行、支付宝类似，取 40 留足余量 */
    private static final int HEADER_SCAN_LIMIT = 40;

    private final String label;

    BillFormat(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 识别结果：格式 + 真表头所在行（0 起；{@link #UNKNOWN} 时为 -1） */
    public static final class Detected {

        public final BillFormat format;
        public final int headerRow;

        Detected(BillFormat format, int headerRow) {
            this.format = format;
            this.headerRow = headerRow;
        }
    }

    /**
     * 扫前若干行找表头。
     *
     * <p><b>顺序有讲究</b>：微信账单里也有「金额(元)」这一列，
     * 而慧记自己的表头要求「日期」——两者不冲突，但仍先把最具体的（微信）判掉，
     * 免得将来慧记的导出加了列之后误判。
     */
    public static Detected detect(List<List<String>> cells) {
        if (cells == null) {
            return new Detected(UNKNOWN, -1);
        }
        int limit = Math.min(cells.size(), HEADER_SCAN_LIMIT);
        for (int i = 0; i < limit; i++) {
            List<String> row = cells.get(i);
            if (isWechatHeader(row)) {
                return new Detected(WECHAT_BILL, i);
            }
            if (isAlipayHeader(row)) {
                return new Detected(ALIPAY_BILL, i);
            }
            if (isWisebookHeader(row)) {
                return new Detected(WISEBOOK_CSV, i);
            }
        }
        return new Detected(UNKNOWN, -1);
    }

    /** 微信：交易时间 + 交易对方 + 收/支，三个一起出现只有微信账单 */
    private static boolean isWechatHeader(List<String> row) {
        return has(row, "交易时间") && has(row, "交易对方") && has(row, "收/支");
    }

    /** 支付宝：交易号 + 交易对方（它的时间列叫「交易创建时间」，与微信不同） */
    private static boolean isAlipayHeader(List<String> row) {
        return has(row, "交易号") && has(row, "交易创建时间");
    }

    /** 慧记：日期 + 方向 + 金额(元)（与 {@code EntryCsv.looksLikeHeader} 同一套判据） */
    private static boolean isWisebookHeader(List<String> row) {
        return has(row, "日期") && has(row, "方向")
                && (has(row, "金额(元)") || has(row, "金额（元）"));
    }

    private static boolean has(List<String> row, String name) {
        if (row == null) {
            return false;
        }
        for (String cell : row) {
            if (cell != null && name.equals(cell.trim())) {
                return true;
            }
        }
        return false;
    }
}
