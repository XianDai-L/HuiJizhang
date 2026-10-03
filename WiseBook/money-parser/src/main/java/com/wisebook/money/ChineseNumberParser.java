package com.wisebook.money;

import java.util.HashMap;
import java.util.Map;

/**
 * 中文数字串解析。
 *
 * <p>核心难点是「省略单位」，即口语中数量级后跟裸数字时，裸数字承接下一级单位：
 *
 * <table border="1">
 *   <caption>省略单位规则</caption>
 *   <tr><th>输入</th><th>结果</th><th>说明</th></tr>
 *   <tr><td>三十五</td><td>35</td><td>裸数字「五」承接「十」的下一级，即个位</td></tr>
 *   <tr><td>三百五</td><td>350</td><td>裸数字承接「百」的下一级，即十位</td></tr>
 *   <tr><td>三百零五</td><td>305</td><td>「零」显式占位，切断承接关系</td></tr>
 *   <tr><td>一千五</td><td>1500</td><td>承接「千」的下一级，即百位</td></tr>
 *   <tr><td>一万五</td><td>15000</td><td>承接「万」的下一级，即千位</td></tr>
 *   <tr><td>三百五十</td><td>350</td><td>单位显式给出，与「三百五」等价</td></tr>
 * </table>
 *
 * <p>算法：解析过程中记住「上一个出现的小单位 lastUnit」，
 * 遇到裸数字时其权重为 {@code lastUnit / 10}；遇到「零」则重置承接关系。
 */
final class ChineseNumberParser {

    private static final Map<Character, Long> DIGITS = new HashMap<>();
    private static final Map<Character, Long> SMALL_UNITS = new HashMap<>();
    private static final Map<Character, Long> SECTION_UNITS = new HashMap<>();

    static {
        DIGITS.put('零', 0L);
        DIGITS.put('〇', 0L);
        DIGITS.put('一', 1L);
        DIGITS.put('壹', 1L);
        DIGITS.put('二', 2L);
        DIGITS.put('贰', 2L);
        DIGITS.put('两', 2L);
        DIGITS.put('三', 3L);
        DIGITS.put('叁', 3L);
        DIGITS.put('四', 4L);
        DIGITS.put('肆', 4L);
        DIGITS.put('五', 5L);
        DIGITS.put('伍', 5L);
        DIGITS.put('六', 6L);
        DIGITS.put('陆', 6L);
        DIGITS.put('七', 7L);
        DIGITS.put('柒', 7L);
        DIGITS.put('八', 8L);
        DIGITS.put('捌', 8L);
        DIGITS.put('九', 9L);
        DIGITS.put('玖', 9L);

        SMALL_UNITS.put('十', 10L);
        SMALL_UNITS.put('拾', 10L);
        SMALL_UNITS.put('百', 100L);
        SMALL_UNITS.put('佰', 100L);
        SMALL_UNITS.put('千', 1000L);
        SMALL_UNITS.put('仟', 1000L);

        SECTION_UNITS.put('万', 10_000L);
        SECTION_UNITS.put('萬', 10_000L);
    }

    private ChineseNumberParser() {
    }

    /** 是否为中文数字字符（用于词法切分） */
    static boolean isNumberChar(char c) {
        return DIGITS.containsKey(c) || SMALL_UNITS.containsKey(c) || SECTION_UNITS.containsKey(c);
    }

    /** 单个中文数字字符的字面值，非数字字符返回 {@code null} */
    static Long digitValue(char c) {
        return DIGITS.get(c);
    }

    /**
     * 解析中文数字串。串中不得含非数字字符，否则返回 {@code null}。
     */
    static Long parse(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }

        long total = 0L;      // 已结算的「节」之和（万级及以上）
        long section = 0L;    // 当前节累计值
        long pending = -1L;   // 尚未归位的裸数字
        long lastUnit = 0L;   // 上一个出现的小单位，用于末尾裸数字的承接

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            Long digit = DIGITS.get(c);
            if (digit != null) {
                if (digit == 0L) {
                    // 「零」显式占位：切断承接关系，其后裸数字权重回落为个位
                    pending = -1L;
                    lastUnit = 0L;
                } else {
                    pending = digit;
                }
                continue;
            }

            Long unit = SMALL_UNITS.get(c);
            if (unit != null) {
                // 「十五」中「十」前无数字，默认取 1
                section += (pending < 0 ? 1L : pending) * unit;
                lastUnit = unit;
                pending = -1L;
                continue;
            }

            Long sectionUnit = SECTION_UNITS.get(c);
            if (sectionUnit != null) {
                section = (section + (pending < 0 ? 1L : pending)) * sectionUnit;
                total += section;
                section = 0L;
                lastUnit = sectionUnit;
                pending = -1L;
                continue;
            }

            return null;
        }

        if (pending >= 0) {
            section += lastUnit > 0 ? pending * (lastUnit / 10) : pending;
        }
        return total + section;
    }
}
