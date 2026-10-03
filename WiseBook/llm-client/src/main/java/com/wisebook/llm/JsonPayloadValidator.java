package com.wisebook.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 返回值的结构校验。
 *
 * <p>校验规则直接来自 {@link ToolSchema#parameters()} —— <b>发给模型的约束和校验返回值的依据是同一份
 * JSON Schema</b>，避免「提示词一套、代码一套」的两处不一致。
 *
 * <p>只实现记账场景需要的子集：{@code required} / {@code type} / {@code enum}。
 * 不引入完整的 JSON Schema 引擎——那会带来远超需求的复杂度。
 *
 * <p>未在 schema 中声明的额外字段会被<b>忽略</b>，不视为错误：重试一次的代价高于收益。
 */
public final class JsonPayloadValidator {

    private JsonPayloadValidator() {
    }

    /**
     * @param schema  工具参数的 JSON Schema（{@code type/properties/required}）
     * @param payload 模型返回的对象
     * @return 问题列表；为空表示通过
     */
    public static List<String> validate(JsonObject schema, JsonObject payload) {
        List<String> issues = new ArrayList<>();
        if (schema == null) {
            return issues;
        }
        if (payload == null) {
            issues.add("返回内容不是 JSON 对象");
            return issues;
        }

        checkRequired(schema, payload, issues);

        JsonObject properties = schema.getAsJsonObject("properties");
        if (properties == null) {
            return issues;
        }
        for (String field : properties.keySet()) {
            JsonElement value = payload.get(field);
            if (value == null || value.isJsonNull()) {
                // 缺省或已在 required 检查中报过，这里不重复报
                continue;
            }
            JsonObject definition = properties.getAsJsonObject(field);
            if (definition == null) {
                continue;
            }
            checkEnum(field, definition, value, issues);
            checkType(field, definition, value, issues);
        }
        return issues;
    }

    private static void checkRequired(JsonObject schema, JsonObject payload, List<String> issues) {
        JsonArray required = schema.getAsJsonArray("required");
        if (required == null) {
            return;
        }
        for (JsonElement element : required) {
            String field = element.getAsString();
            JsonElement value = payload.get(field);
            if (value == null || value.isJsonNull()) {
                issues.add("缺少必填字段：" + field);
            }
        }
    }

    private static void checkEnum(String field, JsonObject definition, JsonElement value,
                                  List<String> issues) {
        JsonArray allowed = definition.getAsJsonArray("enum");
        if (allowed == null) {
            return;
        }
        if (!allowed.contains(value)) {
            issues.add("字段 " + field + " 取值超出允许范围：" + value + "（允许 " + allowed + "）");
        }
    }

    private static void checkType(String field, JsonObject definition, JsonElement value,
                                  List<String> issues) {
        JsonElement declared = definition.get("type");
        if (declared == null || declared.isJsonNull()) {
            return;
        }
        List<String> types = new ArrayList<>();
        if (declared.isJsonArray()) {
            for (JsonElement element : declared.getAsJsonArray()) {
                types.add(element.getAsString());
            }
        } else {
            types.add(declared.getAsString());
        }

        for (String type : types) {
            if (matches(value, type)) {
                return;
            }
        }
        issues.add("字段 " + field + " 类型不符：期望 " + declared + "，实际 " + value);
    }

    private static boolean matches(JsonElement value, String type) {
        switch (type) {
            case "string":
                return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "boolean":
                return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "number":
                return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            case "integer":
                return value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isNumber()
                        && isIntegral(value.getAsJsonPrimitive().getAsString());
            case "array":
                return value.isJsonArray();
            case "object":
                return value.isJsonObject();
            case "null":
                return value.isJsonNull();
            default:
                // 未支持的类型不误报
                return true;
        }
    }

    /** 接受 {@code 35} 与 {@code 35.0}，拒绝 {@code 35.5} */
    private static boolean isIntegral(String text) {
        try {
            new BigDecimal(text).toBigIntegerExact();
            return true;
        } catch (ArithmeticException | NumberFormatException e) {
            return false;
        }
    }
}
