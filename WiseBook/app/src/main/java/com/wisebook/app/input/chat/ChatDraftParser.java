package com.wisebook.app.input.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wisebook.app.data.local.entity.CategoryEntity;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.classify.CategoryClassifier;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.draft.AmountAssembler;
import com.wisebook.app.domain.model.CategoryCandidate;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.CodedEnum;
import com.wisebook.app.domain.model.CodedEnums;
import com.wisebook.app.domain.model.ConfidenceFlag;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.DraftStatus;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.domain.model.PaymentMethod;
import com.wisebook.app.domain.time.RelativeTimeResolver;
import com.wisebook.llm.ExtractionResult;
import com.wisebook.llm.LlmClient;
import com.wisebook.llm.StructuredExtractor;
import com.wisebook.llm.ToolSchema;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话入口的解析器：一句口语 →（模型）→ 结构化 JSON →（领域判据）→ 账目草稿。
 *
 * <p>它是「多入口 + 统一解析管线」里第一个落地、也是 P1 唯一的入口（D2 §7）。
 * 后续的截图（P2）与语音（P3）入口要做的事完全一样：把输入变成文本，然后复用
 * <b>同一个</b> {@link DraftToolSchema} 与 {@link DraftPrompt}。
 *
 * <p>本类只负责「解析」，<b>不落库、不改状态</b>：草稿的保存、去重检查、
 * 档位判定与状态流转由仓储层统一处理（P1-4）。这样拆开之后，
 * 解析器可以完全脱离数据库与线程调度被单测——用假客户端就能把每条分支走一遍。
 *
 * <p><b>模型只负责它擅长的事。</b>金额、时间、分类这三件事都做了"模型给原料、
 * 代码做判断"的分工：
 * <ul>
 *   <li>金额：模型给 {@code amountCents} 与 {@code amountRaw}，规则通道独立重算（{@link AmountAssembler}）</li>
 *   <li>时间：模型只抄时间短语原文，换算交给 {@link RelativeTimeResolver}</li>
 *   <li>分类：模型给候选路径，最终由 {@link CategoryClassifier} 按三层优先级定夺</li>
 * </ul>
 */
public final class ChatDraftParser {

    private final LlmClient client;
    private final StructuredExtractor extractor;
    private final ToolSchema tool;
    private final CategoryTree tree;
    private final CategoryClassifier classifier;
    private final String modelLabel;
    private final long userId;
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
        this.modelLabel = modelLabel;
        this.tree = tree;
        this.classifier = new CategoryClassifier(tree);
        this.userId = userId;
        this.zone = zone;
        this.tool = DraftToolSchema.build(tree, scheme);
        this.extractor = new StructuredExtractor(client);
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

        DraftEntity draft = toDraft(input, extraction.payload(), now);
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

    // ------------------------------------------------------------------ 映射

