package com.wisebook.app.data;

import com.wisebook.app.data.local.EvidenceStore;
import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.entity.LedgerEntity;
import com.wisebook.app.data.local.entity.SettingEntity;
import com.wisebook.app.data.local.seed.CategorySeeder;
import com.wisebook.app.domain.model.CategoryScheme;

import java.util.List;

/**
 * 账本的读写（2026-10-11，多账本）。
 *
 * <p>一个账本 = 一个 {@code user_id}（见 {@link LedgerEntity}）。所以「新建账本」
 * 除了登记一行 {@code t_ledger}，还得给这个新 id <b>播一套分类</b>、
 * <b>建一行设置</b>——否则新账本一进去分类选择器是空的、设置也读不出来。
 * 「删除账本」则反过来：把它名下的账目、草稿、分类、设置与证据文件一起清掉。
 *
 * <p><b>所有方法都是阻塞的</b>，调用方负责放到 {@code databaseExecutor} 上。
 */
public final class LedgerRepository {

    private final WiseBookDatabase database;
    private final LedgerSelectionStore selection;
    private final SettingsRepository settingsRepository;
    private final EvidenceStore evidenceStore;

    public LedgerRepository(WiseBookDatabase database, LedgerSelectionStore selection,
                            SettingsRepository settingsRepository, EvidenceStore evidenceStore) {
        this.database = database;
        this.selection = selection;
        this.settingsRepository = settingsRepository;
        this.evidenceStore = evidenceStore;
    }

    /** 全部账本，按排序号与 id 排列 */
    public List<LedgerEntity> list() {
        return database.ledgerDao().list();
    }

    public LedgerEntity find(long ledgerId) {
        return database.ledgerDao().find(ledgerId);
    }

    /** 某个账本里有多少笔账目（列表界面显示"存档进度"用） */
    public int entryCount(long ledgerId) {
        return database.entryDao().count(ledgerId);
    }

    /**
     * 新建账本并返回它的 id。
     *
     * <p>id 取 {@code MAX(ledger_id) + 1}，不依赖自增：这样新建的账本 id 与
     * 旧数据的 {@code user_id} 处于同一序列，不会撞车。
     */
    public long create(String name) {
        return create(name, null);
    }

    /**
     * 新建账本，并指定它的分类方案（2026-10-11）。
     *
     * <p>为什么方案要在这里定：分类方案是<b>按账本</b>存的（{@code t_setting}），
     * 而新账本创建的那一刻正是要写下这一行设置的时候——用户在建账本时就该知道
     * "这个账本能不能用到二级分类"，而不是建完再去设置页找一遍。
     *
     * <p>分类树本身<b>不因方案而不同</b>：两套方案共享同一棵树（D1-A §6），
     * 方案只是"允不允许选二级"的开关。所以这里照旧播完整棵树。
     *
     * @param scheme {@code null} 表示用默认方案（标准）
     */
    public long create(String name, CategoryScheme scheme) {
        final String ledgerName = (name == null || name.trim().isEmpty()) ? "新账本" : name.trim();
        long id = database.<Long>runInTransaction(() -> {
            Long max = database.ledgerDao().maxId();
            long next = (max == null ? 0L : max) + 1L;
            LedgerEntity ledger = new LedgerEntity();
            ledger.ledgerId = next;
            ledger.name = ledgerName;
            ledger.createdAt = System.currentTimeMillis();
            ledger.sortOrder = (int) next;
            database.ledgerDao().upsert(ledger);
            return next;
        });
        // 播种与建设置放在事务外：CategorySeeder 内部自带事务，嵌套进来没必要
        CategorySeeder.seedIfEmpty(database, id);
        SettingEntity settings = settingsRepository.loadOrInit(id);
        if (scheme != null) {
            settings.categoryScheme = scheme;
            settingsRepository.save(settings);
        }
        return id;
    }

    /** 某个账本的分类方案（"能不能选到二级"）。列表上要显示它，所以单独取一次 */
    public CategoryScheme schemeOf(long ledgerId) {
        return settingsRepository.loadOrInit(ledgerId).categoryScheme;
    }

    /** 重命名一个账本 */
    public void rename(long ledgerId, String name) {
        LedgerEntity ledger = database.ledgerDao().find(ledgerId);
        if (ledger == null) {
            return;
        }
        ledger.name = (name == null || name.trim().isEmpty()) ? ledger.name : name.trim();
        database.ledgerDao().upsert(ledger);
    }

    /**
     * 删除一个账本，<b>连同它的全部数据</b>（不可恢复）。
     *
     * <p>清空顺序刻意如此：先是数据库里那几件事（一个事务保证），
     * 证据文件（原图 / 原录音）在事务之外单独删——文件删不掉不该让整次删除失败。
     * 删账目前先取出证据路径，是因为删了草稿行就再也找不到那些文件了。
     */
    public void delete(long ledgerId) {
        List<String> evidenceRefs = database.draftDao().findEvidenceRefs(ledgerId);
        for (String ref : evidenceRefs) {
            evidenceStore.delete(ref);
        }
        database.runInTransaction(() -> {
            database.entryDao().deleteByUser(ledgerId);
            database.draftDao().deleteByUser(ledgerId);
            database.categoryDao().deleteByUser(ledgerId);
            database.settingDao().deleteByUser(ledgerId);
            database.ledgerDao().delete(ledgerId);
        });
    }

    /**
     * 启动兜底，保证「至少有一个账本、当前账本确实存在、当前账本已播种」。
     *
     * <p>三种情况都要接住：全新安装（一个账本都没有）、升级（迁移已插了默认账本）、
     * 以及「当前账本被删掉但选择里还留着它的 id」。
     */
    public void ensureDefault() {
        List<LedgerEntity> all = database.ledgerDao().list();
        if (all.isEmpty()) {
            LedgerEntity ledger = new LedgerEntity();
            ledger.ledgerId = WiseBookDatabase.DEFAULT_USER_ID;
            ledger.name = "默认账本";
            ledger.createdAt = System.currentTimeMillis();
            ledger.sortOrder = 0;
            database.ledgerDao().upsert(ledger);
            all = database.ledgerDao().list();
        }
        // 当前账本不存在了（比如刚删掉的就是它）→ 落回列表里的第一个
        if (database.ledgerDao().find(selection.id()) == null) {
            selection.select(all.get(0).ledgerId);
        }
        long id = selection.id();
        CategorySeeder.seedIfEmpty(database, id);
        settingsRepository.loadOrInit(id);
    }
}
