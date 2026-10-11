package com.wisebook.app.ui.ledger;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.data.EntryRepository;
import com.wisebook.app.data.local.entity.EntryEntity;

import java.util.List;

/**
 * 首页「最近账目」的状态持有者：<b>跨月、按需加载</b>（2026-10-08 用户要求）。
 *
 * <p><b>为什么不复用账本页的 {@link LedgerViewModel}</b>：它带月份这个维度
 * （本月 / 切月 / 总计），而首页要回答的是"最近发生了什么"——一笔上个月的账
 * 在首页也该看得见。首页只借它算顶部那条「本月支出 / 收入 / 结余」。
 *
 * <p><b>分页的写法是把 limit 调大、每次从第 1 条重取 N 条</b>，不用 OFFSET：
 * 用户是"边看边记"的，翻页期间新记一笔会让 OFFSET 分页跳行或重复；
 * "多要几条"天然稳定，代价只是头部几行重查一次。
 *
 * <p>滚到底才加一页，判断放在 {@link #loadMore()} 里而不是界面里：
 * "还能不能加"这件事取决于"库里还有没有更多"（返回条数少于请求上限 = 到底了），
 * 界面只该负责"用户滚到底了"这个事实。
 */
public class RecentEntriesViewModel extends ViewModel {

    /** 一页多少笔。20 笔 ≈ 卡片内约 4 屏，滚到底再加一页 */
    static final int PAGE_SIZE = 20;

    private final MutableLiveData<Integer> limit = new MutableLiveData<>(PAGE_SIZE);
    private final LiveData<List<EntryEntity>> entries;

    public RecentEntriesViewModel(EntryRepository entryRepository) {
        this.entries = Transformations.switchMap(limit, entryRepository::observeRecent);
    }

    /** 最近 N 笔（跨月），数据库驱动，记完一笔或撤销一笔会自己刷新 */
    public LiveData<List<EntryEntity>> entries() {
        return entries;
    }

    /** 已请求的笔数上限 */
    public int limit() {
        Integer current = limit.getValue();
        return current == null ? PAGE_SIZE : current;
    }

    /**
     * 再要一页。以下两种情况直接返回，不做无谓的查询：
     * <ul>
     *   <li>列表还没到手上（{@code entries} 尚未有值）</li>
     *   <li>库里已经没有更多了——<b>返回条数少于请求上限就等于到底了</b>，
     *       再加一页也只会查出同样多的行</li>
     * </ul>
     */
    public void loadMore() {
        Integer current = limit.getValue();
        List<EntryEntity> loaded = entries.getValue();
        if (current == null || loaded == null || loaded.size() < current) {
            return;
        }
        limit.setValue(current + PAGE_SIZE);
    }

    /** 手动构造依赖的 ViewModel 工厂（P1 不用依赖注入框架，D2 §1） */
    public static final class Factory implements ViewModelProvider.Factory {

        private final EntryRepository entryRepository;

        public Factory(EntryRepository entryRepository) {
            this.entryRepository = entryRepository;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new RecentEntriesViewModel(entryRepository);
        }
    }
}
