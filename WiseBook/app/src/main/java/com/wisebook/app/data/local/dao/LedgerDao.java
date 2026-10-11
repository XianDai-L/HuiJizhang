package com.wisebook.app.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.wisebook.app.data.local.entity.LedgerEntity;

import java.util.List;

/**
 * 账本表访问（2026-10-11 新增，见 {@link LedgerEntity}）。
 */
@Dao
public interface LedgerDao {

    /** 全部账本，按排序号与 id 排列（列表界面用） */
    @Query("SELECT * FROM t_ledger ORDER BY sort_order, ledger_id")
    List<LedgerEntity> list();

    /** 单个账本；不存在（例如刚被删掉）时返回 {@code null} */
    @Query("SELECT * FROM t_ledger WHERE ledger_id = :ledgerId")
    LedgerEntity find(long ledgerId);

    @Query("SELECT COUNT(*) FROM t_ledger")
    int count();

    /** 新建账本时用它分配下一个 id；一个账本都没有时为 {@code null} */
    @Query("SELECT MAX(ledger_id) FROM t_ledger")
    Long maxId();

    /** 写入或覆盖。主键是 {@code ledger_id}，重命名走同一条语句 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(LedgerEntity ledger);

    @Query("DELETE FROM t_ledger WHERE ledger_id = :ledgerId")
    void delete(long ledgerId);
}
