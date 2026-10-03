package com.wisebook.app.domain.model;

/**
 * 双通道金额校验的结论（D1 §2）。
 *
 * <p>这一列是「模型自报的置信度不可信」这条设计的落点：不问模型「你有多大把握」，
 * 而是用 {@code money-parser} 独立重算一遍，把两者的比对结果记在这里。
 */
public enum AmountRuleCheck implements CodedEnum {

    /** 规则重算与模型输出一致 */
    PASS("pass"),

    /** 不一致 —— 必然触发反问（D1 §2.2） */
    FAIL("fail"),

    /** 不适用：没有可校验的金额片段（例如模型没给 amount_raw） */
    NA("na");

    private final String code;

    AmountRuleCheck(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    public static AmountRuleCheck fromCode(String code) {
        return CodedEnums.fromCode(AmountRuleCheck.class, code);
    }
}
