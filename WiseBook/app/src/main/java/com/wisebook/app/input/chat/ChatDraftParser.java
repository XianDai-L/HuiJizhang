package com.wisebook.app.input.chat;

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
import java.util.Collections;

/**
 * 对话入口的解析器：一句口语 →（模型）→ 结构化 JSON →（领域判据）→ 账目草稿。
 *
 * <p>它是「多入口 + 统一解析管线」里第一个落地、也是 P1 唯一的入口（D2 §7）。
 * 后续的截图（P2）与语音（P3）要做的事完全一样：把输入变成文本，然后复用
 * <b>同一个</b> {@link DraftToolSchema} 与 {@link DraftPrompt}。
 *
 * <p>本类只负责「解析」，<b>不落库、不改状态</b>：草稿的保存、去重检查、
 * 档位判定与状态流转由仓储层统一处理。这样拆开之后，解析器可以完全脱离数据库与线程被单测。
 *
 * <p>P2 起「JSON → 草稿实体」那一段搬去了 {@link DraftAssembler}，与截图入口共用
 * ——两个入口要产出的东西本来就该一模一样。
 */
public final class ChatDraftParser {

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
        this.tool = DraftToolSchema.build(tree, scheme);
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

        DraftEntity draft = assembler.assemble(extraction.payload(), input,
                DraftSource.TEXT_CHAT, EvidenceType.TEXT, now);
        return DraftParseResult.ok(Collections.singletonList(draft),
                extraction.attemptCount(), extraction.firstAttemptSucceeded(),
                extraction.payload().toString());
    }

    /** 供排查用：本次解析实际发给模型的工具定义 */
    public ToolSchema tool() {
        return tool;
    }

    public LlmClient client() {
        return client;
    }
}
