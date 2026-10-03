package com.wisebook.llm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 容错流水线的测试。
 *
 * <p>全部使用假客户端，因此每条失败路径都能被稳定复现——不需要网络、不需要 API Key、
 * 不需要 Android。这正是把 {@link LlmClient} 抽成接口的目的。
 *
 * <p>被测的失败场景直接对应 D1 定的防线，以及硅基流动那个已知问题
 * 「长上下文下偶发 JSON 格式不完整」。
 */
public class StructuredExtractorTest {

    private static final String SYSTEM_PROMPT = "你是记账助手。";

    private static final ToolSchema DRAFT_TOOL = ToolSchema.builder("create_draft")
            .description("把口语记账转换成结构化草稿")
            .integer("amountCents", "金额（单位：分）", ToolSchema.Requirement.REQUIRED)
            .enumOf("direction", "收/支方向", ToolSchema.Requirement.REQUIRED, "expense", "income")
            .string("merchant", "商户或对方", ToolSchema.Requirement.OPTIONAL)
            .build();

    private static final String VALID = """
            {"amountCents":2800,"direction":"expense","merchant":"滴滴"}""";

    private StructuredExtractor extractor(FakeLlmClient client) {
        return new StructuredExtractor(client);
    }

    private ExtractionResult run(FakeLlmClient client) {
        return extractor(client).extract(SYSTEM_PROMPT, "今天打车28块", DRAFT_TOOL);
    }

    // ---------------------------------------------------------------- 顺利路径

    @Test
    public void succeedsOnFirstAttempt() {
        FakeLlmClient client = new FakeLlmClient().thenReturn(FakeLlmClient.arguments(VALID));

        ExtractionResult result = run(client);

        assertTrue("should be OK", result.isOk());
        assertEquals("attemptCount", 1, result.attemptCount());
        assertTrue("firstAttemptSucceeded", result.firstAttemptSucceeded());
        assertEquals("amountCents", 2800, result.payload().get("amountCents").getAsInt());
        assertEquals("direction", "expense", result.payload().get("direction").getAsString());
    }

    /** 有些模型不给 tool_calls，把 JSON 写在正文里，还可能包在 Markdown 代码块里 */
    @Test
    public void fallsBackToContentWhenToolCallsMissing() {
        FakeLlmClient client = new FakeLlmClient().thenReturn(FakeLlmClient.contentOnly("""
                好的，结果如下：
                ```json
                {"amountCents":2800,"direction":"expense"}
                ```
                请确认。"""));

        ExtractionResult result = run(client);

        assertTrue("should be OK", result.isOk());
        assertEquals("attemptCount", 1, result.attemptCount());
        assertEquals("amountCents", 2800, result.payload().get("amountCents").getAsInt());
    }

    // ------------------------------------------------------------ 重试修复路径

    /** 输出被截断——硅基流动在长上下文下的已知现象 */
    @Test
    public void retriesAfterTruncatedJson() {
        FakeLlmClient client = new FakeLlmClient()
                .thenReturn(FakeLlmClient.arguments("{\"amountCents\":2800,\"direction\":\"exp"))
                .thenReturn(FakeLlmClient.arguments(VALID));

        ExtractionResult result = run(client);

        assertTrue("should be OK", result.isOk());
        assertEquals("attemptCount", 2, result.attemptCount());
        assertEquals("firstAttempt", ExtractionAttempt.Kind.SCHEMA_ERROR,
                result.attempts().get(0).kind());
        assertFalse("firstAttemptSucceeded", result.firstAttemptSucceeded());
    }

    @Test
    public void retriesAfterMissingRequiredField() {
        FakeLlmClient client = new FakeLlmClient()
                .thenReturn(FakeLlmClient.arguments("{\"direction\":\"expense\"}"))
                .thenReturn(FakeLlmClient.arguments(VALID));

        ExtractionResult result = run(client);

        assertTrue("should be OK", result.isOk());
        assertTrue("detail should mention field",
                result.attempts().get(0).detail().contains("amountCents"));
    }

    @Test
    public void retriesAfterEnumViolation() {
        FakeLlmClient client = new FakeLlmClient()
                .thenReturn(FakeLlmClient.arguments("{\"amountCents\":2800,\"direction\":\"transfer\"}"))
                .thenReturn(FakeLlmClient.arguments(VALID));

        ExtractionResult result = run(client);

        assertTrue("should be OK", result.isOk());
        assertTrue("detail should mention enum",
                result.attempts().get(0).detail().contains("direction"));
    }

