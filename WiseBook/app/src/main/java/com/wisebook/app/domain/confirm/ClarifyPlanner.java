package com.wisebook.app.domain.confirm;

import com.wisebook.app.data.local.entity.CategoryEntity;
import com.wisebook.app.data.local.entity.DraftEntity;
import com.wisebook.app.domain.classify.CategoryTree;
import com.wisebook.app.domain.model.AmountRuleCheck;
import com.wisebook.app.domain.model.CategoryCandidate;
import com.wisebook.app.domain.model.ClarifyQuestion;
import com.wisebook.app.domain.model.Direction;
import com.wisebook.app.domain.model.PaymentMethod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 反问清单的生成（D1 §4）。
 *
 * <p><b>反问由确定性判据产生，不由模型自由发挥。</b>缺哪个字段就问哪个字段，
 * 为什么问、问完之后往哪个字段写，全在代码里写死。这样做的三个好处：
 * 反问内容可复现、可单测；不会出现「模型反复问同一个问题」把 3 轮预算浪费掉；
 * 用户回答后可以<b>确定性合并</b>回草稿，不必再调一次模型。
 *
 * <p><b>{@code options} 的约定：放「可以直接写回该字段的值」</b>——
 * 方向放 {@code expense}/{@code income}，支付方式放 {@code wechat} 等 code，
 * 分类放分类路径（如 {@code 餐饮>咖啡}，因为它就是用户能读懂的写法，
 * 而本地自增 id 对用户毫无意义）。界面负责把 code 翻成中文展示。
 *
 * <p>一次把所有该问的一次问完，不做「一轮只问一个」的限制：
 * D1 §4.2 给的总预算是 3 轮，问得碎会把预算耗在往返上。
 */
public final class ClarifyPlanner {

    private ClarifyPlanner() {
    }

    /**
     * @param draft 当前草稿
     * @param tree  分类树，用于把候选分类换成可读路径、以及在没有候选时兜底给一级分类
     * @return 需要用户补充的问题；为空表示没有要问的，可直接进确认页
     */
    public static List<ClarifyQuestion> plan(DraftEntity draft, CategoryTree tree) {
        List<ClarifyQuestion> questions = new ArrayList<>();

        // 金额：缺失，或规则通道没能给出可信的值
        if (needsAmountQuestion(draft)) {
            questions.add(new ClarifyQuestion("amountCents", "这笔具体是多少钱？"));
        }

        // 方向：模型没给出来时必问，否则「工资」这种分类会挂到支出上
        // 这里用 Arrays.asList 而不是 List.of（后者在 Android 上需要 API 30）：
        // 传进去之后 ClarifyQuestion 的构造器会自己做不可变拷贝，此处无需再包一层
        if (draft.direction == null) {
            questions.add(new ClarifyQuestion("direction", "这笔是支出还是收入？", Arrays.asList(
                    Direction.EXPENSE.code(), Direction.INCOME.code())));
        }

        // 分类：没定下来才问；只是「摇摆但已采用首选」的不问，那种情况在确认页展示、可改
        if (draft.categoryId == null) {
            List<String> options = categoryOptions(draft, tree);
            questions.add(new ClarifyQuestion("categoryId", "这笔该记到哪个分类？",
                    options.isEmpty() ? null : options));
        }

        // 支付方式<b>不在这里问</b>：它已降级为可选字段（HANDOFF 决策 20）。
        // 只要它还是「缺了就反问」，草稿就一定会进 ASKING、一定要用户点一次，
        // 那就等于绕了一圈又变回了"每笔都要确认"。
        // 用户想补的话，在确认页上顺手选一个即可。

        return questions;
    }

    /** 金额缺失、或规则通道判成 fail/na —— 都不能算「有可信的金额」 */
    private static boolean needsAmountQuestion(DraftEntity draft) {
        if (draft.amountCents == null) {
            return true;
        }
        return draft.amountRuleCheck != AmountRuleCheck.PASS;
    }

    /**
     * 分类候选的展示选项。
     *
     * <p>优先用模型给出的候选（那也是它最可能答对的几个）；
     * 一个候选都没有时退回「该方向下的一级分类名」——
     * 一级分类只有 6 到 10 项，作为选择题是可用的，总好过让用户凭空手输。
     */
    private static List<String> categoryOptions(DraftEntity draft, CategoryTree tree) {
        List<String> options = new ArrayList<>();
        if (draft.categoryCandidates != null) {
            for (CategoryCandidate candidate : draft.categoryCandidates) {
                String path = tree.pathOf(candidate.categoryId);
                if (path != null && !options.contains(path)) {
                    options.add(path);
                }
            }
        }
        if (options.isEmpty() && draft.direction != null) {
            for (CategoryEntity top : tree.topLevel(draft.direction)) {
                options.add(top.name);
            }
        }
        return options;
    }

    /** 供界面在确认页上做可选的支付方式选择器用（不是反问项） */
    public static List<String> paymentMethodChoices() {
        List<String> options = new ArrayList<>();
        for (PaymentMethod method : PaymentMethod.values()) {
            options.add(method.code());
        }
        return options;
    }
}
