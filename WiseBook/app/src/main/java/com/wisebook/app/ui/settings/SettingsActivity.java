package com.wisebook.app.ui.settings;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.BuildConfig;
import com.wisebook.app.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import com.wisebook.app.WiseBookApp;
import com.wisebook.app.data.BillImportRepository;
import com.wisebook.app.data.EntryTransferRepository;
import com.wisebook.app.data.local.entity.LedgerEntity;
import com.wisebook.app.data.transfer.EntryCsv;
import com.wisebook.app.input.bill.BillFormat;
import com.wisebook.app.input.bill.BillSheet;
import com.wisebook.app.domain.model.ConfirmMode;
import com.wisebook.app.domain.model.LlmProvider;
import com.wisebook.app.ui.EdgeToEdgeInsets;

import java.util.function.Consumer;

/**
 * 设置页：三组领域/连接设置 + 一组账本管理。
 *
 * <p>2026-10-07 从「单选框直接铺在页面上」改成<b>列表 + 对话框</b>。
 * 这里最关键的变化是<b>状态不再存在控件里</b>：以前 {@code save()} 去问
 * "哪个 RadioButton 被选中了"，现在页面自己持有一份值（下面那几个字段），控件只负责显示。
 * 没有这一步，"点一行 → 弹框选 → 回来更新显示"这条路径就无从谈起
 * ——控件会被反复重建，问不出一个稳定的当前值。
 *
 * <p><b>2026-10-11：取消底部「保存」按钮（用户要求）。</b>每个选项在自己的对话框里点
 * 「确定」即写库，"改了要记得保存"这一步没有了。所以现在页面上的值在点确定的那一刻
 * 就已经落库，不再有 {@code save()} 这个总入口。
 *
 * <p>表单只在<b>第一次</b>加载时被填充（{@code populated} 标志），
 * 之后再来的数据不会覆盖用户已经改了一半的输入。
 */
public class SettingsActivity extends AppCompatActivity {

    /** 大额阈值允许的上限（元）。D1 只说「可设」，给一个防止手滑输入天文数字的边界 */
    private static final long MAX_THRESHOLD_YUAN = 1_000_000L;

    /**
     * 意见反馈的收件地址（2026-10-08 用户提供）。
     *
     * <p>写死在代码里而不是放进设置页：它是<b>产品的对外出口</b>，不是用户配置项——
     * 放进设置只会多一个"被改错、然后反馈再也收不到"的地方。
     */
    private static final String FEEDBACK_EMAIL = "2590526791@qq.com";

    private SettingsViewModel viewModel;

    // ------------------------------------------------------------------ 页面状态

    private ConfirmMode confirmMode = ConfirmMode.DOUBTFUL;
    private String thresholdYuan = "300";
    private LlmProvider provider = LlmProvider.SILICONFLOW;
    private String modelOverride = "";
    private String apiKeyOverride = "";

    private TextView confirmModeValue;
    private TextView thresholdValue;
    private TextView ledgerValue;
    private TextView providerValue;
    private TextView modelValue;
    private TextView apiKeyValue;

    private boolean populated = false;

    // ------------------------------------------------------------------ 账本备份（2026-10-08）

    private EntryTransferRepository transferRepository;
    private BillImportRepository billImportRepository;
    private ExecutorService executor;

