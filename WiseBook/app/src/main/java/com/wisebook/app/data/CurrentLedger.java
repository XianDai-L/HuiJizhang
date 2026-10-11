package com.wisebook.app.data;

/**
 * 「当前账本」的提供者（2026-10-11）。
 *
 * <p>多账本之后，各处不再写死 {@code WiseBookDatabase.DEFAULT_USER_ID}，
 * 而是问它要「现在用哪个账本」——一个账本 = 一个 {@code user_id}，
 * 拿到的这个值直接当查询条件用。
 *
 * <p>做成接口而不是让仓储直接依赖 {@code LedgerSelectionStore}：
 * 测试里可以塞一个恒返回固定 id 的实现，不必去动 SharedPreferences。
 */
public interface CurrentLedger {

    /** 当前账本的 id。 */
    long id();
}
