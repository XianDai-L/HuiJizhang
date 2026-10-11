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
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.data.local.seed.CategorySeeder;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.ConfidenceFlag;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.DraftStatus;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.input.bill.BillFormat;
import com.wisebook.app.input.bill.BillSheet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;

/**
 * 支付账单导入（2026-10-08）。
 *
 * <p>xlsx 的读取由 JVM 的 {@code XlsxSheetReaderTest} 盯着、字段映射由
 * {@code WechatBillAdapterTest} 盯着，这里只管<b>进出数据库那一段</b>：
 * 落没落账、方向对不对、分类有没有走上账单规则表、重复的口径是不是"打标记不跳过"。
 *
 * <p>样本是仿造的（真实的支付记录不进公开仓库），但列名与取值写法照原样。
 */
@RunWith(AndroidJUnit4.class)
public class BillImportRepositoryTest {

    private static final long USER_ID = WiseBookDatabase.DEFAULT_USER_ID;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final String HEADER =
            "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注";

    private WiseBookDatabase db;
    private EntryRepository entryRepository;
    private BillImportRepository billRepository;
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
        billRepository = new BillImportRepository(db, categoryRepository, entryRepository,
                ledger, SHANGHAI);
        tree = categoryRepository.loadTree(USER_ID);
    }

    @After
    public void tearDown() {
        db.close();
    }

    // ------------------------------------------------------------------ 夹具

    private static byte[] billBytes(String... dataRows) {
        StringBuilder text = new StringBuilder();
        text.append("微信支付账单明细,,,,,,,,,,\r\n");
        text.append("微信昵称：[某某],,,,,,,,,,\r\n");
        text.append("----------------------微信支付账单明细列表--------------------,,,,,,,,,,\r\n");
        text.append(HEADER).append("\r\n");
        for (String row : dataRows) {
            text.append(row).append("\r\n");
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private BillImportRepository.BillImportReport importRows(String... dataRows) {
        BillSheet sheet = BillSheet.read(billBytes(dataRows));
        BillFormat.Detected detected = BillFormat.detect(sheet.cells());
        assertEquals(BillFormat.WECHAT_BILL, detected.format);
        return billRepository.importBill(sheet, detected.headerRow);
    }

    // ------------------------------------------------------------------ 用例

    @Test
    public void aBillBecomesEntriesWithDirectionsAndCategories() {
        BillImportRepository.BillImportReport report = importRows(
                "2026-10-08 08:20:39,商户消费,便利超人,某饮料;,支出,¥3.50,零钱,支付成功,4500,P1,/",
                "2026-10-07 17:05:11,转账,张三,转账备注:微信转账,支出,¥5.80,零钱,对方已收钱,5301,1000,/",
                "2026-10-07 15:47:30,转账,李四,转账备注:微信转账,收入,¥3.00,/,已存入零钱,1000,/,/",
                "2026-10-07 14:31:11,商户消费,滴滴出行,费用,支出,¥41.00,零钱,支付成功,4500,M1,/");

        assertEquals(4, report.added);
        assertEquals(0, report.neutralSkipped);
        assertEquals(0, report.badRows);
        assertEquals(0, report.failed);
        assertEquals("UTF-8", report.charsetName);

        List<EntryEntity> entries = entryRepository.findAll();
        assertEquals(4, entries.size());

        // 按发生时间倒序：便利超人那笔最新
        EntryEntity newest = entries.get(0);
        assertEquals(350L, newest.amountCents);
        assertEquals(Direction.EXPENSE, newest.direction);
        assertEquals("账单规则表要在这个入口生效：便利店 → 餐饮>饮品",
                tree.idOfPath("餐饮>饮品", Direction.EXPENSE), Long.valueOf(newest.categoryId));

        EntryEntity outgoingTransfer = entries.get(1);
        assertEquals("支出的转账按转账落，报表默认把转账计入支出",
                Direction.TRANSFER, outgoingTransfer.direction);
        assertEquals("转账的兜底分类是「人情」",
                tree.idOfPath("人情", Direction.TRANSFER), Long.valueOf(outgoingTransfer.categoryId));

        EntryEntity incomingTransfer = entries.get(2);
        assertEquals("别人转给我的钱是收入，不是支出", Direction.INCOME, incomingTransfer.direction);
        assertEquals("收入转账落「红包」",
                tree.idOfPath("红包", Direction.INCOME), Long.valueOf(incomingTransfer.categoryId));

        EntryEntity rideHailing = entries.get(3);
        assertEquals("商户名命中规则表：滴滴 → 交通>打车",
                tree.idOfPath("交通>打车", Direction.EXPENSE), Long.valueOf(rideHailing.categoryId));
    }

    @Test
    public void everyImportedEntryKeepsItsDraftForTraceability() {
        importRows("2026-10-08 08:20:39,商户消费,便利超人,某饮料;,支出,¥3.50,零钱,支付成功,4500,P1,/");

        EntryEntity entry = entryRepository.findAll().get(0);
        DraftEntity draft = db.draftDao().findById(entry.draftId);

        assertNotNull("t_entry.draft_id 是 UNIQUE，且详情页的可解释性靠草稿", draft);
        assertEquals(DraftStatus.POSTED, draft.status);
        assertEquals(DraftSource.BILL_IMPORT, draft.source);
        assertEquals(EvidenceType.TEXT, draft.evidenceType);
        assertTrue("原话要写清这笔账出自哪张账单：" + draft.rawInput,
                draft.rawInput != null && draft.rawInput.contains("微信账单"));
        assertEquals(BillImportRepository.BATCH_PREFIX,
                draft.batchId.substring(0, BillImportRepository.BATCH_PREFIX.length()));
        assertFalse("导入的账不该被标成「免确认直落」——那是 AI 记的标记", entry.autoPosted);
    }

    /**
     * 命中规则表的那一笔，判定结果要<b>写回候选</b>。
     *
     * <p>账目详情页的「分类」是拿 {@code categoryId} 去 {@code categoryCandidates} 里找名字的
     * （{@code DiagnosticsFormatter}），不写回就会显示成「#5」这种裸 id
     * ——实测就是先在详情页上看到「#5」才发现漏了这一步。
     */
    @Test
    public void theAdoptedCategoryIsWrittenBackSoTheDetailPageCanShowItsName() {
        importRows("2026-10-08 08:20:39,商户消费,滴滴出行,费用,支出,¥41.00,零钱,支付成功,4500,M1,/");

        EntryEntity entry = entryRepository.findAll().get(0);
        DraftEntity draft = db.draftDao().findById(entry.draftId);

        assertNotNull("没有候选，详情页只能显示裸 id", draft.categoryCandidates);
        assertEquals(1, draft.categoryCandidates.size());
        assertEquals(Long.valueOf(entry.categoryId),
                Long.valueOf(draft.categoryCandidates.get(0).categoryId));
        assertEquals("交通>打车", draft.categoryCandidates.get(0).name);
    }

    @Test
    public void neutralTransactionsAreSkipped() {
        BillImportRepository.BillImportReport report = importRows(
                "2026-09-20 19:20:50,零钱充值,建设银行(8730),/,/,¥18.00,/,充值完成,1102,/,/");

        assertEquals("充值/提现只是钱换个口袋，不入账", 0, report.added);
        assertEquals(1, report.neutralSkipped);
        assertTrue(entryRepository.findAll().isEmpty());
    }

    /**
     * <b>重复的口径：不跳过，打「疑似重复」标记照常落账</b>（2026-10-08 用户拍板）。
     *
     * <p>这条与账本 CSV 导入<b>刻意不同</b>（那边是跳过）：账单与手记的账可能只是
     * "看起来像"，真实存在同一分钟买两次同一件东西的情况，自动跳过会把真账吞掉。
     */
    @Test
    public void reImportingTheSameFileFlagsDuplicatesInsteadOfSkippingThem() {
        String row = "2026-10-08 08:20:39,商户消费,便利超人,某饮料;,支出,¥3.50,零钱,支付成功,4500,P1,/";

        BillImportRepository.BillImportReport first = importRows(row);
        assertEquals(1, first.added);
        assertEquals(0, first.suspectedDuplicate);

        BillImportRepository.BillImportReport second = importRows(row);

        assertEquals("照常导入，不跳过", 1, second.added);
        assertEquals("但要如实报出有几笔像重复", 1, second.suspectedDuplicate);
        assertEquals(2, entryRepository.findAll().size());
    }

    @Test
    public void theDuplicateFlagLandsOnTheDraftSoTheDetailPageCanShowIt() {
        String row = "2026-10-08 08:20:39,商户消费,便利超人,某饮料;,支出,¥3.50,零钱,支付成功,4500,P1,/";
        importRows(row);
        importRows(row);

        EntryEntity newest = entryRepository.findAll().get(0);
        DraftEntity draft = db.draftDao().findById(newest.draftId);

        assertNotNull(draft.confidenceFlags);
        assertTrue("详情页的「存疑」一行就是读它",
                draft.confidenceFlags.contains(ConfidenceFlag.DUPLICATE_SUSPECT));
    }

    /**
     * 账单导入<b>不走确认流程</b>（2026-10-08 用户拍板）：一次几十上百笔，
     * 逐笔确认不现实。落库后草稿直接是 POSTED，不该停在 ASKING/CONFIRMED 上悬着。
     */
    @Test
    public void importedEntriesSkipTheConfirmFlowEntirely() {
        importRows(
                "2026-10-08 08:20:39,商户消费,便利超人,某饮料;,支出,¥3.50,零钱,支付成功,4500,P1,/",
                "2026-10-07 14:31:11,商户消费,滴滴出行,费用,支出,¥41.00,零钱,支付成功,4500,M1,/");

        for (EntryEntity entry : entryRepository.findAll()) {
            DraftEntity draft = db.draftDao().findById(entry.draftId);
            assertEquals("每一笔都直接落账", DraftStatus.POSTED, draft.status);
            assertNotNull("落账时间要写上", draft.postedAt);
        }
        assertTrue("没有一笔挂在待处理里", db.draftDao().findOpen(USER_ID).isEmpty());
    }

    @Test
    public void unrecognizedCategoryIsCountedInsteadOfSilentlySwallowed() {
        // 学校名刻意不进规则表（可能是学费，也可能是食堂一顿饭），所以会落「其他」
        BillImportRepository.BillImportReport report = importRows(
                "2026-10-08 08:20:39,商户消费,某某学院,某商品,支出,¥5.00,零钱,支付成功,4500,P1,/");

        assertEquals(1, report.added);
        assertEquals("要告诉用户有几笔还需要自己去改分类", 1, report.categoryFallback);

        EntryEntity entry = entryRepository.findAll().get(0);
        assertEquals(tree.idOfPath("其他", Direction.EXPENSE), Long.valueOf(entry.categoryId));
        assertTrue(report.summary().contains("没能从账单里认出分类"));
    }
}
