package com.wisebook.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

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
import com.wisebook.app.ui.settings.LedgerPickerActivity;
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
 * <p>输入栏的用例用的是首页而不是单独的对话页：
 * 2026-10-02 起对话页并入首页（HANDOFF 决策 33），**入口变了，被守住的那条链没变**。
 *
 * <p><b>2026-10-08 修</b>：界面改版（首页结果行 → 自动消失的浮层、设置页 → 点行弹选择器、
 * 账本页合计拆成收支两个）之后，本文件还引用着改版前的 id，导致<b>整个 androidTest 源集
 * 编译不过</b>——不是"某条用例红"，而是 44 条 instrumented 一条都跑不了。
 * 这次按新界面对齐，顺带把两处断言换成了它真正该守的东西：
 * <ul>
 *   <li>结果行已不存在，改成断言结果浮层 {@code parse_toast}（它 3 秒后自己消失，
 *       所以等待窗口不能开太久）</li>
 *   <li>「这一行不该看起来能点」原来的判据是 {@code TextView.isClickable()}。
 *       浮层整体没有点击监听，可点的是里面那个「详情」按钮，
 *       所以判据换成 {@code parse_toast_action} 的可见性——那条**用例的本意**（没配 Key 时
 *       哪儿也去不了）没变</li>
 * </ul>
 */
@RunWith(AndroidJUnit4.class)
public class UiSmokeTest {

    @Test
    public void mainActivityStartsWithItsComposer() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull("首页应有输入框", activity.findViewById(R.id.input));
                assertNotNull("首页应有发送按钮", activity.findViewById(R.id.btn_send));
                assertNotNull("首页应有截图入口", activity.findViewById(R.id.btn_pick_image));
                assertNotNull("首页应有账本入口", activity.findViewById(R.id.btn_ledger));
                assertNotNull("首页应有设置入口", activity.findViewById(R.id.btn_settings));

