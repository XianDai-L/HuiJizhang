package com.wisebook.app.ui.ledger;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wisebook.app.R;
import com.wisebook.app.WiseBookApp;
import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.ui.DraftFormatter;
import com.wisebook.app.ui.EdgeToEdgeInsets;

import java.time.ZoneId;
import java.util.List;

/**
 * 账本列表（D2 §4 的 {@code ui/ledger}）。
 *
 * <p><b>点一条账目进详情页</b>（{@link EntryDetailActivity}）。
 * 原先是在列表上弹一个只有「改正分类 / 撤销」两个动作的框，用户看不到这笔账的任何细节——
 * 实机试用后改成进详情页：账目全貌 + 它是怎么来的 + 两个动作都在那里。
 *
 * <p>撤销入口不设期限——D1 明确要求「不设转正期限，撤销入口永久保留」，
 * 因为数据属于用户，不该给他"过期不能改"的体验。
 */
public class LedgerActivity extends AppCompatActivity {

    private LedgerViewModel viewModel;
    private EntryAdapter adapter;
    private TextView summary;
    private TextView empty;

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
                WiseBookDatabase.DEFAULT_USER_ID,
                ZoneId.systemDefault()))
                .get(LedgerViewModel.class);

        ((TextView) findViewById(R.id.range))
                .setText(getString(R.string.ledger_title) + " · " + viewModel.range().label());
        summary = findViewById(R.id.summary);
        empty = findViewById(R.id.empty);

        adapter = new EntryAdapter(entry ->
                startActivity(EntryDetailActivity.intentFor(this, entry.entryId)));
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        viewModel.entries().observe(this, entries -> {
            adapter.submitList(entries);
            boolean isEmpty = entries == null || entries.isEmpty();
            empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            summary.setText(summarize(entries));
        });
        viewModel.tree().observe(this, adapter::setTree);

        viewModel.load();
    }

    /**
     * 列表顶部的合计。
     *
     * <p>口径与报表保持一致：<b>转账不计入支出与收入</b>（见 {@code MonthlyReport} 的说明）。
     * 两处口径若不一致，用户会看到"列表说 320、报表说 300"这种对不上的事。
     */
    private String summarize(List<EntryEntity> entries) {
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
        return "支出 " + DraftFormatter.groupedAmount(expense)
                + " · 收入 " + DraftFormatter.groupedAmount(income);
    }

}
