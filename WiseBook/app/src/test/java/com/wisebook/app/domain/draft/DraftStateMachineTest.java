package com.wisebook.app.domain.draft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.wisebook.app.domain.model.DraftStatus;

import org.junit.Test;

/**
 * 草稿状态机（D1 §4）。
 */
public class DraftStateMachineTest {

    @Test
    public void happyPath_draftToAskingBackToDraftToPosted() {
        // 用户回答后必须回流 DRAFT 重新校验，不得直接 CONFIRMED（D1 §4.2 约束 1）
        DraftStatus status = DraftStatus.DRAFT;
        status = DraftStateMachine.apply(status, DraftTransition.START_CLARIFY);
        assertEquals(DraftStatus.ASKING, status);

        status = DraftStateMachine.apply(status, DraftTransition.ANSWER_CLARIFY);
        assertEquals("回答后必须回到 DRAFT 重新校验", DraftStatus.DRAFT, status);

        status = DraftStateMachine.apply(status, DraftTransition.USER_CONFIRM);
        assertEquals(DraftStatus.CONFIRMED, status);

        status = DraftStateMachine.apply(status, DraftTransition.POST);
        assertEquals(DraftStatus.POSTED, status);
    }

    @Test
    public void autoConfirmPath() {
        DraftStatus status = DraftStateMachine.apply(DraftStatus.DRAFT, DraftTransition.AUTO_CONFIRM);
        assertEquals(DraftStatus.CONFIRMED, status);
        assertEquals(DraftStatus.POSTED, DraftStateMachine.apply(status, DraftTransition.POST));
    }

    @Test
    public void askingCanBeConfirmedDirectlyByUser() {
        // D1 §4 图里 ASKING 可以直接被用户确认
        assertEquals(DraftStatus.CONFIRMED,
                DraftStateMachine.apply(DraftStatus.ASKING, DraftTransition.USER_CONFIRM));
    }

    @Test
    public void cannotPostWithoutConfirming() {
        // 这道校验是「草稿 → 账目」幂等链路的第一道门：绕过 CONFIRMED 直接写库必须被挡住
        assertThrows(IllegalStateException.class,
                () -> DraftStateMachine.apply(DraftStatus.DRAFT, DraftTransition.POST));
        assertThrows(IllegalStateException.class,
                () -> DraftStateMachine.apply(DraftStatus.ASKING, DraftTransition.POST));
    }

    @Test
    public void cannotAutoConfirmWhileAsking() {
        // 免确认只可能发生在 DRAFT；已经在反问的草稿说明它有疑点，不能悄悄落账
        assertFalse(DraftStateMachine.canApply(DraftStatus.ASKING, DraftTransition.AUTO_CONFIRM));
        assertThrows(IllegalStateException.class,
                () -> DraftStateMachine.apply(DraftStatus.ASKING, DraftTransition.AUTO_CONFIRM));
    }

    @Test
    public void cannotClarifyTwice() {
        assertFalse(DraftStateMachine.canApply(DraftStatus.ASKING, DraftTransition.START_CLARIFY));
        assertFalse(DraftStateMachine.canApply(DraftStatus.CONFIRMED, DraftTransition.START_CLARIFY));
    }

    @Test
    public void discardedIsDeadEnd() {
        assertTrue(DraftStateMachine.availableFrom(DraftStatus.DISCARDED).isEmpty());
    }

    @Test
    public void postedCanOnlyBeVoidedOrCorrected() {
        assertEquals("已入账的记录只能撤销或改正，不能直接丢弃",
                java.util.EnumSet.of(DraftTransition.VOID, DraftTransition.CORRECT),
                DraftStateMachine.availableFrom(DraftStatus.POSTED));
        assertFalse("POSTED 不能再用 DISCARD 兜底，必须走 VOID 表明意图",
                DraftStateMachine.canApply(DraftStatus.POSTED, DraftTransition.DISCARD));
    }

    @Test
    public void expiredCanBeTakenOverOrDiscarded() {
        // D1 §4.3 要求超时条目不删除、由用户自行处理，所以它不是死终态
        assertTrue(DraftStateMachine.canApply(DraftStatus.EXPIRED, DraftTransition.TAKE_OVER));
        assertTrue(DraftStateMachine.canApply(DraftStatus.EXPIRED, DraftTransition.DISCARD));
        assertEquals(DraftStatus.DRAFT,
                DraftStateMachine.apply(DraftStatus.EXPIRED, DraftTransition.TAKE_OVER));
    }

    @Test
    public void expireOnlyFromOpenStates() {
        assertTrue(DraftStateMachine.canApply(DraftStatus.DRAFT, DraftTransition.EXPIRE));
        assertTrue(DraftStateMachine.canApply(DraftStatus.ASKING, DraftTransition.EXPIRE));
        assertFalse(DraftStateMachine.canApply(DraftStatus.CONFIRMED, DraftTransition.EXPIRE));
        assertFalse(DraftStateMachine.canApply(DraftStatus.POSTED, DraftTransition.EXPIRE));
    }

    @Test
    public void discardFromOpenStatesAndExpired() {
        assertTrue(DraftStateMachine.canApply(DraftStatus.DRAFT, DraftTransition.DISCARD));
        assertTrue(DraftStateMachine.canApply(DraftStatus.ASKING, DraftTransition.DISCARD));
        assertTrue(DraftStateMachine.canApply(DraftStatus.EXPIRED, DraftTransition.DISCARD));
        assertFalse(DraftStateMachine.canApply(DraftStatus.CONFIRMED, DraftTransition.DISCARD));
    }

    @Test
    public void nullInputsAreRejectedNotCrashing() {
        assertFalse(DraftStateMachine.canApply(null, DraftTransition.POST));
        assertFalse(DraftStateMachine.canApply(DraftStatus.DRAFT, null));
        assertTrue(DraftStateMachine.availableFrom(null).isEmpty());
    }
}
