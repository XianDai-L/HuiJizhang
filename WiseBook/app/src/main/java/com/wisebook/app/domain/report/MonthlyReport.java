package com.wisebook.app.domain.report;

import com.wisebook.app.domain.model.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 月度报表的组装（D1 §2 的展示口径 + §6.2 的分类挂载规则）。
 *
 * <p>刻意做成<b>纯函数</b>：输入两个列表、输出一份结果，不碰数据库、不碰 Android。
 * 报表是「口径」最密集的地方——转账算不算、估算怎么提示、占比按谁做分母——
 * 这些判断只有放在能秒级重跑单测的地方才不会悄悄错。
 *
 * <p>几个口径上的取舍，都在这里显式声明：
 *
 * <ul>
 *   <li><b>按一级分类聚合</b>（{@code root_category_id}）。这正是 D1 §6.2 里
 *       「简单 / 标准分类方案自由切换、账目零改动」能成立的原因：
 *       无论用户当时选到了几级分类，报表口径都不变</li>
 *   <li><b>转账默认计入支出，勾选「排除转账」才减掉</b>（2026-10-02 修订，见 HANDOFF 决策 22）。
 *       D1 §8 决策 7 说「转账保留、参与收支统计、报表可一键排除」，这里把
 *       「参与统计」落定为<b>默认就计入「本月支出」</b>。所以 {@code includeTransfer}
 *       是真的在改数字、而不是只改一句提示文案——用户勾一下应当看到合计变小。
 *       转账同时<b>始终</b>单独统计笔数与金额，界面才有东西可以说清这笔钱去哪了</li>
 *   <li><b>占比用千分比整数</b>。{@code int} 千分比既够精确又完全避免浮点，
 *       与全项目「钱只用整数」的取向一致</li>
 *   <li><b>浮动范围只对支出提示</b>。D1 §2 给的示例就是支出（「本月支出 3,240 元
 *       （含 8 笔估算，浮动约 ±85 元）」），收入侧不跟着加负担</li>
 * </ul>
 */
public final class MonthlyReport {

    /** 输入：按「一级分类 × 方向」聚合后的一行（来自 SQL 聚合） */
    public static final class CategorySum {

        public final long rootCategoryId;
        public final Direction direction;
        public final long totalCents;
        public final int entryCount;

        public CategorySum(long rootCategoryId, Direction direction, long totalCents,
                           int entryCount) {
            this.rootCategoryId = rootCategoryId;
            this.direction = direction;
            this.totalCents = totalCents;
            this.entryCount = entryCount;
        }
    }

    /** 输入：一笔估算金额及其区间（只用于算浮动范围） */
    public static final class EstimatedAmount {

        public final Direction direction;
        public final long cents;
        public final Long lowerCents;
        public final Long upperCents;

        public EstimatedAmount(Direction direction, long cents, Long lowerCents, Long upperCents) {
            this.direction = direction;
            this.cents = cents;
            this.lowerCents = lowerCents;
            this.upperCents = upperCents;
        }
    }

    /** 输出：报表里的一行 */
    public static final class CategoryRow {

        public final long rootCategoryId;
        public final long totalCents;
        public final int entryCount;
        /** 占同方向合计的千分比（整数） */
        public final int sharePermille;

        CategoryRow(long rootCategoryId, long totalCents, int entryCount, int sharePermille) {
            this.rootCategoryId = rootCategoryId;
            this.totalCents = totalCents;
            this.entryCount = entryCount;
            this.sharePermille = sharePermille;
        }
    }

    public final MonthRange range;
    public final boolean includeTransfer;
    public final long expenseCents;
    public final long incomeCents;
    public final List<CategoryRow> expenseRows;
    public final List<CategoryRow> incomeRows;
    /** 本月估算的<b>支出</b>笔数 */
    public final int expenseEstimatedCount;
    /** 支出合计的下界；无估算时与 {@link #expenseCents} 相同 */
    public final long expenseLowerCents;
    /** 支出合计的上界；无估算时与 {@link #expenseCents} 相同 */
    public final long expenseUpperCents;
    /** 本月转账笔数（无论是否排除，都如实统计出来） */
    public final int transferCount;
    /** 本月转账合计（同样无论是否排除都算出来，界面才有东西可说） */
    public final long transferCents;

    private MonthlyReport(MonthRange range, boolean includeTransfer, long expenseCents,
                          long incomeCents, List<CategoryRow> expenseRows,
                          List<CategoryRow> incomeRows, int expenseEstimatedCount,
                          long expenseLowerCents, long expenseUpperCents, int transferCount,
                          long transferCents) {
        this.range = range;
        this.includeTransfer = includeTransfer;
        this.expenseCents = expenseCents;
        this.incomeCents = incomeCents;
        this.expenseRows = Collections.unmodifiableList(expenseRows);
        this.incomeRows = Collections.unmodifiableList(incomeRows);
        this.expenseEstimatedCount = expenseEstimatedCount;
        this.expenseLowerCents = expenseLowerCents;
        this.expenseUpperCents = expenseUpperCents;
        this.transferCount = transferCount;
        this.transferCents = transferCents;
    }

