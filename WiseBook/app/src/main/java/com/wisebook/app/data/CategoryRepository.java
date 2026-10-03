package com.wisebook.app.data;

import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.seed.CategorySeeder;
import com.wisebook.app.domain.classify.CategoryTree;

/**
 * 分类树的读取（D1 §3.4）。
 *
 * <p>每次都从库里取一次再组装，不做缓存：分类只有几十条，一次查询远快于
 * "加了缓存之后什么时候该失效"的心智负担。而且刚改过分类（用户自定义/停用）之后
 * 立刻要生效，缓存反而要先想清楚失效时机。
 *
 * <p><b>所有方法都是阻塞的</b>，调用方负责放到后台线程。
 */
public final class CategoryRepository {

    private final WiseBookDatabase database;

    public CategoryRepository(WiseBookDatabase database) {
        this.database = database;
    }

    /** 取当前用户的未停用分类，组装成树 */
    public CategoryTree loadTree(long userId) {
        return new CategoryTree(database.categoryDao().findActive(userId));
    }

    /**
     * 确保预置分类已写入。
     *
     * <p>正常情况下 {@code WiseBookApp} 启动时已经播过种，这里只是给
     * 「数据库被清过但进程还活着」这类边角情况兜个底——毕竟没有分类树，
     * 模型的分类枚举就是空的，整条解析链路会退化。
     */
    public void ensureSeeded() {
        CategorySeeder.seedIfEmpty(database);
    }
}
