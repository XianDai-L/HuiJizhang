package com.wisebook.app.domain.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.wisebook.app.domain.model.Direction;

import org.junit.Test;

import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

/**
 * 报表口径测试。
 *
 * <p>报表是「口径」最密集的地方：转账算不算、估算怎么提示、占比按谁做分母——
 * 每一条都能悄悄错，而且错了之后界面上看不出任何异常，只是数字不对。
 * 所以这里把每一条口径都钉成一个用例。
 */
public class MonthlyReportTest {

    private static final MonthRange RANGE = MonthRange.of(2026, 9, ZoneId.of("Asia/Shanghai"));

    private static MonthlyReport.CategorySum sum(long rootId, Direction direction,
                                                 long cents, int count) {
        return new MonthlyReport.CategorySum(rootId, direction, cents, count);
    }

    private static MonthlyReport.EstimatedAmount estimated(Direction direction, long cents,
                                                           Long lower, Long upper) {
        return new MonthlyReport.EstimatedAmount(direction, cents, lower, upper);
    }

    private static MonthlyReport of(List<MonthlyReport.CategorySum> sums,
                                    List<MonthlyReport.EstimatedAmount> estimates) {
        return MonthlyReport.of(RANGE, sums, estimates, true);
    }

    // ------------------------------------------------------------------ 合计

    @Test
    public void totalsAreSummedPerDirection() {
        MonthlyReport report = of(List.of(
                sum(10, Direction.EXPENSE, 12_850L, 3),
                sum(20, Direction.EXPENSE, 30_000L, 1),
                sum(50, Direction.INCOME, 500_000L, 1)), Collections.emptyList());

        assertEquals(42_850L, report.expenseCents);
        assertEquals(500_000L, report.incomeCents);
    }

    @Test
    public void emptyMonthProducesZeroesAndNoRows() {
        MonthlyReport report = of(Collections.emptyList(), Collections.emptyList());

        assertTrue(report.isEmpty());
        assertTrue(report.expenseRows.isEmpty());
        assertTrue(report.incomeRows.isEmpty());
        assertEquals(0, report.transferCount);
        assertEquals(0L, report.transferCents);
        assertFalse(report.hasEstimateSpread());
    }

    // -------------------------------------------------------------- 分类明细

    @Test
    public void rowsAreGroupedByRootCategoryThenSortedByAmountDescending() {
        MonthlyReport report = of(List.of(
                sum(10, Direction.EXPENSE, 3_000L, 1),
                sum(20, Direction.EXPENSE, 7_000L, 1),
                sum(30, Direction.EXPENSE, 5_000L, 2)), Collections.emptyList());

        assertEquals(3, report.expenseRows.size());
        assertEquals(20L, report.expenseRows.get(0).rootCategoryId);
        assertEquals(30L, report.expenseRows.get(1).rootCategoryId);
        assertEquals(10L, report.expenseRows.get(2).rootCategoryId);
    }

    @Test
    public void sameRootCategoryIsMerged() {
        // 同一棵一级分类下的不同二级分类（餐饮>外卖、餐饮>堂食）必须并成一行，
        // 这正是「切换到简单分类方案时报表不变」的依据
        MonthlyReport report = of(List.of(
                sum(10, Direction.EXPENSE, 3_000L, 1),
                sum(10, Direction.EXPENSE, 2_000L, 2)), Collections.emptyList());

        assertEquals(1, report.expenseRows.size());
        assertEquals(5_000L, report.expenseRows.get(0).totalCents);
        assertEquals(3, report.expenseRows.get(0).entryCount);
    }

    @Test
    public void shareIsPermilleOfTheDirectionTotal() {
        MonthlyReport report = of(List.of(
                sum(10, Direction.EXPENSE, 3_000L, 1),
                sum(20, Direction.EXPENSE, 7_000L, 1)), Collections.emptyList());

        assertEquals(700, report.expenseRows.get(0).sharePermille);
        assertEquals(300, report.expenseRows.get(1).sharePermille);
    }

    @Test
    public void incomeHasItsOwnSharesNotTheExpenseOnes() {
        // 占比的分母必须是同方向的合计：收入的行不能拿支出的合计去算百分比
        MonthlyReport report = of(List.of(
                sum(10, Direction.EXPENSE, 9_000L, 1),
                sum(50, Direction.INCOME, 1_000L, 1)), Collections.emptyList());

        assertEquals(1, report.incomeRows.size());
        assertEquals(1000, report.incomeRows.get(0).sharePermille);
    }

    // ------------------------------------------------------------------ 转账

    @Test
    public void transferCountsAsExpenseByDefault() {
        // 实机反馈定下来的口径（HANDOFF 决策 22）：转账默认就计入「本月支出」，
        // 勾上「排除转账」才从合计里减掉——那个勾选框得是真的在改数字，而不是只改文案
        MonthlyReport report = of(List.of(
                sum(10, Direction.EXPENSE, 5_000L, 1),
                sum(60, Direction.TRANSFER, 90_000L, 1)), Collections.emptyList());

        assertEquals(95_000L, report.expenseCents);
        assertEquals(0L, report.incomeCents);
        assertEquals(1, report.transferCount);
        assertEquals(90_000L, report.transferCents);
        assertTrue("转账计入支出时，它的一级分类也要出现在「支出去向」里",
                report.expenseRows.stream().anyMatch(row -> row.rootCategoryId == 60L));
    }

