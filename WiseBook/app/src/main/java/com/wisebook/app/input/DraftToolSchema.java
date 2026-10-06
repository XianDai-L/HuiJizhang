package com.wisebook.app.input;

import com.wisebook.app.data.local.entity.CategoryEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.PaymentMethod;
import com.wisebook.llm.ToolSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * 账目草稿的工具定义（发给模型的 JSON Schema，同时也是校验返回值的依据）。
 *
 * <p><b>关键设计：分类列表是运行时从 {@code t_category} 动态生成的枚举。</b>
 * 模型不知道本地的自增 id，只知道名字，所以让它输出分类<b>路径</b>（如 {@code 餐饮>咖啡}）；
 * 而把路径做成 {@code enum}，就等于用协议层面杜绝了「自创分类」这类幻觉——
 * 模型连编都编不出来，因为它不在允许集合里。
 *
 * <p>顺带解决了另一件事：用户自定义分类会即时生效，因为枚举是从库里现取的。
 *
 * <p><b>绝大多数字段都是可选的，只把 direction 设为必填。</b>
 * 理由是草稿的本质就是「还没凑齐的一笔账」：强行必填会让「没提到金额」这种
 * 最常见的情况直接判为校验失败、白白走一遍重试再降级。
 * 「必填」这件事交给 {@code ConfirmPolicy} 去判——它知道档位，也知道缺哪个字段该问什么。
 *
 * <p><b>为什么它从 {@code input.chat} 搬到了 {@code input}</b>（P2）：它描述的是
 * 「一笔账目」的字段契约，与入口无关。截图入口要产出的是同一批字段，
 * 所以必须共用这一份——否则「单笔一套、截图另一套」会立刻分叉（见 {@link #buildBatch}）。
 */
public final class DraftToolSchema {

    public static final String TOOL_NAME = "create_draft";

    /** 一图多笔用的工具名：外层是数组，元素仍是同一份单笔字段定义 */
    public static final String BATCH_TOOL_NAME = "create_draft_batch";

    /** 批量输出里装账目的字段名 */
    public static final String FIELD_DRAFTS = "drafts";

    // 字段名常量：解析端（DraftAssembler）与这里共用一份，避免字符串两边各写一遍
    public static final String FIELD_DIRECTION = "direction";
    public static final String FIELD_AMOUNT_CENTS = "amountCents";
    public static final String FIELD_AMOUNT_RAW = "amountRaw";
    public static final String FIELD_OCCURRED_AT_TEXT = "occurredAtText";
    public static final String FIELD_CATEGORY_PATH = "categoryPath";
    public static final String FIELD_PAYMENT_METHOD = "paymentMethod";
    public static final String FIELD_MERCHANT = "merchant";
    public static final String FIELD_ITEMS = "items";
    public static final String FIELD_NOTE = "note";
    public static final String FIELD_TRANSACTION_COUNT = "transactionCount";

    /** 模型最多被允许报出的笔数。超过这个数直接当没填，避免它给出荒谬的数字扩大追问范围 */
    public static final int MAX_TRANSACTION_COUNT = 20;

    private DraftToolSchema() {
    }

    /**
     * @param tree   当前分类树（只含未停用分类）
     * @param scheme 当前分类方案，决定枚举里给到一级还是二级
     */
    public static ToolSchema build(CategoryTree tree, CategoryScheme scheme) {
        List<String> paths = categoryPaths(tree, scheme);

        ToolSchema.Builder builder = ToolSchema.builder(TOOL_NAME)
                .description("把一句口语记账转换成结构化账目草稿")
                .enumOf(FIELD_DIRECTION,
                        "收支方向。支出填 expense，收入填 income，"
                                + "转账（给别人转钱、还信用卡、还花呗、取现）填 transfer",
                        ToolSchema.Requirement.REQUIRED,
                        Direction.EXPENSE.code(), Direction.INCOME.code(), Direction.TRANSFER.code())
                .integer(FIELD_AMOUNT_CENTS,
                        "金额，单位是分（28 元填 2800）。句中没提到金额时不要填这个字段",
                        ToolSchema.Requirement.OPTIONAL)
                .string(FIELD_AMOUNT_RAW,
                        "金额在原句中的原文片段，原样抄写，如「三百五」「28」",
                        ToolSchema.Requirement.OPTIONAL)
                .string(FIELD_OCCURRED_AT_TEXT,
                        "时间短语的原文，如「昨天下午」「上周三」。不要换算成日期",
                        ToolSchema.Requirement.OPTIONAL);

        if (paths.isEmpty()) {
            // 分类表还没播种好（理论上不该发生）。此时不挂 enum 约束，
            // 让模型自由输出、由分类层判成"解析不出来"再去问用户——
            // 总好过给一个空 enum 把所有输出都判成非法。
            builder.string(FIELD_CATEGORY_PATH, "分类路径", ToolSchema.Requirement.OPTIONAL);
        } else {
            builder.enumOf(FIELD_CATEGORY_PATH,
                    "分类路径，只能从这个枚举里选。支出选支出分类，收入选收入分类，"
                            + "转账选「人情」或其下的二级（不要选「其他」）。"
                            + "实在判断不出就留空",
                    ToolSchema.Requirement.OPTIONAL, paths.toArray(new String[0]));
        }

        String[] methods = new String[PaymentMethod.values().length];
        for (int i = 0; i < PaymentMethod.values().length; i++) {
            methods[i] = PaymentMethod.values()[i].code();
        }

        return builder
                .enumOf(FIELD_PAYMENT_METHOD, "支付方式", ToolSchema.Requirement.OPTIONAL, methods)
                .string(FIELD_MERCHANT,
                        "具体的商户、平台或对方名称（如「滴滴」「星巴克」「张三」）。"
                                + "不要把动作或品类填进来；无法确定时留空",
                        ToolSchema.Requirement.OPTIONAL)
                .stringArray(FIELD_ITEMS, "商品明细（可空）", ToolSchema.Requirement.OPTIONAL)
                .string(FIELD_NOTE, "备注（可空）", ToolSchema.Requirement.OPTIONAL)
                .integer(FIELD_TRANSACTION_COUNT,
                        "这句话里实际包含几笔账。只有一笔时填 1",
                        ToolSchema.Requirement.OPTIONAL)
                .build();
    }

    /**
     * 「一图多笔」的工具定义（P2）：外层一个数组，<b>元素就是 {@link #build} 那份单笔 schema</b>。
     *
     * <p>单独做一个工具而不是把单笔也改成数组，是因为文字入口的语义就是"一句话一笔"
     * （多笔时只报 {@code transactionCount}，决策 14）。让两个入口各用各的工具，
     * 好处是各自的重试反馈都精确到自己的形状；而元素字段共用一份定义，
     * 又保证了两边不会在字段上分叉。
     */
    public static ToolSchema buildBatch(CategoryTree tree, CategoryScheme scheme) {
        return ToolSchema.builder(BATCH_TOOL_NAME)
                .description("把一张账单截图（或一段含多笔的文字）拆成多笔结构化账目草稿")
                .objectArray(FIELD_DRAFTS,
                        "账目草稿数组：每一笔一个元素，按图中从上到下的顺序排列。"
                                + "只有一笔时数组里也放一个元素",
                        ToolSchema.Requirement.REQUIRED,
                        build(tree, scheme))
                .build();
    }

    /**
     * 允许的分类路径清单：两个方向的末级分类。
     *
     * <p>两个方向放进同一个枚举，是因为模型在看到句子之前无法先知道方向。
     * 方向与路径不匹配的情况（如一笔支出选了「工资」）由
     * {@link CategoryTree#idOfPath} 在解析时拦下，结果是"分类没定下来 → 反问用户"。
     */
    public static List<String> categoryPaths(CategoryTree tree, CategoryScheme scheme) {
        List<String> paths = new ArrayList<>();
        for (Direction direction : new Direction[]{Direction.EXPENSE, Direction.INCOME}) {
            for (CategoryEntity category : tree.selectable(direction, scheme)) {
                String path = tree.pathOf(category.categoryId);
                if (path != null && !paths.contains(path)) {
                    paths.add(path);
                }
            }
        }
        return paths;
    }
}
