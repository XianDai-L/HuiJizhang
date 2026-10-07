package com.wisebook.app.ui.confirm;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.R;
import com.wisebook.app.WiseBookApp;
import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.domain.confirm.ClarifyPlanner;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.PaymentMethod;
import com.wisebook.app.ui.EdgeToEdgeInsets;
import com.wisebook.app.ui.widget.CategoryPicker;

import java.util.List;

/**
 * 确认页 —— D1 §4 里 {@code ASKING} 状态的界面。
 *
 * <p>一页承担两件事：把「为什么要你核对」说清楚，以及让用户改。
 * 之所以合成一页而不是「反问页 + 确认页」两个页面：D1 §4 的图里
 * 「触发确认条件」与「需要补全信息」进的是同一个状态，
 * 拆成两页反而要让用户在两个页面之间来回跳。
 *
 * <p>界面只做三件事：把草稿渲染出来、把用户的选择交给 ViewModel、按结果关掉自己。
 * 「改完能不能落账」全部由 ViewModel 与仓储层判定——所以这里的代码薄到不需要为它写测试。
 */
public class ConfirmActivity extends AppCompatActivity {

    private static final String EXTRA_DRAFT_ID = "draft_id";

    private ConfirmViewModel viewModel;

    private TextView why;
    private TextView questions;
    private EditText amount;
    private RadioGroup directionGroup;
    private Button categoryButton;
    private Button paymentButton;
    private TextView occurredAt;
    private EditText merchant;
    private TextView rawInput;
    private TextView rawToggle;
    /** 原始输入是否展开。默认收起：它常是一整段截图转写文本，展开会把操作按钮顶出屏幕 */
    private boolean rawExpanded;
    private TextView status;
    private Button postButton;
    private Button saveButton;
    private Button discardButton;

