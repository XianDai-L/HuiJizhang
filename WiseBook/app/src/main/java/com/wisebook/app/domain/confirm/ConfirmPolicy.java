package com.wisebook.app.domain.confirm;

import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.app.domain.model.ConfidenceFlag;
import com.wisebook.app.domain.model.ConfirmMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 确认档位判定（D1 §5.1 / §5.2）。
 *
 * <p><b>核心是一条「或」关系</b>：档位二下不能只判金额。若只看金额，
 * AI 把「三百五」记成 305 时金额小于阈值，照样不问就落账——那道双通道校验就白做了。
 * 所以档位二的触发条件是「金额 ≥ 阈值 <b>或</b> 存疑」。
 *
 * <p>本类<b>直接使用 {@link DraftEntity}</b>，而不是为它另造一套领域模型。
 * 理由：D1 定稿的 schema 就是领域模型，两套模型之间写一遍互转代码没有任何业务含义，
 * 却会引入「忘了映射某个字段」这类静默 bug——而这里判错的后果是「该问的没问」。
 * 实体本身只是带注解的普通 Java 对象，不依赖 Android 运行时，因此本类仍可在 JVM 上单测。
 */
public final class ConfirmPolicy {

    /** 判定结论 */
    public enum Decision {
        /** 八条件全满足，直接落账，不打断用户 */
        DIRECT_POST,
        /** 进确认流程 */
        NEEDS_CONFIRM
    }

    /** 判定结论 + 依据。{@code reasons} 既是给用户看的解释，也是排查时的现场记录 */
    public static final class Verdict {

        private final Decision decision;
        private final List<String> reasons;

        Verdict(Decision decision, List<String> reasons) {
            this.decision = decision;
            this.reasons = Collections.unmodifiableList(reasons);
        }

        public Decision decision() {
            return decision;
        }

        /** 需要确认的原因；免确认时为空列表 */
        public List<String> reasons() {
            return reasons;
        }

        public boolean isDirect() {
            return decision == Decision.DIRECT_POST;
        }

        public String reasonText() {
            return String.join("；", reasons);
        }

        @Override
        public String toString() {
            return decision + (reasons.isEmpty() ? "" : "：" + reasonText());
        }
    }

    private ConfirmPolicy() {
    }

