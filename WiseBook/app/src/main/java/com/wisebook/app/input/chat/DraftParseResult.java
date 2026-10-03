package com.wisebook.app.input.chat;

import com.wisebook.app.data.local.entity.DraftEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次对话解析的结果。
 *
 * <p>{@link Status#DEGRADED} 是<b>正常路径</b>而不是异常：D1 定的防线是
 * 「校验失败 → 带错误反馈重试一次 → 再失败降级为手动填写表单」。
 * 调用方拿到 DEGRADED 应当展示表单，而不是弹错误框。
 *
 * <p>{@link #firstAttemptSucceeded} 与 {@link #attemptCount} 是留给论文用的：
 * 「首次通过率」「重试成功率」这类指标直接从这里统计，不必再去翻日志。
 */
public final class DraftParseResult {

    public enum Status {
        /** 解析成功，拿到草稿（P1 通常是一张） */
        OK,
        /** 重试用尽仍不合格 → 转人工填写表单 */
        DEGRADED,
        /** 输入本身为空之类，压根没调用模型 */
        REJECTED
    }

    private final Status status;
    private final List<DraftEntity> drafts;
    private final String message;
    private final int attemptCount;
    private final boolean firstAttemptSucceeded;
    private final String rawPayload;

    private DraftParseResult(Status status, List<DraftEntity> drafts, String message,
                             int attemptCount, boolean firstAttemptSucceeded, String rawPayload) {
        this.status = status;
        this.drafts = Collections.unmodifiableList(new ArrayList<>(drafts));
        this.message = message;
        this.attemptCount = attemptCount;
        this.firstAttemptSucceeded = firstAttemptSucceeded;
        this.rawPayload = rawPayload;
    }

    public static DraftParseResult ok(List<DraftEntity> drafts, int attemptCount,
                                      boolean firstAttemptSucceeded, String rawPayload) {
        return new DraftParseResult(Status.OK, drafts, null, attemptCount,
                firstAttemptSucceeded, rawPayload);
    }

    public static DraftParseResult degraded(String reason, int attemptCount) {
        return new DraftParseResult(Status.DEGRADED, Collections.<DraftEntity>emptyList(),
                reason, attemptCount, false, null);
    }

    public static DraftParseResult rejected(String reason) {
        return new DraftParseResult(Status.REJECTED, Collections.<DraftEntity>emptyList(),
                reason, 0, false, null);
    }

    /**
     * 模型这一轮<b>原始返回的 JSON</b>（紧凑格式）；没调用成功时为 {@code null}。
     *
     * <p>透传它不是为了业务逻辑——业务该用的字段都已经解析进草稿了。
     * 它是给「AI 处理详情」面板看的：<b>排查"模型为什么这么判"时，
     * 任何二次加工过的视图都可能把线索抹掉</b>，只有原始返回不会说谎。
     */
    public String rawPayload() {
        return rawPayload;
    }

    public Status status() {
        return status;
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    /** 是否需要转人工填写表单 */
    public boolean needsManualForm() {
        return status != Status.OK;
    }

    /** 解析出的草稿；非 OK 时为空列表 */
    public List<DraftEntity> drafts() {
        return drafts;
    }

    /** 便捷取第一张草稿；非 OK 时为 {@code null} */
    public DraftEntity firstDraft() {
        return drafts.isEmpty() ? null : drafts.get(0);
    }

    /** 降级 / 拒绝的原因；成功时为 {@code null} */
    public String message() {
        return message;
    }

    /** 实际调用模型的次数，从 1 开始；未调用时为 0 */
    public int attemptCount() {
        return attemptCount;
    }

    /** 是否首次调用即通过——论文里的「首次通过率」 */
    public boolean firstAttemptSucceeded() {
        return firstAttemptSucceeded;
    }

    @Override
    public String toString() {
        return "DraftParseResult{" + status
                + (isOk() ? ", " + drafts.size() + " 张草稿, 尝试 " + attemptCount + " 次"
                          : ", " + message)
                + "}";
    }
}
