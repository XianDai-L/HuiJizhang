package com.wisebook.app.domain.classify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 账单分类规则表。
 *
 * <p>三类要守住的东西：
 * <ol>
 *   <li><b>该命中的要命中</b>：商户名（滴滴、12306）、商品名（打车、12306消费）</li>
 *   <li><b>优先级</b>：商户名压过商品名，具体的品牌压过泛词（京东健康 vs 京东）</li>
 *   <li><b>不该猜的不猜</b>：学校名、人名一律不命中——这是决策 46 留下的取向，
 *       "宁可让人改一次，也不要悄悄错"</li>
 * </ol>
 */
public class BillCategoryRulesTest {

    private static String pathOf(String merchant, String... items) {
        List<String> list = items.length == 0 ? null : Arrays.asList(items);
        BillCategoryRules.Hit hit = BillCategoryRules.match(merchant, list);
        return hit == null ? null : hit.path;
    }

    // ------------------------------------------------------------------ 商户名

    @Test
    public void rideHailingMerchantHitsTheTaxiCategory() {
        assertEquals("交通>打车", pathOf("滴滴出行"));
        assertEquals("交通>打车", pathOf("高德顺风车"));
    }

    @Test
    public void railwayMerchantHitsTheTrainCategory() {
        assertEquals("交通>机票火车", pathOf("中铁网络"));
        assertEquals("交通>机票火车", pathOf("12306"));
    }

    @Test
    public void longMerchantNameStillMatches() {
        // 真实账单里的商户名可以是完整工商名，用「包含」而不是「相等」才认得出
        assertEquals("交通>公交地铁", pathOf("钦州市泰禾公共交通有限公司"));
    }

    @Test
    public void convenienceStoreHitsTheDrinkCategory() {
        // D1-A §2.2 定过的口径：便利店/超市买的瓶装饮料归「餐饮>饮品」
        assertEquals("餐饮>饮品", pathOf("便利超人"));
        assertEquals("餐饮>饮品", pathOf("美宜佳便利店"));
    }

    @Test
    public void platformMerchantWithoutAnyHintOnlyGetsTheTopLevel() {
        // 平台型看不出去买了什么，硬猜二级必然错一半——只挂一级（D1-A §3.2）
        assertEquals("购物", pathOf("淘宝平台商户"));
        assertEquals("购物", pathOf("京东"));
    }

    @Test
    public void specificBrandWinsOverTheGenericOneItContains() {
        assertEquals("京东健康在表里排在京东前面，所以要命中药品而不是购物",
                "医疗>药品", pathOf("京东健康"));
    }

    // ------------------------------------------------------------------ 商品名

    @Test
    public void itemTextIsUsedWhenTheMerchantSaysNothing() {
        // 商户名「某某出行」推不出什么，商品「滴滴快车打车-容师傅-10月01日行程」能
        assertEquals("交通>打车", pathOf("某出行", "滴滴快车打车-容师傅-10月01日行程"));
        assertEquals("交通>机票火车", pathOf("某网络", "12306消费"));
    }

    @Test
    public void merchantWinsOverItem() {
        // 一笔在中国电信付的、商品写着「打车」的账——商户名更可信
        assertEquals("通讯>话费", pathOf("中国电信", "打车费"));
    }

    @Test
    public void itemsAreScannedInOrderAndTheFirstHitWins() {
        BillCategoryRules.Hit hit = BillCategoryRules.match("某超市",
                Arrays.asList("不认识的商品", "外卖一份"));

        assertNotNull(hit);
        assertEquals("餐饮>外卖", hit.path);
    }

    // ------------------------------------------------------------------ 不该猜的

    /**
     * 学校名是机构名，推不出品类：可能是学费，也可能是食堂一顿 5 元的午饭。
     *
     * <p>硬映射到「教育」会让后者错得看不出来——那正是决策 46 要避免的事
     * （宁可落「其他」让用户改一次，也不要悄悄错）。
     */
    @Test
    public void schoolNamesAreDeliberatelyNotGuessed() {
        assertNull(pathOf("广西工业学院"));
        assertNull(pathOf("桂林电子科技大学"));
    }

    @Test
    public void personNamesAreDeliberatelyNotGuessed() {
        assertNull(pathOf("邱湘程"));
        assertNull(pathOf("梁洪荣 (糖诗宋词缘曲)"));
    }

    @Test
    public void unknownMerchantAndItemFallThroughToTheCaller() {
        assertNull(pathOf("某某小店", "一份吃的"));
        assertNull(pathOf(null));
        assertNull(pathOf(""));
        assertNull(BillCategoryRules.match(null, Collections.<String>emptyList()));
    }

    // ------------------------------------------------------------------ 归一化

    @Test
    public void matchingIgnoresCaseAndWhitespace() {
        // 复用去重键那套归一化：两处对「同一个商户」的判据必须一致
        assertEquals("娱乐>游戏", pathOf("Steam 平台"));
        assertEquals("餐饮>咖啡", pathOf(" 星巴克 "));
    }

    @Test
    public void hitCarriesAReasonForTheDetailPage() {
        BillCategoryRules.Hit hit = BillCategoryRules.match("滴滴出行", null);

        assertNotNull(hit);
        assertTrue("判据要能让人看出是按哪一条命中的（详情页的「分类依据」就是它）",
                hit.reason.contains("商户名") && hit.reason.contains("滴滴"));
    }
}