    /**
     * 判断这笔草稿能不能免确认直接落账。
     *
     * @param draft                待判定的草稿
     * @param duplicateHit         去重键是否已命中账目（D1 §5.2 条件 7）
     * @param draftsInBatch        本次输入拆出的草稿总数（条件 8）
     * @param mode                 当前确认档位
     * @param largeThresholdCents  大额阈值（分），仅档位二使用
     */
    public static Verdict decide(DraftEntity draft,
                                 boolean duplicateHit,
                                 int draftsInBatch,
                                 ConfirmMode mode,
                                 long largeThresholdCents) {

        List<String> reasons = new ArrayList<>();

        // 档位一：所有草稿都过确认页，没有例外（D1 §5.1）
        if (mode == ConfirmMode.STRICT) {
            reasons.add("当前档位为「每笔确认」");
            return new Verdict(Decision.NEEDS_CONFIRM, reasons);
        }

        // ---------------- 条件 1：规则重算与模型一致，而不是「模型自称对」
        boolean amountExplained = false;
        if (draft.amountRuleCheck != AmountRuleCheck.PASS) {
            reasons.add(draft.amountRuleCheck == AmountRuleCheck.NA
                    ? "金额无法自动校验（原文没有可重算的金额片段，或口语表达无法安全折算）"
                    : "金额规则校验未通过（模型算出的金额与规则重算不一致）");
            amountExplained = true;
        }

        // ---------------- 条件 2：约数折算值不看一眼就落账，误差会直接进报表
        if (draft.amountIsEstimated) {
            reasons.add("金额是约数折算值（如「三十左右」），需人工确认");
            amountExplained = true;
        }

        // ---------------- 条件 4：必填字段齐全
        List<String> missing = missingRequiredFields(draft);
        if (!missing.isEmpty()) {
            reasons.add("必填字段缺失：" + String.join("、", missing));
        }

        // ---------------- 条件 3 + 5：存疑标签（含分类摇摆）
        // 标签是「事实记录」：哪个判据命中就打哪个，论文统计要用（例如"多少比例的草稿金额存疑"）。
        // reasons 是「给人看的解释」，所以金额与必填这两类标签只在前面没解释过时才补一句，
        // 否则确认页会出现「金额规则校验未通过」与「金额存疑」两句同义提示。
        for (ConfidenceFlag flag : flagsOf(draft)) {
            if (flag == ConfidenceFlag.CATEGORY_SWING) {
                reasons.add("分类判定不确定");
            } else if (flag == ConfidenceFlag.AMOUNT_AMBIGUOUS) {
                if (!amountExplained) {
                    reasons.add(flag.label());
                }
            } else if (flag == ConfidenceFlag.MISSING_FIELD) {
                if (missing.isEmpty()) {
                    reasons.add(flag.label());
                }
            } else {
                reasons.add(flag.label());
            }
        }

        // ---------------- 条件 6：仅档位二比较阈值
        if (mode == ConfirmMode.LARGE
                && draft.amountCents != null
                && draft.amountCents >= largeThresholdCents) {
            reasons.add("金额达到大额阈值（" + yuan(draft.amountCents)
                    + " 元 ≥ " + yuan(largeThresholdCents) + " 元）");
        }

        // ---------------- 条件 7：命中疑似重复，交给用户判断，绝不自动合并
        if (duplicateHit) {
            reasons.add("命中疑似重复的账目，需由用户决定是否重复记账");
        }

        // ---------------- 条件 8：拆分场景一律确认（拆分边界是模型最易出错处）
        if (draftsInBatch > 1) {
            reasons.add("本次输入拆出了 " + draftsInBatch + " 笔，拆分场景一律确认");
        }

        return new Verdict(reasons.isEmpty() ? Decision.DIRECT_POST : Decision.NEEDS_CONFIRM, reasons);
    }

    /**
     * 落账前必须齐全的字段。
     *
     * <p><b>与 D1 §5.2 条件 4 有一处差异：{@code payment_method} 已不在此列</b>
     * （见 HANDOFF 决策 20）。
     *
     * <p>原因是实机验证暴露的：原句里往往根本没提怎么付的，模型也不会去猜
     * （prompt 明确要求「拿不准就留空」），于是每一笔都会因为"缺支付方式"被拦下来确认。
     * 那样一来确认档位就失去了区分度——所有草稿都要确认，等价于永远处在「每笔确认」档位，
     * 三档设定等于白设。
     *
     * <p>现在它是「原句提到了就填、没提到也不影响落账」的可选字段；
     * 用户想补的话在确认页上顺手选一个即可。
     */
    private static List<String> missingRequiredFields(DraftEntity draft) {
        List<String> missing = new ArrayList<>(4);
        if (draft.amountCents == null) {
            missing.add("金额");
        }
        if (draft.direction == null) {
            missing.add("收支方向");
        }
        if (draft.occurredAt == null) {
            missing.add("发生时间");
        }
        if (draft.categoryId == null) {
            missing.add("分类");
        }
        return missing;
    }

    private static Set<ConfidenceFlag> flagsOf(DraftEntity draft) {
        if (draft.confidenceFlags == null || draft.confidenceFlags.isEmpty()) {
            return Collections.emptySet();
        }
        // LinkedHashSet 去重且保序，标签的展示顺序因此与写入顺序一致
        return new LinkedHashSet<>(draft.confidenceFlags);
    }

    /** 分 → 整数元展示（阈值都是整百元，不需要小数） */
    private static String yuan(long cents) {
        return Long.toString(cents / 100L);
    }
}
