package com.wisebook.app.input.image;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把转写文本里的「日期标题」下推到每一行记录上（2026-10-08）。
 *
 * <p><b>为什么要有这一步</b>：账单列表类截图里，日期往往只在分组标题出现一次
 * （{@code 10月1日 星期四}），下面每条记录只写时刻（{@code 18:34 | 转给某某}）。
 * 而「这个标题管到哪几条记录」恰恰是模型判得最不稳的地方——`docs/P2-截图实验.md` §3.2
 * 实测三条路线都把时间标签挂给了上面那笔。挂错的代价不只是时间记错：
 * 去重键里含发生时间，两笔同一账单的时间一旦一个对一个错，重复就判不出来，
 * 于是同一笔进了两次账。
 *
 * <p>所以这里把版面判断从模型手里拿回来：<b>代码确定性地把日期补到行上，
 * 模型照抄即可</b>。判据可单测、结果可复现，真出问题时也看得出是哪条规则没命中。
 *
 * <p><b>补不了的不猜</b>：截图从中间裁起时，开头那几笔属于被裁掉标题的那一组，
 * 文本里没有任何可用日期——这时保持原样，让它们照旧落 fallback，
 * 由 {@code ConfirmPolicy} 的「没读到交易时间」那条把用户拦下来核对。
 * 不做「拿下面的标题往前推」这种推断：账单列表按时间倒序排列，
 * 推错的代价是静默错账，而静默错账比多问一句贵得多。
 *
 * <p>只给<b>含时刻的行</b>加前缀：一笔记录可能占好几行（分类 / 金额 / 时刻与描述），
 * 而日期与时刻本该是一对，挂在时刻行上语义最干净，也不会把整段文本改得很吵。
 */
public final class ImageTranscriptDatePropagator {

    /** 阿拉伯或中文写的「月日」，如 {@code 10月1日} / {@code 九月五号} */
    private static final String MONTH_DAY =
            "[0-9零〇一二三四五六七八九十]{1,3}月"
                    + "[0-9零〇一二三四五六七八九十]{1,3}[日号]";

    /**
     * 日期标题行：<b>整行只由日期（可带星期）与当天统计构成</b>才算，
     * 如 {@code 10月1日 星期四}、{@code 10月3日 星期六 出 0.00 入 200.00}。
     *
     * <p>要求整行匹配是刻意的：只做「含日期」判断的话，
     * 「今天便利店 -20」这类带日期的商户行会被误当成标题，
     * 后面的记录就会全被补上一个错的日期。
     *
     * <p>行尾允许的那截字符类（空白 / 数字 / 金额符号 / 「出入收支」）
     * 是 2026-10-08 实机反馈后放宽的：有的记账 App 把当天的「出 X 入 Y」
     * 印在同一个标题行里，原先"只许日期和星期"会让整行匹配失败，
     * 一整天的记录都推不下日期。允许的字符仍卡得很死——
     * {@code 10月3日 转账 -100} 这种记录行照样进不来，上面那条防线没松动。
     *
     * <p>刻意<b>不认</b> {@code 2026-10-01} / {@code 2026/10/1} 这类连字符写法：
     * {@code RelativeTimeResolver} 目前也解析不了它们，下推出去等于白推
     * （甚至更糟——文本里看着有日期，实际只会命中时刻、日期仍是今天）。
     * 两边要认就必须一起认，这留到以后一起做。
     */
    private static final Pattern DATE_TITLE = Pattern.compile(
            "^(?<date>"
                    + "\\d{4}年" + MONTH_DAY
                    + "|" + MONTH_DAY
                    + "|今天|昨天|前天|大前天"
                    + ")"
                    + "(?:\\s*(?:星期|周|礼拜)[一二三四五六日天])?"
                    + "[\\s\\d.,¥￥出入收支]*$");

    /** 行内时刻，如 {@code 18:34}（全角冒号也认，OCR 会输出它） */
    private static final Pattern CLOCK = Pattern.compile("\\d{1,2}[:：]\\d{2}");

    /** 行内已经带日期时的样子：这种行不补，免得出现「10月1日 10月1日 18:34」 */
    private static final Pattern HAS_DATE = Pattern.compile(
            MONTH_DAY + "|今天|昨天|前天|大前天");

    private ImageTranscriptDatePropagator() {
    }

    /**
     * 下推日期。没有标题、或某几笔补不上日期时，对应内容原样保留。
     *
     * @param transcript 归一化后的转写文本（{@code ImageTranscriptNormalizer} 的输出）
     * @return 补过日期的文本；行数与顺序不变
     */
    public static String propagate(String transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return transcript == null ? "" : transcript;
        }
        String[] lines = transcript.split("\n", -1);

        // 先扫一遍：全图有几个日期标题？这一步决定了开头那几笔的命运——
        // 只有一个标题时它管全图（截图从中间裁起、上面那组也是同一天），
        // 有多个标题时说明文本确实是分组的，标题之前的记录就没有日期可用
        int titleCount = 0;
        String theOnlyDate = null;
        for (String line : lines) {
            String date = dateTitleOf(line);
            if (date != null) {
                titleCount++;
                theOnlyDate = date;
            }
        }
        if (titleCount == 0) {
            return transcript;
        }

        String currentDate = titleCount == 1 ? theOnlyDate : null;
        StringBuilder out = new StringBuilder(transcript.length() + lines.length * 8);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String date = dateTitleOf(line);
            if (date != null) {
                // 标题行原样保留：模型靠它认分组，删掉反而少了线索
                currentDate = date;
                out.append(line);
            } else if (currentDate != null && needsDate(line)) {
                out.append(currentDate).append(' ').append(line);
            } else {
                out.append(line);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /** 整行是日期标题时返回其中的日期片段，否则返回 {@code null} */
    private static String dateTitleOf(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        Matcher matcher = DATE_TITLE.matcher(line.trim());
        return matcher.matches() ? matcher.group("date") : null;
    }

    /** 这一行要不要补日期：非空、自己不带日期、且含时刻 */
    private static boolean needsDate(String line) {
        if (line.isEmpty()) {
            return false;
        }
        if (HAS_DATE.matcher(line).find()) {
            return false;
        }
        return CLOCK.matcher(line).find();
    }
}
