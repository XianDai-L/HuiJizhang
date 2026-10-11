package com.wisebook.app.input.bill;

import com.wisebook.app.data.transfer.EntryCsv;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.PaymentMethod;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 微信支付账单 → {@link BillRow}（纯 JVM）。
 *
 * <p><b>xlsx 与 csv 共用这一个适配器</b>：两种文件的字段、列序、取值完全一致，
 * 差别只在"怎么把格子读出来"，而那是 {@link BillSheet} 的事。
 *
 * <p>源文件里 11 列，真正用得上的是 7 列（表见 {@code COL_*} 常量），
 * 其余（当前状态 / 交易单号 / 商户单号）不落库——「退款也原样导入」这个口径下，
 * 方向由「收/支」列就能定，状态列没有判据作用（2026-10-08 用户拍板）。
 *
 * <p><b>三处口径都是用户定的，写在这里免得以后被当成 bug 改掉</b>：
 * <ol>
 *   <li><b>中性交易跳过</b>：收/支为 {@code /} 的是充值、提现、零钱通存取、信用卡还款
 *       ——钱只是从左口袋挪到右口袋，不是收支，导进去只会把报表撑起来</li>
 *   <li><b>支出的转账 → 转账方向</b>：报表默认把转账计入支出（{@code HANDOFF} 决策 22），
 *       所以"在账本里也算支出"这条已经天然满足，不必把它改成普通支出；
 *       分类落到「人情」（{@code CategoryClassifier} 的转账兜底）</li>
 *   <li><b>收入的转账 → 收入 + 红包</b>：别人转给我的钱不能算成我花出去的
 *       ——一律映射成转账会让 15 笔收款进"本月支出"，报表收入也会凭空少一大截</li>
 * </ol>
 */
public final class WechatBillAdapter {

    /** 商品列的分隔符。微信用半角分号，写全角是防别的导出工具 */
    private static final String ITEM_SEPARATOR = "[;；]";

    /** 账单里代表"没有内容"的占位符 */
    private static final String EMPTY_PLACEHOLDER = "/";

    private static final String PAY_EXPENSE = "支出";
    private static final String PAY_INCOME = "收入";
    private static final String TYPE_TRANSFER = "转账";

    /** 收入方向的转账落这个分类（一级，D1-A §1.2） */
    private static final String INCOME_TRANSFER_CATEGORY = "红包";

    /** 商品列里这些内容是"支付说明"而不是"买了什么"，不进 items */
    private static final String[] BOILERPLATE_PREFIXES = {
            "转账备注", "收款方备注", "商户单号", "订单号", "微信转账"
    };

    private WechatBillAdapter() {
    }

    /** 一次解析的结果 */
    public static final class Parsed {

        public final List<BillRow> rows;
        /** 中性交易被跳过的行数 */
        public final int neutralSkipped;
        /** 金额或时间读不出来而跳过的行数 */
        public final int badRows;

        Parsed(List<BillRow> rows, int neutralSkipped, int badRows) {
            this.rows = Collections.unmodifiableList(rows);
            this.neutralSkipped = neutralSkipped;
            this.badRows = badRows;
        }
    }

    /**
     * @param cells     单元格矩阵
     * @param headerRow 真表头所在行（由 {@link BillFormat#detect} 找到）
     * @param zone      把账单上的墙上时间换算成毫秒用的时区
     */
    public static Parsed parse(List<List<String>> cells, int headerRow, ZoneId zone) {
        if (cells == null || headerRow < 0 || headerRow >= cells.size()) {
            return new Parsed(new ArrayList<BillRow>(), 0, 0);
        }
        List<String> header = cells.get(headerRow);
        int timeColumn = columnOf(header, "交易时间");
        int typeColumn = columnOf(header, "交易类型");
        int merchantColumn = columnOf(header, "交易对方");
        int productColumn = columnOf(header, "商品");
        int payColumn = columnOf(header, "收/支", "收支");
        int amountColumn = columnOf(header, "金额(元)", "金额（元）", "金额");
        int methodColumn = columnOf(header, "支付方式");
        int noteColumn = columnOf(header, "备注");

        // 认不出这四列就没法导。这是文件异常（格式识别已经确认过它们的名字），
        // 与其静默导入 0 笔让用户以为"文件是空的"，不如把话说清楚
        if (timeColumn < 0 || merchantColumn < 0 || payColumn < 0 || amountColumn < 0) {
            throw new IllegalArgumentException(
                    "这份微信账单缺少必要的列（交易时间 / 交易对方 / 收/支 / 金额(元)）");
        }

        List<BillRow> rows = new ArrayList<>();
        int neutralSkipped = 0;
        int badRows = 0;

        for (int i = headerRow + 1; i < cells.size(); i++) {
            List<String> line = cells.get(i);
            if (isBlank(line)) {
                continue;                       // 末尾空行不算错误
            }

            String payer = cell(line, payColumn);
            // 中性交易：收/支列不是「支出」也不是「收入」（微信写的是 "/"）
            if (!PAY_EXPENSE.equals(payer) && !PAY_INCOME.equals(payer)) {
                neutralSkipped++;
                continue;
            }

            String timeText = cell(line, timeColumn);
            String amountText = cell(line, amountColumn);
            Long amountCents = EntryCsv.yuanToCents(amountText);
            Long occurredAt = BillDateTimes.read(timeText, zone);
            if (amountCents == null || occurredAt == null) {
                badRows++;
                continue;
            }

            boolean transfer = TYPE_TRANSFER.equals(cell(line, typeColumn));
            boolean income = PAY_INCOME.equals(payer);

            BillRow row = new BillRow();
            row.sourceRow = i + 1;
            row.amountCents = amountCents;
            row.occurredAt = occurredAt;
            row.merchant = cleanMerchant(cell(line, merchantColumn));
            row.items = items(clean(cell(line, productColumn)));
            row.note = clean(cell(line, noteColumn));
            row.paymentMethod = paymentMethod(clean(cell(line, methodColumn)));

            if (transfer && income) {
                row.direction = Direction.INCOME;
                row.categoryPath = INCOME_TRANSFER_CATEGORY;
            } else if (transfer) {
                row.direction = Direction.TRANSFER;
            } else {
                row.direction = income ? Direction.INCOME : Direction.EXPENSE;
            }

            row.rawInput = sourceLine(row, payer, zone);
            rows.add(row);
        }
        return new Parsed(rows, neutralSkipped, badRows);
    }

