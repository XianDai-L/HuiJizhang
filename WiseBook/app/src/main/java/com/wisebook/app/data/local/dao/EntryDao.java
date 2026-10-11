package com.wisebook.app.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.domain.model.Direction;

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
     * 去重判据：<b>本账本内</b>是否已有同 {@code dedupe_key} 的账目。
     *
     * <p>用 {@code COUNT} 而不是取一行——去重的语义是「命中就算重复」，
     * 命中后进「疑似重复」队列由用户决定，绝不自动合并（D1 §7）。
     *
     * <p><b>去重必须按账本隔离</b>（2026-10-11）：两个账本里各有一笔「今天打车 30」
     * 是完全正常的，不能被判成重复。
     */
    @Query("SELECT COUNT(*) FROM t_entry WHERE dedupe_key = :dedupeKey AND user_id = :userId")
    int countByDedupeKey(String dedupeKey, long userId);

    /**
     * 疑似重复的候选：同账本、同方向、同金额的账目（2026-10-08 新增）。
     *
     * <p>去重键里的发生时间一旦是<b>猜出来的</b>（截图里没读到日期），键就跟着算错，
     * 单靠 {@link #countByDedupeKey} 捞不出重复。这时退到「金额 + 时刻」这个更粗、
     * 但仍然很强的判据上——精确到分的金额与精确到分钟的时刻同时相同，
     * 几乎只能是同一笔。
     *
     * <p>候选行交给调用方在 Java 侧比「时:分」：时分要从毫秒按设备时区折算，
     * 写进 SQL 就得依赖 SQLite 的时区函数，既晦涩又没法在 JVM 上单测。
     */
    @Query("SELECT * FROM t_entry WHERE direction = :direction AND amount_cents = :amountCents"
            + " AND user_id = :userId")
    List<EntryEntity> findByDirectionAndAmount(Direction direction, long amountCents, long userId);

    @Query("SELECT * FROM t_entry WHERE user_id = :userId"
            + " ORDER BY occurred_at DESC, entry_id DESC")
    LiveData<List<EntryEntity>> observeAll(long userId);

    /**
     * 首页「最近账目」：最近 N 笔，<b>跨月</b>。
     *
     * <p>与 {@link #observeBetween} 的区别就是不带月份区间——首页回答的是"最近发生了什么"，
     * 账本页回答的是"这个月钱花哪了"，两个问题本来就不同。
     *
     * <p><b>分页靠把 {@code limit} 调大，而不是用 OFFSET</b>：
     * 用户是"边看边记"的，翻页期间新记一笔会让 OFFSET 跳行/重复；
     * 每次都从第 1 条重新取 N 条则天然稳定（代价只是头部几行重查，量级可以忽略）。
     *
     * @param limit 要取多少笔；由 {@code RecentEntriesViewModel} 每滚到底加一页
     */
    @Query("SELECT * FROM t_entry WHERE user_id = :userId"
            + " ORDER BY occurred_at DESC, entry_id DESC LIMIT :limit")
    LiveData<List<EntryEntity>> observeRecent(long userId, int limit);

    /** 导出用：一次读出<b>本账本</b>的全部账目。<b>阻塞</b> */
    @Query("SELECT * FROM t_entry WHERE user_id = :userId"
            + " ORDER BY occurred_at DESC, entry_id DESC")
    List<EntryEntity> findAll(long userId);

    @Query("SELECT * FROM t_entry WHERE user_id = :userId"
            + " AND occurred_at >= :fromInclusive AND occurred_at < :toExclusive"
            + " ORDER BY occurred_at DESC, entry_id DESC")
    LiveData<List<EntryEntity>> observeBetween(long userId, long fromInclusive, long toExclusive);

    /**
     * 与 {@link #observeBetween} 同一批数据，但按「记账时间」倒序。
     *
     * <p>记两条的原因：{@code ORDER BY} 的字段没法当参数传（Room 的 {@code @Query} 是编译期
     * 生成代码，排序字段写死在 SQL 里），所以只能一个排序一个方法。
     *
     * <p>用 {@code created_at}（账目行的创建时刻）而不是草稿的 {@code posted_at}：
     * 单用户场景下两者几乎同时发生，而 {@code created_at} 就在本表上，不必连表。
     */
    @Query("SELECT * FROM t_entry WHERE user_id = :userId"
            + " AND occurred_at >= :fromInclusive AND occurred_at < :toExclusive"
            + " ORDER BY created_at DESC, entry_id DESC")
    LiveData<List<EntryEntity>> observeBetweenByCreatedAt(long userId, long fromInclusive,
                                                          long toExclusive);

    @Query("SELECT * FROM t_entry WHERE user_id = :userId"
            + " AND occurred_at >= :fromInclusive AND occurred_at < :toExclusive"
            + " ORDER BY occurred_at DESC, entry_id DESC")
    List<EntryEntity> findBetween(long userId, long fromInclusive, long toExclusive);

    /**
     * 报表聚合：按「一级分类 × 收支方向」求和。
     *
     * <p>只查一行 SQL 就把报表所需的数据取全，合计在 Java 侧累加即可——
     * 少一次查询，也让「转账是否计入」这种口径判断留在能看到业务语义的地方。
     */
    @Query("SELECT root_category_id, direction, SUM(amount_cents) AS total_cents,"
            + " COUNT(*) AS entry_count FROM t_entry"
            + " WHERE user_id = :userId"
            + " AND occurred_at >= :fromInclusive AND occurred_at < :toExclusive"
            + " GROUP BY root_category_id, direction")
    List<CategoryTotal> totalsBetween(long userId, long fromInclusive, long toExclusive);

    @Query("SELECT COUNT(*) FROM t_entry WHERE user_id = :userId")
    int count(long userId);

    /** 改正分类时用（D1 §5.3） */
    @Update
    void update(EntryEntity entry);

    @Delete
    void delete(EntryEntity entry);

    /**
     * 删除账本时清空它的账目（2026-10-11）。
     *
     * <p>物理删除在这里是<b>唯一正确</b>的做法：账本都被删掉了，
     * 里面的账目再「停用」也没有任何界面能看得到它们。
     * 调用方必须让用户先输入「确认删除」——数据不可恢复。
     */
    @Query("DELETE FROM t_entry WHERE user_id = :userId")
    void deleteByUser(long userId);
}
