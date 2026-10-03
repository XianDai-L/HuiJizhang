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
import com.wisebook.llm.LlmException;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 解析管线：一句口语 → 草稿（D1 §2 / §3.6 / §6.4，D2 §7）。
 *
 * <p>参照时间固定成 2026-09-24 20:30（周四），时区固定 Asia/Shanghai，
 * 让「昨天下午」这类解析结果可复现。
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

    // ------------------------------------------------------------ 主链

    @Test
    public void happyPathProducesACompleteDraft() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\",\"amountCents\":2800,\"amountRaw\":\"28\","
                        + "\"occurredAtText\":\"今天\",\"categoryPath\":\"餐饮>外卖\","
                        + "\"paymentMethod\":\"wechat\",\"merchant\":\"楼下小馆\","
                        + "\"transactionCount\":1}");

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
        assertEquals(0, draft.clarifyRounds);
    }

    @Test
    public void firstAttemptSucceededIsReported() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\",\"merchant\":\"楼下小馆\"}");

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
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\",\"amountCents\":30500,\"amountRaw\":\"三百五\","
                        + "\"categoryPath\":\"餐饮>外卖\"}");

        DraftEntity draft = parse(client, "买菜三百五");

        assertEquals("不一致时采用规则重算值，而不是模型值",
                Long.valueOf(35000L), draft.amountCents);
        assertEquals(AmountRuleCheck.FAIL, draft.amountRuleCheck);
        assertTrue(draft.confidenceFlags.contains(ConfidenceFlag.AMOUNT_AMBIGUOUS));
    }

    @Test
    public void estimatedAmountKeepsItsRange() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\",\"amountCents\":3000,\"amountRaw\":\"三十左右\","
                        + "\"categoryPath\":\"餐饮>外卖\"}");

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
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\",\"amountCents\":5000,\"amountRaw\":\"随便给\","
                        + "\"categoryPath\":\"餐饮>外卖\"}");

        DraftEntity draft = parse(client, "随便给点钱");

        assertEquals(Long.valueOf(5000L), draft.amountCents);
        assertEquals("规则解析不了就不能假装校验通过", AmountRuleCheck.NA, draft.amountRuleCheck);
        assertTrue(draft.confidenceFlags.contains(ConfidenceFlag.AMOUNT_AMBIGUOUS));
    }

    @Test
    public void missingAmountStaysNull() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\",\"merchant\":\"楼下小馆\",\"categoryPath\":\"餐饮>外卖\"}");

        DraftEntity draft = parse(client, "楼下小馆吃了碗面");

        assertNull(draft.amountCents);
        assertEquals(AmountRuleCheck.NA, draft.amountRuleCheck);
        assertTrue(draft.confidenceFlags.contains(ConfidenceFlag.AMOUNT_AMBIGUOUS));
    }

    @Test
    public void zeroAmountIsTreatedAsMissing() {
        // 金额为 0 的支出没有意义；与其让一个 0 混进统计，不如走"缺字段 → 反问"
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\",\"amountCents\":0,\"amountRaw\":\"0\"}");

        assertNull(parse(client, "楼下小馆吃面").amountCents);
    }

    // ------------------------------------------------------------ 时间

    @Test
    public void timePhraseIsResolvedByCodeNotTrustedFromModel() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\",\"occurredAtText\":\"昨天下午\"}");

        DraftEntity draft = parse(client, "昨天下午打车");

        assertEquals(LocalDateTime.of(2026, 9, 23, 15, 0), at(draft.occurredAt));
        assertEquals(OccurredAtSource.EXPLICIT, draft.occurredAtSource);
    }

    @Test
    public void missingTimeFallsBackToNow() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\"}");

        DraftEntity draft = parse(client, "吃了个饭");

        assertEquals(Long.valueOf(NOW_MILLIS), draft.occurredAt);
        assertEquals(OccurredAtSource.FALLBACK, draft.occurredAtSource);
    }

    // ------------------------------------------------------------ 分类

    @Test
    public void merchantHardMapOutranksModelCategory() {
        // 注意 merchant 必须由模型填出来——这正是 prompt 里那段"只填具体商户、别填动作"
        // 存在的意义：商户填不对，整个映射表这一层就白设了
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\",\"amountCents\":3300,"
                        + "\"categoryPath\":\"餐饮>外卖\",\"merchant\":\"星巴克\"}");

        DraftEntity draft = parse(client, "星巴克买了个东西");

        assertEquals("商户映射表命中即定，模型给的分类不作数",
                Long.valueOf(TestFixtures.CAT_COFFEE), draft.categoryId);
        assertFalse("硬映射命中不该标摇摆，哪怕原文有模糊词",
                draft.confidenceFlags.contains(ConfidenceFlag.CATEGORY_SWING));
    }

    @Test
    public void platformMerchantWithoutItemsStaysAtTopLevel() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\",\"amountCents\":5000,"
                        + "\"categoryPath\":\"购物>日用\",\"merchant\":\"淘宝\"}");

        DraftEntity draft = parse(client, "淘宝下单");

        assertEquals("平台型商户没有商品名时只挂一级，不猜二级",
                Long.valueOf(TestFixtures.CAT_SHOPPING), draft.categoryId);
        assertFalse("只挂一级不再标不确定——挂一级在报表层面本来就是对的",
                draft.confidenceFlags.contains(ConfidenceFlag.CATEGORY_SWING));
    }

    @Test
    public void modelCategoryIsAdoptedAsIs() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\",\"amountCents\":2000,"
                        + "\"categoryPath\":\"餐饮>外卖\"}");

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
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\",\"amountCents\":2000,\"categoryPath\":\"工资\"}");

        DraftEntity draft = parse(client, "楼下小馆吃面");

        assertEquals(Long.valueOf(TestFixtures.CAT_MISC), draft.categoryId);
        assertFalse(draft.confidenceFlags.contains(ConfidenceFlag.CATEGORY_SWING));
    }

    @Test
    public void missingCategoryFallsBackToOther() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\",\"amountCents\":2000}");

        DraftEntity draft = parse(client, "楼下小馆吃面");

        assertEquals(Long.valueOf(TestFixtures.CAT_MISC), draft.categoryId);
        assertFalse(draft.confidenceFlags.contains(ConfidenceFlag.CATEGORY_SWING));
    }

    // ------------------------------------------------------------ 多笔

    @Test
    public void multipleTransactionsFlagForcesConfirmation() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\",\"amountCents\":800,\"transactionCount\":3,"
                        + "\"categoryPath\":\"餐饮>外卖\"}");

        DraftEntity draft = parse(client, "早饭8块，打车17，中午请客230");

        assertTrue("一次输入含多笔时必须强制确认（D1 §5.2 条件 8）",
                draft.confidenceFlags.contains(ConfidenceFlag.SPLIT_UNCERTAIN));
    }

    @Test
    public void absurdTransactionCountIsIgnored() {
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"direction\":\"expense\",\"amountCents\":800,\"transactionCount\":9999}");

        DraftEntity draft = parse(client, "吃了个饭");

        assertFalse(draft.confidenceFlags.contains(ConfidenceFlag.SPLIT_UNCERTAIN));
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
                .thenReturnPayload("{\"direction\":\"expense\",\"amountCents\":800}");

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
