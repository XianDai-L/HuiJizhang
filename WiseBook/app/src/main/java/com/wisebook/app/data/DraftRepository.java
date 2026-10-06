package com.wisebook.app.data;

import androidx.lifecycle.LiveData;

import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.data.local.entity.SettingEntity;
import com.wisebook.app.data.remote.LlmRuntime;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.confirm.ClarifyPlanner;
import com.wisebook.app.domain.confirm.ConfirmPolicy;
import com.wisebook.app.domain.draft.DedupeKey;
import com.wisebook.app.domain.draft.DraftStateMachine;
import com.wisebook.app.domain.draft.DraftTransition;
import com.wisebook.app.domain.model.DraftStatus;
import com.wisebook.app.input.DraftParseResult;
import com.wisebook.app.input.chat.ChatDraftParser;
import com.wisebook.app.input.image.ImageDraftParser;
import com.wisebook.app.input.image.ImageInput;
import com.wisebook.llm.ImageReader;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 草稿的编排层：<b>解析 → 判定 → 落库 → （必要时）立刻落账</b>。
 *
 * <p>它是把 P1-1 的领域判据与 P1-2 的解析管线接起来的地方。刻意放在 {@code data} 而不是
 * {@code domain}：编排必须同时看到领域判据与持久化实体，放在 data 层就让依赖方向保持
 * 「data → domain」单向，domain 依旧是干净的纯逻辑。
 *
 * <p><b>DRAFT 是一个瞬时状态。</b>D1 §4 的图是
 * {@code DRAFT --(触发确认条件)--> ASKING}，也就是说草稿一解析完就立刻被判定去向，
 * 落库时写的已经是 ASKING 或 POSTED。所以「待处理」查询里几乎不会出现 {@code draft} 状态的行
 * ——那不是 bug，而是这条流程的必然结果。
 *
 * <p><b>所有方法都是阻塞的</b>，调用方负责放到后台线程（{@code WiseBookApp.databaseExecutor()}）。
 */
public final class DraftRepository {

    /** 挂起超过 24 小时即停止自动流转（D1 §4.3） */
    public static final long OPEN_TIMEOUT_MILLIS = 24L * 60L * 60L * 1000L;

    /** 反问轮数上限（D1 §4.2 约束 2）。超过之后不再生成问题，转为让用户手动补全 */
    public static final int MAX_CLARIFY_ROUNDS = 3;

    /** 本次提交的结果 */
    public static final class SubmitOutcome {

        public enum Kind {
            /** 八条件全满足，已经写入账本 */
            AUTO_POSTED,
            /** 进了确认流程，等用户在确认页上过一眼 */
            NEEDS_CONFIRM,
            /** 模型没解析出可用结构，转人工填表 */
            DEGRADED
        }

        public final Kind kind;
        /** 新增的草稿 id；{@link Kind#DEGRADED} 时为 -1 */
        public final long draftId;
        /** 新增的账目 id；仅 {@link Kind#AUTO_POSTED} 时有效 */
        public final long entryId;

        private SubmitOutcome(Kind kind, long draftId, long entryId) {
            this.kind = kind;
            this.draftId = draftId;
            this.entryId = entryId;
        }

        public boolean isOk() {
            return kind != Kind.DEGRADED;
        }

        @Override
        public String toString() {
            return kind + "{draft=" + draftId + ", entry=" + entryId + "}";
        }
    }

    /** 一次提交的详细结果，供界面展示 */
    public static final class SubmitReport {

        public final SubmitOutcome outcome;
        /** 给用户看的一句话 */
        public final String summary;
        /** 需要确认的原因 / 降级原因，可空 */
        public final String detail;
        public final int attemptCount;
        public final boolean firstAttemptSucceeded;
        /**
         * 模型这一轮的原始返回（紧凑 JSON），供「AI 处理详情」面板展示。
         *
         * <p>它不参与任何业务判断——业务该用的字段早已解析进草稿。
         * 留在结果里只是为了让人能翻到底牌：分类判错时，
         * 只有原始返回能区分「模型选错了」与「我们解析错了」。
         */
        public final String rawPayload;

