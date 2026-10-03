package com.wisebook.app.data.local.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.OccurredAtSource;
import com.wisebook.app.domain.model.PaymentMethod;

import java.util.List;

/**
 * 账目（D1 §3.3）——表 {@code t_entry}。
 *
 * <p>只装<b>已确认</b>的数据。字段与 {@code t_draft} 有重叠是有意为之
 * （D1 §3.1 已接受这份代价）：换来的是报表查询不必过滤 status。
 *
 * <p>金额、时间、分类这几个字段在数据库层面是 NOT NULL。枚举型字段
 * （{@code direction} / {@code occurred_at_source} / {@code payment_method} /
 * {@code source}）由于 Java 引用类型映射的缘故保持可空。
 *
 * <p>也就是说「必填字段齐全」（D1 §5.2 条件 4）这条约束由<b>落账前的校验</b>
 * 保证，而不是靠列约束——因为草稿阶段这些字段本来就允许缺失，
 * 而 {@code t_draft} 与 {@code t_entry} 共用同一套枚举与转换器。
 * 若将来想让数据库层也拦住，需要一版把这几列收紧为 NOT NULL 的迁移。
 */
@Entity(
        tableName = "t_entry",
        indices = {
                // draft_id 唯一：物理保证同一草稿只能入账一次（D1 §4.2 约束 3）
                @Index(value = "draft_id", unique = true),
                @Index(value = "occurred_at"),
                @Index(value = "root_category_id"),
                @Index(value = "dedupe_key")
        }
)
public class EntryEntity {

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "entry_id")
    public long entryId;

    /** 溯源到草稿；UNIQUE 约束（幂等的物理保障） */
    @ColumnInfo(name = "draft_id")
    public long draftId;

    @ColumnInfo(name = "user_id")
    public long userId;

    @ColumnInfo(name = "direction")
    public Direction direction;

    /** 整数「分」，绝不使用浮点（D1 §3.6） */
    @ColumnInfo(name = "amount_cents")
    public long amountCents;

    @ColumnInfo(name = "amount_lower_cents")
    public Long amountLowerCents;

    @ColumnInfo(name = "amount_upper_cents")
    public Long amountUpperCents;

    @ColumnInfo(name = "amount_raw")
    public String amountRaw;

    @ColumnInfo(name = "amount_is_estimated")
    public boolean amountIsEstimated;

    @ColumnInfo(name = "occurred_at")
    public long occurredAt;

    @ColumnInfo(name = "occurred_at_source")
    public OccurredAtSource occurredAtSource;

    /** 最末级分类 id */
    @ColumnInfo(name = "category_id")
    public long categoryId;

    /** 一级分类 id，报表按它聚合 */
    @ColumnInfo(name = "root_category_id")
    public long rootCategoryId;

    @ColumnInfo(name = "payment_method")
    public PaymentMethod paymentMethod;

    @ColumnInfo(name = "merchant")
    public String merchant;

    @ColumnInfo(name = "items")
    public List<String> items;

    @ColumnInfo(name = "note")
    public String note;

    /** 冗余自草稿，便于溯源（D1 §3.3） */
    @ColumnInfo(name = "source")
    public DraftSource source;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "dedupe_key")
    public String dedupeKey;

    /**
     * 是否为免确认直落（<b>v2 新增</b>，见 {@code Migrations.MIGRATION_1_2}，D1 §5.3）。
     *
     * <p>自动落账的账目进「最近自动记账」列表并带视觉标记，撤销入口永久保留。
     * 放在 {@code t_entry} 而不是 {@code t_draft}，是因为列表要直接读；
     * 而且 §3.3 里 {@code source} 的注释本来就写着「冗余自草稿，便于溯源」，
     * 加这一列与既有取向一致。
     *
     * <p>{@code defaultValue} 必须与迁移里的 {@code DEFAULT 0} 一致，
     * 否则 Room 会判定「迁移结果 ≠ 实体定义」而报错。
     */
    @ColumnInfo(name = "auto_posted", defaultValue = "0")
    public boolean autoPosted;

    @Override
    public String toString() {
        return "Entry#" + entryId + "{" + direction + ", " + amountCents + "分}";
    }
}
