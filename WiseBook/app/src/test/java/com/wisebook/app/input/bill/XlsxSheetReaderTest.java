package com.wisebook.app.input.bill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * xlsx 读取。
 *
 * <p><b>夹具是现搭的，不是用户那份真实账单</b>：那份里面有真实的收款方与金额，
 * 而本仓库是公开的，把支付记录塞进测试资源里既不合适也没必要。
 * 现搭的好处是能把边界一个个摆出来：空行、缺列、内联字符串、共享字符串、
 * 以及账单最要紧的<b>日期序列号</b>。
 *
 * <p>搭出来的包就是真 xlsx 的结构：内容类型表 + 关系表 + 工作簿 + 共享字符串表 + 工作表，
 * 所以它验的是"能不能读一个真的 xlsx"，而不是"能不能读我们自己造的格式"。
 */
public class XlsxSheetReaderTest {

    /** 真实微信账单里 A19 的原始值，对应 2026-10-08 08:20:39（见 BillDateTimesTest） */
    private static final String SERIAL = "46303.34767361111";

    private static final String CONTENT_TYPES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>"
                    + "</Types>";

    private static final String ROOT_RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>";

    private static final String WORKBOOK =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                    + "</workbook>";

    /** 让第一张工作表不叫 sheet1.xml，验的是"按关系表找表"这条路真的通了 */
    private static final String WORKBOOK_RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/data.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
                    + "</Relationships>";

    private static final String SHARED_STRINGS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"3\" uniqueCount=\"3\">"
                    + "<si><t>交易时间</t></si>"
                    + "<si><t>便利超人</t></si>"
                    + "<si><r><t>康师傅</t></r><r><t>茉莉蜜茶</t></r></si>"
                    + "</sst>";

    /**
     * 五条刻意摆出来的边界：
     * <ul>
     *   <li>第 1 行只有共享字符串（{@code t="s"}）</li>
     *   <li>第 2 行整行不存在——Excel 不会为全空的行写 {@code <row>}，
     *       矩阵里必须留一个空位，否则后面每一行都会上移</li>
     *   <li>第 3 行缺 C 列——同理，后面各列不能整体左移</li>
     *   <li>富文本的共享字符串（两个 {@code <t>}）要拼起来</li>
     *   <li>时间是<b>数值</b>（日期序列号），金额也是数值</li>
     * </ul>
     */
    private static final String SHEET =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<sheetData>"
                    + "<row r=\"1\">"
                    + "<c r=\"A1\" t=\"s\"><v>0</v></c>"
                    + "<c r=\"B1\" t=\"s\"><v>28</v></c>"
                    + "</row>"
                    + "<row r=\"3\">"
                    + "<c r=\"A3\"><v>" + SERIAL + "</v></c>"
                    + "<c r=\"B3\" t=\"s\"><v>1</v></c>"
                    + "<c r=\"D3\" t=\"inlineStr\"><is><t>支出</t></is></c>"
                    + "<c r=\"E3\"><v>3.5</v></c>"
                    + "<c r=\"F3\" t=\"s\"><v>2</v></c>"
                    + "</row>"
                    + "</sheetData></worksheet>";

    private static byte[] xlsx() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(out);
        put(zip, "[Content_Types].xml", CONTENT_TYPES);
        put(zip, "_rels/.rels", ROOT_RELS);
        put(zip, "xl/workbook.xml", WORKBOOK);
        put(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
        put(zip, "xl/sharedStrings.xml", SHARED_STRINGS);
        put(zip, "xl/worksheets/data.xml", SHEET);
        zip.close();
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static List<List<String>> read() throws IOException {
        return XlsxSheetReader.read(new ByteArrayInputStream(xlsx()));
    }

    // ------------------------------------------------------------------ 用例

    @Test
    public void sharedStringsAreResolvedByIndexNotByPosition() throws IOException {
        List<List<String>> cells = read();

        assertEquals("表头走的是共享字符串表，A1 引用序号 0", "交易时间", cells.get(0).get(0));
        assertEquals("B1 引用的是序号 28，越界要落成空串而不是抛异常", "", cells.get(0).get(1));
    }

    @Test
    public void rowsSkippedByExcelKeepTheirPlace() throws IOException {
        List<List<String>> cells = read();

        assertEquals("Excel 不写全空的行，矩阵里必须为它留位", 3, cells.size());
        assertTrue(cells.get(1).isEmpty());
    }

    @Test
    public void columnsSkippedByExcelKeepTheirPlace() throws IOException {
        List<String> row = read().get(2);

        assertEquals("C 列不存在，所以 D 列的内容必须还在第 4 格（下标 3）",
                "支出", row.get(3));
        assertEquals("补出来的空列", "", row.get(2));
    }

    @Test
    public void richTextSharedStringIsConcatenated() throws IOException {
        // 共享字符串里被拆成两个 <t> 的那种（前半段加粗），要拼成一整条
        assertEquals("康师傅茉莉蜜茶", read().get(2).get(5));
    }

    @Test
    public void numbersStayExactlyAsExcelWroteThem() throws IOException {
        // 读文件这一层不做任何"解释"：日期序列号与金额都原样拿出去，
        // 判断"这列是不是日期"是适配器的事
        List<String> row = read().get(2);

        assertEquals(SERIAL, row.get(0));
        assertEquals("3.5", row.get(4));
    }

    @Test
    public void columnIndexUnderstandsLetters() {
        assertEquals(0, XlsxSheetReader.columnIndex("A19"));
        assertEquals(1, XlsxSheetReader.columnIndex("B1"));
        assertEquals(25, XlsxSheetReader.columnIndex("Z9"));
        assertEquals(26, XlsxSheetReader.columnIndex("AA1"));
        assertEquals(27, XlsxSheetReader.columnIndex("AB100"));
        assertEquals(-1, XlsxSheetReader.columnIndex("19"));
        assertEquals(-1, XlsxSheetReader.columnIndex(null));
    }
}