        SubmitReport(SubmitOutcome outcome, String summary, String detail,
                     int attemptCount, boolean firstAttemptSucceeded, String rawPayload) {
            this.outcome = outcome;
            this.summary = summary;
            this.detail = detail;
            this.attemptCount = attemptCount;
            this.firstAttemptSucceeded = firstAttemptSucceeded;
            this.rawPayload = rawPayload;
        }
    }

    private final WiseBookDatabase database;
    private final SettingsRepository settingsRepository;
    private final CategoryRepository categoryRepository;
    private final EntryRepository entryRepository;
    private final LlmRuntime llmRuntime;
    private final ZoneId zone;

    public DraftRepository(WiseBookDatabase database,
                           SettingsRepository settingsRepository,
                           CategoryRepository categoryRepository,
                           EntryRepository entryRepository,
                           LlmRuntime llmRuntime) {
        this(database, settingsRepository, categoryRepository, entryRepository, llmRuntime,
                ZoneId.systemDefault());
    }

    /** 允许注入时区，便于测试固定「现在」 */
    public DraftRepository(WiseBookDatabase database,
                           SettingsRepository settingsRepository,
                           CategoryRepository categoryRepository,
                           EntryRepository entryRepository,
                           LlmRuntime llmRuntime,
                           ZoneId zone) {
        this.database = database;
        this.settingsRepository = settingsRepository;
        this.categoryRepository = categoryRepository;
        this.entryRepository = entryRepository;
        this.llmRuntime = llmRuntime;
        this.zone = zone;
    }

    // ------------------------------------------------------------------ 提交

    /**
     * 提交一句输入：解析、落草稿，能免确认就直接落账。
     *
     * <p><b>阻塞方法，禁止在主线程调用。</b>
     */
    public SubmitReport submit(String input) {
        return submit(input, LocalDateTime.now(zone));
    }

    /** @param now 「现在」——既是草稿创建时间，也是「今天/昨天」的参照点 */
    public SubmitReport submit(String input, LocalDateTime now) {
        long userId = WiseBookDatabase.DEFAULT_USER_ID;
        SettingEntity settings = settingsRepository.loadOrInit(userId);
        CategoryTree tree = categoryRepository.loadTree(userId);

        ChatDraftParser parser = new ChatDraftParser(
                llmRuntime.client(),
                llmRuntime.modelLabel(),
                tree,
                settings.categoryScheme,
                userId,
                zone);

        DraftParseResult parsed = parser.parse(input, now);
        if (!parsed.isOk()) {
            return new SubmitReport(
                    new SubmitOutcome(SubmitOutcome.Kind.DEGRADED, -1L, -1L),
                    "没解析出来，请手动填写",
                    parsed.message(),
                    parsed.attemptCount(),
                    false,
                    null);
        }

        return persist(parsed.firstDraft(), settings, tree,
                parsed.attemptCount(), parsed.firstAttemptSucceeded(), parsed.rawPayload());
    }

    // ------------------------------------------------------------------ 截图提交

    /**
     * 一次截图提交的结果：一图多笔就是<b>多条</b> {@link SubmitReport}。
     *
     * <p>刻意不做"批量专用规则"：每一笔都单独走一遍 {@code persist}
     * （去重、档位、免确认直落全都一样），所以这个类只负责把结果收拢起来给界面看。
     */
    public static final class ImageSubmitReport {

        /** 逐笔的结果，顺序与图里从上到下一致 */
        public final List<SubmitReport> reports;
        /** 连转写都没成功时的原因；成功时为 {@code null} */
        public final String failure;

