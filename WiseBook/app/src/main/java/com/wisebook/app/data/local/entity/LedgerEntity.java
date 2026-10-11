package com.wisebook.app.data.local.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 账本（2026-10-11 新增）——表 {@code t_ledger}。
 *
 * <p><b>一个账本 = 一个 {@code user_id}。</b>这是本项目复用既有架构的关键取舍：
 * {@code t_entry} / {@code t_draft} / {@code t_category} / {@code t_setting} 从一开始
 * 就带着 {@code user_id} 这一列（D1 §0「架构预留 user_id，实现上单用户」），
 * 与其给四张表各加一列 {@code ledger_id} 再回填（那是一次真正的 schema 迁移，
 * 出错面大得多），不如把这层预留的维度直接拿来当账本维度
 * ——「账本之间彼此隔离」与「用户之间彼此隔离」在数据形状上本来就是同一件事。
 *
 * <p>所以本表只存<b>账本的元信息</b>（名字、创建时间、排序），
 * 真正的账目与分类仍挂在各自的 {@code user_id} 下，查询天然按账本隔离。
 *
 * <p>{@code ledger_id} 与 {@code user_id} 是同一个值，主键也用它：
 * 「一个账本一行」靠主键保证，与 {@code t_setting} 是同一种做法。
 */
@Entity(tableName = "t_ledger")
public class LedgerEntity {

    /**
     * 账本 id，<b>同时就是该账本下所有数据行的 {@code user_id}</b>。
     *
     * <p>刻意不自增：新建账本时取 {@code MAX(ledger_id) + 1}，与旧数据的
     * {@code user_id = 1} 直接对得上（第一个账本即 {@code DEFAULT_USER_ID}）。
     */
    @PrimaryKey
    @ColumnInfo(name = "ledger_id")
    public long ledgerId;

    /** 账本名。用户可见、可改（重命名），默认「默认账本」 */
    @ColumnInfo(name = "name")
    public String name;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    /** 列表排序号；新建时取当前 id，保证新账本排在后面 */
    @ColumnInfo(name = "sort_order")
    public int sortOrder;

    @Override
    public String toString() {
        return "Ledger#" + ledgerId + "{" + name + "}";
    }
}
