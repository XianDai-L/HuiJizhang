package com.wisebook.app.data;

import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.classify.CategoryClassifier;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.draft.DedupeKey;
import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.app.domain.model.CategoryCandidate;
import com.wisebook.app.domain.model.ConfidenceFlag;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.DraftStatus;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.domain.model.OccurredAtSource;
import com.wisebook.app.input.bill.BillFormat;
import com.wisebook.app.input.bill.BillRow;
import com.wisebook.app.input.bill.BillSheet;
import com.wisebook.app.input.bill.WechatBillAdapter;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 支付账单导入（微信账单，xlsx 与 csv 都认）。
 *
 * <p><b>为什么另起一个仓储、不并进 {@link EntryTransferRepository}</b>：两者的口径不同，
 * 硬合会互相牵制——
 * <ul>
 *   <li>账本 CSV：<b>重复即跳过</b>，因为那是同一份备份导第二次，跳过的就是同一笔</li>
 *   <li>账单：<b>重复不跳过、打「疑似重复」标签照常落账</b>（2026-10-08 用户拍板）。
 *       账单与手记的账可能只是"看起来像"，真实存在同一天同一分钟买两次同一件东西的情况，
 *       自动跳过会把真账吞掉</li>
 *   <li>账单还有「中性交易跳过」「支出转账 / 收入红包」这两条账本 CSV 没有的规则</li>
 * </ul>
 * 两套口径揉进一个方法，读的人就得先分清"现在走的是哪条路"——那正是最容易改错的地方。
 * 里面确实有几十行"造草稿 + 落账"的形状重复，等第三处出现再抽（三次法则）。
 *
 * <p><b>所有方法都是阻塞的</b>，调用方负责放到后台线程。
 */
public final class BillImportRepository {

    /** 批次标签前缀。与 {@code TXT-}（文字）/ {@code IMG-}（截图）同一套约定 */
    public static final String BATCH_PREFIX = "BILL-";

    private final WiseBookDatabase database;
    private final CategoryRepository categoryRepository;
    private final EntryRepository entryRepository;
    private final CurrentLedger currentLedger;
    private final ZoneId zone;

    public BillImportRepository(WiseBookDatabase database, CategoryRepository categoryRepository,
                                EntryRepository entryRepository, CurrentLedger currentLedger,
                                ZoneId zone) {
        this.database = database;
        this.categoryRepository = categoryRepository;
        this.entryRepository = entryRepository;
        this.currentLedger = currentLedger;
        this.zone = zone;
    }

    /** 一次导入的实话：导了几笔、跳了几笔、有几笔像重复、有几笔没认出分类 */
    public static final class BillImportReport {

        public final BillFormat format;
        /** 文本文件用哪种编码读的；xlsx 为 {@code null} */
        public final String charsetName;

        public final int added;
        public final int neutralSkipped;
        public final int suspectedDuplicate;
        public final int badRows;
        /** 规则没命中、落进默认分类（其他 / 人情 / 其他收入）的笔数 */
        public final int categoryFallback;
        public final int failed;

        BillImportReport(BillFormat format, String charsetName, int added, int neutralSkipped,
                         int suspectedDuplicate, int badRows, int categoryFallback, int failed) {
            this.format = format;
            this.charsetName = charsetName;
            this.added = added;
            this.neutralSkipped = neutralSkipped;
            this.suspectedDuplicate = suspectedDuplicate;
            this.badRows = badRows;
            this.categoryFallback = categoryFallback;
            this.failed = failed;
        }

        public String summary() {
            StringBuilder text = new StringBuilder("导入完成：新增 ").append(added).append(" 笔");
            if (neutralSkipped > 0) {
                text.append("，跳过中性交易 ").append(neutralSkipped).append(" 笔");
            }
            if (suspectedDuplicate > 0) {
                // 这句话必须写全：用户看到"疑似重复"最想知道的是"那我账本里现在有没有两笔"
                text.append("，其中 ").append(suspectedDuplicate)
                        .append(" 笔与本机已有账目高度相似（已照常导入并打上标记，请在账本里核对）");
            }
            if (categoryFallback > 0) {
                text.append("，").append(categoryFallback)
                        .append(" 笔没能从账单里认出分类、已归入默认分类");
            }
            if (badRows > 0) {
                text.append("，").append(badRows).append(" 行的金额或时间看不懂已跳过");
            }
            if (failed > 0) {
                text.append("，").append(failed).append(" 笔写入失败");
            }
            return text.toString();
        }
    }

    /**
     * 导入一份账单。
     *
     * <p><b>整体一个事务</b>：中途失败不会留下半批账目。
     *
     * @param sheet     已读好的文件（{@link BillSheet#read}）
     * @param headerRow 真表头所在行（{@link BillFormat#detect}）
     */
    public BillImportReport importBill(BillSheet sheet, int headerRow) {
        WechatBillAdapter.Parsed parsed = WechatBillAdapter.parse(sheet.cells(), headerRow, zone);
        BillImportReport report = database.runInTransaction(
                () -> doImport(parsed, sheet.charsetName()));
        // Room 的实现里可调用体抛异常会往外传播，这里的空分支是"万一返回了空"的兜底：
        // 报成全部失败，总好过让界面拿到 null
        return report == null
                ? new BillImportReport(BillFormat.WECHAT_BILL, sheet.charsetName(),
                        0, parsed.neutralSkipped, 0, parsed.badRows, 0, parsed.rows.size())
                : report;
    }

