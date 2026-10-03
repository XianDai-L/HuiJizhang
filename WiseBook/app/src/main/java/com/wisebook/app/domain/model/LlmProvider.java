package com.wisebook.app.domain.model;

/**
 * 大模型服务商（D2 §6）。
 *
 * <p>两家都是 OpenAI 兼容协议，所以 {@code llm-client} 里只有一套客户端实现，
 * 差别全部收在这份枚举里：baseUrl 由 {@code LlmModelConfig} 决定，
 * 默认模型与展示名放在这里。
 *
 * <p>留两家不是"多此一举"：2026 年这类聚合平台的限流是常态，
 * 答辩演示当天主链路被限流就是现场事故（D2 §6.5）。
 * 切换服务商只需要改这一个枚举值。
 */
public enum LlmProvider implements CodedEnum {

    /** 聚合平台，主力：一个 Key 下挂多家开源模型 */
    SILICONFLOW("siliconflow", "硅基流动", "Qwen/Qwen3.6-35B-A3B"),

    /** 官方直连，退路 */
    DEEPSEEK("deepseek", "DeepSeek 官方", "deepseek-chat");

    private final String code;
    private final String label;
    private final String defaultModel;

    LlmProvider(String code, String label, String defaultModel) {
        this.code = code;
        this.label = label;
        this.defaultModel = defaultModel;
    }

    @Override
    public String code() {
        return code;
    }

    /** 界面展示用中文名 */
    public String label() {
        return label;
    }

    /**
     * 默认模型名（2026-09-24 实测可用）。
     *
     * <p>换模型只改这个字符串——这也是「多模型对比实验」几乎零成本的原因（D2 §6.2），
     * 实验数据落在 {@code t_draft.model} 上。
     */
    public String defaultModel() {
        return defaultModel;
    }

    public static LlmProvider fromCode(String code) {
        return CodedEnums.fromCode(LlmProvider.class, code);
    }
}
