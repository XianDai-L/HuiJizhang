package com.wisebook.app.ui.ledger;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.R;
import com.wisebook.app.WiseBookApp;
import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.ui.EdgeToEdgeInsets;
import com.wisebook.app.ui.widget.CategoryPicker;

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
    private LinearLayout amountRows;
    private LinearLayout timeRows;
    private LinearLayout fieldRows;
    private LinearLayout postingRows;
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
        amountRows = findViewById(R.id.amount_rows);
        timeRows = findViewById(R.id.time_rows);
        fieldRows = findViewById(R.id.field_rows);
        postingRows = findViewById(R.id.posting_rows);
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
                WiseBookDatabase.DEFAULT_USER_ID))
                .get(EntryDetailViewModel.class);

        toggleParse.setOnClickListener(view -> {
            parseExpanded = !parseExpanded;
            applyParseState();
        });
        findViewById(R.id.btn_correct).setOnClickListener(view -> pickCategory());
        findViewById(R.id.btn_void).setOnClickListener(view -> confirmVoid());

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

        fill(amountRows, detail.amountRows);
        fill(timeRows, detail.timeRows);
        fill(fieldRows, detail.fieldRows);
        fill(postingRows, detail.postingRows);
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
     * 「输入解析」的展开 / 收起。
     *
     * <p>标题上的箭头是唯一的折叠提示——按钮本身就是标题行，整行都可点。
     */
    private void applyParseState() {
        parsePanel.setVisibility(parseExpanded ? View.VISIBLE : View.GONE);
        toggleParse.setText(parseExpanded
                ? getString(R.string.detail_section_input) + " ▾"
                : getString(R.string.detail_section_input) + " ▸");
    }

    /**
     * 把一组字段铺进容器，<b>一行两列</b>。
     *
     * <p>每行两项而不是一项，是实机反馈的直接结果：一屏能装的信息翻倍，
     * 于是「不用下滑就能看到金额、时间、明细、入账和两个操作」这件事才成立。
     * 奇数个字段时最后一个独占一行（不硬凑，宁可留白）。
     */
    private void fill(LinearLayout container, List<EntryDetailViewModel.Row> rows) {
        container.removeAllViews();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        int gap = (int) (12 * getResources().getDisplayMetrics().density);

        LinearLayout currentRow = null;
        for (int i = 0; i < rows.size(); i++) {
            if (i % 2 == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setGravity(Gravity.TOP);
                container.addView(currentRow, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            View cell = inflater.inflate(R.layout.item_detail_cell, currentRow, false);
            ((TextView) cell.findViewById(R.id.row_label)).setText(rows.get(i).label);
            ((TextView) cell.findViewById(R.id.row_value)).setText(rows.get(i).value);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 2 == 1) {
                params.setMarginStart(gap);
            }
            currentRow.addView(cell, params);
        }
    }

    private void pickCategory() {
        CategoryPicker.show(this, viewModel.tree(), viewModel.direction(), viewModel.scheme(),
                viewModel::correct);
    }

    private void confirmVoid() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.ledger_action_void)
                .setMessage("这笔将从账本与统计里去掉，原始截图也会一并删除。"
                        + "你原来那句话和 AI 的理解过程会保留，方便回头看它当时是怎么读的。")
                .setNegativeButton("再想想", null)
                .setPositiveButton(R.string.ledger_action_void,
                        (dialog, which) -> viewModel.voidEntry())
                .show();
    }
}
