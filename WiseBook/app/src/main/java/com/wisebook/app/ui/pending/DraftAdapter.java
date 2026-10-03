package com.wisebook.app.ui.pending;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.wisebook.app.R;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.ui.DraftFormatter;

import java.util.List;
import java.util.function.Consumer;

/**
 * 待处理草稿列表的适配器。
 *
 * <p>用 {@link ListAdapter} + {@link DiffUtil} 而不是裸 {@code notifyDataSetChanged}：
 * 列表项带着「用户可能正在看的那一行」这种隐含状态，整表重绘会让滚动位置与
 * 点击反馈一起跳。DiffUtil 只更新真正变了的那几行。
 */
public class DraftAdapter extends ListAdapter<DraftEntity, DraftAdapter.Holder> {

    private final Consumer<DraftEntity> onItemClick;
    private CategoryTree tree;

    public DraftAdapter(Consumer<DraftEntity> onItemClick) {
        super(DIFF);
        this.onItemClick = onItemClick;
    }

    /** 分类树加载完成后调用。路径变了但草稿没变，所以得手动通知重绘 */
    public void setTree(CategoryTree tree) {
        this.tree = tree;
        if (getItemCount() > 0) {
            notifyItemRangeChanged(0, getItemCount());
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_draft, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        DraftEntity draft = getItem(position);
        holder.title.setText(DraftFormatter.headline(draft, tree));
        holder.sub.setText(DraftFormatter.subline(draft));

        List<String> flags = DraftFormatter.flagLabels(draft);
        holder.reason.setText(flags.isEmpty() ? "等你确认" : String.join(" · ", flags));
        holder.itemView.setOnClickListener(view -> onItemClick.accept(draft));
    }

    static final class Holder extends RecyclerView.ViewHolder {

        final TextView title;
        final TextView sub;
        final TextView reason;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.item_title);
            sub = itemView.findViewById(R.id.item_sub);
            reason = itemView.findViewById(R.id.item_reason);
        }
    }

    private static final DiffUtil.ItemCallback<DraftEntity> DIFF =
            new DiffUtil.ItemCallback<DraftEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull DraftEntity oldItem,
                                               @NonNull DraftEntity newItem) {
                    return oldItem.draftId == newItem.draftId;
                }

                @Override
                public boolean areContentsTheSame(@NonNull DraftEntity oldItem,
                                                  @NonNull DraftEntity newItem) {
                    // updatedAt 是每次改动都会刷新的字段，用它当"内容变了"的判据最省事
                    return oldItem.updatedAt == newItem.updatedAt
                            && oldItem.status == newItem.status;
                }
            };
}
