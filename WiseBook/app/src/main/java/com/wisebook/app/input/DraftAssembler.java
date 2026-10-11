package com.wisebook.app.input;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.classify.CategoryClassifier;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.draft.AmountAssembler;
import com.wisebook.app.domain.model.CategoryCandidate;
import com.wisebook.app.domain.model.CodedEnum;
import com.wisebook.app.domain.model.CodedEnums;
import com.wisebook.app.domain.model.ConfidenceFlag;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.DraftStatus;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.domain.model.PaymentMethod;
import com.wisebook.app.domain.time.RelativeTimeResolver;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 把「模型返回的一份账目 JSON」装配成 {@link DraftEntity}。
 *
 * <p>P1 时这段逻辑住在 {@code ChatDraftParser} 的私有方法里，因为那时只有一个入口。
 * P2 的截图入口要产出的东西<b>与文字入口完全一样</b>——同样要算金额双通道、
 * 同样要跑分类判定、同样要打存疑标签——所以把它提出来共用。
 * 两处各写一份的代价不是"多敲几行"，而是迟早有一处忘了跟着改，
 * 而这里判错的后果是「该问的没问」。
 *
 * <p><b>模型只负责它擅长的事</b>，三件事都做了"模型给原料、代码做判断"的分工：
 * <ul>
 *   <li>金额：模型给 {@code amountCents} 与 {@code amountRaw}，规则通道独立重算（{@link AmountAssembler}）</li>
 *   <li>时间：模型只抄时间短语原文，换算交给 {@link RelativeTimeResolver}</li>
 *   <li>分类：模型给候选路径，最终由 {@link CategoryClassifier} 按三层优先级定夺</li>
 * </ul>
 *
 * <p>本类只做装配，<b>不落库、不改状态</b>：保存、去重检查、档位判定与状态流转
 * 由 {@code DraftRepository} 统一处理。这样拆开之后，装配逻辑可以脱离数据库与线程被单测。
 */
public final class DraftAssembler {

    private final CategoryTree tree;
    private final CategoryClassifier classifier;
    private final String modelLabel;
    private final long userId;
    private final ZoneId zone;

    /**
     * @param modelLabel 写入 {@code t_draft.model} 的溯源标签
     * @param tree       当前分类树
     * @param userId     单用户场景恒为 1
     */
    public DraftAssembler(CategoryTree tree, String modelLabel, long userId, ZoneId zone) {
        this.tree = tree;
        this.classifier = new CategoryClassifier(tree);
        this.modelLabel = modelLabel;
        this.userId = userId;
        this.zone = zone;
    }

    /**
     * @param payload      模型返回的一份账目 JSON
     * @param rawInput     原话（文字入口）或转写文本（截图入口），落进 {@code t_draft.raw_input}
     * @param source       入口来源，落进 {@code t_draft.source}
     * @param evidenceType 证据类型；原图/原音频不留存时只写类型、不写路径
     * @param now          「现在」，既是草稿的创建时间，也是「今天/昨天」的参照点
     */
    public DraftEntity assemble(JsonObject payload, String rawInput, DraftSource source,
                                EvidenceType evidenceType, LocalDateTime now) {
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
        draft.source = source;
        draft.evidenceType = evidenceType;
        draft.rawInput = rawInput;
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
        //
        // 2026-10-08 起也不再产生 SPLIT_UNCERTAIN（决策 50）：那个标签原先来自模型自报的
        // transactionCount（"这句话有几笔"），而现在拆分由模型直接给数组完成、字段已删。
        // 枚举值保留只为读历史数据（与 CATEGORY_SWING 同一个处理）。
        // 「拆出多笔要不要一律确认」另有开关：ConfirmPolicy 的条件 8，等用户拍板后再接线。
        List<ConfidenceFlag> flags = new ArrayList<>();
        if (amount.ambiguous()) {
            flags.add(ConfidenceFlag.AMOUNT_AMBIGUOUS);
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

    /**
     * 把批量工具返回的 {@code drafts} 数组装配成一串草稿（决策 50 前只服务截图入口，
     * 现在两个入口共用这一段）。
     *
     * <p>放在这里而不是各解析器里各写一遍：这段循环就是 {@link #assemble} 加上
     * "批次标签 + 顺序号"两件事，两个入口的差别只有 {@code source} / {@code evidenceType} /
     * {@code rawInput} 三个入参。<b>「同一条逻辑被两处复用、只做对了一处」这个项目已经踩过两次
     * （HANDOFF §8-23、§8-35）</b>，能共用就别复制。
     *
     * <p>不是对象的元素直接跳过（schema 校验本就该挡住，这里只是不让一个坏元素毁掉整批）。
     *
     * @param elements      {@code drafts} 数组
     * @param rawInput      原话（文字入口）或转写文本（截图入口），整批共用
     * @param batchId       批次标签，由 {@link #batchId} 生成；同一次输入的几笔共享它
     */
    public List<DraftEntity> assembleBatch(JsonArray elements, String rawInput, DraftSource source,
                                           EvidenceType evidenceType, String batchId,
                                           LocalDateTime now) {
        List<DraftEntity> drafts = new ArrayList<>();
        if (elements == null) {
            return drafts;
        }
        int index = 0;
        for (JsonElement element : elements) {
            if (!element.isJsonObject()) {
                continue;
            }
            DraftEntity draft = assemble(element.getAsJsonObject(), rawInput, source,
                    evidenceType, now);
            // 一次输入里的多笔共享同一个批次标签（D1 §4.2：只是标签，不共享状态），
            // splitIndex 记录它在原话/原图里的顺序——确认页按它排序才与用户看到的一致
            draft.batchId = batchId;
            draft.splitIndex = index;
            drafts.add(draft);
            index++;
        }
        return drafts;
    }

    /**
     * 批次标签：前缀 + 「现在」派生的毫秒。
     *
     * <p>用「现在」派生而不是 UUID：同一毫秒里解析两次输入在实际使用中不会发生，
     * 而<b>可复现</b>的标签让单测能直接断言它（UUID 只能断言"非空"）。
     * 前缀用入口区分（{@code TXT-} / {@code IMG-}），排查时一眼看得出这批草稿从哪来。
     */
    public String batchId(String prefix, LocalDateTime now) {
        return prefix + now.atZone(zone).toInstant().toEpochMilli();
    }

    // ------------------------------------------------------------- payload 读取

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
