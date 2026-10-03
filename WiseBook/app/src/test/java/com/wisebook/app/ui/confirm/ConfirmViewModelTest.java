package com.wisebook.app.ui.confirm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 确认页「改了什么才算人工核过」的判据。
 *
 * <p>这两条测试来自一个实机 bug：用户说「我买游戏花了283.4」第一次要确认，
 * 在确认页点一下「保存修改并重新校验」就过了——看起来像系统两次判断不一致。
 *
 * <p>根因是当时把「输入框里能解析出金额」当成了「用户核过金额」，
 * 于是什么都没改也会把「规则校验未通过」抹掉。双通道校验是 D1 §2 用来防
 * "模型金额算错"的防线，被一个不改动任何内容的点击拆掉，等于白做。
 *
 * <p>现在钉成用例：<b>只有值真的变了，才算了人工核过</b>。
 */
public class ConfirmViewModelTest {

    // ------------------------------------------------------------ 元 ↔ 分

    @Test
    public void parsesDecimalYuan() {
        assertEquals(Long.valueOf(28_340L), ConfirmViewModel.parseYuanToCents("283.4"));
    }

    @Test
    public void parsesIntegerYuan() {
        assertEquals(Long.valueOf(28_000L), ConfirmViewModel.parseYuanToCents("280"));
    }

    @Test
    public void roundsBeyondTwoDecimals() {
        assertEquals(Long.valueOf(1_235L), ConfirmViewModel.parseYuanToCents("12.345"));
    }

    @Test
    public void rejectsBlankAndNonNumericAndNonPositive() {
        assertNull("空输入表示「没有金额」，不是 0", ConfirmViewModel.parseYuanToCents(""));
        assertNull(ConfirmViewModel.parseYuanToCents("   "));
        assertNull(ConfirmViewModel.parseYuanToCents("二十八"));
        assertNull(ConfirmViewModel.parseYuanToCents("0"));
        assertNull(ConfirmViewModel.parseYuanToCents("-5"));
    }

    // ------------------------------------------- 什么才算「人工核过金额」

    @Test
    public void unchangedAmountDoesNotClearTheDoubt() {
        // 用户报的现场：金额一格没动，点「保存修改并重新校验」
        Long unchanged = ConfirmViewModel.parseYuanToCents("283.4");

        assertFalse("金额没变就不该清除质疑，否则「保存」等于一张万能通行证",
                ConfirmViewModel.amountDoubtsResolvedByEditing(28_340L, unchanged));
    }

    @Test
    public void changedAmountClearsTheDoubt() {
        assertTrue(ConfirmViewModel.amountDoubtsResolvedByEditing(28_340L,
                ConfirmViewModel.parseYuanToCents("283.5")));
    }

    @Test
    public void fillingInAMissingAmountClearsTheDoubt() {
        assertTrue("原本没有金额、用户补上了，这也是人工核过",
                ConfirmViewModel.amountDoubtsResolvedByEditing(null,
                        ConfirmViewModel.parseYuanToCents("283.4")));
    }

    @Test
    public void unparseableInputNeverClearsTheDoubt() {
        // 输入不合法时草稿里的金额保持原样，质疑自然也不该消失
        assertFalse(ConfirmViewModel.amountDoubtsResolvedByEditing(
                28_340L, ConfirmViewModel.parseYuanToCents("abc")));
        assertFalse(ConfirmViewModel.amountDoubtsResolvedByEditing(
                28_340L, ConfirmViewModel.parseYuanToCents("")));
    }

    @Test
    public void sameValueWrittenDifferentlyStillCountsAsUnchanged() {
        // 「283.40」与「283.4」是同一个数：用户只是补了个零，不该被当成"改过"
        assertFalse(ConfirmViewModel.amountDoubtsResolvedByEditing(28_340L,
                ConfirmViewModel.parseYuanToCents("283.40")));
    }
}
