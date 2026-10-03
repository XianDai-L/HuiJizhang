package com.wisebook.app.ui.ledger;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.wisebook.app.R;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.ui.DraftFormatter;

import java.util.function.Consumer;

/**
 * 账本列表的适配器。
 *
 * <p>这里承担 D1 §5.3 的一个具体要求：<b>免确认直落的账目要带视觉标记</b>
 * （浅色底 + 「自动记」小标记），好让用户快速扫检"哪些是我没确认过就被记下的"。
 *
 * <p>标记用浅紫底而不是红色：自动落账是正常功能，不是错误。
 * 把它做得像警告，用户会以为出了问题。
 */
public class EntryAdapter extends ListAdapter<EntryEntity, EntryAdapter.Holder> {

    private final Consumer<EntryEntity> onItemClick;
    private CategoryTree tree;

    public EntryAdapter(Consumer<EntryEntity> onItemClick) {
        super(DIFF);
        this.onItemClick = onItemClick;
    }

    /** 分类树加载完成后调用。路径变了但账目没变，所以得手动通知重绘 */
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
                .inflate(R.layout.item_entry, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        EntryEntity entry = getItem(position);

        holder.title.setText(DraftFormatter.entryHeadline(entry, tree));
        holder.sub.setText(DraftFormatter.entrySubline(entry));
        holder.badge.setVisibility(entry.autoPosted ? View.VISIBLE : View.GONE);

        // 背景色走 setBackgroundColor、水波纹走 layout 里的 foreground，
        // 两者互不干扰（背景被占用后再设 selectableItemBackground 会把水波纹顶掉）
        holder.itemView.setBackgroundColor(entry.autoPosted
                ? ContextCompat.getColor(holder.itemView.getContext(),
                        R.color.auto_posted_background)
                : Color.TRANSPARENT);

        holder.itemView.setOnClickListener(view -> onItemClick.accept(entry));
    }

    static final class Holder extends RecyclerView.ViewHolder {

        final TextView title;
        final TextView sub;
        final TextView badge;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.item_title);
            sub = itemView.findViewById(R.id.item_sub);
            badge = itemView.findViewById(R.id.item_badge);
        }
    }

    private static final DiffUtil.ItemCallback<EntryEntity> DIFF =
            new DiffUtil.ItemCallback<EntryEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull EntryEntity oldItem,
                                               @NonNull EntryEntity newItem) {
                    return oldItem.entryId == newItem.entryId;
                }

                @Override
                public boolean areContentsTheSame(@NonNull EntryEntity oldItem,
                                                  @NonNull EntryEntity newItem) {
                    // 逐字段比：账目没有 updatedAt 这种"一改就变"的字段可用，
                    // 而"改正分类"恰恰只动其中一两个字段
                    return oldItem.amountCents == newItem.amountCents
                            && oldItem.categoryId == newItem.categoryId
                            && oldItem.direction == newItem.direction
                            && oldItem.autoPosted == newItem.autoPosted
                            && oldItem.occurredAt == newItem.occurredAt
                            && equals(oldItem.merchant, newItem.merchant);
                }

                private static boolean equals(String left, String right) {
                    return left == null ? right == null : left.equals(right);
                }
            };
}
