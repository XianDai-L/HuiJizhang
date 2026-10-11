package com.wisebook.app.ui.ledger;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wisebook.app.R;
import com.wisebook.app.WiseBookApp;
import com.wisebook.app.data.DraftRepository;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.data.local.entity.LedgerEntity;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.ui.DraftFormatter;
import com.wisebook.app.ui.EdgeToEdgeInsets;
import com.wisebook.app.ui.ImageInputLoader;
import com.wisebook.app.ui.chat.ChatViewModel;
import com.wisebook.app.ui.confirm.ConfirmActivity;
import com.wisebook.app.ui.pending.PendingDraftsActivity;
import com.wisebook.app.ui.report.ReportActivity;
import com.wisebook.app.ui.settings.LedgerPickerActivity;
import com.wisebook.app.ui.settings.SettingsActivity;
import com.wisebook.app.ui.widget.MaxHeightRecyclerView;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 首页 = 记账工作台。
 *
 * <p>它从"导航页"变成了"工作台"（2026-10-02，见 HANDOFF 决策 33）：
 * 底部那条输入栏是主入口，说完一句话就在<b>同一页</b>看到结果，不再跳一次对话页。
 *
 * <p>2026-10-07 界面重排后，版面自上而下是：
 * 标题行 → 本月概览条 → 三个图标入口 → 最近账目（卡片内可滚）。顺序是刻意定的：
 * <b>先给"去哪"（入口），再给"刚发生了什么"（账目）</b>。
 *
 * <p>其中「本月花了多少」这件事账本页<b>已经在做了</b>，所以顶部概览复用
 * {@link LedgerViewModel}——同一份查询、同一套口径（转账计入支出），
 * 不必再写一遍聚合。两处若各算各的，迟早出现"首页说 320、账本说 300"。
 *
 * <p><b>但卡片里的「最近账目」不看月份</b>（2026-10-08 用户要求）：它回答的是
 * "最近发生了什么"，上个月的账也该看得见，滚到底还会再要一页——
 * 所以它有自己的 {@link RecentEntriesViewModel}。顶部概览仍是<b>本月</b>口径：
 * 那回答的是另一个问题（"这个月花超了吗"）。
 *
 * <p>界面层只做三件事：把输入（文字或截图）交给 {@link ChatViewModel}、把状态渲染出来、
 * 把选中的图读成字节交给它。解析、落库、档位判定全在 ViewModel 与仓储层里。
 */
public class MainActivity extends AppCompatActivity {

    /** 提升成字段是为了选图：读字节要提交到应用唯一的数据库线程，回调里要用到它 */
    private WiseBookApp app;
    private ChatViewModel viewModel;
    private LedgerViewModel ledgerViewModel;
    /** 卡片里的「最近账目」：跨月 + 滚到底再要一页 */
    private RecentEntriesViewModel recentEntries;
    private EntryAdapter recentAdapter;

    private EditText input;
    private TextView parsingHint;
    private ImageButton pickImageButton;
    private ImageButton sendButton;
    private TextView heroExpense;
    private TextView heroIncome;
    private TextView heroBalance;
    private TextView recentCount;
    private TextView recentTotal;
    private TextView recentEmpty;
    private TextView recentAll;
    private TextView ledgerCount;
    private TextView reportCount;
    private TextView pendingCount;
    /** 顶栏的「当前账本：X」，点它进账本选择（2026-10-11） */
    private TextView ledgerBadge;
    private MaxHeightRecyclerView recentList;
    private View composer;

    /**
     * 上一次已经弹过的那条状态。
     *
     * <p>LiveData 在旋转屏幕后会把最后一个状态重放回来，不加这个判断的话，
     * 转一次屏提示就再弹一次。**按对象身份比较，不按文案**——理由见 {@link #showResult}。
     */
    private ChatViewModel.UiState lastShownState;

    /** 处理完成的浮动提示（见 {@code view_parse_toast}） */
    private View parseToast;
    private TextView parseToastText;
    private TextView parseToastAction;
    private View parseToastBar;
    private ObjectAnimator toastBarAnim;

    /** 到点自动收起。留成字段是为了能 removeCallbacks 取消上一次的 */
    private final Runnable hideToastTask = this::hideParseToast;

