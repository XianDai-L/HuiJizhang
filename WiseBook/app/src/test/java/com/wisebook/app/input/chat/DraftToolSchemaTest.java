package com.wisebook.app.input.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wisebook.app.TestFixtures;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.PaymentMethod;
import com.wisebook.llm.ToolSchema;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具定义（D2 §6.6 的「function calling 强约束」）。
 *
 * <p>要守住的核心是：<b>分类枚举是从分类树现算出来的</b>。
 * 它是"用协议层面杜绝自创分类"这个设计能否成立的地方——
 * 一旦枚举写死或漏了某一级，模型就有机会编出一个不存在的分类。
 */
public class DraftToolSchemaTest {

    @Test
    public void onlyDirectionIsRequired() {
        ToolSchema schema = DraftToolSchema.build(TestFixtures.tree(), CategoryScheme.STANDARD);
        JsonObject parameters = schema.parameters();

        JsonArray required = parameters.getAsJsonArray("required");
        assertEquals("其它字段一律可选，让草稿能承载「还没凑齐」的状态",
                1, required.size());
        assertEquals(DraftToolSchema.FIELD_DIRECTION, required.get(0).getAsString());
    }

    @Test
    public void declaresEveryFieldTheParserReads() {
        JsonObject properties = DraftToolSchema
                .build(TestFixtures.tree(), CategoryScheme.STANDARD)
                .parameters().getAsJsonObject("properties");

        for (String field : new String[]{
                DraftToolSchema.FIELD_DIRECTION,
                DraftToolSchema.FIELD_AMOUNT_CENTS,
                DraftToolSchema.FIELD_AMOUNT_RAW,
                DraftToolSchema.FIELD_OCCURRED_AT_TEXT,
                DraftToolSchema.FIELD_CATEGORY_PATH,
                DraftToolSchema.FIELD_PAYMENT_METHOD,
                DraftToolSchema.FIELD_MERCHANT,
                DraftToolSchema.FIELD_ITEMS,
                DraftToolSchema.FIELD_NOTE,
                DraftToolSchema.FIELD_TRANSACTION_COUNT}) {
            assertTrue("schema 缺少字段 " + field, properties.has(field));
        }
    }

    @Test
    public void paymentMethodEnumMatchesTheDomainEnum() {
        JsonObject properties = DraftToolSchema
                .build(TestFixtures.tree(), CategoryScheme.STANDARD)
                .parameters().getAsJsonObject("properties");
        JsonArray allowed = properties.getAsJsonObject(DraftToolSchema.FIELD_PAYMENT_METHOD)
                .getAsJsonArray("enum");

        assertEquals(PaymentMethod.values().length, allowed.size());
        assertTrue(allowed.toString().contains(PaymentMethod.WECHAT.code()));
    }

    @Test
    public void categoryEnumIsStandardSchemeLastLevel() {
        List<String> paths = DraftToolSchema
                .categoryPaths(TestFixtures.tree(), CategoryScheme.STANDARD);

        assertTrue("有二级的一级要列到二级", paths.contains("餐饮>咖啡"));
        assertTrue("没有二级的一级本身就是末级", paths.contains("其他"));
        assertTrue("收入一级也要列进去", paths.contains("工资"));
        assertFalse("一级分类自己不作为可选值", paths.contains("餐饮"));
    }

    @Test
    public void categoryEnumFollowsTheScheme() {
        List<String> simple = DraftToolSchema
                .categoryPaths(TestFixtures.tree(), CategoryScheme.SIMPLE);

        assertTrue(simple.contains("餐饮"));
        assertFalse("简单方案不给二级", simple.contains("餐饮>咖啡"));
    }

    @Test
    public void categoryEnumGoesIntoTheSchema() {
        JsonObject properties = DraftToolSchema
                .build(TestFixtures.tree(), CategoryScheme.STANDARD)
                .parameters().getAsJsonObject("properties");
        JsonArray allowed = properties.getAsJsonObject(DraftToolSchema.FIELD_CATEGORY_PATH)
                .getAsJsonArray("enum");

        List<String> values = stringList(allowed);
        assertEquals(DraftToolSchema.categoryPaths(TestFixtures.tree(), CategoryScheme.STANDARD),
                values);
    }

    @Test
    public void emptyCategoryTreeDegradesToFreeTextInsteadOfEmptyEnum() {
        // 分类表还没播种时，空 enum 会把模型的每一次输出都判成非法——
        // 那比"让它自由输出、随后判成解析不出来去问用户"糟糕得多
        JsonObject properties = DraftToolSchema
                .build(new com.wisebook.app.domain.classify.CategoryTree(List.of()),
                        CategoryScheme.STANDARD)
                .parameters().getAsJsonObject("properties");
        JsonObject categoryPath = properties.getAsJsonObject(DraftToolSchema.FIELD_CATEGORY_PATH);

        assertFalse("不该给出 enum", categoryPath.has("enum"));
        assertEquals("string", categoryPath.get("type").getAsString());
    }

    private static List<String> stringList(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            values.add(array.get(i).getAsString());
        }
        return values;
    }
}
