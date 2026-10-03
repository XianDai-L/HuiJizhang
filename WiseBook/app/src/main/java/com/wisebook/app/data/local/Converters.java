package com.wisebook.app.data.local;

import androidx.room.TypeConverter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.app.domain.model.CategoryCandidate;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.ClarifyQuestion;
import com.wisebook.app.domain.model.ConfidenceFlag;
import com.wisebook.app.domain.model.ConfirmMode;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.DraftStatus;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.domain.model.OccurredAtSource;
import com.wisebook.app.domain.model.PaymentMethod;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 领域类型 ↔ SQLite 存储的转换。
 *
 * <p>分两类：
 * <ol>
 *   <li><b>枚举</b>：统一走 {@link com.wisebook.app.domain.model.CodedEnum#code()}，
 *       存小写字符串。好处有三个——读库时肉眼可读；与 D1 定稿取值一致；
 *       与发给模型的 JSON Schema {@code enum} 取值是同一份。
 *       <b>刻意不存 {@code ordinal()}</b>：枚举顺序一改，历史数据会静默错位。</li>
 *   <li><b>集合</b>：用 Gson 存成 JSON 文本。D1 里 {@code items} /
 *       {@code category_candidates} / {@code clarify_questions} 都是数组字段，
 *       单用户记账场景不需要为它们建子表、也不需要按数组元素查询，
 *       存 JSON 是这里最省事且够用的做法。</li>
 * </ol>
 *
 * <p>所有转换器对 {@code null} 一律返回 {@code null}，<b>不做空值兜底</b>：
 * 缺值该由业务层判断，在转换器里偷偷补默认值会把问题掩盖到更难排查的地方。
 */
public final class Converters {

    private static final Gson GSON = new Gson();

    private static final Type CATEGORY_CANDIDATE_LIST = new TypeToken<List<CategoryCandidate>>() {
    }.getType();
    private static final Type CLARIFY_QUESTION_LIST = new TypeToken<List<ClarifyQuestion>>() {
    }.getType();
    private static final Type STRING_LIST = new TypeToken<List<String>>() {
    }.getType();
    private static final Type STRING_LIST_FOR_FLAGS = new TypeToken<List<String>>() {
    }.getType();

    private Converters() {
    }

    // ---------------------------------------------------------------- 确认档位

    @TypeConverter
    public static String fromConfirmMode(ConfirmMode mode) {
        return mode == null ? null : mode.code();
    }

    @TypeConverter
    public static ConfirmMode toConfirmMode(String code) {
        return ConfirmMode.fromCode(code);
    }

    // ---------------------------------------------------------------- 分类方案

    @TypeConverter
    public static String fromCategoryScheme(CategoryScheme scheme) {
        return scheme == null ? null : scheme.code();
    }

    @TypeConverter
    public static CategoryScheme toCategoryScheme(String code) {
        return CategoryScheme.fromCode(code);
    }

    // ---------------------------------------------------------------- 收支方向

    @TypeConverter
    public static String fromDirection(Direction direction) {
        return direction == null ? null : direction.code();
    }

    @TypeConverter
    public static Direction toDirection(String code) {
        return Direction.fromCode(code);
    }

    // ---------------------------------------------------------------- 草稿状态

    @TypeConverter
    public static String fromDraftStatus(DraftStatus status) {
        return status == null ? null : status.code();
    }

    @TypeConverter
    public static DraftStatus toDraftStatus(String code) {
        return DraftStatus.fromCode(code);
    }

    // ---------------------------------------------------------------- 草稿来源

    @TypeConverter
    public static String fromDraftSource(DraftSource source) {
        return source == null ? null : source.code();
    }

    @TypeConverter
    public static DraftSource toDraftSource(String code) {
        return DraftSource.fromCode(code);
    }

    // ---------------------------------------------------------------- 证据类型

    @TypeConverter
    public static String fromEvidenceType(EvidenceType type) {
        return type == null ? null : type.code();
    }

    @TypeConverter
    public static EvidenceType toEvidenceType(String code) {
        return EvidenceType.fromCode(code);
    }

    // ---------------------------------------------------------------- 支付方式

    @TypeConverter
    public static String fromPaymentMethod(PaymentMethod method) {
        return method == null ? null : method.code();
    }

    @TypeConverter
    public static PaymentMethod toPaymentMethod(String code) {
        return PaymentMethod.fromCode(code);
    }

    // ------------------------------------------------------------ 发生时间来源

    @TypeConverter
    public static String fromOccurredAtSource(OccurredAtSource source) {
        return source == null ? null : source.code();
    }

    @TypeConverter
    public static OccurredAtSource toOccurredAtSource(String code) {
        return OccurredAtSource.fromCode(code);
    }

    // ------------------------------------------------------------ 金额校验结论

    @TypeConverter
    public static String fromAmountRuleCheck(AmountRuleCheck check) {
        return check == null ? null : check.code();
    }

    @TypeConverter
    public static AmountRuleCheck toAmountRuleCheck(String code) {
        return AmountRuleCheck.fromCode(code);
    }

    // ---------------------------------------------------------------- 存疑标签

    /**
     * 存疑标签是枚举列表。这里先转成 code 字符串列表再序列化，
     * 好让库里的值与 D1 取值一致；直接用 Gson 序列化枚举会写成大写的 {@code name()}。
     */
    @TypeConverter
    public static String fromConfidenceFlags(List<ConfidenceFlag> flags) {
        if (flags == null) {
            return null;
        }
        List<String> codes = new ArrayList<>(flags.size());
        for (ConfidenceFlag flag : flags) {
            codes.add(flag.code());
        }
        return GSON.toJson(codes);
    }

    @TypeConverter
    public static List<ConfidenceFlag> toConfidenceFlags(String json) {
        if (json == null) {
            return null;
        }
        List<String> codes = GSON.fromJson(json, STRING_LIST_FOR_FLAGS);
        List<ConfidenceFlag> flags = new ArrayList<>(codes.size());
        for (String code : codes) {
            flags.add(ConfidenceFlag.fromCode(code));
        }
        return flags;
    }

    // ---------------------------------------------------------------- 分类候选

    @TypeConverter
    public static String fromCategoryCandidates(List<CategoryCandidate> candidates) {
        return candidates == null ? null : GSON.toJson(candidates);
    }

    @TypeConverter
    public static List<CategoryCandidate> toCategoryCandidates(String json) {
        return json == null ? null : GSON.fromJson(json, CATEGORY_CANDIDATE_LIST);
    }

    // ---------------------------------------------------------------- 反问清单

    @TypeConverter
    public static String fromClarifyQuestions(List<ClarifyQuestion> questions) {
        return questions == null ? null : GSON.toJson(questions);
    }

    @TypeConverter
    public static List<ClarifyQuestion> toClarifyQuestions(String json) {
        return json == null ? null : GSON.fromJson(json, CLARIFY_QUESTION_LIST);
    }

    // ---------------------------------------------------------------- 商品明细

    @TypeConverter
    public static String fromStringList(List<String> values) {
        return values == null ? null : GSON.toJson(values);
    }

    @TypeConverter
    public static List<String> toStringList(String json) {
        return json == null ? null : GSON.fromJson(json, STRING_LIST);
    }
}
