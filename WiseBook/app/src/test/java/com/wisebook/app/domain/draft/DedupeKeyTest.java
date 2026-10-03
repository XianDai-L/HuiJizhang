package com.wisebook.app.domain.draft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import com.wisebook.app.domain.model.Direction;

import org.junit.Test;

/**
 * 去重键（D1 §7）。
 */
public class DedupeKeyTest {

    /**
     * 刻意取一个<b>正好落在整分钟边界上</b>的时间戳
     * （29833334 × 60000）。若随便取一个毫秒值，「同一分钟内」的用例
     * 会因为加了几十秒就跨到下一分钟而失败——那测的就不是精度设定，而是运气。
     */
    private static final long T = 1_790_000_040_000L;

    @Test
    public void sameInputsProduceSameKey() {
        String a = DedupeKey.of(Direction.EXPENSE, 2800L, T, "滴滴");
        String b = DedupeKey.of(Direction.EXPENSE, 2800L, T, "滴滴");
        assertEquals(a, b);
    }

    @Test
    public void withinSameMinuteIsConsideredSame() {
        // 同一笔账被记两次，两次的秒数几乎不可能一样，所以精度只到分钟
        String a = DedupeKey.of(Direction.EXPENSE, 2800L, T, "滴滴");
        String b = DedupeKey.of(Direction.EXPENSE, 2800L, T + 45_000L, "滴滴");
        assertEquals("同一分钟内的两次记录应视为同一笔", a, b);
    }

    @Test
    public void differentMinutesAreDifferent() {
        String a = DedupeKey.of(Direction.EXPENSE, 2800L, T, "滴滴");
        String b = DedupeKey.of(Direction.EXPENSE, 2800L, T + DedupeKey.MINUTE_MILLIS, "滴滴");
        assertNotEquals(a, b);
    }

    @Test
    public void merchantIsNormalized() {
        // 英文商户名：大小写与首尾空白都不影响
        String didi = DedupeKey.of(Direction.EXPENSE, 2800L, T, "DiDi");
        assertEquals(didi, DedupeKey.of(Direction.EXPENSE, 2800L, T, "didi"));
        assertEquals(didi, DedupeKey.of(Direction.EXPENSE, 2800L, T, "  DIDI  "));

        // 中文商户名：只去空白（中间夹的空格也算）
        String chinese = DedupeKey.of(Direction.EXPENSE, 2800L, T, "滴滴");
        assertEquals(chinese, DedupeKey.of(Direction.EXPENSE, 2800L, T, " 滴 滴 "));

        // 归一化不做「转拼音」这种智能处理：中文与拼音本来就不是同一种写法，
        // 强行等同会把本该两条的记录合并掉，比漏去重更糟
        assertNotEquals(chinese, didi);
    }

    @Test
    public void nullAndEmptyMerchantAreEquivalent() {
        assertEquals(DedupeKey.of(Direction.EXPENSE, 2800L, T, null),
                DedupeKey.of(Direction.EXPENSE, 2800L, T, ""));
    }

    @Test
    public void amountDifferenceOfOneCentBreaksTheKey() {
        assertNotEquals(DedupeKey.of(Direction.EXPENSE, 2800L, T, "滴滴"),
                DedupeKey.of(Direction.EXPENSE, 2799L, T, "滴滴"));
    }

    @Test
    public void directionMatters() {
        assertNotEquals(DedupeKey.of(Direction.EXPENSE, 2800L, T, "张三"),
                DedupeKey.of(Direction.INCOME, 2800L, T, "张三"));
    }

    @Test
    public void nullDirectionIsRejected() {
        assertThrows(NullPointerException.class,
                () -> DedupeKey.of(null, 2800L, T, "滴滴"));
    }

    @Test
    public void keyIsStableAndHexadecimal() {
        String key = DedupeKey.of(Direction.EXPENSE, 2800L, T, "滴滴");
        assertEquals("SHA-256 十六进制固定 64 位", 64, key.length());
        assertEquals(key, key.toLowerCase());
    }
}
