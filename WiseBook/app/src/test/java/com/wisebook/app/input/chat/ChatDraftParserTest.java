package com.wisebook.app.input.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.wisebook.app.TestFixtures;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.ConfidenceFlag;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.DraftStatus;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.domain.model.OccurredAtSource;
import com.wisebook.app.domain.model.PaymentMethod;
import com.wisebook.app.input.DraftParseResult;
import com.wisebook.app.input.FakeLlmClient;
import com.wisebook.llm.LlmException;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 解析管线：一句口语 → 草稿（D1 §2 / §3.6 / §6.4，D2 §7）。
 *
 * <p>参照时间固定成 2026-09-24 20:30（周四），时区固定 Asia/Shanghai，
 * 让「昨天下午」这类解析结果可复现。
 *
 * <p><b>2026-10-08 起所有 payload 都是批量形状</b>（{@code {"drafts":[...]}}，决策 50）：
 * 文字入口也走批量工具，单笔场景就是数组里只有一个元素。下面的用例统一用
 * {@link #one} / {@link #many} 包壳，免得每处各写一遍这层结构。
 */
public class ChatDraftParserTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 24, 20, 30);
    private static final long NOW_MILLIS =
            NOW.atZone(ZONE).toInstant().toEpochMilli();
    private static final long USER_ID = 1L;
    private static final String MODEL_LABEL = "siliconflow:fake-model";

    private static ChatDraftParser parser(FakeLlmClient client) {
        return new ChatDraftParser(client, MODEL_LABEL, TestFixtures.tree(),
                CategoryScheme.STANDARD, USER_ID, ZONE);
    }

    private static DraftEntity parse(FakeLlmClient client, String input) {
        DraftParseResult result = parser(client).parse(input, NOW);
        assertTrue("应当解析成功，实际：" + result, result.isOk());
        return result.firstDraft();
    }

    private static LocalDateTime at(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZONE);
    }

    /** 单笔的批量形状：{@code {"drafts":[ {…} ]}} */
    private static String one(String draftJson) {
        return "{\"drafts\":[" + draftJson + "]}";
    }

    /** 多笔的批量形状；元素顺序就是句中出现顺序 */
    private static String many(String... draftJsons) {
        StringBuilder text = new StringBuilder("{\"drafts\":[");
        for (int i = 0; i < draftJsons.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(draftJsons[i]);
        }
        return text.append("]}").toString();
    }

    // ------------------------------------------------------------ 主链

    @Test
    public void happyPathProducesACompleteDraft() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(one(
                "{\"direction\":\"expense\",\"amountCents\":2800,\"amountRaw\":\"28\","
                        + "\"occurredAtText\":\"今天\",\"categoryPath\":\"餐饮>外卖\","
                        + "\"paymentMethod\":\"wechat\",\"merchant\":\"楼下小馆\"}"));

        DraftEntity draft = parse(client, "楼下小馆吃面 28块");

        assertEquals(DraftStatus.DRAFT, draft.status);
        assertEquals(DraftSource.TEXT_CHAT, draft.source);
        assertEquals(EvidenceType.TEXT, draft.evidenceType);
        assertEquals("楼下小馆吃面 28块", draft.rawInput);
        assertEquals(MODEL_LABEL, draft.model);
        assertEquals(USER_ID, draft.userId);

        assertEquals(Direction.EXPENSE, draft.direction);
        assertEquals(Long.valueOf(2800L), draft.amountCents);
        assertEquals("28", draft.amountRaw);
        assertEquals(AmountRuleCheck.PASS, draft.amountRuleCheck);
        assertFalse(draft.amountIsEstimated);

        assertEquals(Long.valueOf(NOW_MILLIS), draft.occurredAt);
        assertEquals(OccurredAtSource.EXPLICIT, draft.occurredAtSource);

        assertEquals(Long.valueOf(TestFixtures.CAT_TAKEOUT), draft.categoryId);
        assertEquals(Long.valueOf(TestFixtures.CAT_FOOD), draft.rootCategoryId);
        assertEquals(PaymentMethod.WECHAT, draft.paymentMethod);
        assertEquals("楼下小馆", draft.merchant);
        assertEquals("信息齐全时不该有任何存疑标签", 0, draft.confidenceFlags.size());
        assertEquals(0, draft.splitIndex);
        assertNotNull("批次标签要留给这笔草稿（即使是单笔）", draft.batchId);
        assertEquals(0, draft.clarifyRounds);
    }

    @Test
    public void firstAttemptSucceededIsReported() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(one(
                "{\"direction\":\"expense\",\"merchant\":\"楼下小馆\"}"));

        DraftParseResult result = parser(client).parse("楼下小馆吃面", NOW);

        assertTrue(result.isOk());
        assertEquals(1, result.attemptCount());
        assertTrue("首次通过率是论文指标，不能算错", result.firstAttemptSucceeded());
        assertEquals(1, result.drafts().size());
    }

    // ------------------------------------------------------------ 金额双通道

    @Test
    public void inconsistentAmountUsesRuleValueAndFlagsFailure() {
        // 模型说 305 元，规则从「三百五」重算得 350 元 —— 必然触发反问（D1 §2.2）
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(one(
                "{\"direction\":\"expense\",\"amountCents\":30500,\"amountRaw\":\"三百五\","
                        + "\"categoryPath\":\"餐饮>外卖\"}"));

        DraftEntity draft = parse(client, "买菜三百五");

        assertEquals("不一致时采用规则重算值，而不是模型值",
                Long.valueOf(35000L), draft.amountCents);
        assertEquals(AmountRuleCheck.FAIL, draft.amountRuleCheck);
        assertTrue(draft.confidenceFlags.contains(ConfidenceFlag.AMOUNT_AMBIGUOUS));
    }

    @Test
    public void estimatedAmountKeepsItsRange() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(one(
                "{\"direction\":\"expense\",\"amountCents\":3000,\"amountRaw\":\"三十左右\","
                        + "\"categoryPath\":\"餐饮>外卖\"}"));

        DraftEntity draft = parse(client, "买菜三十左右");

        assertEquals(AmountRuleCheck.PASS, draft.amountRuleCheck);
        assertTrue(draft.amountIsEstimated);
        assertEquals(Long.valueOf(2500L), draft.amountLowerCents);
        assertEquals(Long.valueOf(3500L), draft.amountUpperCents);
        assertTrue("约数金额必须带存疑标签", draft.confidenceFlags.contains(
                ConfidenceFlag.AMOUNT_AMBIGUOUS));
    }

    @Test
    public void unparseableAmountRawFallsBackToModelValue() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(one(
                "{\"direction\":\"expense\",\"amountCents\":5000,\"amountRaw\":\"随便给\","
                        + "\"categoryPath\":\"餐饮>外卖\"}"));

        DraftEntity draft = parse(client, "随便给点钱");

        assertEquals(Long.valueOf(5000L), draft.amountCents);
        assertEquals("规则解析不了就不能假装校验通过", AmountRuleCheck.NA, draft.amountRuleCheck);
        assertTrue(draft.confidenceFlags.contains(ConfidenceFlag.AMOUNT_AMBIGUOUS));
    }

    @Test
    public void missingAmountStaysNull() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(one(
                "{\"direction\":\"expense\",\"merchant\":\"楼下小馆\","
                        + "\"categoryPath\":\"餐饮>外卖\"}"));

        DraftEntity draft = parse(client, "楼下小馆吃了碗面");

        assertNull(draft.amountCents);
        assertEquals(AmountRuleCheck.NA, draft.amountRuleCheck);
        assertTrue(draft.confidenceFlags.contains(ConfidenceFlag.AMOUNT_AMBIGUOUS));
    }

    @Test
    public void zeroAmountIsTreatedAsMissing() {
        // 金额为 0 的支出没有意义；与其让一个 0 混进统计，不如走"缺字段 → 反问"
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(one(
                "{\"direction\":\"expense\",\"amountCents\":0,\"amountRaw\":\"0\"}"));

        assertNull(parse(client, "楼下小馆吃面").amountCents);
    }

    // ------------------------------------------------------------ 时间

    @Test
    public void timePhraseIsResolvedByCodeNotTrustedFromModel() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(one(
                "{\"direction\":\"expense\",\"occurredAtText\":\"昨天下午\"}"));

        DraftEntity draft = parse(client, "昨天下午打车");

        assertEquals(LocalDateTime.of(2026, 9, 23, 15, 0), at(draft.occurredAt));
        assertEquals(OccurredAtSource.EXPLICIT, draft.occurredAtSource);
    }

    @Test
    public void missingTimeFallsBackToNow() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(one(
                "{\"direction\":\"expense\"}"));

        DraftEntity draft = parse(client, "吃了个饭");

        assertEquals(Long.valueOf(NOW_MILLIS), draft.occurredAt);
        assertEquals(OccurredAtSource.FALLBACK, draft.occurredAtSource);
    }

    // ------------------------------------------------------------ 分类

    @Test
    public void merchantNameDoesNotOverrideModelCategory() {
        // 商户映射表已删除（HANDOFF 决策 46）：截图里出现的商户名不一定与这笔账有关，
        // 所以商户名不再决定分类。prompt 里"只填具体商户、别填动作"那段仍然有用，
        // 但它现在服务于可解释性（详情页要显示对方是谁），不再用来定分类。
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(one(
                "{\"direction\":\"expense\",\"amountCents\":3300,"
                        + "\"categoryPath\":\"餐饮>外卖\",\"merchant\":\"星巴克\"}"));

        DraftEntity draft = parse(client, "星巴克买了个东西");

        assertEquals("模型说是外卖就是外卖", Long.valueOf(TestFixtures.CAT_TAKEOUT), draft.categoryId);
        assertFalse("分类照旧不产生摇摆标记",
                draft.confidenceFlags.contains(ConfidenceFlag.CATEGORY_SWING));
    }

    @Test
    public void platformMerchantKeepsTheModelCategory() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(one(
                "{\"direction\":\"expense\",\"amountCents\":5000,"
                        + "\"categoryPath\":\"购物>日用\",\"merchant\":\"淘宝\"}"));

        DraftEntity draft = parse(client, "淘宝下单");

        assertEquals("平台型不再被改挂到一级，直接采用模型给的二级",
                Long.valueOf(TestFixtures.CAT_DAILY), draft.categoryId);
        assertFalse("同样不产生摇摆标记",
                draft.confidenceFlags.contains(ConfidenceFlag.CATEGORY_SWING));
    }

    @Test
    public void modelCategoryIsAdoptedAsIs() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(one(
                "{\"direction\":\"expense\",\"amountCents\":2000,"
                        + "\"categoryPath\":\"餐饮>外卖\"}"));

        DraftEntity draft = parse(client, "楼下小馆吃面");

        assertEquals(Long.valueOf(TestFixtures.CAT_TAKEOUT), draft.categoryId);
        assertEquals(Long.valueOf(TestFixtures.CAT_FOOD), draft.rootCategoryId);
        assertFalse("分类判定简化后，模型给的分类直接采用，不再产生 CATEGORY_SWING",
                draft.confidenceFlags.contains(ConfidenceFlag.CATEGORY_SWING));
    }

    @Test
    public void categoryPathConflictingWithDirectionFallsBackToOther() {
        // 一笔支出却选了「工资」（收入分类）——不能硬塞进去，
        // 但也不必反问用户：归入「其他」即可（决策 30）
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(one(
                "{\"direction\":\"expense\",\"amountCents\":2000,\"categoryPath\":\"工资\"}"));

        DraftEntity draft = parse(client, "楼下小馆吃面");

        assertEquals(Long.valueOf(TestFixtures.CAT_MISC), draft.categoryId);
        assertFalse(draft.confidenceFlags.contains(ConfidenceFlag.CATEGORY_SWING));
    }

    /**
     * 模型报<b>一级</b>分类时不该被打回重试。
     *
     * <p>prompt 让模型「转账选『人情』或其下的二级」，而「人情」是一级——
     * 2026-10-08 之前枚举只列末级，这句指令等于让模型给一个协议层禁止的取值，
     * 于是每笔转账都白重试一次（`HANDOFF` §8-31，实验里两条转账样本都中招）。
     * 现在一级也在枚举里：校验一次通过，落库就是「人情」这个一级，
     * 与转账兜底的落点（决策 32）是同一个分类。
     */
    @Test
    public void topLevelCategoryIsAcceptedForTransfer() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(one(
                "{\"direction\":\"transfer\",\"amountCents\":500,\"amountRaw\":\"5块\","
                        + "\"categoryPath\":\"人情\"}"));

        DraftEntity draft = parse(client, "转账5块");

        assertEquals(Long.valueOf(TestFixtures.CAT_SOCIAL), draft.categoryId);
        assertEquals("一级分类所属的一级就是它自己",
                Long.valueOf(TestFixtures.CAT_SOCIAL), draft.rootCategoryId);
        assertEquals("枚举允许一级之后，这类句子不该再被打回重试", 1, client.callCount());
    }

    @Test
    public void missingCategoryFallsBackToOther() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(one(
                "{\"direction\":\"expense\",\"amountCents\":2000}"));

        DraftEntity draft = parse(client, "楼下小馆吃面");

        assertEquals(Long.valueOf(TestFixtures.CAT_MISC), draft.categoryId);
        assertFalse(draft.confidenceFlags.contains(ConfidenceFlag.CATEGORY_SWING));
    }

    // ------------------------------------------------------------ 多笔（决策 50）

    /**
     * 一句话里每一笔都是一张独立草稿，共享批次标签、各自有序号。
     *
     * <p>P1 时这里断言的是「打上 SPLIT_UNCERTAIN 强制确认」（决策 14：只解析第一笔）。
     * 决策 50 之后改由批量工具直接拆笔——实验（`docs/对话多笔录入实验.md`）显示
     * 这样在单笔与多笔样本上都没有付出代价，而"只落第一笔、其余手填"是要用户掏力气的。
     */
    @Test
    public void everyElementBecomesItsOwnDraftWithSharedBatchId() {
        // 分类只能用 TestFixtures 那棵小树里有的路径——枚举是协议层的硬约束，
        // 写一个树里没有的分类会让整个数组被判非法（这一点由 schema 校验保证，别绕过它）
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(many(
                "{\"direction\":\"expense\",\"amountCents\":800,\"categoryPath\":\"餐饮>外卖\"}",
                "{\"direction\":\"expense\",\"amountCents\":1700,\"categoryPath\":\"餐饮>咖啡\"}",
                "{\"direction\":\"transfer\",\"amountCents\":23000}"));

        DraftParseResult result = parser(client).parse("早饭8块，打车17，中午请客230", NOW);

        assertTrue(result.isOk());
        assertEquals("一句话里的每一笔都是一张独立草稿", 3, result.draftCount());

        List<DraftEntity> drafts = result.drafts();
        assertEquals(Long.valueOf(800L), drafts.get(0).amountCents);
        assertEquals(Long.valueOf(1700L), drafts.get(1).amountCents);
        assertEquals(Long.valueOf(23000L), drafts.get(2).amountCents);

        assertEquals("同一次输入的几笔共享批次标签",
                drafts.get(0).batchId, drafts.get(2).batchId);
        assertTrue("标签要能看出是文字入口来的（截图入口是 IMG-）",
                drafts.get(0).batchId.startsWith("TXT-"));
        assertEquals(0, drafts.get(0).splitIndex);
        assertEquals(1, drafts.get(1).splitIndex);
        assertEquals("序号按句中出现次序", 2, drafts.get(2).splitIndex);

        assertEquals(DraftSource.TEXT_CHAT, drafts.get(0).source);
        assertEquals(EvidenceType.TEXT, drafts.get(0).evidenceType);
        assertEquals("每一笔都留着同一句原话",
                "早饭8块，打车17，中午请客230", drafts.get(2).rawInput);
    }

    @Test
    public void singleTransactionIsStillOneElementArray() {
        // 单笔是绝大多数情况，批量工具的语义是"有几笔给几个元素"——单笔就是数组里一个元素
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                one("{\"direction\":\"expense\",\"amountCents\":2800}"));

        DraftParseResult result = parser(client).parse("楼下小馆吃面 28", NOW);

        assertTrue(result.isOk());
        assertEquals("单笔不该被拆成多张", 1, result.draftCount());
        assertEquals(0, result.firstDraft().splitIndex);
    }

    @Test
    public void emptyDraftArrayDegrades() {
        // 空数组是合法的（schema 只约束元素形状），所以重试也救不回来，
        // 直接按"没读出账目"降级——不必白跑第二次调用
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload("{\"drafts\":[]}");

        DraftParseResult result = parser(client).parse("吃了个饭", NOW);

        assertEquals(DraftParseResult.Status.DEGRADED, result.status());
        assertTrue("降级说明要指出是「没读出账目」：" + result.message(),
                result.message().contains("没从这句话里读出账目"));
        assertEquals("空数组能过 schema，不该再重试一次", 1, result.attemptCount());
    }

    // ------------------------------------------------------------ 降级路径

    @Test
    public void blankInputIsRejectedWithoutCallingModel() {
        FakeLlmClient client = new FakeLlmClient();

        DraftParseResult result = parser(client).parse("   ", NOW);

        assertEquals(DraftParseResult.Status.REJECTED, result.status());
        assertTrue(result.needsManualForm());
        assertEquals("空白输入不该浪费一次调用", 0, client.callCount());
    }

    @Test
    public void unparsableOutputTwiceBecomesDegraded() {
        // 硅基流动在长上下文下偶发 JSON 截断（D2 §6.6），这是要有心理准备的真实路径
        FakeLlmClient client = new FakeLlmClient()
                .thenReturnContentOnly("这不是 JSON")
                .thenReturnContentOnly("这也不是 JSON");

        DraftParseResult result = parser(client).parse("吃了个饭", NOW);

        assertEquals(DraftParseResult.Status.DEGRADED, result.status());
        assertTrue(result.needsManualForm());
        assertTrue(result.drafts().isEmpty());
        assertEquals(2, result.attemptCount());
        assertFalse(result.firstAttemptSucceeded());
    }

    @Test
    public void retryCarriesPreviousErrorsBackToModel() {
        // 修正型重试的关键：第二次调用必须带上"上一次错在哪"
        FakeLlmClient client = new FakeLlmClient()
                .thenReturnContentOnly("不是 JSON")
                .thenReturnPayload(one("{\"direction\":\"expense\",\"amountCents\":800}"));

        DraftParseResult result = parser(client).parse("吃了个饭", NOW);

        assertTrue(result.isOk());
        assertEquals(2, result.attemptCount());
        assertFalse(result.firstAttemptSucceeded());
        assertTrue("重试时必须把上一次的错误回传给模型",
                client.userContentAt(1).contains("未通过校验"));
    }

    @Test
    public void transportErrorDegradesWithoutRetrying() {
        FakeLlmClient client = new FakeLlmClient()
                .thenFail(new LlmException(LlmException.ErrorKind.TRANSPORT, 0, "网络不通"));

        DraftParseResult result = parser(client).parse("吃了个饭", NOW);

        assertEquals(DraftParseResult.Status.DEGRADED, result.status());
        assertEquals("传输层错误不该混进结构校验的重试预算", 1, client.callCount());
        assertNotNull(result.message());
    }
}
