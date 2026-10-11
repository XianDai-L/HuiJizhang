package com.wisebook.app.data.local.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.ConfirmMode;
import com.wisebook.app.domain.model.LedgerCategoryMode;

/**
 * 用户设置（D1 §3.5）——表 {@code t_setting}。
 *
 * <p>单用户场景下 {@code user_id} 恒为 1。它同时是主键，保证「一个用户一行」，
 * 也让 {@code upsert} 天生幂等。
 *
 * <p>字段与 D1 §3.5 逐字对应，未擅自增删。两个 {@code *_version} 字段是
 * 词典与映射表的版本号：内容清单（D1-A）是可迭代的，靠版本号才能知道
 * 本地已加载的是哪一版。
 */
@Entity(tableName = "t_setting")
public class SettingEntity {

    @PrimaryKey
    @ColumnInfo(name = "user_id")
    public long userId;

    /** 确认档位，见 {@link ConfirmMode} */
    @ColumnInfo(name = "confirm_mode")
    public ConfirmMode confirmMode;

    /** 大额阈值（分），默认 30000 = 300 元 */
    @ColumnInfo(name = "large_threshold_cents")
    public long largeThresholdCents;

    /** 反问轮数上限，默认 3，超限转手动填写表单 */
    @ColumnInfo(name = "clarify_max_rounds")
    public int clarifyMaxRounds;

    /** 分类方案，见 {@link CategoryScheme} */
    @ColumnInfo(name = "category_scheme")
    public CategoryScheme categoryScheme;

    /**
     * 账本列表里分类的显示粒度，见 {@link LedgerCategoryMode}（2026-10-08 新增）。
     *
     * <p><b>已废弃（2026-10-11）：</b>设置页上那一行在 2026-10-08 就从
     * 「分类显示到几级」改成了「列出账本里的分类」，这个开关随之失去界面入口；
     * 2026-10-11 那一行又被提升成独立的「账本」分组（进账本选择页），
     * 于是它彻底没有使用者了。
     *
     * <p>列<b>刻意保留</b>：删列要配一版迁移，而它只是一个可空字符串、不占空间。
     * 迁移是这个项目最容易出错的环节（HANDOFF §8-17），能不动就不动——
     * 与 {@code merchant_map_version} 是同一种处理。
     */
    @ColumnInfo(name = "ledger_category_mode")
    public LedgerCategoryMode ledgerCategoryMode;

    /**
     * 商户映射表版本。**已废弃**（2026-10-07，HANDOFF 决策 46）：映射表整体删除了，
     * 这个字段不再有使用者。
     *
     * <p>列<b>刻意保留</b>：删列要配一版迁移，而它只是一个 int、不占空间。
     * 迁移是这个项目最容易出错的环节（HANDOFF §8-17），能不动就不动。
     */
    @ColumnInfo(name = "merchant_map_version")
    public int merchantMapVersion;

    /** 约数词典版本（D1-A §5） */
    @ColumnInfo(name = "approx_dict_version")
    public int approxDictVersion;
}
