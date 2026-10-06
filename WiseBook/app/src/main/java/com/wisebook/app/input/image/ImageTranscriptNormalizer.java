package com.wisebook.app.input.image;

/**
 * 把 OCR 出来的文本归一化成"平铺文本"。
 *
 * <p><b>为什么需要这一步</b>：P2 实验发现，{@code DeepSeek-OCR} 按"文档理解"的习惯
 * 输出 Markdown（{@code # 记账本} / {@code #### 转账} / {@code **-100.00**}），
 * 下游文本模型会把这种层级读成"一份文档"而不是"六条并列记录"，
 * 于是在记账明细图上把 <b>6 笔报成 1 笔</b>（`docs/P2-截图实验.md` §3.3）。
 *
 * <p>解法有意选择<b>确定性代码</b>而不是"再求模型一次"：多一次调用就多一次不确定性，
 * 而这里要做的事（剥掉记号）根本不需要理解语义。转写结果本来就只是"文字来源"，
 * 把它整理成平铺文本正是这一层的职责。
 *
 * <p>规则刻意保守——<b>只删记号，不动内容</b>：金额的负号与小数点一个字都不能碰，
 * 所以行首的 {@code -} 只有在后面跟空白时才当列表符号处理，
 * 否则 {@code -100.00} 会被吃掉一个字符，而"金额从这里开始错"是最贵的错法。
 */
public final class ImageTranscriptNormalizer {

    private ImageTranscriptNormalizer() {
    }

    /** 归一化；输入为 {@code null} 时返回空串 */
    public static String normalize(String transcript) {
        if (transcript == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(transcript.length());
        String[] lines = transcript.split("\n", -1);
        for (String rawLine : lines) {
            String line = stripDecorations(rawLine);
            // 连续空行压成一行：版面留白对下游没有信息量，只让文本变长
            if (line.isEmpty() && out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString().trim();
    }

    private static String stripDecorations(String rawLine) {
        String line = rawLine.trim();

        // (1) 行首层级标题：# / ## / ### …
        while (line.startsWith("#")) {
            line = line.substring(1).trim();
        }

        // (2) 行首列表符号：仅当符号后面跟空白时才算（见类注释里 -100.00 的例子）
        if (line.length() > 1
                && (line.charAt(0) == '-' || line.charAt(0) == '*'
                    || line.charAt(0) == '+' || line.charAt(0) == '>')
                && Character.isWhitespace(line.charAt(1))) {
            line = line.substring(1).trim();
        }

        // (3) 有序列表「1. 」「12. 」——只在点号后确实跟空白时剥离，
        //     所以「1.5 元」这种小数不会被误伤
        int dot = line.indexOf('.');
        if (dot > 0 && dot <= 3 && isDigits(line.substring(0, dot))
                && dot + 1 < line.length() && Character.isWhitespace(line.charAt(dot + 1))) {
            line = line.substring(dot + 1).trim();
        }

        // (4) 强调与行内代码记号。它们对下游没有任何信息量，却会打断金额与商户名的连续性
        return line.replace("**", "").replace("__", "").replace("`", "");
    }

    private static boolean isDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
