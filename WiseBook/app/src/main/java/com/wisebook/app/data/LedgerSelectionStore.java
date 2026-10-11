package com.wisebook.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.wisebook.app.data.local.WiseBookDatabase;

/**
 * 「当前账本」的存取（2026-10-11）。
 *
 * <p><b>为什么不放进 {@code t_setting}：</b>设置表是按账本存的（一行一个账本），
 * 而「当前用哪个账本」这件事必须<b>跨账本</b>存在——把它放进某个账本自己的设置里，
 * 就会出现"要读当前账本，得先知道当前账本"的循环。所以它放在应用级的
 * SharedPreferences 里，与 {@code LlmConfigStore} 是同一种取向。
 *
 * <p><b>缓存一个内存值：</b>账目查询到处都是、又都在数据库线程上，
 * 每次都读一次磁盘没必要。这个类是全应用唯一的写入点，
 * 所以内存值与磁盘值不会分叉。切换账本时 {@link #select(long)} 立即更新缓存，
 * 后续查询马上按新账本走（界面则靠重启任务栈重建）。
 */
public final class LedgerSelectionStore implements CurrentLedger {

    private static final String PREFS_NAME = "wisebook_ledger";
    private static final String KEY_CURRENT = "current_ledger_id";

    private final SharedPreferences preferences;

    /** 进程内缓存；构造时从磁盘载入一次 */
    private volatile long current;

    public LedgerSelectionStore(Context context) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.current = preferences.getLong(KEY_CURRENT, WiseBookDatabase.DEFAULT_USER_ID);
    }

    @Override
    public long id() {
        return current;
    }

    /** 切换当前账本，立即生效并落盘。 */
    public void select(long ledgerId) {
        current = ledgerId;
        preferences.edit().putLong(KEY_CURRENT, ledgerId).apply();
    }
}
