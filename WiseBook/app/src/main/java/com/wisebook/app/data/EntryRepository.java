package com.wisebook.app.data;

import androidx.lifecycle.LiveData;

import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.domain.draft.DraftStateMachine;
import com.wisebook.app.domain.draft.DraftTransition;
import com.wisebook.app.domain.model.DraftStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 账目的读写（D1 §3.3）。
 *
 * <p>核心是 {@link #post}：<b>幂等落账</b>。D1 §4.2 约束 3 要求「POSTED 必须幂等」，
 * 防的是用户手抖点两次确认、或者网络重试导致同一笔被记两遍。这里用了两道保险：
 *
 * <ol>
 *   <li><b>逻辑层</b>：整个动作包在一个事务里，且先判断草稿是否已是 POSTED；
 *       是就直接返回既有账目，不再插</li>
 *   <li><b>物理层</b>：{@code t_entry.draft_id} 上有 UNIQUE 索引。
 *       即便逻辑层被绕过，数据库也不会让同一个草稿落两次账——
 *       这是「约束写在数据库里，而不是只写在代码里」的价值</li>
 * </ol>
 *
 * <p><b>所有方法都是阻塞的</b>，调用方负责放到后台线程。
 */
public final class EntryRepository {

    /** 落账结果 */
    public static final class PostOutcome {

        public enum Kind {
            /** 本次真的写入了 */
            POSTED,
            /** 之前已经落过账，本次什么都没做（幂等命中） */
            ALREADY_POSTED,
            /** 落不了，附原因 */
            FAILED
        }

        public final Kind kind;
        public final long entryId;
        public final String message;

        private PostOutcome(Kind kind, long entryId, String message) {
            this.kind = kind;
            this.entryId = entryId;
            this.message = message;
        }

        /**
         * 造一个「落不了账」的结果。
         *
         * <p>之所以开这个具名工厂、而不是把构造器放开：{@code FAILED} 的结果里
         * {@code entryId} 恒为 -1，让调用方手写一个 {@code -1L} 迟早会有人写错。
         * 这类"参数之间有固定搭配"的构造，收进工厂比暴露构造器安全。
         */
        public static PostOutcome failed(String message) {
            return new PostOutcome(Kind.FAILED, -1L, message);
        }

        public boolean isOk() {
            return kind != Kind.FAILED;
        }

        public boolean isNewlyPosted() {
            return kind == Kind.POSTED;
        }

        @Override
        public String toString() {
            return kind + (message == null ? "" : "：" + message);
        }
    }

    private final WiseBookDatabase database;

    public EntryRepository(WiseBookDatabase database) {
        this.database = database;
    }

    /**
     * 把一笔已确认的草稿写入账本。
     *
     * @param autoPosted 是否免确认直落（写入 {@code t_entry.auto_posted}，
     *                   决定它是否出现在「最近自动记账」里并带视觉标记）
     * @return 落账结果；<b>可重复调用</b>，第二次起返回 ALREADY_POSTED
     */
    public PostOutcome post(long draftId, boolean autoPosted) {
        // 显式声明成 Callable 而不是直接传 lambda：
        // RoomDatabase 同时有 runInTransaction(Runnable) 与 runInTransaction(Callable) 两个重载，
        // 一个既返回值又能当语句用的 lambda 会让编译器无从选择
        Callable<PostOutcome> work = () -> doPost(draftId, autoPosted);
        return database.runInTransaction(work);
    }

    /**
     * 撤销一笔已入账记录（D1 §5.3「一键撤销」）。
     *
     * <p>删的是账目行，草稿保留并置为 DISCARDED——{@code raw_input} 与模型信息
     * 是可解释性的基础，不该跟着账目一起消失。
     *
     * @return 是否确实撤销了一笔
     */
    public boolean voidEntry(long entryId) {
        Callable<Boolean> work = () -> doVoid(entryId);
        return Boolean.TRUE.equals(database.runInTransaction(work));
    }

    /**
     * 改正一笔账目的分类（D1 §5.3「一键改正」）。
     *
     * <p><b>账目与草稿一起改。</b>D1 §3.1 已经接受了「核心字段两边各存一份」这份代价，
     * 那就得承担它的义务：只改一边，库里会留下「账本说餐饮、草稿说交通」这种矛盾，
     * 而草稿恰恰是用户回看「我当时说了什么、AI 理解成了什么」的唯一依据。
     *
     * <p>不需要重算 {@code dedupe_key}：它的构成是「方向 + 金额 + 发生时间（分钟）+ 商户」
     * （见 {@code DedupeKey}），<b>不含分类</b>，所以改正分类不会让已建索引的去重键失效。
     *
     * @param rootCategoryId 一级分类 id；报表按它聚合，必须跟着末级一起更新
     * @return 是否确实改正了一笔
     */
    public boolean correctCategory(long entryId, long categoryId, long rootCategoryId) {
        Callable<Boolean> work = () -> {
            EntryEntity entry = database.entryDao().findById(entryId);
            if (entry == null) {
                return false;
            }
            entry.categoryId = categoryId;
            entry.rootCategoryId = rootCategoryId;
            database.entryDao().update(entry);

            DraftEntity draft = database.draftDao().findById(entry.draftId);
            if (draft != null) {
                draft.categoryId = categoryId;
                draft.rootCategoryId = rootCategoryId;
                draft.updatedAt = System.currentTimeMillis();
                database.draftDao().update(draft);
            }
            return true;
        };
        return Boolean.TRUE.equals(database.runInTransaction(work));
    }

    /** 单条账目；不存在时返回 {@code null}。<b>阻塞</b> */
    public EntryEntity findById(long entryId) {
        return database.entryDao().findById(entryId);
    }

    /** 某个月区间内的账目，按发生时间倒序；数据库驱动的观察（账本列表用） */
    public LiveData<List<EntryEntity>> observeBetween(long fromInclusive, long toExclusive) {
        return database.entryDao().observeBetween(fromInclusive, toExclusive);
    }

    /** 某个月区间内的账目明细。<b>阻塞</b> */
    public List<EntryEntity> findBetween(long fromInclusive, long toExclusive) {
        return database.entryDao().findBetween(fromInclusive, toExclusive);
    }

    // ------------------------------------------------------------------ 内部

    private PostOutcome doPost(long draftId, boolean autoPosted) {
        DraftEntity draft = database.draftDao().findById(draftId);
        if (draft == null) {
            return new PostOutcome(PostOutcome.Kind.FAILED, -1L, "草稿不存在：" + draftId);
        }

        // 幂等命中：已经落过账就把既有那条还回去，不再写
        if (draft.status == DraftStatus.POSTED) {
            EntryEntity existing = database.entryDao().findByDraftId(draftId);
            return existing == null
                    ? new PostOutcome(PostOutcome.Kind.FAILED, -1L,
                            "草稿标记为已落账，但账目不见了（数据不一致）")
                    : new PostOutcome(PostOutcome.Kind.ALREADY_POSTED, existing.entryId,
                            "这笔已经记过了");
        }

        if (!DraftStateMachine.canApply(draft.status, DraftTransition.POST)) {
            return new PostOutcome(PostOutcome.Kind.FAILED, -1L,
                    "草稿当前状态为 " + draft.status + "，不能落账");
        }

        String missing = missingRequiredFields(draft);
        if (missing != null) {
            return new PostOutcome(PostOutcome.Kind.FAILED, -1L, "必填字段不齐：" + missing);
        }

        long now = System.currentTimeMillis();
        long entryId = database.entryDao().insert(toEntry(draft, autoPosted, now));

        draft.status = DraftStateMachine.apply(draft.status, DraftTransition.POST);
        draft.entryId = entryId;
        draft.postedAt = now;
        draft.updatedAt = now;
        database.draftDao().update(draft);

        return new PostOutcome(PostOutcome.Kind.POSTED, entryId, null);
    }

    private boolean doVoid(long entryId) {
        EntryEntity entry = database.entryDao().findById(entryId);
        if (entry == null) {
            return false;
        }
        DraftEntity draft = database.draftDao().findById(entry.draftId);
        database.entryDao().delete(entry);
        if (draft != null && DraftStateMachine.canApply(draft.status, DraftTransition.VOID)) {
            draft.status = DraftStateMachine.apply(draft.status, DraftTransition.VOID);
            draft.entryId = null;
            draft.postedAt = null;
            draft.updatedAt = System.currentTimeMillis();
            database.draftDao().update(draft);
        }
        return true;
    }

    private static EntryEntity toEntry(DraftEntity draft, boolean autoPosted, long now) {
        EntryEntity entry = new EntryEntity();
        entry.draftId = draft.draftId;
        entry.userId = draft.userId;
        entry.direction = draft.direction;
        entry.amountCents = draft.amountCents;
        entry.amountLowerCents = draft.amountLowerCents;
        entry.amountUpperCents = draft.amountUpperCents;
        entry.amountRaw = draft.amountRaw;
        entry.amountIsEstimated = draft.amountIsEstimated;
        entry.occurredAt = draft.occurredAt;
        entry.occurredAtSource = draft.occurredAtSource;
        entry.categoryId = draft.categoryId;
        entry.rootCategoryId = draft.rootCategoryId;
        entry.paymentMethod = draft.paymentMethod;
        entry.merchant = draft.merchant;
        entry.items = draft.items == null ? null : new ArrayList<>(draft.items);
        entry.note = draft.note;
        entry.source = draft.source;
        entry.createdAt = now;
        entry.dedupeKey = draft.dedupeKey;
        entry.autoPosted = autoPosted;
        return entry;
    }

    /**
     * 落账前的字段校验。口径必须与 {@code ConfirmPolicy} 完全一致，
     * 否则会出现「判定说能落账、落账时说字段不齐」这种自相矛盾的状态。
     *
     * <p>注意这里<b>没有</b>检查 {@code payment_method}：它已降级为可选字段
     * （见 {@code ConfirmPolicy} 与 HANDOFF 决策 20），
     * {@code t_entry.payment_method} 在 schema 里本来也是可空的。
     *
     * @return 缺失字段的描述；齐全时返回 {@code null}
     */
    private static String missingRequiredFields(DraftEntity draft) {
        StringBuilder missing = new StringBuilder();
        appendIfNull(missing, draft.amountCents, "金额");
        appendIfNull(missing, draft.direction, "收支方向");
        appendIfNull(missing, draft.occurredAt, "发生时间");
        appendIfNull(missing, draft.categoryId, "分类");
        // 一级分类 id 不是解析产物，落账前必须补好，否则报表聚合不出来
        appendIfNull(missing, draft.rootCategoryId, "一级分类");
        return missing.length() == 0 ? null : missing.toString();
    }

    private static void appendIfNull(StringBuilder builder, Object value, String label) {
        if (value != null) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('、');
        }
        builder.append(label);
    }
}
