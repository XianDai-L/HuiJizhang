package com.wisebook.app.ui.ledger;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.R;
import com.wisebook.app.WiseBookApp;
import com.wisebook.app.data.DraftRepository;
import com.wisebook.app.ui.EdgeToEdgeInsets;
import com.wisebook.app.ui.chat.ChatViewModel;
import com.wisebook.app.ui.confirm.ConfirmActivity;
import com.wisebook.app.ui.pending.PendingDraftsActivity;
import com.wisebook.app.ui.report.ReportActivity;
import com.wisebook.app.ui.settings.SettingsActivity;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 首页 = 记账工作台。
 *
 * <p>它从"导航页"变成了"工作台"（2026-10-02，见 HANDOFF 决策 33）：
 * 底部那条输入栏是 P1 唯一的主入口，说完一句话就在<b>同一页</b>看到结果，
 * 不再跳一次对话页。上面的账本 / 报表 / 设置仍然是入口，只是退到了上方。
 *
 * <p>界面层只做三件事：把输入交给 {@link ChatViewModel}、把状态渲染出来、
 * 把语音识别的文字填回输入框。解析、落库、档位判定全在 ViewModel 与仓储层里。
 *
 * <p>待处理数量走 LiveData 观察数据库：从确认流程回来、或者自动落账之后，
 * 数字会自己刷新，不需要在 {@code onResume} 里手动再查一遍。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private ChatViewModel viewModel;

    private TextView openDraftCount;
    private EditText input;
    private TextView speak;
    private TextView parseResult;
    private TextView parsingHint;
    private ImageButton voiceModeButton;
    private ImageButton sendButton;

    /** 语音模式：输入框换成「点一下开始说话」 */
    private boolean voiceMode;

    private SpeechRecognizer recognizer;
    /** 正在听。用于忽略"回来时已经不在听"的迟到回调 */
    private boolean listening;

    /**
     * 录音权限的申请。用 ActivityResult API 而不是 {@code onRequestPermissionsResult}：
     * 后者靠 requestCode 做分支，加第二个权限时就要小心编码碰撞。
     */
    private final ActivityResultLauncher<String> recordPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    enterVoiceMode();
                } else {
                    showTip(getString(R.string.home_voice_permission_denied));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        EdgeToEdgeInsets.apply(findViewById(R.id.main));

        WiseBookApp app = WiseBookApp.from(this);
        DraftRepository draftRepository = app.draftRepository();

        viewModel = new ViewModelProvider(this, new ChatViewModel.Factory(
                draftRepository,
                app.databaseExecutor(),
                app.llmRuntime()))
                .get(ChatViewModel.class);

        openDraftCount = findViewById(R.id.open_draft_count);
        input = findViewById(R.id.input);
        speak = findViewById(R.id.speak);
        parseResult = findViewById(R.id.parse_result);
        parsingHint = findViewById(R.id.parsing_hint);
        voiceModeButton = findViewById(R.id.btn_voice_mode);
        sendButton = findViewById(R.id.btn_send);

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
        voiceModeButton.setOnClickListener(view -> toggleVoiceMode());
        speak.setOnClickListener(view -> startListening());

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

    private void render(ChatViewModel.UiState state) {
        parsingHint.setVisibility(state.parsing ? View.VISIBLE : View.GONE);
        // 解析中只锁「发送」这一个按钮，不锁输入框：上一句已经交出去了，
        // 用户完全可以趁着等结果把下一句先打出来。
        // （原来锁整个输入框会顺手把键盘收掉，等结果回来还得再点一次输入框）
        sendButton.setEnabled(!state.parsing);
        speak.setEnabled(!state.parsing && !listening);

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
        if (state.entryId > 0L) {
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

    // ------------------------------------------------------------------ 语音

    private void toggleVoiceMode() {
        if (voiceMode) {
            enterTextMode();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // 这台设备连识别服务都没有，切过去只会让人对着麦克风说话而没有任何反应
            showTip(getString(R.string.home_voice_unavailable));
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            enterVoiceMode();
        } else {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void enterVoiceMode() {
        voiceMode = true;
        input.setVisibility(View.GONE);
        speak.setVisibility(View.VISIBLE);
        speak.setText(R.string.home_speak_hint);
        voiceModeButton.setImageResource(R.drawable.ic_keyboard);
        voiceModeButton.setContentDescription(getString(R.string.home_switch_to_text));
        hideKeyboard();
    }

    private void enterTextMode() {
        voiceMode = false;
        stopListening();
        input.setVisibility(View.VISIBLE);
        speak.setVisibility(View.GONE);
        voiceModeButton.setImageResource(R.drawable.ic_mic);
        voiceModeButton.setContentDescription(getString(R.string.home_switch_to_voice));
    }

    /**
     * 开始听。
     *
     * <p>用系统自带的 {@link SpeechRecognizer}（识别文字直接回填输入框），
     * 而不是自己录一段音频去调接口：识别在设备上完成、不走我们的 Key，
     * 也就不会占用模型额度；而且识别结果是<b>文字</b>，正好复用整条文本解析管线
     * ——语音接进来没有新增任何一条解析路径。
     */
    private void startListening() {
        if (listening) {
            return;
        }
        if (recognizer == null && !ensureRecognizer()) {
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);

        listening = true;
        speak.setText(R.string.home_listening);
        speak.setEnabled(false);
        recognizer.startListening(intent);
    }

    private boolean ensureRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            return false;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onResults(Bundle results) {
                applySpeech(results);
            }

            @Override
            public void onError(int error) {
                Log.w(TAG, "语音识别失败，错误码 " + error);
                finishListening();
                showTip(getString(R.string.home_voice_failed));
            }

            // 下面这些回调不影响结果，只用来把"正在听"这件事收尾
            @Override
            public void onReadyForSpeech(Bundle params) {
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
        return true;
    }

    /**
     * 识别结果落进输入框，<b>然后切回文字模式</b>。
     *
     * <p>刻意不自动提交：识别有错字的风险（同音字、数字尤其），
     * 让它先躺进输入框，用户扫一眼就能改——这是最便宜的一道纠错，
     * 比事后在确认页上纠便宜得多。
     */
    private void applySpeech(Bundle results) {
        finishListening();
        ArrayList<String> texts = results == null ? null
                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String text = texts == null || texts.isEmpty() ? "" : texts.get(0);
        if (TextUtils.isEmpty(text)) {
            showTip(getString(R.string.home_voice_failed));
            return;
        }
        input.setText(text);
        input.setSelection(text.length());
        enterTextMode();
        input.requestFocus();
    }

    private void finishListening() {
        listening = false;
        speak.setText(R.string.home_speak_hint);
        speak.setEnabled(true);
    }

    private void stopListening() {
        if (recognizer != null && listening) {
            recognizer.cancel();
        }
        finishListening();
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

    @Override
    protected void onDestroy() {
        if (recognizer != null) {
            // 不释放会漏一个 binder 连接，而且下次进来再 create 会报错
            recognizer.destroy();
            recognizer = null;
        }
        super.onDestroy();
    }
}
