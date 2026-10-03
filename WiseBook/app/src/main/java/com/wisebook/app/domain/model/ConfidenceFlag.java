package com.wisebook.app.domain.model;

/**
 * 存疑标签（D1 §3.2）。
 *
 * <p><b>枚举标签，不是分数。</b>大模型自报的置信度经常是错的（D1 §2.1），
 * 所以「这笔存不存疑」由确定性判据决定，判据命中就打一个标签，
 * 再由确认档位决定要不要打断用户。
 */
public enum ConfidenceFlag implements CodedEnum {

    /** 金额存疑：双通道校验不一致，或口语表达无法安全折算 */
    AMOUNT_AMBIGUOUS("amount_ambiguous", "金额存疑"),

    /**
     * 分类不确定。
     *
     * <p><b>新代码不再产生这个标签</b>（2026-10-02，HANDOFF 决策 30）：
     * 分类判定已不再输出"不确定"信号。保留枚举值只是为了能正确读出
     * 历史数据里已存的 {@code category_swing}——删掉它会让老草稿反序列化失败。
     */
    CATEGORY_SWING("category_swing", "分类不确定"),

    /** 必填字段缺失 */
    MISSING_FIELD("missing_field", "信息不全"),

    /** 一次输入拆出多笔，拆分边界不确定（D1 §5.2 条件 8） */
    SPLIT_UNCERTAIN("split_uncertain", "多笔拆分不确定"),

    /** 语音场景「四/十」混淆风险（D1 §2.3），标记可见、可修正，不强制实时反问 */
    ASR_RISK("asr_risk", "语音数字可能有误");

    private final String code;
    private final String label;

    ConfidenceFlag(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @Override
    public String code() {
        return code;
    }

    /** 界面展示用中文名 */
    public String label() {
        return label;
    }

    public static ConfidenceFlag fromCode(String code) {
        return CodedEnums.fromCode(ConfidenceFlag.class, code);
    }
}
