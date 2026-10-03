package com.wisebook.app.domain.confirm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.wisebook.app.TestFixtures;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.app.domain.model.CategoryCandidate;
import com.wisebook.app.domain.model.ClarifyQuestion;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.PaymentMethod;

import org.junit.Test;

import java.util.List;

/**
 * 反问清单（D1 §4）。
 */
public class ClarifyPlannerTest {

    private static List<ClarifyQuestion> plan(DraftEntity draft) {
        return ClarifyPlanner.plan(draft, TestFixtures.tree());
    }

    private static ClarifyQuestion questionFor(List<ClarifyQuestion> questions, String field) {
        for (ClarifyQuestion question : questions) {
            if (field.equals(question.field)) {
                return question;
            }
        }
        return null;
    }

    @Test
    public void cleanDraftAsksNothing() {
        assertTrue("信息齐全就没有要问的", plan(TestFixtures.cleanDraft()).isEmpty());
    }

    @Test
    public void missingAmountAsksForAmount() {
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.amountCents = null;
        ClarifyQuestion question = questionFor(plan(draft), "amountCents");
        assertEquals("这笔具体是多少钱？", question.question);
        assertFalse("金额是开放题，没有选项", question.isChoice());
    }

    @Test
    public void failedRuleCheckAlsoAsksForAmount() {
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.amountRuleCheck = AmountRuleCheck.FAIL;
        assertEquals("规则重算与模型不一致时必须让用户确认金额",
                "amountCents", questionFor(plan(draft), "amountCents").field);
    }

    @Test
    public void missingDirectionAsksWithCodeOptions() {
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.direction = null;
        ClarifyQuestion question = questionFor(plan(draft), "direction");
        assertTrue(question.isChoice());
        assertEquals("选项里放的是能直接写回字段的值，不是展示文案",
                List.of(Direction.EXPENSE.code(), Direction.INCOME.code()),
                question.optionsOrEmpty());
    }

    @Test
    public void missingCategoryOffersModelCandidates() {
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.categoryId = null;
        draft.categoryCandidates = List.of(
                new CategoryCandidate(TestFixtures.CAT_COFFEE, "咖啡"),
                new CategoryCandidate(TestFixtures.CAT_TAKEOUT, "外卖"));

        ClarifyQuestion question = questionFor(plan(draft), "categoryId");
        assertTrue(question.isChoice());
        assertEquals("候选以可读路径呈现，界面直接显示",
                List.of("餐饮>咖啡", "餐饮>外卖"), question.optionsOrEmpty());
    }

    @Test
    public void missingCategoryWithoutCandidatesFallsBackToTopLevel() {
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.categoryId = null;
        draft.categoryCandidates = List.of();

        ClarifyQuestion question = questionFor(plan(draft), "categoryId");
        assertEquals("至少让用户能挑一个一级分类，而不是凭空手输",
                List.of("餐饮", "其他", "购物", "人情"), question.optionsOrEmpty());
    }

    @Test
    public void missingPaymentMethodIsNotAsked() {
        // 支付方式已降级为可选（HANDOFF 决策 20）。只要它还是「缺了就反问」，
        // 草稿就一定会进 ASKING、一定要用户点一次——绕一圈又变成"每笔都要确认"
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.paymentMethod = null;
        assertTrue("缺支付方式不该产生任何反问", plan(draft).isEmpty());
    }

    @Test
    public void paymentMethodChoicesAreStillExposedForTheConfirmPage() {
        // 反问里不问，但确认页上要能顺手选一个，所以选项本身还得有
        List<String> choices = ClarifyPlanner.paymentMethodChoices();
        assertEquals(PaymentMethod.values().length, choices.size());
        assertTrue(choices.contains(PaymentMethod.WECHAT.code()));
        assertFalse("给界面的是 code，不是中文标签",
                choices.contains(PaymentMethod.WECHAT.label()));
    }

    @Test
    public void questionsAreOrderedByImportance() {
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.amountCents = null;
        draft.categoryId = null;
        draft.paymentMethod = null;

        List<String> fields = plan(draft).stream()
                .map(question -> question.field)
                .toList();
        assertEquals("金额在前、分类在后：越靠前越影响这笔账对不对",
                List.of("amountCents", "categoryId"), fields);
    }

    @Test
    public void categoriesWithCandidatesButResolvedAreNotAsked() {
        // 分类「摇摆但已采用首选」属于确认页展示的内容，不是要问的问题；
        // 只有完全没定下来才需要反问
        DraftEntity draft = TestFixtures.cleanDraft();
        draft.categoryCandidates = List.of(
                new CategoryCandidate(TestFixtures.CAT_TAKEOUT, "外卖"),
                new CategoryCandidate(TestFixtures.CAT_COFFEE, "咖啡"));
        assertTrue(plan(draft).isEmpty());
    }
}
