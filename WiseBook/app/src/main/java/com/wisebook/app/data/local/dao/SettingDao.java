package com.wisebook.app.data.local.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.wisebook.app.data.local.entity.SettingEntity;

/**
 * 设置表访问（D1 §3.5）。
 */
@Dao
public interface SettingDao {

    /**
     * @return 该用户的设置；尚未初始化过时为 {@code null}，由调用方补默认值
     */
    @Query("SELECT * FROM t_setting WHERE user_id = :userId")
    SettingEntity find(long userId);

    /**
     * 写入或覆盖。
     *
     * <p>用 {@code REPLACE} 而非 {@code UPDATE}：设置行可能还不存在（首次启动），
     * 一条语句同时覆盖「插入」与「更新」两种情况，调用方不必先查后写。
     * 由于 {@code user_id} 是主键，REPLACE 的语义就是「整行覆盖」，不会产生第二行。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(SettingEntity setting);
}
