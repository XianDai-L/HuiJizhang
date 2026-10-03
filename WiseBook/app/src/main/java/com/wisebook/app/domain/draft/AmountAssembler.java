package com.wisebook.app.domain.draft;

import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.money.AmountParseResult;
import com.wisebook.money.AmountRuleValidator;
import com.wisebook.money.DualCheckResult;

/**
 * 金额装配：把「模型算出的金额」与「规则重算的金额」合成草稿上的那几个金额字段（D1 §2）。
 *
 * <p>它是双通道校验的<b>落点</b>：校验器（{@code money-parser}）只负责给出结论，
 * 而「结论怎么映射成 {@code amount_cents} / {@code amount_is_estimated} /
 * {@code amount_rule_check} 三个字段」这件事必须有人定，否则每个入口（对话、截图、语音）
 * 都各写一遍，迟早不一致。所以放在领域层，由所有入口共用。
 *
 * <p><b>不一致时采用「规则重算值」而不是模型值</b>：走到这条分支已经说明模型算错了，
 * 而规则是确定性的。同时把 {@code amount_rule_check} 置为 {@code FAIL}，
 * 于是这笔必然进确认流程（D1 §2.2），用户看到的就是规则算出的那个值。
 */
public final class AmountAssembler {

    private static final AmountRuleValidator VALIDATOR = new AmountRuleValidator();

    /** 装配结果，直接对应 {@code t_draft} 的金额字段 */
    public static final class Outcome {

        /** 折算值（分）；{@code null} 表示没得到可信金额 */
        public final Long amountCents;

        public final Long lowerCents;

        public final Long upperCents;

        /** 是否为约数折算值 */
        public final boolean estimated;

        public final AmountRuleCheck ruleCheck;

        /** 原文片段，回填给草稿的 {@code amount_raw} */
        public final String rawSpan;

        /** 口语表达本身无法安全折算（如「至少三十」），必须反问 */
        public final boolean needsClarification;

        /** 判定依据，用于排查与界面解释 */
        public final String detail;

        Outcome(Long amountCents, Long lowerCents, Long upperCents, boolean estimated,
                AmountRuleCheck ruleCheck, String rawSpan, boolean needsClarification,
                String detail) {
            this.amountCents = amountCents;
            this.lowerCents = lowerCents;
            this.upperCents = upperCents;
            this.estimated = estimated;
            this.ruleCheck = ruleCheck;
            this.rawSpan = rawSpan;
            this.needsClarification = needsClarification;
            this.detail = detail;
        }

        /** 金额是否「不够确定」——用于打 {@code amount_ambiguous} 标签 */
        public boolean ambiguous() {
            return needsClarification
                    || estimated
                    || amountCents == null
                    || ruleCheck != AmountRuleCheck.PASS;
        }

        @Override
        public String toString() {
            return "Amount{" + amountCents + "分, estimated=" + estimated
                    + ", rule=" + ruleCheck + "} — " + detail;
        }
    }

    private AmountAssembler() {
    }

    /**
     * @param modelCents 模型输出的金额（分）；{@code null} 表示模型没给出
     * @param amountRaw  金额对应的原文片段，如「三百五」
     */
    public static Outcome assemble(Long modelCents, String amountRaw) {
        Outcome outcome = rawAssemble(modelCents, amountRaw);

        // 0 元的账目不是账目。模型可能填 0，规则通道也可能把原文里的「0」解析成 0 分，
        // 两条路都要堵：与其让一个 0 混进统计，不如当成"没得到金额"去反问。
        // 这条归一化放在最后统一做，是为了让上面每条分支都不必各自记得这件事。
        if (outcome.amountCents != null && outcome.amountCents <= 0L) {
            return new Outcome(null, null, null, false, AmountRuleCheck.NA, outcome.rawSpan,
                    outcome.needsClarification,
                    "解析出的金额不是正数，视为未提供金额（" + outcome.detail + "）");
        }
        return outcome;
    }

    private static Outcome rawAssemble(Long modelCents, String amountRaw) {
        DualCheckResult check = VALIDATOR.check(modelCents, amountRaw);

        if (check instanceof DualCheckResult.Consistent consistent) {
            return fromParsed(consistent.parsed(), AmountRuleCheck.PASS,
                    "规则重算与模型一致：" + consistent.parsed().getDisplay());
        }

        if (check instanceof DualCheckResult.ModelMissing missing) {
            // 模型没给金额，规则通道能补——此时不存在"模型算错"的可能，
            // 所以判 PASS，让小额无风险的单子能正常免确认落账
            return fromParsed(missing.parsed(), AmountRuleCheck.PASS,
                    "模型未给出金额，采用规则重算值：" + missing.parsed().getDisplay());
        }

        if (check instanceof DualCheckResult.Inconsistent inconsistent) {
            return fromParsed(inconsistent.parsed(), AmountRuleCheck.FAIL,
                    "规则重算 " + inconsistent.parsed().getDisplay()
                            + " 与模型给出的 "
                            + AmountParseResult.formatYuan(inconsistent.modelCents()) + " 不一致");
        }

        if (check instanceof DualCheckResult.NeedClarification clarification) {
            // 「至少三十」这类只有下界的表达：折算会严重低估并漏掉确认流程（D1 §1.2.2）
            return new Outcome(null, null, null, false, AmountRuleCheck.NA,
                    clarification.parsed().getRawSpan(), true,
                    clarification.parsed().getClarifyReason());
        }

        if (check instanceof DualCheckResult.RuleUnparseable unparseable) {
            // 规则通道解析不了（例如 model 给了金额但原文片段不可解析）。
            // 只能退而采用模型值，并把校验标为不适用——不能假装它通过了。
            return new Outcome(unparseable.modelCents(), null, null, false,
                    AmountRuleCheck.NA, unparseable.rawSpan(), false,
                    "规则通道无法解析金额片段，只能采用模型给出的值");
        }

        throw new IllegalStateException(
                "未覆盖的双通道校验分支：" + check.getClass().getSimpleName());
    }

    private static Outcome fromParsed(AmountParseResult parsed, AmountRuleCheck ruleCheck,
                                      String detail) {
        return new Outcome(parsed.getCents(), parsed.getLowerCents(), parsed.getUpperCents(),
                parsed.isEstimated(), ruleCheck, parsed.getRawSpan(), false, detail);
    }
}