    public static Intent intentFor(Context context, long draftId) {
        return new Intent(context, ConfirmActivity.class).putExtra(EXTRA_DRAFT_ID, draftId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_confirm);
        EdgeToEdgeInsets.apply(findViewById(R.id.confirm_root));

        why = findViewById(R.id.why);
        questions = findViewById(R.id.questions);
        amount = findViewById(R.id.amount);
        directionGroup = findViewById(R.id.group_direction);
        categoryButton = findViewById(R.id.btn_category);
        paymentButton = findViewById(R.id.btn_payment);
        occurredAt = findViewById(R.id.occurred_at);
        merchant = findViewById(R.id.merchant);
        rawInput = findViewById(R.id.raw_input);
        rawToggle = findViewById(R.id.btn_toggle_raw);
        status = findViewById(R.id.status);
        rawToggle.setOnClickListener(view -> {
            rawExpanded = !rawExpanded;
            applyRawState();
        });
        applyRawState();
        postButton = findViewById(R.id.btn_post);
        saveButton = findViewById(R.id.btn_save);
        discardButton = findViewById(R.id.btn_discard);

        WiseBookApp app = WiseBookApp.from(this);
        viewModel = new ViewModelProvider(this, new ConfirmViewModel.Factory(
                app.draftRepository(),
                app.settingsRepository(),
                app.categoryRepository(),
                app.databaseExecutor(),
                WiseBookDatabase.DEFAULT_USER_ID))
                .get(ConfirmViewModel.class);

        categoryButton.setOnClickListener(view -> pickCategory());
        paymentButton.setOnClickListener(view -> pickPayment());
        saveButton.setOnClickListener(view -> {
            syncFromUi();
            viewModel.saveAndRevalidate();
        });
        postButton.setOnClickListener(view -> {
            syncFromUi();
            viewModel.post();
        });
        discardButton.setOnClickListener(view -> confirmDiscard());

        viewModel.form().observe(this, this::render);
        viewModel.status().observe(this, message -> status.setText(message));
        // 落账或丢弃完成后自动退出：用户的目的已经达成，留在这一页只会让他怀疑没成功
        viewModel.finished().observe(this, done -> {
            if (Boolean.TRUE.equals(done)) {
                finish();
            }
        });

        long draftId = getIntent().getLongExtra(EXTRA_DRAFT_ID, -1L);
        if (draftId <= 0L) {
            Toast.makeText(this, "缺少草稿标识", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        viewModel.load(draftId);
    }

    /** 把界面上正在编辑的三项交给 ViewModel（分类与支付方式在选择时就已同步） */
    private void syncFromUi() {
        Direction direction = directionGroup.getCheckedRadioButtonId() == R.id.direction_income
                ? Direction.INCOME : Direction.EXPENSE;
        viewModel.syncEdits(amount.getText().toString(), direction,
                merchant.getText().toString());
    }

    private void render(ConfirmViewModel.Form form) {
        why.setText(form.why == null || form.why.isEmpty() ? "（这笔记账没触发任何确认条件）" : form.why);
        questions.setText(form.questions == null || form.questions.isEmpty()
                ? "" : getString(R.string.confirm_questions) + "：\n" + form.questions);

        // 金额与商户只在首次渲染时填进输入框；之后用户正在编辑的内容不该被回写冲掉
        if (!amount.isFocused() && amount.getText().toString().isEmpty()) {
            amount.setText(form.amountYuan);
        }
        if (!merchant.isFocused() && merchant.getText().toString().isEmpty()) {
            merchant.setText(form.merchant == null ? "" : form.merchant);
        }

        directionGroup.check(form.direction == Direction.INCOME
                ? R.id.direction_income : R.id.direction_expense);
        categoryButton.setText(form.categoryPath);
        paymentButton.setText(form.paymentText == null
                ? getString(R.string.confirm_payment_unset) : form.paymentText);
        occurredAt.setText(getString(R.string.confirm_occurred_at) + "：" + form.occurredAtText);
        // 「你原来说的」这几个字已经由折叠标题承担，这里只放内容本身
        rawInput.setText(form.rawInput == null ? "" : form.rawInput);

        postButton.setEnabled(form.editable);
        saveButton.setEnabled(form.editable);
        amount.setEnabled(form.editable);
        merchant.setEnabled(form.editable);
    }

    /**
     * 原始输入的展开 / 收起。
     *
     * <p>整行标题都可点（而不是只在末尾挂一个小箭头）：点中的面积更大，
     * 而"我原来那句话"本身就是要看的东西，点它展开是最自然的手势。
     */
    private void applyRawState() {
        rawInput.setVisibility(rawExpanded ? View.VISIBLE : View.GONE);
        rawToggle.setText(rawExpanded
                ? getString(R.string.confirm_raw_toggle) + " ▾"
                : getString(R.string.confirm_raw_toggle) + " ▸");
    }

    /** 分类选择器：一级横向、二级在下方展开（与账本页共用同一份实现） */
    private void pickCategory() {
        CategoryPicker.show(this, viewModel.tree(), viewModel.direction(), viewModel.scheme(),
                path -> {
                    viewModel.setCategoryPath(path);
                    categoryButton.setText(path);
                });
    }

    private void pickPayment() {
        // 反问清单里不再问支付方式（见 ClarifyPlanner），但确认页上应该能顺手选一个
        final List<String> codes = ClarifyPlanner.paymentMethodChoices();
        String[] labels = new String[codes.size() + 1];
        labels[0] = getString(R.string.confirm_payment_unset);
        for (int i = 0; i < codes.size(); i++) {
            labels[i + 1] = PaymentMethod.fromCode(codes.get(i)).label();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_payment)
                .setItems(labels, (dialog, which) -> {
                    PaymentMethod chosen = which == 0
                            ? null : PaymentMethod.fromCode(codes.get(which - 1));
                    viewModel.setPaymentMethod(chosen);
                    paymentButton.setText(chosen == null
                            ? getString(R.string.confirm_payment_unset) : chosen.label());
                })
                .show();
    }

    private void confirmDiscard() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_action_discard)
                .setMessage("丢弃后这笔不会记账。原始输入会保留，方便你回头看 AI 当时理解成了什么。")
                .setNegativeButton("再想想", null)
                .setPositiveButton("丢弃", (dialog, which) -> viewModel.discard())
                .show();
    }
}
