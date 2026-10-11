package com.wisebook.app.input.bill;

import com.wisebook.app.data.transfer.EntryCsv;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 账单文本文件的<b>编码判定</b>（纯 JVM，可用单测钉住）。
 *
 * <p>为什么这个小东西必须单独存在：<b>微信与支付宝导出的 CSV 都是 GBK</b>，
 * 而项目里原先读 CSV 的代码（{@code SettingsActivity.readText}）是无条件按 UTF-8 解的。
 * 拿一份 GBK 的微信账单进来，中文表头会变成一串乱码，于是格式识别认不出来，
 * 用户看到的是「这不是慧记导出的 CSV」——<b>报错信息指向错误的方向，最难查</b>。
 * 这一条不先解决，后面所有解析都无从谈起。
 *
 * <p>判定顺序（先是便宜的、再是兜底的）：
 * <ol>
 *   <li><b>UTF-8 BOM</b>（{@code EF BB BF}）→ 直接按 UTF-8 解，并丢掉 BOM。
 *       慧记自己导出的 CSV 就带 BOM（那是为了让 Excel 不乱码），所以这条一定会走到</li>
 *   <li>没有 BOM → 用 {@link CodingErrorAction#REPORT} 严格试解 UTF-8。
 *       合法就当 UTF-8——ASCII 与中文 UTF-8 都会在这里通过</li>
 *   <li>严格解失败 → 说明它<b>确实不是</b> UTF-8，按 GBK 解。
 *       GBK 的码位几乎覆盖全字节空间，解码不会失败，所以它是安全的兜底</li>
 * </ol>
 *
 * <p>已知的理论盲区：一份 GBK 文件恰好整体构成合法 UTF-8 序列时会被误判成 UTF-8。
 * 含中文的 GBK 文本几乎不可能满足这个条件，而代价（若真发生）是乱码表头 → 认不出格式，
 * 与"没做这个类"时表现一样，不会更糟，所以不值得为它引入第三方嗅探库。
 */
public final class BillTextDecoder {

    /** 由本类解出来的文本恒是 UTF-8 或 GBK 之一，这两个名字只用于给用户报"按什么读的" */
    public static final String UTF8 = "UTF-8";
    public static final String GBK = "GBK";

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final Charset GBK_CHARSET = Charset.forName(GBK);

    private BillTextDecoder() {
    }

    /** 解码结果：文本 + 用了哪种编码（后者会写进导入结果，方便排查乱码） */
    public static final class Decoded {

        public final String text;
        public final String charset;

        Decoded(String text, String charset) {
            this.text = text;
            this.charset = charset;
        }
    }

    public static Decoded decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new Decoded("", UTF8);
        }
        if (startsWithUtf8Bom(bytes)) {
            String text = new String(bytes, UTF8_BOM.length,
                    bytes.length - UTF8_BOM.length, StandardCharsets.UTF_8);
            return new Decoded(stripBomChar(text), UTF8);
        }
        try {
            return new Decoded(strict(bytes, StandardCharsets.UTF_8), UTF8);
        } catch (CharacterCodingException notUtf8) {
            return new Decoded(new String(bytes, GBK_CHARSET), GBK);
        }
    }

    private static boolean startsWithUtf8Bom(byte[] bytes) {
        return bytes.length >= UTF8_BOM.length
                && bytes[0] == UTF8_BOM[0]
                && bytes[1] == UTF8_BOM[1]
                && bytes[2] == UTF8_BOM[2];
    }

    /**
     * 严格解码：遇到非法字节序列就抛异常，而不是塞一个 {@code U+FFFD} 占位符。
     *
     * <p>这正是"试 UTF-8"能成立的关键——宽松解码永远不失败，
     * 于是 GBK 文件也会"成功"解出一堆乱码，判定就失去意义了。
     */
    private static String strict(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** 字节层的 BOM 已剥掉；这里防的是"文本自己还带一个 U+FEFF"（换行/拼接后可能出现） */
    private static String stripBomChar(String text) {
        return text.startsWith(EntryCsv.BOM) ? text.substring(EntryCsv.BOM.length()) : text;
    }
}
