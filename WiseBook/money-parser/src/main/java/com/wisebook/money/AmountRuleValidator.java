package com.wisebook.money;

/**
 * 金额双通道校验器。
 *
 * <p>设计动机（见 D1 §2）：大模型自报的置信度不可靠，
 * 因此用确定性规则独立重算一遍，作为「是否存疑」的判据。
 *
 * <p>适用范围：<b>只有金额</b>有可穷举的语法规则；
 * 分类、商户、支付方式无法双通道校验，只能靠枚举与判定规则。
 */
public class AmountRuleValidator {

    /**
     * @param modelCents 大模型输出的金额（分）；{@code null} 表示模型未给出
     * @param rawSpan    金额对应的原文片段，如「三百五」
     */
    public DualCheckResult check(Long modelCents, String rawSpan) {
        // 不用 String.isBlank()：Android 上它是 API 33 才有的（minSdk 26），详见 HANDOFF §8
        if (rawSpan == null || rawSpan.trim().isEmpty()) {
            return new DualCheckResult.RuleUnparseable(modelCents, rawSpan == null ? "" : rawSpan);
        }

        AmountParseResult parsed = AmountParser.parse(rawSpan);
        if (parsed == null) {
            return new DualCheckResult.RuleUnparseable(modelCents, rawSpan);
        }
        if (parsed.isNeedsClarification()) {
            return new DualCheckResult.NeedClarification(parsed);
        }
        if (modelCents == null) {
            return new DualCheckResult.ModelMissing(parsed);
        }

        boolean consistent;
        if (parsed.getPrecision() == AmountPrecision.EXACT) {
            // 精确值必须完全相等
            consistent = modelCents == parsed.getCents();
        } else if (parsed.getLowerCents() != null && parsed.getUpperCents() != null) {
            // 约数场景：模型值落在区间内即视为一致，避免误报
            consistent = modelCents >= parsed.getLowerCents()
                    && modelCents <= parsed.getUpperCents();
        } else {
            consistent = modelCents == parsed.getCents();
        }

        if (consistent) {
            return new DualCheckResult.Consistent(parsed);
        }
        return new DualCheckResult.Inconsistent(parsed, modelCents);
    }
}
