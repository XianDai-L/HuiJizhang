package com.wisebook.app.ui;

import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.ConfidenceFlag;
import com.wisebook.app.domain.model.OccurredAtSource;
import com.wisebook.money.AmountParseResult;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把草稿渲染成给人看的文字。
 *
 * <p>金额格式化<b>直接复用 {@code money-parser} 的 {@link AmountParseResult#formatYuan}</b>，
 * 而不是在这里再写一遍「分转元」——那种地方一旦出现两份实现，
 * 迟早会出现「详情页显示 28.00、确认页显示 28」这种对不上的小事。
 */
public final class DraftFormatter {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final DateTimeFormatter FULL_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private DraftFormatter() {
    }

    /**
     * 账本行的主行：{@code 支出 28.50 元 · 交通>打车}。
     *
     * <p>金额固定两位小数（{@link #groupedAmount}），而不是 {@link #amountWithUnit} 那种
     * 跟着输入精度走的写法。原因是账本页顶部的合计本来就是 {@code 25.60 元}：
     * 同一屏上「合计 25.60」配「明细 12.8」看着就像两套算法算出来的。
     *
     * <p>草稿侧（待处理列表、确认页）继续用 {@code amountWithUnit}——
     * 那里括号里是用户原话的数字精度，「12.8」比「12.80」更接近他说的那个数。
     */
    public static String entryHeadline(EntryEntity entry, CategoryTree tree) {
        StringBuilder builder = new StringBuilder();
        builder.append(entry.direction == null ? "方向未定" : entry.direction.label());
        builder.append(' ').append(groupedAmount(entry.amountCents));
        builder.append(" · ").append(categoryPath(tree, entry.categoryId));
        return builder.toString();
    }

    /**
     * 账本行的次行：{@code 09-24 20:30 · 滴滴 · 微信 · 估算}。
     *
     * <p>估算要显式标出来：金额是「总价 45 里我只花了我的那份」这类折算出来的，
     * 用户扫列表时有权知道哪几笔不是原句里的原始数字。
     */
    public static String entrySubline(EntryEntity entry) {
        StringBuilder builder = new StringBuilder(dateTime(entry.occurredAt));
        if (entry.merchant != null && !entry.merchant.trim().isEmpty()) {
            builder.append(" · ").append(entry.merchant);
        }
        if (entry.paymentMethod != null) {
            builder.append(" · ").append(entry.paymentMethod.label());
        }
        if (entry.amountIsEstimated) {
            builder.append(" · 估算");
        }
        return builder.toString();
    }

    /** 账本列表里的「解析方式」标记：文字 / 图片 / 语音 */
    public static String sourceLabel(EntryEntity entry) {
        return entry.source == null ? "未知" : entry.source.label();
    }

    /**
     * 带千分位的金额，报表用：{@code 3,240.00 元}。
     *
     * <p>报表上的数字比列表上的更值得分隔符——用户是在这些数上做判断的。
     */
    public static String groupedAmount(long cents) {
        return String.format(Locale.CHINA, "%,d.%02d 元", cents / 100, Math.abs(cents % 100));
    }

    /** 千分比转成百分比文本，如 {@code 423} → {@code 42.3%} */
    public static String percent(int permille) {
        return String.format(Locale.CHINA, "%.1f%%", permille / 10.0);
    }

    /** 分类占比变化文本；分类树还没加载好时给一个空串，让界面自己决定怎么显示 */
    public static String categoryName(CategoryTree tree, long categoryId) {
        if (tree == null) {
            return "";
        }
        String path = tree.pathOf(categoryId);
        return path == null ? "" : path;
    }

    /** 不带单位的金额，如 {@code 28} ／ {@code 28.5}；为 null 时给一个诚实的占位 */
    public static String yuan(Long cents) {
        return cents == null ? "？" : AmountParseResult.formatYuan(cents);
    }

    /** 带单位的金额，如 {@code 28.5 元} */
    public static String amountWithUnit(Long cents) {
        return yuan(cents) + " 元";
    }

    public static String dateTime(Long millis, ZoneId zone) {
        if (millis == null) {
            return "时间未定";
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).format(DATE_TIME);
    }

    public static String dateTime(Long millis) {
        return dateTime(millis, ZoneId.systemDefault());
    }

    /** 完整到年份的时间，详情页用（列表上省掉年份，详情页不该省） */
    public static String fullDateTime(Long millis) {
        if (millis == null) {
            return "—";
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                .format(FULL_DATE_TIME);
    }

    /** 分类路径；未定时给「分类未定」而不是留空，免得界面上出现一片空白 */
    public static String categoryPath(CategoryTree tree, Long categoryId) {
        if (categoryId == null || tree == null) {
            return "分类未定";
        }
        String path = tree.pathOf(categoryId);
        return path == null ? "分类未定" : path;
    }

    /** 存疑标签的展示名；没有标签时返回空列表 */
    public static List<String> flagLabels(DraftEntity draft) {
        List<String> labels = new ArrayList<>();
        if (draft.confidenceFlags == null) {
            return labels;
        }
        for (ConfidenceFlag flag : draft.confidenceFlags) {
            labels.add(flag.label());
        }
        return labels;
    }

    /**
     * 发生时间的来源说明。
     *
     * <p>这三个档次值得让用户看见：{@code fallback} 意味着「原句里根本没提时间，
     * 我按你说话的那一刻记的」——用户看到这句话，才会想起来去改。
     */
    public static String occurredAtLabel(OccurredAtSource source) {
        if (source == null) {
            return "时间未定";
        }
        switch (source) {
            case EXPLICIT:
                return "原句明确写了";
            case INFERRED:
                return "从口语里推断的";
            case FALLBACK:
            default:
                return "原句没提时间，按当前时刻记的";
        }
    }

    /** 列表项的一行主标题：{@code 支出 28.5 元 · 餐饮>外卖} */
    public static String headline(DraftEntity draft, CategoryTree tree) {
        StringBuilder builder = new StringBuilder();
        builder.append(draft.direction == null ? "方向未定" : draft.direction.label());
        builder.append(' ').append(amountWithUnit(draft.amountCents));
        builder.append(" · ").append(categoryPath(tree, draft.categoryId));
        return builder.toString();
    }

    /** 列表项的次行：{@code 09-24 20:30 · 楼下小馆}；没商户时只留时间 */
    public static String subline(DraftEntity draft) {
        String time = dateTime(draft.occurredAt);
        if (draft.merchant == null || draft.merchant.trim().isEmpty()) {
            return time;
        }
        return time + " · " + draft.merchant;
    }
}
