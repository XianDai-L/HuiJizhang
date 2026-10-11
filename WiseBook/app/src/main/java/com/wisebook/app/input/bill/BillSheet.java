package com.wisebook.app.input.bill;

import com.wisebook.app.data.transfer.EntryCsv;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * 一次导入读进来的文件：<b>字节 → 单元格矩阵</b>（纯 JVM）。
 *
 * <p>它只回答两个问题：「这是什么形态的文件」与「里面的格子长什么样」。
 * <b>不认厂商、不解释字段</b>——那是 {@link BillFormat} 与各适配器的事。
 * 这样切分的好处是每一种文件形态都能单独测：xlsx 走 zip，csv 走编码判定 + 文本切格。
 *
 * <p>为什么不统一成"先解成文本"：xlsx 是二进制，没有"文本"这一步；
 * 反过来 csv 也没有"单元格"以外的东西。矩阵是两者唯一的共同语言，
 * 后续（认表头、按列取名）都只跟矩阵打交道。
 */
public final class BillSheet {

    /**
     * 单次导入的文件大小上限。
     *
     * <p>取 8 MB 与图片入口同一口径（{@code ImageInputLoader} 也是 8 MB）：
     * 一份三五年的微信账单也就几百 KB，超限的几乎一定是选错了文件
     * ——而选错文件时"早拒绝"比"解到一半内存爆掉"好得多。
     */
    public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;

    private final boolean xlsx;
    private final String charset;
    private final String text;
    private final List<List<String>> cells;

    private BillSheet(boolean xlsx, String charset, String text, List<List<String>> cells) {
        this.xlsx = xlsx;
        this.charset = charset;
        this.text = text;
        this.cells = Collections.unmodifiableList(cells);
    }

    /**
     * 读一个文件。
     *
     * @throws IllegalArgumentException 文件为空、过大，或 xlsx 结构读不了。
     *          <b>消息是给用户看的</b>，调用方直接显示即可
     */
    public static BillSheet read(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("这个文件是空的");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件超过 8 MB，请先按时间段拆分再导入");
        }
        return isZip(bytes) ? readXlsx(bytes) : readText(bytes);
    }

    /** 是不是 xlsx。xlsx 是 zip，zip 的魔数固定是 {@code PK\x03\x04} */
    public static boolean isZip(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && bytes[0] == 'P' && bytes[1] == 'K'
                && bytes[2] == 0x03 && bytes[3] == 0x04;
    }

    private static BillSheet readXlsx(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            List<List<String>> cells = XlsxSheetReader.read(in);
            return new BillSheet(true, null, null, cells);
        } catch (IOException e) {
            throw new IllegalArgumentException("这个 Excel 读不了：" + messageOf(e));
        }
    }

    private static BillSheet readText(byte[] bytes) {
        BillTextDecoder.Decoded decoded = BillTextDecoder.decode(bytes);
        // 单元格切分复用账本 CSV 那一套：它是逐字符扫描的，引号里的逗号与换行都照顾到了。
        // 再写一份"按逗号 split"的版本，迟早在同一类文件上出现两种结果
        List<List<String>> cells = EntryCsv.parseCells(decoded.text);
        return new BillSheet(false, decoded.charset, decoded.text, cells);
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    // ------------------------------------------------------------------ 读取

    /** 是不是 xlsx（zip）形态 */
    public boolean isXlsx() {
        return xlsx;
    }

    /** 解出来用的编码；xlsx 时为 {@code null}。用来在导入结果里报"按什么读的" */
    public String charsetName() {
        return charset;
    }

    /**
     * 解码后的原文；<b>xlsx 时为 {@code null}</b>。
     *
     * <p>只有走"慧记自有 CSV"那条路时才需要它——那是按文本解析的；
     * 账单走矩阵，不必再拼回文本（拼回去反而会丢转义信息）。
     */
    public String text() {
        return text;
    }

    public List<List<String>> cells() {
        return cells;
    }
}
