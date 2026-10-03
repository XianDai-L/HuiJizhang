package com.wisebook.app.data;

import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.dao.CategoryTotal;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.report.MonthRange;
import com.wisebook.app.domain.report.MonthlyReport;

import java.util.ArrayList;
import java.util.List;

/**
 * 报表的数据来源。
 *
 * <p>这一层很薄，只做一件事：<b>把数据库的两种投影翻译成领域层的输入</b>。
 * 聚合口径本身全在 {@link MonthlyReport} 里——那边不碰数据库，所以能秒级重跑单测。
 *
 * <p>为什么查两次而不是一次：
 * <ul>
 *   <li>{@code totalsBetween} 用一条 {@code GROUP BY} 拿到「一级分类 × 方向」的合计，
 *       报表的合计数与分类明细都由它来</li>
 *   <li>{@code findBetween} 拿明细，只为算估算浮动区间——
 *       区间边界（{@code amount_lower_cents} / {@code amount_upper_cents}）是逐笔的属性，
 *       {@code SUM} 不出来上下界（除非再写两个 {@code SUM} 列，那样反而更难读）</li>
 * </ul>
 *
 * <p><b>所有方法都是阻塞的</b>，调用方负责放到后台线程。
 */
public final class ReportRepository {

    private final WiseBookDatabase database;

    public ReportRepository(WiseBookDatabase database) {
        this.database = database;
    }

    /** 组装某个月的报表 */
    public MonthlyReport build(MonthRange range, boolean includeTransfer) {
        List<CategoryTotal> totals =
                database.entryDao().totalsBetween(range.fromInclusive, range.toExclusive);
        List<EntryEntity> entries =
                database.entryDao().findBetween(range.fromInclusive, range.toExclusive);
        return MonthlyReport.of(range, toSums(totals), toEstimates(entries), includeTransfer);
    }

    private static List<MonthlyReport.CategorySum> toSums(List<CategoryTotal> totals) {
        List<MonthlyReport.CategorySum> sums = new ArrayList<>(totals.size());
        for (CategoryTotal total : totals) {
            sums.add(new MonthlyReport.CategorySum(
                    total.rootCategoryId,
                    // 投影上不挂类型转换器，读出来的就是库里存的 code（见 CategoryTotal 的注释）
                    Direction.fromCode(total.directionCode),
                    total.totalCents,
                    total.entryCount));
        }
        return sums;
    }

    private static List<MonthlyReport.EstimatedAmount> toEstimates(List<EntryEntity> entries) {
        List<MonthlyReport.EstimatedAmount> estimates = new ArrayList<>();
        for (EntryEntity entry : entries) {
            if (entry.amountIsEstimated) {
                estimates.add(new MonthlyReport.EstimatedAmount(entry.direction, entry.amountCents,
                        entry.amountLowerCents, entry.amountUpperCents));
            }
        }
        return estimates;
    }
}
