package com.wisebook.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import com.wisebook.app.data.transfer.EntryCsv;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.draft.DedupeKey;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.DraftStatus;
import com.wisebook.app.domain.model.EvidenceType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 账本导出 / 导入（2026-10-08）。
 *
 * <p>要守住的是一条"备份能不能用"的链：
 * <b>导出的 CSV 能被自己读回来、重复导入不会长出两套账、导入的账目能落进账本</b>。
 * CSV 的转义与金额精度由 JVM 的 {@code EntryCsvTest} 盯着，这里只管"进出数据库"这一段。
 */
@RunWith(AndroidJUnit4.class)
public class EntryTransferRepositoryTest {

    private static final long USER_ID = WiseBookDatabase.DEFAULT_USER_ID;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private WiseBookDatabase db;
    private EntryRepository entryRepository;
    private EntryTransferRepository transferRepository;
    private CategoryTree tree;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, WiseBookDatabase.class)
                .allowMainThreadQueries()
                .build();
        CategorySeeder.seedIfEmpty(db, USER_ID);

        CurrentLedger ledger = () -> USER_ID;
        entryRepository = new EntryRepository(db, new EvidenceStore(context.getFilesDir()), ledger);
        CategoryRepository categoryRepository = new CategoryRepository(db, ledger);
        transferRepository = new EntryTransferRepository(db, categoryRepository, entryRepository,
                ledger, SHANGHAI);
        tree = categoryRepository.loadTree(USER_ID);
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void importIntoAnEmptyLedgerCreatesEntriesAndTheirDrafts() {
        // 手写一份与导出格式一致的 CSV：这条验的是"导入能不能独立成立"，
        // 值级别的往返保真由 EntryCsvTest 盯着，两边不重复造轮子
        String csv = "日期,方向,金额(元),分类路径,商户,原话\n"
                + "2026-10-07 21:09,支出,12.34,餐饮>外卖,楼下小馆,楼下小馆吃面 12.34\n"
                + "2026-09-30 08:00,转账,56,人情>红包,张三,给张三转了 56\n";

        EntryTransferRepository.ImportReport report = transferRepository.importCsv(csv);

        assertTrue(report.headerFound);
        assertEquals("两笔都该被导入", 2, report.added);
        assertEquals(0, report.skippedDuplicate);
        assertEquals(0, report.badRows);
        assertEquals(0, report.categoryFallback);

        List<EntryEntity> entries = entryRepository.findAll();
        assertEquals(2, entries.size());
        assertEquals("10 月 7 日那笔排在最前（按发生时间倒序）", 1_234L, entries.get(0).amountCents);
        // CSV 里写的是 08:00，导入后必须还是 08:00 那一刻（按本机时区解释）
        assertEquals(LocalDateTime.of(2026, 9, 30, 8, 0).atZone(SHANGHAI)
                        .toInstant().toEpochMilli(),
                entries.get(1).occurredAt);
        assertEquals("商户要留住", "张三", entries.get(1).merchant);
        assertEquals("方向要认出来", Direction.TRANSFER, entries.get(1).direction);
        assertEquals("分类路径要解析成本机 id",
                tree.idOfPath("人情>红包", Direction.TRANSFER),
                Long.valueOf(entries.get(1).categoryId));

        // 每笔导入都要有自己的草稿：t_entry.draft_id 是 UNIQUE，且详情页的可解释性靠它
        DraftEntity draft = db.draftDao().findById(entries.get(1).draftId);
        assertNotNull("导入的账目必须同时有一条草稿，否则详情页没有来历可看", draft);
        assertEquals(DraftStatus.POSTED, draft.status);
        assertEquals("原话要还原出来", "给张三转了 56", draft.rawInput);
        assertFalse("导入的账不该被标成「免确认直落」——那是 AI 记的标记",
                entries.get(1).autoPosted);
    }

    @Test
    public void exportingThenImportingTheSameFileSkipsEverything() {
        insertPostedDraft(occurredAt(2026, 10, 7), 1_234L, "楼下小馆");
        insertPostedDraft(occurredAt(2026, 9, 30), 5_600L, "菜市场");

        EntryTransferRepository.ExportResult exported = transferRepository.exportAll();
        assertEquals("两笔账目都要进文件", 2, exported.count);
        assertTrue("文件头要能被认出来（BOM 由写文件那层拼，解析时忽略）",
                exported.csv.startsWith("日期,方向,金额(元)"));

        EntryTransferRepository.ImportReport report = transferRepository.importCsv(exported.csv);

        assertEquals("文件里的两笔本机都有，一笔都不该新增", 0, report.added);
        assertEquals(2, report.skippedDuplicate);
        assertEquals("账本里仍然只有两笔", 2, entryRepository.findAll().size());
    }

    @Test
    public void unknownCategoryPathFallsBackInsteadOfFailing() {
        // 用户手改过 CSV、或者分类在本机被停用：不该整行丢掉，更不该中断导入
        String csv = "日期,方向,金额(元),分类路径,商户\n"
                + "2026-10-07 21:09,支出,28,不存在的分类>子项,某店\n";

        EntryTransferRepository.ImportReport report = transferRepository.importCsv(csv);

        assertEquals(1, report.added);
        assertEquals("分类没匹配上要计数，好让用户知道有几笔被归到了默认桶",
                1, report.categoryFallback);

        EntryEntity entry = entryRepository.findAll().get(0);
        Long misc = tree.idOfPath("其他", Direction.EXPENSE);
        assertNotNull(misc);
        assertEquals("落默认桶，而不是留空（留空会让报表聚合不出来）",
                misc, Long.valueOf(entry.categoryId));
    }

    @Test
    public void nonCsvFileIsRejectedWithoutTouchingTheDatabase() {
        EntryTransferRepository.ImportReport report =
                transferRepository.importCsv("这就是一段普通文字，不是 CSV");

        assertFalse(report.headerFound);
        assertEquals(0, report.added);
        assertTrue("什么都不该写进库", entryRepository.findAll().isEmpty());
    }

    // ------------------------------------------------------------------ 夹具

    private static long occurredAt(int year, int month, int day) {
        ZonedDateTime moment = LocalDateTime.of(year, month, day, 12, 0).atZone(SHANGHAI);
        return moment.toInstant().toEpochMilli();
    }

    /** 造一笔已落账的支出 */
    private void insertPostedDraft(long occurredAt, long amountCents, String merchant) {
        CategoryEntity category = tree.selectable(Direction.EXPENSE, CategoryScheme.STANDARD)
                .get(0);
        DraftEntity draft = new DraftEntity();
        draft.userId = USER_ID;
        draft.status = DraftStatus.CONFIRMED;
        draft.source = DraftSource.TEXT_CHAT;
        draft.evidenceType = EvidenceType.TEXT;
        draft.rawInput = "测试用的一句话";
        draft.direction = Direction.EXPENSE;
        draft.amountCents = amountCents;
        draft.amountRaw = EntryCsv.yuan(amountCents);
        draft.occurredAt = occurredAt;
        draft.categoryId = category.categoryId;
        draft.rootCategoryId = tree.rootIdOf(category.categoryId);
        draft.merchant = merchant;
        draft.dedupeKey = DedupeKey.of(Direction.EXPENSE, amountCents, occurredAt, merchant);
        draft.createdAt = occurredAt;
        draft.updatedAt = occurredAt;
        long draftId = db.draftDao().insert(draft);
        assertTrue(entryRepository.post(draftId, false).isOk());
    }
}
