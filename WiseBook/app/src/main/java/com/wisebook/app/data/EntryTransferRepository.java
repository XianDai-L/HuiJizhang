package com.wisebook.app.data;

import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.data.local.entity.EntryEntity;
import com.wisebook.app.data.transfer.EntryCsv;
import com.wisebook.app.domain.classify.CategoryClassifier;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.draft.DedupeKey;
import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.app.domain.model.CategoryCandidate;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.DraftStatus;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.domain.model.OccurredAtSource;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 账本导出 / 导入（2026-10-08 用户要求：备份与设备转移）。
 *
 * <p><b>没有后端</b>，所以"导出"= 生成一个 CSV 交给用户自己保管，
 * "导入"= 用户选一个 CSV 读回来。格式与转义细节都在 {@link EntryCsv}（纯 JVM，有单测）。
 *
 * <p>导入的两条口径（都是刻意的，写在这里免得以后被误当 bug）：
 * <ol>
 *   <li><b>重复的跳过</b>：判同用与落地账目同一套去重键
 *       （方向 + 金额 + 发生时间 + 商户，D1 §7），命中就跳过并计数——
 *       同一份文件导两次不会长出两套账</li>
 *   <li><b>分类匹配不上就落默认桶</b>：CSV 里的路径喂给与解析入口<b>同一个</b>
 *       {@link CategoryClassifier}，所以"其他 / 人情 / 其他收入"这套兜底不必在这里重抄一遍</li>
 * </ol>
 *
 * <p><b>所有方法都是阻塞的</b>，调用方负责放到后台线程（{@code WiseBookApp.databaseExecutor()}）。
 */
public final class EntryTransferRepository {

    private final WiseBookDatabase database;
    private final CategoryRepository categoryRepository;
    private final EntryRepository entryRepository;
    private final CurrentLedger currentLedger;
    private final ZoneId zone;

    public EntryTransferRepository(WiseBookDatabase database, CategoryRepository categoryRepository,
                                   EntryRepository entryRepository, CurrentLedger currentLedger,
                                   ZoneId zone) {
        this.database = database;
        this.categoryRepository = categoryRepository;
        this.entryRepository = entryRepository;
        this.currentLedger = currentLedger;
        this.zone = zone;
    }

    // ------------------------------------------------------------------ 导出

    /** 一次导出的结果：CSV 文本 + 笔数（界面要说"已导出 N 笔"，笔数不能靠反数行数） */
    public static final class ExportResult {

        /** CSV 文本，<b>不含 BOM</b>（写文件时拼 {@link EntryCsv#BOM}） */
        public final String csv;
        public final int count;

        ExportResult(String csv, int count) {
            this.csv = csv;
            this.count = count;
        }
    }

    /**
     * 全部账目 → CSV。
     *
     * <p>「原话」与「原文金额」取自关联草稿：账目行上只有 {@code amount_raw}，
     * 而用户最想留住的往往是"我当时到底说了什么"（截图入口就是那一整段转写）。
     */
    public ExportResult exportAll() {
        long userId = currentLedger.id();
        CategoryTree tree = categoryRepository.loadTree(userId);

        List<EntryCsv.OutRow> rows = new ArrayList<>();
        for (EntryEntity entry : entryRepository.findAll()) {
            DraftEntity draft = database.draftDao().findById(entry.draftId);

            EntryCsv.OutRow row = new EntryCsv.OutRow();
            row.occurredAt = EntryCsv.formatDateTime(entry.occurredAt, zone);
            row.direction = entry.direction == null ? null : entry.direction.label();
            row.amountYuan = EntryCsv.yuan(entry.amountCents);
            row.categoryPath = tree.pathOf(entry.categoryId);
            row.merchant = entry.merchant;
            row.paymentMethod = entry.paymentMethod == null ? null : entry.paymentMethod.label();
            row.note = entry.note;
            row.items = entry.items == null ? null
                    : String.join(EntryCsv.ITEMS_SEPARATOR, entry.items);
            row.source = entry.source == null ? null : entry.source.label();
            row.estimated = entry.amountIsEstimated ? "是" : "否";
            row.amountRange = EntryCsv.formatRange(entry.amountLowerCents, entry.amountUpperCents);
            row.amountRaw = entry.amountRaw != null ? entry.amountRaw
                    : (draft == null ? null : draft.amountRaw);
            row.rawInput = draft == null ? null : draft.rawInput;
            row.createdAt = EntryCsv.formatDateTime(entry.createdAt, zone);
            row.id = Long.toString(entry.entryId);
            rows.add(row);
        }
        return new ExportResult(EntryCsv.write(rows), rows.size());
    }

    // ------------------------------------------------------------------ 导入

    /** 一次导入的结果，用来给用户一句实话（导了几笔、跳过几笔、几笔没对上分类） */
    public static final class ImportReport {

        /** 表头能不能认出来：认不出来说明这不是本应用导出的 CSV */
        public final boolean headerFound;
        /** 真正落进账本的笔数 */
        public final int added;
        /** 因为"本机已有同一笔"而跳过的笔数 */
        public final int skippedDuplicate;
        /** 字段缺/非法而跳过的行数（用户可能用 Excel 编辑过） */
        public final int badRows;
        /** 分类路径在本机找不到、改落默认桶的笔数 */
        public final int categoryFallback;
        /** 落库失败（理论上不该发生）的笔数 */
        public final int failed;

