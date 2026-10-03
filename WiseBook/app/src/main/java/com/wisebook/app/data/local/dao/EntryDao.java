package com.wisebook.app.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.wisebook.app.data.local.entity.EntryEntity;

import java.util.List;

/**
 * 账目表访问（D1 §3.3）。
 */
@Dao
public interface EntryDao {

    /**
     * @return 新行的 entry_id；同一草稿重复落账会因 {@code draft_id} 的 UNIQUE
     *         约束抛 {@code SQLiteConstraintException}，这正是幂等的物理保障
     */
    @Insert
    long insert(EntryEntity entry);

    @Query("SELECT * FROM t_entry WHERE entry_id = :entryId")
    EntryEntity findById(long entryId);

    @Query("SELECT * FROM t_entry WHERE draft_id = :draftId LIMIT 1")
    EntryEntity findByDraftId(long draftId);

    /**
     * 去重判据：是否已有同 {@code dedupe_key} 的账目。
     *
     * <p>用 {@code COUNT} 而不是取一行——去重的语义是「命中就算重复」，
     * 命中后进「疑似重复」队列由用户决定，绝不自动合并（D1 §7）。
     */
    @Query("SELECT COUNT(*) FROM t_entry WHERE dedupe_key = :dedupeKey")
    int countByDedupeKey(String dedupeKey);

    @Query("SELECT * FROM t_entry ORDER BY occurred_at DESC, entry_id DESC")
    LiveData<List<EntryEntity>> observeAll();

    @Query("SELECT * FROM t_entry WHERE occurred_at >= :fromInclusive AND occurred_at < :toExclusive"
            + " ORDER BY occurred_at DESC, entry_id DESC")
    LiveData<List<EntryEntity>> observeBetween(long fromInclusive, long toExclusive);

    @Query("SELECT * FROM t_entry WHERE occurred_at >= :fromInclusive AND occurred_at < :toExclusive"
            + " ORDER BY occurred_at DESC, entry_id DESC")
    List<EntryEntity> findBetween(long fromInclusive, long toExclusive);

    /**
     * 报表聚合：按「一级分类 × 收支方向」求和。
     *
     * <p>只查一行 SQL 就把报表所需的数据取全，合计在 Java 侧累加即可——
     * 少一次查询，也让「转账是否计入」这种口径判断留在能看到业务语义的地方。
     */
    @Query("SELECT root_category_id, direction, SUM(amount_cents) AS total_cents,"
            + " COUNT(*) AS entry_count FROM t_entry"
            + " WHERE occurred_at >= :fromInclusive AND occurred_at < :toExclusive"
            + " GROUP BY root_category_id, direction")
    List<CategoryTotal> totalsBetween(long fromInclusive, long toExclusive);

    @Query("SELECT COUNT(*) FROM t_entry")
    int count();

    /** 改正分类时用（D1 §5.3） */
    @Update
    void update(EntryEntity entry);

    @Delete
    void delete(EntryEntity entry);
}
