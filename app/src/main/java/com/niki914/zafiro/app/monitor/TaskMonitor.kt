package com.niki914.zafiro.app.monitor

import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.VirtualDisplayService
import com.niki914.zafiro.mcp.service.RemoteOperationTracker
import com.niki914.zafiro.mcp.service.RemoteTaskInput
import com.niki914.zafiro.mcp.service.TaskLifecycleReducer
import com.niki914.zafiro.mcp.service.TaskSnapshot
import com.niki914.zafiro.mcp.service.TaskSource
import com.niki914.zafiro.service.findService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 观察界面与通知共用的任务状态。任务生命周期与「观察」无关：关闭观察界面不影响它。
 * 状态只来自两处真实信号：原生 Agent 回合（[AgentControl.status]）与外部 MCP 的工具活动/操作会话
 * （[RemoteOperationTracker]）；有虚拟屏本身不意味着有任务在跑。
 */
object TaskMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _snapshot = MutableStateFlow(TaskSnapshot.Idle)
    val snapshot: StateFlow<TaskSnapshot> = _snapshot.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val nativeState = findService<AgentControl>()?.status ?: flowOf(null)
            var previousRunning: TaskSnapshot? = null
            combine(
                RemoteOperationTracker.engaged,
                RemoteOperationTracker.sessionActive,
                RemoteOperationTracker.lastCall,
                RemoteOperationTracker.sessionEnd,
                nativeState,
            ) { engaged, session, call, end, native ->
                RemoteTaskInput(engaged, session, call, end) to native
            }.collect { (remote, native) ->
                val now = System.currentTimeMillis()
                val running = TaskLifecycleReducer.running(remote, native, now)
                when {
                    running != null -> {
                        previousRunning = running
                        _snapshot.value = running
                    }

                    previousRunning != null -> {
                        _snapshot.value = TaskLifecycleReducer.terminal(previousRunning!!, remote, native, now)
                            ?: TaskSnapshot.Idle
                        previousRunning = null
                    }
                }
            }
        }
    }

    /**
     * 停止当前任务。原生回合：取消回合。外部 MCP：取消在途工具，并让对方的下一个请求失败一次
     * （这是它唯一可用的取消机制，不是终止远端 Agent 本身）。
     */
    fun stopTask() {
        when (_snapshot.value.source) {
            TaskSource.NATIVE_AGENT -> findService<AgentControl>()?.stop()
            TaskSource.REMOTE_MCP -> RemoteOperationTracker.requestStop()
            null -> Unit
        }
    }

    /** 观察哪块屏：AI 最近操作的受管虚拟屏，没有就取第一块。 */
    fun displayToWatch(): Int? {
        val service = findService<VirtualDisplayService>() ?: return null
        val last = RemoteOperationTracker.lastVirtualDisplayId.value
        if (last != null && service.managedDisplay(last) != null) return last
        return service.displays().firstOrNull()?.displayId
    }
}
