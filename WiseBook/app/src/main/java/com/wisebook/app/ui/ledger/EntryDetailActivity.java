package com.wisebook.app.ui.ledger;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
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

/**
 * 账目详情。
 *
 * <p>原先账本列表点一条只弹「改正分类 / 撤销」两个动作，用户看不到这笔账的任何细节。
 * 现在点击进这一页：账目的全貌 + 它是怎么来的 + 两个动作。
 *
 * <p>「输入解析」默认只露出原始输入，其余解析内容点一下才展开——
 * 多数时候用户只想确认"这笔记对了吗"，一句原话就够了。
 */
public class EntryDetailActivity extends AppCompatActivity {

    private static final String EXTRA_ENTRY_ID = "entry_id";

    private EntryDetailViewModel viewModel;

    private TextView amount;
    private TextView directionCategory;
    private TextView rawLabel;
    private TextView rawText;
    private Button toggleParse;
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
        rawLabel = findViewById(R.id.raw_label);
        rawText = findViewById(R.id.raw_text);
        toggleParse = findViewById(R.id.btn_toggle_parse);
        parseDetails = findViewById(R.id.parse_details);
        amountRows = findViewById(R.id.amount_rows);
        timeRows = findViewById(R.id.time_rows);
        fieldRows = findViewById(R.id.field_rows);
        postingRows = findViewById(R.id.posting_rows);
        status = findViewById(R.id.status);

        WiseBookApp app = WiseBookApp.from(this);
        viewModel = new ViewModelProvider(this, new EntryDetailViewModel.Factory(
                app.entryRepository(),
                app.draftRepository(),
                app.settingsRepository(),
                app.categoryRepository(),
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

        if (detail.parseDetails == null || detail.parseDetails.isEmpty()) {
            toggleParse.setVisibility(View.GONE);
            parseDetails.setVisibility(View.GONE);
        } else {
            toggleParse.setVisibility(View.VISIBLE);
            parseDetails.setText(detail.parseDetails);
            applyParseState();
        }

        fill(amountRows, detail.amountRows);
        fill(timeRows, detail.timeRows);
        fill(fieldRows, detail.fieldRows);
        fill(postingRows, detail.postingRows);
    }

    private void applyParseState() {
        parseDetails.setVisibility(parseExpanded ? View.VISIBLE : View.GONE);
        toggleParse.setText(parseExpanded
                ? R.string.detail_collapse
                : R.string.detail_expand);
    }

    private void fill(LinearLayout container, List<EntryDetailViewModel.Row> rows) {
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (EntryDetailViewModel.Row row : rows) {
            View view = inflater.inflate(R.layout.item_detail_row, container, false);
            ((TextView) view.findViewById(R.id.row_label)).setText(row.label);
            ((TextView) view.findViewById(R.id.row_value)).setText(row.value);
            container.addView(view);
        }
    }

    private void pickCategory() {
        CategoryPicker.show(this, viewModel.tree(), viewModel.direction(), viewModel.scheme(),
                viewModel::correct);
    }

    private void confirmVoid() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.ledger_action_void)
                .setMessage("这笔将从账本与统计里去掉。你原来那句话和 AI 的理解过程会保留，"
                        + "方便回头看它当时是怎么读的。")
                .setNegativeButton("再想想", null)
                .setPositiveButton(R.string.ledger_action_void,
                        (dialog, which) -> viewModel.voidEntry())
                .show();
    }
}
