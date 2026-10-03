package com.wisebook.app.ui.pending;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.data.CategoryRepository;
import com.wisebook.app.data.DraftRepository;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.classify.CategoryTree;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 待处理草稿列表。
 *
 * <p>列表本身直接观察数据库（Room 的 {@code LiveData}），所以从确认页回来、
 * 或丢弃了一笔之后，列表会自己少一行——不需要在 {@code onResume} 里手工重查。
 * 分类树是要额外加载的（要用来把分类 id 渲染成可读路径），单独走一次异步。
 */
public class PendingDraftsViewModel extends ViewModel {

    private final DraftRepository draftRepository;
    private final CategoryRepository categoryRepository;
    private final ExecutorService executor;
    private final long userId;
    private final MutableLiveData<CategoryTree> tree = new MutableLiveData<>();

    public PendingDraftsViewModel(DraftRepository draftRepository,
                                  CategoryRepository categoryRepository,
                                  ExecutorService executor, long userId) {
        this.draftRepository = draftRepository;
        this.categoryRepository = categoryRepository;
        this.executor = executor;
        this.userId = userId;
    }

    /** 数据库驱动的待处理列表 */
    public LiveData<List<DraftEntity>> drafts() {
        return draftRepository.observeOpenDrafts();
    }

    public LiveData<CategoryTree> tree() {
        return tree;
    }

    /** <b>异步</b>加载分类树（只为渲染路径，缺了也只是显示「分类未定」） */
    public void loadTree() {
        if (tree.getValue() != null) {
            return;
        }
        executor.execute(() -> tree.postValue(categoryRepository.loadTree(userId)));
    }

    /** 手动构造依赖的 ViewModel 工厂（P1 不用依赖注入框架，D2 §1） */
    public static final class Factory implements ViewModelProvider.Factory {

        private final DraftRepository draftRepository;
        private final CategoryRepository categoryRepository;
        private final ExecutorService executor;
        private final long userId;

        public Factory(DraftRepository draftRepository, CategoryRepository categoryRepository,
                       ExecutorService executor, long userId) {
            this.draftRepository = draftRepository;
            this.categoryRepository = categoryRepository;
            this.executor = executor;
            this.userId = userId;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new PendingDraftsViewModel(draftRepository, categoryRepository, executor,
                    userId);
        }
    }
}
