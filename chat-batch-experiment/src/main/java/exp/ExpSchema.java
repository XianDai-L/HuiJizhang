package exp;

import com.wisebook.llm.ToolSchema;

/**
 * 实验用工具定义：<b>单笔</b>与<b>批量</b>两份，逐字对齐产品 {@code DraftToolSchema}。
 *
 * <p>三份约束必须与产品一致，否则测出来的是 schema 的松紧差异，不是策略差异：
 * <ul>
 *   <li>{@link #single()} ↔ 产品 {@code DraftToolSchema.build}：只有 {@code direction} 必填，
 *       分类是枚举，其它字段一律可选</li>
 *   <li>{@link #batch()} ↔ 产品 {@code DraftToolSchema.buildBatch}：
 *       {@code drafts} 数组必填，元素就是上面那份单笔 schema（产品也是同一份对象引用）</li>
 * </ul>
 *
 * <p>唯一的刻意偏离同 {@code p2-experiment}：{@code paymentMethod} 放宽成字符串。
 * 它在本实验里只是观察项，卡在枚举上会触发 schema 重试，把"拆没拆对"这个正题淹掉。
 */
public final class ExpSchema {

    /** 产品的单笔工具名与批量工具名（产品 {@code DraftToolSchema} 里同名） */
    public static final String TOOL_NAME = "create_draft";
    public static final String BATCH_TOOL_NAME = "create_draft_batch";
    public static final String FIELD_DRAFTS = "drafts";

    private ExpSchema() {
    }

    /** 与 D1-A §2 的二级清单一致（产品是从 {@code t_category} 现算，实验里是静态副本） */
    private static final String[] PATHS = {
            "餐饮>买菜", "餐饮>外卖", "餐饮>咖啡", "餐饮>饮品", "餐饮>零食", "餐饮>聚餐",
            "交通>公交地铁", "交通>打车", "交通>加油", "交通>停车", "交通>机票火车", "交通>共享单车",
            "购物>服饰", "购物>数码", "购物>日用", "购物>美妆", "购物>母婴",
            "居住>房租", "居住>水电燃气", "居住>物业", "居住>家居",
            "通讯>话费", "通讯>宽带",
            "娱乐>游戏", "娱乐>影音", "娱乐>旅行", "娱乐>运动",
            "医疗>门诊", "医疗>药品", "医疗>体检",
            "教育>学费", "教育>书籍", "教育>培训",
            "人情>红包", "人情>礼物", "人情>请客",
            "其他",
            "工资", "奖金", "兼职", "投资", "红包", "其他收入"
    };

    /**
     * 单笔（= 产品现状给文字入口用的那份）。
     *
     * <p>注意 {@code transactionCount} 的措辞与产品一致：说的是"这句话里实际包含几笔账"，
     * 也就是<b>只报数、不拆笔</b>——这正是本次实验要对比的策略之一。
     */
    public static ToolSchema single() {
        return ToolSchema.builder(TOOL_NAME)
                .description("把一句口语记账转换成结构化账目草稿")
                .enumOf("direction",
                        "收支方向。支出填 expense，收入填 income，"
                                + "转账（给别人转钱、还信用卡、还花呗、取现）填 transfer",
                        ToolSchema.Requirement.REQUIRED,
                        "expense", "income", "transfer")
                .integer("amountCents",
                        "金额，单位是分（28 元填 2800）。句中没提到金额时不要填这个字段",
                        ToolSchema.Requirement.OPTIONAL)
                .string("amountRaw",
                        "金额在原句中的原文片段，原样抄写，如「三百五」「28」",
                        ToolSchema.Requirement.OPTIONAL)
                .string("occurredAtText",
                        "时间短语的原文，如「昨天下午」「上周三」。不要换算成日期",
                        ToolSchema.Requirement.OPTIONAL)
                .enumOf("categoryPath",
                        "分类路径，只能从这个枚举里选。支出选支出分类，收入选收入分类，"
                                + "转账选「人情」或其下的二级（不要选「其他」）。实在判断不出就留空",
                        ToolSchema.Requirement.OPTIONAL, PATHS)
                .string("paymentMethod", "支付方式原文（可空）", ToolSchema.Requirement.OPTIONAL)
                .string("merchant",
                        "具体的商户、平台或对方名称（如「滴滴」「星巴克」「张三」）。"
                                + "不要把动作或品类填进来；无法确定时留空",
                        ToolSchema.Requirement.OPTIONAL)
                .stringArray("items", "商品明细（可空）", ToolSchema.Requirement.OPTIONAL)
                .string("note", "备注（可空）", ToolSchema.Requirement.OPTIONAL)
                .integer("transactionCount",
                        "这句话里实际包含几笔账。只有一笔时填 1",
                        ToolSchema.Requirement.OPTIONAL)
                .build();
    }

    /** 批量（= 产品 P2「一图多笔」用的那份，本次实验把它试着接到文字入口） */
    public static ToolSchema batch() {
        return ToolSchema.builder(BATCH_TOOL_NAME)
                .description("把一张账单截图（或一段含多笔的文字）拆成多笔结构化账目草稿")
                .objectArray(FIELD_DRAFTS,
                        "账目草稿数组：每一笔一个元素，按句中/图中从上到下的顺序排列。"
                                + "只有一笔时数组里也放一个元素",
                        ToolSchema.Requirement.REQUIRED,
                        single())
                .build();
    }
}
