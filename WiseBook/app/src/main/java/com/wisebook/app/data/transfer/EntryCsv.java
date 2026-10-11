package com.wisebook.app.data.transfer;

import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.PaymentMethod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 账本 CSV 的格式定义与读写（<b>纯 JVM，不依赖 Android</b>）。
 *
 * <p>2026-10-08：给「备份 / 设备转移」加的最简通道。选 CSV 而不是自有格式，
 * 是因为它<b>人也能看、Excel 也能打开</b>；代价是列是"扁"的（字段有损），
 * 这一点在列注释里写清了。
 *
 * <p>抽成纯类不是为了好看：CSV 最容易出错的地方全在这里——
 * <b>转义</b>（原话与商品明细里可能出现逗号、引号、换行）、<b>金额精度</b>（元 ↔ 分）、
 * <b>时间解析</b>、<b>中文枚举回认</b>。这些都能用 JVM 单测钉住，不必上设备。
 *
 * <p><b>两个必须记住的坑</b>：
 * <ol>
 *   <li>文件头要带 UTF-8 BOM，否则 Excel 打开中文是乱码（{@link #BOM}，写文件时拼在最前面）</li>
 *   <li>解析必须逐字符扫描，不能 {@code split(",")} 也不能按行切——
 *       引号里的逗号与换行是数据的一部分（截图转写文本里有换行，很正常）</li>
 * </ol>
 */
public final class EntryCsv {

    /** UTF-8 BOM。写文件时拼在最前面；解析时会被自动忽略 */
    public static final String BOM = "\uFEFF";

    /**
     * 列顺序 = 表头顺序。导入<b>按表头名取列</b>，所以少一列、多一列、顺序变了都不会错位
     * （表头认不出来才整体拒绝）。
     */
    public static final String[] HEADERS = {
            "日期", "方向", "金额(元)", "分类路径", "商户", "支付方式", "备注",
            "商品明细", "来源", "是否约数", "金额区间(元)", "原文金额", "原话", "记账时间", "id"
    };

    /** {@code t_entry.items} 在 CSV 里用顿号连接（与界面显示同一套写法） */
    public static final String ITEMS_SEPARATOR = "、";

    private static final String LINE_END = "\r\n";
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /**
     * 校验用的时区。
     *
     * <p>解析阶段只关心"这一格的日期能不能读出来"，真正换算成毫秒的是调用方
     * （用本机时区）。用一个固定时区只为让"能不能读"这件事与运行环境无关。
     */
    private static final ZoneId VALIDATION_ZONE = ZoneId.of("UTC");

    private EntryCsv() {
    }

    // ------------------------------------------------------------------ 导出

    /**
     * 导出的一行。字段都是<b>给人看的文本</b>（时间已格式化、金额已转元、枚举已转中文），
     * 由 {@code EntryTransferRepository} 填好；列顺序由 {@link #cells()} 统一管，
     * 免得"表头改了、写行时忘了改"。
     */
    public static final class OutRow {

        public String occurredAt;
        public String direction;
        public String amountYuan;
        public String categoryPath;
        public String merchant;
        public String paymentMethod;
        public String note;
        public String items;
        public String source;
        public String estimated;
        public String amountRange;
        public String amountRaw;
        public String rawInput;
        public String createdAt;
        public String id;

        String[] cells() {
            return new String[]{occurredAt, direction, amountYuan, categoryPath, merchant,
                    paymentMethod, note, items, source, estimated, amountRange, amountRaw,
                    rawInput, createdAt, id};
        }
    }

    /** 生成整份 CSV 文本（<b>不含 BOM</b>，写文件时再拼 {@link #BOM}） */
    public static String write(List<OutRow> rows) {
        StringBuilder out = new StringBuilder();
        out.append(String.join(",", HEADERS)).append(LINE_END);
        for (OutRow row : rows) {
            String[] cells = row.cells();
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(quote(cells[i]));
            }
            out.append(LINE_END);
        }
        return out.toString();
    }

    /**
     * 一个 CSV 单元格的转义。
     *
     * <p>只在必要时加引号（含逗号 / 引号 / 换行时），这样导出的文件用文本编辑器看也很干净；
     * 引号按 RFC 4180 的写法翻倍。
     */
    static String quote(String value) {
        String text = value == null ? "" : value;
        boolean needsQuote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        if (!needsQuote) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    // ------------------------------------------------------------------ 导入

    /** 一次解析的结果 */
    public static final class ParseResult {

        public final List<InRow> rows;
        /** 表头能认出来吗。认不出来（不是本应用导出的文件）时 rows 为空 */
        public final boolean headerFound;
        /** 字段非法而跳过的数据行数（日期/方向/金额缺一个就算非法） */
        public final int skippedRows;

        ParseResult(List<InRow> rows, boolean headerFound, int skippedRows) {
            this.rows = Collections.unmodifiableList(rows);
            this.headerFound = headerFound;
            this.skippedRows = skippedRows;
        }
    }

    /** 一行单元格，按<b>表头名</b>取值；缺列时取到 {@code null} */
    public static final class InRow {

        private final Map<String, String> cells;

        InRow(Map<String, String> cells) {
            this.cells = cells;
        }

        public String get(String header) {
            String value = cells.get(header);
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }

        public String categoryPath() {
            return get("分类路径");
        }

        public String merchant() {
            return get("商户");
        }

        public String note() {
            return get("备注");
        }

        public String amountRaw() {
            return get("原文金额");
        }

        public String rawInput() {
            return get("原话");
        }

        public String id() {
            return get("id");
        }

        /** 商品明细：按顿号拆开；没有则返回 {@code null}（与库里的可空语义一致） */
        public List<String> items() {
            String text = get("商品明细");
            if (text == null) {
                return null;
            }
            List<String> values = new ArrayList<>();
            for (String piece : text.split(ITEMS_SEPARATOR)) {
                String item = piece.trim();
                if (!item.isEmpty()) {
                    values.add(item);
                }
            }
            return values.isEmpty() ? null : values;
        }

        public DraftSource source() {
            String text = get("来源");
            if (text == null) {
                return null;
            }
            for (DraftSource candidate : DraftSource.values()) {
                if (candidate.code().equalsIgnoreCase(text) || candidate.label().equals(text)) {
                    return candidate;
                }
            }
            return null;
        }

        public PaymentMethod paymentMethod() {
            String text = get("支付方式");
            if (text == null) {
                return null;
            }
            for (PaymentMethod candidate : PaymentMethod.values()) {
                if (candidate.code().equalsIgnoreCase(text) || candidate.label().equals(text)) {
                    return candidate;
                }
            }
            return null;
        }

        /** 方向：中文（支出/收入/转账）与 code（expense/…）都认 */
        public Direction direction() {
            String text = get("方向");
            if (text == null) {
                return null;
            }
            for (Direction candidate : Direction.values()) {
                if (candidate.code().equalsIgnoreCase(text) || candidate.label().equals(text)) {
                    return candidate;
                }
            }
            return null;
        }

        /** 是否约数：「是」/「否」，或 true/false */
        public boolean estimated() {
            String text = get("是否约数");
            return text != null && (text.startsWith("是") || "true".equalsIgnoreCase(text));
        }

        /** 金额（元） → 分。非法返回 {@code null} */
        public Long amountCents() {
            return yuanToCents(get("金额(元)"));
        }

        public Long lowerCents() {
            return yuanToCents(range("lower"));
        }

        public Long upperCents() {
            return yuanToCents(range("upper"));
        }

        /** 发生时间 → 毫秒；非法返回 {@code null} */
        public Long occurredAtMillis(ZoneId zone) {
            return millis(get("日期"), zone);
        }

        private String range(String side) {
            String text = get("金额区间(元)");
            if (text == null) {
                return null;
            }
            // 导出时写成「下界 ~ 上界」；用 ~ 或 - 都认
            String[] parts = text.split("[~～-]");
            if (parts.length != 2) {
                return null;
            }
            return "lower".equals(side) ? parts[0].trim() : parts[1].trim();
        }
    }

    /**
     * 解析整份 CSV。
     *
     * <p>三个容错：① 自动忽略 BOM；② 表头行之前有空行也能找到表头；
     * ③ 单行字段非法只跳过那一行并计数，不让整份文件失败——
     * 用户手改过 Excel 的文件很常见，因为一行毁掉整个导入是最糟的体验。
     */
    public static ParseResult parse(String text) {
        List<List<String>> rows = parseCells(text);
        if (rows.isEmpty()) {
            return new ParseResult(new ArrayList<InRow>(), false, 0);
        }

        int headerIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (looksLikeHeader(rows.get(i))) {
                headerIndex = i;
                break;
            }
        }
        if (headerIndex < 0) {
            return new ParseResult(new ArrayList<InRow>(), false, 0);
        }

        List<String> header = rows.get(headerIndex);
        List<InRow> parsed = new ArrayList<>();
        int skipped = 0;
        for (int i = headerIndex + 1; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            Map<String, String> mapped = new HashMap<>();
            boolean empty = true;
            for (int column = 0; column < header.size() && column < cells.size(); column++) {
                String name = header.get(column).trim();
                if (!name.isEmpty()) {
                    mapped.put(name, cells.get(column));
                }
                if (!cells.get(column).trim().isEmpty()) {
                    empty = false;
                }
            }
            if (empty) {
                continue;                       // 空行（末尾常见）不算错误
            }
            InRow row = new InRow(mapped);
            if (row.occurredAtMillis(VALIDATION_ZONE) == null
                    || row.direction() == null || row.amountCents() == null) {
                skipped++;
                continue;
            }
            parsed.add(row);
        }
        return new ParseResult(parsed, true, skipped);
    }

    /** 表头判定：必须同时认得出「日期」与「金额(元)」两列，否则不算本应用导出的文件 */
    private static boolean looksLikeHeader(List<String> row) {
        boolean hasDate = false;
        boolean hasAmount = false;
        for (String cell : row) {
            String name = cell.trim();
            if ("日期".equals(name)) {
                hasDate = true;
            }
            if ("金额(元)".equals(name)) {
                hasAmount = true;
            }
        }
        return hasDate && hasAmount;
    }

    /**
     * 逐字符扫描成单元格矩阵。
     *
     * <p><b>这是本类最要紧的一段</b>：引号内的逗号与换行属于数据，所以既不能
     * {@code split(",")}（会把一句话切成好几格）也不能按行切（会把一笔账断成两笔）。
     *
     * <p><b>2026-10-08 起改为 public</b>：账单导入（{@code input.bill.BillSheet}）也要切 CSV，
     * 而这里恰好是最容易写错的一段（引号里的逗号、引号里的换行、连写的两个引号）。
     * 让第二处再写一遍，等于主动造一个"两份实现迟早不一样"的坑。
     */
    public static List<List<String>> parseCells(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null) {
            return rows;
        }
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        int index = text.startsWith(BOM) ? 1 : 0;

        while (index < text.length()) {
            char ch = text.charAt(index);
            if (inQuotes) {
                if (ch == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        cell.append('"');
                        index += 2;
                        continue;
                    }
                    inQuotes = false;
                    index++;
                    continue;
                }
                cell.append(ch);
                index++;
                continue;
            }
            if (ch == '"') {
                inQuotes = true;
                index++;
                continue;
            }
            if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
                index++;
                continue;
            }
            if (ch == '\r') {
                index++;                        // CRLF 的 CR 丢掉
                continue;
            }
            if (ch == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                index++;
                continue;
            }
            cell.append(ch);
            index++;
        }
        // 收尾：最后一行没有换行符时也要收进来
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------ 转换

    /**
     * 「元」文本 → 「分」。
     *
     * <p>容忍千分位与货币符号（用户可能用 Excel 编辑过），但<b>绝不用浮点</b>：
     * {@code Double.parseDouble("0.29") * 100} 会得到 28.999…，
     * 四舍五入成 29 分看似没事，累计到报表上就是误差（D1 §3.6 的原因）。
     *
     * <p><b>2026-10-08 起改为 public</b>：账单导入也要把「¥3.50」（csv）与
     * 「3.5」（xlsx 里的数值格）变成分，而"不能用浮点"这件事只该有一个实现。
     */
    public static Long yuanToCents(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replace(",", "").replace("，", "")
                .replace("￥", "").replace("¥", "").replace("$", "").replace("＄", "")
                .replace("元", "").replace(" ", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            long cents = new BigDecimal(cleaned)
                    .movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            return cents <= 0L ? null : cents;
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    /** 「yyyy-MM-dd HH:mm」 → 毫秒；非法返回 {@code null} */
    static Long millis(String text, ZoneId zone) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        for (String pattern : new String[]{"yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss",
                "yyyy/M/d HH:mm", "yyyy/M/d H:mm", "yyyy-MM-dd"}) {
            try {
                LocalDateTime dateTime = LocalDateTime.parse(value,
                        DateTimeFormatter.ofPattern(pattern));
                return dateTime.atZone(zone).toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {
                // 换下一个格式继续试；全都不行返回 null
            }
        }
        return null;
    }

    /** 毫秒 → 「yyyy-MM-dd HH:mm」 */
    public static String formatDateTime(long millis, ZoneId zone) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone)
                .format(DATE_TIME);
    }

    /** 供导出方拼「金额区间(元)」列 */
    public static String formatRange(Long lowerCents, Long upperCents) {
        if (lowerCents == null && upperCents == null) {
            return null;
        }
        return yuan(lowerCents) + " ~ " + yuan(upperCents);
    }

    /** 分 → 元文本（不带单位）。{@code null} 原样返回 */
    public static String yuan(Long cents) {
        if (cents == null) {
            return null;
        }
        return String.format(java.util.Locale.CHINA, "%d.%02d", cents / 100, Math.abs(cents % 100));
    }

    /** 便于排查：把表头名列表按原名数组顺序返回一份不可变副本 */
    public static List<String> headers() {
        return Collections.unmodifiableList(Arrays.asList(HEADERS));
    }
}