    @Test
    public void retriesAfterTypeMismatch() {
        FakeLlmClient client = new FakeLlmClient()
                .thenReturn(FakeLlmClient.arguments("{\"amountCents\":\"2800\",\"direction\":\"expense\"}"))
                .thenReturn(FakeLlmClient.arguments(VALID));

        ExtractionResult result = run(client);

        assertTrue("should be OK", result.isOk());
        assertTrue("detail should mention type",
                result.attempts().get(0).detail().contains("类型不符"));
    }

    @Test
    public void retriesAfterEmptyResponse() {
        FakeLlmClient client = new FakeLlmClient()
                .thenReturn(FakeLlmClient.empty())
                .thenReturn(FakeLlmClient.arguments(VALID));

        ExtractionResult result = run(client);

        assertTrue("should be OK", result.isOk());
        assertEquals("attemptCount", 2, result.attemptCount());
    }

    /**
     * 修正型重试的关键：第二次请求必须带上上一次的具体错误。
     * 裸重试（原样再问一遍）成功率很低，因为模型不知道自己哪里错了。
     */
    @Test
    public void retryCarriesPreviousErrorsBackToModel() {
        FakeLlmClient client = new FakeLlmClient()
                .thenReturn(FakeLlmClient.arguments("{\"direction\":\"expense\"}"))
                .thenReturn(FakeLlmClient.arguments(VALID));

        run(client);

        String secondRequest = client.userContentAt(1);
        assertTrue("原始输入应保留", secondRequest.contains("今天打车28块"));
        assertTrue("应带上校验反馈", secondRequest.contains("未通过校验"));
        assertTrue("应指出具体缺哪个字段", secondRequest.contains("amountCents"));
    }

    // ---------------------------------------------------------------- 降级路径

    @Test
    public void degradesWhenRetriesExhausted() {
        FakeLlmClient client = new FakeLlmClient()
                .thenReturn(FakeLlmClient.arguments("{\"direction\":\"expense\"}"))
                .thenReturn(FakeLlmClient.arguments("{\"direction\":\"expense\"}"));

        ExtractionResult result = run(client);

        assertFalse("should be DEGRADED", result.isOk());
        assertEquals("attemptCount", 2, result.attemptCount());
        assertNotNull("degradeReason", result.degradeReason());
        assertEquals("payload should be null", null, result.payload());
    }

    @Test
    public void degradesOnTransportErrorWithoutRetrying() {
        FakeLlmClient client = new FakeLlmClient()
                .thenFail(new LlmException(LlmException.ErrorKind.RATE_LIMIT, 429, "限流"))
                .thenReturn(FakeLlmClient.arguments(VALID));

        ExtractionResult result = run(client);

        assertFalse("should be DEGRADED", result.isOk());
        assertEquals("transport error must not be retried", 1, client.callCount());
        assertEquals("attemptKind", ExtractionAttempt.Kind.TRANSPORT_ERROR,
                result.attempts().get(0).kind());
        assertTrue("reason should mention RATE_LIMIT", result.degradeReason().contains("RATE_LIMIT"));
    }

    @Test
    public void respectsConfiguredAttemptBudget() {
        FakeLlmClient client = new FakeLlmClient()
                .thenReturn(FakeLlmClient.arguments("{}"))
                .thenReturn(FakeLlmClient.arguments("{}"))
                .thenReturn(FakeLlmClient.arguments("{}"));

        ExtractionResult result = new StructuredExtractor(client, 3)
                .extract(SYSTEM_PROMPT, "今天打车28块", DRAFT_TOOL);

        assertFalse("should be DEGRADED", result.isOk());
        assertEquals("attemptCount", 3, result.attemptCount());
        assertEquals("callCount", 3, client.callCount());
    }

    // ------------------------------------------------------------ 降级提取工具

    @Test
    public void jsonObjectInExtractsBareObject() {
        assertEquals("{\"a\":1}", StructuredExtractor.jsonObjectIn("前缀 {\"a\":1} 后缀"));
        assertEquals(null, StructuredExtractor.jsonObjectIn("没有花括号"));
        assertEquals(null, StructuredExtractor.jsonObjectIn(null));
    }
}
