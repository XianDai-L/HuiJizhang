package com.wisebook.app.ui.ledger;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.wisebook.app.R;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.ui.CategoryColors;
import com.wisebook.app.ui.DraftFormatter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 账本列表的适配器：<b>一天一个 item</b>。
 *
 * <p>分组由 {@link #groupByDay} 算好（它才知道排序口径），这里只负责渲染。
 * 一天一个 item，而不是「分组头一个 item + 每条账目各一个 item」：
 * 后者的卡片边界只能靠"给第一条加上圆角、最后一条加下圆角"拼出来，
 * 数据一变就得重算谁在头谁在尾。一天一个 item 之后，卡片边框天然把这一天圈住。
 *
 * <p>代价是当天的账目不走 RecyclerView 的复用（在 LinearLayout 里 addView）。
 * 一天的账目最多十来笔，这点开销换来的是布局上的确定性。
 *
 * <p>与首页「最近账目」用的是两个适配器，这是刻意的：首页要的是"最近发生了什么"，
 * 一条条平铺最好读；账本要的是"钱是怎么花掉的"，按天切开才看得出节奏。
 */
public class LedgerAdapter extends RecyclerView.Adapter<LedgerAdapter.DayHolder> {

    private static final DateTimeFormatter DAY_FORMAT =
            DateTimeFormatter.ofPattern("M 月 d 日 · EEE", Locale.CHINA);

    /** 一天：日期文案 + 当日支出小计 + 当天的账目 */
    public static final class DayGroup {

        final String label;
        final long expenseCents;
        final List<EntryEntity> entries;

        DayGroup(String label, long expenseCents, List<EntryEntity> entries) {
            this.label = label;
            this.expenseCents = expenseCents;
            this.entries = entries;
        }
    }

    private final List<DayGroup> groups = new ArrayList<>();
    private final Consumer<EntryEntity> onItemClick;
    private CategoryTree tree;

    public LedgerAdapter(Consumer<EntryEntity> onItemClick) {
        this.onItemClick = onItemClick;
    }

    /**
     * 分类树加载完成后调用。路径变了但账目没变，所以得手动通知重绘——
     * 与首页那个适配器是同一个理由。
     */
    public void setTree(CategoryTree tree) {
        this.tree = tree;
        if (!groups.isEmpty()) {
            notifyDataSetChanged();
        }
    }

    public void submit(List<DayGroup> newGroups) {
        groups.clear();
        if (newGroups != null) {
            groups.addAll(newGroups);
        }
        notifyDataSetChanged();
    }

    /**
     * 把一批账目按天切片。分组键用「发生时间」而不是记账时间：
     * 用户翻账本是想知道"那天花了什么"，而补记昨天的账时，
     * 记账时间会把两天的账目混到一起。
     */
    public static List<DayGroup> groupByDay(List<EntryEntity> entries, ZoneId zone) {
        List<DayGroup> result = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            return result;
        }
        String currentLabel = null;
        long dayExpense = 0L;
        List<EntryEntity> bucket = new ArrayList<>();

        for (EntryEntity entry : entries) {
            String label = dayLabel(entry, zone);
            if (currentLabel != null && !label.equals(currentLabel)) {
                result.add(new DayGroup(currentLabel, dayExpense, bucket));
                dayExpense = 0L;
                bucket = new ArrayList<>();
            }
            currentLabel = label;
            bucket.add(entry);
            if (entry.direction == Direction.EXPENSE) {
                dayExpense += entry.amountCents;
            }
        }
        result.add(new DayGroup(currentLabel, dayExpense, bucket));
        return result;
    }

    private static String dayLabel(EntryEntity entry, ZoneId zone) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(entry.occurredAt), zone)
                .format(DAY_FORMAT);
    }

    @NonNull
    @Override
    public DayHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new DayHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_day_group, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull DayHolder holder, int position) {
        DayGroup group = groups.get(position);

        holder.label.setText(group.label);
        holder.total.setText(group.expenseCents == 0L
                ? "—"
                : "−" + DraftFormatter.groupedYuan(group.expenseCents));

        holder.entries.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(holder.itemView.getContext());
        for (EntryEntity entry : group.entries) {
            View row = inflater.inflate(R.layout.item_entry, holder.entries, false);
            bindRow(row, entry);
            holder.entries.addView(row);
        }
    }

    @Override
    public int getItemCount() {
        return groups.size();
    }

    private void bindRow(View row, EntryEntity entry) {
        Context context = row.getContext();
        String categoryPath = DraftFormatter.categoryPath(tree, entry.categoryId);

        TextView category = row.findViewById(R.id.item_cat);
        TextView title = row.findViewById(R.id.item_title);
        TextView sub = row.findViewById(R.id.item_sub);
        TextView amount = row.findViewById(R.id.item_amount);
        TextView badge = row.findViewById(R.id.item_badge);

        // 主行优先给商户；没有商户时退回分类路径，免得标题空着而右侧金额孤零零地挂着
        String merchant = entry.merchant == null ? "" : entry.merchant.trim();
        title.setText(merchant.isEmpty()
                ? DraftFormatter.coloredPath(context, categoryPath)
                : merchant);
        sub.setText(DraftFormatter.entrySubline(context, tree, entry));

        category.setText(EntryAdapter.abbreviation(categoryPath));
        category.setTextColor(ContextCompat.getColor(context,
                CategoryColors.foregroundOf(entry.rootCategoryId)));
        category.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(context, CategoryColors.backgroundOf(entry.rootCategoryId))));

        amount.setText(entry.direction == Direction.INCOME
                ? "+" + DraftFormatter.groupedYuan(entry.amountCents)
                : "−" + DraftFormatter.groupedYuan(entry.amountCents));
        amount.setTextColor(ContextCompat.getColor(context,
                entry.direction == Direction.INCOME ? R.color.income : R.color.ink));

        badge.setVisibility(entry.autoPosted ? View.VISIBLE : View.GONE);

        row.setOnClickListener(view -> onItemClick.accept(entry));
    }

    static final class DayHolder extends RecyclerView.ViewHolder {

        final TextView label;
        final TextView total;
        final LinearLayout entries;

        DayHolder(@NonNull View itemView) {
            super(itemView);
            label = itemView.findViewById(R.id.day_label);
            total = itemView.findViewById(R.id.day_total);
            entries = itemView.findViewById(R.id.day_entries);
        }
    }
}
