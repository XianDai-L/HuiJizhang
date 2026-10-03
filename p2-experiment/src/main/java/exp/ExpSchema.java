package exp;

import com.wisebook.llm.ToolSchema;

/**
 * 实验用工具定义。
 *
 * <p>字段名与产品 {@code DraftToolSchema} 完全一致，分类枚举也按 D1-A §2 的
 * 二级清单原样给出——两条路线必须吃同一份约束，否则比的是 schema 的松紧，不是路线的优劣。
 *
 * <p>唯一刻意的偏离：{@code paymentMethod} 在产品里是枚举，这里放宽成字符串。
 * 三张图上的措辞（「建设银行储蓄卡(8730)」）与产品的支付方式枚举对不齐，
 * 卡在这里会触发 schema 重试，把"金额读没读准"这个正题淹掉。它在结果里只作观察项。
 */
public final class ExpSchema {

    private ExpSchema() {
    }

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

    public static ToolSchema build() {
        return ToolSchema.builder("create_draft")
                .description("把一张支付截图或一段文字转换成结构化账目草稿")
                .enumOf("direction",
                        "收支方向。支出填 expense，收入填 income，转账（给别人转钱、还信用卡、还花呗、取现）填 transfer",
                        ToolSchema.Requirement.REQUIRED,
                        "expense", "income", "transfer")
                .integer("amountCents",
                        "金额，单位是分（28 元填 2800）。没有金额时不要填这个字段",
                        ToolSchema.Requirement.OPTIONAL)
                .string("amountRaw",
                        "金额在原图中的原文片段，原样抄写，如「￥7.00」「-15.00」",
                        ToolSchema.Requirement.OPTIONAL)
                .string("occurredAtText",
                        "时间短语的原文，如「昨天 21:09」「2026年10月02日 21:09:26」。不要换算成日期",
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
                        "这张图里实际包含几笔账。只有一笔时填 1",
                        ToolSchema.Requirement.OPTIONAL)
                .build();
    }
}
