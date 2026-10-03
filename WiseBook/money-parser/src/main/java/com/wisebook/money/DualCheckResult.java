package com.wisebook.money;

/**
 * 双通道校验结果。
 *
 * <p>通道一：大模型输出的结构化金额（modelCents）
 * <br>通道二：规则校验器独立重算（{@link AmountParser}）
 *
 * <p>用 {@code getClass().getSimpleName()} 可取得分支名
 * （{@code Consistent} / {@code Inconsistent} / {@code ModelMissing}
 * / {@code RuleUnparseable} / {@code NeedClarification}）。
 */
public abstract class DualCheckResult {

    DualCheckResult() {
    }

    /** 两通道一致 */
    public static final class Consistent extends DualCheckResult {

        private final AmountParseResult parsed;

        Consistent(AmountParseResult parsed) {
            this.parsed = parsed;
        }

        public AmountParseResult parsed() {
            return parsed;
        }
    }

    /** 两通道不一致 → 必然触发反问 */
    public static final class Inconsistent extends DualCheckResult {

        private final AmountParseResult parsed;
        private final long modelCents;

        Inconsistent(AmountParseResult parsed, long modelCents) {
            this.parsed = parsed;
            this.modelCents = modelCents;
        }

        public AmountParseResult parsed() {
            return parsed;
        }

        public long modelCents() {
            return modelCents;
        }
    }

    /** 模型未给出金额，规则通道可补 */
    public static final class ModelMissing extends DualCheckResult {

        private final AmountParseResult parsed;

        ModelMissing(AmountParseResult parsed) {
            this.parsed = parsed;
        }

        public AmountParseResult parsed() {
            return parsed;
        }
    }

    /** 规则通道无法解析原始片段 → 转人工确认 */
    public static final class RuleUnparseable extends DualCheckResult {

        private final Long modelCents;
        private final String rawSpan;

        RuleUnparseable(Long modelCents, String rawSpan) {
            this.modelCents = modelCents;
            this.rawSpan = rawSpan;
        }

        public Long modelCents() {
            return modelCents;
        }

        public String rawSpan() {
            return rawSpan;
        }
    }

    /** 口语表达本身无法安全折算（如「至少三十」）→ 必须反问 */
    public static final class NeedClarification extends DualCheckResult {

        private final AmountParseResult parsed;

        NeedClarification(AmountParseResult parsed) {
            this.parsed = parsed;
        }

        public AmountParseResult parsed() {
            return parsed;
        }
    }
}
