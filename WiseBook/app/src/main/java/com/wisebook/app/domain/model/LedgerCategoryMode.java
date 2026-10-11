package com.wisebook.app.domain.model;

/**
 * 账本列表里分类的显示粒度（2026-10-08 新增）。
 *
 * <p><b>已废弃（2026-10-11）：</b>它对应的设置项在 2026-10-08 就换了语义
 * （从"显示到几级"变成"列出账本里的分类"），2026-10-11 又随那一行被提升成
 * 独立的「账本」分组，至此没有任何界面会读写它。
 * 枚举本身保留：{@code t_setting.ledger_category_mode} 那一列还在（删列要配迁移），
 * 而 {@code Converters} 要能把它读出来，删掉这个类型会让老数据读不进来。
 *
 * <p><b>它只影响显示，不影响数据。</b>分类树、报表聚合、分类选择器都还是完整的两级：
 * 切到「简单」只是账本行不再把二级路径铺出来，已有的账目、已有的二级分类一个都没变。
 * 这一点是刻意的——「账本看着清爽一点」和「换个分类方案」是两件事，
 * 把它们绑在一起，用户就不敢随便动这个开关了。
 *
 * <p>与 {@link CategoryScheme} 的关系：两者都叫简单 / 标准，但管的不是一回事
 * （那个管「能不能选二级」，这个管「显示不显示二级」）。所以做成两个独立枚举，
 * 而不是复用一个——复用会让「能选什么」和「显示什么」被迫永远同步。
 */
public enum LedgerCategoryMode implements CodedEnum {

    /** 简单：账本行只显示一级分类，如「餐饮」 */
    SIMPLE("simple"),

    /** 标准：显示完整路径，如「餐饮&gt;外卖」 */
    STANDARD("standard");

    private final String code;

    LedgerCategoryMode(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    /** 界面上的一句话说法 */
    public String label() {
        return this == SIMPLE ? "简单" : "标准";
    }

    public static LedgerCategoryMode fromCode(String code) {
        return CodedEnums.fromCode(LedgerCategoryMode.class, code);
    }
}
