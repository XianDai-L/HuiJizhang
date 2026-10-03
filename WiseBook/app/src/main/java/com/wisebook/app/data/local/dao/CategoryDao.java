package com.wisebook.app.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.wisebook.app.data.local.entity.CategoryEntity;

import java.util.List;

/**
 * 分类树访问（D1 §3.4）。
 */
@Dao
public interface CategoryDao {

    /**
     * 全部未停用分类，按层级与排序号排列。
     *
     * <p>一次性把整棵树取回内存，再由 Java 侧组装成父子结构——
     * 分类总量只有几十条，比在 SQL 里递归查询简单得多，也便于单元测试。
     */
    @Query("SELECT * FROM t_category WHERE user_id = :userId AND is_archived = 0"
            + " ORDER BY level, sort_order, category_id")
    List<CategoryEntity> findActive(long userId);

    @Query("SELECT * FROM t_category WHERE user_id = :userId ORDER BY level, sort_order, category_id")
    List<CategoryEntity> findAll(long userId);

    @Query("SELECT * FROM t_category WHERE category_id = :categoryId")
    CategoryEntity findById(long categoryId);

    @Query("SELECT * FROM t_category WHERE user_id = :userId AND level = 1 AND parent_id IS NULL"
            + " ORDER BY sort_order, category_id")
    List<CategoryEntity> findTopLevel(long userId);

    @Query("SELECT * FROM t_category WHERE parent_id = :parentId ORDER BY sort_order, category_id")
    List<CategoryEntity> findChildren(long parentId);

    @Query("SELECT COUNT(*) FROM t_category WHERE user_id = :userId")
    int count(long userId);

    /** @return 新行的 category_id */
    @Insert
    long insert(CategoryEntity category);

    /** @return 每一行的 category_id，顺序与传入顺序一致 */
    @Insert
    List<Long> insertAll(List<CategoryEntity> categories);
}
