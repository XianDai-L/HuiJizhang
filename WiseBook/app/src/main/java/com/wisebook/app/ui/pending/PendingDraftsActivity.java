package com.wisebook.app.ui.pending;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wisebook.app.R;
import com.wisebook.app.WiseBookApp;
import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.ui.EdgeToEdgeInsets;
import com.wisebook.app.ui.confirm.ConfirmActivity;

/**
 * 待处理草稿列表：确认页的入口。
 *
 * <p>这一页顺手证明了一件事：<b>草稿必须落库</b>。列表是跨页面、跨进程重启都存在的，
 * 靠的是数据库里的行；要是草稿只活在内存里，从对话页跳到确认页就得靠 Intent 把整个对象塞过去，
 * 一旦被系统回收就什么都没有了。
 */
public class PendingDraftsActivity extends AppCompatActivity {

    private PendingDraftsViewModel viewModel;
    private DraftAdapter adapter;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_pending_drafts);
        EdgeToEdgeInsets.apply(findViewById(R.id.pending_root));

        WiseBookApp app = WiseBookApp.from(this);
        viewModel = new ViewModelProvider(this, new PendingDraftsViewModel.Factory(
                app.draftRepository(),
                app.categoryRepository(),
                app.databaseExecutor(),
                WiseBookDatabase.DEFAULT_USER_ID))
                .get(PendingDraftsViewModel.class);

        empty = findViewById(R.id.empty);
        adapter = new DraftAdapter(draft -> startActivity(
                ConfirmActivity.intentFor(this, draft.draftId)));

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        viewModel.drafts().observe(this, drafts -> {
            adapter.submitList(drafts);
            boolean isEmpty = drafts == null || drafts.isEmpty();
            empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        });
        viewModel.tree().observe(this, adapter::setTree);

        viewModel.loadTree();
    }
}
