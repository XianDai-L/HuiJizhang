package com.wisebook.app.input;

import com.wisebook.llm.LlmClient;
import com.wisebook.llm.LlmException;
import com.wisebook.llm.LlmRawResponse;
import com.wisebook.llm.ToolSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的假客户端：按脚本依次返回预设响应。
 *
 * <p>它与 {@code llm-client} 模块里那个同名类是两回事——那个是包内可见的，
 * 只服务于该模块的重试/降级测试。这里是解析器测试自己的替身，
 * 目的是让<b>整条「输入 → 草稿」链路在没有网络、没有 Key、没有 Android 的情况下可测</b>。
 *
 * <p>它同时记录了每次调用实际收到的 userContent，
 * 这样"重试时有没有把上一次的错误回传"「转写文本到底长什么样」这类细节也能被断言。
 *
 * <p>P2 起它住在 {@code input} 包（原来是 {@code input.chat} 的包私有类）：
 * 截图入口的解析器测试同样要它，而"两个入口用同一个替身"正好说明两者走的是同一条管线。
 */
public final class FakeLlmClient implements LlmClient {

    private final List<Object> scripted = new ArrayList<>();
    private final List<String> receivedUserContents = new ArrayList<>();
    private int cursor = 0;

    public FakeLlmClient thenReturn(LlmRawResponse response) {
        scripted.add(response);
        return this;
    }

    /** 脚本项：返回一段 tool_calls.arguments 文本 */
    public FakeLlmClient thenReturnPayload(String json) {
        return thenReturn(arguments(json));
    }

    /** 脚本项：只返回正文，不给 tool_calls */
    public FakeLlmClient thenReturnContentOnly(String content) {
        return thenReturn(new LlmRawResponse("fake-model", null, content, "{\"raw\":\"stub\"}"));
    }

    /** 脚本项：抛出调用异常 */
    public FakeLlmClient thenFail(LlmException exception) {
        scripted.add(exception);
        return this;
    }

    public int callCount() {
        return receivedUserContents.size();
    }

    public String userContentAt(int index) {
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

    public static LlmRawResponse arguments(String json) {
        return new LlmRawResponse("fake-model", json, null, "{\"raw\":\"stub\"}");
    }
}
