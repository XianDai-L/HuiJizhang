package com.wisebook.app.ui.ledger;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.R;
import com.wisebook.app.WiseBookApp;
import com.wisebook.app.ui.DraftFormatter;
import com.wisebook.app.ui.EdgeToEdgeInsets;
import com.wisebook.app.ui.widget.CategoryPicker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 账目详情。
 *
 * <p>原先账本列表点一条只弹「改正分类 / 撤销」两个动作，用户看不到这笔账的任何细节。
 * 现在点击进这一页：账目的全貌 + 它是怎么来的 + 两个动作。
 *
 * <p><b>「输入解析」整块默认收起</b>（HANDOFF 决策 45 的实机反馈）：截图入口的原文
 * 可能是一整页账单的转写文本，展开着会把四个字段区和操作按钮全顶到屏幕外。
 * 想看的时候点一下标题——点它展开是最自然的手势，因为"我原来那句话"本身就是要看的东西。
 */
public class EntryDetailActivity extends AppCompatActivity {

    private static final String EXTRA_ENTRY_ID = "entry_id";

    private EntryDetailViewModel viewModel;
    private ExecutorService executor;

    private TextView amount;
    private TextView directionCategory;
    private Button toggleParse;
    private LinearLayout parsePanel;
    private ImageView evidenceImage;
    private TextView rawLabel;
    private TextView rawText;
    private TextView parseDetails;
    private LinearLayout detailRows;
    private TextView status;

    private boolean parseExpanded;

    public static Intent intentFor(Context context, long entryId) {
        return new Intent(context, EntryDetailActivity.class).putExtra(EXTRA_ENTRY_ID, entryId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_entry_detail);
        EdgeToEdgeInsets.apply(findViewById(R.id.detail_root));

        amount = findViewById(R.id.amount);
        directionCategory = findViewById(R.id.direction_category);
        toggleParse = findViewById(R.id.btn_toggle_parse);
        parsePanel = findViewById(R.id.parse_panel);
        evidenceImage = findViewById(R.id.evidence_image);
        rawLabel = findViewById(R.id.raw_label);
        rawText = findViewById(R.id.raw_text);
        parseDetails = findViewById(R.id.parse_details);
        detailRows = findViewById(R.id.detail_rows);
        status = findViewById(R.id.status);

        WiseBookApp app = WiseBookApp.from(this);
        executor = app.databaseExecutor();
        viewModel = new ViewModelProvider(this, new EntryDetailViewModel.Factory(
                app.entryRepository(),
                app.draftRepository(),
                app.settingsRepository(),
                app.categoryRepository(),
                app.evidenceStore(),
                app.databaseExecutor(),
                app.currentLedgerId()))
                .get(EntryDetailViewModel.class);

        toggleParse.setOnClickListener(view -> {
            parseExpanded = !parseExpanded;
            applyParseState();
        });
        // 初始也要走一遍：箭头（▶）只有在 applyParseState 里才拼得上去，
        // 漏掉这一句，收起状态就只剩「输入解析」四个字，看不出这行能点（实机反馈）
        applyParseState();
        // 顶栏的返回键：这一页是从账本或首页点进来的，返回即回上一页
        findViewById(R.id.btn_back).setOnClickListener(view -> finish());
        // 金额可改：动作长在金额本身上，和「分类」那一行是同一个思路
        amount.setOnClickListener(view -> editAmount());
        // 原始截图缩在 180dp 的框里连金额都看不清，点开看大图
        evidenceImage.setOnClickListener(view -> showEvidenceFullScreen());
        // 「撤销」收进右上角的三点菜单：它是低频且不可逆的动作，
        // 摆在页面正中容易误触，收起来之后误触成本高了一档。
        // 「改正分类」则搬到了「分类」那一行上（见 fillSummary）
        findViewById(R.id.btn_more).setOnClickListener(this::showOverflowMenu);

        viewModel.detail().observe(this, this::render);
        viewModel.status().observe(this, message -> {
            if (message != null) {
                status.setText(message);
            }
        });
        // 撤销之后自动退出：这笔账已经不在账本里了，留在详情页没有意义
        viewModel.finished().observe(this, done -> {
            if (Boolean.TRUE.equals(done)) {
                finish();
            }
        });

        long entryId = getIntent().getLongExtra(EXTRA_ENTRY_ID, -1L);
        if (entryId <= 0L) {
            finish();
            return;
        }
        viewModel.load(entryId);
    }