        private ImageSubmitReport(List<SubmitReport> reports, String failure) {
            this.reports = Collections.unmodifiableList(new ArrayList<>(reports));
            this.failure = failure;
        }

        static ImageSubmitReport failed(String reason) {
            return new ImageSubmitReport(Collections.<SubmitReport>emptyList(), reason);
        }

        static ImageSubmitReport of(List<SubmitReport> reports) {
            return new ImageSubmitReport(reports, null);
        }

        public boolean isOk() {
            return failure == null;
        }

        public int draftCount() {
            return reports.size();
        }

        /** 已经写进账本的笔数 */
        public int autoPostedCount() {
            int count = 0;
            for (SubmitReport report : reports) {
                if (report.outcome.kind == SubmitOutcome.Kind.AUTO_POSTED) {
                    count++;
                }
            }
            return count;
        }

        /** 还等用户核对的笔数 */
        public int needsConfirmCount() {
            int count = 0;
            for (SubmitReport report : reports) {
                if (report.outcome.kind == SubmitOutcome.Kind.NEEDS_CONFIRM) {
                    count++;
                }
            }
            return count;
        }

        /**
         * 汇总成一句话。单笔时沿用文字入口的说法（用户的体验应当是连续的），
         * 多笔时把「几笔入账、几笔待核对」讲清楚——<b>这张图被读出了几笔</b>本身
         * 就是用户最想确认的事：模型既可能漏读，也可能把一笔读成两笔。
         */
        public String summary() {
            if (!isOk()) {
                return failure;
            }
            if (reports.size() == 1) {
                return reports.get(0).summary;
            }
            int posted = autoPostedCount();
            int pending = needsConfirmCount();
            int degraded = reports.size() - posted - pending;

            StringBuilder text = new StringBuilder("这张图有 ").append(reports.size()).append(" 笔：");
            if (posted > 0) {
                text.append("已自动记账 ").append(posted).append(" 笔");
            }
            if (posted > 0 && pending > 0) {
                text.append("，");
            }
            if (pending > 0) {
                text.append("待核对 ").append(pending).append(" 笔");
            }
            if (degraded > 0) {
                if (posted > 0 || pending > 0) {
                    text.append("，");
                }
                text.append("有 ").append(degraded).append(" 笔没解析出来");
            }
            return text.toString();
        }

        /** 汇总说明；单笔时沿用原始 detail */
        public String detail() {
            if (!isOk()) {
                return null;
            }
            if (reports.size() == 1) {
                return reports.get(0).detail;
            }
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < reports.size(); i++) {
                SubmitReport report = reports.get(i);
                text.append(i + 1).append(". ").append(report.summary);
                if (report.detail != null && !report.detail.isEmpty()) {
                    text.append("——").append(report.detail);
                }
                if (i < reports.size() - 1) {
                    text.append('\n');
                }
            }
            return text.toString();
        }

        /**
         * 结果行的落点。
         *
         * <p>单笔时与文字入口一模一样（那一笔的确认页或账目详情页）；
         * 多笔时给 -1，由界面改去「待处理」列表把整批看完——
         * 「点一下却只看得到其中一笔」最容易让人以为剩下的被漏记了。
         */
        public long primaryDraftId() {
            return reports.size() == 1 ? reports.get(0).outcome.draftId : -1L;
        }

        public long primaryEntryId() {
            return reports.size() == 1 ? reports.get(0).outcome.entryId : -1L;
        }

