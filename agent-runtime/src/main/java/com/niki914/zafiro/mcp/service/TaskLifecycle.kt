package com.niki914.zafiro.mcp.service

import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.TurnOutcome

enum class TaskLifecycle { IDLE, RUNNING, PAUSED, COMPLETED, FAILED, STOPPED }

enum class TaskSource {
    /** 应用内 Agent 回合：有真正的回合生命周期与取消机制。 */
    NATIVE_AGENT,

    /** 外部 MCP（HTTP 或 Gateway）：只有工具活动与可选的操作会话，没有「任务」本体。 */
    REMOTE_MCP,
}

enum class CallState { Running, Ok, Failed, Aborted }

/** 最近一次屏幕/手机工具调用。 */
data class ToolCallRecord(
    val toolName: String,
    val displayId: Int?,
    val state: CallState,
    val at: Long,
)

enum class SessionEndReason { Completed, Stopped }

data class SessionEnd(val reason: SessionEndReason, val at: Long)

/** [RemoteOperationTracker] 在某一刻的可观察事实。 */
data class RemoteTaskInput(
    val engaged: Boolean,
    val sessionActive: Boolean,
    val lastCall: ToolCallRecord?,
    val sessionEnd: SessionEnd?,
)

data class TaskSnapshot(
    val lifecycle: TaskLifecycle,
    val source: TaskSource?,
    /** 面向人的当前状态：正在执行的工具、等待下一步指令，或结局。不编造进度。 */
    val detail: String?,
    val lastCall: ToolCallRecord?,
    val at: Long,
) {
    /** 「停止任务」只在确有可停的东西时给出。 */
    val canStop: Boolean get() = lifecycle == TaskLifecycle.RUNNING || lifecycle == TaskLifecycle.PAUSED

    companion object {
        val Idle = TaskSnapshot(TaskLifecycle.IDLE, null, null, null, 0L)
    }
}

object TaskLifecycleReducer {

    /** 此刻正在进行的任务；没有则 null。原生回合优先于远端活动。 */
    fun running(remote: RemoteTaskInput, native: AgentState?, now: Long): TaskSnapshot? {
        if (native != null && native !is AgentState.Idle) {
            val paused = native is AgentState.WaitingApproval
            return TaskSnapshot(
                lifecycle = if (paused) TaskLifecycle.PAUSED else TaskLifecycle.RUNNING,
                source = TaskSource.NATIVE_AGENT,
                detail = when (native) {
                    is AgentState.ToolRunning -> native.label
                    is AgentState.WaitingApproval -> null
                    else -> null
                },
                lastCall = remote.lastCall,
                at = now,
            )
        }
        if (remote.engaged) {
            val call = remote.lastCall
            val inFlight = call?.state == CallState.Running
            return TaskSnapshot(
                lifecycle = TaskLifecycle.RUNNING,
                source = TaskSource.REMOTE_MCP,
                detail = if (inFlight) call?.toolName else null,
                lastCall = call,
                at = now,
            )
        }
        return null
    }

    /**
     * 任务从运行变为不运行时的结局。没有显式会话的零散远端工具调用没有「任务」可言，
     * 返回 null，由调用方回到 IDLE，而不是谎称「已完成」。
     */
    fun terminal(previous: TaskSnapshot, remote: RemoteTaskInput, native: AgentState?, now: Long): TaskSnapshot? {
        val lifecycle = when (previous.source) {
            TaskSource.NATIVE_AGENT -> when ((native as? AgentState.Idle)?.lastOutcome) {
                TurnOutcome.Completed -> TaskLifecycle.COMPLETED
                TurnOutcome.Failed -> TaskLifecycle.FAILED
                TurnOutcome.Interrupted -> TaskLifecycle.STOPPED
                null -> return null
            }

            TaskSource.REMOTE_MCP -> when (remote.sessionEnd?.reason) {
                SessionEndReason.Completed -> TaskLifecycle.COMPLETED
                SessionEndReason.Stopped -> TaskLifecycle.STOPPED
                null -> return null
            }

            null -> return null
        }
        return TaskSnapshot(lifecycle, previous.source, null, previous.lastCall ?: remote.lastCall, now)
    }
}
