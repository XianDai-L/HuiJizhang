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
 * （{@link EntryDetailActivity} / {@link EntryDetailViewModel}）：
 * 列表只负责把人送过去。
 *
 * <p>P3 起多了一件事：<b>排序</b>。两个口径都保留——「发生时间」回答"这笔钱什么时候花的"，
 * 「记账时间」回答"我什么时候记的它"。两种问题都真实存在（补记昨天的账时，
 * 刚记的那笔会排在发生时间很靠后的位置，按记账时间才看得到自己刚做了什么）。
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
    private final MonthRange range;

    private final MutableLiveData<CategoryTree> tree = new MutableLiveData<>();
    private final MutableLiveData<SortOrder> sortOrder =
            new MutableLiveData<>(SortOrder.OCCURRED_AT);

    public LedgerViewModel(EntryRepository entryRepository, CategoryRepository categoryRepository,
                           ExecutorService executor, long userId, ZoneId zone) {
        this.entryRepository = entryRepository;
        this.categoryRepository = categoryRepository;
        this.executor = executor;
        this.userId = userId;
        this.range = MonthRange.current(zone);
    }

    public MonthRange range() {
        return range;
    }

    public LiveData<SortOrder> sortOrder() {
        return sortOrder;
    }

    /** 在两种排序之间来回切。默认是「发生时间」，所以第一次点会切到「记账时间」 */
    public void toggleSort() {
        sortOrder.setValue(sortOrder.getValue() == SortOrder.CREATED_AT
                ? SortOrder.OCCURRED_AT
                : SortOrder.CREATED_AT);
    }

    /**
     * 本月账目，数据库驱动，改动后自动刷新。
     *
     * <p>用 {@code switchMap} 而不是让界面自己选两个 LiveData 里的一个：
     * 排序字段写死在 SQL 里（Room 的 {@code @Query} 是编译期生成的，没法把 ORDER BY
     * 当参数），所以"当前按哪个排"必须换一整条查询——这是 ViewModel 的状态，
     * 界面不该知道。
     */
    public LiveData<List<EntryEntity>> entries() {
        return Transformations.switchMap(sortOrder, order ->
                order == SortOrder.CREATED_AT
                        ? entryRepository.observeBetweenByCreatedAt(
                                range.fromInclusive, range.toExclusive)
                        : entryRepository.observeBetween(
                                range.fromInclusive, range.toExclusive));
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
