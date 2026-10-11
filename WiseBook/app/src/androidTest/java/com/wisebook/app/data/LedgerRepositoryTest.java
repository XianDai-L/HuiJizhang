package com.wisebook.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wisebook.app.data.local.EvidenceStore;
import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.data.local.entity.LedgerEntity;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.Direction;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * 多账本（2026-10-11）。
 *
 * <p>要守住的是一条用户要求：<b>记账只记当前账本、看数据只看当前账本</b>。
 * 账本维度复用 {@code user_id}，所以这条链的本质是"所有查询都带上了 user_id 过滤"——
 * 这里用两个账本各放一笔账来验它，而不是只看"新建账本成功"。
 *
 * <p>删除账本要连数据一起清掉，也在这里验：删掉一个，另一个必须毫发无损。
 */
@RunWith(AndroidJUnit4.class)
public class LedgerRepositoryTest {

    private WiseBookDatabase db;
    private LedgerSelectionStore selection;
    private LedgerRepository ledgers;
    private EntryRepository entries;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, WiseBookDatabase.class)
                .allowMainThreadQueries()
                .build();
        selection = new LedgerSelectionStore(context);
        ledgers = new LedgerRepository(db, selection, new SettingsRepository(db),
                new EvidenceStore(context.getFilesDir()));
        entries = new EntryRepository(db, new EvidenceStore(context.getFilesDir()), selection);
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void everyLedgerSeesOnlyItsOwnEntries() {
        long daily = ledgers.create("日常");
        long travel = ledgers.create("旅行");
        assertTrue("两个账本的 id 不能撞", daily != travel);

        db.entryDao().insert(entry(daily, 1L));
        db.entryDao().insert(entry(travel, 2L));

        // 「当前账本」就是查询的过滤器：切到哪个，就只看到哪个的账
        selection.select(daily);
        List<EntryEntity> dailyEntries = entries.findAll();
        assertEquals(1, dailyEntries.size());
        assertEquals(daily, dailyEntries.get(0).userId);

        selection.select(travel);
        List<EntryEntity> travelEntries = entries.findAll();
        assertEquals(1, travelEntries.size());
        assertEquals(travel, travelEntries.get(0).userId);
    }

    @Test
    public void newLedgerGetsItsOwnCategoryTree() {
        long daily = ledgers.create("日常");
        long travel = ledgers.create("旅行");

        assertTrue("新账本必须自带一套分类，否则记不了账",
                !db.categoryDao().findAll(daily).isEmpty());
        assertTrue("两个账本各有各的分类树",
                db.categoryDao().count(daily) > 0 && db.categoryDao().count(travel) > 0);
    }

    /**
     * 新建时选的分类方案要写进这个账本的设置里（2026-10-11）。
     *
     * <p>分类方案是按账本存的，所以"建账本时选了简单"必须能被之后读出来——
     * 否则新建弹框里那个选项就只是个装饰。
     */
    @Test
    public void newLedgerRemembersTheChosenScheme() {
        long simple = ledgers.create("只用一级", CategoryScheme.SIMPLE);
        long standard = ledgers.create("可以用二级", CategoryScheme.STANDARD);

        assertEquals(CategoryScheme.SIMPLE, ledgers.schemeOf(simple));
        assertEquals(CategoryScheme.STANDARD, ledgers.schemeOf(standard));
        assertEquals("不指定方案时按默认（标准）",
                CategoryScheme.STANDARD, ledgers.schemeOf(ledgers.create("不指定")));
    }

    @Test
    public void deletingALedgerWipesItsDataAndLeavesTheOtherUntouched() {
        long daily = ledgers.create("日常");
        long travel = ledgers.create("旅行");
        db.entryDao().insert(entry(daily, 1L));
        db.entryDao().insert(entry(travel, 2L));

        ledgers.delete(travel);

        assertNull("账本行没了", db.ledgerDao().find(travel));
        assertEquals("它的账目被清空", 0, db.entryDao().count(travel));
        assertEquals("它的分类被清空", 0, db.categoryDao().count(travel));
        assertEquals("另一个账本毫发无损", 1, db.entryDao().count(daily));
        assertNotNull(db.ledgerDao().find(daily));
    }

    @Test
    public void ensureDefaultAlwaysLeavesAUsableLedger() {
        // 一个账本都没有（模拟全新安装）→ 兜底建一个，并把当前账本指向它
        selection.select(999L);
        ledgers.ensureDefault();

        List<LedgerEntity> all = ledgers.list();
        assertEquals("至少有一个账本", 1, all.size());
        assertEquals("当前账本落到真实存在的那个上",
                all.get(0).ledgerId, selection.id());
        assertTrue("兜底账本的分类也播好了",
                db.categoryDao().count(selection.id()) > 0);
    }

    /** 一笔最小可用的账目；只填 schema 里 NOT NULL 的列 */
    private static EntryEntity entry(long userId, long draftId) {
        EntryEntity entity = new EntryEntity();
        entity.draftId = draftId;
        entity.userId = userId;
        entity.direction = Direction.EXPENSE;
        entity.amountCents = 1000L;
        entity.occurredAt = 1_790_000_000_000L;
        entity.categoryId = 1L;
        entity.rootCategoryId = 1L;
        entity.createdAt = 1_790_000_000_000L;
        return entity;
    }
}
