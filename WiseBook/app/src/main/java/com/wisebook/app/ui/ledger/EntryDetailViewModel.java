package com.wisebook.app.ui.ledger;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.wisebook.app.data.CategoryRepository;
import com.wisebook.app.data.DraftRepository;
import com.wisebook.app.data.EntryRepository;
import com.wisebook.app.data.SettingsRepository;
import com.wisebook.app.data.local.EvidenceStore;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.data.local.entity.SettingEntity;
import com.wisebook.app.domain.classify.CategoryClassifier;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.ui.DiagnosticsFormatter;
import com.wisebook.app.ui.DraftFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 账目详情页的状态持有者。
 *
 * <p><b>这个页面的数据全部来自现成的两张表</b>：账目本身在 {@code t_entry}，
 * 它的"来历"（原话、模型、分类候选、存疑标签）在同一条草稿上（{@code draft_id} 关联）。
 * 所以这一页不需要任何新字段，只是把已经攒好的东西摊开——
 * 这恰好是当初把可解释性数据全存下来的回报。
 *
 * <p>「输入解析」区默认只露出原始输入（原话／截图／录音），
 * 其余解析内容要点一下才展开：多数时候用户只想确认"这笔记对了吗"。
 */
public class EntryDetailViewModel extends ViewModel {

    /** 详情页的一行：左标签 + 右内容 */
    public static final class Row {

        public final String label;
        public final String value;

