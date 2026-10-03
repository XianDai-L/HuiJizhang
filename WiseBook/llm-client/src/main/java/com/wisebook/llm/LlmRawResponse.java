package com.wisebook.llm;

/**
 * 一次模型调用的原始返回，尚未做任何校验。
 *
 * <p>之所以保留 {@code content} 与 {@code rawBody}：</p>
 * <ul>
 *   <li>{@code content} —— 有些模型在给了 tools 的情况下仍把 JSON 塞在正文里，
 *       需要走降级提取路径（见 {@link StructuredExtractor}）</li>
 *   <li>{@code rawBody} —— 出问题时必须能回看原文，这是可解释性的基础（D1 的溯源要求）</li>
 * </ul>
 */
public final class LlmRawResponse {

    private final String model;
    private final String toolArguments;
    private final String content;
    private final String rawBody;

    public LlmRawResponse(String model, String toolArguments, String content, String rawBody) {
        this.model = model;
        this.toolArguments = toolArguments;
        this.content = content;
        this.rawBody = rawBody;
    }

    public String model() {
        return model;
    }

    /** {@code tool_calls[0].function.arguments}，为 null 表示模型未走工具调用 */
    public String toolArguments() {
        return toolArguments;
    }

    /** {@code message.content}，为 null 表示正文为空 */
    public String content() {
        return content;
    }

    /** 完整响应体，仅用于排查与溯源 */
    public String rawBody() {
        return rawBody;
    }
}