    public static MonthlyReport of(MonthRange range, List<CategorySum> sums,
                                   List<EstimatedAmount> estimates, boolean includeTransfer) {
        Map<Long, MutableRow> expenseMap = new LinkedHashMap<>();
        Map<Long, MutableRow> incomeMap = new LinkedHashMap<>();
        long expense = 0L;
        long income = 0L;
        int transferCount = 0;
        long transferCents = 0L;

        for (CategorySum sum : sums == null ? Collections.<CategorySum>emptyList() : sums) {
            if (sum == null || sum.direction == null) {
                continue;
            }
            if (sum.direction == Direction.TRANSFER) {
                // 转账无论是否排除都要单独统计：界面要能说清"含/排除了几笔、多少钱"
                transferCount += sum.entryCount;
                transferCents += sum.totalCents;
                if (!includeTransfer) {
                    continue;
                }
                // 默认口径：转账也是资金流出，计入「本月支出」，并出现在支出去向的明细里。
                // 转账复用支出分类树（决策 27），所以这里的 rootCategoryId 本来就是支出侧的一级
                expense += sum.totalCents;
                accumulate(expenseMap, sum);
                continue;
            }
            if (sum.direction == Direction.EXPENSE) {
                expense += sum.totalCents;
                accumulate(expenseMap, sum);
            } else if (sum.direction == Direction.INCOME) {
                income += sum.totalCents;
                accumulate(incomeMap, sum);
            }
        }

        int estimatedCount = 0;
        long lowerDeviation = 0L;
        long upperDeviation = 0L;
        for (EstimatedAmount estimate : estimates == null
                ? Collections.<EstimatedAmount>emptyList() : estimates) {
            // 只对支出提示浮动（D1 §2 的示例口径），收入侧不跟着加负担。
            // 转账在计入支出时也得算进来，否则「含 N 笔估算」与实际合计对不上
            if (estimate == null) {
                continue;
            }
            if (estimate.direction != Direction.EXPENSE
                    && !(includeTransfer && estimate.direction == Direction.TRANSFER)) {
                continue;
            }
            estimatedCount++;
            if (estimate.lowerCents != null) {
                lowerDeviation += Math.max(0L, estimate.cents - estimate.lowerCents);
            }
            if (estimate.upperCents != null) {
                upperDeviation += Math.max(0L, estimate.upperCents - estimate.cents);
            }
        }

        return new MonthlyReport(
                range,
                includeTransfer,
                expense,
                income,
                toRows(expenseMap, expense),
                toRows(incomeMap, income),
                estimatedCount,
                Math.max(0L, expense - lowerDeviation),
                expense + upperDeviation,
                transferCount,
                transferCents);
    }

    /** 没有任何账目 */
    public boolean isEmpty() {
        return expenseCents == 0L && incomeCents == 0L;
    }

    /** 支出侧是否有值得提示的浮动 */
    public boolean hasEstimateSpread() {
        return expenseEstimatedCount > 0
                && (expenseLowerCents != expenseCents || expenseUpperCents != expenseCents);
    }

    // ------------------------------------------------------------------ 内部

    private static void accumulate(Map<Long, MutableRow> rows, CategorySum sum) {
        MutableRow row = rows.get(sum.rootCategoryId);
        if (row == null) {
            row = new MutableRow(sum.rootCategoryId);
            rows.put(sum.rootCategoryId, row);
        }
        row.totalCents += sum.totalCents;
        row.entryCount += sum.entryCount;
    }

    private static List<CategoryRow> toRows(Map<Long, MutableRow> rows, long directionTotal) {
        List<CategoryRow> result = new ArrayList<>(rows.size());
        for (MutableRow row : rows.values()) {
            result.add(new CategoryRow(row.rootCategoryId, row.totalCents, row.entryCount,
                    directionTotal <= 0L ? 0 : (int) (row.totalCents * 1000L / directionTotal)));
        }
        // 金额降序；金额相同时按 id 升序，保证两次查询的顺序是确定的
        result.sort(Comparator.comparingLong((CategoryRow row) -> -row.totalCents)
                .thenComparingLong(row -> row.rootCategoryId));
        return result;
    }

    private static final class MutableRow {

        final long rootCategoryId;
        long totalCents;
        int entryCount;

        MutableRow(long rootCategoryId) {
            this.rootCategoryId = rootCategoryId;
        }
    }
}
