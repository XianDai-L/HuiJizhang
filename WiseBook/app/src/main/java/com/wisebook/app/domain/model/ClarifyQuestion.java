package com.wisebook.app.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条反问（D1 §3.2 的 {@code clarify_questions}）。
 *
 * <p>反问由<b>确定性判据</b>生成，不由模型自由发挥：缺字段就问那个字段，
 * 分类摇摆就给候选让用户点。这样反问内容可复现、可单测，
 * 也不会出现「模型反复问同一个问题」把轮数浪费掉。
 */
public final class ClarifyQuestion {

    /**
     * 目标字段名，与 {@code DraftEntity} 的字段同名（如 {@code amountCents} /
     * {@code categoryId} / {@code paymentMethod}）。用户回答后按这个字段名合并回草稿。
     */
    public String field;

    /** 展示给用户的问题 */
    public String question;

    /** 选择题的选项；开放题（如「具体多少钱」）为 {@code null} */
    public List<String> options;

    /** Gson 反序列化需要无参构造 */
    public ClarifyQuestion() {
    }

    /** 开放题 */
    public ClarifyQuestion(String field, String question) {
        this(field, question, null);
    }

    /** 选择题 */
    public ClarifyQuestion(String field, String question, List<String> options) {
        this.field = field;
        this.question = question;
        // 不用 List.copyOf：Android 上它是 API 31 才有的（minSdk 26），详见 HANDOFF §8
        this.options = options == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(options));
    }

    /** 是否选择题（有选项则用户点选即可，无需再调模型） */
    public boolean isChoice() {
        return options != null && !options.isEmpty();
    }

    public List<String> optionsOrEmpty() {
        return options == null ? Collections.emptyList() : options;
    }

    @Override
    public String toString() {
        return question + " → " + field;
    }
}
