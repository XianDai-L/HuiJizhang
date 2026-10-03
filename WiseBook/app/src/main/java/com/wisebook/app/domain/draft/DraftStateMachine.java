package com.wisebook.app.domain.draft;

import com.wisebook.app.domain.model.DraftStatus;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 草稿状态机（D1 §4）。
 *
 * <p>它本身没有逻辑，只是对 {@link DraftTransition} 那张动作表做校验。
 * 这是刻意的：<b>状态迁移规则集中在枚举里，状态机只负责「合不合法」和「结果是什么」</b>，
 * 于是规则可以被一眼读完，也能被逐条单测。
 *
 * <p>非法迁移直接抛异常，不做静默兜底。原因：状态机被绕过一次，
 * 「草稿 → 账目」的幂等保证就失效了（比如 {@code CONFIRMED → POSTED} 之外的路
 * 也可能写到账目表），这类错误应该在最靠近现场的地方炸出来。
 */
public final class DraftStateMachine {

    private DraftStateMachine() {
    }

    public static boolean canApply(DraftStatus from, DraftTransition transition) {
        return from != null && transition != null && transition.allows(from);
    }

    /**
     * 执行一次迁移。
     *
     * @throws IllegalStateException 该动作不允许从 {@code from} 出发
     */
    public static DraftStatus apply(DraftStatus from, DraftTransition transition) {
        if (!canApply(from, transition)) {
            throw new IllegalStateException("非法状态迁移：" + from + " 不能执行「"
                    + (transition == null ? "null" : transition.label()) + "」，允许的来源状态是 "
                    + (transition == null ? "-" : transition.allowedFrom()));
        }
        return transition.target();
    }

    /** 某个状态下还能做哪些动作（界面据此决定显示哪些按钮、隐藏哪些） */
    public static Set<DraftTransition> availableFrom(DraftStatus from) {
        if (from == null) {
            return Collections.emptySet();
        }
        Set<DraftTransition> available = EnumSet.noneOf(DraftTransition.class);
        for (DraftTransition transition : DraftTransition.values()) {
            if (transition.allows(from)) {
                available.add(transition);
            }
        }
        return Collections.unmodifiableSet(available);
    }
}
