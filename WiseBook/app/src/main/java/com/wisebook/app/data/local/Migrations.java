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

    /** 全部迁移，按 from → to 升序 */
    public static final Migration[] ALL = {
            MIGRATION_1_2,
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
