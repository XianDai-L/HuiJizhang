package com.wisebook.app.domain.confirm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.wisebook.app.TestFixtures;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.app.domain.model.ConfidenceFlag;
import com.wisebook.app.domain.model.ConfirmMode;

import org.junit.Test;

/**
 * 确认档位判定（D1 §5.1 / §5.2）。
 *
 * <p>重点验证两件事：三档各自的触发条件不同；以及 §5.2 里那几条
 * <b>与档位无关</b>的安全闸门在任何档位下都不能被绕过。
 */
public class ConfirmPolicyTest {

    private static final long THRESHOLD = 30_000L;   // 300 元

    private static ConfirmPolicy.Verdict decide(DraftEntity draft, ConfirmMode mode) {
        return ConfirmPolicy.decide(draft, false, 1, mode, THRESHOLD);
    }

    // ---------------------------------------------------------------- 三档

    @Test
    public void cleanSmallDraftInLargeModeGoesDirect() {
        ConfirmPolicy.Verdict verdict = decide(TestFixtures.cleanDraft(), ConfirmMode.LARGE);
        assertTrue("小额、无风险、档位二 → 免确认直落", verdict.isDirect());
        assertTrue("免确认时不该有任何理由", verdict.reasons().isEmpty());
    }

    @Test
    public void strictModeAlwaysConfirms() {
        ConfirmPolicy.Verdict verdict = decide(TestFixtures.cleanDraft(), ConfirmMode.STRICT);
        assertFalse(verdict.isDirect());
        assertTrue(verdict.reasonText().contains("每笔确认"));
    }

    @Test
    public void largeModeConfirmsAtThreshold() {
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.amountCents = THRESHOLD;
        assertFalse("恰好等于阈值即触发确认", decide(draft, ConfirmMode.LARGE).isDirect());

        draft.amountCents = THRESHOLD - 1;
        assertTrue("差一分就不触发", decide(draft, ConfirmMode.LARGE).isDirect());
    }

    @Test
    public void doubtfulModeIgnoresAmount() {
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.amountCents = 100_000_000L;   // 一百万元
        // 用户主动选了「存疑才确认」档位，就意味着接受大额也直接落账——
        // D1 §5.2 条件 6 明写「档位二下」才比较阈值
        assertTrue(decide(draft, ConfirmMode.DOUBTFUL).isDirect());
    }

    // ----------------------------------------- 与档位无关的安全闸门

    @Test
    public void ruleCheckFailureConfirmsInEveryMode() {
        for (ConfirmMode mode : new ConfirmMode[]{ConfirmMode.LARGE, ConfirmMode.DOUBTFUL}) {
            DraftEntity draft = TestFixtures.cleanDraft();
            draft.amountRuleCheck = AmountRuleCheck.FAIL;
            assertFalse("档位 " + mode + " 下规则校验失败必须确认",
                    decide(draft, mode).isDirect());
        }
    }

    @Test
    public void unparseableAmountConfirmsInEveryMode() {
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.amountRuleCheck = AmountRuleCheck.NA;
        assertFalse(decide(draft, ConfirmMode.DOUBTFUL).isDirect());
    }

    @Test
    public void estimatedAmountConfirmsInEveryMode() {
        // D1 §5.2 条件 2 与档位无关：约数折算值可能与实际有偏差，必须让人看一眼
        for (ConfirmMode mode : new ConfirmMode[]{ConfirmMode.LARGE, ConfirmMode.DOUBTFUL}) {
            DraftEntity draft = TestFixtures.cleanDraft();
            draft.amountIsEstimated = true;
            assertFalse("档位 " + mode + " 下约数金额必须确认", decide(draft, mode).isDirect());
        }
    }

    @Test
    public void missingPaymentMethodNoLongerBlocksAutoPosting() {
        // 实机反馈：原句里往往没提怎么付的，模型也不该猜。若把它算作必填，
        // 每一笔都会被拦一次确认——三档设定就失去区分度了（HANDOFF 决策 20）
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.paymentMethod = null;

        ConfirmPolicy.Verdict verdict = decide(draft, ConfirmMode.LARGE);

        assertTrue("缺支付方式不该阻止免确认直落", verdict.isDirect());
        assertTrue(verdict.reasons().isEmpty());
    }

    @Test
    public void missingCategoryStillBlocks() {
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.categoryId = null;
        ConfirmPolicy.Verdict verdict = decide(draft, ConfirmMode.LARGE);
        assertFalse(verdict.isDirect());
        assertTrue(verdict.reasonText().contains("分类"));
    }

    @Test
    public void missingAmountConfirmsEvenInDoubtfulMode() {
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.amountCents = null;
        assertFalse(decide(draft, ConfirmMode.DOUBTFUL).isDirect());
    }

    @Test
    public void categorySwingFlagConfirms() {
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.confidenceFlags = TestFixtures.flagsOf(ConfidenceFlag.CATEGORY_SWING);
        ConfirmPolicy.Verdict verdict = decide(draft, ConfirmMode.DOUBTFUL);
        assertFalse(verdict.isDirect());
        assertTrue(verdict.reasonText().contains("分类"));
    }

    @Test
    public void anyConfidenceFlagConfirms() {
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.confidenceFlags = TestFixtures.flagsOf(ConfidenceFlag.ASR_RISK);
        ConfirmPolicy.Verdict verdict = decide(draft, ConfirmMode.DOUBTFUL);
        assertFalse(verdict.isDirect());
        assertTrue(verdict.reasonText().contains(ConfidenceFlag.ASR_RISK.label()));
    }

    @Test
    public void duplicateHitConfirmsEvenInDoubtfulMode() {
        DraftEntity draft = TestFixtures.cleanDraft();
        ConfirmPolicy.Verdict verdict =
                ConfirmPolicy.decide(draft, true, 1, ConfirmMode.DOUBTFUL, THRESHOLD);
        assertFalse("命中疑似重复必须由用户决定（D1 §7 绝不自动合并）", verdict.isDirect());
    }

    @Test
    public void multiDraftBatchConfirms() {
        DraftEntity draft = TestFixtures.cleanDraft();
        ConfirmPolicy.Verdict verdict =
                ConfirmPolicy.decide(draft, false, 3, ConfirmMode.LARGE, THRESHOLD);
        assertFalse("拆分场景一律确认（D1 §5.2 条件 8）", verdict.isDirect());
        assertTrue(verdict.reasonText().contains("3 笔"));
    }

    @Test
    public void allProblemsAreReportedTogether() {
        // 一次把问题报全，用户一轮就能改完，不用来回问
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.amountCents = 500_000L;
        draft.categoryId = null;
        draft.confidenceFlags = TestFixtures.flagsOf(ConfidenceFlag.CATEGORY_SWING);
        ConfirmPolicy.Verdict verdict = decide(draft, ConfirmMode.LARGE);
        assertFalse(verdict.isDirect());
        assertEquals(3, verdict.reasons().size());
        assertTrue(verdict.reasonText().contains("分类"));
        assertTrue(verdict.reasonText().contains("必填字段缺失"));
        assertTrue(verdict.reasonText().contains("大额"));
    }
}
