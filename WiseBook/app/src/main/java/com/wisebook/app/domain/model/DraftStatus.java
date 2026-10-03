package com.wisebook.app.domain.model;

/**
 * 草稿状态（D1 §4.1）。
 *
 * <pre>
 * DRAFT ──触发确认条件──→ ASKING ──用户回答──→ 回到 DRAFT 重新校验
 *   │                      │
 *   │ 免确认（八条件全满足）│ 用户确认 / 反问用尽转手动填表
 *   ↓                      ↓
 * CONFIRMED ──幂等写入事务──→ POSTED（终态）
 *
 * DRAFT / ASKING ──用户放弃──→ DISCARDED（终态）
 * DRAFT / ASKING ──超时 24h──→ EXPIRED（终态，转「待处理」，不删除）
 * </pre>
 */
public enum DraftStatus implements CodedEnum {

    /** 已解析出草稿，待处理 */
    DRAFT("draft"),

    /** 正在向用户反问 */
    ASKING("asking"),

    /** 已确认，待写入 */
    CONFIRMED("confirmed"),

    /** 已写入账本 */
    POSTED("posted"),

    /** 用户主动丢弃 */
    DISCARDED("discarded"),

    /** 超时作废（24 小时未处理） */
    EXPIRED("expired");

    private final String code;

    DraftStatus(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    public static DraftStatus fromCode(String code) {
        return CodedEnums.fromCode(DraftStatus.class, code);
    }

    /** 终态不可再迁移。多笔拆分时每张草稿各自独立流转，靠的就是这个判断 */
    public boolean isTerminal() {
        return this == POSTED || this == DISCARDED || this == EXPIRED;
    }

    /** 是否还挂在用户手上等着处理（用于「待处理」列表） */
    public boolean isOpen() {
        return this == DRAFT || this == ASKING;
    }
}
