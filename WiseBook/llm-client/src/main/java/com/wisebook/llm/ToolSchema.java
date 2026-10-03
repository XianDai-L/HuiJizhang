package com.wisebook.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 工具（function）定义。
 *
 * <p><b>关键设计：同一份 JSON Schema 既作为发给模型的约束，又作为返回值的校验依据。</b>
 * 见 {@link JsonPayloadValidator}。这样就不存在「提示词里写一套约束、代码里再写一套校验」
 * 的两处不一致问题——那种不一致是结构化输出翻车的常见根源。
 *
 * <p>用法：
 * <pre>
 * ToolSchema schema = ToolSchema.builder("create_draft")
 *         .description("把一句口语记账转换成结构化账目草稿")
 *         .integer("amountCents", "金额（单位：分）", Requirement.REQUIRED)
 *         .enumOf("direction", "收/支方向", Requirement.REQUIRED, "expense", "income")
 *         .string("merchant", "商户或对方", Requirement.OPTIONAL)
 *         .build();
 * </pre>
 */
public final class ToolSchema {

    /** 字段是否必填 */
    public enum Requirement {
        REQUIRED,
        OPTIONAL
    }

    private final String name;
    private final String description;
    private final JsonObject parameters;

    private ToolSchema(String name, String description, JsonObject parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** JSON Schema 本体（{@code type=object} + {@code properties} + {@code required}） */
    public JsonObject parameters() {
        return parameters;
    }

    /** 转成 OpenAI 协议里 {@code tools[].function} 的形状 */
    public JsonObject toFunctionJson() {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters);
        return function;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** 小型 schema 构造器：只覆盖记账场景需要的类型，不追求完整 JSON Schema */
    public static final class Builder {

        private final String name;
        private String description = "";
        private final JsonObject properties = new JsonObject();
        private final JsonArray required = new JsonArray();

        private Builder(String name) {
            this.name = name;
        }

        public Builder description(String text) {
            this.description = text == null ? "" : text;
            return this;
        }

        public Builder string(String field, String desc, Requirement requirement) {
            return add(field, "string", desc, requirement, null);
        }

        public Builder integer(String field, String desc, Requirement requirement) {
            return add(field, "integer", desc, requirement, null);
        }

        public Builder number(String field, String desc, Requirement requirement) {
            return add(field, "number", desc, requirement, null);
        }

        public Builder bool(String field, String desc, Requirement requirement) {
            return add(field, "boolean", desc, requirement, null);
        }

        /** 枚举字段：取值被强约束在给定集合内，模型不得自由发挥 */
        public Builder enumOf(String field, String desc, Requirement requirement, String... values) {
            JsonArray array = new JsonArray();
            for (String value : values) {
                array.add(value);
            }
            return add(field, "string", desc, requirement, array);
        }

        public Builder stringArray(String field, String desc, Requirement requirement) {
            JsonObject item = new JsonObject();
            item.addProperty("type", "string");
            JsonObject property = new JsonObject();
            property.addProperty("type", "array");
            property.addProperty("description", desc);
            property.add("items", item);
            properties.add(field, property);
            if (requirement == Requirement.REQUIRED) {
                required.add(field);
            }
            return this;
        }

        private Builder add(String field, String type, String desc,
                            Requirement requirement, JsonArray enumValues) {
            JsonObject property = new JsonObject();
            property.addProperty("type", type);
            property.addProperty("description", desc);
            if (enumValues != null) {
                property.add("enum", enumValues);
            }
            properties.add(field, property);
            if (requirement == Requirement.REQUIRED) {
                required.add(field);
            }
            return this;
        }

        public ToolSchema build() {
            JsonObject parameters = new JsonObject();
            parameters.addProperty("type", "object");
            parameters.add("properties", properties);
            if (!required.isEmpty()) {
                parameters.add("required", required);
            }
            return new ToolSchema(name, description, parameters);
        }
    }
}
