package com.wisebook.app.domain.model;

/**
 * 交易发生时间的来源（D1 §3.2 / §3.6）。
 *
 * <p>「昨天下午打车 30」今晚录入时，{@code occurred_at} 必须落到昨天，
 * 而 {@code created_at} 是今晚——两者必须分开，这个枚举记录的就是
 * {@code occurred_at} 是怎么来的。
 */
public enum OccurredAtSource implements CodedEnum {

    /** 原文有明确时间（「昨天下午三点」），确定性解析得到 */
    EXPLICIT("explicit"),

    /** 原文有模糊时间（「刚才」「早上」），推断得到 */
    INFERRED("inferred"),

    /** 原文完全没提时间，回退到当前时间 */
    FALLBACK("fallback");

    private final String code;

    OccurredAtSource(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    public static OccurredAtSource fromCode(String code) {
        return CodedEnums.fromCode(OccurredAtSource.class, code);
    }
}
