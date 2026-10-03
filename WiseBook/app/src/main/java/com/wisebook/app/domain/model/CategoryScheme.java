package com.wisebook.app.domain.model;

/**
 * 分类方案（D1 §6.1）。
 *
 * <p>两种方案共享同一棵分类树，这里只是「是否允许选到二级」的开关。
 * 切换只改 {@code t_setting} 的这一个值，不动分类表、不动账目——
 * 这是「双向自由切换、零迁移」能成立的前提。
 */
public enum CategoryScheme implements CodedEnum {

    /** 简单：只允许选一级分类 */
    SIMPLE("simple"),

    /** 标准：一级 + 二级，也可只选一级 */
    STANDARD("standard");

    private final String code;

    CategoryScheme(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    public static CategoryScheme fromCode(String code) {
        return CodedEnums.fromCode(CategoryScheme.class, code);
    }
}
