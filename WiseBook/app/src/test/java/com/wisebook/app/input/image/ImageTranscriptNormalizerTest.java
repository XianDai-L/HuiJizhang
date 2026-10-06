package com.wisebook.app.input.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 转写文本的归一化（P2 实验的直接产物）。
 *
 * <p>要守住的核心只有一条：<b>只删记号，不动内容</b>。
 * 尤其是金额——负号与小数点被吃掉一个字符，整笔账就是另一个数，
 * 而这种错法用户很难发现（数字看起来仍然"像个金额"）。
 */
public class ImageTranscriptNormalizerTest {

    @Test
    public void stripsMarkdownHeadings() {
        String normalized = ImageTranscriptNormalizer.normalize(
                "# 记账本\n#### 转账\n18:34 | 转给某人");

        assertEquals("记账本\n转账\n18:34 | 转给某人", normalized);
    }

    @Test
    public void stripsEmphasisMarkers() {
        assertEquals("总支出¥192.30", ImageTranscriptNormalizer.normalize("**总支出¥192.30**"));
    }

    /**
     * 最要紧的一条：行首的负号是金额符号，不是列表符号。
     */
    @Test
    public void keepsNegativeAmountsIntact() {
        String normalized = ImageTranscriptNormalizer.normalize(
                "#### 转账\n**-100.00**\n- 16.30");

        assertTrue("负数金额必须原样保留", normalized.contains("-100.00"));
        assertTrue("列表符号后的金额也要保留", normalized.contains("16.30"));
        assertFalse("列表符号本身该被剥掉", normalized.contains("- 16.30"));
    }

    /** 有序列表要剥，但小数不能误伤 */
    @Test
    public void stripsOrderedListButKeepsDecimals() {
        String normalized = ImageTranscriptNormalizer.normalize("1. 打车 28\n2. 咖啡 1.5");

        assertTrue(normalized.contains("打车 28"));
        assertTrue("小数不能被当成有序列表", normalized.contains("1.5"));
    }

    @Test
    public void collapsesBlankLines() {
        assertEquals("a\nb", ImageTranscriptNormalizer.normalize("a\n\n\n\nb"));
    }

    @Test
    public void emptyInputIsSafe() {
        assertEquals("", ImageTranscriptNormalizer.normalize(null));
        assertEquals("", ImageTranscriptNormalizer.normalize("   \n  "));
    }
}