        Row(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    /** 一份完整快照。字段全为 final，改状态就换一个新对象 */
    public static final class Detail {

        /** 金额大字，约数带 {@code ≈} */
        public final String amount;
        /** 如 {@code 支出 · 娱乐>游戏} */
        public final String directionCategory;
        /** 「输入解析」收起时可见的那一行 */
        public final String rawLabel;
        public final String rawText;
        /** 展开后显示的其余解析内容；没有时为 {@code null} */
        public final String parseDetails;
        /**
         * 原图的<b>绝对路径</b>；没有原图（文字/语音入口，或文件已被清理）时为 {@code null}。
         *
         * <p>库里存的是相对路径，这里拼成绝对路径再交给界面——
         * 界面不该知道 {@code filesDir} 在哪，更不该自己拼路径。
         */
        public final String evidencePath;
        public final List<Row> amountRows;
        public final List<Row> timeRows;
        public final List<Row> fieldRows;
        public final List<Row> postingRows;

        Detail(String amount, String directionCategory, String rawLabel, String rawText,
               String parseDetails, String evidencePath, List<Row> amountRows,
               List<Row> timeRows, List<Row> fieldRows, List<Row> postingRows) {
            this.amount = amount;
            this.directionCategory = directionCategory;
            this.rawLabel = rawLabel;
            this.rawText = rawText;
            this.parseDetails = parseDetails;
            this.evidencePath = evidencePath;
            this.amountRows = amountRows;
            this.timeRows = timeRows;
            this.fieldRows = fieldRows;
            this.postingRows = postingRows;
        }
    }

    private final EntryRepository entryRepository;
    private final DraftRepository draftRepository;
    private final SettingsRepository settingsRepository;
    private final CategoryRepository categoryRepository;
    private final EvidenceStore evidenceStore;
    private final ExecutorService executor;
    private final long userId;

    private final MutableLiveData<Detail> detail = new MutableLiveData<>();
    private final MutableLiveData<String> status = new MutableLiveData<>();
    /** 撤销之后置为 true，界面据此关掉自己 */
    private final MutableLiveData<Boolean> finished = new MutableLiveData<>(Boolean.FALSE);

    private EntryEntity entry;
    private CategoryTree tree;
    private SettingEntity settings;

    public EntryDetailViewModel(EntryRepository entryRepository, DraftRepository draftRepository,
                                SettingsRepository settingsRepository,
                                CategoryRepository categoryRepository,
                                EvidenceStore evidenceStore, ExecutorService executor,
                                long userId) {
        this.entryRepository = entryRepository;
        this.draftRepository = draftRepository;
        this.settingsRepository = settingsRepository;
        this.categoryRepository = categoryRepository;
        this.evidenceStore = evidenceStore;
        this.executor = executor;
        this.userId = userId;
    }

    public LiveData<Detail> detail() {
        return detail;
    }

    public LiveData<String> status() {
        return status;
    }

    public LiveData<Boolean> finished() {
        return finished;
    }

    // ------------------------------------------------------------ 分类选择器要用

    public CategoryTree tree() {
        return tree;
    }

    public Direction direction() {
        return entry == null ? null : entry.direction;
    }

    public CategoryScheme scheme() {
        return settings == null ? CategoryScheme.STANDARD : settings.categoryScheme;
    }

    // ------------------------------------------------------------------ 动作

    /** <b>异步</b>加载 */
    public void load(long entryId) {
        executor.execute(() -> {
            entry = entryRepository.findById(entryId);
            if (entry == null) {
                status.postValue("这笔账目已经不在了");
                finished.postValue(Boolean.TRUE);
                return;
            }
            tree = categoryRepository.loadTree(userId);
            settings = settingsRepository.loadOrInit(userId);
            publish();
        });
    }

    /** 改正分类（D1 §5.3）：账目与草稿一起改，改完刷新本页 */
    public void correct(String categoryPath) {
        if (entry == null || tree == null) {
            return;
        }
        long entryId = entry.entryId;
        Direction direction = entry.direction;
        executor.execute(() -> {
            Long categoryId = tree.idOfPath(categoryPath, direction);
            Long rootId = categoryId == null ? null : tree.rootIdOf(categoryId);
            if (categoryId == null || rootId == null) {
                status.postValue("没找到这个分类");
                return;
            }
            boolean ok = entryRepository.correctCategory(entryId, categoryId, rootId);
            status.postValue(ok ? "已改正，账目与草稿同步更新" : "改正失败，请重试");
            if (ok) {
                load(entryId);
            }
        });
    }

    /** 一键撤销：撤的是账目，草稿与原始输入保留 */
    public void voidEntry() {
        if (entry == null) {
            return;
        }
        long entryId = entry.entryId;
        executor.execute(() -> {
            boolean ok = entryRepository.voidEntry(entryId);
            if (!ok) {
                status.postValue("撤销失败，请重试");
                return;
            }
            status.postValue("已撤销这笔账目");
            finished.postValue(Boolean.TRUE);
        });
    }

    // ------------------------------------------------------------------ 组装

    private void publish() {
        detail.postValue(compose(entry, draftRepository.findDraft(entry.draftId), tree,
                evidenceStore));
    }

    private static Detail compose(EntryEntity entry, DraftEntity draft, CategoryTree tree,
                                  EvidenceStore evidenceStore) {
        String amount = (entry.amountIsEstimated ? "≈ " : "")
                + DraftFormatter.groupedAmount(entry.amountCents);
        String directionCategory = (entry.direction == null ? "方向未定" : entry.direction.label())
                + " · " + DraftFormatter.categoryPath(tree, entry.categoryId);

        return new Detail(
                amount,
                directionCategory,
                rawLabel(draft),
                rawText(draft, entry),
                draft == null ? null : parseDetails(draft, entry, tree),
                evidencePath(evidenceStore, draft),
                amountRows(entry, draft),
                timeRows(entry),
                fieldRows(entry),
                postingRows(entry, draft));
    }

    private static String rawLabel(DraftEntity draft) {
        EvidenceType type = draft == null ? null : draft.evidenceType;
        if (type == EvidenceType.IMAGE) {
            return "原始截图";
        }
        if (type == EvidenceType.AUDIO) {
            return "原始录音";
        }
        return "你原来说的";
    }

    /**
     * 原始输入本身。
     *
     * <p>三种入口统一显示 {@code raw_input}：文字入口是原话，截图入口是 OCR 转写出来的文字
     * （{@code ImageDraftParser} 把转写文本写进了同一列）。
     * <b>"模型当时看到了什么"因此才看得见</b>——截图出错时，这一栏往往就是答案所在。
     *
     * <p>原图不再走这里：它是另一个东西（图像），由 {@link #evidencePath} 单独给出，
     * 界面上有专门的图位。
     */
    private static String rawText(DraftEntity draft, EntryEntity entry) {
        if (draft == null) {
            return "（原始记录已不在）";
        }
        String input = draft.rawInput;
        return (input == null || input.trim().isEmpty()) ? "（没有留下原文）" : input;
    }

    /**
     * 原图的绝对路径；没有原图或文件已不在这台设备上时返回 {@code null}。
     *
     * <p>把"库里那条相对路径"解析成磁盘文件这一步收在 ViewModel 里：
     * 界面拿到的是"能不能显示、显示哪个文件"的结论，而不是一串需要自己拼的路径。
     * 这也是"只存相对路径"能成立的前提——拼 {@code filesDir} 的地方只有一个。
     */
    private static String evidencePath(EvidenceStore evidenceStore, DraftEntity draft) {
        if (draft == null || draft.evidenceRef == null) {
            return null;
        }
        java.io.File file = evidenceStore.resolve(draft.evidenceRef);
        return file == null ? null : file.getAbsolutePath();
    }

    private static String parseDetails(DraftEntity draft, EntryEntity entry, CategoryTree tree) {
        String reason;
        if (tree == null) {
            reason = null;
        } else {
            CategoryClassifier.Result classified = new CategoryClassifier(tree).classify(draft);
            reason = classified.categoryId != null && classified.categoryId == entry.categoryId
                    ? classified.reason
                    : "你手动改正过分类";
        }
        // attemptCount 传 0：调用次数没有存进库里，详情页如实不显示那一行
        return DiagnosticsFormatter.describe(0, false, draft, null, reason);
    }

    private static List<Row> amountRows(EntryEntity entry, DraftEntity draft) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("原文金额", draft == null || draft.amountRaw == null
                ? "—" : draft.amountRaw));
        rows.add(new Row("是否约数", entry.amountIsEstimated ? "是（折算值）" : "否"));
        rows.add(new Row("金额区间", rangeOf(entry)));
        rows.add(new Row("规则校验", ruleCheck(draft == null ? null : draft.amountRuleCheck)));
        return rows;
    }

