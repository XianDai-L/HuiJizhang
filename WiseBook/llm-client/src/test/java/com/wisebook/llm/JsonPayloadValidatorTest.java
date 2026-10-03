package com.wisebook.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 结构校验器的测试。
 *
 * <p>校验依据直接取自 {@link ToolSchema#parameters()}，
 * 所以这里先验证「同一份 schema 双重用途」这条设计成立。
 */
public class JsonPayloadValidatorTest {

    private static final ToolSchema TOOL = ToolSchema.builder("create_draft")
            .integer("amountCents", "金额（分）", ToolSchema.Requirement.REQUIRED)
            .enumOf("direction", "方向", ToolSchema.Requirement.REQUIRED, "expense", "income")
            .string("merchant", "商户", ToolSchema.Requirement.OPTIONAL)
            .build();

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static List<String> validate(String payload) {
        return JsonPayloadValidator.validate(TOOL.parameters(), json(payload));
    }

    @Test
    public void acceptsValidPayload() {
        assertTrue(validate("{\"amountCents\":2800,\"direction\":\"expense\"}").isEmpty());
    }

    /** 可选字段缺失不算错误 */
    @Test
    public void missingOptionalFieldIsFine() {
        assertTrue(validate("{\"amountCents\":2800,\"direction\":\"income\"}").isEmpty());
    }

    @Test
    public void reportsMissingRequiredField() {
        List<String> issues = validate("{\"direction\":\"expense\"}");
        assertEquals("issueCount", 1, issues.size());
        assertTrue("should name the field", issues.get(0).contains("amountCents"));
    }

    @Test
    public void reportsNullRequiredField() {
        List<String> issues = validate("{\"amountCents\":null,\"direction\":\"expense\"}");
        assertEquals("issueCount", 1, issues.size());
        assertTrue("should name the field", issues.get(0).contains("amountCents"));
    }

    @Test
    public void reportsEnumViolation() {
        List<String> issues = validate("{\"amountCents\":2800,\"direction\":\"transfer\"}");
        assertEquals("issueCount", 1, issues.size());
        assertTrue("should name the field", issues.get(0).contains("direction"));
    }

    @Test
    public void reportsTypeMismatch() {
        List<String> issues = validate("{\"amountCents\":\"2800\",\"direction\":\"expense\"}");
        assertEquals("issueCount", 1, issues.size());
        assertTrue("should mention type", issues.get(0).contains("类型不符"));
    }

    /** 模型常见输出：整数写成 2800.0，应当接受 */
    @Test
    public void acceptsIntegralFloatForIntegerField() {
        assertTrue(validate("{\"amountCents\":2800.0,\"direction\":\"expense\"}").isEmpty());
    }

    @Test
    public void rejectsNonIntegralForIntegerField() {
        List<String> issues = validate("{\"amountCents\":2800.5,\"direction\":\"expense\"}");
        assertEquals("issueCount", 1, issues.size());
    }

    /**
     * 额外字段被忽略而非报错：为它重试一次的代价高于收益。
     */
    @Test
    public void ignoresUndeclaredExtraFields() {
        assertTrue(validate(
                "{\"amountCents\":2800,\"direction\":\"expense\",\"note\":\"模型自己加的\"}").isEmpty());
    }

    @Test
    public void reportsMultipleIssuesAtOnce() {
        List<String> issues = validate("{\"direction\":\"unknown\"}");
        assertEquals("issueCount", 2, issues.size());
    }
}