                // 空闲状态：发送可点，结果浮层与进度提示都不该露出来
                assertTrue("空闲时发送按钮应可点",
                        activity.findViewById(R.id.btn_send).isEnabled());
                assertEquals(View.GONE, activity.findViewById(R.id.parse_toast).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.parsing_hint).getVisibility());
            });
        }
    }

    // 语音输入已于 2026-10-07 整体移除（P3 再做，HANDOFF 决策 49），
    // 相关用例（voiceModeTogglesBetweenTypingAndSpeaking）与录音授权辅助方法一并删除。

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

    /**
     * 相邻两次结果<b>文案完全相同</b>时，第二次也必须弹。
     *
     * <p>这条钉的是一个真实出现过的 bug（`HANDOFF` §8-37）：去重键原先用
     * {@code summary + detail} 拼文案，而"两笔都免确认直落的账"文案是常量，
     * 于是第二笔被当成"同一条状态被重放"而静默。用户实测：「今天花了五块」→「今天花了六块」，
     * 第二句不弹浮层（而中间夹一句落点不同的账就会弹）。
     *
     * <p>用"空输入"造两次<b>必然同文案</b>的结果（两次都是「先写一句要记的账」）：
     * 好处是<b>不调模型、不花钱、也不依赖有没有配 Key</b>——这条用例要在任何环境下都跑得到，
     * 否则它挡不住的东西又会被静默放过。
     */
    @Test
    public void secondIdenticalResultStillShowsTheToast() throws InterruptedException {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.findViewById(R.id.btn_send).performClick());
            assertToast(scenario, "第一次发送就该弹出提示", true);

            // 等它自己收起（3 秒寿命 + 淡出），否则"第二次弹了"与"第一次还没消失"分不开
            Thread.sleep(4000L);
            assertToast(scenario, "浮层该按设计自动收起", false);

            scenario.onActivity(activity -> activity.findViewById(R.id.btn_send).performClick());
            assertToast(scenario, "文案相同的第二次结果也必须弹（不能被当成状态重放）", true);
        }
    }

    private static void assertToast(ActivityScenario<MainActivity> scenario, String message,
                                    boolean expectedVisible) {
        boolean[] visible = new boolean[1];
        scenario.onActivity(activity -> visible[0] =
                activity.findViewById(R.id.parse_toast).getVisibility() == View.VISIBLE);
        assertEquals(message, expectedVisible, visible[0]);
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
                View toast = activity.findViewById(R.id.parse_toast);
                TextView toastText = activity.findViewById(R.id.parse_toast_text);
                View toastAction = activity.findViewById(R.id.parse_toast_action);

                input.setText("今天打车 28");
                activity.findViewById(R.id.btn_send).performClick();

                // 没配 Key 时 ChatViewModel 是同步拒绝的（不提交后台任务），所以这里不用等
                assertEquals("没有 Key 时结果浮层必须露出来", View.VISIBLE, toast.getVisibility());
                String text = toastText.getText().toString();
                assertTrue("没有 Key 时应提示去配置，实际：" + text, text.contains("API Key"));
                assertEquals("没配 Key 时哪儿也去不了，浮层上不该出现「详情」按钮",
                        View.GONE, toastAction.getVisibility());
            });
        }
    }

    @Test
    public void homeParsesARealInputAndShowsAResultToast() throws InterruptedException {
        // 这一条会真的调用模型、真的花钱，所以只在配了 Key 时执行。
        // 它验的是端到端的一根链条：输入 → 解析 → 草稿 → 落账/待确认 → 结果浮层。
        // 其中任何一环断了界面都不会报错，只是那条浮层永远不出现——
        // 这种"静默失效"只有真跑一次才看得出来
        assumeTrue("没配 Key，跳过真实解析", hasApiKey());

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.input)).setText("我买游戏花了283.4");
                activity.findViewById(R.id.btn_send).performClick();
            });

            // 真实调用要等网络；最多等 90 秒（读超时本身是 120 秒，但正常几秒就回来）。
            // 轮询间隔取 250ms 而不是 1 秒：**浮层只活 3 秒**（MainActivity.TOAST_DURATION_MS），
            // 间隔太粗的话有可能会连着两次都落在它收起之后
            boolean shown = false;
            for (int attempt = 0; attempt < 360 && !shown; attempt++) {
                boolean[] state = new boolean[1];
                scenario.onActivity(activity -> {
                    View toast = activity.findViewById(R.id.parse_toast);
                    TextView toastText = activity.findViewById(R.id.parse_toast_text);
                    state[0] = toast.getVisibility() == View.VISIBLE
                            && !toastText.getText().toString().isEmpty();
                });
                shown = state[0];
                if (!shown) {
                    Thread.sleep(250L);
                }
            }
            assertTrue("解析完成后，结果浮层应当带着结论出现在输入栏上方", shown);
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
     * 设置页表单能起来。
     *
     * <p>2026-10-07 改版后，确认档位 / 大额阈值 / 分类方案 / API Key 都不再是 RadioGroup 与
     * EditText，而是"一行可点、点开弹选择器或输入框"，行里显示当前值。
     * 所以这里断言的是<b>行 + 它的值</b>——那才是用户能操作与能读到的两样东西。
     */
    @Test
    public void settingsActivityLoadsForm() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull("确认档位那一行", activity.findViewById(R.id.row_confirm_mode));
                assertNotNull("确认档位的当前值", activity.findViewById(R.id.value_confirm_mode));
                assertNotNull("大额阈值那一行", activity.findViewById(R.id.row_threshold));
                assertNotNull("大额阈值的当前值", activity.findViewById(R.id.value_threshold));
                // 分类方案 2026-10-11 从设置页移除：新建账本时定死，之后不能改，
                // 所以这里不再有 row_scheme / value_scheme 可断言
                assertNotNull("API Key 那一行", activity.findViewById(R.id.row_api_key));
                assertNotNull("API Key 的当前值", activity.findViewById(R.id.value_api_key));
                // 2026-10-11：底部「保存」按钮取消（改成每个选项在自己的对话框里确定），
                // 所以这里不再验 btn_save，改验新增的「账本」分组那一行
                assertNotNull("账本那一行", activity.findViewById(R.id.row_ledger));
                assertNotNull("账本的当前值", activity.findViewById(R.id.value_ledger));
                assertNotNull("账本备份那一组", activity.findViewById(R.id.row_export));
                assertNotNull(activity.findViewById(R.id.row_import));
            });
        }
    }

    /**
     * 反馈表单能弹出来（2026-10-08）。
     *
     * <p>只验"点一下不崩"：表单里的控件 id 一旦写错，崩溃发生在<b>用户按下发送之后</b>，
     * 而那正是最不该出问题的一刻。对话框里的控件用 {@code findViewById(activity)} 拿不到
     * （它们不在 Activity 的视图树里），所以这里只能验到"弹出来了"，
     * 更深的交互留给真机手验。
     */
    @Test
    public void feedbackFormOpensFromSettings() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity ->
                    activity.findViewById(R.id.row_feedback).performClick());
        }
    }

    /**
     * 账本选择页能起来（2026-10-11 多账本）。
     *
     * <p>它要读账本列表 + 每个账本的分类树，是新增页里依赖最多的一页，
     * 光"能编译"不足以保证起得来。
     */
    @Test
    public void ledgerPickerActivityStarts() {
        try (ActivityScenario<LedgerPickerActivity> scenario =
                     ActivityScenario.launch(LedgerPickerActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.btn_new_ledger));
                assertNotNull(activity.findViewById(R.id.ledger_cards));
            });
        }
    }

    /**
     * 「新建账本」弹框能弹出来（2026-10-11）。
     *
     * <p>自定义布局的 {@code inflate} 是这类弹框最容易挂的地方（id 写错就崩），
     * 所以这里至少验一次"点一下就弹、不崩"。
     */
    @Test
    public void newLedgerDialogOpens() {
        try (ActivityScenario<LedgerPickerActivity> scenario =
                     ActivityScenario.launch(LedgerPickerActivity.class)) {
            scenario.onActivity(activity ->
                    activity.findViewById(R.id.btn_new_ledger).performClick());
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
                // 改版后顶部合计拆成了支出 / 收入两个（原来是单个 summary）
                assertNotNull(activity.findViewById(R.id.summary_expense));
                assertNotNull(activity.findViewById(R.id.summary_income));
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
            assertTrue("原话要真的填进视图，展开时才看得到", rawShown);

            scenario.onActivity(activity -> {
                // 整块折叠（P3 实机反馈）：截图入口的原文可能是一整页转写文本，
                // 展开着会把四个字段区和操作按钮顶到屏幕外
                assertEquals("「输入解析」默认整块收起",
                        View.GONE, activity.findViewById(R.id.parse_panel).getVisibility());
                activity.findViewById(R.id.btn_toggle_parse).performClick();
                assertEquals("点一下才展开",
                        View.VISIBLE, activity.findViewById(R.id.parse_panel).getVisibility());
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
