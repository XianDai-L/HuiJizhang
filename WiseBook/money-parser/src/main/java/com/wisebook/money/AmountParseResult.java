package com.wisebook.money;

/**
 * 口语金额解析结果（三层分离，见 D1 §1.2.1）。
 *
 * <ul>
 *   <li>展示层：{@link #getDisplay()}（带精度符号，如 {@code ≈33}、{@code >300}、{@code 50~60}）</li>
 *   <li>存储层：{@link #getCents()} 折算值 + {@link #getLowerCents()} / {@link #getUpperCents()} 区间</li>
 *   <li>报表层：由 {@link #getCents()} 求和，由区间给出浮动范围</li>
 * </ul>
 *
 * <p><b>注意：</b>{@link #isNeedsClarification()} 为 {@code true} 时 {@link #getCents()} 无意义（恒为 0），
 * 调用方必须先判断该标志，再使用金额值。
 */
public final class AmountParseResult {

    private final long cents;
    private final Long lowerCents;
    private final Long upperCents;
    private final AmountPrecision precision;
    private final String display;
    private final boolean estimated;
    private final boolean needsClarification;
    private final String clarifyReason;
    private final String rawSpan;

    public AmountParseResult(long cents, Long lowerCents, Long upperCents,
                             AmountPrecision precision, String display, boolean estimated,
                             boolean needsClarification, String clarifyReason, String rawSpan) {
        this.cents = cents;
        this.lowerCents = lowerCents;
        this.upperCents = upperCents;
        this.precision = precision;
        this.display = display;
        this.estimated = estimated;
        this.needsClarification = needsClarification;
        this.clarifyReason = clarifyReason;
        this.rawSpan = rawSpan;
    }

    /** 精确金额（区间退化为单点） */
    public static AmountParseResult exact(long cents, String rawSpan) {
        return new AmountParseResult(cents, cents, cents, AmountPrecision.EXACT,
                formatYuan(cents), false, false, null, rawSpan);
    }

    /** 估算金额：带区间与展示符号 */
    public static AmountParseResult estimated(long cents, long lowerCents, long upperCents,
                                              AmountPrecision precision, String display, String rawSpan) {
        return new AmountParseResult(cents, lowerCents, upperCents, precision,
                display, true, false, null, rawSpan);
    }

    /** 无法安全折算，必须反问 */
    public static AmountParseResult clarify(String rawSpan, String reason) {
        return new AmountParseResult(0L, null, null, AmountPrecision.LOWER_BOUND,
                "?", true, true, reason, rawSpan);
    }

    /** 折算值（分），参与统计 */
    public long getCents() {
        return cents;
    }

    /** 区间下界（分），{@code null} 表示无下界 */
    public Long getLowerCents() {
        return lowerCents;
    }

    /** 区间上界（分），{@code null} 表示无上界 */
    public Long getUpperCents() {
        return upperCents;
    }

    public AmountPrecision getPrecision() {
        return precision;
    }

    /** 展示文本，如 {@code 30} / {@code ≈33} / {@code >300} / {@code 50~60} / {@code <30} */
    public String getDisplay() {
        return display;
    }

    /** 是否为约数折算值（非精确） */
    public boolean isEstimated() {
        return estimated;
    }

    /** 是否必须反问（无法安全折算） */
    public boolean isNeedsClarification() {
        return needsClarification;
    }

    /** 反问原因，仅 {@link #isNeedsClarification()} 为 true 时有值 */
    public String getClarifyReason() {
        return clarifyReason;
    }

    /** 原始片段 */
    public String getRawSpan() {
        return rawSpan;
    }

    /** 区间宽度（分）；两端无界或精确时为 0 */
    public long spanCents() {
        if (lowerCents == null || upperCents == null) {
            return 0L;
        }
        return upperCents - lowerCents;
    }

    /** 分 → 展示文本，如 3050 → {@code "30.5"}、350 → {@code "3.5"}、3500 → {@code "35"} */
    public static String formatYuan(long cents) {
        long yuan = cents / 100L;
        long jiao = (cents % 100L) / 10L;
        long fen = cents % 10L;
        if (jiao == 0L && fen == 0L) {
            return Long.toString(yuan);
        }
        if (fen == 0L) {
            return yuan + "." + jiao;
        }
        return yuan + "." + jiao + fen;
    }

    @Override
    public String toString() {
        return "AmountParseResult{cents=" + cents
                + ", range=[" + lowerCents + "," + upperCents + "]"
                + ", precision=" + precision
                + ", display='" + display + '\''
                + ", estimated=" + estimated
                + ", needsClarification=" + needsClarification
                + '}';
    }
}
