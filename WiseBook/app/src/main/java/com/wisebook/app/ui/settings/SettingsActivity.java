package com.wisebook.app.ui.settings;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.R;
import com.wisebook.app.WiseBookApp;
import com.wisebook.app.ui.EdgeToEdgeInsets;
import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.ConfirmMode;
import com.wisebook.app.domain.model.LlmProvider;

/**
 * 设置页：三组领域设置（D1 §3.5）+ 一组模型连接配置。
 *
 * <p>表单只在<b>第一次</b>加载时被填充（{@code populated} 标志），
 * 之后再来的数据不会覆盖用户已经改了一半的输入——否则保存后回读一次，
 * 用户正在编辑的内容就会被冲掉。
 */
public class SettingsActivity extends AppCompatActivity {

    /** 大额阈值允许的上限（元）。D1 只说「可设」，给一个防止手滑输入天文数字的边界 */
    private static final long MAX_THRESHOLD_YUAN = 1_000_000L;

    private SettingsViewModel viewModel;

    private RadioGroup confirmModeGroup;
    private EditText thresholdInput;
    private RadioGroup schemeGroup;
    private RadioGroup providerGroup;
    private EditText modelInput;
    private EditText apiKeyInput;
    private TextView status;

    private boolean populated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);
        EdgeToEdgeInsets.apply(findViewById(R.id.settings_root));

        confirmModeGroup = findViewById(R.id.group_confirm_mode);
        thresholdInput = findViewById(R.id.threshold);
        schemeGroup = findViewById(R.id.group_scheme);
        providerGroup = findViewById(R.id.group_provider);
        modelInput = findViewById(R.id.model);
        apiKeyInput = findViewById(R.id.api_key);
        status = findViewById(R.id.status);

        WiseBookApp app = WiseBookApp.from(this);
        viewModel = new ViewModelProvider(this, new SettingsViewModel.Factory(
                app.settingsRepository(),
                app.llmConfigStore(),
                app.databaseExecutor(),
                app::invalidateLlmClient,
                WiseBookDatabase.DEFAULT_USER_ID))
                .get(SettingsViewModel.class);

        viewModel.form().observe(this, this::populateOnce);
        viewModel.status().observe(this, message -> status.setText(message));
        findViewById(R.id.btn_save).setOnClickListener(view -> save());

        viewModel.load();
    }

    private void populateOnce(SettingsViewModel.Form form) {
        if (populated) {
            return;
        }
        populated = true;

        check(confirmModeGroup, form.confirmMode == ConfirmMode.STRICT ? R.id.mode_strict
                : form.confirmMode == ConfirmMode.DOUBTFUL ? R.id.mode_doubtful
                : R.id.mode_large);

        thresholdInput.setText(Long.toString(form.largeThresholdCents / 100L));

        check(schemeGroup, form.categoryScheme == CategoryScheme.SIMPLE
                ? R.id.scheme_simple : R.id.scheme_standard);

        check(providerGroup, form.provider == LlmProvider.DEEPSEEK
                ? R.id.provider_deepseek : R.id.provider_siliconflow);

        // 只预填「运行时覆盖值」，不预填编译期默认值——
        // 否则一进设置页点保存，就把 local.properties 里的 Key 固化成了一条覆盖记录
        modelInput.setText(form.modelOverride == null ? "" : form.modelOverride);
        apiKeyInput.setText(form.apiKeyOverride == null ? "" : form.apiKeyOverride);
    }

    private void save() {
        Long cents = parseThresholdCents(thresholdInput.getText().toString());
        if (cents == null) {
            status.setText("大额阈值请填 0 到 " + MAX_THRESHOLD_YUAN + " 之间的整数（元）");
            return;
        }

        viewModel.save(
                selectedConfirmMode(),
                cents,
                schemeGroup.getCheckedRadioButtonId() == R.id.scheme_simple
                        ? CategoryScheme.SIMPLE : CategoryScheme.STANDARD,
                providerGroup.getCheckedRadioButtonId() == R.id.provider_deepseek
                        ? LlmProvider.DEEPSEEK : LlmProvider.SILICONFLOW,
                modelInput.getText().toString(),
                apiKeyInput.getText().toString());
    }

    private ConfirmMode selectedConfirmMode() {
        int checked = confirmModeGroup.getCheckedRadioButtonId();
        if (checked == R.id.mode_strict) {
            return ConfirmMode.STRICT;
        }
        if (checked == R.id.mode_doubtful) {
            return ConfirmMode.DOUBTFUL;
        }
        return ConfirmMode.LARGE;
    }

    /** @return 解析失败或超范围时返回 {@code null} */
    private static Long parseThresholdCents(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            long yuan = Long.parseLong(text);
            if (yuan < 0L || yuan > MAX_THRESHOLD_YUAN) {
                return null;
            }
            return yuan * 100L;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void check(RadioGroup group, int radioButtonId) {
        group.check(radioButtonId);
    }
}
