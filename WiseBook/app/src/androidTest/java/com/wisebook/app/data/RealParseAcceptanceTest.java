package com.wisebook.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
 *
 * <p>2026-10-08：文字入口改成批量之后（决策 50），{@code submit} 返回的是一批结果
 * （{@code BatchSubmitReport}）。这些用例的断言只落在"第一笔"上，形状没变。
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
        DraftRepository.BatchSubmitReport report = deal("我买游戏花了283.4");
        DraftEntity draft = draftOf(report);

        assertNotNull("真实调用不该失败（草稿没落库就没有可断言的对象）", draft);
        assertEquals("283.4 元 必须能被规则通道算成 28340 分：这条不依赖模型",
                28_340L, (long) draft.amountCents);
    }

    @Test
    public void futureIntentSentence() {
        DraftRepository.BatchSubmitReport report = deal("明天要花500买游戏");

        assertNotNull("真实调用不该失败", draftOf(report));
    }

    /**
     * 多笔：一句话里的每一笔都要变成一张独立草稿（决策 50 的真实链路验收）。
     *
     * <p>只断言"拆出了多笔"与"每一笔都有金额"这类结构性结论，不断言具体金额——
     * 模型怎么切分这两笔（8 与 17）不是确定性代码的职责。
     */
    @Test
    public void multiTransactionSentenceProducesOneDraftPerTransaction() {
        DraftRepository.BatchSubmitReport report = deal("早饭8块，打车17");

        assertTrue("这句话本该拆出多笔，实际拆了 " + report.draftCount() + " 笔",
                report.draftCount() >= 2);
        for (DraftRepository.SubmitReport one : report.reports) {
            assertTrue("每一笔都该有自己的草稿 id", one.outcome.draftId > 0L);
        }
    }

    /** 跑一次真实解析，把能看出问题的一切打进日志，然后把结果还回去 */
    private DraftRepository.BatchSubmitReport deal(String input) {
        DraftRepository.BatchSubmitReport report = app.draftRepository().submit(input);
        DraftEntity draft = draftOf(report);

        System.out.println("================ 真实解析输入：" + input);
        System.out.println("  结论  : " + report.summary());
        System.out.println("  说明  : " + report.detail());
        System.out.println("  笔数  : " + report.draftCount()
                + "（已自动记账 " + report.autoPostedCount()
                + "，待核对 " + report.needsConfirmCount() + "）");
        System.out.println("  调用  : " + attemptsOf(report) + " 次，首次就通过 = "
                + firstAttemptOf(report));
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
        System.out.println("  批次  : " + draft.batchId + "，序号 = " + draft.splitIndex);
        System.out.println("  状态  : " + draft.status);
        System.out.println("  为什么需要确认: " + app.draftRepository().explainWhyNeedsConfirm(
                draft, app.settingsRepository().loadOrInit(WiseBookDatabase.DEFAULT_USER_ID)));
        System.out.println("  模型  : " + draft.model);
        return report;
    }

    /** 第一笔的草稿实体；没有落库时返回 {@code null} */
    private DraftEntity draftOf(DraftRepository.BatchSubmitReport report) {
        if (report.reports.isEmpty()) {
            return null;
        }
        long draftId = report.reports.get(0).outcome.draftId;
        return draftId <= 0L ? null : app.draftRepository().findDraft(draftId);
    }

    /**
     * 整批只调一次模型，所以第一条的调用次数就是这一批的次数。
     *
     * <p>不看各笔相加：那会把同一次解析数好多遍（每一笔的 report 都带着同一个 attemptCount）。
     */
    private static int attemptsOf(DraftRepository.BatchSubmitReport report) {
        return report.reports.isEmpty() ? 0 : report.reports.get(0).attemptCount;
    }

    private static boolean firstAttemptOf(DraftRepository.BatchSubmitReport report) {
        return !report.reports.isEmpty() && report.reports.get(0).firstAttemptSucceeded;
    }
}
