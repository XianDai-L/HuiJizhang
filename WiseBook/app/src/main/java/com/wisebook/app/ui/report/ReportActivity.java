package com.wisebook.app.ui.report;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.R;
import com.wisebook.app.WiseBookApp;
import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.report.MonthlyReport;
import com.wisebook.app.ui.DraftFormatter;
import com.wisebook.app.ui.EdgeToEdgeInsets;

import java.time.ZoneId;
import java.util.List;

/**
 * 报表（D2 §4 的 {@code ui/report}）。
 *
 * <p>内容按 D2 §9 的「最小可用」定：本月支出 / 收入合计、估算浮动提示、
 * 按一级分类汇总，外加转账的一键排除。预算预警属于 P5，不在这里。
 *
 * <p>分类明细的行是动态加进 {@link LinearLayout} 的，没有用 RecyclerView：
 * 一级分类最多十来个，而且这块内容不长、不需要回收复用。
 * 为一个静态小列表再引一套 Adapter 是负担而不是收益。
 */
public class ReportActivity extends AppCompatActivity {

    private ReportViewModel viewModel;

    private TextView range;
    private TextView expenseTotal;
    private TextView incomeTotal;
    private TextView estimateNote;
    private TextView transferNote;
    private TextView empty;
    private TextView expenseHeader;
    private TextView incomeHeader;
    private LinearLayout expenseRows;
    private LinearLayout incomeRows;
    private CheckBox excludeTransfer;

    /** 报表与分类树是两条异步流，先到的那条不该导致渲染出半成品，所以都缓存下来 */
    private MonthlyReport lastReport;
    private CategoryTree tree;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_report);
        EdgeToEdgeInsets.apply(findViewById(R.id.report_root));

        range = findViewById(R.id.range);
        expenseTotal = findViewById(R.id.expense_total);
        incomeTotal = findViewById(R.id.income_total);
        estimateNote = findViewById(R.id.estimate_note);
        transferNote = findViewById(R.id.transfer_note);
        empty = findViewById(R.id.empty);
        expenseHeader = findViewById(R.id.expense_header);
        incomeHeader = findViewById(R.id.income_header);
        expenseRows = findViewById(R.id.expense_rows);
        incomeRows = findViewById(R.id.income_rows);
        excludeTransfer = findViewById(R.id.exclude_transfer);

        WiseBookApp app = WiseBookApp.from(this);
        viewModel = new ViewModelProvider(this, new ReportViewModel.Factory(
                app.reportRepository(),
                app.categoryRepository(),
                app.databaseExecutor(),
                WiseBookDatabase.DEFAULT_USER_ID,
                ZoneId.systemDefault()))
                .get(ReportViewModel.class);

        // 先恢复勾选状态、再挂监听：反过来的话，恢复状态这一步本身就会触发一次重算
        excludeTransfer.setChecked(!viewModel.isIncludeTransfer());
        excludeTransfer.setOnCheckedChangeListener(
                (button, checked) -> viewModel.setIncludeTransfer(!checked));

        viewModel.report().observe(this, report -> {
            lastReport = report;
            render();
        });
        viewModel.tree().observe(this, loaded -> {
            tree = loaded;
            render();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 每次回到这一页都重算：从账本页撤销一笔再回来，看到的必须是新的数
        viewModel.load();
    }

    private void render() {
        if (lastReport == null) {
            return;
        }
        range.setText(getString(R.string.report_title) + " · " + lastReport.range.label());
        expenseTotal.setText(DraftFormatter.groupedAmount(lastReport.expenseCents));
        incomeTotal.setText(DraftFormatter.groupedAmount(lastReport.incomeCents));

        renderEstimateNote();
        renderTransferNote();

        boolean isEmpty = lastReport.isEmpty();
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        fillRows(expenseRows, expenseHeader, lastReport.expenseRows);
        fillRows(incomeRows, incomeHeader, lastReport.incomeRows);
    }

    private void renderEstimateNote() {
        if (lastReport.expenseEstimatedCount <= 0) {
            estimateNote.setVisibility(View.GONE);
            return;
        }
        estimateNote.setVisibility(View.VISIBLE);
        estimateNote.setText(lastReport.hasEstimateSpread()
                ? getString(R.string.report_estimate_note,
                        lastReport.expenseEstimatedCount, spreadText())
                : "含 " + lastReport.expenseEstimatedCount + " 笔估算");
    }

    /**
     * 浮动范围文本。
     *
     * <p>上下界对称时写成 {@code ±85.00 元}（D1 §2 的示例口径）；
     * 不对称时老实写成 {@code -50.00 元 ~ +80.00 元}——
     * 把不对称的区间硬说成「±」，是在替用户做了一个他没同意的假设。
     */
    private String spreadText() {
        long lower = lastReport.expenseCents - lastReport.expenseLowerCents;
        long upper = lastReport.expenseUpperCents - lastReport.expenseCents;
        if (lower == upper) {
            return "±" + DraftFormatter.groupedAmount(lower);
        }
        return "-" + DraftFormatter.groupedAmount(lower)
                + " ~ +" + DraftFormatter.groupedAmount(upper);
    }

    /**
     * 转账的那行提示。
     *
     * <p>措辞必须与合计口径一致：转账默认<b>已经</b>计入「本月支出」，
     * 所以这里说「含 N 笔；勾上排除转账就从上面的合计里减掉」。
     * 反过来说，如果数字里没算它、文案却说「含」，用户对不上账——
     * 之前就是这个毛病：勾选框只改了这句话的措辞，没改任何数字。
     */
    private void renderTransferNote() {
        if (lastReport.transferCount <= 0) {
            transferNote.setVisibility(View.GONE);
            return;
        }
        transferNote.setVisibility(View.VISIBLE);
        transferNote.setText(getString(
                lastReport.includeTransfer
                        ? R.string.report_transfer_included
                        : R.string.report_transfer_note,
                lastReport.transferCount,
                DraftFormatter.groupedAmount(lastReport.transferCents)));
    }

    private void fillRows(LinearLayout container, TextView header,
                          List<MonthlyReport.CategoryRow> rows) {
        container.removeAllViews();
        if (rows.isEmpty()) {
            header.setVisibility(View.GONE);
            return;
        }
        header.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (MonthlyReport.CategoryRow row : rows) {
            View view = inflater.inflate(R.layout.item_report_row, container, false);
            String name = DraftFormatter.categoryName(tree, row.rootCategoryId);
            ((TextView) view.findViewById(R.id.row_name)).setText(
                    (name.isEmpty() ? "分类 #" + row.rootCategoryId : name)
                            + "（" + row.entryCount + " 笔）");
            ((TextView) view.findViewById(R.id.row_share))
                    .setText(DraftFormatter.percent(row.sharePermille));
            ((TextView) view.findViewById(R.id.row_amount))
                    .setText(DraftFormatter.groupedAmount(row.totalCents));
            container.addView(view);
        }
    }
}