    private void render(EntryDetailViewModel.Detail detail) {
        amount.setText(detail.amount);
        directionCategory.setText(detail.directionCategory);
        rawLabel.setText(detail.rawLabel);
        rawText.setText(detail.rawText);
        parseDetails.setText(detail.parseDetails == null ? "" : detail.parseDetails);
        showEvidence(detail.evidencePath);

        fillSummary(detail.summaryRows);
    }

    /**
     * 原图。只有截图入口才有——文字与语音入口没有证据文件，这时整块隐藏。
     *
     * <p>解码放后台：整屏截图动辄几百 KB，解码要几十毫秒，在主线程上做会掉帧。
     */
    private void showEvidence(String absolutePath) {
        if (absolutePath == null || absolutePath.trim().isEmpty()) {
            evidenceImage.setVisibility(View.GONE);
            return;
        }
        evidenceImage.setVisibility(View.VISIBLE);
        final String path = absolutePath;
        executor.execute(() -> {
            Bitmap bitmap = BitmapFactory.decodeFile(path);
            runOnUiThread(() -> {
                if (bitmap == null) {
                    // 文件在、但解不出来（被清理过、或根本不是图片）：不显示空框
                    evidenceImage.setVisibility(View.GONE);
                } else {
                    evidenceImage.setImageBitmap(bitmap);
                }
            });
        });
    }

