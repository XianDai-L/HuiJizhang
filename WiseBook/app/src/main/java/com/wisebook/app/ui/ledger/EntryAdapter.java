package com.wisebook.app.ui.ledger;

import android.content.Context;
import android.content.res.ColorStateList;
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
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.ui.CategoryColors;
import com.wisebook.app.ui.DraftFormatter;

import java.util.function.Consumer;

/**
 * 账本行的适配器。首页的「最近账目」与账本页共用同一个实例类型。
 *
 * <p>这里承担 D1 §5.3 的一个具体要求：<b>免确认直落的账目要带视觉标记</b>
 * （浅色底 + 「自动记」小标记），好让用户快速扫检"哪些是我没确认过就被记下的"。
 *
 * <p>标记用浅紫底而不是红色：自动落账是正常功能，不是错误。
 * 把它做得像警告，用户会以为出了问题。
 *
 * <p>行内四段（2026-10-07）：分类色块 → 商户 / 副行 → 金额 → 「自动记」。
 * 金额从标题里拆出来单列，方向靠正负号表达，扫一列数字比读一句话快得多。
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
        Context context = holder.itemView.getContext();

        String categoryPath = DraftFormatter.categoryPath(tree, entry.categoryId);

        // 主行优先给商户；没有商户时退回分类路径，
        // 免得标题位置空着而右侧金额孤零零地挂着
        String merchant = entry.merchant == null ? "" : entry.merchant.trim();
        holder.title.setText(merchant.isEmpty()
                ? DraftFormatter.coloredPath(context, categoryPath)
                : merchant);
        holder.sub.setText(DraftFormatter.entrySubline(context, tree, entry));

        // 分类色块：颜色由一级分类决定（与报表页共用 CategoryColors）
        holder.category.setText(abbreviation(categoryPath));
        holder.category.setTextColor(ContextCompat.getColor(context,
                CategoryColors.foregroundOf(entry.rootCategoryId)));
        holder.category.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(context, CategoryColors.backgroundOf(entry.rootCategoryId))));

        holder.amount.setText(amountText(entry));
        holder.amount.setTextColor(ContextCompat.getColor(context,
                entry.direction == Direction.INCOME ? R.color.income : R.color.ink));

        holder.badge.setVisibility(entry.autoPosted ? View.VISIBLE : View.GONE);

        // 「自动记」只靠右侧那个 badge 表达，不再整行换底色：
        // 整行变紫之后，同屏几笔自动落账的账目会连成一片，反而看不清行与行的边界
        holder.itemView.setOnClickListener(view -> onItemClick.accept(entry));
    }

    /**
     * 金额带方向符号：支出 {@code −32.00}、收入 {@code +8,200.00}。
     *
     * <p>用符号而不是「支出 / 收入」两个字：一列数字右对齐时，符号是最省宽度的方向提示，
     * 而且和顶部合计的正负号是同一套语言。
     */
    private static String amountText(EntryEntity entry) {
        String sign = entry.direction == Direction.INCOME ? "+" : "−";
        return sign + DraftFormatter.groupedYuan(entry.amountCents);
    }

    /**
     * 分类色块上的两个字。
     *
     * <p>取一级分类名的前两个字：一个字（「餐」「购」）看不出是哪一类，
     * 整个「餐饮 › 咖啡」又塞不进 40dp 的方块。
     */
    /** 包内可见：账本页的分组适配器要用同一套取字规则，两处的色块必须长得一样 */
    static String abbreviation(String path) {
        if (path == null || path.isEmpty()) {
            return "?";
        }
        // 路径形如「餐饮 › 咖啡」，只取第一级
        String head = path.split("[>›]")[0].trim();
        if (head.isEmpty()) {
            return "?";
        }
        return head.length() <= 2 ? head : head.substring(0, 2);
    }

    static final class Holder extends RecyclerView.ViewHolder {

        final TextView category;
        final TextView title;
        final TextView sub;
        final TextView amount;
        final TextView badge;

        Holder(@NonNull View itemView) {
            super(itemView);
            category = itemView.findViewById(R.id.item_cat);
            title = itemView.findViewById(R.id.item_title);
            sub = itemView.findViewById(R.id.item_sub);
            amount = itemView.findViewById(R.id.item_amount);
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
                            && oldItem.rootCategoryId == newItem.rootCategoryId
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
