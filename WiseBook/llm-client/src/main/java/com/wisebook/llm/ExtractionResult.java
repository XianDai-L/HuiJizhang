package com.wisebook.llm;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;

/**
 * 结构化提取的最终结果。
 *
 * <p>{@link Status#DEGRADED} 是<b>正常路径而非异常</b>：D1 定的防线是
 * 「校验失败 → 重试一次 → 再失败降级为手动填写表单」。调用方拿到 DEGRADED
 * 时应当展示表单，而不是报错。
 */
public final class ExtractionResult {

    /** 结果状态 */
    public enum Status {
        /** 已通过 schema 校验 */
        OK,
        /** 重试用尽仍不合格，转人工填写 */
        DEGRADED
    }

    private final Status status;
    private final JsonObject payload;
    private final List<ExtractionAttempt> attempts;
    private final String degradeReason;

    private ExtractionResult(Status status, JsonObject payload,
                             List<ExtractionAttempt> attempts, String degradeReason) {
        this.status = status;
        this.payload = payload;
        this.attempts = Collections.unmodifiableList(attempts);
        this.degradeReason = degradeReason;
    }

    public static ExtractionResult ok(JsonObject payload, List<ExtractionAttempt> attempts) {
        return new ExtractionResult(Status.OK, payload, attempts, null);
    }

    public static ExtractionResult degraded(List<ExtractionAttempt> attempts, String reason) {
        return new ExtractionResult(Status.DEGRADED, null, attempts, reason);
    }

    public Status status() {
        return status;
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    /** 通过校验的结构化结果；{@link #isOk()} 为 false 时为 null */
    public JsonObject payload() {
        return payload;
    }

    /** 每一次尝试的记录，供溯源与实验统计 */
    public List<ExtractionAttempt> attempts() {
        return attempts;
    }

    /** 降级原因；{@link #isOk()} 为 true 时为 null */
    public String degradeReason() {
        return degradeReason;
    }

    /** 实际调用次数，从 1 开始 */
    public int attemptCount() {
        return attempts.size();
    }

    /** 是否首次尝试即通过——论文里的「首次通过率」 */
    public boolean firstAttemptSucceeded() {
        return attempts.size() == 1 && attempts.get(0).kind() == ExtractionAttempt.Kind.OK;
    }

    @Override
    public String toString() {
        return isOk()
                ? "ExtractionResult{OK, 尝试 " + attempts.size() + " 次}"
                : "ExtractionResult{DEGRADED, " + degradeReason + "}";
    }
}
