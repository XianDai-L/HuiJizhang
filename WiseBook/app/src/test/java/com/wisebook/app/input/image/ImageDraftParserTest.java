package com.wisebook.app.input.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.wisebook.app.TestFixtures;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.model.CategoryScheme;
import com.wisebook.app.domain.model.DraftSource;
import com.wisebook.app.domain.model.EvidenceType;
import com.wisebook.app.input.DraftParseResult;
import com.wisebook.app.input.FakeLlmClient;
import com.wisebook.llm.LlmException;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 截图入口的解析链路（P2-3）。
 *
 * <p>要守住的是三件事：
 * <ol>
 *   <li><b>一图多笔</b>：一张图读出的每一笔都成为一张独立草稿，且共享批次标签、各自有序号</li>
 *   <li><b>来源与证据</b>：草稿必须记着自己来自截图，但不留原图（决策 37）</li>
 *   <li><b>两类失败都降级</b>：转写失败、拆笔失败都是 D1 说的正常路径，不是异常</li>
 * </ol>
 */
public class ImageDraftParserTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 10, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static ImageDraftParser parser(FakeImageReader reader, FakeLlmClient client) {
        return new ImageDraftParser(reader, client, "siliconflow:deepseek-ai/DeepSeek-OCR",
                TestFixtures.tree(), CategoryScheme.STANDARD, 1L, ZONE);
    }

    private static ImageInput anyImage() {
        return new ImageInput(new byte[]{1, 2, 3}, "image/jpeg", "相册截图");
    }

    @Test
    public void oneScreenshotWithTwoTransactionsProducesTwoDrafts() {
        FakeImageReader reader = FakeImageReader.returning(
                "微信支付\n收款方甲\n￥7.00\n收款方乙\n￥15.00");
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"drafts\":["
                        + "{\"direction\":\"expense\",\"amountCents\":700,"
                        + "\"amountRaw\":\"￥7.00\",\"merchant\":\"收款方甲\"},"
                        + "{\"direction\":\"expense\",\"amountCents\":1500,"
                        + "\"amountRaw\":\"￥15.00\",\"merchant\":\"收款方乙\"}]}");

        DraftParseResult result = parser(reader, client).parse(anyImage(), NOW);

        assertTrue(result.isOk());
        assertEquals("一笔一元素，两张草稿", 2, result.draftCount());

        DraftEntity first = result.drafts().get(0);
        DraftEntity second = result.drafts().get(1);
        assertEquals(Long.valueOf(700L), first.amountCents);
        assertEquals(Long.valueOf(1500L), second.amountCents);

        assertEquals(DraftSource.IMAGE, first.source);
        assertEquals(EvidenceType.IMAGE, first.evidenceType);
        assertNull("原图不留（HANDOFF 决策 37）", first.evidenceRef);
        assertEquals("转写文本要留下来当原话，用户才知道模型当时看到了什么",
                reader.transcript(), first.rawInput);

        assertEquals("同一张图的几笔共享批次标签", first.batchId, second.batchId);
        assertTrue("批次标签要能看出是截图来的", first.batchId.startsWith("IMG-"));
        assertEquals(0, first.splitIndex);
        assertEquals("顺序与图里从上到下一致", 1, second.splitIndex);
    }

    /** 归一是确定性处理，必须真的作用在发给模型的文本上 */
    @Test
    public void markdownTranscriptIsFlattenedBeforeTheModelSeesIt() {
        FakeImageReader reader = FakeImageReader.returning(
                "# 记账本\n#### 转账\n**-100.00**");
        FakeLlmClient client = new FakeLlmClient().thenReturnPayload(
                "{\"drafts\":[{\"direction\":\"transfer\",\"amountCents\":10000}]}");

        parser(reader, client).parse(anyImage(), NOW);

        String sentToModel = client.userContentAt(0);
        assertFalse("层级标题会让下游把整页读成一份文档", sentToModel.contains("#"));
        assertFalse(sentToModel.contains("**"));
        assertTrue("金额必须原样保留", sentToModel.contains("-100.00"));
    }

    @Test
    public void transcribeFailureDegradesInsteadOfThrowing() {
        FakeImageReader reader = FakeImageReader.failing(
                new LlmException(LlmException.ErrorKind.AUTH, 401, "Key 无效"));
        FakeLlmClient client = new FakeLlmClient();

        DraftParseResult result = parser(reader, client).parse(anyImage(), NOW);

        assertEquals(DraftParseResult.Status.DEGRADED, result.status());
        assertTrue(result.message().contains("读取图片失败"));
        assertEquals("转写就失败了，不该再去调模型", 0, client.callCount());
    }

    @Test
    public void emptyTranscriptDegrades() {
        FakeImageReader reader = FakeImageReader.returning("   \n  ");
        FakeLlmClient client = new FakeLlmClient();

        DraftParseResult result = parser(reader, client).parse(anyImage(), NOW);

        assertEquals(DraftParseResult.Status.DEGRADED, result.status());
        assertEquals(0, client.callCount());
    }

    /** 拆笔失败会用尽重试预算再降级——与文字入口共用同一套容错 */
    @Test
    public void invalidBatchPayloadDegradesAfterRetry() {
        FakeImageReader reader = FakeImageReader.returning("￥7.00");
        FakeLlmClient client = new FakeLlmClient()
                .thenReturnPayload("{\"drafts\":{}}")
                .thenReturnPayload("{\"drafts\":{}}");

        DraftParseResult result = parser(reader, client).parse(anyImage(), NOW);

        assertEquals(DraftParseResult.Status.DEGRADED, result.status());
        assertEquals("一次原始 + 一次修正型重试", 2, client.callCount());
    }
}
