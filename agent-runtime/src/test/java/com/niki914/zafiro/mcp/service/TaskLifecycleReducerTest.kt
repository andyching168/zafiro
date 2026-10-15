// 保护：任务生命周期推导——零散的远端工具调用不能被说成「已完成」，用户停止要落在 STOPPED，
// 等待授权是 PAUSED，原生回合优先于远端活动。改坏会让通知/观察界面对任务结局说谎。
package com.niki914.zafiro.mcp.service

import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.TurnOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskLifecycleReducerTest {
    private fun remote(
        engaged: Boolean = false,
        session: Boolean = false,
        end: SessionEnd? = null,
    ) = RemoteTaskInput(engaged, session, null, end)

    @Test
    fun nativeTurnWinsOverRemoteActivityAndApprovalIsPaused() {
        val running = TaskLifecycleReducer.running(remote(engaged = true), AgentState.Thinking(null), 1L)
        assertEquals(TaskSource.NATIVE_AGENT, running?.source)
        assertEquals(TaskLifecycle.RUNNING, running?.lifecycle)
    }

    @Test
    fun remoteActivityIsRunningOnlyWhileEngaged() {
        assertNull(TaskLifecycleReducer.running(remote(), AgentState.Idle(), 1L))
        assertEquals(
            TaskSource.REMOTE_MCP,
            TaskLifecycleReducer.running(remote(engaged = true), AgentState.Idle(), 1L)?.source,
        )
    }

    @Test
    fun bareRemoteToolCallsHaveNoTerminalOutcome() {
        val previous = TaskSnapshot(TaskLifecycle.RUNNING, TaskSource.REMOTE_MCP, null, null, 1L)
        assertNull(TaskLifecycleReducer.terminal(previous, remote(), AgentState.Idle(), 2L))
    }

    @Test
    fun userStoppedSessionEndsAsStoppedAndClientEndAsCompleted() {
        val previous = TaskSnapshot(TaskLifecycle.RUNNING, TaskSource.REMOTE_MCP, null, null, 1L)
        val stopped = remote(end = SessionEnd(SessionEndReason.Stopped, 2L))
        val done = remote(end = SessionEnd(SessionEndReason.Completed, 2L))
        assertEquals(TaskLifecycle.STOPPED, TaskLifecycleReducer.terminal(previous, stopped, null, 3L)?.lifecycle)
        assertEquals(TaskLifecycle.COMPLETED, TaskLifecycleReducer.terminal(previous, done, null, 3L)?.lifecycle)
    }

    @Test
    fun nativeOutcomeMapsToTerminalLifecycle() {
        val previous = TaskSnapshot(TaskLifecycle.RUNNING, TaskSource.NATIVE_AGENT, null, null, 1L)
        fun after(o: TurnOutcome) =
            TaskLifecycleReducer.terminal(previous, remote(), AgentState.Idle(lastOutcome = o), 2L)?.lifecycle
        assertEquals(TaskLifecycle.FAILED, after(TurnOutcome.Failed))
        assertEquals(TaskLifecycle.STOPPED, after(TurnOutcome.Interrupted))
        assertEquals(TaskLifecycle.COMPLETED, after(TurnOutcome.Completed))
    }
}
