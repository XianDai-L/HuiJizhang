package com.wisebook.app.domain.draft;

import com.wisebook.app.domain.model.DraftStatus;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 草稿状态迁移的<b>动作表</b>（D1 §4）。
 *
 * <p><b>为什么建模成「动作」而不是「状态对」：</b>同一个状态对可能对应意图完全不同的迁移。
 * 最典型的是 {@code POSTED}：用户既可能「撤销」（这笔不算了），也可能「改正」（改完重记）。
 * 如果状态机只认 {@code from → to}，这两种意图就分不开，日志里也看不出用户到底干了什么。
 * 用动作命名之后，每个动作各自承担自己的副作用（撤销要删账目行、改正不用）。
 *
 * <p>表里每一行的三个信息：动作名、目标状态、允许的来源状态集合。
 * 状态机本身因此退化成一个查表校验（见 {@link DraftStateMachine}）。
 *
 * <p><b>两处与 D1 §4 状态图不一致的地方，都是有意为之：</b>
 * <ul>
 *   <li>{@link #TAKE_OVER} / {@link #DISCARD} 允许从 {@code EXPIRED} 出发。§4 的图把
 *       {@code EXPIRED} 画成终态，但 §4.3 又要求超时条目「不删除、由用户自行处理」——
 *       若不可再流转，§4.3 就无从落地。按 §4.3 的意图处理。</li>
 *   <li>{@link #VOID} / {@link #CORRECT} 允许从 {@code POSTED} 出发。§4 的图只画了
 *       自动流转，而这两个动作是 §5.3 明确要求的「一键撤销 / 一键改正」，
 *       作用对象正是已落账的账目。</li>
 * </ul>
 */
public enum DraftTransition {

    /** DRAFT → ASKING：命中确认条件，开始反问 */
    START_CLARIFY("发起反问", DraftStatus.ASKING, DraftStatus.DRAFT),

    /**
     * ASKING → DRAFT：用户回答后<b>必须回流重新校验</b>（D1 §4.2 约束 1）。
     * 不得直接进 CONFIRMED——回答可能带出新信息，也可能本身有误。
     */
    ANSWER_CLARIFY("用户回答，回流重新校验", DraftStatus.DRAFT, DraftStatus.ASKING),

    /** DRAFT → CONFIRMED：八条件全满足，免确认直落（D1 §5.2） */
    AUTO_CONFIRM("免确认直落", DraftStatus.CONFIRMED, DraftStatus.DRAFT),

    /** DRAFT / ASKING → CONFIRMED：用户在确认页点了确认 */
    USER_CONFIRM("用户确认", DraftStatus.CONFIRMED, DraftStatus.DRAFT, DraftStatus.ASKING),

    /** CONFIRMED → POSTED：幂等写入事务（D1 §4.2 约束 3） */
    POST("写入账本", DraftStatus.POSTED, DraftStatus.CONFIRMED),

    /** DRAFT / ASKING / EXPIRED → DISCARDED：用户主动丢弃 */
    DISCARD("丢弃草稿", DraftStatus.DISCARDED,
            DraftStatus.DRAFT, DraftStatus.ASKING, DraftStatus.EXPIRED),

    /** DRAFT / ASKING → EXPIRED：挂起超过 24 小时，停止自动流转（D1 §4.3） */
    EXPIRE("超时归档", DraftStatus.EXPIRED, DraftStatus.DRAFT, DraftStatus.ASKING),

    /** EXPIRED → DRAFT：用户从「待处理」里接管一笔超时草稿 */
    TAKE_OVER("接管超时草稿", DraftStatus.DRAFT, DraftStatus.EXPIRED),

    /**
     * POSTED → DISCARDED：撤销一笔已入账的记录（D1 §5.3「一键撤销」）。
     * 账目行会被物理删除，而草稿保留为 DISCARDED——{@code raw_input} 与模型信息是
     * 可解释性的基础，不能跟着账目一起消失。
     */
    VOID("撤销已入账记录", DraftStatus.DISCARDED, DraftStatus.POSTED),

    /**
     * POSTED → DRAFT：改正一笔已入账的记录（D1 §5.3「一键改正」）。
     * 回到可编辑状态，改完重新走校验与确认，不绕过任何一道防线。
     */
    CORRECT("改正已入账记录", DraftStatus.DRAFT, DraftStatus.POSTED);

    private final String label;
    private final DraftStatus target;
    private final Set<DraftStatus> allowedFrom;

    DraftTransition(String label, DraftStatus target, DraftStatus... allowedFrom) {
        this.label = label;
        this.target = target;
        // 不用 Set.of：Android 上它是 API 30 才有的（minSdk 26），详见 HANDOFF §8。
        // 用 LinkedHashSet 而不是 EnumSet，是为了让 keep 声明顺序——
        // 上面那条报错信息里会打印这个集合，顺序固定才便于对照源码。
        this.allowedFrom = Collections.unmodifiableSet(
                new LinkedHashSet<>(Arrays.asList(allowedFrom)));
    }

    /** 界面展示用的中文动作名 */
    public String label() {
        return label;
    }

    /** 执行后的目标状态 */
    public DraftStatus target() {
        return target;
    }

    /** 允许的来源状态 */
    public Set<DraftStatus> allowedFrom() {
        return allowedFrom;
    }

    public boolean allows(DraftStatus from) {
        return allowedFrom.contains(from);
    }
}
