package com.wisebook.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.app.domain.model.CategoryCandidate;
import com.wisebook.app.domain.model.ClarifyQuestion;
import com.wisebook.app.domain.model.ConfidenceFlag;
import com.wisebook.app.domain.model.Direction;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 「AI 处理详情」面板的排版与取值。
 *
 * <p>这块内容的价值全在"看得见"：分类判错时，它是唯一能区分
 * 「模型选错了」「候选解析错了」「判据误伤」的地方。
 * 所以每一段都钉一条用例，免得某次重构把某一行悄悄弄丢——
 * 丢一行不会有人报错，只会让人以后再也看不到那个线索。
 */
public class DiagnosticsFormatterTest {

    private static DraftEntity draft() {
        DraftEntity draft = new DraftEntity();
        draft.model = "deepseek:deepseek-chat";
        draft.direction = Direction.EXPENSE;
        draft.amountCents = 28_340L;
        draft.amountRaw = "283.4";
        draft.amountRuleCheck = AmountRuleCheck.PASS;
        draft.categoryId = 30L;
        draft.categoryCandidates = candidates();
        return draft;
    }

    @Test
    public void showsModelAmountAndCategory() {
        String text = DiagnosticsFormatter.describe(1, true, draft(),
                "{\"amountCents\":28340}", null);

        assertTrue("模型名是溯源的第一环", text.contains("deepseek:deepseek-chat"));
        assertTrue(text.contains("283.4"));
        assertTrue(text.contains("通过"));
        assertTrue("要显示采用了哪个分类", text.contains("娱乐>游戏"));
    }

    @Test
    public void showsTheRawPayloadVerbatim() {
        String text = DiagnosticsFormatter.describe(1, true, draft(),
                "{\"categoryPath\":\"娱乐>游戏\"}", null);

        // 原始返回必须原样可见：加工过的视图会把线索按作者的理解重排
        assertTrue(text.contains("模型原始返回"));
        assertTrue(text.contains("categoryPath"));
    }

    @Test
    public void showsHowTheCategoryWasDecided() {
        // 用户想知道的是"它凭什么这么分"，所以要写出依据是映射表、规则、模型还是兜底
        String text = DiagnosticsFormatter.describe(1, true, draft(), null,
                "平台型商户「淘宝」没有商品名，只挂一级「购物」");

        assertTrue(text.contains("依据"));
        assertTrue(text.contains("只挂一级"));
    }

    @Test
    public void reportsRetryAndMissingAmount() {
        DraftEntity draft = draft();
        draft.amountCents = null;
        draft.amountRaw = null;
        draft.amountRuleCheck = AmountRuleCheck.NA;
        draft.categoryId = null;
        draft.confidenceFlags = new ArrayList<>(List.of(ConfidenceFlag.AMOUNT_AMBIGUOUS));
        draft.clarifyQuestions = new ArrayList<>(List.of(
                new ClarifyQuestion("amountCents", "这笔具体是多少钱？", null)));

        String text = DiagnosticsFormatter.describe(2, false, draft, null, null);

        assertTrue(text.contains("含重试"));
        assertTrue(text.contains("金额") && text.contains("未定"));
        assertTrue(text.contains("分类") && text.contains("未定"));
        assertTrue(text.contains("金额存疑"));
        assertTrue("反问清单要能看见，否则不知道系统打算问什么", text.contains("这笔具体是多少钱"));
        assertFalse("没有原始返回时不该凭空造一段", text.contains("模型原始返回"));
    }

    private static List<CategoryCandidate> candidates() {
        List<CategoryCandidate> candidates = new ArrayList<>();
        candidates.add(new CategoryCandidate(30L, "娱乐>游戏"));
        return candidates;
    }
}
