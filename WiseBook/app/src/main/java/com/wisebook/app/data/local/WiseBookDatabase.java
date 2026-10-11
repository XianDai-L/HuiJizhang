package com.wisebook.app.data.local;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import com.wisebook.app.data.local.dao.CategoryDao;
import com.wisebook.app.data.local.dao.DraftDao;
import com.wisebook.app.data.local.dao.EntryDao;
import com.wisebook.app.data.local.dao.LedgerDao;
import com.wisebook.app.data.local.dao.SettingDao;
import com.wisebook.app.data.local.entity.CategoryEntity;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.data.local.entity.LedgerEntity;
import com.wisebook.app.data.local.entity.SettingEntity;

/**
 * 慧记本地库（D1 §3）。
 *
 * <p>五张表：{@code t_draft}（草稿）/ {@code t_entry}（账目）/
 * {@code t_category}（分类树）/ {@code t_setting}（设置）/ {@code t_ledger}（账本）。
 *
 * <p><b>多账本（2026-10-11）：</b>账本维度复用既有的 {@code user_id}
 * （见 {@link LedgerEntity}）——账目、草稿、分类、设置本来就按它隔离，
 * 所以多账本不需要给这几张表加列，{@code t_ledger} 只存账本本身的元信息。
 *
 * <p>{@code exportSchema = true} 让 Room 把每一版 schema 导出成 JSON，
 * 放在 {@code app/schemas/}。它有两个用途：一是写迁移时的唯一依据，
 * 二是 {@code MigrationTest} 靠它还原出「旧版本的库到底长什么样」。
 *
 * <p>P1 用「手动构造」而不是依赖注入框架（D2 §1），所以这里保留进程内单例：
 * 数据库连接是重资源，多处各建一个实例会带来并发写锁竞争。
 */
@Database(
        entities = {
                DraftEntity.class,
                EntryEntity.class,
                CategoryEntity.class,
                SettingEntity.class,
                LedgerEntity.class
        },
        version = 4,
        exportSchema = true
)
@TypeConverters(Converters.class)
public abstract class WiseBookDatabase extends RoomDatabase {

    /**
     * 第一个账本的 id（= 旧数据的 {@code user_id}，D1 §0）。
     *
     * <p>多账本之前它是「唯一用户」；现在它是「默认账本」的 id，
     * 仍被当作「还没有任何账本时兜底的账本 id」使用。
     */
    public static final long DEFAULT_USER_ID = 1L;

    private static final String DATABASE_NAME = "wisebook.db";

    private static volatile WiseBookDatabase instance;

    public abstract DraftDao draftDao();

    public abstract EntryDao entryDao();

    public abstract CategoryDao categoryDao();

    public abstract SettingDao settingDao();

    public abstract LedgerDao ledgerDao();

    public static WiseBookDatabase get(Context context) {
        if (instance == null) {
            synchronized (WiseBookDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    WiseBookDatabase.class,
                                    DATABASE_NAME)
                            // 禁止 fallbackToDestructiveMigration：迁移漏写时必须报错，
                            // 而不是静默清库把用户数据丢掉
                            .addMigrations(Migrations.ALL)
                            .build();
                }
            }
        }
        return instance;
    }
}
