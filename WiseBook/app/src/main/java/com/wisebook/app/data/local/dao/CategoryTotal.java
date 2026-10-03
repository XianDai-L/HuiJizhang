package com.wisebook.app.data.local.dao;

import androidx.room.ColumnInfo;

/**
 * 报表聚合的查询结果行（不是表，只是 {@link EntryDao#totalsBetween} 的投影）。
 *
 * <p>{@code direction} 刻意用 {@code String} 而不是 {@link com.wisebook.app.domain.model.Direction}：
 * 投影类型上不挂类型转换器，读出来的就是库里存的原始 code，
 * 由领域层显式转换。少一层隐式转换，排查时少一个"到底哪一步转的"问题。
 */
public class CategoryTotal {

    @ColumnInfo(name = "root_category_id")
    public long rootCategoryId;

    /** {@link com.wisebook.app.domain.model.Direction} 的 code */
    @ColumnInfo(name = "direction")
    public String directionCode;

    @ColumnInfo(name = "total_cents")
    public long totalCents;

    @ColumnInfo(name = "entry_count")
    public int entryCount;

    @Override
    public String toString() {
        return "root#" + rootCategoryId + " " + directionCode + " = " + totalCents + "分/" + entryCount + "笔";
    }
}
