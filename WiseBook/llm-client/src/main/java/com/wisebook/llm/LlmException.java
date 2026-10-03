package com.wisebook.llm;

/**
 * 模型调用失败。
 *
 * <p>{@link ErrorKind} 决定上层如何处理：限流可以切换厂商重试，鉴权失败重试无意义。
 */
public class LlmException extends Exception {

    /** 失败类别 */
    public enum ErrorKind {
        /** 网络不可达、超时、DNS 失败 */
        TRANSPORT,
        /** 鉴权失败（401/403），重试无意义 */
        AUTH,
        /** 限流（429）—— 2026 年常见，需切厂商或退避 */
        RATE_LIMIT,
        /** 服务端错误（5xx） */
        SERVER,
        /** 响应结构不符合 OpenAI 协议 */
        BAD_RESPONSE
    }

    private final ErrorKind kind;
    private final int httpCode;

    public LlmException(ErrorKind kind, int httpCode, String message) {
        super(message);
        this.kind = kind;
        this.httpCode = httpCode;
    }

    public LlmException(ErrorKind kind, int httpCode, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpCode = httpCode;
    }

    public ErrorKind kind() {
        return kind;
    }

    /** HTTP 状态码；非 HTTP 层错误时为 0 */
    public int httpCode() {
        return httpCode;
    }
}
