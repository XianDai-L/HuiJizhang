package com.wisebook.money;

/**
 * 金额相关风险标签。
 *
 * <p>注意：这些是<b>枚举标签</b>而非置信度分数（见 D1 §3.2）——
 * 大模型自报的分数不可靠，只能用确定性判据。
 */
public enum AmountRisk {

    /** 语音场景下「四」与「十」极易互相听错（sì / shí） */
    ASR_FOUR_TEN("语音「四/十」混淆风险"),

    /** 一次输入含多笔，需要拆分 */
    MULTI_TRANSACTION("一次输入含多笔，需拆分"),

    /** 数量 × 单价，需要推理而非查表 */
    QUANTITY_PRICING("数量×单价需要推理"),

    /** 分摊 / AA，需要推理 */
    SHARED_SPLIT("分摊金额需要推理");

    private final String label;

    AmountRisk(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
