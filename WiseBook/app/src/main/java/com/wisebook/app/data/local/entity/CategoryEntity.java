package com.wisebook.app.data.local.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.wisebook.app.domain.model.Direction;

/**
 * 分类（D1 §3.4）——表 {@code t_category}。
 *
 * <p>统一树结构：一级与二级同表，靠 {@code parent_id} + {@code level} 区分。
 * 「简单 / 标准」两种方案共享这同一棵树，{@code t_setting.category_scheme}
 * 只是「是否允许选到二级」的开关——这是报表聚合代码只有一套的前提。
 *
 * <p><b>禁止物理删除</b>（D1 §6.5）：删除一律置 {@code is_archived = 1}。
 * 物理删除会让历史账目变成孤儿，统计直接漏账。
 */
@Entity(
        tableName = "t_category",
        indices = {
                @Index(value = "parent_id"),
                @Index(value = {"user_id", "parent_id"})
        }
)
public class CategoryEntity {

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "category_id")
    public long categoryId;

    @ColumnInfo(name = "user_id")
    public long userId;

    /** {@code null} = 一级分类 */
    @ColumnInfo(name = "parent_id")
    public Long parentId;

    /** 1 = 一级，2 = 二级 */
    @ColumnInfo(name = "level")
    public int level;

    @ColumnInfo(name = "name")
    public String name;

    /** 1 = 系统预置，0 = 用户自定义 */
    @ColumnInfo(name = "is_system")
    public boolean isSystem;

    /** 1 = 停用（只停用不删除） */
    @ColumnInfo(name = "is_archived")
    public boolean isArchived;

    @ColumnInfo(name = "sort_order")
    public int sortOrder;

    @ColumnInfo(name = "icon")
    public String icon;

    /**
     * 收支方向（<b>v2 新增</b>，见 {@code Migrations.MIGRATION_1_2}）。
     *
     * <p>只有一级分类存值；二级恒为 {@code null}，方向由父级继承——
     * D1-A §1 里只有一级分类分收支，且「收入不设二级」。
     */
    @ColumnInfo(name = "direction")
    public Direction direction;

    /** 是否一级分类 */
    public boolean isTopLevel() {
        return level == 1;
    }

    @Override
    public String toString() {
        return name + "#" + categoryId + (level == 1 ? "(一级)" : "(二级)");
    }
}
