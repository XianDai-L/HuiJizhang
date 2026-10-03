package com.wisebook.app.domain.model;

/**
 * 确认档位（D1 §5.1），三选一。
 *
 * <p>注意三档的触发条件是「或」关系：档位二不只是判金额，
 * 存疑同样要问——否则「三百五」被记成 305 时也不会反问。
 */
public enum ConfirmMode implements CodedEnum {

    /** 一 · 每笔确认：所有草稿都过确认页 */
    STRICT("strict"),

    /** 二 · 大额确认：小额直接落账，金额 ≥ 阈值 或 存疑才确认 */
    LARGE("large"),

    /** 三 · 存疑才确认：命中 confidence_flags 或必填字段缺失才确认 */
    DOUBTFUL("doubtful");

    private final String code;

    ConfirmMode(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    public static ConfirmMode fromCode(String code) {
        return CodedEnums.fromCode(ConfirmMode.class, code);
    }
}
