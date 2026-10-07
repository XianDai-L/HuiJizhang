package com.wisebook.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wisebook.app.data.local.EvidenceStore;
import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.entity.CategoryEntity;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.data.local.seed.CategorySeeder;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.draft.DedupeKey;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.DraftStatus;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.domain.report.MonthRange;
import com.wisebook.app.domain.report.MonthlyReport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 账目层：撤销、改正、按区间取数（D1 §5.3 / §6.2）。
 *
 * <p>重点在<b>「改正」的两面性</b>：账目改了、草稿也得改，
 * 而且报表要跟着变——三件事少任何一件，用户都会看到自相矛盾的数据。
 */
@RunWith(AndroidJUnit4.class)
public class EntryRepositoryTest {

    private static final long USER_ID = WiseBookDatabase.DEFAULT_USER_ID;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private WiseBookDatabase db;
    private EntryRepository entryRepository;
    private ReportRepository reportRepository;
    private CategoryTree tree;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, WiseBookDatabase.class)
                .allowMainThreadQueries()
                .build();
        CategorySeeder.seedIfEmpty(db);

        entryRepository = new EntryRepository(db, new EvidenceStore(context.getFilesDir()));
        reportRepository = new ReportRepository(db);
        tree = new CategoryRepository(db).loadTree(USER_ID);
    }

    @After
    public void tearDown() {
        db.close();
    }

    // ------------------------------------------------------------------ 改正

    @Test
    public void correctCategoryUpdatesEntryAndDraftTogether() {
        List<CategoryEntity> categories = twoCategoriesWithDifferentRoots();
        long oldCategory = categories.get(0).categoryId;
        long oldRoot = tree.rootIdOf(oldCategory);
        long newCategory = categories.get(1).categoryId;
        long newRoot = tree.rootIdOf(newCategory);

        long draftId = insertPostedDraft(oldCategory, oldRoot, occurredAt(2026, 9, 10), 3_000L);
        long entryId = db.entryDao().findByDraftId(draftId).entryId;

        assertTrue(entryRepository.correctCategory(entryId, newCategory, newRoot));

        EntryEntity entry = db.entryDao().findById(entryId);
        assertEquals(newCategory, entry.categoryId);
        assertEquals(newRoot, entry.rootCategoryId);

        // 草稿侧的分类列是可空的（Long），显式转成基本类型再比，
        // 否则 assertEquals 的 (long,long) 与 (Object,Object) 两个重载会撞车
        DraftEntity draft = db.draftDao().findById(draftId);
        assertNotNull(draft.categoryId);
        assertEquals("草稿必须跟着改：否则库里会留下「账本说 A、草稿说 B」的两份矛盾记录",
                newCategory, (long) draft.categoryId);
        assertEquals(newRoot, (long) draft.rootCategoryId);
    }

    @Test
    public void correctingCategoryLeavesTheDedupeKeyAlone() {
        // 去重键的构成是「方向 + 金额 + 发生时间（分钟）+ 商户」，不含分类（见 DedupeKey）。
        // 这条用例把「不含分类」这个前提钉住：将来若有人给键里加了分类，
        // 已建好的索引会全部失效，而这条测试会立刻失败
        List<CategoryEntity> categories = twoCategoriesWithDifferentRoots();
        long draftId = insertPostedDraft(categories.get(0).categoryId,
                tree.rootIdOf(categories.get(0).categoryId), occurredAt(2026, 9, 10), 3_000L);
        long entryId = db.entryDao().findByDraftId(draftId).entryId;

        String before = db.entryDao().findById(entryId).dedupeKey;
        assertNotNull("前提：这笔账目本来就有去重键", before);

        entryRepository.correctCategory(entryId,
                categories.get(1).categoryId, tree.rootIdOf(categories.get(1).categoryId));

        assertEquals(before, db.entryDao().findById(entryId).dedupeKey);
    }

    @Test
    public void reportFollowsTheCorrection() {
        // 改正分类的最终目的是让报表变对，所以这条是端到端的落点断言
        List<CategoryEntity> categories = twoCategoriesWithDifferentRoots();
        CategoryEntity first = categories.get(0);
        CategoryEntity second = categories.get(1);
        long firstRoot = tree.rootIdOf(first.categoryId);
        long secondRoot = tree.rootIdOf(second.categoryId);
        MonthRange september = MonthRange.of(2026, 9, SHANGHAI);

        long draftA = insertPostedDraft(first.categoryId, firstRoot, occurredAt(2026, 9, 10), 3_000L);
        long entryA = db.entryDao().findByDraftId(draftA).entryId;
        insertPostedDraft(second.categoryId, secondRoot, occurredAt(2026, 9, 11), 7_000L);

        MonthlyReport before = reportRepository.build(september, true);
        assertEquals(2, before.expenseRows.size());
        assertEquals(7_000L, before.expenseRows.get(0).totalCents);

        entryRepository.correctCategory(entryA, second.categoryId, secondRoot);

        MonthlyReport after = reportRepository.build(september, true);
        assertEquals("两笔并到同一个一级分类之后，报表该只剩一行", 1, after.expenseRows.size());
        assertEquals(10_000L, after.expenseRows.get(0).totalCents);
        assertEquals(1000, after.expenseRows.get(0).sharePermille);
        assertEquals("改正分类不该动到合计", 10_000L, after.expenseCents);
    }

    // ------------------------------------------------------------------ 撤销

    @Test
    public void voidEntryRemovesTheEntryAndKeepsTheDraft() {
        CategoryEntity category = twoCategoriesWithDifferentRoots().get(0);
        long draftId = insertPostedDraft(category.categoryId,
                tree.rootIdOf(category.categoryId), occurredAt(2026, 9, 10), 2_800L);
        long entryId = db.entryDao().findByDraftId(draftId).entryId;

        assertTrue(entryRepository.voidEntry(entryId));

        assertNull("账目要真的从账本与统计里消失", db.entryDao().findById(entryId));
        DraftEntity draft = db.draftDao().findById(draftId);
        assertNotNull("草稿与原始输入必须保留：用户说「这笔记错了」时要能回看原话", draft);
        assertEquals(DraftStatus.DISCARDED, draft.status);
        assertNull(draft.entryId);
    }

    @Test
    public void voidingAnUnknownEntryIsANoOp() {
        assertFalse(entryRepository.voidEntry(12_345L));
    }

    // -------------------------------------------------------------- 按月取数

    @Test
    public void findBetweenOnlyReturnsTheMonth() {
        MonthRange september = MonthRange.of(2026, 9, SHANGHAI);
        CategoryEntity category = twoCategoriesWithDifferentRoots().get(0);
        long rootId = tree.rootIdOf(category.categoryId);

        insertPostedDraft(category.categoryId, rootId, occurredAt(2026, 8, 31), 1_000L);
        insertPostedDraft(category.categoryId, rootId, occurredAt(2026, 9, 1), 2_000L);
        insertPostedDraft(category.categoryId, rootId, occurredAt(2026, 9, 30), 3_000L);
        insertPostedDraft(category.categoryId, rootId, occurredAt(2026, 10, 1), 4_000L);

        List<EntryEntity> entries = entryRepository.findBetween(
                september.fromInclusive, september.toExclusive);
        assertEquals("只该有 9 月 1 日与 9 月 30 日两笔", 2, entries.size());
        assertEquals("9 月 30 日那笔排在最前（按发生时间倒序）", 3_000L, entries.get(0).amountCents);
        assertEquals("SQL 聚合出来的合计必须与明细对得上", 5_000L,
                reportRepository.build(september, true).expenseCents);
    }

    // ------------------------------------------------------------------ 夹具

    private static long occurredAt(int year, int month, int day) {
        ZonedDateTime moment = LocalDateTime.of(year, month, day, 12, 0).atZone(SHANGHAI);
        return moment.toInstant().toEpochMilli();
    }

    /**
     * 取两个<b>一级分类不同</b>的末级分类。
     *
     * <p>必须是不同的一级分类，否则报表按 {@code root_category_id} 聚合后会并成一行，
     * 「改正之后报表变样」这个断言就失去意义了。
     */
    private List<CategoryEntity> twoCategoriesWithDifferentRoots() {
        List<CategoryEntity> selectable =
                tree.selectable(Direction.EXPENSE, CategoryScheme.STANDARD);
        List<CategoryEntity> picked = new ArrayList<>(2);
        Set<Long> roots = new HashSet<>();
        for (CategoryEntity category : selectable) {
            if (roots.add(tree.rootIdOf(category.categoryId))) {
                picked.add(category);
                if (picked.size() == 2) {
                    break;
                }
            }
        }
        assertEquals("预置分类里应当至少有 2 个一级支出分类", 2, picked.size());
        return picked;
    }

    /** 造一笔已落账的支出，返回草稿 id */
    private long insertPostedDraft(long categoryId, long rootCategoryId, long occurredAt,
                                   long amountCents) {
        DraftEntity draft = new DraftEntity();
        draft.userId = USER_ID;
        draft.status = DraftStatus.CONFIRMED;
        draft.source = DraftSource.TEXT_CHAT;
        draft.evidenceType = EvidenceType.TEXT;
        draft.rawInput = "测试用的一句话";
        draft.direction = Direction.EXPENSE;
        draft.amountCents = amountCents;
        draft.occurredAt = occurredAt;
        draft.categoryId = categoryId;
        draft.rootCategoryId = rootCategoryId;
        draft.merchant = "测试商户";
        draft.dedupeKey = DedupeKey.of(Direction.EXPENSE, amountCents, occurredAt, draft.merchant);
        draft.createdAt = occurredAt;
        draft.updatedAt = occurredAt;
        draft.splitIndex = 0;
        draft.confidenceFlags = new ArrayList<>();

        long draftId = db.draftDao().insert(draft);
        assertTrue("前提：这笔应当能落账",
                entryRepository.post(draftId, false).isNewlyPosted());
        return draftId;
    }
}