    /**
     * 点开看大图。
     *
     * <p>详情页那张缩略图只有 180dp 高，账单截图里的金额、商户名根本读不清——
     * 而「这笔账是从哪张图来的」恰恰需要看清原文才有意义。
     *
     * <p>用全屏黑底 + FIT_CENTER，点任意位置关闭：看图时不该还要去找返回键。
     */
    private void showEvidenceFullScreen() {
        Drawable drawable = evidenceImage.getDrawable();
        if (drawable == null) {
            return;
        }
        ImageView full = new ImageView(this);
        full.setImageDrawable(drawable);
        full.setScaleType(ImageView.ScaleType.FIT_CENTER);
        full.setBackgroundColor(Color.BLACK);

        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(full, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        full.setOnClickListener(view -> dialog.dismiss());
        dialog.show();
    }

    /**
     * 「输入解析」的展开 / 收起。
     *
     * <p>标题上的箭头是唯一的折叠提示——按钮本身就是标题行，整行都可点。
     */
    private void applyParseState() {
        parsePanel.setVisibility(parseExpanded ? View.VISIBLE : View.GONE);
        toggleParse.setText(parseExpanded
                ? getString(R.string.detail_section_input) + " ▼"
                : getString(R.string.detail_section_input) + " ▶");
    }

    /**
     * 铺开正面那张字段表。
     *
     * <p>从「一行两列」改成一列：以前两列是为了把四组字段全塞进一屏，
     * 现在正面只留日常要看的那几行，一列就够——值也终于能写完整
     * （半屏宽那版遇到长商户名只能换行）。
     *
     * <p><b>「分类」那一行整行可点</b>，点它就是改正分类：
     * 让动作长在字段上，比在页面底部再找一个按钮少一步。
     */
    private void fillSummary(List<EntryDetailViewModel.Row> rows) {
        detailRows.removeAllViews();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        String categoryLabel = getString(R.string.confirm_category);

        for (EntryDetailViewModel.Row row : rows) {
            View cell = inflater.inflate(R.layout.item_detail_cell, detailRows, false);
            ((TextView) cell.findViewById(R.id.row_label)).setText(row.label);

            boolean editable = categoryLabel.equals(row.label);
            // 分类路径里的分隔符染色：那是层级标记，不该和分类名抢注意力
            TextView valueView = cell.findViewById(R.id.row_value);
            valueView.setText(editable
                    ? DraftFormatter.coloredPath(this, row.value)
                    : row.value);
            cell.findViewById(R.id.row_chevron)
                    .setVisibility(editable ? View.VISIBLE : View.GONE);
            if (editable) {
                cell.setBackgroundResource(android.R.drawable.list_selector_background);
                cell.setOnClickListener(view -> pickCategory());
            }

            detailRows.addView(cell, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /**
     * 右上角的三点菜单。目前只有一项：删除这笔账目。
     *
     * <p>用 {@link PopupMenu} 而不是 AlertDialog 列表：它贴着按钮下方弹出，
     * 用户看得见自己点的是哪一个按钮，也知道这个菜单属于这一页。
     */
    private void showOverflowMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(getString(R.string.detail_action_delete));
        menu.setOnMenuItemClickListener(item -> {
            confirmVoid();
            return true;
        });
        menu.show();
    }

    /**
     * 改金额（2026-10-08 新增）。
     *
     * <p>用最朴素的输入框，不做专门的金额键盘：记账应用里「改一个数」是低频动作，
     * 弹一个能直接输的框最快，多一层自定义键盘就多一层要维护的东西。
     *
     * <p>校验只挡两件事——能不能解析成数、是不是正数。多于两位小数按四舍五入到分，
     * 与入账时金额换算的规则一致。
     */
    private void editAmount() {
        EntryDetailViewModel.Detail detail = viewModel.detail().getValue();
        if (detail == null) {
            return;
        }

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setGravity(Gravity.CENTER);
        // 预填当前金额：多数时候用户只是想改其中一个数字，不该让他从头打一遍
        input.setText(DraftFormatter.groupedYuan(detail.amountCents).replace(",", ""));
        input.setSelection(input.getText().length());

        // 裸 EditText 自带边距是 0，直接塞进对话框会贴着边框
        FrameLayout box = new FrameLayout(this);
        int horizontal = getResources().getDimensionPixelSize(R.dimen.space_6);
        int vertical = getResources().getDimensionPixelSize(R.dimen.space_2);
        box.setPadding(horizontal, vertical, horizontal, 0);
        box.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(R.string.detail_edit_amount)
                .setView(box)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                    Long cents = yuanToCents(input.getText().toString());
                    if (cents == null) {
                        status.setText(R.string.detail_amount_invalid);
                        return;
                    }
                    viewModel.correctAmount(cents);
                })
                .show();
    }

    /**
     * 「元」文本 → 「分」。
     *
     * <p>用 {@link BigDecimal} 而不是 {@code Double.parseDouble(x) * 100}：
     * 后者在 0.1 这类数上会给出 10.000000000000002 这样的结果，取整后就差了一分。
     * 一分钱不影响大局，但这种偏差没有任何理由留着。
     *
     * @return 解析不了、非正数、或超出 long 范围时返回 {@code null}
     */
    private static Long yuanToCents(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim().replace(",", "");
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            long cents = new BigDecimal(trimmed)
                    .movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            return cents > 0L ? cents : null;
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private void pickCategory() {
        CategoryPicker.show(this, viewModel.tree(), viewModel.direction(), viewModel.scheme(),
                viewModel::correct);
    }

    private void confirmVoid() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.detail_action_delete)
                .setMessage("这笔将从账本与统计里去掉，原始截图也会一并删除。")
                .setNegativeButton("再想想", null)
                .setPositiveButton(R.string.detail_action_delete_short,
                        (dialog, which) -> viewModel.voidEntry())
                .show();
    }
}
