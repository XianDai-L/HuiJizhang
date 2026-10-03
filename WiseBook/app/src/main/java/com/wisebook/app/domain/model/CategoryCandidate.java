package com.wisebook.app.domain.model;

/**
 * 模型给出的分类（D1 §3.2 的 {@code category_candidates}）。
 *
 * <p>P1 的模型输出是 {@code categoryPath} 一个字段，由分类层解析成 id 后写入本结构。
 * 列表里通常只有一项——分类判定的简化（HANDOFF 决策 30）去掉了「多个候选」这个概念：
 * 模型只给最可能的那一个，系统的职责是把它落到本地分类树上，落不上就兜到「其他」。
 *
 * <p>保留列表形态而不是单个字段，是因为 {@code t_draft.category_candidates} 这一列
 * 已经存在、也已经在用；为了一个元素改列结构要配一版迁移，不划算。
 */
public final class CategoryCandidate {

    /** t_category.category_id */
    public long categoryId;

    /** 分类路径（如 {@code 餐饮&gt;咖啡}），用于直接展示与排查 */
    public String name;

    /** Gson 反序列化需要无参构造 */
    public CategoryCandidate() {
    }

    public CategoryCandidate(long categoryId, String name) {
        this.categoryId = categoryId;
        this.name = name;
    }

    @Override
    public String toString() {
        return name + "#" + categoryId;
    }
}
