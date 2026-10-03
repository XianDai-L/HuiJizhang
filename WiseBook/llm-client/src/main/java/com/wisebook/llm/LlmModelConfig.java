package com.wisebook.llm;

/**
 * 一个可调用的模型端点配置。
 *
 * <p>硅基流动与 DeepSeek 官方都是 <b>OpenAI 兼容协议</b>，因此同一套客户端实现
 * 只需要换这份配置即可同时对接两家。换模型时只改 {@link #model()} 一个字符串，
 * 这也是「多模型对比实验」成本极低的原因。
 */
public final class LlmModelConfig {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String label;

    public LlmModelConfig(String baseUrl, String apiKey, String model, String label) {
        // 不用 String.isBlank()：Android 上它是 API 33 才有的（minSdk 26），详见 HANDOFF §8
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        if (model == null || model.trim().isEmpty()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        this.baseUrl = baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.label = label == null ? model : label;
    }

    /** 聚合平台：一个 Key 下挂多家开源模型，含视觉模型 */
    public static LlmModelConfig siliconFlow(String apiKey, String model) {
        return new LlmModelConfig("https://api.siliconflow.cn/v1", apiKey, model, "siliconflow:" + model);
    }

    /** 官方直连：聚合平台限流时的备用链路 */
    public static LlmModelConfig deepSeek(String apiKey, String model) {
        return new LlmModelConfig("https://api.deepseek.com/v1", apiKey, model, "deepseek:" + model);
    }

    /** 自定义端点（代理、本地推理服务等） */
    public static LlmModelConfig custom(String baseUrl, String apiKey, String model, String label) {
        return new LlmModelConfig(baseUrl, apiKey, model, label);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    /** 用于写入 {@code t_draft.model} 的溯源标签，形如 {@code siliconflow:Qwen/...} */
    public String label() {
        return label;
    }

    public String chatCompletionsUrl() {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/chat/completions";
    }

    @Override
    public String toString() {
        return "LlmModelConfig{" + label + ", url=" + chatCompletionsUrl() + '}';
    }
}
