package com.wisebook.money;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 带货币符号与正负号的金额。
 *
 * <p>这几条来自 P2 截图入口的实机验证：截图上的金额几乎总带符号
 * （{@code ￥7.00}、{@code -15.00}、{@code +8.00}），而规则通道原先碰到这些前缀
 * 就整段解析失败——于是"金额明明读对了，却提示无法自动校验、每笔都要用户点一下确认"。
 * 这与决策 20/24 花力气消掉的是同一类体验问题。
 *
 * <p>设计上：符号只被<b>跳过</b>，不折进数值。方向由 {@code draft.direction} 表达，
 * 金额在系统里恒为非负（金额带符号会让报表口径与去重键都变得难以推理）。
 */
public class SignedAmountTest {

    @Test
    public void yuanSymbolIsSkipped() {
        assertEquals(700L, (long) AmountParser.parse("￥7.00").getCents());
        assertEquals(700L, (long) AmountParser.parse("¥7.00").getCents());
    }

    @Test
    public void leadingMinusDoesNotMakeTheAmountNegative() {
        AmountParseResult parsed = AmountParser.parse("-15.00");

        assertNotNull(parsed);
        assertEquals(1500L, (long) parsed.getCents());
        assertFalse("符号不影响「是不是约数」这件事", parsed.isEstimated());
    }

    @Test
    public void leadingPlusIsSkipped() {
        assertEquals(800L, (long) AmountParser.parse("+8.00").getCents());
    }

    @Test
    public void symbolAndUnitCanCoexist() {
        assertEquals(2800L, (long) AmountParser.parse("￥28元").getCents());
    }

    @Test
    public void hyphenBetweenTwoNumbersIsNotAnAmount() {
        // 「3-4」是范围不是金额：跳过连字符后两个裸数字互相冲突，
        // 必须判为无法解析——宁可人工确认，也不要猜一个数出来
        assertNull(AmountParser.parse("3-4"));
    }
}
