package com.wisebook.app.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.wisebook.app.data.local.entity.DraftEntity;

import java.util.List;

/**
 * 草稿表访问（D1 §3.2）。
 *
 * <p>查询里直接写字面量 {@code 'draft'} / {@code 'asking'}：
 * 状态在库里存的是 {@link com.wisebook.app.domain.model.DraftStatus#code()}，
 * 所以 SQL 里能用可读的小写字符串，不需要记住任何数字枚举值。
 */
@Dao
public interface DraftDao {

    @Insert
    long insert(DraftEntity draft);

    @Update
    void update(DraftEntity draft);

    @Query("SELECT * FROM t_draft WHERE draft_id = :draftId")
    DraftEntity findById(long draftId);

    @Query("SELECT * FROM t_draft WHERE entry_id = :entryId LIMIT 1")
    DraftEntity findByEntryId(long entryId);

    /** 还挂在用户手上的草稿（「待处理」列表的数据源） */
    @Query("SELECT * FROM t_draft WHERE status = 'draft' OR status = 'asking'"
            + " ORDER BY created_at DESC")
    LiveData<List<DraftEntity>> observeOpen();

    @Query("SELECT * FROM t_draft WHERE status = 'draft' OR status = 'asking'"
            + " ORDER BY created_at DESC")
    List<DraftEntity> findOpen();

    /**
     * 挂起超过时限的待处理草稿，用于超时归档。
     *
     * @param cutoff 更新时间的下界（早于它的算挂起）
     */
    @Query("SELECT * FROM t_draft WHERE (status = 'draft' OR status = 'asking')"
            + " AND updated_at < :cutoff ORDER BY created_at")
    List<DraftEntity> findStaleOpen(long cutoff);

    @Query("SELECT * FROM t_draft WHERE status = :statusCode ORDER BY created_at DESC")
    List<DraftEntity> findByStatusCode(String statusCode);

    @Query("SELECT * FROM t_draft WHERE batch_id = :batchId ORDER BY split_index")
    List<DraftEntity> findByBatch(String batchId);

    @Query("SELECT COUNT(*) FROM t_draft")
    int count();

    @Query("DELETE FROM t_draft WHERE draft_id = :draftId")
    void delete(long draftId);
}
