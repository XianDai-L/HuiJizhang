package com.wisebook.app.data.local;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.wisebook.app.data.local.seed.CategorySeeder;

/**
 * 数据库迁移集中登记处。
 *
 * <p>存在的理由（D1 §3.6 / HANDOFF §8-6）：本项目会频繁加字段，教材那种
 * 「{@code onUpgrade} 空实现」意味着加一个字段就只能卸载重装、用户数据全丢。
 * 所有迁移集中在这个数组里，按版本升序排列，Room 会挑出从当前库版本到
 * 目标版本所需的那几条按序执行。
 *
 * <p><b>禁止使用 {@code fallbackToDestructiveMigration()}</b>——那等于把
 * 「迁移写漏了」变成「静默清库」，比空实现更危险。
 */
public final class Migrations {

    private Migrations() {
    }

    /**
     * v1 → v2：补上 D1 schema 的两处缺口。
     *
     * <p><b>为什么会有这一版：</b>v1 是严格按 D1 §3.2 / §3.3 / §3.4 逐字实现的。
     * 真正开始写确认流程时才发现有两件事没地方放：
     *
     * <ol>
     *   <li><b>分类表没有「收支方向」。</b>D1-A §1 把一级分类明确分成支出 10 项与
     *       收入 6 项，但 §3.4 的列清单漏了这一列。不补的话，分类选择器无法按
     *       收支分组，模型的分类枚举也无法按方向过滤——一笔支出可能被填成「工资」。</li>
     *   <li><b>账目表无法区分「用户确认过的」与「免确认自动落账的」。</b>
     *       D1 §5.3 要求自动落账的账目带视觉标记，但 §3.3 的列里没有任何字段能做
     *       这个区分；而 §4 的状态图显示两条路都走 CONFIRMED → POSTED，
     *       所以草稿状态也区分不了。</li>
     * </ol>
     *
     * <p>这一条迁移是真实开发过程的产物，不是为演示而造的。
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // (1) 分类表补方向列。
            //     刻意不带 DEFAULT：二级分类的方向由父级继承，
            //     若写成 DEFAULT 'expense'，全部二级会被错标成支出。
            db.execSQL("ALTER TABLE t_category ADD COLUMN direction TEXT");

            // (2) 回填一级分类的方向。
            //     分类名从播种用的同一份常量生成，不在迁移里手抄一遍，避免两处漂移。
            //     v1 时期还不存在用户自定义分类（P1 是第一个版本），按名称匹配即可
            //     完整覆盖；等 P1 之后出现自定义收入分类时，这条迁移早已执行完毕。
            //     顺序有讲究：先把收入的标出来，剩下的未标注一级就都是支出。
            db.execSQL("UPDATE t_category SET direction = 'income'"
                    + " WHERE parent_id IS NULL AND name IN (" + quotedIncomeLevelOneNames() + ")");
            db.execSQL("UPDATE t_category SET direction = 'expense'"
                    + " WHERE parent_id IS NULL AND direction IS NULL");

            // (3) 账目表补「免确认直落」标记。
            //     默认 0 必须与 EntryEntity 上的 @ColumnInfo(defaultValue = "0") 一致，
            //     否则 Room 会判定「迁移结果 ≠ 实体定义」并直接报错。
            db.execSQL("ALTER TABLE t_entry ADD COLUMN auto_posted INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * v2 → v3：设置表补「账本分类显示」（2026-10-08）。
     *
     * <p>背景：账本行原本永远铺完整路径（{@code 餐饮>外卖}），有反馈说一行里
     * 二级分类太长、又和商户抢注意力，希望只看一级。做成设置项而不是直接改死，
     * 是因为「简单 / 标准」两种偏好都合理，不该由开发者替用户定。
     *
     * <p>列为可空、且<b>回填 'standard'</b>：老用户升级后看到的必须和升级前一样，
     * 不能因为一次升级就让账本换了副样子。回填也顺手让「空值」不会流到使用处。
     */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE t_setting ADD COLUMN ledger_category_mode TEXT");
            db.execSQL("UPDATE t_setting SET ledger_category_mode = 'standard'");
        }
    };

    /**
     * v3 → v4：多账本（2026-10-11）。
     *
     * <p>背景：用户要求「账本选择，像游戏存档那样」，也就是从单账本走向多账本。
     * 隔离维度<b>复用既有的 {@code user_id}</b>（D1 §0 早就为将来预留了它），
     * 所以这一版<b>不给任何已有表加列</b>——只新增一张存账本元信息的 {@code t_ledger}，
     * 再把升级前那套数据（{@code user_id = 1}）登记成第一个账本。
     *
     * <p>另外补一条预置分类「人情 &gt; 转账」：转账在旧实现里是复用支出树、挂在
     * 「人情」一级上（决策 27），现在单独给它一个二级分类，账目挂得更准。
     * 新装的设备由 {@code CategorySeeder} 直接带上它，这一句只为升级的设备补。
     */
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // (1) 账本表。列定义必须与 LedgerEntity 完全一致，否则 Room 会判定
            //     「迁移结果 ≠ 实体定义」而报错（HANDOFF §8-17）。
            db.execSQL("CREATE TABLE IF NOT EXISTS `t_ledger` ("
                    + "`ledger_id` INTEGER NOT NULL, "
                    + "`name` TEXT, "
                    + "`created_at` INTEGER NOT NULL, "
                    + "`sort_order` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`ledger_id`))");

            // (2) 把升级前的那套数据登记成第一个账本。用 IF NOT 语义（INSERT OR IGNORE）
            //     避免重复执行时报主键冲突。名字固定，用户随后可重命名。
            db.execSQL("INSERT OR IGNORE INTO t_ledger (ledger_id, name, created_at, sort_order)"
                            + " VALUES (?, ?, ?, ?)",
                    new Object[]{WiseBookDatabase.DEFAULT_USER_ID, "默认账本",
                            System.currentTimeMillis(), 0});

            // (3) 给已有账本的「人情」补一个二级分类「转账」。
            //     用 SELECT 而不是写死 category_id：父分类的 id 是自增的，不可假设。
            //     二级分类的 direction 恒为 NULL（方向由父级继承，见 CategoryEntity）。
            db.execSQL("INSERT INTO t_category"
                    + " (user_id, parent_id, level, name, is_system, is_archived,"
                    + " sort_order, icon, direction)"
                    + " SELECT user_id, category_id, 2, '转账', 1, 0, 99, NULL, NULL"
                    + " FROM t_category WHERE parent_id IS NULL AND name = '人情'");
        }
    };

    /** 全部迁移，按 from → to 升序 */
    public static final Migration[] ALL = {
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
    };

    /** 把收入一级分类名拼成 SQL 的 IN 列表 */
    private static String quotedIncomeLevelOneNames() {
        StringBuilder builder = new StringBuilder();
        for (String name : CategorySeeder.incomeLevelOneNames()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append('\'').append(name).append('\'');
        }
        return builder.toString();
    }
}
