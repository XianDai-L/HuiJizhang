package com.wisebook.app.ui.ledger;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wisebook.app.R;
import com.wisebook.app.WiseBookApp;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.report.MonthRange;
import com.wisebook.app.ui.DraftFormatter;
import com.wisebook.app.ui.EdgeToEdgeInsets;
import com.wisebook.app.ui.widget.MonthPicker;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 账本列表（D2 §4 的 {@code ui/ledger}）。
 *
 * <p><b>点一条账目进详情页</b>（{@link EntryDetailActivity}）。
 * 原先是在列表上弹一个只有「改正分类 / 撤销」两个动作的框，用户看不到这笔账的任何细节——
 * 实机试用后改成进详情页：账目全貌 + 它是怎么来的 + 两个动作都在那里。
 *
 * <p>撤销入口不设期限——D1 明确要求「不设转正期限，撤销入口永久保留」，
 * 因为数据属于用户，不该给他"过期不能改"的体验。
 *
 * <p>2026-10-07 三处新增：<b>总计</b>（本月 / 全部账目）、<b>分类筛选</b>、<b>按天分组</b>。
 * 筛选与分组都只是"已经拿到的这批数据怎么摆"，所以它们都在这一层做，
 * 没有为筛选再写一条 SQL——数据库只查一次。
 */
public class LedgerActivity extends AppCompatActivity {

    private LedgerViewModel viewModel;
    private LedgerAdapter adapter;
    private TextView range;
    private TextView totalButton;
    private TextView summaryExpense;
    private TextView summaryIncome;
    private TextView empty;
    private LinearLayout filterChips;
    private RecyclerView list;

    /** 当前拿到的一批账目（未过滤）。切分类时在它上面重新过一遍，不必再查库 */
    private List<EntryEntity> loaded = new ArrayList<>();

    /** 当前选中的一级分类；null 表示「全部」 */
    private Long selectedRootId;

