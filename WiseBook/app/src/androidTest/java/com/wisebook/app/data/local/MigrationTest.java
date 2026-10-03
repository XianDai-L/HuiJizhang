package com.wisebook.app.data.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wisebook.app.data.local.seed.CategorySeeder;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.List;

/**
 * 迁移测试（HANDOFF §6 要求「必须实现 Migration」）。
 *
 * <p>关键点：<b>v1 的库不是在这里手写 CREATE TABLE 造出来的</b>，
 * 而是由 {@link MigrationTestHelper} 读取 {@code app/schemas/.../1.json}
 * 还原出来的。那个 JSON 是 Room 从 v1 实体自动导出的，
 * 所以「旧版本的库长什么样」这件事只有一份事实来源，不会出现
 * 「测试里写的 v1 和真实的 v1 不一致」这种自欺。
 *
 * <p>{@code runMigrationsAndValidate} 还会顺带做一件更重要的事：
 * 把迁移后的库结构与 Room 依据<b>当前实体</b>生成的期望结构逐列比对。
 * 只要有一个列的类型 / 可空性 / 默认值与实体不一致，测试就会失败——
 * 这就是「迁移写漏了、写错了」的自动哨兵。
 */
@RunWith(AndroidJUnit4.class)
public class MigrationTest {

    /** 两个用例各用一个库名，避免 @Rule 之间互相干扰 */
    private static final String PARTIAL_DB = "migration-partial.db";
    private static final String FULL_DB = "migration-full.db";

    @Rule
    public MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            WiseBookDatabase.class);

    /**
     * v1 → v2：两列补上、收入分类回填正确、老数据一条不少。
     */
    @Test
    public void migrate1To2_addsColumnsAndBackfillsDirection() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(PARTIAL_DB, 1);

        // 造出 v1 时代的数据：一条支出一级、一条它的二级、一条收入一级、一笔账目
        long foodId = insertCategoryV1(db, "餐饮", null, 1, 0);
        insertCategoryV1(db, "咖啡", foodId, 2, 0);
        insertCategoryV1(db, "工资", null, 1, 1);
        insertEntryV1(db, 7L, 2800L);
        db.close();

        db = helper.runMigrationsAndValidate(PARTIAL_DB, 2, true, Migrations.MIGRATION_1_2);

        assertEquals("支出一级应回填为 expense", "expense", directionOf(db, "餐饮"));
        assertEquals("收入一级应回填为 income", "income", directionOf(db, "工资"));
        assertNull("二级方向由父级继承，应保持 NULL", directionOf(db, "咖啡"));

        assertEquals("v1 的分类必须一条不少", 3, countOf(db, "t_category"));
        assertEquals("v1 的账目必须一条不少", 1, countOf(db, "t_entry"));
        assertEquals("历史账目默认不是自动落账",
                0, singleIntOf(db, "SELECT auto_posted FROM t_entry"));

        db.close();
    }

    /**
     * 回填必须<b>覆盖全部</b>一级分类，不能有漏网的。
     *
     * <p>漏网的表现是「本该是收入的被标成支出」，所以断言要同时数两个方向的条数：
     * 只数 income 会漏掉「某个收入分类被标成 expense」的情况。
     */
    @Test
    public void migrate1To2_backfillsEveryTopLevelCategory() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(FULL_DB, 1);

        List<String> expenseNames = CategorySeeder.expenseLevelOneNames();
        List<String> incomeNames = CategorySeeder.incomeLevelOneNames();
        long sortOrder = 0;
        for (String name : expenseNames) {
            insertCategoryV1(db, name, null, 1, sortOrder++);
        }
        for (String name : incomeNames) {
            insertCategoryV1(db, name, null, 1, sortOrder++);
        }
        db.close();

        db = helper.runMigrationsAndValidate(FULL_DB, 2, true, Migrations.MIGRATION_1_2);

        assertEquals("全部支出一级都应回填为 expense",
                expenseNames.size(), countWhere(db, "parent_id IS NULL AND direction = 'expense'"));
        assertEquals("全部收入一级都应回填为 income",
                incomeNames.size(), countWhere(db, "parent_id IS NULL AND direction = 'income'"));
        assertEquals("一级分类总数不变",
                expenseNames.size() + incomeNames.size(), countOf(db, "t_category"));

        db.close();
    }

    // ------------------------------------------------------------------ 辅助

    private static long insertCategoryV1(SupportSQLiteDatabase db, String name, Long parentId,
                                        int level, long sortOrder) {
        db.execSQL("INSERT INTO t_category"
                        + " (user_id, parent_id, level, name, is_system, is_archived, sort_order)"
                        + " VALUES (1, ?, ?, ?, 1, 0, ?)",
                new Object[]{parentId, level, name, sortOrder});
        return lastInsertRowId(db);
    }

    /** 只填 v1 里 NOT NULL 的列，其余留空——恰好也在验证空列不影响迁移 */
    private static void insertEntryV1(SupportSQLiteDatabase db, long draftId, long amountCents) {
        db.execSQL("INSERT INTO t_entry"
                        + " (draft_id, user_id, amount_cents, amount_is_estimated,"
                        + " occurred_at, category_id, root_category_id, created_at)"
                        + " VALUES (?, 1, ?, 0, 1700000000000, 1, 1, 1700000000000)",
                new Object[]{draftId, amountCents});
    }

    private static long lastInsertRowId(SupportSQLiteDatabase db) {
        try (Cursor cursor = db.query("SELECT last_insert_rowid()")) {
            assertTrue(cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private static String directionOf(SupportSQLiteDatabase db, String name) {
        try (Cursor cursor = db.query(
                "SELECT direction FROM t_category WHERE name = ?", new Object[]{name})) {
            assertTrue("应能找到分类：" + name, cursor.moveToFirst());
            return cursor.isNull(0) ? null : cursor.getString(0);
        }
    }

    private static int countOf(SupportSQLiteDatabase db, String table) {
        return singleIntOf(db, "SELECT COUNT(*) FROM " + table);
    }

    private static int countWhere(SupportSQLiteDatabase db, String where) {
        return singleIntOf(db, "SELECT COUNT(*) FROM t_category WHERE " + where);
    }

    private static int singleIntOf(SupportSQLiteDatabase db, String sql) {
        try (Cursor cursor = db.query(sql)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }
}
