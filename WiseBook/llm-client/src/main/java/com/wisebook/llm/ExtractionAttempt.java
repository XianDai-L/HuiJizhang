package com.wisebook.llm;

/**
 * 一次尝试的记录。
 *
 * <p>保留每一次的失败原因与原始返回，有两个用途：</p>
 * <ul>
 *   <li>可解释性：草稿出错时能回看模型当时到底返回了什么（D1 的溯源要求）</li>
 *   <li>论文数据：「重试成功率」「首次通过率」这类指标直接从这里统计</li>
 * </ul>
 */
public final class ExtractionAttempt {

    /** 本次尝试的结果类别 */
    public enum Kind {
        /** 通过校验 */
        OK,
        /** 返回的 JSON 缺失、无法解析或不符合 schema */
        SCHEMA_ERROR,
        /** 调用本身失败（网络、鉴权、限流） */
        TRANSPORT_ERROR
    }

    private static final int RAW_LIMIT = 2000;

    private final int index;
    private final Kind kind;
    private final String detail;
    private final String rawToolArguments;
    private final String rawContent;

    private ExtractionAttempt(int index, Kind kind, String detail,
                              String rawToolArguments, String rawContent) {
        this.index = index;
        this.kind = kind;
        this.detail = detail;
        this.rawToolArguments = truncate(rawToolArguments);
        this.rawContent = truncate(rawContent);
    }

    static ExtractionAttempt ok(int index, LlmRawResponse raw) {
        return new ExtractionAttempt(index, Kind.OK, "", raw.toolArguments(), raw.content());
    }

    static ExtractionAttempt schemaError(int index, String detail, LlmRawResponse raw) {
        return new ExtractionAttempt(index, Kind.SCHEMA_ERROR, detail,
                raw.toolArguments(), raw.content());
    }

    static ExtractionAttempt transportError(int index, String detail) {
        return new ExtractionAttempt(index, Kind.TRANSPORT_ERROR, detail, null, null);
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= RAW_LIMIT ? text : text.substring(0, RAW_LIMIT) + "…";
    }

    public int index() {
        return index;
    }

    public Kind kind() {
        return kind;
    }

    /** 失败原因；通过时为空串 */
    public String detail() {
        return detail;
    }

    public String rawToolArguments() {
        return rawToolArguments;
    }

    public String rawContent() {
        return rawContent;
    }

    @Override
    public String toString() {
        return "第 " + index + " 次 " + kind + (detail.isEmpty() ? "" : "：" + detail);
    }
}
