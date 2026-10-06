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

import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.data.local.entity.SettingEntity;
import com.wisebook.app.data.local.seed.CategorySeeder;
import com.wisebook.app.data.remote.LlmRuntime;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.app.domain.model.ConfirmMode;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.DraftStatus;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.domain.model.OccurredAtSource;
import com.wisebook.app.domain.model.PaymentMethod;
import com.wisebook.llm.ImageReader;
import com.wisebook.llm.LlmClient;
import com.wisebook.llm.LlmRawResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * 草稿编排层：判定 → 落库 → 落账（D1 §4 / §5.2 / §7）。
 *
 * <p>这里刻意只测 {@code persist} 这一半：它不碰模型，用一笔手工构造的草稿
 * 就能把两条分支（免确认直落 / 进确认流程）都走一遍。
 * 传进去的 {@link LlmRuntime} 一旦被调用就直接断言失败——
 * 这本身就是一条不变量：<b>判定与落库不该依赖模型</b>。
 */
@RunWith(AndroidJUnit4.class)
public class DraftRepositoryTest {

    private static final long USER_ID = WiseBookDatabase.DEFAULT_USER_ID;
    private static final long THRESHOLD = 30_000L;      // 300 元
    private static final long FIXED_MILLIS = 1_790_000_040_000L;

