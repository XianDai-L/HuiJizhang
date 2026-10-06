package com.wisebook.money;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 口语金额解析器（纯函数，无外部依赖）。
 *
 * <p>它是「双通道校验」中的规则通道：独立于大模型重算金额，
 * 与模型输出比对，不一致即判定存疑（见 D1 §2）。
 *
 * <p>解析流程：
 * <pre>
 * 归一化「半」 → 剥离约数标记 → 双数字区间 → 金额表达式 → 套用标记语义
 * </pre>
 */
public final class AmountParser {

    private AmountParser() {
    }

    /** 金额单位及其折合「分」的倍数 */
    private enum MoneyUnit {
        YUAN(100L),
        JIAO(10L),
        FEN(1L);

        final long centsScale;

        MoneyUnit(long centsScale) {
            this.centsScale = centsScale;
        }
    }

    /** 词法单元：数值 + 可选单位（unit 为 null 表示裸数字） */
    private static final class Token {

        final long value;
        final MoneyUnit unit;

        Token(long value, MoneyUnit unit) {
            this.value = value;
            this.unit = unit;
        }
    }

    /** 带「十」的双数字区间：五六十 */
    private static final Pattern TWO_DIGIT_RANGE_TEN =
            Pattern.compile("([一二三四五六七八九两])([一二三四五六七八九两])十(块|元|圆|毛|角|分)?");

    /** 不带「十」的双数字区间：三四块、三五毛 */
    private static final Pattern TWO_DIGIT_RANGE_PLAIN =
            Pattern.compile("([一二三四五六七八九两])([一二三四五六七八九两])(块|元|圆|毛|角|分)?");

    /**
     * 解析口语金额片段。
     *
     * @return 解析结果；无法解析时返回 {@code null}（调用方应转为人工确认）
     */
    public static AmountParseResult parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = normalize(raw);
        if (text.isEmpty()) {
            return null;
        }

        String body0 = normalizeHalf(text);

        // 剥离约数标记（可出现在数字前、数字后，或数字与单位之间）
        ApproxDictionary.Hit hit = ApproxDictionary.find(body0);
        String body = hit != null ? hit.remaining() : body0;
        ApproxDictionary.Kind kind = hit != null ? hit.marker().kind() : null;

        if (body.isEmpty()) {
            return null;
        }

        // 双数字区间优先于中文数字解析：否则「五六十」中「六」会覆盖「五」
        AmountParseResult range = twoDigitRange(body, raw.trim());
        if (range != null) {
            return range;
        }

