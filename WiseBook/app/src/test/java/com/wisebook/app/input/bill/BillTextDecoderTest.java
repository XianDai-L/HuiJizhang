package com.wisebook.app.input.bill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 编码判定。
 *
 * <p>这个类存在的唯一理由是"微信与支付宝导出的 CSV 是 GBK"，
 * 所以<b>这里必须有一条真的用 GBK 字节跑的用例</b>——
 * 只测 UTF-8 等于没测，因为那条路本来就不会坏。
 */
public class BillTextDecoderTest {

    private static final Charset GBK = Charset.forName("GBK");

    private static final String SAMPLE = "交易时间,交易类型,交易对方,收/支,金额(元),备注";

    @Test
    public void utf8WithBomLosesTheBom() {
        byte[] bytes = ("\uFEFF" + SAMPLE).getBytes(StandardCharsets.UTF_8);

        BillTextDecoder.Decoded decoded = BillTextDecoder.decode(bytes);

        assertEquals(BillTextDecoder.UTF8, decoded.charset);
        assertEquals("BOM 要剥掉，否则第一列的名字会变成「\uFEFF交易时间」而认不出来",
                SAMPLE, decoded.text);
    }

    @Test
    public void utf8WithoutBomIsStillUtf8() {
        BillTextDecoder.Decoded decoded =
                BillTextDecoder.decode(SAMPLE.getBytes(StandardCharsets.UTF_8));

        assertEquals(BillTextDecoder.UTF8, decoded.charset);
        assertEquals(SAMPLE, decoded.text);
    }

    /**
     * 关键用例：GBK 字节必须被判成 GBK，而不是被 UTF-8 硬解成一堆问号。
     *
     * <p>判据是"严格解 UTF-8 会失败"——宽松解码永远不失败，
     * 所以实现里必须用 {@code CodingErrorAction.REPORT}（见 {@code BillTextDecoder}）。
     */
    @Test
    public void gbkBytesAreRecognizedAsGbk() {
        byte[] bytes = SAMPLE.getBytes(GBK);

        BillTextDecoder.Decoded decoded = BillTextDecoder.decode(bytes);

        assertEquals(BillTextDecoder.GBK, decoded.charset);
        assertEquals("按 GBK 解回来必须与原文一字不差", SAMPLE, decoded.text);
    }

    @Test
    public void plainAsciiIsUtf8() {
        BillTextDecoder.Decoded decoded =
                BillTextDecoder.decode("a,b,c".getBytes(StandardCharsets.UTF_8));

        assertEquals(BillTextDecoder.UTF8, decoded.charset);
        assertEquals("a,b,c", decoded.text);
    }

    @Test
    public void emptyInputDoesNotBlowUp() {
        BillTextDecoder.Decoded decoded = BillTextDecoder.decode(new byte[0]);

        assertTrue(decoded.text.isEmpty());
        assertEquals(BillTextDecoder.UTF8, decoded.charset);
    }
}