    /**
     * 导出走系统「新建文档」（SAF），<b>零权限</b>——与首页选图用 {@code PickVisualMedia} 是同一个取向：
     * 应用拿到的只是用户明确选中的那个文件的写权限，不必申请"读写存储"。
     */
    private final ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
                if (uri != null) {
                    writeCsvTo(uri);
                }
            });

    /** 导入走系统「打开文档」，同样是零权限 */
    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    importFileFrom(uri);
                }
            });

    /**
     * xlsx 的 MIME。
     *
     * <p>为什么不靠它来识别文件：文件管理器报的 MIME 并不可靠（已实测有把 CSV
     * 报成 octet-stream 的）。它的作用只是让系统选择器别把 xlsx 灰掉——
     * <b>真正的识别看的是文件内容</b>（{@code BillFormat}）。
     */
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);
        EdgeToEdgeInsets.apply(findViewById(R.id.settings_root));

        findViewById(R.id.btn_back).setOnClickListener(view -> finish());

        confirmModeValue = findViewById(R.id.value_confirm_mode);
        thresholdValue = findViewById(R.id.value_threshold);
        ledgerValue = findViewById(R.id.value_ledger);
        providerValue = findViewById(R.id.value_provider);
        modelValue = findViewById(R.id.value_model);
        apiKeyValue = findViewById(R.id.value_api_key);

        findViewById(R.id.row_confirm_mode).setOnClickListener(view -> pickConfirmMode());
        findViewById(R.id.row_threshold).setOnClickListener(view -> editThreshold());
        // 账本：进独立的选择页（内容含各账本的分类清单，对话框装不下）
        findViewById(R.id.row_ledger).setOnClickListener(
                view -> startActivity(new Intent(this, LedgerPickerActivity.class)));
        findViewById(R.id.row_provider).setOnClickListener(view -> pickProvider());
        findViewById(R.id.row_model).setOnClickListener(view -> editModel());
        findViewById(R.id.row_api_key).setOnClickListener(view -> editApiKey());

        WiseBookApp app = WiseBookApp.from(this);
        transferRepository = app.entryTransferRepository();
        billImportRepository = app.billImportRepository();
        executor = app.databaseExecutor();

        findViewById(R.id.row_export).setOnClickListener(view -> exportCsv());
        findViewById(R.id.row_import).setOnClickListener(view -> confirmImport());
        findViewById(R.id.row_feedback).setOnClickListener(view -> showFeedbackForm());

        viewModel = new ViewModelProvider(this, new SettingsViewModel.Factory(
                app.settingsRepository(),
                app.llmConfigStore(),
                app.databaseExecutor(),
                app::invalidateLlmClient,
                app.currentLedgerId()))
                .get(SettingsViewModel.class);

        viewModel.form().observe(this, this::populateOnce);
        // 保存结果只作提示：没有"保存按钮"之后，页面不再需要一行常驻状态
        viewModel.status().observe(this, message -> {
            if (message != null && !message.isEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            }
        });

        viewModel.load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从账本选择页回来时，当前账本名可能已经变了
        loadLedgerLabel();
    }

    // ------------------------------------------------------------------ 填充与显示

    private void populateOnce(SettingsViewModel.Form form) {
        if (populated) {
            return;
        }
        populated = true;

        confirmMode = form.confirmMode;
        thresholdYuan = Long.toString(form.largeThresholdCents / 100L);
        provider = form.provider;
        // 只预填「运行时覆盖值」，不预填编译期默认值——
        // 否则一进设置页点保存，就把 local.properties 里的 Key 固化成了一条覆盖记录
        modelOverride = form.modelOverride == null ? "" : form.modelOverride;
        apiKeyOverride = form.apiKeyOverride == null ? "" : form.apiKeyOverride;

        renderValues();
    }

    /** 把当前状态刷到每一行的右侧。改完值只调这一个方法，显示不会漏项 */
    private void renderValues() {
        confirmModeValue.setText(modeLabel(confirmMode));
        thresholdValue.setText(getString(R.string.settings_value_yuan, thresholdYuan));
        loadLedgerLabel();
        providerValue.setText(provider == LlmProvider.DEEPSEEK
                ? R.string.settings_provider_deepseek : R.string.settings_provider_siliconflow);
        modelValue.setText(modelOverride.isEmpty()
                ? getString(R.string.settings_value_default) : modelOverride);
        // Key 不明文显示：设置页可能被别人瞄到，而它本身也不短
        apiKeyValue.setText(apiKeyOverride.isEmpty()
                ? getString(R.string.settings_value_default)
                : getString(R.string.settings_value_configured));
    }

    /** 档位在行上只显示短名，长解释留在对话框里 */
    private String modeLabel(ConfirmMode mode) {
        switch (mode) {
            case STRICT:
                return getString(R.string.settings_mode_strict_short);
            case DOUBTFUL:
                return getString(R.string.settings_mode_doubtful_short);
            case LARGE:
            default:
                return getString(R.string.settings_mode_large_short);
        }
    }

    /**
     * 「账本」那一行右侧显示的当前账本名与笔数。
     *
     * <p>要读一次库（账本名 + 该账本的账目数），所以异步取回来再填。
     * 新账本还没记账时笔数是 0，这是实话，不特殊处理。
     */
    private void loadLedgerLabel() {
        WiseBookApp app = WiseBookApp.from(this);
        app.databaseExecutor().execute(() -> {
            long id = app.currentLedgerId();
            LedgerEntity ledger = app.ledgerRepository().find(id);
            int count = app.ledgerRepository().entryCount(id);
            String name = ledger == null ? "" : ledger.name;
            runOnUiThread(() -> ledgerValue.setText(name == null || name.isEmpty()
                    ? getString(R.string.settings_ledger_none)
                    : getString(R.string.settings_ledger_value, name, count)));
        });
    }

    // ------------------------------------------------------------------ 对话框

    /**
     * 单选弹框。选中只是记住，点「确定」才生效——"在选项里确定"。
     */
    private void pickOption(int titleRes, String[] labels, int checked, Consumer<Integer> onPicked) {
        final int[] chosen = {checked};
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> chosen[0] = which)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm,
                        (dialog, which) -> onPicked.accept(chosen[0]))
                .show();
    }

    private void pickConfirmMode() {
        final ConfirmMode[] values = {ConfirmMode.STRICT, ConfirmMode.LARGE, ConfirmMode.DOUBTFUL};
        String[] labels = new String[values.length];
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = modeLabel(values[i]);
            if (values[i] == confirmMode) {
                checked = i;
            }
        }
        pickOption(R.string.settings_confirm_mode, labels, checked, which -> {
            confirmMode = values[which];
            renderValues();
            saveAccounting();
        });
    }

    private void pickProvider() {
        final LlmProvider[] values = {LlmProvider.SILICONFLOW, LlmProvider.DEEPSEEK};
        String[] labels = {
                getString(R.string.settings_provider_siliconflow),
                getString(R.string.settings_provider_deepseek),
        };
        pickOption(R.string.settings_provider, labels, provider == LlmProvider.DEEPSEEK ? 1 : 0,
                which -> {
                    provider = values[which];
                    renderValues();
                    saveLlm();
                });
    }

    private void editThreshold() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(thresholdYuan);
        input.setSelection(input.getText().length());
        showInputDialog(R.string.settings_threshold, input, text ->
                parseThresholdCents(text) == null
                        ? getString(R.string.settings_threshold_invalid, MAX_THRESHOLD_YUAN)
                        : null, text -> {
            thresholdYuan = text;
            renderValues();
            saveAccounting();
        });
    }

    private void editModel() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.settings_model_hint);
        input.setText(modelOverride);
        input.setSelection(input.getText().length());
        showInputDialog(R.string.settings_model, input, text -> null, text -> {
            modelOverride = text;
            renderValues();
            saveLlm();
        });
    }

    private void editApiKey() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(R.string.settings_api_key_hint);
        input.setText(apiKeyOverride);
        input.setSelection(input.getText().length());
        showInputDialog(R.string.settings_api_key, input, text -> null, text -> {
            apiKeyOverride = text;
            renderValues();
            saveLlm();
        });
    }

    /** 输入校验：返回错误文案（不关框），返回 {@code null} 表示放行 */
    private interface Validator {
        String validate(String text);
    }

    /**
     * 带输入框的对话框，点「确定」即生效。
     *
     * <p>输入框外面包一层带 padding 的容器：{@code AlertDialog} 直接 setView 一个
     * {@code EditText} 时，它会贴着对话框边缘，看起来像被裁掉了一块。
     *
     * <p>正向按钮的监听在 {@code show()} 之后自己接：{@code setPositiveButton} 一定会关框，
     * 而校验没过时我们希望框还留着让用户改。
     */
    private void showInputDialog(int titleRes, EditText input, Validator validator,
                                 Consumer<String> onConfirm) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(padded(input))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String text = input.getText().toString().trim();
            String error = validator.validate(text);
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
                return;
            }
            hideKeyboard(input);
            dialog.dismiss();
            onConfirm.accept(text);
        });
    }

    private View padded(View content) {
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return container;
    }

    private void hideKeyboard(View view) {
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) {
            manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    // ------------------------------------------------------------------ 保存

    /** 落地「记账行为」两项。阈值不合法时本就不该走到这里（输入框那一步已拦） */
    private void saveAccounting() {
        Long cents = parseThresholdCents(thresholdYuan);
        if (cents == null) {
            Toast.makeText(this, getString(R.string.settings_threshold_invalid, MAX_THRESHOLD_YUAN),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        viewModel.saveAccounting(confirmMode, cents);
    }

    /** 落地模型连接配置 */
    private void saveLlm() {
        viewModel.saveLlm(provider, modelOverride, apiKeyOverride);
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

    // ------------------------------------------------------------------ 账本备份

    private void exportCsv() {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        exportLauncher.launch(getString(R.string.settings_export_file_name, stamp));
    }

    /**
     * 写 CSV。在数据库线程上做（要读库、要拼文本），写完回主线程报告。
     *
     * <p><b>BOM 拼在这里</b>（而不是 {@code EntryCsv} 里）：{@link EntryCsv} 是纯逻辑，
     * 不该关心"给谁看"；而 Excel 打开不带 BOM 的 UTF-8 CSV 会把中文显示成乱码，
     * 这个锅只能由写文件这一层背。
     */
    private void writeCsvTo(Uri uri) {
        executor.execute(() -> {
            EntryTransferRepository.ExportResult result;
            try {
                result = transferRepository.exportAll();
            } catch (RuntimeException e) {
                postTransferMessage(getString(R.string.settings_export_failed, messageOf(e)));
                return;
            }
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    postTransferMessage(getString(R.string.settings_export_failed, "打不开目标文件"));
                    return;
                }
                out.write((EntryCsv.BOM + result.csv).getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException | RuntimeException e) {
                postTransferMessage(getString(R.string.settings_export_failed, messageOf(e)));
                return;
            }
            postTransferMessage(result.count == 0
                    ? getString(R.string.settings_export_empty)
                    : getString(R.string.settings_export_done, result.count));
        });
    }

    /**
     * 导入前的说明。
     *
     * <p>先讲清"不会覆盖、重复的怎么处理"，用户才敢点下去——
     * 给一个会改数据的动作加一次确认，比事后解释便宜得多。
     *
     * <p>2026-10-08 起这个入口<b>同时收账本 CSV 与支付账单（微信 xlsx / csv）</b>：
     * 认格式是应用的事，不该让用户先想清楚自己手上是什么文件。
     */
    private void confirmImport() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_import)
                .setMessage(getString(R.string.settings_import_message))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm, (dialog, which) ->
                        importLauncher.launch(new String[]{"text/csv", "text/plain",
                                "application/csv", XLSX_MIME, "*/*"}))
                .show();
    }

    /**
     * 读文件并导入。在数据库线程上做（解析 + 写库），结果用对话框说清楚。
     *
     * <p><b>读成字节而不是字符串</b>：xlsx 是二进制的，而且文本文件也得先拿到字节
     * 才能判编码（微信与支付宝的 CSV 都是 GBK，见 {@code BillTextDecoder}）。
     * 先按 UTF-8 解成字符串再判断，那一步就已经把 GBK 的中文表头毁掉了。
     *
     * <p>MIME 放宽成"任意类型"：有些文件管理器把 CSV 报成 {@code text/plain} 甚至
     * {@code application/octet-stream}，卡在 MIME 上会让用户"看得见文件却选不中"。
     * 选错文件的代价也只是一句「认不出这个文件」。
     *
     * <p>（这段注释刻意不写通配符 MIME 的字面量：它含一个斜杠星号，会把块注释提前闭合。）
     */
    private void importFileFrom(Uri uri) {
        executor.execute(() -> {
            byte[] bytes;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    postTransferMessage(getString(R.string.settings_import_failed, "打不开这个文件"));
                    return;
                }
                bytes = readBytes(in);
            } catch (IOException | RuntimeException e) {
                postTransferMessage(getString(R.string.settings_import_failed, messageOf(e)));
                return;
            }

            String summary = routeImport(bytes);
            if (summary == null) {
                return;                         // 错误消息已经弹过了
            }
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle(R.string.settings_transfer_title)
                    .setMessage(summary)
                    .setPositiveButton(R.string.action_confirm, null)
                    .show());
        });
    }

    /**
     * <b>认格式 → 分派</b>。失败时弹提示并返回 {@code null}。
     *
     * <p>分派表写成一个 switch 而不是一串 if：多一种账单文件时只加一个分支，
     * 而"用户拿来的到底是什么"这件事只有一个判断处。
     */
    private String routeImport(byte[] bytes) {
        try {
            BillSheet sheet = BillSheet.read(bytes);
            BillFormat.Detected detected = BillFormat.detect(sheet.cells());
            switch (detected.format) {
                case WISEBOOK_CSV:
                    return transferRepository.importCsv(sheet.text()).summary();
                case WECHAT_BILL:
                    return billImportRepository.importBill(sheet, detected.headerRow).summary();
                case ALIPAY_BILL:
                    return getString(R.string.settings_import_alipay_unsupported);
                default:
                    return getString(R.string.settings_import_unknown);
            }
        } catch (RuntimeException e) {
            postTransferMessage(getString(R.string.settings_import_failed, messageOf(e)));
            return null;
        }
    }

    private void postTransferMessage(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    /**
     * 把一个输入流读成字节。
     *
     * <p>不用 {@code InputStream.readAllBytes()}：那是 Java 9 才有的 API，
     * 而本项目 minSdk 26（Android 8 自带的是 Java 8 库）——用它在老设备上会
     * {@code NoSuchMethodError}，而单测、模拟器、Lint 三处都看不见（HANDOFF §8-21）。
     *
     * <p>大小上限由 {@link BillSheet} 把关（8 MB），这里只负责老实读完。
     */
    private static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    // ------------------------------------------------------------------ 意见反馈

    /**
     * 打开反馈表单。
     *
     * <p>正文为空时<b>不关框</b>：用户已经写了一半的抱怨被清掉是最容易把人劝退的。
     * 所以正向按钮的监听是 {@code show()} 之后自己接的（{@code setPositiveButton} 一定会关框）。
     */
    private void showFeedbackForm() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_feedback, null, false);
        EditText input = content.findViewById(R.id.feedback_input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_feedback)
                .setView(content)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.settings_feedback_send, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.settings_feedback_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            sendFeedback(text);
        });
    }

    /**
     * 把反馈交出去：优先邮件，其次系统分享，最后复制到剪贴板。
     *
     * <p><b>不去问 {@code resolveActivity} 有没有邮件 App</b>：Android 11+ 的包可见性会让它
     * 对没声明 {@code <queries>} 的应用返回 {@code null}，于是"明明装了邮件却说没有"。
     * 直接试、失败就退，既不用改清单，也把"设备上真没有邮件 App"这条真实情况一并处理了。
     */
    private void sendFeedback(String userText) {
        String body = userText + "\n\n" + diagnostics();

        Intent mail = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
        mail.putExtra(Intent.EXTRA_EMAIL, new String[]{FEEDBACK_EMAIL});
        mail.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.settings_feedback_subject));
        mail.putExtra(Intent.EXTRA_TEXT, body);
        try {
            startActivity(mail);
            return;
        } catch (ActivityNotFoundException noMailApp) {
            // 退到系统分享面板：用户自己选微信 / QQ / 邮件客户端
        }

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.settings_feedback_subject));
        share.putExtra(Intent.EXTRA_TEXT, body);
        try {
            startActivity(Intent.createChooser(share, getString(R.string.settings_feedback_chooser)));
        } catch (ActivityNotFoundException nothingToShareWith) {
            copyToClipboard(body);
        }
    }

    /** 自动附带的诊断信息：只有环境，没有任何账目内容（用户选了"只带版本 + 设备"） */
    private String diagnostics() {
        return getString(R.string.settings_feedback_diagnostics,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                Build.MANUFACTURER + " " + Build.MODEL,
                Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT);
    }

    private void copyToClipboard(String text) {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.settings_feedback_subject), text));
        }
        Toast.makeText(this, R.string.settings_feedback_no_app, Toast.LENGTH_LONG).show();
    }
}
