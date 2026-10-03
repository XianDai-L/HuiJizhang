package com.wisebook.app.data.local.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.app.domain.model.CategoryCandidate;
import com.wisebook.app.domain.model.ClarifyQuestion;
import com.wisebook.app.domain.model.ConfidenceFlag;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.DraftStatus;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.domain.model.OccurredAtSource;
import com.wisebook.app.domain.model.PaymentMethod;

import java.util.List;

/**
 * 账目草稿（D1 §3.2）——表 {@code t_draft}。
 *
 * <p><b>可空性规则</b>（贯穿全表）：解析产物一律可空，草稿自身元数据一律非空。
 * 因为草稿的本质就是「还没凑齐的一笔账」——强求非空会让「解析出一半」这种
 * 最常见的情况无处安放，而草稿天生就该能承载不完整。
 *
 * <p>草稿与账目<b>物理分表</b>（D1 §3.1）：账本表只含已确认数据，
 * 报表查询不需要过滤 status，也就不存在「忘了过滤」这种静默出错。
 */
@Entity(
        tableName = "t_draft",
        indices = {
                @Index(value = "status"),
                @Index(value = "dedupe_key"),
                @Index(value = "batch_id"),
                @Index(value = "created_at")
        }
)
public class DraftEntity {

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "draft_id")
    public long draftId;

    @ColumnInfo(name = "user_id")
    public long userId;

    @ColumnInfo(name = "status")
    public DraftStatus status;

    @ColumnInfo(name = "source")
    public DraftSource source;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    /** 落账时间；未落账为 {@code null} */
    @ColumnInfo(name = "posted_at")
    public Long postedAt;

    // ------------------------------------------------------ 交易字段（解析产物）

    @ColumnInfo(name = "direction")
    public Direction direction;

    /** 折算值（分），参与统计。整数分，禁止浮点（D1 §3.6） */
    @ColumnInfo(name = "amount_cents")
    public Long amountCents;

    /** 约数区间下界（分），可空 */
    @ColumnInfo(name = "amount_lower_cents")
    public Long amountLowerCents;

    /** 约数区间上界（分），可空 */
    @ColumnInfo(name = "amount_upper_cents")
    public Long amountUpperCents;

    /** 原文片段，如「三百五」。双通道校验的规则通道靠它重算 */
    @ColumnInfo(name = "amount_raw")
    public String amountRaw;

    /** 是否为约数折算值（非精确） */
    @ColumnInfo(name = "amount_is_estimated")
    public boolean amountIsEstimated;

    @ColumnInfo(name = "amount_rule_check")
    public AmountRuleCheck amountRuleCheck;

    @ColumnInfo(name = "occurred_at")
    public Long occurredAt;

    @ColumnInfo(name = "occurred_at_source")
    public OccurredAtSource occurredAtSource;

    /** 最末级分类 id */
    @ColumnInfo(name = "category_id")
    public Long categoryId;

    /**
     * 所属一级分类 id。录入时一次算好——这是「简单 / 标准」双向切换
     * 零迁移的基础：报表永远按 {@code root_category_id} 聚合。
     */
    @ColumnInfo(name = "root_category_id")
    public Long rootCategoryId;

    @ColumnInfo(name = "category_candidates")
    public List<CategoryCandidate> categoryCandidates;

    @ColumnInfo(name = "payment_method")
    public PaymentMethod paymentMethod;

    /** 商户或对方。<b>只放具体商户/平台/人，不放动作与品类</b>（实测坑，见 HANDOFF §8-10） */
    @ColumnInfo(name = "merchant")
    public String merchant;

    @ColumnInfo(name = "items")
    public List<String> items;

    @ColumnInfo(name = "note")
    public String note;

    // -------------------------------------------------------------- 草稿专属

    /** 原始输入文本；语音入口时是 ASR 转写文本。用户说「这笔记错了」时要能回看原话 */
    @ColumnInfo(name = "raw_input")
    public String rawInput;

    @ColumnInfo(name = "evidence_type")
    public EvidenceType evidenceType;

    /** 音频 / 图片文件路径。P1 恒为 {@code null}（只有文本入口） */
    @ColumnInfo(name = "evidence_ref")
    public String evidenceRef;

    /** 可选元信息，如 {@code {bbox, page_no}}，以 JSON 文本存放 */
    @ColumnInfo(name = "evidence_meta")
    public String evidenceMeta;

    /** 一次输入拆多笔时共享的分组标签。<b>只是标签，不共享状态</b>（D1 §4.2） */
    @ColumnInfo(name = "batch_id")
    public String batchId;

    @ColumnInfo(name = "split_index")
    public int splitIndex;

    /** 模型名 + 版本，如 {@code siliconflow:Qwen/...}，用于多模型对比实验 */
    @ColumnInfo(name = "model")
    public String model;

    /** 待回答的反问清单 */
    @ColumnInfo(name = "clarify_questions")
    public List<ClarifyQuestion> clarifyQuestions;

    /** 已反问轮数，上限 3，超限转手动填表（D1 §4.2） */
    @ColumnInfo(name = "clarify_rounds")
    public int clarifyRounds;

    /** 存疑标签（枚举标签，不是分数） */
    @ColumnInfo(name = "confidence_flags")
    public List<ConfidenceFlag> confidenceFlags;

    /** 去重键，见 {@link com.wisebook.app.domain.draft.DedupeKey} */
    @ColumnInfo(name = "dedupe_key")
    public String dedupeKey;

    // ---------------------------------------------------------------- 落账后

    /** 指向 {@code t_entry}，落账成功后写入 */
    @ColumnInfo(name = "entry_id")
    public Long entryId;

    @Override
    public String toString() {
        return "Draft#" + draftId + "{" + status + ", " + amountCents + "分, "
                + (merchant == null ? "" : merchant) + "}";
    }
}