    @Test
    public void excludingTransferTakesItOutOfTheExpenseTotal() {
        // 排除之后：合计要变小、明细行要消失，但笔数与金额仍然算得出来——
        // 界面得能说清"排除了几笔、多少钱"
        MonthlyReport report = MonthlyReport.of(RANGE, List.of(
                sum(10, Direction.EXPENSE, 5_000L, 1),
                sum(60, Direction.TRANSFER, 90_000L, 2)), Collections.emptyList(), false);

        assertEquals(5_000L, report.expenseCents);
        assertEquals("排除之后转账不该再出现在「支出去向」里", 1, report.expenseRows.size());
        assertEquals(2, report.transferCount);
        assertEquals(90_000L, report.transferCents);
        assertFalse(report.includeTransfer);
    }

    @Test
    public void estimatedTransferFollowsTheSameSwitch() {
        // 转账计入支出时，它的估算浮动也要跟着计入——否则「含 N 笔估算」
        // 与实际合计对不上（账目里有估算，合计却当它不存在）
        List<MonthlyReport.CategorySum> sums =
                List.of(sum(60, Direction.TRANSFER, 90_000L, 1));
        List<MonthlyReport.EstimatedAmount> estimates = List.of(
                estimated(Direction.TRANSFER, 90_000L, 80_000L, 100_000L));

        MonthlyReport included = MonthlyReport.of(RANGE, sums, estimates, true);
        assertEquals(1, included.expenseEstimatedCount);
        assertEquals(80_000L, included.expenseLowerCents);
        assertEquals(100_000L, included.expenseUpperCents);

        MonthlyReport excluded = MonthlyReport.of(RANGE, sums, estimates, false);
        assertEquals(0, excluded.expenseEstimatedCount);
        assertEquals(0L, excluded.expenseCents);
    }

    // ------------------------------------------------------------------ 估算

    @Test
    public void estimateSpreadWidensTheExpenseTotalBothWays() {
        // 「三百五」这种折算值 300 元、区间 [250, 350] → 合计落在 [250, 350]
        MonthlyReport report = of(
                List.of(sum(10, Direction.EXPENSE, 30_000L, 1)),
                List.of(estimated(Direction.EXPENSE, 30_000L, 25_000L, 35_000L)));

        assertEquals(1, report.expenseEstimatedCount);
        assertEquals(25_000L, report.expenseLowerCents);
        assertEquals(35_000L, report.expenseUpperCents);
        assertTrue(report.hasEstimateSpread());
    }

    @Test
    public void asymmetricSpreadIsKeptAsymmetric() {
        // 「差不多 300，可能少 50、也可能多 80」→ [250, 380]。
        // 上下界不对称就老实不对称，硬说成「±」是在替用户做一个他没同意的假设
        MonthlyReport report = of(
                List.of(sum(10, Direction.EXPENSE, 30_000L, 1)),
                List.of(estimated(Direction.EXPENSE, 30_000L, 25_000L, 38_000L)));

        assertEquals(25_000L, report.expenseLowerCents);
        assertEquals(38_000L, report.expenseUpperCents);
    }

    @Test
    public void estimatedWithoutBoundsOnlyCountsAndDoesNotFakePrecision() {
        // 「三十左右」只有折算值、没有上下界：只计笔数，不制造一个假的区间
        MonthlyReport report = of(
                List.of(sum(10, Direction.EXPENSE, 3_000L, 1)),
                List.of(estimated(Direction.EXPENSE, 3_000L, null, null)));

        assertEquals(1, report.expenseEstimatedCount);
        assertEquals(3_000L, report.expenseLowerCents);
        assertEquals(3_000L, report.expenseUpperCents);
        assertFalse(report.hasEstimateSpread());
    }

    @Test
    public void incomeEstimatesDoNotTouchTheExpenseSpread() {
        // D1 §2 的示例口径只给支出加浮动提示，收入侧不跟着加负担
        MonthlyReport report = of(
                List.of(sum(10, Direction.EXPENSE, 30_000L, 1),
                        sum(50, Direction.INCOME, 10_000L, 1)),
                List.of(estimated(Direction.INCOME, 10_000L, 5_000L, 20_000L)));

        assertEquals(0, report.expenseEstimatedCount);
        assertEquals(30_000L, report.expenseLowerCents);
        assertEquals(30_000L, report.expenseUpperCents);
        assertFalse(report.hasEstimateSpread());
    }

    // ---------------------------------------------------------------- 健壮性

    @Test
    public void unrecognizedDirectionCodeIsSkipped() {
        // CategoryTotal 的方向是字符串（投影上不挂转换器）。库里若出现无法识别的 code，
        // Direction.fromCode 会返回 null——报表不能因此崩掉，只是少算那一行
        MonthlyReport report = MonthlyReport.of(RANGE, List.of(
                new MonthlyReport.CategorySum(99, null, 9_999L, 3),
                sum(10, Direction.EXPENSE, 1_000L, 1)), Collections.emptyList(), true);

        assertEquals(1_000L, report.expenseCents);
        assertEquals(1, report.expenseRows.size());
    }

    @Test
    public void nullInputsAreTreatedAsEmpty() {
        MonthlyReport report = MonthlyReport.of(RANGE, null, null, true);
        assertTrue(report.isEmpty());
    }
}
