package com.wisebook.app.ui.report;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.data.CategoryRepository;
import com.wisebook.app.data.ReportRepository;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.report.MonthRange;
import com.wisebook.app.domain.report.MonthlyReport;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;

/**
 * 报表的状态持有者。
 *
 * <p>与账本列表不同，报表不是「观察数据库」而是「按需算一次」——
 * 报表要在内存里做聚合、还要受「是否排除转账」这个开关影响，
 * 不是一条 SQL 能直接映射成界面的东西。所以这里用一个普通 {@code LiveData}，
 * 由界面在每次进入页面时触发一次重算（{@link #load()}），
 * 保证从账本页撤销了一笔再回来，这边看到的是新的数。
 */
public class ReportViewModel extends ViewModel {

    private final ReportRepository reportRepository;
    private final CategoryRepository categoryRepository;
    private final ExecutorService executor;
    private final long userId;
    private final ZoneId zone;
    /** 当前看的月份。切月会改它，所以不是 final */
    private MonthRange monthRange;

    private final MutableLiveData<MonthlyReport> report = new MutableLiveData<>();
    private final MutableLiveData<CategoryTree> tree = new MutableLiveData<>();

    /** 默认<b>不</b>排除转账：D1 §8 决策 7 的口径是「转账参与统计、可一键排除」 */
    private boolean includeTransfer = true;

    /** 是否看「全部账目」。与账本页那个总计开关是同一件事，两页的表现要对得上 */
    private boolean total;

    public ReportViewModel(ReportRepository reportRepository,
                           CategoryRepository categoryRepository,
                           ExecutorService executor, long userId, ZoneId zone) {
        this.reportRepository = reportRepository;
        this.categoryRepository = categoryRepository;
        this.executor = executor;
        this.userId = userId;
        this.zone = zone;
        this.monthRange = MonthRange.current(zone);
    }

    public MonthRange range() {
        return monthRange;
    }

    public boolean isTotal() {
        return total;
    }

    /** 当前生效的范围：本月，或者全部账目 */
    private MonthRange effectiveRange() {
        return total ? MonthRange.all() : monthRange;
    }

    /** 在「本月」和「总计」之间切，并立刻重算 */
    public void toggleTotal() {
        total = !total;
        load();
    }

    /**
     * 往前 / 往后翻一个月。
     *
     * <p>翻月自动退出「总计」：总计没有"上一个月"可言，用户点箭头说明他想按月看，
     * 这时候还停在总计上会让人以为按钮坏了。
     */
    public void shiftMonth(int delta) {
        LocalDate anchor = LocalDate.of(monthRange.year, monthRange.month, 1).plusMonths(delta);
        selectMonth(anchor.getYear(), anchor.getMonthValue());
    }

    /** 直接跳到指定的年月（顶栏点中间那串年月之后选的结果） */
    public void selectMonth(int year, int month) {
        monthRange = MonthRange.of(year, month, zone);
        total = false;
        load();
    }

    public boolean isIncludeTransfer() {
        return includeTransfer;
    }

    public LiveData<MonthlyReport> report() {
        return report;
    }

    public LiveData<CategoryTree> tree() {
        return tree;
    }

    /** <b>异步</b>重算一次报表 */
    public void load() {
        executor.execute(() -> {
            tree.postValue(categoryRepository.loadTree(userId));
            report.postValue(reportRepository.build(effectiveRange(), includeTransfer));
        });
    }

    /** 切换「是否把转账算进来」，并立刻重算 */
    public void setIncludeTransfer(boolean include) {
        if (includeTransfer == include) {
            return;
        }
        includeTransfer = include;
        load();
    }

    /** 手动构造依赖的 ViewModel 工厂（P1 不用依赖注入框架，D2 §1） */
    public static final class Factory implements ViewModelProvider.Factory {

        private final ReportRepository reportRepository;
        private final CategoryRepository categoryRepository;
        private final ExecutorService executor;
        private final long userId;
        private final ZoneId zone;

        public Factory(ReportRepository reportRepository, CategoryRepository categoryRepository,
                       ExecutorService executor, long userId, ZoneId zone) {
            this.reportRepository = reportRepository;
            this.categoryRepository = categoryRepository;
            this.executor = executor;
            this.userId = userId;
            this.zone = zone;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new ReportViewModel(reportRepository, categoryRepository, executor,
                    userId, zone);
        }
    }
}