    /**
     * 选图。用系统照片选择器（D2 §7 已指定这条路），<b>零权限</b>：
     * 不需要 {@code READ_MEDIA_IMAGES}，也就没有"一个记账应用为什么要读我整个相册"的疑问
     * ——应用拿到的只是用户明确选中的那一个文件的读权限。
     */
    private final ActivityResultLauncher<PickVisualMediaRequest> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    loadAndSubmit(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        EdgeToEdgeInsets.apply(findViewById(R.id.main));

        app = WiseBookApp.from(this);
        DraftRepository draftRepository = app.draftRepository();

        viewModel = new ViewModelProvider(this, new ChatViewModel.Factory(
                draftRepository,
                app.databaseExecutor(),
                app.llmRuntime()))
                .get(ChatViewModel.class);

        // 顶部概览（本月支出 / 收入 / 结余）与两个快捷入口的计数：账本页已经在算同一份数据，
        // 直接复用它的 ViewModel，免得两处口径打架
        ledgerViewModel = new ViewModelProvider(this, new LedgerViewModel.Factory(
                app.entryRepository(),
                app.categoryRepository(),
                app.databaseExecutor(),
                app.currentLedgerId(),
                ZoneId.systemDefault()))
                .get(LedgerViewModel.class);

        // 卡片里的账目跨月、按需加载，与"本月概览"是两个不同的问题，所以另有一个 ViewModel
        recentEntries = new ViewModelProvider(this, new RecentEntriesViewModel.Factory(
                app.entryRepository()))
                .get(RecentEntriesViewModel.class);

        bindViews();
        bindActions();
        observeDrafts(draftRepository);
        observeLedger();

        // 旋转屏幕后 LiveData 会把最后一次状态重放回来，提示不会因此重复弹（见 lastShownState）
        viewModel.state().observe(this, this::render);
    }

    // ------------------------------------------------------------------ 绑定

    private void bindViews() {
        input = findViewById(R.id.input);
        parsingHint = findViewById(R.id.parsing_hint);
        pickImageButton = findViewById(R.id.btn_pick_image);
        sendButton = findViewById(R.id.btn_send);
        composer = findViewById(R.id.composer);
        parseToast = findViewById(R.id.parse_toast);
        parseToastText = findViewById(R.id.parse_toast_text);
        parseToastAction = findViewById(R.id.parse_toast_action);
        parseToastBar = findViewById(R.id.parse_toast_bar);

        heroExpense = findViewById(R.id.hero_expense);
        heroIncome = findViewById(R.id.hero_income);
        heroBalance = findViewById(R.id.hero_balance);

        recentList = findViewById(R.id.recent_list);
        recentEmpty = findViewById(R.id.recent_empty);
        recentCount = findViewById(R.id.recent_count);
        recentTotal = findViewById(R.id.recent_total);
        recentAll = findViewById(R.id.btn_ledger_all);

        ledgerCount = findViewById(R.id.qbtn_ledger_count);
        reportCount = findViewById(R.id.qbtn_report_count);
        pendingCount = findViewById(R.id.qbtn_pending_count);
        ledgerBadge = findViewById(R.id.ledger_badge);

        // 首页的账目行与账本页共用同一个适配器：两处的行长得一样，
        // 用户从首页点进账本时不会有"换了一套列表"的割裂感
        recentAdapter = new EntryAdapter(entry ->
                startActivity(EntryDetailActivity.intentFor(this, entry.entryId)));
        // 上限约 5 行：只有两三笔时卡片随内容收紧，账目多时到此为止、内部滚
        recentList.setMaxHeightPx((int) (300 * getResources().getDisplayMetrics().density));
        recentList.setLayoutManager(new LinearLayoutManager(this));
        recentList.setAdapter(recentAdapter);
    }

