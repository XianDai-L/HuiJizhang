package com.wisebook.app.input.image;

import com.google.gson.JsonArray;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.input.DraftAssembler;
import com.wisebook.app.input.DraftParseResult;
import com.wisebook.app.input.DraftToolSchema;
import com.wisebook.llm.ExtractionResult;
import com.wisebook.llm.ImageReader;
import com.wisebook.llm.LlmClient;
import com.wisebook.llm.LlmException;
import com.wisebook.llm.StructuredExtractor;
import com.wisebook.llm.ToolSchema;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 截图入口的解析器：图 →（转写）→ 文字 →（同一套文本管线）→ 一张或多张草稿。
 *
 * <p><b>它是"路线 B"的落点</b>（`docs/P2-截图实验.md` §4）：截图被退化成"文本的另一个来源"，
 * 之后的金额双通道校验、分类判定、去重、档位判定全部复用文字入口那条管线——
 * 所以这里没有一行金额或分类的代码，只有"取文字、拆多笔、交给装配器"三件事。
 *
 * <p>两步调用，两步都可能失败，且<b>失败都算降级而不是异常</b>：
 * 转写失败（网络/限流/图里没字）与拆笔失败（模型没给出合法数组）都会变成
 * 「转人工填写」——这是 D1 定的正常路径。
 *
 * <p>2026-10-08 起「取数组 → 装配」那一段由 {@link DraftAssembler#assembleBatch} 承担，
 * 与文字入口共用（决策 50）。本类保留的差异只有三处：
 * 第一步多一次转写、{@code source/evidenceType} 是 IMAGE、批次前缀是 {@code IMG-}。
 *
 * <p>与 {@code ChatDraftParser} 一样，本类只解析、不落库、不改状态。
 */
public final class ImageDraftParser {

    /** 截图入口的批次标签前缀（文字入口是 {@code TXT-}） */
    private static final String BATCH_PREFIX = "IMG-";

    private final ImageReader imageReader;
    private final StructuredExtractor extractor;
    private final ToolSchema batchTool;
    private final DraftAssembler assembler;

    /**
     * @param imageReader 图 → 文字的转写器（由组装点提供，缺 Key 时那里会给出 null，
     *                    调用方应先判断再构造本解析器）
     * @param client      文本模型客户端，用于第二步的结构化
     * @param modelLabel  写入 {@code t_draft.model} 的溯源标签
     */
    public ImageDraftParser(ImageReader imageReader, LlmClient client, String modelLabel,
                            CategoryTree tree, CategoryScheme scheme, long userId, ZoneId zone) {
        this.imageReader = imageReader;
        this.batchTool = DraftToolSchema.buildBatch(tree, scheme);
        this.extractor = new StructuredExtractor(client);
        this.assembler = new DraftAssembler(tree, modelLabel, userId, zone);
    }

    /**
     * 解析一张图，可能产出多张草稿。<b>阻塞方法，禁止在主线程调用。</b>
     *
     * @param now 「现在」，相对时间的参照点；同时也是批次标签的来源
     */
    public DraftParseResult parse(ImageInput image, LocalDateTime now) {
        if (image == null || image.bytes == null || image.bytes.length == 0) {
            return DraftParseResult.rejected("没有拿到图片内容");
        }

        String raw;
        try {
            raw = imageReader.readImage(image.bytes, image.mimeType, ImageDraftPrompt.TRANSCRIBE);
        } catch (LlmException e) {
            return DraftParseResult.degraded(
                    "读取图片失败（" + e.kind() + "）：" + e.getMessage(), 0);
        }

        String transcript = ImageTranscriptNormalizer.normalize(raw);
        if (transcript.isEmpty()) {
            return DraftParseResult.degraded("图里没识别出文字", 1);
        }

        // 把分组标题上的日期下推到每一行，再交给模型（见 ImageTranscriptDatePropagator）：
        // 「标题管到哪几条记录」是模型判得最不稳的地方，这一段改成代码算。
        // rawInput 仍用未下推的原文——详情页要让用户看到「OCR 到底读到了什么」，
        // 而补出来的日期是给模型用的加工结果，不是图上原本有的字。
        ExtractionResult extraction = extractor.extract(ImageDraftPrompt.DRAFTS,
                ImageTranscriptDatePropagator.propagate(transcript), batchTool);
        if (!extraction.isOk()) {
            return DraftParseResult.degraded(extraction.degradeReason(), extraction.attemptCount());
        }

        JsonArray array = extraction.payload().getAsJsonArray(DraftToolSchema.FIELD_DRAFTS);
        List<DraftEntity> drafts = assembler.assembleBatch(array, transcript,
                DraftSource.IMAGE, EvidenceType.IMAGE,
                assembler.batchId(BATCH_PREFIX, now), now);
        if (drafts.isEmpty()) {
            return DraftParseResult.degraded("没从这张图里读出账目", extraction.attemptCount());
        }

        return DraftParseResult.ok(drafts, extraction.attemptCount(),
                extraction.firstAttemptSucceeded(), extraction.payload().toString());
    }

    /** 供排查用：本入口实际发给模型的工具定义 */
    public ToolSchema tool() {
        return batchTool;
    }
}
