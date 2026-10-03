package com.wisebook.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wisebook.app.BuildConfig;
import com.wisebook.app.R;
import com.wisebook.app.WiseBookApp;
import com.wisebook.app.data.EntryRepository;
import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.entity.CategoryEntity;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.DraftStatus;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.ui.confirm.ConfirmActivity;
import com.wisebook.app.ui.ledger.EntryDetailActivity;
import com.wisebook.app.ui.ledger.LedgerActivity;
import com.wisebook.app.ui.ledger.MainActivity;
import com.wisebook.app.ui.pending.PendingDraftsActivity;
import com.wisebook.app.ui.report.ReportActivity;
import com.wisebook.app.ui.settings.SettingsActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 界面冒烟测试：确认各页面能起来、能完成布局填充。
 *
 * <p>界面代码本身很薄（逻辑都在 ViewModel 与仓储层，已被 JVM 单测覆盖），
 * 所以这里不测业务，只守一类真实会挂的事：<b>布局填充失败、
 * ViewModel 工厂没被正确调用</b>。后者尤其值得守——
 * {@code ViewModelProvider.Factory} 的 {@code create(Class)} 一旦没被走到，
 * 表现就是进页面直接崩，而不是编译期报错。
 *
 * <p>输入栏的三条用例（输入 → 发送 → 结果行）用的是首页而不是单独的对话页：
 * 2026-10-02 起对话页并入首页（HANDOFF 决策 33），**入口变了，被守住的那条链没变**。
 */
@RunWith(AndroidJUnit4.class)
public class UiSmokeTest {

    @Test
    public void mainActivityStartsWithItsComposer() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull("首页应有输入框", activity.findViewById(R.id.input));
                assertNotNull("首页应有发送按钮", activity.findViewById(R.id.btn_send));
                assertNotNull("首页应有语音切换", activity.findViewById(R.id.btn_voice_mode));
                assertNotNull("首页应有账本入口", activity.findViewById(R.id.btn_ledger));
                assertNotNull("首页应有设置入口", activity.findViewById(R.id.btn_settings));

