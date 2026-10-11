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

    /**
     * 单笔字段契约的名字。
     *
     * <p>2026-10-08 起它<b>不再作为顶层工具发给模型</b>：两个入口都走批量工具
     * （决策 50），这份定义的作用变成"批量数组的元素 schema"（见 {@link #buildBatch}）。
     * 名字保留是因为它同时也是字段定义本身的名字——元素到底描述的是什么，得有名字可叫。
     */
    public static final String TOOL_NAME = "create_draft";

    /** 批量工具名：外层是数组，元素是 {@link #build} 那份单笔字段定义 */
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
                            + "能判断到二级就给二级；只能判断到一级时给一级也可以。"
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
                .build();
    }

    /**
     * 多笔的工具定义：外层一个数组，<b>元素就是 {@link #build} 那份单笔 schema</b>。
     *
     * <p>P2 加它时只给截图入口用（「一图多笔」）。2026-10-08 起<b>文字入口也走它</b>
     * （决策 50，依据 `docs/对话多笔录入实验.md`）——所以现在两个入口共用同一个工具，
     * 差异只在提示词里"顺序按句中出现次序还是图中从上到下"。
     *
     * <p>元素 schema 是同一个对象引用而不是深拷贝：数组元素与单笔本该是同一套字段定义，
     * 拷贝一份反而让「改了单笔、忘了改数组」重新成为可能。
     *
     * <p><b>数组刻意不设 {@code maxItems} 上限</b>（2026-10-08 用户拍板）：设了之后，
     * 超限会让整批被判非法 → 重试 → 整批降级转人工，而"真有一张二十笔的长账单截图"
     * 恰恰是最需要自动拆的场景。防过拆靠 prompt 里那句"拆分边界只看金额 /
     * 不是金额的数字不要拆成一笔"，而不是靠 schema 一刀切。
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
     * 允许的分类路径清单：两个方向的<b>末级分类 + 「有二级的一级」</b>。
     *
     * <p>两个方向放进同一个枚举，是因为模型在看到句子之前无法先知道方向。
     * 方向与路径不匹配的情况（如一笔支出选了「工资」）由
     * {@link CategoryTree#idOfPath} 在解析时拦下，结果是"分类没定下来 → 落默认桶"。
     *
     * <p><b>为什么一级也要进枚举</b>（2026-10-08 修，HANDOFF §8-31）：
     * prompt 明确让模型「转账一律选<b>「人情」</b>或其下的二级」，而枚举原先只列末级
     * （{@code 人情>红包} / {@code 人情>礼物} / {@code 人情>请客}）——<b>提示词让模型给一个
     * 协议层禁止的取值</b>，于是每笔转账都白重试一次（实测首次通过率因此掉到 4/6）。
     * 而"落在一级"本来就是这个系统的正常形态：转账没有可用分类时，
     * {@code CategoryClassifier} 的兜底写的就是一级「人情」（决策 32），
     * 报表按一级聚合（D1 §6.2），{@code rootIdOf} / {@code pathOf} 对它都成立。
     *
     * <p>于是现在枚举允许两级，粒度靠 prompt 引导（能判断到二级就给二级，
     * 判断不到再给一级）——而不是让模型为了满足 schema 去<b>编一个二级</b>，
     * 那样得到的"红包 / 礼物 / 请客"是假信息。
     *
     * <p>简单方案下 {@code selectable} 返回的就是一级，这里不会再重复添加（有 {@code contains} 去重）。
     */
    public static List<String> categoryPaths(CategoryTree tree, CategoryScheme scheme) {
        List<String> paths = new ArrayList<>();
        for (Direction direction : new Direction[]{Direction.EXPENSE, Direction.INCOME}) {
            for (CategoryEntity category : tree.selectable(direction, scheme)) {
                addPath(paths, tree, category.categoryId);
            }
            for (CategoryEntity top : tree.topLevel(direction)) {
                // 没有二级的一级（如「其他」「工资」）本来就在 selectable 里，别重复
                if (!tree.children(top.categoryId).isEmpty()) {
                    addPath(paths, tree, top.categoryId);
                }
            }
        }
        return paths;
    }

    private static void addPath(List<String> paths, CategoryTree tree, long categoryId) {
        String path = tree.pathOf(categoryId);
        if (path != null && !paths.contains(path)) {
            paths.add(path);
        }
    }
}
