package com.wisebook.app.ui.ledger;

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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.R;
import com.wisebook.app.WiseBookApp;
import com.wisebook.app.data.DraftRepository;
import com.wisebook.app.ui.EdgeToEdgeInsets;
import com.wisebook.app.ui.ImageInputLoader;
import com.wisebook.app.ui.chat.ChatViewModel;
import com.wisebook.app.ui.confirm.ConfirmActivity;
import com.wisebook.app.ui.pending.PendingDraftsActivity;
import com.wisebook.app.ui.report.ReportActivity;
import com.wisebook.app.ui.settings.SettingsActivity;

/**
 * 首页 = 记账工作台。
 *
 * <p>它从"导航页"变成了"工作台"（2026-10-02，见 HANDOFF 决策 33）：
 * 底部那条输入栏是 P1 唯一的主入口，说完一句话就在<b>同一页</b>看到结果，
 * 不再跳一次对话页。上面的账本 / 报表 / 设置仍然是入口，只是退到了上方。
 *
 * <p>界面层只做三件事：把输入（文字或截图）交给 {@link ChatViewModel}、把状态渲染出来、
 * 把选中的图读成字节交给它。解析、落库、档位判定全在 ViewModel 与仓储层里。
 *
 * <p>待处理数量走 LiveData 观察数据库：从确认流程回来、或者自动落账之后，
 * 数字会自己刷新，不需要在 {@code onResume} 里手动再查一遍。
 */
public class MainActivity extends AppCompatActivity {

    /** 提升成字段是为了选图：读字节要提交到应用唯一的数据库线程，回调里要用到它 */
    private WiseBookApp app;
    private ChatViewModel viewModel;

    private TextView openDraftCount;
    private EditText input;
    private TextView parseResult;
    private TextView parsingHint;
    private ImageButton pickImageButton;
    private ImageButton sendButton;

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

        openDraftCount = findViewById(R.id.open_draft_count);
        input = findViewById(R.id.input);
        parseResult = findViewById(R.id.parse_result);
        parsingHint = findViewById(R.id.parsing_hint);
        pickImageButton = findViewById(R.id.btn_pick_image);
        sendButton = findViewById(R.id.btn_send);

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
        findViewById(R.id.btn_ledger).setOnClickListener(
                view -> startActivity(new Intent(this, LedgerActivity.class)));
        findViewById(R.id.btn_report).setOnClickListener(
                view -> startActivity(new Intent(this, ReportActivity.class)));
        findViewById(R.id.btn_settings).setOnClickListener(
                view -> startActivity(new Intent(this, SettingsActivity.class)));
        // 待处理那一行本身就是入口：数量非 0 才可点，0 笔时点进去只会是一页空白
        openDraftCount.setOnClickListener(view -> {
            if (openDraftCount.isEnabled()) {
                startActivity(new Intent(this, PendingDraftsActivity.class));
            }
        });

        draftRepository.observeOpenDrafts().observe(this, drafts -> {
            int count = drafts == null ? 0 : drafts.size();
            openDraftCount.setText(count == 0
                    ? getString(R.string.home_open_drafts, 0)
                    : getString(R.string.home_open_drafts, count) + "（点这里处理）");
            openDraftCount.setEnabled(count > 0);
        });

        // 旋转屏幕后 LiveData 会把最后一次状态重放回来，结果行自动恢复
        viewModel.state().observe(this, this::render);
    }

    // ------------------------------------------------------------------ 提交

    /**
     * 把输入框里的话交出去。
     *
     * <p><b>发送即清空，失败也清空</b>（HANDOFF 决策 34）。失败也清空是有意的：
     * 留着原文会让人以为"再点一次就是重试"，而重试需要的是<b>改一下再说</b>
     * ——原话就摆在结果行下面，想照着改，从结果里能看见它。
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
     * <p>选完图会顺手收起键盘：截图记账时用户不是在看输入框，
     * 留着键盘会挡住刚出现的解析结果行。
     */
    private void loadAndSubmit(Uri uri) {
        hideKeyboard();
        ImageInputLoader.loadAsync(getContentResolver(), uri, app.databaseExecutor(),
                image -> viewModel.submitImage(image.bytes, image.mimeType, image.displayName),
                this::showTip);
    }

    private void render(ChatViewModel.UiState state) {
        parsingHint.setVisibility(state.parsing ? View.VISIBLE : View.GONE);
        // 解析中只锁「发送」这一个按钮，不锁输入框：上一句已经交出去了，
        // 用户完全可以趁着等结果把下一句先打出来。
        // （原来锁整个输入框会顺手把键盘收掉，等结果回来还得再点一次输入框）
        sendButton.setEnabled(!state.parsing);
        // 相机也一起锁上：上一次选图还在解析时又选一张，会各自落一批草稿，
        // 而用户看到的是"我明明只选了一次"
        pickImageButton.setEnabled(!state.parsing);

        if (!state.hasResult()) {
            parseResult.setVisibility(View.GONE);
            return;
        }

        parseResult.setVisibility(View.VISIBLE);
        parseResult.setText(resultText(state));
        parseResult.setTextColor(ContextCompat.getColor(this, state.error
                ? android.R.color.holo_red_dark
                : android.R.color.darker_gray));
        parseResult.setClickable(state.canOpen());
        parseResult.setOnClickListener(state.canOpen() ? view -> openResult(state) : null);
    }

    private String resultText(ChatViewModel.UiState state) {
        StringBuilder text = new StringBuilder(state.summary);
        if (state.detail != null && !state.detail.isEmpty()) {
            text.append('\n').append(state.detail);
        }
        if (state.canOpen()) {
            // 没有这行字的话，"结果行可以点"这件事没有任何提示
            text.append('\n').append(getString(R.string.home_result_open_hint));
        }
        return text.toString();
    }

    /** 结果行的去处：已经入账的看账目，等核对的进确认页。两者都没有时它不可点 */
    private void openResult(ChatViewModel.UiState state) {
        if (state.openPendingList) {
            // 一图多笔：把整批摆在一起看，比只跳进其中一笔更符合"我刚发了张账单"的预期
            startActivity(new Intent(this, PendingDraftsActivity.class));
        } else if (state.entryId > 0L) {
            startActivity(EntryDetailActivity.intentFor(this, state.entryId));
        } else if (state.draftId > 0L) {
            startActivity(ConfirmActivity.intentFor(this, state.draftId));
        }
    }

    private void showTip(String message) {
        parseResult.setVisibility(View.VISIBLE);
        parseResult.setText(message);
        parseResult.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
        parseResult.setClickable(false);
        parseResult.setOnClickListener(null);
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