    private void bindActions() {
        pickImageButton.setOnClickListener(view -> pickImage());
        sendButton.setOnClickListener(view -> submit());
        // 输入法如果给的是「发送」键，也该能发出去——现在没有「解析」按钮了，
        // 让回车什么都不做会让人以为输入框坏了
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit();
                return true;
            }
            return false;
        });

        findViewById(R.id.btn_ledger).setOnClickListener(view -> openLedger());
        // 区块标题右侧的「全部 N 笔 →」是同一个去处，给了它就不必去点图标
        recentAll.setOnClickListener(view -> openLedger());
        findViewById(R.id.btn_report).setOnClickListener(
                view -> startActivity(new Intent(this, ReportActivity.class)));
        findViewById(R.id.btn_pending).setOnClickListener(
                view -> startActivity(new Intent(this, PendingDraftsActivity.class)));
        findViewById(R.id.btn_settings).setOnClickListener(
                view -> startActivity(new Intent(this, SettingsActivity.class)));
        // 顶栏的当前账本：点一下像翻存档一样切换
        ledgerBadge.setOnClickListener(
                view -> startActivity(new Intent(this, LedgerPickerActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从账本选择或设置页回来时，当前账本名可能已经变了
        loadLedgerBadge();
    }

    /**
     * 顶栏那行「当前账本：X」。
     *
     * <p>名字要读库，异步取回来再填。取不到（理论上不该发生）时藏起来——
     * 留一行「当前账本：」比不显示更容易让人误会。
     */
    private void loadLedgerBadge() {
        app.databaseExecutor().execute(() -> {
            LedgerEntity ledger = app.ledgerRepository().find(app.currentLedgerId());
            String name = ledger == null ? null : ledger.name;
            runOnUiThread(() -> {
                if (name == null || name.isEmpty()) {
                    ledgerBadge.setVisibility(View.GONE);
                } else {
                    ledgerBadge.setVisibility(View.VISIBLE);
                    ledgerBadge.setText(getString(R.string.home_ledger_badge, name));
                }
            });
        });
    }

    private void openLedger() {
        startActivity(new Intent(this, LedgerActivity.class));
    }

    // ------------------------------------------------------------------ 观察数据

    /**
     * 待处理笔数走 LiveData 观察数据库：从确认流程回来、或者自动落账之后，
     * 数字会自己刷新，不需要在 {@code onResume} 里手动再查一遍。
     */
    private void observeDrafts(DraftRepository draftRepository) {
        draftRepository.observeOpenDrafts().observe(this, drafts -> {
            int count = drafts == null ? 0 : drafts.size();
            pendingCount.setText(getString(R.string.home_pending_count, count));
        });
    }

    private void observeLedger() {
        // ① 本月口径：概览条 + 两个入口的计数（账本页算的是同一份数据）
        ledgerViewModel.entries().observe(this, this::renderMonthlySummary);
        // 分类树只用来把 categoryId 渲染成可读路径，加载完通知适配器重绘一次
        ledgerViewModel.tree().observe(this, recentAdapter::setTree);
        ledgerViewModel.load();

        // ② 跨月口径：卡片里的最近账目（滚到底会再要一页）
        recentEntries.entries().observe(this, this::renderRecentEntries);
        bindRecentPaging();
    }

    /**
     * 滚到底再要一页。
     *
     * <p>只认"用户在往下滚、而且已经到底"这一个事实；"库里还有没有更多"由
     * {@link RecentEntriesViewModel#loadMore()} 判断——界面不该去猜数据还有没有。
     */
    private void bindRecentPaging() {
        recentList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (dy <= 0) {
                    return;
                }
                if (!view.canScrollVertically(1)) {
                    recentEntries.loadMore();
                }
            }
        });
    }

    /** 顶部概览条与两个入口的计数：<b>本月</b>口径 */
    private void renderMonthlySummary(List<EntryEntity> entries) {
        int count = entries == null ? 0 : entries.size();

        long expense = 0L;
        long income = 0L;
        Set<Long> categories = new HashSet<>();
        if (entries != null) {
            for (EntryEntity entry : entries) {
                // 2026-10-08 改：转账也计入支出。
                // 原先 TRANSFER 两个分支都不进，于是首页「本月支出」总比报表页少一截
                // ——报表（MonthlyReport）默认就是把转账计入支出的，两页口径必须一致，
                // 否则同一个月的数在两个页面对不上，用户只会觉得有一边算错了。
                if (entry.direction == Direction.EXPENSE || entry.direction == Direction.TRANSFER) {
                    expense += entry.amountCents;
                } else if (entry.direction == Direction.INCOME) {
                    income += entry.amountCents;
                }
                // 用一级分类去重：报表入口那句「分类 N 项」指的是一级分类，
                // 与报表页「按一级分类聚合」的口径保持一致
                categories.add(entry.rootCategoryId);
            }
        }

        // 概览条。一笔都没有时给「—」而不是「¥0.00」：
        // 0 和「这个月还没开始记」是两件事，别让用户以为把钱花光了
        if (count == 0) {
            heroExpense.setText(R.string.home_hero_empty);
            heroIncome.setText(R.string.home_hero_empty);
            heroBalance.setText(R.string.home_hero_empty);
        } else {
            heroExpense.setText("¥" + DraftFormatter.groupedYuan(expense));
            heroIncome.setText("+" + DraftFormatter.groupedYuan(income));
            heroBalance.setText(DraftFormatter.groupedYuan(income - expense));
        }

        ledgerCount.setText(getString(R.string.home_ledger_count, count));
        reportCount.setText(getString(R.string.home_report_count, categories.size()));
        // 这个链接去的是账本页，而账本页默认就是本月视图——所以这里写"本月 N 笔"是准的，
        // 不能写"共 N 笔"（卡片里的列表现在是跨月的，两者口径不同）
        recentAll.setText(getString(R.string.home_recent_all, count));
    }

    /**
     * 卡片里的「最近账目」：<b>跨月</b>，条数随滚动增加。
     *
     * <p>底栏两个数字都只说这张卡片里的事：「已显示 N 笔」+「支出 X 元」。
     * 早先它们取的是本月口径，跨月之后就会和下面的行对不上——
     * 同一块地方的数字与明细必须同源，否则用户只会觉得有一边错了。
     */
    private void renderRecentEntries(List<EntryEntity> entries) {
        int count = entries == null ? 0 : entries.size();

        long expense = 0L;
        if (entries != null) {
            for (EntryEntity entry : entries) {
                if (entry.direction == Direction.EXPENSE || entry.direction == Direction.TRANSFER) {
                    expense += entry.amountCents;
                }
            }
        }

        recentCount.setText(getString(R.string.home_recent_count, count));
        recentTotal.setText("支出 " + DraftFormatter.groupedAmount(expense));
        recentAdapter.submitList(entries);

        recentList.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        recentEmpty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ 提交

    /**
     * 把输入框里的话交出去。
     *
     * <p><b>发送即清空，失败也清空</b>（HANDOFF 决策 34）。失败也清空是有意的：
     * 留着原文会让人以为"再点一次就是重试"，而重试需要的是<b>改一下再说</b>
     * ——原话就在提示里，想照着改，从提示里能看见它。
     */
    private void submit() {
        String text = input.getText().toString();
        input.setText("");
        viewModel.submit(text);
    }

    // ------------------------------------------------------------------ 截图

    /**
     * 打开系统照片选择器。
     *
     * <p>限制成「只选图片」：用户不会在这里误选到视频或文档，少一类需要解释的失败。
     */
    private void pickImage() {
        pickImageLauncher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    /**
     * 读图 → 交给 ViewModel。
     *
     * <p>读字节在后台（{@link ImageInputLoader} 内部），这里只接回调——
     * Activity 里仍然一行线程代码都没有，与文字入口保持一致。
     *
     * <p>选完图会顺手收起键盘：截图记账时用户不是在看输入框，留着键盘会挡住刚弹出的提示。
     */
    private void loadAndSubmit(Uri uri) {
        hideKeyboard();
        ImageInputLoader.loadAsync(getContentResolver(), uri, app.databaseExecutor(),
                image -> viewModel.submitImage(image.bytes, image.mimeType, image.displayName),
                this::showTip);
    }

    // ------------------------------------------------------------------ 渲染

    private void render(ChatViewModel.UiState state) {
        parsingHint.setVisibility(state.parsing ? View.VISIBLE : View.GONE);
        // 解析中只锁「发送」和「相机」，不锁输入框：上一句已经交出去了，
        // 用户完全可以趁着等结果把下一句先打出来。
        // 相机一起锁是因为：上一次选图还在解析时又选一张，会各自落一批草稿，
        // 而用户看到的是"我明明只选了一次"
        sendButton.setEnabled(!state.parsing);
        pickImageButton.setEnabled(!state.parsing);

        if (!state.hasResult()) {
            return;
        }
        showResult(state);
    }

    /**
     * 把解析结果弹成浮层（不是像以前那样在输入栏上方留一行常驻文字）。
     *
     * <p>换成浮层有两个实际好处：一是它<b>不占版面</b>，首页因此能腾出整块高度给账目；
     * 二是它<b>自己会消失</b>，用户不需要手动清理上一次的结果。
     *
     * <p><b>去重按对象身份，不按文案</b>（2026-10-08 修，HANDOFF §8-37）：
     * 原先的键是 {@code summary + "|" + detail} 拼出来的文案，而"两笔都免确认直落的账"
     * 文案是<b>常量</b>（summary 恒为「已自动记账」，detail 里也没有金额、商户、时间），
     * 于是第二笔被误判成"同一条状态被重放"而静默——用户实测：
     * 「今天花了五块」→「今天花了六块」，第二句不弹；而中间夹一句落点不同的账就会弹。
     *
     * <p>换成身份比较之后两件事天然分开：转屏重放拿到的是<b>同一个对象</b>（挡住），
     * 每次解析结果都是 {@code UiState.result(...)} <b>新建的对象</b>
     * （哪怕文案一字不差也照样弹）。这条判断该问的是"这是不是同一个事件"，
     * 而不是"这两句话长得像不像"——后者会把两笔长得一样的账也一起吞掉。
     */
    private void showResult(ChatViewModel.UiState state) {
        if (state == lastShownState) {
            return;
        }
        lastShownState = state;

        StringBuilder text = new StringBuilder(state.summary == null ? "" : state.summary);
        if (state.detail != null && !state.detail.isEmpty()) {
            text.append('\n').append(state.detail);
        }
        showSnack(text.toString(), state.canOpen() ? state : null);
    }

    /** 这条提示在屏幕上停留多久。进度条走完 = 它消失，两者用的是同一个值 */
    private static final long TOAST_DURATION_MS = 3000L;

    /**
     * 弹一条处理完成的提示。
     *
     * <p>从底部滑入 20dp 再淡入，不是从屏幕外飞进来：这条例行提示要传达的是
     * 「刚才那句话已经记好了」，动作太大反而像出了什么事。
     *
     * <p>连续说两句时，后来的直接接管同一条浮层（不排队）：
     * 用户关心的是最新一条，把两条依次播完只会让第二条姗姗来迟。
     *
     * @param state 为 null 表示纯提示（例如读图失败），没有可跳转的去处
     */
    private void showSnack(String message, ChatViewModel.UiState state) {
        parseToastText.setText(message);

        boolean openable = state != null;
        parseToastAction.setVisibility(openable ? View.VISIBLE : View.GONE);
        if (openable) {
            parseToastAction.setOnClickListener(view -> {
                hideParseToast();
                openResult(state);
            });
        }

        // 进度条就是这条提示的剩余寿命：起点回到满格，再匀速走完
        if (toastBarAnim != null) {
            toastBarAnim.cancel();
        }
        parseToastBar.setPivotX(0f);
        parseToastBar.setScaleX(1f);
        toastBarAnim = ObjectAnimator.ofFloat(parseToastBar, "scaleX", 1f, 0f);
        toastBarAnim.setDuration(TOAST_DURATION_MS);
        toastBarAnim.start();

        parseToast.setVisibility(View.VISIBLE);
        parseToast.setAlpha(0f);
        parseToast.setTranslationY(dp(20));
        parseToast.animate().alpha(1f).translationY(0f).setDuration(260).start();

        // 先撤掉上一次的定时，否则连发两句时前一句的定时会把后一句收掉
        parseToast.removeCallbacks(hideToastTask);
        parseToast.postDelayed(hideToastTask, TOAST_DURATION_MS);
    }

    private void hideParseToast() {
        if (parseToast.getVisibility() != View.VISIBLE) {
            return;
        }
        parseToast.animate()
                .alpha(0f)
                .translationY(dp(12))
                .setDuration(200)
                .withEndAction(() -> parseToast.setVisibility(View.GONE))
                .start();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** 提示的去处：已经入账的看账目，等核对的进确认页。两者都没有时它不带动作按钮 */
    private void openResult(ChatViewModel.UiState state) {
        if (state.openPendingList) {
            // 一次提交拆出多笔（文字一句话或一张图）：把整批摆在一起看，
            // 比只跳进其中一笔更符合"我刚发了一句/一张账单"的预期
            startActivity(new Intent(this, PendingDraftsActivity.class));
        } else if (state.entryId > 0L) {
            startActivity(EntryDetailActivity.intentFor(this, state.entryId));
        } else if (state.draftId > 0L) {
            startActivity(ConfirmActivity.intentFor(this, state.draftId));
        }
    }

    private void showTip(String message) {
        showSnack(message, null);
    }

    // ------------------------------------------------------------------ 杂项

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) {
            manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

}