    private WiseBookDatabase db;
    private SettingsRepository settingsRepository;
    private CategoryRepository categoryRepository;
    private EntryRepository entryRepository;
    private DraftRepository draftRepository;
    private CategoryTree tree;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, WiseBookDatabase.class)
                .allowMainThreadQueries()
                .build();
        CategorySeeder.seedIfEmpty(db);

        settingsRepository = new SettingsRepository(db);
        categoryRepository = new CategoryRepository(db);
        entryRepository = new EntryRepository(db);
        draftRepository = new DraftRepository(db, settingsRepository, categoryRepository,
                entryRepository, failingRuntime());
        tree = categoryRepository.loadTree(USER_ID);
    }

    @After
    public void tearDown() {
        db.close();
    }

    // ------------------------------------------------------ 免确认直落

    @Test
    public void cleanSmallDraftIsPostedImmediately() {
        SettingEntity settings = settingsRepository.loadOrInit(USER_ID);
        DraftRepository.SubmitReport report =
                draftRepository.persist(cleanDraft(), settings, tree, 1, true);

        assertEquals(DraftRepository.SubmitOutcome.Kind.AUTO_POSTED, report.outcome.kind);

        EntryEntity entry = db.entryDao().findByDraftId(report.outcome.draftId);
        assertTrue("免确认落账要打上标记，才能在「最近自动记账」里被认出来", entry.autoPosted);
        assertEquals(2800L, entry.amountCents);
        assertEquals(Direction.EXPENSE, entry.direction);
        assertEquals("楼下小馆", entry.merchant);

        DraftEntity stored = db.draftDao().findById(report.outcome.draftId);
        assertEquals(DraftStatus.POSTED, stored.status);
        assertEquals(Long.valueOf(entry.entryId), stored.entryId);
        assertNull("落账成功的草稿不该还挂在待处理里", stored.clarifyQuestions);
    }

    @Test
    public void postingTwiceOnlyWritesOneEntry() {
        // D1 §4.2 约束 3：POSTED 必须幂等。用户手抖点两次、或网络重试，都不能记成两笔
        SettingEntity settings = settingsRepository.loadOrInit(USER_ID);
        DraftRepository.SubmitReport report =
                draftRepository.persist(cleanDraft(), settings, tree, 1, true);
        long draftId = report.outcome.draftId;

        EntryRepository.PostOutcome again = entryRepository.post(draftId, false);

        assertEquals(EntryRepository.PostOutcome.Kind.ALREADY_POSTED, again.kind);
        assertEquals("第二次调用不得新增账目", 1, db.entryDao().count());
    }

    @Test
    public void postRefusesDraftThatWasNeverConfirmed() {
        DraftEntity draft = cleanDraft();
        draft.status = DraftStatus.DRAFT;
        long draftId = db.draftDao().insert(draft);

        EntryRepository.PostOutcome outcome = entryRepository.post(draftId, false);

        assertEquals(EntryRepository.PostOutcome.Kind.FAILED, outcome.kind);
        assertEquals(0, db.entryDao().count());
    }

    @Test
    public void postRefusesDraftWithMissingRequiredFields() {
        // 绕过 CONFIRMED 直接落账不行；状态合法但字段不齐同样不行
        DraftEntity draft = cleanDraft();
        draft.status = DraftStatus.CONFIRMED;
        draft.occurredAt = null;
        long draftId = db.draftDao().insert(draft);

        EntryRepository.PostOutcome outcome = entryRepository.post(draftId, false);

        assertEquals(EntryRepository.PostOutcome.Kind.FAILED, outcome.kind);
        assertTrue(outcome.message.contains("发生时间"));
        assertEquals(0, db.entryDao().count());
    }

    @Test
    public void postAllowsMissingPaymentMethod() {
        // 支付方式已降级为可选（HANDOFF 决策 20）：t_entry.payment_method 本来就可空，
        // 落账校验也不该拦它——否则会出现「判定说能落账、落账却说字段不齐」的自相矛盾
        DraftEntity draft = cleanDraft();
        draft.status = DraftStatus.CONFIRMED;
        draft.paymentMethod = null;
        long draftId = db.draftDao().insert(draft);

        assertTrue("缺支付方式不该拦住落账", entryRepository.post(draftId, false).isOk());
        assertEquals(1, db.entryDao().count());
    }

    // ------------------------------------------------------ 进确认流程

    @Test
    public void largeAmountGoesToConfirmFlow() {
        SettingEntity settings = largeModeSettings();
        DraftEntity draft = cleanDraft();
        draft.amountCents = THRESHOLD;   // 恰好等于阈值即触发（D1 §5.2 条件 6）

        DraftRepository.SubmitReport report =
                draftRepository.persist(draft, settings, tree, 1, true);

        assertEquals(DraftRepository.SubmitOutcome.Kind.NEEDS_CONFIRM, report.outcome.kind);
        assertEquals(0, db.entryDao().count());
        assertTrue("要能说清为什么需要确认", report.detail.contains("大额阈值"));

        DraftEntity stored = db.draftDao().findById(report.outcome.draftId);
        assertEquals(DraftStatus.ASKING, stored.status);
        assertEquals(1, stored.clarifyRounds);
    }

    @Test
    public void strictModeAsksEvenForACleanDraft() {
        SettingEntity settings = settingsRepository.loadOrInit(USER_ID);
        settings.confirmMode = ConfirmMode.STRICT;
        settingsRepository.save(settings);

        DraftRepository.SubmitReport report =
                draftRepository.persist(cleanDraft(), settingsRepository.loadOrInit(USER_ID),
                        tree, 1, true);

        assertEquals(DraftRepository.SubmitOutcome.Kind.NEEDS_CONFIRM, report.outcome.kind);
        assertEquals(0, db.entryDao().count());
    }

    @Test
    public void duplicateEntryGoesToConfirmFlow() {
        // D1 §7：命中疑似重复只捞出来给用户看，绝不自动合并
        SettingEntity settings = settingsRepository.loadOrInit(USER_ID);
        draftRepository.persist(cleanDraft(), settings, tree, 1, true);
        assertEquals(1, db.entryDao().count());

        DraftRepository.SubmitReport second =
                draftRepository.persist(cleanDraft(), settings, tree, 1, true);

        assertEquals(DraftRepository.SubmitOutcome.Kind.NEEDS_CONFIRM, second.outcome.kind);
        assertEquals("疑似重复的那笔不能自己写进去", 1, db.entryDao().count());
        assertTrue(second.detail.contains("重复"));
    }

    @Test
    public void missingAmountAsksAndDoesNotComputeDedupeKey() {
        SettingEntity settings = settingsRepository.loadOrInit(USER_ID);
        DraftEntity draft = cleanDraft();
        draft.amountCents = null;
        draft.amountRuleCheck = AmountRuleCheck.NA;

        DraftRepository.SubmitReport report =
                draftRepository.persist(draft, settings, tree, 1, true);

        assertEquals(DraftRepository.SubmitOutcome.Kind.NEEDS_CONFIRM, report.outcome.kind);
        DraftEntity stored = db.draftDao().findById(report.outcome.draftId);
        assertNull("必填不齐算不出去重键", stored.dedupeKey);
    }

    // ------------------------------------------------------ 超时归档

    @Test
    public void staleDraftIsArchivedNotDeleted() {
        // D1 §4.3：挂起超过 24 小时停止自动流转，但不删除——数据属于用户。
        // 用「每笔确认」档位让草稿停在 ASKING：小额干净草稿会被免确认直落，
        // 那就没有"挂起的草稿"可归档了（这正是上一条用例在测的事）
        SettingEntity settings = settingsRepository.loadOrInit(USER_ID);
        settings.confirmMode = ConfirmMode.STRICT;
        settingsRepository.save(settings);

        DraftRepository.SubmitReport report = draftRepository.persist(
                cleanDraft(), settingsRepository.loadOrInit(USER_ID), tree, 1, true);
        long draftId = report.outcome.draftId;
        assertEquals(DraftStatus.ASKING, db.draftDao().findById(draftId).status);

        DraftEntity stored = db.draftDao().findById(draftId);
        stored.updatedAt = System.currentTimeMillis() - DraftRepository.OPEN_TIMEOUT_MILLIS - 1000L;
        db.draftDao().update(stored);

        assertEquals(1, draftRepository.archiveStale());

        DraftEntity archived = db.draftDao().findById(draftId);
        assertNotNull("超时归档是停止流转，不是删除", archived);
        assertEquals(DraftStatus.EXPIRED, archived.status);
        assertEquals("归档后不再出现在待处理里", 0, draftRepository.countOpenDrafts());
    }

    @Test
    public void freshDraftIsNotArchived() {
        SettingEntity settings = settingsRepository.loadOrInit(USER_ID);
        draftRepository.persist(cleanDraft(), settings, tree, 1, true);

        assertEquals(0, draftRepository.archiveStale());
    }

    // ------------------------------------------------------ 确认、改后重校验、丢弃

    @Test
    public void confirmAndPostMovesAskingDraftToPosted() {
        long draftId = seedAskingDraft();

        EntryRepository.PostOutcome outcome = draftRepository.confirmAndPost(draftId);

        assertTrue(outcome.isNewlyPosted());
        assertEquals(1, db.entryDao().count());

        DraftEntity stored = db.draftDao().findById(draftId);
        assertEquals(DraftStatus.POSTED, stored.status);
        assertNull("确认之后反问清单就作废了", stored.clarifyQuestions);
        assertFalse("用户确认过的账目不该带自动落账标记",
                db.entryDao().findByDraftId(draftId).autoPosted);
    }

    @Test
    public void confirmAndPostIsIdempotent() {
        // 用户手抖点两次「确认并记账」，不能记成两笔
        long draftId = seedAskingDraft();
        draftRepository.confirmAndPost(draftId);

        EntryRepository.PostOutcome again = draftRepository.confirmAndPost(draftId);

        assertEquals(EntryRepository.PostOutcome.Kind.ALREADY_POSTED, again.kind);
        assertEquals(1, db.entryDao().count());
    }

    @Test
    public void confirmRecoversFromAHalfFinishedConfirm() {
        // 模拟「上次点确认时进程被打断」：状态已经走到 CONFIRMED，但账目没写进去。
        // 再点一次必须能补上，而不是卡在「状态为 CONFIRMED，不能确认」
        long draftId = seedAskingDraft();
        DraftEntity draft = db.draftDao().findById(draftId);
        draft.status = DraftStatus.CONFIRMED;
        db.draftDao().update(draft);

        assertTrue(draftRepository.confirmAndPost(draftId).isNewlyPosted());
        assertEquals(1, db.entryDao().count());
    }

    @Test
    public void discardChangesStatusInsteadOfDeleting() {
        long draftId = seedAskingDraft();

        assertTrue(draftRepository.discard(draftId));

        DraftEntity stored = db.draftDao().findById(draftId);
        assertNotNull("丢弃是改状态，不是删行：原始输入要留给排查", stored);
        assertEquals(DraftStatus.DISCARDED, stored.status);
        assertEquals(0, db.entryDao().count());
        assertEquals("丢弃后不再出现在待处理里", 0, draftRepository.countOpenDrafts());
    }

    @Test
    public void editsThatSatisfyTheConditionsAutoPost() {
        // 大额草稿进了确认流程；用户把金额改小 → 重新校验后条件全满足，应当直接落账
        // （D1 §4.2 约束 1：改完必须回流 DRAFT 重跑判据，而不是直接塞进账本）
        long draftId = seedAskingDraft();

        DraftEntity edit = db.draftDao().findById(draftId);
        edit.amountCents = 1000L;
        edit.confidenceFlags = new ArrayList<>();

        DraftRepository.SubmitReport report = draftRepository.applyEditsAndRevalidate(
                edit, settingsRepository.loadOrInit(USER_ID), tree);

        assertEquals(DraftRepository.SubmitOutcome.Kind.AUTO_POSTED, report.outcome.kind);
        assertEquals(1, db.entryDao().count());
        assertEquals(DraftStatus.POSTED, db.draftDao().findById(draftId).status);
    }

    @Test
    public void clarifyRoundsAreCappedAtThree() {
        // D1 §4.2 约束 2：反问最多 3 轮，问烦了就不再问，转为让用户手动补全。
        // 这里同样必须显式用大额确认档位：否则这笔大额草稿在「存疑才确认」下
        // 第三轮就直接免确认落账了，根本走不到"轮数封顶"那一步
        SettingEntity settings = largeModeSettings();
        long draftId = seedAskingDraft();
        assertEquals("第一轮反问已经问出去了", 1, db.draftDao().findById(draftId).clarifyRounds);

        for (int round = 2; round <= DraftRepository.MAX_CLARIFY_ROUNDS + 1; round++) {
            DraftEntity edit = db.draftDao().findById(draftId);
            draftRepository.applyEditsAndRevalidate(edit, settings, tree);
        }

        DraftEntity stored = db.draftDao().findById(draftId);
        assertEquals(DraftRepository.MAX_CLARIFY_ROUNDS, stored.clarifyRounds);
        assertNull("到上限之后不再生成反问清单", stored.clarifyQuestions);
    }

    // ------------------------------------------------------------------ 夹具

    /**
     * 把档位显式设成「大额确认」。
     *
     * <p>下面那些用例要验的是「金额 ≥ 阈值时的行为」，与默认档位取哪个无关，
     * 所以它们必须<b>自己把档位设成要验的那个值</b>，而不是依赖
     * {@link SettingsRepository} 的默认值。
     *
     * <p>这一点是真栽过的：默认档位从「大额确认」改成「存疑才确认」之后，
     * 依赖默认值的用例集体变红——而它们其实一条都没坏，
     * 只是"碰巧"建立在"默认值恰好是大额确认"之上。
     * 测试依赖别处的默认值，就是把别人的改动变成自己的故障。
     */
    private SettingEntity largeModeSettings() {
        SettingEntity settings = settingsRepository.loadOrInit(USER_ID);
        settings.confirmMode = ConfirmMode.LARGE;
        settingsRepository.save(settings);
        return settings;
    }

    /** 造一笔停在 ASKING 的草稿：金额等于阈值，大额确认档位下必然要确认 */
    private long seedAskingDraft() {
        DraftEntity draft = cleanDraft();
        draft.amountCents = THRESHOLD;
        draft.amountLowerCents = THRESHOLD;
        draft.amountUpperCents = THRESHOLD;
        long draftId = draftRepository
                .persist(draft, largeModeSettings(), tree, 1, true)
                .outcome.draftId;
        assertEquals("前提：这笔应当停在确认流程里",
                DraftStatus.ASKING, db.draftDao().findById(draftId).status);
        return draftId;
    }

    /** 一笔信息齐全、小额、无存疑的草稿：档位二下应当免确认直落 */
    private DraftEntity cleanDraft() {
        DraftEntity draft = new DraftEntity();
        draft.userId = USER_ID;
        draft.status = DraftStatus.DRAFT;
        draft.source = DraftSource.TEXT_CHAT;
        draft.evidenceType = EvidenceType.TEXT;
        draft.rawInput = "今天打车28";
        draft.createdAt = FIXED_MILLIS;
        draft.updatedAt = FIXED_MILLIS;

        draft.direction = Direction.EXPENSE;
        draft.amountCents = 2800L;
        draft.amountLowerCents = 2800L;
        draft.amountUpperCents = 2800L;
        draft.amountRaw = "28";
        draft.amountIsEstimated = false;
        draft.amountRuleCheck = AmountRuleCheck.PASS;

        draft.occurredAt = FIXED_MILLIS;
        draft.occurredAtSource = OccurredAtSource.EXPLICIT;

        Long categoryId = tree.idOfPath("餐饮>外卖", Direction.EXPENSE);
        draft.categoryId = categoryId;
        draft.rootCategoryId = tree.rootIdOf(categoryId);

        draft.paymentMethod = PaymentMethod.WECHAT;
        draft.merchant = "楼下小馆";
        draft.confidenceFlags = new ArrayList<>();
        draft.clarifyRounds = 0;
        draft.splitIndex = 0;
        return draft;
    }

    /** 一旦被调用就失败：判定与落库这条路径不该碰模型 */
    private static LlmRuntime failingRuntime() {
        return new LlmRuntime() {
            @Override
            public LlmClient client() {
                throw new AssertionError("persist 路径不该调用模型");
            }

            @Override
            public boolean isReady() {
                return false;
            }

            @Override
            public String modelLabel() {
                return "test:stub";
            }

            @Override
            public ImageReader imageReader() {
                // 这个替身没有转写能力：persist 路径不会走到它，
                // 而 submitImage 用它来判断"当前配置能不能截图记账"（返回 null = 不能）
                return null;
            }

            @Override
            public boolean isImageReady() {
                return false;
            }
        };
    }

    /**
     * 截图提交用的运行时：转写返回预设文本、拆笔返回预设 JSON，都不碰网络。
     *
     * <p>有了它，「一图多笔」这条链路就能在真库上被验——走的是与线上完全相同的
     * {@code ImageDraftParser} → {@code DraftAssembler} → {@code persist}。
     */
    private static LlmRuntime imageRuntime(String transcript, String batchPayload) {
        return new LlmRuntime() {
            @Override
            public LlmClient client() {
                return (systemPrompt, userContent, tool) ->
                        new LlmRawResponse("test:fake", batchPayload, null, "{\"raw\":\"stub\"}");
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public String modelLabel() {
                return "test:fake";
            }

            @Override
            public ImageReader imageReader() {
                return (imageBytes, mimeType, instruction) -> transcript;
            }

            @Override
            public boolean isImageReady() {
                return true;
            }
        };
    }

    // ------------------------------------------------------ 截图入口（一图多笔）

    @Test
    public void submitImageCreatesOneDraftPerTransaction() {
        String transcript = "微信支付\n收款方甲\n￥7.00\n收款方乙\n￥15.00";
        String payload = "{\"drafts\":["
                + "{\"direction\":\"expense\",\"amountCents\":700,\"merchant\":\"收款方甲\"},"
                + "{\"direction\":\"expense\",\"amountCents\":1500,\"merchant\":\"收款方乙\"}]}";
        DraftRepository repository = new DraftRepository(db, settingsRepository,
                categoryRepository, entryRepository, imageRuntime(transcript, payload));

        DraftRepository.ImageSubmitReport report = repository.submitImage(
                new byte[]{1, 2, 3}, "image/jpeg", "相册截图");

        assertTrue(report.isOk());
        assertEquals("一笔一张草稿", 2, report.draftCount());

        List<DraftEntity> drafts = db.draftDao().findOpen();
        assertEquals(2, drafts.size());
        assertEquals("同一张图的两笔共享批次标签", drafts.get(0).batchId, drafts.get(1).batchId);
        assertEquals(0, drafts.get(0).splitIndex);
        assertEquals("序号要与图里从上到下一致", 1, drafts.get(1).splitIndex);
        assertEquals(DraftSource.IMAGE, drafts.get(0).source);
        assertEquals(EvidenceType.IMAGE, drafts.get(0).evidenceType);
        assertNull("原图不留（HANDOFF 决策 37）", drafts.get(0).evidenceRef);
        assertEquals("转写文本留下来当原话", transcript, drafts.get(0).rawInput);
    }

    @Test
    public void submitImageWithoutTranscriberFailsBeforeCallingAnything() {
        DraftRepository repository = new DraftRepository(db, settingsRepository,
                categoryRepository, entryRepository, failingRuntime());

        DraftRepository.ImageSubmitReport report = repository.submitImage(
                new byte[]{1, 2, 3}, "image/jpeg", "相册截图");

        assertFalse(report.isOk());
        assertTrue("要说清缺的是哪一家的 Key", report.summary().contains("硅基流动"));
        assertEquals("连库都不该动", 0, db.draftDao().findOpen().size());
    }
}