        /** 多笔且有需要核对的部分 → 界面应把用户送去待处理列表 */
        public boolean shouldOpenPendingList() {
            return reports.size() > 1 && needsConfirmCount() > 0;
        }
    }

    /**
     * 提交一张截图：转写 → 拆笔 → 逐笔判定与落库。<b>阻塞方法，禁止在主线程调用。</b>
     *
     * <p>一图多笔在这里就是"连着提交好几笔"：每笔独立走去重与档位判定，
     * 彼此<b>不共享状态</b>（D1 §4.2），只共享一个 {@code batch_id} 标签。
     * 这样"一批里有一笔没解析出来"不会连累其它笔。
     */
    public ImageSubmitReport submitImage(byte[] imageBytes, String mimeType, String displayName) {
        return submitImage(imageBytes, mimeType, displayName, LocalDateTime.now(zone));
    }

    /** @param now 「现在」，相对时间的参照点；也是批次标签的来源 */
    public ImageSubmitReport submitImage(byte[] imageBytes, String mimeType, String displayName,
                                        LocalDateTime now) {
        ImageReader imageReader = llmRuntime.imageReader();
        if (imageReader == null) {
            // 没有 OCR 链路时不去白跑一次网络：直接说清缺哪一家的 Key
            return ImageSubmitReport.failed(
                    "截图记账要用硅基流动的 OCR 模型。到「设置」里填一个硅基流动的 Key 再试");
        }

        long userId = WiseBookDatabase.DEFAULT_USER_ID;
        SettingEntity settings = settingsRepository.loadOrInit(userId);
        CategoryTree tree = categoryRepository.loadTree(userId);

        ImageDraftParser parser = new ImageDraftParser(imageReader, llmRuntime.client(),
                llmRuntime.modelLabel(), tree, settings.categoryScheme, userId, zone);

        DraftParseResult parsed = parser.parse(
                new ImageInput(imageBytes, mimeType, displayName), now);
        if (!parsed.isOk()) {
            return ImageSubmitReport.failed(parsed.message());
        }

        List<SubmitReport> reports = new ArrayList<>();
        for (DraftEntity draft : parsed.drafts()) {
            reports.add(persist(draft, settings, tree, parsed.attemptCount(),
                    parsed.firstAttemptSucceeded(), parsed.rawPayload()));
        }
        return ImageSubmitReport.of(reports);
    }

    /**
     * 判定并落库。
     *
     * <p>从 {@link #submit} 里拆出来，是为了让「判定 + 落库 + 落账」这段
     * 不依赖模型的逻辑可以单独验——用一笔手工构造的草稿就能把两条分支都走一遍。
     */
    public SubmitReport persist(DraftEntity draft, SettingEntity settings, CategoryTree tree,
                               int attemptCount, boolean firstAttemptSucceeded) {
        return persist(draft, settings, tree, attemptCount, firstAttemptSucceeded, null);
    }

    /**
     * @param rawPayload 模型原始返回；手工构造草稿（测试、确认页改后重校验）时传 {@code null}
     */
    public SubmitReport persist(DraftEntity draft, SettingEntity settings, CategoryTree tree,
                               int attemptCount, boolean firstAttemptSucceeded,
                               String rawPayload) {
        draft.updatedAt = System.currentTimeMillis();
        draft.dedupeKey = dedupeKeyOf(draft);

        boolean duplicate = draft.dedupeKey != null
                && database.entryDao().countByDedupeKey(draft.dedupeKey) > 0;

        ConfirmPolicy.Verdict verdict = ConfirmPolicy.decide(draft, duplicate, 1,
                settings.confirmMode, settings.largeThresholdCents);

        if (verdict.isDirect()) {
            return autoPost(draft, attemptCount, firstAttemptSucceeded, rawPayload);
        }
        return toAsking(draft, tree, verdict.reasonText(), null, attemptCount,
                firstAttemptSucceeded, rawPayload);
    }

    /** 免确认直落：CONFIRMED → 落账 → POSTED（D1 §5.2 / §5.3） */
    private SubmitReport autoPost(DraftEntity draft, int attemptCount,
                                  boolean firstAttemptSucceeded, String rawPayload) {
        // 用草稿的真实状态去校验，而不是写死 DRAFT：
        // 走「确认页改完重新校验」这条路进来时，状态可能不是 DRAFT
        if (!DraftStateMachine.canApply(draft.status, DraftTransition.AUTO_CONFIRM)) {
            return toAsking(draft, null, "",
                    "当前状态（" + draft.status + "）不能自动落账，请确认后重记",
                    attemptCount, firstAttemptSucceeded, rawPayload);
        }
        draft.status = DraftStateMachine.apply(draft.status, DraftTransition.AUTO_CONFIRM);
        draft.updatedAt = System.currentTimeMillis();
        saveOrInsert(draft);
        long draftId = draft.draftId;

        EntryRepository.PostOutcome post = entryRepository.post(draftId, true);
        if (!post.isOk()) {
            // 自动落账没成功（例如必填字段其实没齐）→ 退回确认流程。
            // 不能把草稿留在 CONFIRMED 上不管：那既没入账、又不在待处理列表里，等于石沉大海
            return toAsking(draft, null, "", "自动落账未成功（" + post.message + "），请确认后重记",
                    attemptCount, firstAttemptSucceeded, rawPayload);
        }

        return new SubmitReport(
                new SubmitOutcome(SubmitOutcome.Kind.AUTO_POSTED, draftId, post.entryId),
                "已自动记账",
                "小额、无存疑、未重复，按当前档位直接入账。可在账本里核对或撤销",
                attemptCount,
                firstAttemptSucceeded,
                rawPayload);
    }

    /** 进确认流程：DRAFT → ASKING，并生成反问清单（D1 §4） */
    private SubmitReport toAsking(DraftEntity draft, CategoryTree tree, String reason,
                                  String extraReason, int attemptCount,
                                  boolean firstAttemptSucceeded, String rawPayload) {
        if (draft.status == DraftStatus.DRAFT) {
            draft.status = DraftStateMachine.apply(DraftStatus.DRAFT, DraftTransition.START_CLARIFY);
        }

        // 反问轮数上限（D1 §4.2 约束 2）：问烦了就不再问，转为让用户手动把字段补齐。
        // 注意「不再生成问题」不等于「放弃这笔」——草稿仍在待处理里，确认页照样能改能确认
        boolean mayAsk = draft.clarifyRounds < MAX_CLARIFY_ROUNDS;
        draft.clarifyQuestions = (mayAsk && tree != null) ? ClarifyPlanner.plan(draft, tree) : null;
        draft.clarifyRounds = mayAsk ? draft.clarifyRounds + 1 : draft.clarifyRounds;
        draft.updatedAt = System.currentTimeMillis();

        if (!mayAsk) {
            extraReason = joinReasons(extraReason,
                    "已反问 " + MAX_CLARIFY_ROUNDS + " 轮，不再自动追问；请直接手动补全后确认");
        }

        saveOrInsert(draft);

        String detail = joinReasons(extraReason, reason);
        return new SubmitReport(
                new SubmitOutcome(SubmitOutcome.Kind.NEEDS_CONFIRM, draft.draftId, -1L),
                "已生成草稿，等你确认",
                detail,
                attemptCount,
                firstAttemptSucceeded,
                rawPayload);
    }

    // ------------------------------------------------------------------ 列表

    /** 还挂在用户手上的草稿（「待处理」列表），随数据库变化自动刷新 */
    public LiveData<List<DraftEntity>> observeOpenDrafts() {
        return database.draftDao().observeOpen();
    }

    /** <b>阻塞方法。</b>用于首页显示待处理数量这类一次性读取 */
    public int countOpenDrafts() {
        return database.draftDao().findOpen().size();
    }

    public DraftEntity findDraft(long draftId) {
        return database.draftDao().findById(draftId);
    }

    public void save(DraftEntity draft) {
        draft.updatedAt = System.currentTimeMillis();
        database.draftDao().update(draft);
    }

    // -------------------------------------------------------------- 确认与丢弃

    /**
     * 用户在确认页点了「确认」：{@code DRAFT/ASKING → CONFIRMED → POSTED}。
     *
     * <p><b>整个过程包在一个事务里</b>，中间不会留下"已确认但没入账"的中间态。
     * 即便上一次点确认时进程被打断、草稿停在 {@code CONFIRMED}，
     * 这里也会跳过确认那一步直接补上落账——所以重复点不会卡住，也不会记两笔。
     *
     * @return 落账结果；已入账时返回 {@code ALREADY_POSTED}
     */
    public EntryRepository.PostOutcome confirmAndPost(long draftId) {
        Callable<EntryRepository.PostOutcome> work = () -> doConfirmAndPost(draftId);
        return database.runInTransaction(work);
    }

    /**
     * 用户点了「丢弃」（D1 §4）。
     *
     * <p>只改状态、不删行：{@code raw_input} 与模型信息是可解释性的基础，
     * 而且「用户丢弃了什么」本身就是有用的数据（比如说明 prompt 该改）。
     *
     * @return 是否确实丢弃了一笔
     */
    public boolean discard(long draftId) {
        Callable<Boolean> work = () -> {
            DraftEntity draft = database.draftDao().findById(draftId);
            if (draft == null) {
                return false;
            }
            if (!DraftStateMachine.canApply(draft.status, DraftTransition.DISCARD)) {
                return false;
            }
            draft.status = DraftStateMachine.apply(draft.status, DraftTransition.DISCARD);
            draft.clarifyQuestions = null;
            draft.updatedAt = System.currentTimeMillis();
            database.draftDao().update(draft);
            return true;
        };
        return Boolean.TRUE.equals(database.runInTransaction(work));
    }

    /**
     * 用户在确认页改完字段后保存：<b>必须回流 DRAFT 重新校验</b>（D1 §4.2 约束 1）。
     *
     * <p>流程是 {@code ASKING --ANSWER_CLARIFY--> DRAFT}，然后重新跑一遍
     * {@link ConfirmPolicy}：条件都满足了就直接落账，还有问题就带着新的问题再问一轮。
     *
     * <p>之所以不让界面改完直接落账：用户改的可能是金额，而金额一改，
     * 「是否约数」「规则重算是否一致」「去重键是否命中」全都得重算——
     * 绕开这一步等于把前面积累的校验成果丢掉。
     */
    public SubmitReport applyEditsAndRevalidate(DraftEntity edited, SettingEntity settings,
                                               CategoryTree tree) {
        DraftEntity current = database.draftDao().findById(edited.draftId);
        if (current == null) {
            return new SubmitReport(
                    new SubmitOutcome(SubmitOutcome.Kind.DEGRADED, -1L, -1L),
                    "草稿已不存在",
                    null, 0, false, null);
        }

        // 回到 DRAFT。两种入口：ASKING 回答完回流；EXPIRED 被用户接管（D1 §4.3）
        if (DraftStateMachine.canApply(current.status, DraftTransition.ANSWER_CLARIFY)) {
            edited.status = DraftStateMachine.apply(current.status, DraftTransition.ANSWER_CLARIFY);
        } else if (DraftStateMachine.canApply(current.status, DraftTransition.TAKE_OVER)) {
            edited.status = DraftStateMachine.apply(current.status, DraftTransition.TAKE_OVER);
        }
        // 上一轮的问题作废：这轮会按新数据重新生成
        edited.clarifyQuestions = null;
        // 轮数保留，用于上限判断
        edited.clarifyRounds = current.clarifyRounds;
        edited.createdAt = current.createdAt;

        return persist(edited, settings, tree, 1, true);
    }

    /**
     * 这笔草稿现在为什么需要确认——给确认页顶部那行解释用。
     *
     * <p>做法是<b>重新跑一遍 {@link ConfirmPolicy}</b>，而不是把当初的理由存进库里。
     * 好处有两个：一是判据只有一个出处，不会出现「页面上的理由与代码里的判据不一致」；
     * 二是用户改完字段之后，这里的解释会自动跟着变（这正是 D1 §4.2 约束 1 想要的效果）。
     *
     * @return 需要确认的原因；若按当前档位其实已可免确认，返回空串
     */
    public String explainWhyNeedsConfirm(DraftEntity draft, SettingEntity settings) {
        boolean duplicate = draft.dedupeKey != null
                && database.entryDao().countByDedupeKey(draft.dedupeKey) > 0;
        return ConfirmPolicy.decide(draft, duplicate, 1,
                settings.confirmMode, settings.largeThresholdCents).reasonText();
    }

    /**
     * 新草稿就插入、已有的就更新。
     *
     * <p>必须区分这两种情况：{@code persist} 的调用方有两个——解析管线（草稿还没 id）
     * 与确认页的「改完重新校验」（草稿是从库里读出来的、已经有 id）。
     * 后者若走了 {@code insert}，会直接撞主键。
     */
    private void saveOrInsert(DraftEntity draft) {
        if (draft.draftId == 0L) {
            draft.draftId = database.draftDao().insert(draft);
        } else {
            database.draftDao().update(draft);
        }
    }

    // ------------------------------------------------------------------ 内部

    private EntryRepository.PostOutcome doConfirmAndPost(long draftId) {
        DraftEntity draft = database.draftDao().findById(draftId);
        if (draft == null) {
            return EntryRepository.PostOutcome.failed("草稿不存在：" + draftId);
        }

        // POSTED 与 CONFIRMED 都跳过「用户确认」这一步：
        // 前者由 post() 自己返回 ALREADY_POSTED，后者是上次被打断留下的，补上落账即可
        if (draft.status != DraftStatus.CONFIRMED && draft.status != DraftStatus.POSTED) {
            if (!DraftStateMachine.canApply(draft.status, DraftTransition.USER_CONFIRM)) {
                return EntryRepository.PostOutcome.failed(
                        "草稿当前状态为 " + draft.status + "，不能确认");
            }
            draft.status = DraftStateMachine.apply(draft.status, DraftTransition.USER_CONFIRM);
            draft.clarifyQuestions = null;
            draft.updatedAt = System.currentTimeMillis();
            database.draftDao().update(draft);
        }

        return entryRepository.post(draftId, false);
    }

    /**
     * 超时归档：挂起超过 24 小时的草稿停止自动流转（D1 §4.3）。
     *
     * <p><b>不删除</b>——数据属于用户，不替他丢弃。归档后仍可在「待处理」里被接管。
     *
     * @return 本次归档了几笔
     */
    public int archiveStale() {
        long cutoff = System.currentTimeMillis() - OPEN_TIMEOUT_MILLIS;
        List<DraftEntity> stale = database.draftDao().findStaleOpen(cutoff);
        long now = System.currentTimeMillis();
        for (DraftEntity draft : stale) {
            draft.status = DraftStateMachine.apply(draft.status, DraftTransition.EXPIRE);
            draft.updatedAt = now;
            database.draftDao().update(draft);
        }
        return stale.size();
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 去重键（D1 §7）。
     *
     * <p>必填字段不齐时算不出键——这不是特例，而是必然：那种草稿本来就要进确认流程，
     * 也就轮不到"免确认直落之前先查重复"这一步。
     */
    private static String dedupeKeyOf(DraftEntity draft) {
        if (draft.direction == null || draft.amountCents == null || draft.occurredAt == null) {
            return null;
        }
        return DedupeKey.of(draft.direction, draft.amountCents, draft.occurredAt, draft.merchant);
    }

    private static String joinReasons(String extra, String reason) {
        boolean hasExtra = extra != null && !extra.isEmpty();
        boolean hasReason = reason != null && !reason.isEmpty();
        if (hasExtra && hasReason) {
            return extra + "；" + reason;
        }
        if (hasExtra) {
            return extra;
        }
        return hasReason ? reason : "";
    }
}