    private BillImportReport doImport(WechatBillAdapter.Parsed parsed, String charsetName) {
        long userId = currentLedger.id();
        CategoryTree tree = categoryRepository.loadTree(userId);
        CategoryClassifier classifier = new CategoryClassifier(tree);
        long now = System.currentTimeMillis();
        String batchId = BATCH_PREFIX + now;

        int added = 0;
        int suspected = 0;
        int fallback = 0;
        int failed = 0;

        int splitIndex = 0;
        for (BillRow row : parsed.rows) {
            DraftEntity draft = newDraft(userId, row, batchId, splitIndex, now);
            splitIndex++;

            // 去重键与手记/截图入口用的是同一个（方向 + 金额 + 分钟 + 商户，D1 §7）。
            // 先查的是**已落库的账目**，所以同一份文件导第二次、或两次导出的时间范围重叠，
            // 这里都会命中——命中之后不跳过，只打标签（用户口径）
            String dedupeKey = DedupeKey.of(row.direction, row.amountCents, row.occurredAt,
                    row.merchant);
            draft.dedupeKey = dedupeKey;
            if (database.entryDao().countByDedupeKey(dedupeKey, userId) > 0) {
                draft.confidenceFlags.add(ConfidenceFlag.DUPLICATE_SUSPECT);
                suspected++;
            }

            // 分类：账单语义直接给出的路径（目前只有收入转账的「红包」）当作候选，
            // 其余交给分类器里的账单规则表。不在这里自己定分类，是为了让详情页
            // 重跑判定时得到同一个结论（见 CategoryClassifier 的类注释）
            Long explicitId = row.categoryPath == null
                    ? null : tree.idOfPath(row.categoryPath, row.direction);
            if (explicitId != null) {
                draft.categoryCandidates = Collections.singletonList(
                        new CategoryCandidate(explicitId, tree.pathOf(explicitId)));
            }

            CategoryClassifier.Result classified = classifier.classify(draft);
            if (classified.categoryId == null) {
                failed++;                       // 分类表都没播种好这种异常情况
                continue;
            }
            draft.categoryId = classified.categoryId;
            draft.rootCategoryId = classified.rootCategoryId;
            if (!classified.candidates.isEmpty()) {
                // 把判定结果写回候选，与 DraftAssembler.assemble 同一步：
                // 账目详情页「输入解析」里的分类名是拿 categoryId 去 categoryCandidates 里
                // 找的（DiagnosticsFormatter），不写回就会显示成「#5」这种裸 id。
                //
                // 它<b>不会</b>影响下次重跑判定的结论：账单入口的规则表排在候选之前
                // （见 CategoryClassifier 里的说明），所以回看时照样从账单字段重新推
                draft.categoryCandidates = classified.candidates;
            }
            if (classified.defaulted) {
                // 账单里认不出分类，只能落默认桶。这个数要报给用户——
                // 他一眼就知道有多少笔还需要自己去改分类，而不是翻账本时才发现一片「其他」
                fallback++;
            }

            long draftId = database.draftDao().insert(draft);
            // autoPosted = false：它是"导入进来的"，不是"AI 免确认直落"——
            // 账本上那个标记代表"这笔没经过你确认"，导入的账不该背这个名声
            EntryRepository.PostOutcome outcome = entryRepository.post(draftId, false);
            if (outcome.isOk()) {
                added++;
            } else {
                failed++;
            }
        }

        return new BillImportReport(BillFormat.WECHAT_BILL, charsetName,
                added, parsed.neutralSkipped, suspected, parsed.badRows, fallback, failed);
    }

    /**
     * 一行账单 → 草稿。
     *
     * <p>几个字段的取值都是"实话"而不是"看起来完整"：
     * <ul>
     *   <li>{@code amount_rule_check = NA}：导入的金额没有经过双通道校验
     *       （那是给口语金额用的），写 PASS 等于伪造成"校验过"</li>
     *   <li>{@code occurred_at_source = EXPLICIT}：账单上的时间是被明确写下来的，
     *       不是口语里推断的——因此 {@code ConfirmPolicy} 条件 9（截图入口时间靠猜时转确认）
     *       在这里本来就不该触发，何况账单导入根本不走确认</li>
     *   <li>{@code status = CONFIRMED}：草稿落库后立刻 {@code post}，
     *       中间那个 DRAFT 状态是瞬时的（与决策 17、决策 53 的导入路径一致）</li>
     * </ul>
     */
    private static DraftEntity newDraft(long userId, BillRow row, String batchId,
                                        int splitIndex, long now) {
        DraftEntity draft = new DraftEntity();
        draft.userId = userId;
        draft.status = DraftStatus.CONFIRMED;
        draft.source = DraftSource.BILL_IMPORT;
        draft.evidenceType = EvidenceType.TEXT;
        draft.rawInput = row.rawInput;
        draft.direction = row.direction;
        draft.amountCents = row.amountCents;
        draft.amountRaw = null;                 // 账单给的是数字，没有"原文金额"这回事
        draft.amountIsEstimated = false;
        draft.amountRuleCheck = AmountRuleCheck.NA;
        draft.occurredAt = row.occurredAt;
        draft.occurredAtSource = OccurredAtSource.EXPLICIT;
        draft.paymentMethod = row.paymentMethod;
        draft.merchant = row.merchant;
        draft.items = row.items;
        draft.note = row.note;
        draft.createdAt = now;
        draft.updatedAt = now;
        draft.postedAt = null;
        draft.entryId = null;
        draft.clarifyQuestions = null;
        draft.clarifyRounds = 0;
        draft.model = null;                     // 账单导入没有模型参与，别编一个
        draft.batchId = batchId;
        draft.splitIndex = splitIndex;
        draft.confidenceFlags = new ArrayList<>();
        return draft;
    }
}
