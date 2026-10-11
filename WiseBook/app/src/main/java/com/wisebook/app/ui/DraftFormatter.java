package com.wisebook.app.ui;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;

import androidx.core.content.ContextCompat;

import com.wisebook.app.R;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.ConfidenceFlag;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.OccurredAtSource;
import com.wisebook.money.AmountParseResult;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** 方向的口语说法，用在列表副行上（{@code 支出 · 餐饮 › 咖啡}） */
    public static String directionLabel(Direction direction) {
        if (direction == null) {
            return "方向未定";
        }
        switch (direction) {
            case INCOME:
                return "收入";
            case TRANSFER:
                return "转账";
            case EXPENSE:
            default:
                return "支出";
        }
    }

    /**
     * 账本行的副行：{@code 10-07 08:42 · 支出 · 餐饮 › 咖啡 · 微信}。
     *
     * <p>2026-10-07 改：原来这里拼的是「时间 · <b>商户</b> · 支付方式」，而主行显示的也是商户
     * （没有商户时才退回分类路径），于是同一个名字在一行里出现两次。
     * 现在副行改给<b>方向 + 分类路径</b>——这两样恰恰是主行没有的信息，
     * 一眼就能看出「这笔是支出还是转账、归到哪一类」。
     *
     * <p>分类路径里的层级分隔符会染色（见 {@link #coloredPath}），
     * 与详情页、选分类弹窗保持同一种读法。
     */
    public static CharSequence entrySubline(Context context, CategoryTree tree, EntryEntity entry) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        builder.append(dateTime(entry.occurredAt));
        builder.append(" · ").append(directionLabel(entry.direction));

        builder.append(" · ");
        int pathStart = builder.length();
        builder.append(categoryPath(tree, entry.categoryId));
        colorSeparators(context, builder, pathStart);

        if (entry.paymentMethod != null) {
            builder.append(" · ").append(entry.paymentMethod.label());
        }
        if (entry.amountIsEstimated) {
            builder.append(" · 估算");
        }
        return builder;
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

    /**
     * 带千分位、不带单位的金额，如 {@code 3,240.00}。
     *
     * <p>给「单位已经写在旁边」的地方用：首页概览条要的是 {@code ¥3,240.00}，
     * 用 {@link #groupedAmount} 会得到 {@code 3,240.00 元}，前面再加 ¥ 就成了
     * 「¥3,240.00 元」——一个数字两个单位。分位与正负号的处理与 groupedAmount 一致。
     */
    public static String groupedYuan(long cents) {
        return String.format(Locale.CHINA, "%,d.%02d", cents / 100, Math.abs(cents % 100));
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

    /** 分类路径里的层级分隔符。两种写法都要认：库里可能存的是全角箭头 */
    private static final Pattern PATH_SEPARATOR = Pattern.compile("[>›]");

    /**
     * 把分类路径里的分隔符染成品牌色，如 {@code 餐饮 > 咖啡}。
     *
     * <p>那个符号是<b>层级标记、不是内容</b>。染成蓝色之后，用户扫过去读到的是
     * 分类名，符号退到背景里；又不至于完全看不见——它仍在告诉人"这是一条路径"。
     */
    public static CharSequence coloredPath(Context context, String path) {
        if (path == null || path.isEmpty()) {
            return path == null ? "" : path;
        }
        SpannableString styled = new SpannableString(path);
        int color = ContextCompat.getColor(context, R.color.brand);
        Matcher matcher = PATH_SEPARATOR.matcher(path);
        while (matcher.find()) {
            styled.setSpan(new ForegroundColorSpan(color), matcher.start(), matcher.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled;
    }

    /**
     * 给已经拼好的整行文字里、从 {@code from} 开始的那段分类路径染分隔符。
     *
     * <p>副行是「时间 · 方向 · 路径 · 支付」拼起来的，不能把整行交给 {@link #coloredPath}
     * ——那会把时间里的冒号之类的也算进去。所以这里只处理路径那一段。
     */
    private static void colorSeparators(Context context, SpannableStringBuilder builder, int from) {
        int color = ContextCompat.getColor(context, R.color.brand);
        Matcher matcher = PATH_SEPARATOR.matcher(builder.toString());
        while (matcher.find()) {
            if (matcher.start() >= from) {
                builder.setSpan(new ForegroundColorSpan(color), matcher.start(), matcher.end(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
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