                // 空闲状态：发送可点，结果行与进度提示都不该露出来
                assertTrue("空闲时发送按钮应可点",
                        activity.findViewById(R.id.btn_send).isEnabled());
                assertEquals(View.GONE, activity.findViewById(R.id.parse_result).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.parsing_hint).getVisibility());
            });
        }
    }

    @Test
    public void voiceModeTogglesBetweenTypingAndSpeaking() {
        // 语音切换是个"两个控件互斥显示"的开关。它切错了页面不会报错，
        // 只会让用户对着一个空白的输入区发呆——所以必须把它钉住
        grantRecordPermission();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View input = activity.findViewById(R.id.input);
                View speak = activity.findViewById(R.id.speak);
                assertEquals("默认是文字输入", View.VISIBLE, input.getVisibility());
                assertEquals(View.GONE, speak.getVisibility());

                activity.findViewById(R.id.btn_voice_mode).performClick();
                assertEquals("切到语音后输入框该藏起来", View.GONE, input.getVisibility());
                assertEquals("切到语音后该出现「点一下开始说话」",
                        View.VISIBLE, speak.getVisibility());

                activity.findViewById(R.id.btn_voice_mode).performClick();
                assertEquals("再点一下切回文字", View.VISIBLE, input.getVisibility());
                assertEquals(View.GONE, speak.getVisibility());
            });
        }
    }

    @Test
    public void sendingClearsTheInput() {
        // 发送即清空（HANDOFF 决策 34）。输入框里留着上一句会让人以为
        // "再点一次就是重发"——而这条路径下重复提交是会长出两笔账的
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                EditText input = activity.findViewById(R.id.input);
                input.setText("今天打车 28");
                activity.findViewById(R.id.btn_send).performClick();

                assertTrue("发送之后输入框必须空掉", input.getText().toString().isEmpty());
            });
        }
    }

    @Test
    public void homeShowsGuidanceWhenNoApiKeyIsConfigured() {
        // 这条的前提是「构建时没有注入 Key」。local.properties 里配了 Key 之后，
        // 「没有可用的 Key」这个分支根本走不到，这条用例就失去意义了——
        // 与其让它在配了 Key 的环境里变红（那只会掩盖真正的问题），不如把前提写清楚。
        assumeTrue("构建时已注入默认 Key，跳过「无 Key 引导」用例",
                BuildConfig.DEEPSEEK_API_KEY.isEmpty() && BuildConfig.SILICONFLOW_API_KEY.isEmpty());

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                EditText input = activity.findViewById(R.id.input);
                TextView result = activity.findViewById(R.id.parse_result);
                input.setText("今天打车 28");
                activity.findViewById(R.id.btn_send).performClick();

                assertEquals("没有 Key 时结果行必须露出来", View.VISIBLE, result.getVisibility());
                String text = result.getText().toString();
                assertTrue("没有 Key 时应提示去配置，实际：" + text, text.contains("API Key"));
                assertFalse("没配 Key 时哪儿也去不了，这一行不该看起来能点", result.isClickable());
            });
        }
    }

    @Test
    public void homeParsesARealInputAndShowsAResultRow() throws InterruptedException {
        // 这一条会真的调用模型、真的花钱，所以只在配了 Key 时执行。
        // 它验的是端到端的一根链条：输入 → 解析 → 草稿 → 落账/待确认 → 结果行。
        // 其中任何一环断了界面都不会报错，只是那一行永远不出现——
        // 这种"静默失效"只有真跑一次才看得出来
        assumeTrue("没配 Key，跳过真实解析", hasApiKey());

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.input)).setText("我买游戏花了283.4");
                activity.findViewById(R.id.btn_send).performClick();
            });

            // 真实调用要等网络；最多等 90 秒（读超时本身是 120 秒，但正常几秒就回来）
            boolean shown = false;
            for (int attempt = 0; attempt < 90 && !shown; attempt++) {
                boolean[] state = new boolean[1];
                scenario.onActivity(activity -> {
                    TextView result = activity.findViewById(R.id.parse_result);
                    state[0] = result.getVisibility() == View.VISIBLE
                            && !result.getText().toString().isEmpty();
                });
                shown = state[0];
                if (!shown) {
                    Thread.sleep(1000L);
                }
            }
            assertTrue("解析完成后，结果行应当带着结论出现在输入栏上方", shown);
        }
    }

    /** 编译期注入的 Key 或设置页填的 Key，有一个就算具备真实调用条件 */
    private static boolean hasApiKey() {
        return !BuildConfig.DEEPSEEK_API_KEY.isEmpty()
                || !BuildConfig.SILICONFLOW_API_KEY.isEmpty()
                || WiseBookApp.from(ApplicationProvider.getApplicationContext())
                        .llmConfigStore().hasApiKey();
    }

    /**
     * 直接给应用授录音权限。
     *
     * <p>不这么做的话，点语音按钮会弹出<b>系统权限框</b>——它挡住的是后面的用例，
     * 而不只是这一条，测试结果会变得莫名其妙。用 UiAutomation 授权相当于
     * 替用户先把权限点掉，被测代码走的仍是真实的「已授权」分支。
     */
    private static void grantRecordPermission() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
                ApplicationProvider.getApplicationContext().getPackageName(),
                Manifest.permission.RECORD_AUDIO);
    }

    @Test
    public void settingsActivityLoadsForm() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.group_confirm_mode));
                assertNotNull(activity.findViewById(R.id.threshold));
                assertNotNull(activity.findViewById(R.id.group_scheme));
                assertNotNull(activity.findViewById(R.id.api_key));
                assertNotNull(activity.findViewById(R.id.btn_save));
            });
        }
    }

    @Test
    public void pendingDraftsActivityStarts() {
        try (ActivityScenario<PendingDraftsActivity> scenario =
                     ActivityScenario.launch(PendingDraftsActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.list));
                assertNotNull(activity.findViewById(R.id.empty));
            });
        }
    }

    @Test
    public void ledgerActivityStarts() {
        try (ActivityScenario<LedgerActivity> scenario =
                     ActivityScenario.launch(LedgerActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.list));
                assertNotNull(activity.findViewById(R.id.summary));
            });
        }
    }

    @Test
    public void reportActivityStarts() {
        try (ActivityScenario<ReportActivity> scenario =
                     ActivityScenario.launch(ReportActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.expense_total));
                assertNotNull(activity.findViewById(R.id.income_total));
                assertNotNull(activity.findViewById(R.id.exclude_transfer));
            });
        }
    }

    @Test
    public void confirmActivityStartsForAnExistingDraft() throws Exception {
        // 确认页的 ViewModel 依赖最多（仓储 + 设置 + 分类树），
        // 光靠"能编译"不足以说明它起得来，所以这里真的塞一笔草稿进去拉起来
        Context context = ApplicationProvider.getApplicationContext();
        long draftId = insertDraftForSmoke(context);
        try (ActivityScenario<ConfirmActivity> scenario =
                     ActivityScenario.launch(ConfirmActivity.intentFor(context, draftId))) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.why));
                assertNotNull(activity.findViewById(R.id.btn_post));
                assertNotNull(activity.findViewById(R.id.btn_category));
            });
        }
    }

    /**
     * 账本与报表在<b>有数据</b>时的渲染。
     *
     * <p>这条测试覆盖一段空数据时碰不到的代码：报表的分类明细行是运行时
     * {@code inflate} + {@code addView} 出来的。若 {@code item_report_row} 里的控件 id 写错，
     * 空数据时页面一切正常、一有账目就 NPE——只测「页面能起来」是抓不到的。
     *
     * <p>会往调试包的库里留几笔演示账目：断言只用「至少渲染出一行」这种
     * 不随运行次数变化的判据，跑第二遍也不会失败。
     */
    @Test
    public void ledgerAndReportRenderPostedEntries() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        seedTwoDemoExpenses(context);

        try (ActivityScenario<LedgerActivity> ledger =
                     ActivityScenario.launch(LedgerActivity.class)) {
            assertTrue("账本列表应当渲染出账目", awaitLedgerRows(ledger, 1) >= 1);
        }
        try (ActivityScenario<ReportActivity> report =
                     ActivityScenario.launch(ReportActivity.class)) {
            assertTrue("报表应当渲染出分类明细行", awaitReportRows(report, 1) >= 1);
        }
    }

    /** 造两笔<b>一级分类不同</b>的支出 + 一笔转账，免得报表把它们并成一行 */
    private static void seedTwoDemoExpenses(Context context) throws Exception {
        WiseBookApp app = WiseBookApp.from(context);
        WiseBookDatabase db = app.database();
        Future<?> future = Executors.newSingleThreadExecutor().submit(() -> {
            // 应用启动时的播种是异步的，本测试可能比它先跑。
            // 不显式确保一次的话，分类树为空会导致一笔账都造不出来——
            // 跑全套时因为前面的测试已经把应用"热身"过，反而不容易暴露这个偶发问题
            app.categoryRepository().ensureSeeded();
            CategoryTree tree = app.categoryRepository()
                    .loadTree(WiseBookDatabase.DEFAULT_USER_ID);
            Set<Long> seenRoots = new HashSet<>();
            for (CategoryEntity category
                    : tree.selectable(Direction.EXPENSE, CategoryScheme.STANDARD)) {
                long rootId = tree.rootIdOf(category.categoryId);
                if (!seenRoots.add(rootId)) {
                    continue;
                }
                insertConfirmedDraft(db, app.entryRepository(), category, rootId);
                if (seenRoots.size() == 2) {
                    break;
                }
            }
            // 再补一笔转账：它在报表里是个独立口径（默认计入支出、勾上「排除转账」又减掉），
            // 演示数据里没有转账的话，那条路径在界面上根本走不到
            Long social = tree.idOfPath("人情", Direction.TRANSFER);
            if (social != null) {
                insertConfirmedDraft(db, app.entryRepository(), tree.byId(social), social,
                        Direction.TRANSFER, 66_000L, "给爸妈转了 660");
            }
        });
        future.get();
    }

    /**
     * 账目详情页：默认只显示原始输入，点一下才展开其余解析内容。
     *
     * <p>这条验的是"默认收起"这个交互本身——它容易在重构里被顺手改掉，
     * 而改掉之后页面并不会报错，只是用户一进来就被一屏技术细节淹掉。
     */
    @Test
    public void entryDetailShowsRawInputAndExpandsParseDetailsOnDemand() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        long entryId = seedOneDemoExpense(context);

        try (ActivityScenario<EntryDetailActivity> scenario =
                     ActivityScenario.launch(EntryDetailActivity.intentFor(context, entryId))) {
            boolean rawShown = false;
            for (int attempt = 0; attempt < 50 && !rawShown; attempt++) {
                boolean[] state = new boolean[1];
                scenario.onActivity(activity -> {
                    TextView raw = activity.findViewById(R.id.raw_text);
                    state[0] = raw.getText().toString().contains("冒烟测试");
                });
                rawShown = state[0];
                if (!rawShown) {
                    Thread.sleep(100L);
                }
            }
            assertTrue("「输入解析」默认就该显示你原来说的那句话", rawShown);

            scenario.onActivity(activity -> {
                assertEquals("解析详情默认收起",
                        View.GONE, activity.findViewById(R.id.parse_details).getVisibility());
                activity.findViewById(R.id.btn_toggle_parse).performClick();
                assertEquals("点一下才展开",
                        View.VISIBLE, activity.findViewById(R.id.parse_details).getVisibility());
            });
        }
    }

    /** 造一笔并返回它的 entry_id */
    private static long seedOneDemoExpense(Context context) throws Exception {
        WiseBookApp app = WiseBookApp.from(context);
        WiseBookDatabase db = app.database();
        Future<Long> future = Executors.newSingleThreadExecutor().submit(() -> {
            app.categoryRepository().ensureSeeded();
            CategoryTree tree = app.categoryRepository()
                    .loadTree(WiseBookDatabase.DEFAULT_USER_ID);
            for (CategoryEntity category
                    : tree.selectable(Direction.EXPENSE, CategoryScheme.STANDARD)) {
                return insertConfirmedDraft(db, app.entryRepository(), category,
                        tree.rootIdOf(category.categoryId));
            }
            throw new IllegalStateException("分类表是空的，造不出账目");
        });
        return future.get();
    }

    /** 造一笔支出并落账 */
    private static long insertConfirmedDraft(WiseBookDatabase db, EntryRepository entryRepository,
                                             CategoryEntity category, long rootCategoryId) {
        return insertConfirmedDraft(db, entryRepository, category, rootCategoryId,
                Direction.EXPENSE, 1_280L, "界面冒烟测试用的账目");
    }

    private static long insertConfirmedDraft(WiseBookDatabase db, EntryRepository entryRepository,
                                             CategoryEntity category, long rootCategoryId,
                                             Direction direction, long amountCents,
                                             String rawInput) {
        DraftEntity draft = new DraftEntity();
        draft.userId = WiseBookDatabase.DEFAULT_USER_ID;
        draft.status = DraftStatus.CONFIRMED;
        draft.source = DraftSource.TEXT_CHAT;
        draft.evidenceType = EvidenceType.TEXT;
        draft.rawInput = rawInput;
        draft.direction = direction;
        draft.amountCents = amountCents;
        draft.occurredAt = System.currentTimeMillis();
        draft.categoryId = category.categoryId;
        draft.rootCategoryId = rootCategoryId;
        draft.merchant = "冒烟测试";
        draft.createdAt = draft.occurredAt;
        draft.updatedAt = draft.occurredAt;
        draft.splitIndex = 0;
        draft.confidenceFlags = new ArrayList<>();
        long draftId = db.draftDao().insert(draft);
        // autoPosted=true：顺带把「自动记」标记也渲染出来
        return entryRepository.post(draftId, true).entryId;
    }

    /** 等账本列表把数据读出来（ViewModel 是异步的，断言不能紧跟着 launch 就跑） */
    private static int awaitLedgerRows(ActivityScenario<LedgerActivity> scenario, int atLeast)
            throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            int[] count = new int[1];
            scenario.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.list);
                count[0] = list.getAdapter() == null ? 0 : list.getAdapter().getItemCount();
            });
            if (count[0] >= atLeast) {
                return count[0];
            }
            Thread.sleep(100L);
        }
        return 0;
    }

    /** 等报表把分类明细行渲染出来 */
    private static int awaitReportRows(ActivityScenario<ReportActivity> scenario, int atLeast)
            throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            int[] count = new int[1];
            scenario.onActivity(activity -> {
                LinearLayout rows = activity.findViewById(R.id.expense_rows);
                count[0] = rows.getChildCount();
            });
            if (count[0] >= atLeast) {
                return count[0];
            }
            Thread.sleep(100L);
        }
        return 0;
    }

    /** 造一笔最简草稿（只求能让确认页渲染，字段齐全度无所谓）。<b>必须在后台线程执行</b> */
    private static long insertDraftForSmoke(Context context) throws Exception {
        WiseBookDatabase db = WiseBookApp.from(context).database();
        Future<Long> future = Executors.newSingleThreadExecutor().submit(() -> {
            DraftEntity draft = new DraftEntity();
            draft.userId = WiseBookDatabase.DEFAULT_USER_ID;
            draft.status = DraftStatus.ASKING;
            draft.source = DraftSource.TEXT_CHAT;
            draft.evidenceType = EvidenceType.TEXT;
            draft.rawInput = "冒烟测试用的一笔草稿";
            draft.direction = Direction.EXPENSE;
            draft.amountCents = 680_000L;
            draft.occurredAt = System.currentTimeMillis();
            draft.createdAt = System.currentTimeMillis();
            draft.updatedAt = System.currentTimeMillis();
            draft.splitIndex = 0;
            draft.clarifyRounds = 1;
            draft.confidenceFlags = new ArrayList<>();
            return db.draftDao().insert(draft);
        });
        return future.get();
    }
}