    private DraftEntity toDraft(String input, JsonObject payload, LocalDateTime now) {
        Direction direction = readEnum(payload, DraftToolSchema.FIELD_DIRECTION, Direction.class);

        // 「金额必须是正数」这条规则由 AmountAssembler 统一把关（模型填 0 与原文是「0」两条路
        // 都要堵），这里只负责把两个原始值取出来
        AmountAssembler.Outcome amount = AmountAssembler.assemble(
                readLong(payload, DraftToolSchema.FIELD_AMOUNT_CENTS),
                readString(payload, DraftToolSchema.FIELD_AMOUNT_RAW));

        RelativeTimeResolver.Result time = RelativeTimeResolver.resolve(
                readString(payload, DraftToolSchema.FIELD_OCCURRED_AT_TEXT), now, zone);

        long nowMillis = now.atZone(zone).toInstant().toEpochMilli();

        DraftEntity draft = new DraftEntity();
        draft.userId = userId;
        draft.status = DraftStatus.DRAFT;
        draft.source = DraftSource.TEXT_CHAT;
        draft.evidenceType = EvidenceType.TEXT;
        draft.rawInput = input;
        draft.createdAt = nowMillis;
        draft.updatedAt = nowMillis;
        draft.postedAt = null;
        draft.entryId = null;
        draft.batchId = null;
        draft.splitIndex = 0;
        draft.clarifyQuestions = null;
        draft.clarifyRounds = 0;
        draft.model = modelLabel;

        draft.direction = direction;
        draft.amountCents = amount.amountCents;
        draft.amountLowerCents = amount.lowerCents;
        draft.amountUpperCents = amount.upperCents;
        draft.amountRaw = amount.rawSpan;
        draft.amountIsEstimated = amount.estimated;
        draft.amountRuleCheck = amount.ruleCheck;

        draft.occurredAt = time.occurredAtMillis;
        draft.occurredAtSource = time.source;

        draft.paymentMethod = readEnum(payload, DraftToolSchema.FIELD_PAYMENT_METHOD,
                PaymentMethod.class);
        draft.merchant = readString(payload, DraftToolSchema.FIELD_MERCHANT);
        draft.items = readStringList(payload, DraftToolSchema.FIELD_ITEMS);
        draft.note = readString(payload, DraftToolSchema.FIELD_NOTE);

        // 模型给的分类路径 → 候选。方向不匹配（例如一笔支出选了「工资」）在这里就被挡下：
        // 解析不出 id 就不进候选，最终表现为"分类没定下来 → 反问用户"，比硬猜安全
        List<CategoryCandidate> candidates = new ArrayList<>();
        addCandidate(candidates, readString(payload, DraftToolSchema.FIELD_CATEGORY_PATH), direction);
        draft.categoryCandidates = candidates;

        CategoryClassifier.Result classification = classifier.classify(draft);
        draft.categoryId = classification.categoryId;
        draft.rootCategoryId = classification.rootCategoryId;
        if (!classification.candidates.isEmpty()) {
            draft.categoryCandidates = classification.candidates;
        }

        // 注意这里<b>不会</b>再产生 CATEGORY_SWING：分类判定已不再输出"不确定"信号
        // （HANDOFF 决策 30）。分类要么被确定性规则定死，要么采用模型的，要么兜到「其他」
        List<ConfidenceFlag> flags = new ArrayList<>();
        if (amount.ambiguous()) {
            flags.add(ConfidenceFlag.AMOUNT_AMBIGUOUS);
        }
        if (readTransactionCount(payload) > 1) {
            flags.add(ConfidenceFlag.SPLIT_UNCERTAIN);
        }
        draft.confidenceFlags = flags;

        return draft;
    }

    /**
     * 把模型给的分类路径解析成候选；解析不出来就忽略（分类层会兜到「其他」）。
     *
     * <p>候选名存的是<b>完整路径</b>（{@code 娱乐>游戏}）而不是末级名（{@code 游戏}）：
     * 「AI 处理详情」面板要靠它让人一眼看出模型选到了哪一支，
     * 而只写「游戏」时，用户没法判断这到底是「娱乐&gt;游戏」还是别处的「游戏」。
     */
    private void addCandidate(List<CategoryCandidate> candidates, String path, Direction direction) {
        if (path == null) {
            return;
        }
        Long id = tree.idOfPath(path, direction);
        if (id == null) {
            return;
        }
        for (CategoryCandidate existing : candidates) {
            if (existing.categoryId == id) {
                return;
            }
        }
        String resolved = tree.pathOf(id);
        candidates.add(new CategoryCandidate(id, resolved == null ? path : resolved));
    }

    // ------------------------------------------------------------- payload 读取

    private static int readTransactionCount(JsonObject payload) {
        Long value = readLong(payload, DraftToolSchema.FIELD_TRANSACTION_COUNT);
        if (value == null || value < 1L || value > DraftToolSchema.MAX_TRANSACTION_COUNT) {
            return 1;
        }
        return value.intValue();
    }

    private static String readString(JsonObject payload, String field) {
        JsonElement element = payload.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        String value = element.getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    private static Long readLong(JsonObject payload, String field) {
        JsonElement element = payload.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            // 类型不符理论上已被 schema 校验挡掉；真到这一步就当作"没填"，不抛异常
            return null;
        }
    }

    private static List<String> readStringList(JsonObject payload, String field) {
        JsonElement element = payload.get(field);
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) {
                continue;
            }
            String value = item.getAsString().trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values.isEmpty() ? null : values;
    }

    private static <E extends Enum<E> & CodedEnum> E readEnum(JsonObject payload, String field,
                                                              Class<E> type) {
        String code = readString(payload, field);
        if (code == null) {
            return null;
        }
        try {
            return CodedEnums.fromCode(type, code);
        } catch (IllegalArgumentException e) {
            // schema 的 enum 约束本该挡住非法取值，走到这里说明有人改 schema 或枚举时没对齐。
            // 当成"没填"而不是抛异常：草稿阶段留空是可接受的，崩溃不行
            return null;
        }
    }
}