    /** 分类名要等分类树加载完才有，所以留一份引用给筛选标签用 */
    private CategoryTree tree;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_ledger);
        EdgeToEdgeInsets.apply(findViewById(R.id.ledger_root));

        WiseBookApp app = WiseBookApp.from(this);
        viewModel = new ViewModelProvider(this, new LedgerViewModel.Factory(
                app.entryRepository(),
                app.categoryRepository(),
                app.databaseExecutor(),
                app.currentLedgerId(),
                ZoneId.systemDefault()))
                .get(LedgerViewModel.class);

        range = findViewById(R.id.range);
        totalButton = findViewById(R.id.btn_total);
        summaryExpense = findViewById(R.id.summary_expense);
        summaryIncome = findViewById(R.id.summary_income);
        empty = findViewById(R.id.empty);
        filterChips = findViewById(R.id.filter_chips);
        list = findViewById(R.id.list);

        findViewById(R.id.btn_back).setOnClickListener(view -> finish());
        totalButton.setOnClickListener(view -> viewModel.toggleTotal());

        // 按钮上显示"当前按什么排"（而不是"点了会变成什么"）：状态与显示一致，
        // 用户不用先点一次才知道现在是什么口径
        Button sortButton = findViewById(R.id.btn_sort);
        sortButton.setOnClickListener(view -> viewModel.toggleSort());
        viewModel.sortOrder().observe(this, order -> sortButton.setText(
                order == LedgerViewModel.SortOrder.CREATED_AT
                        ? R.string.ledger_sort_created
                        : R.string.ledger_sort_occurred));

        viewModel.total().observe(this, total -> {
            totalButton.setTextColor(ContextCompat.getColor(this,
                    Boolean.TRUE.equals(total) ? R.color.brand : R.color.ink_2));
            renderRangeLabel();
        });
        viewModel.range().observe(this, monthRange -> renderRangeLabel());

        findViewById(R.id.btn_prev_month).setOnClickListener(view -> viewModel.shiftMonth(-1));
        findViewById(R.id.btn_next_month).setOnClickListener(view -> viewModel.shiftMonth(1));
        range.setOnClickListener(view -> {
            MonthRange current = viewModel.range().getValue();
            if (current == null) {
                return;
            }
            // 滚轮的初值是"正在看的那个月"，用户改的只是它的偏移
            MonthPicker.show(this, current.year, current.month,
                    (year, month) -> viewModel.selectMonth(year, month));
        });

        adapter = new LedgerAdapter(entry ->
                startActivity(EntryDetailActivity.intentFor(this, entry.entryId)));
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        viewModel.entries().observe(this, entries -> {
            loaded = entries == null ? new ArrayList<>() : entries;
            renderChips();
            applyFilter();
        });
        viewModel.tree().observe(this, loadedTree -> {
            tree = loadedTree;
            adapter.setTree(loadedTree);
            // 分类树晚于账目到达时，筛选标签上的名字要跟着从「分类 #3」换成「餐饮」
            renderChips();
        });

        viewModel.load();
    }

    /**
     * 顶栏中间那串文字。
     *
     * <p>「换了哪个月」和「是不是总计」都会改它，所以收成一个方法：
     * 分两处各写一次 {@code setText}，迟早会有一处忘了跟着改。
     */
    private void renderRangeLabel() {
        MonthRange current = viewModel.range().getValue();
        range.setText(Boolean.TRUE.equals(viewModel.total().getValue())
                ? getString(R.string.ledger_range_total)
                : (current == null ? "" : current.label()));
    }

    // ------------------------------------------------------------------ 筛选

    /**
     * 生成分类筛选标签。
     *
     * <p>只列<b>这批账目里实际出现过</b>的一级分类（加一个「全部」）：
     * 把分类树里的分类全摆出来，会有一半是点了没内容的空标签。
     *
     * <p>标签顺序按分类在列表中首次出现的顺序，不按 id 排——
     * 用户看到的是"这个月先花了什么"，顺序本身就带信息。
     */
    private void renderChips() {
        filterChips.removeAllViews();

        Map<Long, String> categories = new LinkedHashMap<>();
        for (EntryEntity entry : loaded) {
            if (!categories.containsKey(entry.rootCategoryId)) {
                String name = DraftFormatter.categoryName(tree, entry.rootCategoryId);
                categories.put(entry.rootCategoryId,
                        name.isEmpty() ? "分类 #" + entry.rootCategoryId : name);
            }
        }

        addChip(getString(R.string.ledger_filter_all), null);
        for (Map.Entry<Long, String> category : categories.entrySet()) {
            addChip(category.getValue(), category.getKey());
        }
    }

    private void addChip(String label, Long rootCategoryId) {
        boolean on = Objects.equals(selectedRootId, rootCategoryId);

        TextView chip = new TextView(this);
        chip.setText(label);
        // dimen 里的 text_* 已经是 sp，取出来是 px，所以按 PX 单位设回去
        chip.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.text_body_small));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(15), dp(8), dp(15), dp(8));
        chip.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        chip.setTextColor(ContextCompat.getColor(this, on ? R.color.white : R.color.ink_2));
        if (on) {
            chip.setTypeface(Typeface.DEFAULT_BOLD);
        }
        chip.setOnClickListener(view -> {
            selectedRootId = rootCategoryId;
            renderChips();
            applyFilter();
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(8));
        filterChips.addView(chip, params);
    }

    private void applyFilter() {
        List<EntryEntity> filtered = new ArrayList<>();
        for (EntryEntity entry : loaded) {
            if (selectedRootId == null || entry.rootCategoryId == selectedRootId) {
                filtered.add(entry);
            }
        }

        adapter.submit(LedgerAdapter.groupByDay(filtered, ZoneId.systemDefault()));

        boolean isEmpty = filtered.isEmpty();
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);

        // 合计跟着筛选走：既然用户在问"餐饮花了多少"，合计就该回答这个问题
        renderSummary(filtered);
    }

    /**
     * 顶部的合计。
     *
     * <p>口径与报表保持一致：<b>转账不计入支出与收入</b>（见 {@code MonthlyReport} 的说明）。
     * 两处口径若不一致，用户会看到"列表说 320、报表说 300"这种对不上的事。
     *
     * <p>金额不带「元」：单位已经写在卡片左上角的标签里，数字后面再跟一个「元」是重复的。
     */
    private void renderSummary(List<EntryEntity> entries) {
        long expense = 0L;
        long income = 0L;
        if (entries != null) {
            for (EntryEntity entry : entries) {
                if (entry.direction == Direction.EXPENSE) {
                    expense += entry.amountCents;
                } else if (entry.direction == Direction.INCOME) {
                    income += entry.amountCents;
                }
            }
        }
        summaryExpense.setText(DraftFormatter.groupedYuan(expense));
        summaryIncome.setText(DraftFormatter.groupedYuan(income));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
