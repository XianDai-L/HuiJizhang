package com.wisebook.app.domain.classify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.wisebook.app.TestFixtures;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.model.CategoryCandidate;
import com.wisebook.app.domain.model.Direction;

import org.junit.Test;

import java.util.List;

/**
 * 分类判定（D1 §6.4 / D1-A §4）。
 *
 * <p>删除商户映射表之后（HANDOFF 决策 46）要守住的东西一共三条：
 *
 * <ol>
 *   <li><b>模型给什么就用什么</b>，不叠加任何摇摆判据</li>
 *   <li><b>模型说不出所以然时落该方向的默认桶</b>（支出/收入是「其他」，转账是「人情」），
 *       而不是反问用户</li>
 *   <li><b>商户名不再影响分类</b>——这是删除映射表换来的行为，必须有测试钉住，
 *       否则哪天有人"顺手把映射表加回来"也不会被发现</li>
 * </ol>
 *
 * <p>这里同样不再有任何 {@code swing} 相关断言——那个概念已经删掉了（决策 30）。
 */
public class CategoryClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier(TestFixtures.tree());

    private static DraftEntity draft(String merchant, Long amountCents,
                                     List<CategoryCandidate> candidates) {
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.merchant = merchant;
        draft.rawInput = "随便一句话";
        draft.amountCents = amountCents;
        draft.items = null;
        draft.categoryCandidates = candidates;
        draft.categoryId = null;
        draft.rootCategoryId = null;
        return draft;
    }

    private static List<CategoryCandidate> candidate(long id, String name) {
        return List.of(new CategoryCandidate(id, name));
    }

    // ---------------------------------------------- 第一段：模型给的分类

    @Test
    public void adoptsTheModelCategoryWhateverTheAmount() {
        // 金额再大、商户再陌生，也不再因为分类去打断用户：
        // 报表按一级聚合，二级选错不影响任何统计数字
        CategoryClassifier.Result result = classifier.classify(
                draft("楼下小馆", 500_000L, candidate(TestFixtures.CAT_TAKEOUT, "餐饮>外卖")));

        assertEquals(Long.valueOf(TestFixtures.CAT_TAKEOUT), result.categoryId);
        assertTrue(result.reason.contains("采用模型"));
    }

    /**
     * 删除映射表的行为哨兵：商户名本身不再决定分类。
     *
     * <p>星巴克原本会硬映射到「餐饮&gt;咖啡」，现在一律听模型的——
     * 因为截图里出现的商户名不一定与这笔账有关（决策 46）。
     */
    @Test
    public void merchantNameNoLongerDecidesTheCategory() {
        CategoryClassifier.Result result = classifier.classify(
                draft("星巴克", 3300L, candidate(TestFixtures.CAT_TAKEOUT, "餐饮>外卖")));

        assertEquals("模型说是外卖就是外卖，不再被商户名覆盖",
                Long.valueOf(TestFixtures.CAT_TAKEOUT), result.categoryId);
    }

    @Test
    public void knownMerchantWithoutModelCategoryFallsBackInsteadOfGuessing() {
        // 商户名再眼熟，模型没给分类时也不猜——落默认桶
        CategoryClassifier.Result result = classifier.classify(draft("星巴克", 3300L, List.of()));

        assertEquals(Long.valueOf(TestFixtures.CAT_MISC), result.categoryId);
    }

    // ---------------------------------------------------- 兜底：默认桶

    @Test
    public void missingCategoryFallsBackToOther() {
        CategoryClassifier.Result result = classifier.classify(draft("楼下小馆", 5000L, List.of()));

        assertEquals("没有分类可用就归入「其他」，而不是反问用户",
                Long.valueOf(TestFixtures.CAT_MISC), result.categoryId);
        assertEquals(Long.valueOf(TestFixtures.CAT_MISC), result.rootCategoryId);
        assertTrue(result.reason.contains("其他"));
    }

    @Test
    public void unknownCategoryIdFallsBackToOther() {
        // 模型瞎编了一个分类（或用户删过分类），落不到本地树上
        CategoryClassifier.Result result = classifier.classify(
                draft("楼下小馆", 5000L, candidate(999L, "幽灵分类")));

        assertEquals(Long.valueOf(TestFixtures.CAT_MISC), result.categoryId);
    }

    @Test
    public void incomeWithoutCategoryFallsBackToOtherIncome() {
        DraftEntity draft = draft("某公司", 5000L, List.of());
        draft.direction = Direction.INCOME;

        assertEquals("收入侧的兜底是「其他收入」，与支出侧的「其他」不是同一行",
                Long.valueOf(TestFixtures.CAT_MISC_INCOME), classifier.classify(draft).categoryId);
    }

    @Test
    public void transferWithoutCategoryFallsBackToSocial() {
        // 转账复用支出分类树（决策 27），默认落「人情」而不是「其他」（决策 32）。
        // 实机反馈：模型常把转账判成「其他」，而那是个什么都说明不了的桶
        DraftEntity draft = draft("张三", 200_000L, List.of());
        draft.direction = Direction.TRANSFER;

        assertEquals(Long.valueOf(TestFixtures.CAT_SOCIAL), classifier.classify(draft).categoryId);
    }

    @Test
    public void modelSayingOtherForATransferCountsAsNotSayingAnything() {
        // 转账给「其他」等于"没给"：这个位置留给「人情」有信息量得多
        DraftEntity draft = draft("张三", 200_000L, List.of());
        draft.direction = Direction.TRANSFER;
        draft.categoryCandidates = List.of(
                new CategoryCandidate(TestFixtures.CAT_MISC, "其他"));

        assertEquals(Long.valueOf(TestFixtures.CAT_SOCIAL), classifier.classify(draft).categoryId);
    }

    @Test
    public void modelSayingSomethingSpecificForATransferIsStillRespected() {
        // 「人情」只是兜底，不是硬规则：模型给出别的一级分类时照旧采用
        DraftEntity draft = draft("物业", 20_000L, List.of());
        draft.direction = Direction.TRANSFER;
        draft.categoryCandidates = List.of(
                new CategoryCandidate(TestFixtures.CAT_FOOD, "餐饮"));

        assertEquals(Long.valueOf(TestFixtures.CAT_FOOD), classifier.classify(draft).categoryId);
    }

    @Test
    public void otherDirectionIsUnaffectedByTheTransferRule() {
        // 一笔普通支出给「其他」是正常判断，不该被上面那条规则改掉
        DraftEntity draft = draft("路边摊", 2_000L, List.of());
        draft.categoryCandidates = List.of(
                new CategoryCandidate(TestFixtures.CAT_MISC, "其他"));

        assertEquals(Long.valueOf(TestFixtures.CAT_MISC), classifier.classify(draft).categoryId);
    }
}
