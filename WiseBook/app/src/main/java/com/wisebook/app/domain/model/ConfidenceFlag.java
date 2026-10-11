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

    /**
     * 一次输入拆出多笔，拆分边界不确定（D1 §5.2 条件 8）。
     *
     * <p><b>2026-10-08 起不再产生</b>（决策 50）：它原先来自模型自报的 {@code transactionCount}，
     * 而拆分现在由批量工具的数组直接完成、那个字段已删。枚举值保留只为读历史数据
     * ——删掉它会让库里带这个 code 的行在反序列化时抛异常（与 {@link #CATEGORY_SWING}
     * 同一个处理，见 HANDOFF 决策 30）。
     */
    SPLIT_UNCERTAIN("split_uncertain", "多笔拆分不确定"),

    /** 语音场景「四/十」混淆风险（D1 §2.3），标记可见、可修正，不强制实时反问 */
    ASR_RISK("asr_risk", "语音数字可能有误"),

    /**
     * 疑似重复：去重键（方向 + 金额 + 发生时间 + 商户）命中了已有账目。
     *
     * <p><b>为什么要有这个枚举值</b>：D1 §5.2 条件 7 的「疑似重复」在
     * 文字/截图入口是靠传一个 {@code boolean} 给 {@link com.wisebook.app.domain.confirm.ConfirmPolicy}
     * 触发的，它会把草稿拦进确认页、由用户当场决定，<b>不需要落库</b>。
     * 而账单导入是"不走确认、直接落账"（2026-10-08 用户拍板），当场没人可问，
     * 这个事实就只能记在草稿上，让用户在账目详情页的「存疑」一行看到它。
     *
     * <p>⚠️ <b>命中之后绝不自动合并</b>（D1 §7）：连续同额消费是真实存在的，
     * 这个标签的语义是"捞出来给用户看"，不是"判定为重复"。
     */
    DUPLICATE_SUSPECT("duplicate_suspect", "疑似重复");

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
