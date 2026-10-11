package com.wisebook.app.ui.ledger;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.data.CategoryRepository;
import com.wisebook.app.data.EntryRepository;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.report.MonthRange;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 账本列表的状态持有者。
 *
 * <p>列表直接观察数据库，所以撤销或改正之后<b>列表会自己少一行 / 换一行</b>，
 * 不需要手工重查——用户从详情页返回时，看到的已经是改过的数据。
 *
 * <p><b>这里没有任何"改数据"的方法。</b>改正与撤销都搬到了详情页
 * （{@link EntryDetailActivity} / {@link EntryDetailViewModel}）：列表只负责把人送过去。
 *
 * <p>排序有两个口径：「发生时间」回答"这笔钱什么时候花的"，
 * 「记账时间」回答"我什么时候记的它"。两种问题都真实存在（补记昨天的账时，
 * 刚记的那笔会排在发生时间很靠后的位置，按记账时间才看得到自己刚做了什么）。
 *
 * <p>2026-10-07 补上<b>切月</b>：在这之前 {@code range} 是构造时定死的
 * {@code MonthRange.current()}，界面只能显示"现在这个月"，翻不了页。
 */
public class LedgerViewModel extends ViewModel {

    /** 账本排序口径 */
    public enum SortOrder {
        /** 按发生时间倒序（默认；与 D1 §6.2 的月度口径一致） */
        OCCURRED_AT,
        /** 按记账时间倒序（账目行的创建时刻） */
        CREATED_AT
    }

    private final EntryRepository entryRepository;
    private final CategoryRepository categoryRepository;
    private final ExecutorService executor;
    private final long userId;
    private final ZoneId zone;

    private final MutableLiveData<CategoryTree> tree = new MutableLiveData<>();

    /**
     * 列表的查询条件：排序口径 + 时间范围 + 看哪个（月）。
     *
     * <p>把三个维度收进一个对象，是为了让 {@link #entries()} 只做一次 switchMap。
     * 分散成几个独立 LiveData 的话，"排序变了""范围变了""换月了"要各写一条分支，
     * 而那几条分支的查询组合是同一套。
     */
    private static final class Query {

        final SortOrder order;
        final boolean total;
        /** 当前看的是哪个月。总计模式下它仍然有效，切回来时不用重新算 */
        final MonthRange range;

        Query(SortOrder order, boolean total, MonthRange range) {
            this.order = order;
            this.total = total;
            this.range = range;
        }
    }

    /** 初值要等拿到 zone 才算得出来（当前是哪个月），所以放在构造器里赋值 */
    private final MutableLiveData<Query> query;

    public LedgerViewModel(EntryRepository entryRepository, CategoryRepository categoryRepository,
                           ExecutorService executor, long userId, ZoneId zone) {
        this.entryRepository = entryRepository;
        this.categoryRepository = categoryRepository;
        this.executor = executor;
        this.userId = userId;
        this.zone = zone;
        this.query = new MutableLiveData<>(
                new Query(SortOrder.OCCURRED_AT, false, MonthRange.current(zone)));
    }

    /** 当前看的是哪个月。总计模式下界面不显示它，但仍要有个值好切回来 */
    public LiveData<MonthRange> range() {
        return Transformations.map(query, current -> current.range);
    }

    public LiveData<SortOrder> sortOrder() {
        return Transformations.map(query, current -> current.order);
    }

    /** 当前是不是「总计」（不按月切分，看全部账目） */
    public LiveData<Boolean> total() {
        return Transformations.map(query, current -> current.total);
    }

    /** 在两种排序之间来回切。默认是「发生时间」，所以第一次点会切到「记账时间」 */
    public void toggleSort() {
        Query current = query.getValue();
        query.setValue(new Query(
                current.order == SortOrder.CREATED_AT
                        ? SortOrder.OCCURRED_AT
                        : SortOrder.CREATED_AT,
                current.total,
                current.range));
    }

    /**
     * 在「本月」和「总计」之间切。
     *
     * <p>总计不是另写一条 SQL，而是把时间边界放开——查询本身还是同一条，
     * 只是条件松了。「总计」与「本月」的差别本来就只在时间范围上。
     */
    public void toggleTotal() {
        Query current = query.getValue();
        query.setValue(new Query(current.order, !current.total, current.range));
    }

    /**
     * 往前 / 往后翻一个月。
     *
     * <p>翻月会自动退出「总计」：总计没有"上一个月"可言，
     * 用户点箭头说明他想按月看，这时候还停在总计上会让人以为按钮坏了。
     */
    public void shiftMonth(int delta) {
        Query current = query.getValue();
        LocalDate anchor = LocalDate.of(current.range.year, current.range.month, 1)
                .plusMonths(delta);
        query.setValue(new Query(current.order, false,
                MonthRange.of(anchor.getYear(), anchor.getMonthValue(), zone)));
    }

    /** 直接跳到指定的年月（顶栏点中间那串年月之后选的结果） */
    public void selectMonth(int year, int month) {
        Query current = query.getValue();
        query.setValue(new Query(current.order, false, MonthRange.of(year, month, zone)));
    }

    /**
     * 账目列表，数据库驱动，改动后自动刷新。
     *
     * <p>用 {@code switchMap} 而不是让界面自己选几个 LiveData 里的一个：
     * 排序字段写死在 SQL 里（Room 的 {@code @Query} 是编译期生成的，没法把 ORDER BY
     * 当参数），所以"当前按哪个排"必须换一整条查询——这是 ViewModel 的状态，
     * 界面不该知道。
     */
    public LiveData<List<EntryEntity>> entries() {
        return Transformations.switchMap(query, current -> {
            // 总计 = 把时间边界放开，查询本身不变
            long from = current.total ? 0L : current.range.fromInclusive;
            long to = current.total ? Long.MAX_VALUE : current.range.toExclusive;
            return current.order == SortOrder.CREATED_AT
                    ? entryRepository.observeBetweenByCreatedAt(from, to)
                    : entryRepository.observeBetween(from, to);
        });
    }

    public LiveData<CategoryTree> tree() {
        return tree;
    }

    /** <b>异步</b>加载分类树（只为把分类 id 渲染成可读路径） */
    public void load() {
        executor.execute(() -> tree.postValue(categoryRepository.loadTree(userId)));
    }

    /** 手动构造依赖的 ViewModel 工厂（P1 不用依赖注入框架，D2 §1） */
    public static final class Factory implements ViewModelProvider.Factory {

        private final EntryRepository entryRepository;
        private final CategoryRepository categoryRepository;
        private final ExecutorService executor;
        private final long userId;
        private final ZoneId zone;

        public Factory(EntryRepository entryRepository, CategoryRepository categoryRepository,
                       ExecutorService executor, long userId, ZoneId zone) {
            this.entryRepository = entryRepository;
            this.categoryRepository = categoryRepository;
            this.executor = executor;
            this.userId = userId;
            this.zone = zone;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new LedgerViewModel(entryRepository, categoryRepository, executor,
                    userId, zone);
        }
    }
}
