package com.wisebook.money;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 带小数点的金额。
 *
 * <p>这几条来自实机报的一个真问题：用户说「我买游戏花了283.4」，
 * 模型给的 28340 分是对的，但<b>规则通道解析不了「283.4」</b>——
 * 于是双通道校验只能判「无法校验」，每一笔都被拦下来要求确认。
 *
 * <p>根因是词法扫描只认数字与中文数字，碰到 {@code '.'} 直接放弃整段。
 * 而小数点恰恰是最确定的信号：它明确把元和角分分开了，
 * 比「三十块五」那种要靠上下文推断角位的写法可靠得多。
 */
public class DecimalAmountTest {

    @Test
    public void oneDecimalDigitIsJiao() {
        AmountParseResult parsed = AmountParser.parse("283.4");

        assertNotNull("小数金额必须能被解析，否则双通道校验对最常见的一类写法直接失效", parsed);
        assertEquals(28_340L, (long) parsed.getCents());
        assertFalse("283.4 是确定值，不是约数", parsed.isEstimated());
    }

    @Test
    public void twoDecimalDigitsAreJiaoAndFen() {
        AmountParseResult parsed = AmountParser.parse("283.45");

        assertNotNull(parsed);
        assertEquals(28_345L, (long) parsed.getCents());
    }

    @Test
    public void trailingZeroDoesNotChangeTheValue() {
        // 「283.4」与「283.40」是同一个数，不该一个能解析、一个不能
        assertEquals(28_340L, (long) AmountParser.parse("283.4").getCents());
        assertEquals(28_340L, (long) AmountParser.parse("283.40").getCents());
    }

    @Test
    public void unitWordAfterTheDecimalIsRedundantNotHarmful() {
        assertEquals(350L, (long) AmountParser.parse("3.5元").getCents());
        assertEquals(350L, (long) AmountParser.parse("3.5块").getCents());
    }

    @Test
    public void decimalPointWithoutDigitsIsNotAnAmount() {
        // 「283.」没法判断后面还有没有内容，宁可判为无法解析让人工确认，
        // 也不要猜一个数出来——猜错会静默进报表
        assertNull(AmountParser.parse("283."));
    }

    @Test
    public void integersStillWorkWithoutAnyUnit() {
        // 改动不能影响原来就支持的形式：全串无单位按元处理
        assertEquals(28_340L, (long) AmountParser.parse("283.4").getCents());
        assertEquals(28_300L, (long) AmountParser.parse("283").getCents());
    }
}
