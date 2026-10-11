package com.wisebook.app.input.chat;

import com.google.gson.JsonArray;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.input.DraftAssembler;
import com.wisebook.app.input.DraftParseResult;
import com.wisebook.app.input.DraftPrompt;
import com.wisebook.app.input.DraftToolSchema;
import com.wisebook.llm.ExtractionResult;
import com.wisebook.llm.LlmClient;
import com.wisebook.llm.StructuredExtractor;
import com.wisebook.llm.ToolSchema;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 对话入口的解析器：一句口语 →（模型）→ 结构化 JSON →（领域判据）→ 一到多张账目草稿。
 *
 * <p>它是「多入口 + 统一解析管线」里第一个落地、也是 P1 唯一的入口（D2 §7）。
 *
 * <p><b>2026-10-08 起它改用批量工具</b>（决策 50，依据 `docs/对话多笔录入实验.md`）：
 * 一句话里含好几笔时，模型直接给出 {@code drafts} 数组，这里产出 N 张草稿，
 * 而不是像 P1 那样"只解析第一笔 + 报 transactionCount"（决策 14）。
 * 用数组工具是实验数据定的：多笔句笔数、逐笔金额、逐笔方向全对，
 * 且单笔句没有付出代价（首次通过 7/7、笔数 7/7）。
 *
 * <p>于是本类与 {@code ImageDraftParser} 的结构完全对称——同样是"取数组 → 交给
 * {@link DraftAssembler#assembleBatch} → 返回一组草稿"，差别只有
 * {@code source} / {@code evidenceType} / 批次前缀三处。这正是"截图退化为文本来源、
 * 两个入口共用一条管线"这个判断走到位之后该有的样子。
 *
 * <p>本类只负责「解析」，<b>不落库、不改状态</b>：草稿的保存、去重检查、
 * 档位判定与状态流转由仓储层统一处理。
 */
public final class ChatDraftParser {

    /**
     * 文字入口的批次标签前缀。
     *
     * <p>与截图入口的 {@code IMG-} 分开：同一批草稿从哪来，看标签就知道，
     * 排查时不必再去翻 {@code source} 列。
     */
    private static final String BATCH_PREFIX = "TXT-";

    private final LlmClient client;
    private final StructuredExtractor extractor;
    private final ToolSchema tool;
    private final DraftAssembler assembler;
    private final ZoneId zone;

    /**
     * @param client     模型客户端（测试时传假客户端即可）
     * @param modelLabel 写入 {@code t_draft.model} 的溯源标签
     * @param tree       当前分类树
     * @param scheme     当前分类方案，决定 schema 里的分类枚举给到几级
     * @param userId     单用户场景恒为 1
     */
    public ChatDraftParser(LlmClient client, String modelLabel, CategoryTree tree,
                           CategoryScheme scheme, long userId) {
        this(client, modelLabel, tree, scheme, userId, ZoneId.systemDefault());
    }

    /** 允许注入时区，便于测试固定「现在」与地区 */
    public ChatDraftParser(LlmClient client, String modelLabel, CategoryTree tree,
                           CategoryScheme scheme, long userId, ZoneId zone) {
        this.client = client;
        this.zone = zone;
        this.tool = DraftToolSchema.buildBatch(tree, scheme);
        this.extractor = new StructuredExtractor(client);
        this.assembler = new DraftAssembler(tree, modelLabel, userId, zone);
    }

    /** 用真实当前时间解析 */
    public DraftParseResult parse(String userInput) {
        return parse(userInput, LocalDateTime.now(zone));
    }

    /**
     * @param now 「现在」——既是草稿的创建时间，也是「今天/昨天」这类相对时间的参照点。
     *            显式传入是为了让单测不受时钟影响
     */
    public DraftParseResult parse(String userInput, LocalDateTime now) {
        String input = userInput == null ? "" : userInput.trim();
        if (input.isEmpty()) {
            return DraftParseResult.rejected("没有输入内容");
        }

        ExtractionResult extraction = extractor.extract(DraftPrompt.SYSTEM, input, tool);
        if (!extraction.isOk()) {
            // 降级是 D1 设计好的正常路径，不是异常
            return DraftParseResult.degraded(extraction.degradeReason(), extraction.attemptCount());
        }

        JsonArray array = extraction.payload().getAsJsonArray(DraftToolSchema.FIELD_DRAFTS);
        List<DraftEntity> drafts = assembler.assembleBatch(array, input,
                DraftSource.TEXT_CHAT, EvidenceType.TEXT,
                assembler.batchId(BATCH_PREFIX, now), now);
        if (drafts.isEmpty()) {
            // schema 本该挡住"没有 drafts / 元素不是对象"（drafts 是必填的对象数组）；
            // 真走到这里就按"没读出账目"降级——与截图入口同一种处理，不抛异常
            return DraftParseResult.degraded("没从这句话里读出账目", extraction.attemptCount());
        }

        return DraftParseResult.ok(drafts, extraction.attemptCount(),
                extraction.firstAttemptSucceeded(), extraction.payload().toString());
    }

    /** 供排查用：本次解析实际发给模型的工具定义 */
    public ToolSchema tool() {
        return tool;
    }

    public LlmClient client() {
        return client;
    }
}
