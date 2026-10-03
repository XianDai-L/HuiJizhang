package com.wisebook.app.ui.ledger;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
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
 * <p><b>这里不再有任何"改数据"的方法。</b>改正与撤销都搬到了详情页
 * （{@link EntryDetailActivity} / {@link EntryDetailViewModel}）：
 * 列表只负责把人送过去。这样列表页不需要设置、也不需要分类方案，
 * 依赖少了一半。
 */
public class LedgerViewModel extends ViewModel {

    private final EntryRepository entryRepository;
    private final CategoryRepository categoryRepository;
    private final ExecutorService executor;
    private final long userId;
    private final MonthRange range;

    private final MutableLiveData<CategoryTree> tree = new MutableLiveData<>();

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

    /** 本月账目，按发生时间倒序；数据库驱动，改动后自动刷新 */
    public LiveData<List<EntryEntity>> entries() {
        return entryRepository.observeBetween(range.fromInclusive, range.toExclusive);
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
