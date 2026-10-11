package com.wisebook.app.input.bill;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * <b>自己解 xlsx</b>（纯 JVM，可用单测钉住）：把一个 .xlsx 读出单元格矩阵。
 *
 * <p>为什么不用现成的库：POI 在 Android 上体积多出几 MB、方法数也压不住，
 * 而新版 POI 还用了 Java 9+ 的 API——那正是 {@code HANDOFF} §8-21 记着的坑
 * （minSdk 26 上会 {@code NoSuchMethodError}）。相比之下，账单文件只需要 xlsx 里
 * <b>极小的一块子集</b>，自己解不到三百行，且这段能在 JVM 上单测，不必起模拟器。
 *
 * <p>xlsx 本质是一个 zip，我们只关心三个部件：
 * <ul>
 *   <li>{@code xl/sharedStrings.xml} —— 共享字符串表。单元格 {@code t="s"} 时，
 *       里面的数字是<b>这张表的序号</b>，不是值本身</li>
 *   <li>{@code xl/workbook.xml} + {@code xl/_rels/workbook.xml.rels} —— 用来找出第一张工作表是哪个文件。
 *       多花这几十行是为了别读错 sheet：只认 {@code sheet1.xml} 的写法在"工作表被改名或重排"时会静默读错，
 *       而账单文件恰恰是用户从支付平台导出的，名字不由我们定</li>
 *   <li>{@code xl/worksheets/*.xml} —— 真正的行列数据</li>
 * </ul>
 *
 * <p><b>刻意不做的两件事</b>（账单文件里不存在，做了就是为想象中的需求写代码）：
 * 公式求值（只读缓存值 {@code <v>}）、合并单元格（合并区只有左上角有值，
 * 而账单每一格都有值）。
 *
 * <p>返回的是<b>纯文本矩阵</b>：数字保持 xlsx 里的原始写法（{@code 3.5}），
 * 日期也保持原始的数字序列号——判断"这一列是不是日期"是格式适配器的职责，
 * 不是读文件这一层的。分层清楚才好单测。
 */
public final class XlsxSheetReader {

    /** 单个部件解压后的上限。防的是畸形文件（zip 炸弹）把内存吃光 */
    private static final int MAX_ENTRY_BYTES = 16 * 1024 * 1024;

    /** 最多缓冲几张工作表。账单文件只有一张，留几张余量只是不想在畸形文件上失控 */
    private static final int MAX_WORKSHEETS = 4;

    private static final String SHARED_STRINGS = "xl/sharedStrings.xml";
    private static final String WORKBOOK = "xl/workbook.xml";
    private static final String WORKBOOK_RELS = "xl/_rels/workbook.xml.rels";
    private static final String WORKSHEET_DIR = "xl/worksheets/";

    /** 关系命名空间。{@code r:id} 这个属性的前缀，用局部名匹配时容错 */
    private static final String RELATIONSHIP_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private XlsxSheetReader() {
    }

    /**
     * @return 单元格矩阵，每行一个 {@code List<String>}；越界的行/列会被补齐成空串，
     *         所以<b>行列下标与 Excel 里看到的一致</b>，适配器可以直接按列号取
     */
    public static List<List<String>> read(InputStream in) throws IOException {
        Map<String, byte[]> parts = readParts(in);
        List<String> shared = parseSharedStrings(parts.get(SHARED_STRINGS));
        byte[] sheet = pickWorksheet(parts);
        if (sheet == null) {
            throw new IOException("这个 Excel 里没有找到工作表");
        }
        return parseWorksheet(sheet, shared);
    }

    // ------------------------------------------------------------------ zip

    /**
     * 一次顺序扫描把需要的部件读进内存。
     *
     * <p>为什么要全部缓冲再处理：{@code ZipInputStream} 只能顺着读，
     * 而"该读哪张工作表"要先看过 {@code workbook.xml.rels} 才知道——
     * 那个部件的顺序并不保证在工作表之前。攒着最后再拼，比来回开流简单得多，
     * 账单文件的量级（百来行/几十 KB）也完全吃得消。
     */
    private static Map<String, byte[]> readParts(InputStream in) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        int worksheets = 0;
        ZipInputStream zip = new ZipInputStream(in);
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                boolean isWorksheet = name.startsWith(WORKSHEET_DIR);
                if (isWorksheet) {
                    if (worksheets >= MAX_WORKSHEETS) {
                        continue;
                    }
                    worksheets++;
                } else if (!SHARED_STRINGS.equals(name)
                        && !WORKBOOK.equals(name)
                        && !WORKBOOK_RELS.equals(name)) {
                    continue;
                }
                parts.put(name, readFully(zip));
            }
        } finally {
            zip.close();
        }
        return parts;
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > MAX_ENTRY_BYTES) {
                throw new IOException("这个 Excel 的部件异常大，已停止读取");
            }
        }
        return buffer.toByteArray();
    }

    // -------------------------------------------------------------- 选工作表

    /** 先按 {@code workbook.xml} 的声明找第一张表；读不到就退到"名字排序后的第一张" */
    private static byte[] pickWorksheet(Map<String, byte[]> parts) throws IOException {
        String declared = firstSheetPartName(parts.get(WORKBOOK), parts.get(WORKBOOK_RELS));
        if (declared != null) {
            byte[] found = parts.get(declared);
            if (found != null && found.length > 0) {
                return found;
            }
        }
        List<String> names = new ArrayList<>(parts.keySet());
        Collections.sort(names);
        for (String name : names) {
            if (name.startsWith(WORKSHEET_DIR)) {
                return parts.get(name);
            }
        }
        return null;
    }

    private static String firstSheetPartName(byte[] workbook, byte[] rels) throws IOException {
        if (workbook == null || rels == null) {
            return null;
        }
        Document book = parseXml(workbook);
        NodeList sheets = book.getElementsByTagName("sheet");
        if (sheets.getLength() == 0) {
            return null;
        }
        String relationshipId = relationshipId((Element) sheets.item(0));
        if (relationshipId == null) {
            return null;
        }
        NodeList relationships = parseXml(rels).getElementsByTagName("Relationship");
        for (int i = 0; i < relationships.getLength(); i++) {
            Element relationship = (Element) relationships.item(i);
            if (relationshipId.equals(attribute(relationship, "Id"))) {
                return resolve("xl/", attribute(relationship, "Target"));
            }
        }
        return null;
    }

    /** 取 {@code r:id}：优先按命名空间找，拿不到再退到"名字以 :id 结尾" */
    private static String relationshipId(Element sheet) {
        String byNamespace = sheet.getAttributeNS(RELATIONSHIP_NS, "id");
        if (byNamespace != null && !byNamespace.isEmpty()) {
            return byNamespace;
        }
        NamedNodeMap attributes = sheet.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String name = attribute.getNodeName();
            if ("id".equals(name) || name.endsWith(":id")) {
                return attribute.getNodeValue();
            }
        }
        return null;
    }

    /** 关系里的 Target 可能是相对路径（{@code worksheets/sheet1.xml}）或绝对路径（{@code /xl/...}） */
    private static String resolve(String baseDir, String target) {
        if (target == null || target.isEmpty()) {
            return null;
        }
        String path = target.startsWith("/") ? target.substring(1) : baseDir + target;
        return path.replace("/./", "/");
    }

    // ------------------------------------------------------------ 共享字符串

    /**
     * 共享字符串表：每个 {@code <si>} 是一条字符串，<b>下标就是单元格里引用的序号</b>。
     *
     * <p>一条 {@code <si>} 里可能有多个 {@code <t>}（富文本会把它拆成若干 run，
     * 比如前半段加粗），所以要把它们按出现顺序拼起来，而不是只取第一个。
     */
    private static List<String> parseSharedStrings(byte[] xml) throws IOException {
        List<String> values = new ArrayList<>();
        if (xml == null) {
            return values;
        }
        NodeList items = parseXml(xml).getElementsByTagName("si");
        for (int i = 0; i < items.getLength(); i++) {
            values.add(concatTexts((Element) items.item(i)));
        }
        return values;
    }

    // ---------------------------------------------------------------- 工作表

    private static List<List<String>> parseWorksheet(byte[] xml, List<String> shared) throws IOException {
        NodeList rows = parseXml(xml).getElementsByTagName("row");
        List<List<String>> matrix = new ArrayList<>();
        for (int i = 0; i < rows.getLength(); i++) {
            Element row = (Element) rows.item(i);
            // 行的位置以 r 属性为准：Excel 不会为全空的行写 <row>，
            // 按顺序自增下标会让空行之后的所有行整体上移
            int rowIndex = Math.max(0, intOf(attribute(row, "r"), matrix.size() + 1) - 1);
            List<String> line = lineAt(matrix, rowIndex);

            NodeList cells = row.getElementsByTagName("c");
            int nextColumn = 0;
            for (int c = 0; c < cells.getLength(); c++) {
                Element cell = (Element) cells.item(c);
                // 列位置同理以 r 属性（如 "B19"）为准：整列为空的单元格根本不会被写出来，
                // 不认这个属性的话，中间缺一格就会让后面所有列<b>整体左移</b>
                int column = columnIndex(attribute(cell, "r"));
                if (column < 0) {
                    column = nextColumn;
                }
                nextColumn = column + 1;
                setAt(line, column, cellValue(cell, shared));
            }
        }
        return matrix;
    }

    private static List<String> lineAt(List<List<String>> matrix, int index) {
        while (matrix.size() <= index) {
            matrix.add(new ArrayList<String>());
        }
        return matrix.get(index);
    }

    private static void setAt(List<String> line, int index, String value) {
        while (line.size() <= index) {
            line.add("");
        }
        line.set(index, value);
    }

    /**
     * 单元格取值。只有四种写法会出现在账单文件里：
     * {@code t="s"} 共享字符串、{@code t="inlineStr"} 内联字符串、
     * {@code t="str"} 公式的字符串结果、以及<b>什么都不写</b>的数值。
     */
    private static String cellValue(Element cell, List<String> shared) {
        String type = attribute(cell, "t");
        if ("inlineStr".equals(type) || "str".equals(type)) {
            String inline = concatTexts(cell);
            if (!inline.isEmpty() || "inlineStr".equals(type)) {
                return inline;
            }
        }
        String raw = childText(cell, "v");
        if (raw == null) {
            return "";
        }
        if ("s".equals(type)) {
            int index = intOf(raw, -1);
            return index >= 0 && index < shared.size() ? shared.get(index) : "";
        }
        return raw;
    }

    /** 把元素下所有 {@code <t>} 的文本按文档顺序拼起来 */
    private static String concatTexts(Element element) {
        NodeList texts = element.getElementsByTagName("t");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < texts.getLength(); i++) {
            text.append(texts.item(i).getTextContent());
        }
        return text.toString();
    }

    private static String childText(Element cell, String name) {
        NodeList children = cell.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
                String text = child.getTextContent();
                return text == null ? "" : text.trim();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 工具

    /**
     * {@code "B19"} → {@code 1}；不合法返回 {@code -1}。
     *
     * <p>Excel 的列号是 26 进制的双射（A=1 … Z=26、AA=27），
     * 所以每读一位就 {@code *26 + 位值}，最后减一变成 0 起的下标。
     */
    static int columnIndex(String reference) {
        if (reference == null) {
            return -1;
        }
        int result = 0;
        int letters = 0;
        for (int i = 0; i < reference.length(); i++) {
            char ch = reference.charAt(i);
            int value;
            if (ch >= 'A' && ch <= 'Z') {
                value = ch - 'A' + 1;
            } else if (ch >= 'a' && ch <= 'z') {
                value = ch - 'a' + 1;
            } else {
                break;
            }
            result = result * 26 + value;
            letters++;
        }
        return letters == 0 ? -1 : result - 1;
    }

    private static String attribute(Element element, String name) {
        String value = element.getAttribute(name);
        return value == null || value.isEmpty() ? null : value;
    }

    private static int intOf(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 解析 xlsx 内部的小 XML。
     *
     * <p>不用命名空间感知：这些部件里我们只按<b>标签名与属性名</b>取值，
     * 关掉命名空间解析反而少一层前缀映射的麻烦（{@code r:id} 会原样保留成属性名）。
     *
     * <p>顺手关掉外部实体展开：账单是用户从网上拿来的文件，
     * 一个能触发外部实体的解析器在这里没有任何好处。
     */
    private static Document parseXml(byte[] xml) throws IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException ignored) {
            // 某些实现不认识这个特性名。关掉实体展开已经挡住了外部实体，不必因此让导入失败
        }
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            // 文件自带 DTD 声明时不要让解析器去网络/磁盘上找它
            builder.setEntityResolver((publicId, systemId) ->
                    new org.xml.sax.InputSource(new java.io.StringReader("")));
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("这个 Excel 内部的 XML 读不了：" + e.getMessage(), e);
        }
    }
}
