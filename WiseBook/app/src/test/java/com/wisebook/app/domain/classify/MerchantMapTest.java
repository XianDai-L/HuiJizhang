package com.wisebook.app.domain.classify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Optional;

/**
 * 商户映射表（D1-A §3）。
 *
 * <p>重点在<b>关键词包含关系</b>上：D1-A 的表里，长关键词必须先于短关键词被命中，
 * 否则「京东健康」会落到「京东」的平台型分支上，分类直接错。
 */
public class MerchantMapTest {

    @Test
    public void verticalMerchantIsHardMapped() {
        assertEquals(Optional.of("餐饮>咖啡"), MerchantMap.verticalPath("星巴克"));
        assertEquals(Optional.of("交通>打车"), MerchantMap.verticalPath("滴滴"));
        assertEquals(Optional.of("娱乐>影音"), MerchantMap.verticalPath("腾讯视频"));
    }

    @Test
    public void longestKeywordWins_jdHealth() {
        assertEquals("「京东健康」是医疗，不能落到平台型「京东」的购物分支",
                Optional.of("医疗>药品"), MerchantMap.verticalPath("京东健康"));
        assertEquals("「京东」本身是平台型，没有垂直映射",
                Optional.empty(), MerchantMap.verticalPath("京东"));
        assertEquals(Optional.of("购物"), MerchantMap.platformTopLevel("京东"));
    }

    @Test
    public void longestKeywordWins_meituanFamily() {
        assertEquals(Optional.of("医疗>药品"), MerchantMap.verticalPath("美团买药"));
        assertEquals(Optional.of("交通>共享单车"), MerchantMap.verticalPath("美团单车"));
        assertEquals("「美团」本身是平台型，没有垂直映射",
                Optional.empty(), MerchantMap.verticalPath("美团"));
        assertEquals(Optional.of("餐饮"), MerchantMap.platformTopLevel("美团"));
    }

    @Test
    public void matchingIgnoresCaseAndWhitespace() {
        assertEquals(Optional.of("餐饮>咖啡"), MerchantMap.verticalPath("manner"));
        assertEquals(Optional.of("餐饮>咖啡"), MerchantMap.verticalPath("MANNER"));
        assertEquals(Optional.of("餐饮>咖啡"), MerchantMap.verticalPath("  Manner  "));
        assertEquals(Optional.of("娱乐>游戏"), MerchantMap.verticalPath("steam"));
    }

    @Test
    public void merchantContainingFamiliarBrandStillMatches() {
        // 支付账单里的商户名常带后缀，用 contains 而不是 equals 就是为了这个
        assertEquals(Optional.of("餐饮>咖啡"), MerchantMap.verticalPath("星巴克(国贸店)"));
        assertTrue(MerchantMap.isKnown("瑞幸咖啡朝阳门店"));
    }

    @Test
    public void unknownMerchantGivesNothing() {
        assertEquals(Optional.empty(), MerchantMap.verticalPath("楼下小卖部"));
        assertEquals(Optional.empty(), MerchantMap.platformTopLevel("楼下小卖部"));
        assertFalse(MerchantMap.isKnown("楼下小卖部"));
    }

    @Test
    public void nullAndBlankAreSafe() {
        assertEquals(Optional.empty(), MerchantMap.verticalPath(null));
        assertEquals(Optional.empty(), MerchantMap.verticalPath(""));
        assertEquals(Optional.empty(), MerchantMap.verticalPath("   "));
        assertFalse(MerchantMap.isKnown(null));
    }

    @Test
    public void platformMerchantsAreRecognized() {
        assertTrue(MerchantMap.isPlatformMerchant("淘宝"));
        assertTrue(MerchantMap.isPlatformMerchant("饿了么"));
        assertTrue(MerchantMap.isPlatformMerchant("盒马鲜生"));
        assertFalse(MerchantMap.isPlatformMerchant("星巴克"));
    }
}
