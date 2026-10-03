package com.wisebook.app.domain.draft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.wisebook.app.domain.model.AmountRuleCheck;

import org.junit.Test;

/**
 * 金额装配（D1 §2 的双通道校验落点）。
 *
 * <p>它把 {@code money-parser} 的五个校验分支逐一映射到草稿字段上，
 * 所以这里逐分支对齐断言——映射错了，整条金额防线就形同虚设。
 */
public class AmountAssemblerTest {

    @Test
    public void consistentExactAmountPasses() {
        AmountAssembler.Outcome outcome = AmountAssembler.assemble(2800L, "28");

        assertEquals(Long.valueOf(2800L), outcome.amountCents);
        assertEquals(AmountRuleCheck.PASS, outcome.ruleCheck);
        assertFalse(outcome.estimated);
        assertFalse(outcome.ambiguous());
        assertFalse(outcome.needsClarification);
    }

    @Test
    public void modelMissingAmountStillPasses() {
        // 模型没给金额、规则能补：不存在"模型算错"的可能，所以判 PASS，
        // 让小额无风险的单子能正常免确认落账
        AmountAssembler.Outcome outcome = AmountAssembler.assemble(null, "二十八");

        assertEquals(Long.valueOf(2800L), outcome.amountCents);
        assertEquals(AmountRuleCheck.PASS, outcome.ruleCheck);
        assertFalse(outcome.ambiguous());
    }

    @Test
    public void inconsistentAmountUsesRuleValueAndFails() {
        // 模型把「三百五」记成 305 元，规则重算 350 元
        AmountAssembler.Outcome outcome = AmountAssembler.assemble(30500L, "三百五");

        assertEquals("采用规则重算值", Long.valueOf(35000L), outcome.amountCents);
        assertEquals(AmountRuleCheck.FAIL, outcome.ruleCheck);
        assertTrue(outcome.ambiguous());
        assertTrue("判定依据要能说清是哪两个数不一致",
                outcome.detail.contains("350") && outcome.detail.contains("305"));
    }

    @Test
    public void approvedApproximationInsideItsRangeIsConsistent() {
        AmountAssembler.Outcome outcome = AmountAssembler.assemble(3000L, "三十左右");

        assertEquals(Long.valueOf(3000L), outcome.amountCents);
        assertEquals(Long.valueOf(2500L), outcome.lowerCents);
        assertEquals(Long.valueOf(3500L), outcome.upperCents);
        assertEquals(AmountRuleCheck.PASS, outcome.ruleCheck);
        assertTrue("约数毕竟是折算值", outcome.estimated);
        assertTrue(outcome.ambiguous());
    }

    @Test
    public void approximationOutsideItsRangeFails() {
        // 规则说「三十左右」是 25~35 元，模型却给了 40 元
        AmountAssembler.Outcome outcome = AmountAssembler.assemble(4000L, "三十左右");

        assertEquals(AmountRuleCheck.FAIL, outcome.ruleCheck);
        assertEquals(Long.valueOf(3000L), outcome.amountCents);
        assertTrue(outcome.estimated);
    }

    @Test
    public void oneSidedExpressionRequiresClarification() {
        // 「至少三十」只有下界，折算会严重低估并漏掉确认流程（D1 §1.2.2）
        AmountAssembler.Outcome outcome = AmountAssembler.assemble(3000L, "至少三十");

        assertTrue(outcome.needsClarification);
        assertNull("必须反问，此时不该有任何金额值", outcome.amountCents);
        assertEquals(AmountRuleCheck.NA, outcome.ruleCheck);
        assertTrue(outcome.detail.contains("只有下界"));
    }

    @Test
    public void unparseableRawSpanFallsBackToModelValueAsNotApplicable() {
        AmountAssembler.Outcome outcome = AmountAssembler.assemble(5000L, "随便给");

        assertEquals(Long.valueOf(5000L), outcome.amountCents);
        assertEquals("规则解析不了就不能假装校验通过",
                AmountRuleCheck.NA, outcome.ruleCheck);
        assertTrue(outcome.ambiguous());
    }

    @Test
    public void zeroAmountIsTreatedAsMissing() {
        // 0 元的账目不是账目。两条路都要堵：模型自己填 0，
        // 以及原文里就是「0」被规则通道解析成 0 分
        AmountAssembler.Outcome fromRaw = AmountAssembler.assemble(0L, "0");
        assertNull(fromRaw.amountCents);
        assertEquals(AmountRuleCheck.NA, fromRaw.ruleCheck);
        assertTrue(fromRaw.ambiguous());

        AmountAssembler.Outcome modelOnly = AmountAssembler.assemble(0L, null);
        assertNull(modelOnly.amountCents);
        assertEquals(AmountRuleCheck.NA, modelOnly.ruleCheck);
    }

    @Test
    public void missingRawSpanIsNotApplicable() {
        AmountAssembler.Outcome outcome = AmountAssembler.assemble(5000L, null);

        assertEquals(Long.valueOf(5000L), outcome.amountCents);
        assertEquals(AmountRuleCheck.NA, outcome.ruleCheck);
    }
}
