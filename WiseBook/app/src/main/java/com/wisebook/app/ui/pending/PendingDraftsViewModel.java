package com.wisebook.app.ui.pending;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.data.CategoryRepository;
import com.wisebook.app.data.DraftRepository;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.data.local.entity.SettingEntity;
import com.wisebook.app.domain.classify.CategoryTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 待处理草稿列表。
 *
 * <p>列表本身直接观察数据库（Room 的 {@code LiveData}），所以从确认页回来、
 * 或丢弃了一笔之后，列表会自己少一行——不需要在 {@code onResume} 里手工重查。
 * 分类树是要额外加载的（要用来把分类 id 渲染成可读路径），单独走一次异步。
 *
 * <p>第三件事是「<b>每行为什么在这儿</b>」：它不是草稿的字段，而是拿草稿去跑一遍
 * {@code ConfirmPolicy} 得到的结论。所以它得跟着列表一起重算，见 {@link #explainAll}。
 */
public class PendingDraftsViewModel extends ViewModel {

    private final DraftRepository draftRepository;
    private final CategoryRepository categoryRepository;
    private final ExecutorService executor;
    private final long userId;
    private final MutableLiveData<CategoryTree> tree = new MutableLiveData<>();
    private final MutableLiveData<Map<Long, String>> reasons = new MutableLiveData<>();

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

    /** 每行为什么进待处理列表；键是草稿 id，由 {@link #explainAll} 填充 */
    public LiveData<Map<Long, String>> reasons() {
        return reasons;
    }

    /** <b>异步</b>加载分类树（只为渲染路径，缺了也只是显示「分类未定」） */
    public void loadTree() {
        if (tree.getValue() != null) {
            return;
        }
        executor.execute(() -> tree.postValue(categoryRepository.loadTree(userId)));
    }

    /**
     * 重算每行的原因。<b>异步</b>：要读设置、还要逐条查重复，不能在主线程做。
     *
     * <p>由列表数据变化触发——列表是 Room 驱动的，用户处理完一笔回来它会自己变，
     * 原因跟着重算，不会出现"行没了原因还挂着"。
     *
     * <p>设置只读一次：一屏草稿共用同一份设置，逐条去查同一个单行表是白费力气。
     */
    public void explainAll(List<DraftEntity> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            reasons.postValue(new HashMap<>());
            return;
        }
        final List<DraftEntity> snapshot = new ArrayList<>(drafts);
        executor.execute(() -> {
            SettingEntity settings = draftRepository.loadSettings();
            Map<Long, String> computed = new HashMap<>();
            for (DraftEntity draft : snapshot) {
                computed.put(draft.draftId,
                        draftRepository.explainWhyNeedsConfirm(draft, settings));
            }
            reasons.postValue(computed);
        });
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
