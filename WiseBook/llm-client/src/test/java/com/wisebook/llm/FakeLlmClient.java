package com.wisebook.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的假客户端：按脚本依次返回预设响应。
 *
 * <p>它的存在正是 {@link LlmClient} 抽出来的目的——让「校验 + 重试 + 降级」
 * 这套容错逻辑能在<b>没有网络、没有 API Key、没有 Android</b> 的情况下被完整测到。
 */
final class FakeLlmClient implements LlmClient {

    private final List<Object> scripted = new ArrayList<>();
    private final List<String> receivedUserContents = new ArrayList<>();
    private int cursor = 0;

    /** 脚本项：要返回的原始响应 */
    FakeLlmClient thenReturn(LlmRawResponse response) {
        scripted.add(response);
        return this;
    }

    /** 脚本项：抛出调用异常 */
    FakeLlmClient thenFail(LlmException exception) {
        scripted.add(exception);
        return this;
    }

    /** 调用次数 */
    int callCount() {
        return receivedUserContents.size();
    }

    /** 第 N 次调用实际收到的用户内容（从 0 开始），用于验证重试时是否带上了错误反馈 */
    String userContentAt(int index) {
        return receivedUserContents.get(index);
    }

    @Override
    public LlmRawResponse chatWithTool(String systemPrompt, String userContent, ToolSchema tool)
            throws LlmException {
        receivedUserContents.add(userContent);
        if (cursor >= scripted.size()) {
            throw new IllegalStateException("假客户端脚本已用尽（第 " + (cursor + 1) + " 次调用）");
        }
        Object next = scripted.get(cursor++);
        if (next instanceof LlmException) {
            throw (LlmException) next;
        }
        return (LlmRawResponse) next;
    }

    // ---------------------------------------------------------- 便捷构造

    /** 返回一段 tool_calls.arguments 文本 */
    static LlmRawResponse arguments(String json) {
        return new LlmRawResponse("fake-model", json, null, "{\"raw\":\"stub\"}");
    }

    /** 只返回正文，不给 tool_calls（用于测试降级提取路径） */
    static LlmRawResponse contentOnly(String content) {
        return new LlmRawResponse("fake-model", null, content, "{\"raw\":\"stub\"}");
    }

    /** 什么都没返回 */
    static LlmRawResponse empty() {
        return new LlmRawResponse("fake-model", null, null, "{}");
    }
}