    // ------------------------------------------------------------------ 取值

    /**
     * 按<b>列名</b>定位列号。
     *
     * <p>不按固定下标取列：微信这两年的导出改过列序，而列名是文件里真实存在的东西。
     * 名字里的空白先清掉再比，免得全角空格把「交易时间」变成认不出的东西。
     */
    private static int columnOf(List<String> header, String... candidates) {
        if (header == null) {
            return -1;
        }
        for (String candidate : candidates) {
            for (int i = 0; i < header.size(); i++) {
                String name = header.get(i);
                if (name != null && candidate.equals(compact(name))) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 去掉所有空白字符，用于比对列名（全角空格、换行都可能夹在表头里） */
    private static String compact(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != ' ' && ch != '\u00A0' && ch != '\u3000'
                    && ch != '\t' && ch != '\r' && ch != '\n') {
                result.append(ch);
            }
        }
        return result.toString();
    }

    private static String cell(List<String> line, int index) {
        if (index < 0 || index >= line.size()) {
            return null;
        }
        String value = line.get(index);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(List<String> line) {
        if (line == null) {
            return true;
        }
        for (String value : line) {
            if (value != null && !value.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** {@code "/"} 与空白都算"没有内容" */
    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || EMPTY_PLACEHOLDER.equals(trimmed) ? null : trimmed;
    }

    /**
     * 商户名清洗。
     *
     * <p>微信在收款方昵称两侧塞了 <b>{@code U+00A0}（不换行空格）</b>：
     * {@code \u00a0\u00a0邱湘程 (\u00a0\u00a0)}。它不是 {@code Character.isWhitespace}
     * 认可的空白，{@code trim()} 清不掉，会一路带进 {@code merchant} 与账本界面。
     *
     * <p>再把"内容全是空白的尾括号组"剪掉（{@code 邱湘程 (  )} → {@code 邱湘程}）：
     * 那对括号是微信给"备注名"留的位置，空的就没有信息。
     * 内容非空的括号照留（{@code 梁洪荣 (糖诗宋词缘曲)} 里的备注是有意义的）。
     */
    private static String cleanMerchant(String value) {
        String text = clean(value);
        if (text == null) {
            return null;
        }
        text = text.replace('\u00A0', ' ').replace('\u3000', ' ');
        text = text.replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? null : stripBlankParenthesis(text);
    }

    private static String stripBlankParenthesis(String text) {
        int end = text.length() - 1;
        char last = text.charAt(end);
        if (last != ')' && last != '）') {
            return text;
        }
        int start = text.lastIndexOf(last == ')' ? '(' : '（');
        if (start < 0 || !text.substring(start + 1, end).trim().isEmpty()) {
            return text;
        }
        return text.substring(0, start).trim();
    }

    /**
     * 商品列 → {@code items}。
     *
     * <p>丢掉"支付说明"型的内容（{@code 转账备注:微信转账}、{@code 收款方备注:二维码收款}、
     * {@code 商户单号1315…}）：它们是这一笔怎么付的说明，不是"买了什么"，
     * 留着只会让详情页的明细栏变成噪声。
     */
    private static List<String> items(String product) {
        if (product == null) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (String piece : product.split(ITEM_SEPARATOR)) {
            String item = piece.trim();
            if (item.isEmpty() || isBoilerplate(item)) {
                continue;
            }
            values.add(item);
        }
        return values.isEmpty() ? null : values;
    }

    private static boolean isBoilerplate(String item) {
        for (String prefix : BOILERPLATE_PREFIXES) {
            if (item.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 支付方式 → 枚举。
     *
     * <p>只归到项目已有的 5 个取值之一，<b>卡里的银行名不保留</b>
     * （{@code 建设银行储蓄卡(8730)} → {@code BANK_CARD}）：那一列本来就不是给用户看的，
     * 而支付方式在确认页上是可选项，多出来的信息只会增加选择器的负担。
     */
    private static PaymentMethod paymentMethod(String value) {
        if (value == null) {
            return null;
        }
        if (value.contains("零钱")) {
            return PaymentMethod.WECHAT;
        }
        if (value.contains("银行") || value.contains("储蓄卡") || value.contains("信用卡")
                || value.contains("借记卡")) {
            return PaymentMethod.BANK_CARD;
        }
        return null;
    }

    /** 落进 {@code t_draft.raw_input}：一笔账的出处，详情页要能一眼看出它从哪来 */
    private static String sourceLine(BillRow row, String payer, ZoneId zone) {
        StringBuilder text = new StringBuilder("微信账单 ");
        text.append(EntryCsv.formatDateTime(row.occurredAt, zone));
        text.append('｜').append(row.merchant == null ? "（未记对方）" : row.merchant);
        text.append('｜').append(payer);
        text.append(' ').append(EntryCsv.yuan(row.amountCents)).append(" 元");
        return text.toString();
    }
}