        ImportReport(boolean headerFound, int added, int skippedDuplicate, int badRows,
                     int categoryFallback, int failed) {
            this.headerFound = headerFound;
            this.added = added;
            this.skippedDuplicate = skippedDuplicate;
            this.badRows = badRows;
            this.categoryFallback = categoryFallback;
            this.failed = failed;
        }

        public String summary() {
            if (!headerFound) {
                return "这不是慧记导出的 CSV（没找到表头），没有导入任何账目";
            }
            StringBuilder text = new StringBuilder("导入完成：新增 ").append(added).append(" 笔");
            if (skippedDuplicate > 0) {
                text.append("，跳过重复 ").append(skippedDuplicate).append(" 笔");
            }
            if (badRows > 0) {
                text.append("，").append(badRows).append(" 行数据看不懂已跳过");
            }
            if (categoryFallback > 0) {
                text.append("，").append(categoryFallback).append(" 笔的分类在本机没有、已归入默认分类");
            }
            if (failed > 0) {
                text.append("，").append(failed).append(" 笔写入失败");
            }
            return text.toString();
        }
    }

    /**
     * 导入一份 CSV。<b>整体一个事务</b>：中途失败不会留下半批账目。
     *
     * <p>重复判定用去重键，命中即跳过——不去改已有那笔（"导入"是补数据，
     * 不是"以文件为准覆盖本机"；要覆盖得先自己删）。
     */
    public ImportReport importCsv(String text) {
        EntryCsv.ParseResult parsed = EntryCsv.parse(text);
        if (!parsed.headerFound) {
            return new ImportReport(false, 0, 0, 0, 0, 0);
        }
        ImportReport report = database.runInTransaction(() -> doImport(parsed));
        return report == null
                ? new ImportReport(true, 0, 0, parsed.skippedRows, 0, 0)
                : report;
    }

    private ImportReport doImport(EntryCsv.ParseResult parsed) {
        long userId = currentLedger.id();
        CategoryTree tree = categoryRepository.loadTree(userId);
        CategoryClassifier classifier = new CategoryClassifier(tree);
        long now = System.currentTimeMillis();

        int added = 0;
        int skipped = 0;
        int fallback = 0;
        int failed = 0;

        for (EntryCsv.InRow row : parsed.rows) {
            Direction direction = row.direction();
            Long amountCents = row.amountCents();
            Long occurredAt = row.occurredAtMillis(zone);
            if (direction == null || amountCents == null || occurredAt == null) {
                continue;                       // 已在 parse 阶段被过滤，这里是第二道
            }
            String merchant = row.merchant();

            String dedupeKey = DedupeKey.of(direction, amountCents, occurredAt, merchant);
            if (database.entryDao().countByDedupeKey(dedupeKey, userId) > 0) {
                skipped++;
                continue;
            }

            DraftEntity draft = new DraftEntity();
            draft.userId = userId;
            draft.status = DraftStatus.CONFIRMED;      // 直接可落账（下面立刻 post）
            draft.source = row.source() == null ? DraftSource.BILL_IMPORT : row.source();
            draft.evidenceType = evidenceTypeOf(draft.source);
            draft.rawInput = row.rawInput();
            draft.direction = direction;
            draft.amountCents = amountCents;
            draft.amountRaw = row.amountRaw();
            draft.amountIsEstimated = row.estimated();
            draft.amountLowerCents = row.lowerCents();
            draft.amountUpperCents = row.upperCents();
            // 导入的金额没有经过双通道校验（原文往往根本没带过来），
            // 写 PASS 等于伪造成"校验过"——NA 才是实话
            draft.amountRuleCheck = AmountRuleCheck.NA;
            draft.occurredAt = occurredAt;
            // CSV 里的日期是明确写下的，不是从口语推断的
            draft.occurredAtSource = OccurredAtSource.EXPLICIT;
            draft.paymentMethod = row.paymentMethod();
            draft.merchant = merchant;
            draft.items = row.items();
            draft.note = row.note();
            draft.createdAt = now;
            draft.updatedAt = now;
            draft.clarifyRounds = 0;
            draft.confidenceFlags = new ArrayList<>();
            draft.dedupeKey = dedupeKey;

            // 分类：把 CSV 的路径当成"模型给的候选"喂给同一个分类器，
            // 匹配不上时它自己会走默认桶（其他 / 人情 / 其他收入），口径与解析入口一致
            Long categoryId = tree.idOfPath(row.categoryPath(), direction);
            if (categoryId == null) {
                fallback++;
            } else {
                draft.categoryCandidates = Collections.singletonList(
                        new CategoryCandidate(categoryId, tree.pathOf(categoryId)));
            }
            CategoryClassifier.Result classified = classifier.classify(draft);
            if (classified.categoryId == null) {
                failed++;                       // 分类表都没播种好这种异常情况
                continue;
            }
            draft.categoryId = classified.categoryId;
            draft.rootCategoryId = classified.rootCategoryId;

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

        return new ImportReport(true, added, skipped, parsed.skippedRows, fallback, failed);
    }

    /**
     * 来源 → 证据类型。
     *
     * <p>导入的账目<b>没有原图/原音频文件</b>（CSV 里存不下文件），所以只写类型、不写路径——
     * 详情页照样能显示"这笔来自图片"，只是没有图可看。这是 CSV 这种格式的固有损失。
     */
    private static EvidenceType evidenceTypeOf(DraftSource source) {
        if (source == DraftSource.IMAGE) {
            return EvidenceType.IMAGE;
        }
        if (source == DraftSource.VOICE) {
            return EvidenceType.AUDIO;
        }
        return EvidenceType.TEXT;
    }
}