    private static String rangeOf(EntryEntity entry) {
        if (entry.amountLowerCents == null && entry.amountUpperCents == null) {
            return "—";
        }
        return DraftFormatter.yuan(entry.amountLowerCents)
                + " ~ " + DraftFormatter.yuan(entry.amountUpperCents) + " 元";
    }

    private static List<Row> timeRows(EntryEntity entry) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("发生时间", DraftFormatter.fullDateTime(entry.occurredAt)));
        rows.add(new Row("时间来源", DraftFormatter.occurredAtLabel(entry.occurredAtSource)));
        return rows;
    }

    private static List<Row> fieldRows(EntryEntity entry) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("商户", blankToDash(entry.merchant)));
        rows.add(new Row("支付方式", entry.paymentMethod == null ? "—" : entry.paymentMethod.label()));
        rows.add(new Row("备注", blankToDash(entry.note)));
        rows.add(new Row("商品明细", entry.items == null || entry.items.isEmpty()
                ? "—" : String.join("、", entry.items)));
        return rows;
    }

    private static List<Row> postingRows(EntryEntity entry, DraftEntity draft) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("入账时间", DraftFormatter.fullDateTime(
                draft == null ? entry.createdAt : draft.postedAt)));
        rows.add(new Row("记账方式", entry.autoPosted
                ? "免确认直落（未经你确认）" : "你确认后入账"));
        rows.add(new Row("解析方式", DraftFormatter.sourceLabel(entry)));
        return rows;
    }

    private static String ruleCheck(AmountRuleCheck check) {
        if (check == null) {
            return "—";
        }
        switch (check) {
            case PASS:
                return "通过（规则重算与模型一致）";
            case FAIL:
                return "未通过（规则重算与模型不一致）";
            case NA:
            default:
                return "无法自动校验";
        }
    }

    private static String blankToDash(String text) {
        return text == null || text.trim().isEmpty() ? "—" : text;
    }

    /** 手动构造依赖的 ViewModel 工厂（P1 不用依赖注入框架，D2 §1） */
    public static final class Factory implements ViewModelProvider.Factory {

        private final EntryRepository entryRepository;
        private final DraftRepository draftRepository;
        private final SettingsRepository settingsRepository;
        private final CategoryRepository categoryRepository;
        private final EvidenceStore evidenceStore;
        private final ExecutorService executor;
        private final long userId;

        public Factory(EntryRepository entryRepository, DraftRepository draftRepository,
                       SettingsRepository settingsRepository,
                       CategoryRepository categoryRepository, EvidenceStore evidenceStore,
                       ExecutorService executor, long userId) {
            this.entryRepository = entryRepository;
            this.draftRepository = draftRepository;
            this.settingsRepository = settingsRepository;
            this.categoryRepository = categoryRepository;
            this.evidenceStore = evidenceStore;
            this.executor = executor;
            this.userId = userId;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new EntryDetailViewModel(entryRepository, draftRepository,
                    settingsRepository, categoryRepository, evidenceStore, executor, userId);
        }
    }
}
