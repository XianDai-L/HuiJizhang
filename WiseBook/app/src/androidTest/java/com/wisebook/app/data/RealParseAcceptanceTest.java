package com.wisebook.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wisebook.app.WiseBookApp;
import com.wisebook.app.data.local.WiseBookDatabase;
import com.wisebook.app.data.local.entity.DraftEntity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 真实模型链路的验收测试（P1-6）。
 *
 * <p><b>这一条会真的调用模型、真的花钱。</b>所以它只在
 * {@code local.properties} 配了 Key 时才执行，没配就直接跳过——
 * 既能当端到端验收的证据，又不会在没配 Key 的环境里把构建搞红。
 *
 * <p><b>为什么不把模型的输出断言死：</b>模型本来就不是确定性的，断言"分类必须是娱乐>游戏"
 * 只能得到一条时红时绿的测试。这里只断言<b>结构性结论</b>——真正该被保证的东西，
 * 比如「283.4 必须由规则通道算成 28340 分」，那是确定性代码的职责，与模型无关。
 * 其余（分类候选、存疑标签、反问清单、为什么不免确认）全部打印到日志供人阅读。
 *
 * <p>读日志：{@code adb logcat -d -s System.out}。
 * 这也顺带演示了 D1 §2 双通道校验的意义：<b>金额不靠模型，靠规则重算</b>。
 */
@RunWith(AndroidJUnit4.class)
public class RealParseAcceptanceTest {

    private WiseBookApp app;

    @Before
    public void requireApiKey() {
        Context context = ApplicationProvider.getApplicationContext();
        app = WiseBookApp.from(context);
        assumeTrue("local.properties 里没配 Key，跳过真实调用测试", app.llmRuntime().isReady());
    }

    @Test
    public void decimalAmountWithoutUnitIsRebuiltByTheRuleChannel() {
        DraftRepository.SubmitReport report = deal("我买游戏花了283.4");

        assertTrue("真实调用不该失败（-1 表示草稿压根没落库）", report.outcome.draftId > 0);
        assertEquals("283.4 元 必须能被规则通道算成 28340 分：这条不依赖模型",
                28_340L, (long) draftOf(report).amountCents);
    }

    @Test
    public void futureIntentSentence() {
        DraftRepository.SubmitReport report = deal("明天要花500买游戏");
        assertTrue(report.outcome.draftId > 0);
    }

    /** 跑一次真实解析，把能看出问题的一切打进日志，然后把结果还回去 */
    private DraftRepository.SubmitReport deal(String input) {
        DraftRepository.SubmitReport report = app.draftRepository().submit(input);
        DraftEntity draft = draftOf(report);

        System.out.println("================ 真实解析输入：" + input);
        System.out.println("  结论  : " + report.summary);
        System.out.println("  说明  : " + report.detail);
        System.out.println("  调用  : " + report.attemptCount + " 次，首次就通过 = "
                + report.firstAttemptSucceeded);
        if (draft == null) {
            System.out.println("  （草稿没落库，没有更多可看的字段）");
            return report;
        }
        System.out.println("  金额  : " + draft.amountCents + " 分"
                + "，原文片段 = " + draft.amountRaw
                + "，规则校验 = " + draft.amountRuleCheck
                + "，约数 = " + draft.amountIsEstimated
                + "，区间 = [" + draft.amountLowerCents + ", " + draft.amountUpperCents + "]");
        System.out.println("  方向  : " + draft.direction);
        System.out.println("  分类  : categoryId = " + draft.categoryId
                + "，rootCategoryId = " + draft.rootCategoryId);
        System.out.println("  候选  : " + (draft.categoryCandidates == null
                ? "无" : draft.categoryCandidates.size() + " 个 " + draft.categoryCandidates));
        System.out.println("  商户  : " + draft.merchant + "，支付方式 = " + draft.paymentMethod);
        System.out.println("  存疑  : " + draft.confidenceFlags);
        System.out.println("  反问  : " + draft.clarifyQuestions);
        System.out.println("  原话  : " + draft.rawInput);
        System.out.println("  状态  : " + draft.status);
        System.out.println("  为什么需要确认: " + app.draftRepository().explainWhyNeedsConfirm(
                draft, app.settingsRepository().loadOrInit(WiseBookDatabase.DEFAULT_USER_ID)));
        System.out.println("  模型  : " + draft.model);
        return report;
    }

    private DraftEntity draftOf(DraftRepository.SubmitReport report) {
        return report.outcome.draftId <= 0L
                ? null
                : app.draftRepository().findDraft(report.outcome.draftId);
    }
}
