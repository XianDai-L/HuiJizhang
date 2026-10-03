package com.wisebook.app.domain.model;

/**
 * 证据类型（D1 §3.2 的 {@code evidence_type}）。
 *
 * <p>它和 {@link DraftSource} 是两件事：来源说的是「用户从哪个入口输入」，
 * 证据说的是「原始材料是什么形态」。P1 只有文本，恒为 {@link #TEXT}。
 */
public enum EvidenceType implements CodedEnum {

    AUDIO("audio"),

    IMAGE("image"),

    TEXT("text");

    private final String code;

    EvidenceType(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    public static EvidenceType fromCode(String code) {
        return CodedEnums.fromCode(EvidenceType.class, code);
    }
}
