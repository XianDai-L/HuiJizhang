package com.wisebook.app.ui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.app.domain.model.CategoryCandidate;
import com.wisebook.app.domain.model.ClarifyQuestion;
import com.wisebook.app.domain.model.ConfidenceFlag;

import java.util.ArrayList;
import java.util.List;

/**
 * 「AI 处理详情」面板的文本渲染。
 *
 * <p>它回答的是同一个问题的四个方面：<b>模型看到了什么、给出了什么、
 * 代码据此判了什么、最后为什么拦下（或放行）这笔账。</b>
 *
 * <p>为什么要把这些摊开给用户看：
 * <ul>
 *   <li><b>可信度</b>。记账应用最怕的是"它凭什么这么记"。让人能翻到底牌，
 *       比任何一句"AI 很准"都有说服力</li>
 *   <li><b>可调试</b>。分类判错时，光看结果无法区分是模型选错了、还是候选解析错了、
 *       还是摇摆判据误伤。这一屏把三段分开摆，一眼能定位</li>
 *   <li><b>可研究</b>。模型自报的把握程度只有被看见、被记录，才能与
 *       "用户实际改没改"对照出结论</li>
 * </ul>
 *
 * <p>刻意做成纯字符串函数：它不碰 Android、不碰数据库，
 * 因此可以直接在 JVM 上验证排版与取值，不必起模拟器。
 */
public final class DiagnosticsFormatter {

    private DiagnosticsFormatter() {
    }

    /**
     * @param attemptCount          本次调用模型的次数；传 {@code 0} 表示不知道
     *                              （从账目详情页回看历史账目时，调用次数并没有存进库里），
     *                              此时这一行直接不显示，而不是写一句"未知"占着位置
     * @param draft                 解析出的草稿
     * @param rawPayload            模型原始返回的 JSON；没成功时为 {@code null}
     * @param categoryReason        这个分类是怎么定下来的（映射表命中 / 平台型规则 /
     *                              采用模型 / 兜底「其他」）。用户想知道的是"它凭什么这么分"，
     *                              所以这一行必须写清依据，而不是只给一个分类名
     */
    public static String describe(int attemptCount, boolean firstAttemptSucceeded,
                                  DraftEntity draft, String rawPayload, String categoryReason) {
        StringBuilder text = new StringBuilder();

        field(text, "模型", draft.model == null ? "（未知）" : draft.model);
        if (attemptCount > 0) {
            field(text, "调用", attemptCount + " 次"
                    + (firstAttemptSucceeded ? "，首次就通过" : "，含重试"));
        }
        field(text, "金额", amount(draft));
        field(text, "方向", draft.direction == null ? "未定" : draft.direction.label());
        field(text, "分类", category(draft));
        field(text, "依据", categoryReason == null ? "（未知）" : categoryReason);
        field(text, "存疑", flags(draft));
        field(text, "反问", questions(draft));

        if (rawPayload != null && !rawPayload.isEmpty()) {
            text.append("\n— 模型原始返回 —\n").append(pretty(rawPayload));
        }
        return text.toString();
    }

    // ------------------------------------------------------------------ 各行

    /** 字段名固定两字，后面补四个空格即可对齐（不必按显示宽度精确计算） */
    private static void field(StringBuilder text, String name, String value) {
        text.append(name).append("    ").append(value).append('\n');
    }

    private static String amount(DraftEntity draft) {
        if (draft.amountCents == null) {
            return "未定（会触发反问）";
        }
        StringBuilder text = new StringBuilder(DraftFormatter.yuan(draft.amountCents)).append(" 元");
        if (draft.amountRaw != null) {
            text.append("（原文「").append(draft.amountRaw).append("」）");
        }
        text.append("，规则校验 ").append(ruleCheck(draft.amountRuleCheck));
        if (draft.amountIsEstimated) {
            text.append("，约数折算");
        }
        if (draft.amountLowerCents != null || draft.amountUpperCents != null) {
            text.append("，区间 ")
                    .append(DraftFormatter.yuan(draft.amountLowerCents)).append("~")
                    .append(DraftFormatter.yuan(draft.amountUpperCents)).append(" 元");
        }
        return text.toString();
    }

    private static String category(DraftEntity draft) {
        if (draft.categoryId == null) {
            return "未定（会触发反问）";
        }
        CategoryCandidate adopted = adopted(draft);
        return adopted == null ? ("#" + draft.categoryId) : adopted.name;
    }

    private static String flags(DraftEntity draft) {
        if (draft.confidenceFlags == null || draft.confidenceFlags.isEmpty()) {
            return "无";
        }
        List<String> labels = new ArrayList<>();
        for (ConfidenceFlag flag : draft.confidenceFlags) {
            labels.add(flag.label());
        }
        return String.join("、", labels);
    }

    private static String questions(DraftEntity draft) {
        if (draft.clarifyQuestions == null || draft.clarifyQuestions.isEmpty()) {
            return "无";
        }
        List<String> texts = new ArrayList<>();
        for (ClarifyQuestion question : draft.clarifyQuestions) {
            texts.add(question.question);
        }
        return String.join("；", texts);
    }

    // ------------------------------------------------------------------ 工具

    private static CategoryCandidate adopted(DraftEntity draft) {
        if (draft.categoryId == null || draft.categoryCandidates == null) {
            return null;
        }
        for (CategoryCandidate candidate : draft.categoryCandidates) {
            if (candidate.categoryId == draft.categoryId) {
                return candidate;
            }
        }
        return null;
    }

    private static String ruleCheck(AmountRuleCheck check) {
        if (check == null) {
            return "未知";
        }
        switch (check) {
            case PASS:
                return "通过（规则重算与模型一致）";
            case FAIL:
                return "未通过（规则重算与模型不一致）";
            case NA:
            default:
                return "无法校验";
        }
    }

    /**
     * 原始返回只做缩进，<b>不做任何字段级加工</b>。
     *
     * <p>排查「模型为什么这么判」时，加工过的视图最危险：它会把线索按我的理解重新排列，
     * 于是我只能看到我以为的东西。原始返回不会说谎。
     */
    private static String pretty(String rawJson) {
        try {
            return new GsonBuilder().setPrettyPrinting().create()
                    .toJson(JsonParser.parseString(rawJson));
        } catch (RuntimeException e) {
            return rawJson;
        }
    }
}
