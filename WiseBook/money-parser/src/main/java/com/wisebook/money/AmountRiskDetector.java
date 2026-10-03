package com.wisebook.money;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于原文的确定性风险检测。
 *
 * <p>这些判据不依赖模型，命中即打标，由确认档位决定是否打断用户。
 */
public final class AmountRiskDetector {

    private AmountRiskDetector() {
    }

    private static final Pattern MULTI_TRANSACTION =
            Pattern.compile("[，,。；;]|还有|另外|再加|以及");

    private static final Pattern SHARED_SPLIT =
            Pattern.compile("(?i)(aa|平摊|分摊|均摊|一人一半|平均|每人)");

    private static final Pattern QUANTITY_HINT =
            Pattern.compile("[0-9一二三四五六七八九十百]+\\s*(杯|份|个|盒|瓶|张|件|斤|公斤|只|包|碗|双|次|人)");

    private static final Pattern NUMBER_RUN =
            Pattern.compile("[〇零一二三四五六七八九十百千万]+");

    private static final Pattern ANY_NUMBER =
            Pattern.compile("[0-9]+|[一二三四五六七八九十百千万]+");

    /**
     * @param text      原始输入（或 ASR 转写文本）
     * @param fromVoice 是否来自语音入口
     */
    public static Set<AmountRisk> detect(String text, boolean fromVoice) {
        Set<AmountRisk> risks = EnumSet.noneOf(AmountRisk.class);
        // 刻意用 trim().isEmpty() 而不是 String.isBlank()：
        // isBlank() 在 Android 上是 API 33 才有的，而本项目 minSdk 26。
        // 同样的替换遍布三个模块，原因记在 HANDOFF §8。
        if (text == null || text.trim().isEmpty()) {
            return risks;
        }

        if (fromVoice && hasFourTenConfusion(text)) {
            risks.add(AmountRisk.ASR_FOUR_TEN);
        }
        if (MULTI_TRANSACTION.matcher(text).find()) {
            risks.add(AmountRisk.MULTI_TRANSACTION);
        }
        if (SHARED_SPLIT.matcher(text).find()) {
            risks.add(AmountRisk.SHARED_SPLIT);
        }
        // 启发式：出现量词，且存在两处以上数字 → 可能是「三杯咖啡一杯15」
        if (QUANTITY_HINT.matcher(text).find() && countNumbers(text) >= 2) {
            risks.add(AmountRisk.QUANTITY_PRICING);
        }
        return risks;
    }

    /**
     * 「四 / 十」混淆风险判定。
     *
     * <p>最初写成「只要含四或十就打标」，但「十」在金额口语里是超高频字，
     * 那种判据几乎恒为真，等于没有筛选作用。
     *
     * <p>收紧为两种真正会改变金额的情形：
     * <ol>
     *   <li>同一个数字串里「四」和「十」同时出现 —— 十四 ↔ 四十 的经典互换</li>
     *   <li>数字串整体就是「四」或「十」 —— 四块 ↔ 十块 的互换（2.5 倍误差）</li>
     * </ol>
     *
     * <p>「十五」「三十五」这类只含「十」的表达不误报。
     */
    private static boolean hasFourTenConfusion(String text) {
        Matcher matcher = NUMBER_RUN.matcher(text);
        while (matcher.find()) {
            String run = matcher.group();
            boolean hasFour = run.indexOf('四') >= 0 || run.indexOf('肆') >= 0;
            boolean hasTen = run.indexOf('十') >= 0 || run.indexOf('拾') >= 0;
            if ((hasFour && hasTen)
                    || run.equals("四") || run.equals("十")
                    || run.equals("肆") || run.equals("拾")) {
                return true;
            }
        }
        return false;
    }

    /** 粗略统计数字块数量，用于量词推理的启发式判断 */
    private static int countNumbers(String text) {
        Matcher matcher = ANY_NUMBER.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