        Long cents = parseMoneyExpression(body);
        if (cents == null) {
            return null;
        }
        return applyMarker(kind, cents, raw.trim());
    }

    // ---------------------------------------------------------------- 归一化

    private static String normalize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 「半」归一化：半 = 0.5 元 = 五毛。
     * 五块半 → 五块五毛；半块 → 五毛。
     */
    private static String normalizeHalf(String text) {
        if (text.endsWith("块半")) {
            return text.substring(0, text.length() - 1) + "五毛";
        }
        if (text.endsWith("半块")) {
            return text.substring(0, text.length() - 2) + "五毛";
        }
        return text;
    }

    // ------------------------------------------------------------ 双数字区间

    /**
     * 双数字区间：五六十、三四块。
     *
     * <p>统一模型为 {@code [d1 × U, d2 × U]}：
     * <ul>
     *   <li>带「十」时 U = 10 × 金额单位（五六十块 → U = 10 元 → [50,60] 元）</li>
     *   <li>不带「十」时 U = 金额单位（三四块 → U = 1 元 → [3,4] 元）</li>
     * </ul>
     *
     * <p>折算取区间中值。使用整体匹配（{@link Matcher#matches()}）而非查找，
     * 避免把「三百五」「两块五」这类正常表达误判为区间。
     */
    private static AmountParseResult twoDigitRange(String body, String rawSpan) {
        Matcher ten = TWO_DIGIT_RANGE_TEN.matcher(body);
        if (ten.matches()) {
            return buildRange(ten.group(1), ten.group(2),
                    unitOf(ten.group(3)) * 10L, rawSpan);
        }
        Matcher plain = TWO_DIGIT_RANGE_PLAIN.matcher(body);
        if (plain.matches()) {
            return buildRange(plain.group(1), plain.group(2),
                    unitOf(plain.group(3)), rawSpan);
        }
        return null;
    }

    /** 金额单位字符 → 折合「分」的倍数；{@code null} 或空串按「元」处理 */
    private static long unitOf(String unit) {
        if ("毛".equals(unit) || "角".equals(unit)) {
            return MoneyUnit.JIAO.centsScale;
        }
        if ("分".equals(unit)) {
            return MoneyUnit.FEN.centsScale;
        }
        return MoneyUnit.YUAN.centsScale;
    }

    private static AmountParseResult buildRange(String lowDigit, String highDigit,
                                                long unitCents, String rawSpan) {
        Long low = ChineseNumberParser.digitValue(lowDigit.charAt(0));
        Long high = ChineseNumberParser.digitValue(highDigit.charAt(0));
        if (low == null || high == null) {
            return null;
        }

        long lower = low * unitCents;
        long upper = high * unitCents;
        return AmountParseResult.estimated(
                (lower + upper) / 2L,
                lower,
                upper,
                AmountPrecision.RANGE,
                AmountParseResult.formatYuan(lower) + "~" + AmountParseResult.formatYuan(upper),
                rawSpan);
    }

    // ------------------------------------------------------------ 金额表达式

    private static Long parseMoneyExpression(String s) {
        List<Token> tokens = tokenize(s);
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }

        long yuan = 0L;
        long jiao = 0L;
        long fen = 0L;
        MoneyUnit prev = null;

        for (Token token : tokens) {
            if (token.unit == MoneyUnit.YUAN) {
                yuan += token.value;
                prev = MoneyUnit.YUAN;
            } else if (token.unit == MoneyUnit.JIAO) {
                jiao += token.value;
                prev = MoneyUnit.JIAO;
            } else if (token.unit == MoneyUnit.FEN) {
                fen += token.value;
                prev = MoneyUnit.FEN;
            } else if (prev == MoneyUnit.YUAN) {
                // 「三十块五」中裸数字为角位
                jiao += token.value;
            } else if (prev == MoneyUnit.JIAO) {
                fen += token.value;
            } else if (tokens.size() == 1) {
                // 全串无单位时视为元（如「35」）
                yuan += token.value;
            } else {
                return null;
            }
        }
        return yuan * 100L + jiao * 10L + fen;
    }

    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;

        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            // 货币符号与正负号：它们对「金额是多少」没有信息量——方向由 draft.direction
            // 单独表达（支出/收入/转账），所以这里只跳过，不折进数值，也不让负号把金额变负。
            //
            // 这条路是 P2 截图入口实测暴露的：截图上的金额几乎总带符号（￥7.00 / -15.00 / +8.00），
            // 而原先碰到这些前缀会整段解析失败，表现为「金额明明读对了，却提示无法自动校验、
            // 每笔都要用户点一下确认」——正是决策 20/24 花力气消掉的那种体验。
            if (c == '￥' || c == '¥' || c == '$' || c == '＄' || c == '+' || c == '-') {
                i++;
                continue;
            }

            int start = i;
            if (Character.isDigit(c)) {
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
            } else if (ChineseNumberParser.isNumberChar(c)) {
                while (i < s.length() && ChineseNumberParser.isNumberChar(s.charAt(i))) {
                    i++;
                }
            } else {
                return null;
            }
            String numberText = s.substring(start, i);

            Long value0 = valueOf(numberText);
            if (value0 == null) {
                return null;
            }

            // 小数点：形如「283.4」「283.45」。
            //
            // 小数点是这里<b>最确定的</b>一个信号——它明确把「元」与「角/分」分开了，
            // 比单位词可靠得多（「三十块五」要靠"上一个单位是元"来推断五在角位）。
            // 实现上拆成「283 元」+「4 角」两个 token，直接复用下面那套元/角/分累加，
            // 而不是给 Token 加一个"小数位"字段：多一条并行折算路径，
            // 就多一处可能跟老逻辑算出不同结果的地方。
            Long jiao = null;
            Long fen = null;
            if (i < s.length() && s.charAt(i) == '.') {
                int fractionStart = i + 1;
                int end = fractionStart;
                while (end < s.length() && end - fractionStart < 2
                        && Character.isDigit(s.charAt(end))) {
                    end++;
                }
                if (end == fractionStart) {
                    // 光有小数点后面没数字（如「283.」）：不是金额，交给上层判为无法解析
                    return null;
                }
                String fraction = s.substring(fractionStart, end);
                i = end;
                jiao = (long) Character.digit(fraction.charAt(0), 10);
                if (fraction.length() == 2) {
                    fen = (long) Character.digit(fraction.charAt(1), 10);
                }
            }

            MoneyUnit unit = null;
            if (i < s.length()) {
                char u = s.charAt(i);
                if (u == '块' || u == '元' || u == '圆' || u == '快') {
                    unit = MoneyUnit.YUAN;
                    i++;
                } else if (u == '毛' || u == '角') {
                    unit = MoneyUnit.JIAO;
                    i++;
                } else if (u == '分') {
                    unit = MoneyUnit.FEN;
                    i++;
                }
            }
            // 「三十块钱」中的「钱」为冗余字
            if (unit != null && i < s.length() && s.charAt(i) == '钱') {
                i++;
            }

            if (jiao != null) {
                // 小数 = 元.角分。此时若同时出现单位词（「3.5 元」），它只是冗余，
                // 不再改变折算结果——小数点已经说清楚了哪几位是元、哪几位是角分
                out.add(new Token(value0, MoneyUnit.YUAN));
                out.add(new Token(jiao, MoneyUnit.JIAO));
                if (fen != null) {
                    out.add(new Token(fen, MoneyUnit.FEN));
                }
                continue;
            }
            out.add(new Token(value0, unit));
        }
        return out;
    }

    private static Long valueOf(String text) {
        boolean allDigits = !text.isEmpty();
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                allDigits = false;
                break;
            }
        }
        if (allDigits) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return ChineseNumberParser.parse(text);
    }

    // -------------------------------------------------------------- 标记语义

    private static AmountParseResult applyMarker(ApproxDictionary.Kind kind, long cents, String rawSpan) {
        if (kind == null) {
            return AmountParseResult.exact(cents, rawSpan);
        }

        return switch (kind) {
            // 三十左右：[N - spread, N + spread]，折算 N
            case APPROX -> {
                long spread = approxSpreadCents(cents);
                yield AmountParseResult.estimated(
                        cents,
                        Math.max(0L, cents - spread),
                        cents + spread,
                        AmountPrecision.APPROX,
                        "≈" + AmountParseResult.formatYuan(cents),
                        rawSpan);
            }

            // 三十来块：[N, N + spread]，折算 1.1N（偏上，符合「来」的语义）
            case APPROX_UP -> {
                long estimate = cents * 11L / 10L;
                yield AmountParseResult.estimated(
                        estimate,
                        cents,
                        cents + approxSpreadCents(cents),
                        AmountPrecision.APPROX,
                        "≈" + AmountParseResult.formatYuan(estimate),
                        rawSpan);
            }

            // 三百多：>300，上界推进一个数量级，折算中值
            case LOWER_BOUND -> {
                long step = stepCents(cents);
                yield AmountParseResult.estimated(
                        cents + step / 2L,
                        cents,
                        cents + step,
                        AmountPrecision.LOWER_BOUND,
                        ">" + AmountParseResult.formatYuan(cents),
                        rawSpan);
            }

            // 十几 / 三十几：按数量级展开为整段区间
            case RANGE_BY_STEP -> {
                long step = stepCents(cents);
                long upper = cents + step;
                yield AmountParseResult.estimated(
                        cents + step / 2L,
                        cents,
                        upper,
                        AmountPrecision.RANGE,
                        AmountParseResult.formatYuan(cents) + "~"
                                + AmountParseResult.formatYuan(upper),
                        rawSpan);
            }

            // 不到三十：[0, 30)，折算上界
            case UPPER_BOUND_EXCLUSIVE -> AmountParseResult.estimated(
                    cents, 0L, cents,
                    AmountPrecision.UPPER_BOUND,
                    "<" + AmountParseResult.formatYuan(cents),
                    rawSpan);

            // 三十以内 / 最多三十：[0, 30]，折算上界
            case UPPER_BOUND_INCLUSIVE -> AmountParseResult.estimated(
                    cents, 0L, cents,
                    AmountPrecision.UPPER_BOUND,
                    "≤" + AmountParseResult.formatYuan(cents),
                    rawSpan);

            // 至少三十：只有下界，折算必然严重低估，必须反问
            case LOWER_NO_UPPER -> AmountParseResult.clarify(
                    rawSpan,
                    "「" + rawSpan + "」只有下界（" + AmountParseResult.formatYuan(cents)
                            + " 元）没有上界，折算会严重低估并漏掉确认流程，需反问具体金额");
        };
    }

    /**
     * 「来」「左右」的浮动半径（分），取 {@code min(数量级步长 / 2, N / 6)}。
     *
     * <ul>
     *   <li>步长项让小额符合十进制的直觉：「三十左右」→ ±5 元，而非按比例算出的 ±3 元</li>
     *   <li>{@code N / 6} 项封顶，避免在 10 的整数幂附近区间爆炸
     *       （「一百左右」若纯用步长会得到 [50,150]）</li>
     * </ul>
     *
     * <p>该公式精确复现两个基准：三十来块 = 33、三十左右 = [25,35]。
     */
    private static long approxSpreadCents(long cents) {
        return Math.min(stepCents(cents) / 2L, cents / 6L);
    }

    /**
     * 按数量级取步长（分）。
     *
     * <p>30 元 → 10 元；300 元 → 100 元；1500 元 → 1000 元；不足 10 元统一取 1 元。
     */
    private static long stepCents(long cents) {
        long yuan = cents / 100L;
        if (yuan < 10L) {
            return MoneyUnit.YUAN.centsScale;
        }
        long unit = 10L;
        while (unit * 10L <= yuan) {
            unit *= 10L;
        }
        return unit * MoneyUnit.YUAN.centsScale;
    }
}
