package com.niki914.zafiro.app.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.niki914.logging.Logger
import com.niki914.zafiro.mcp.service.RemoteOperationTracker
import com.niki914.zafiro.mcp.service.RemoteTouchLock
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 外部 MCP 操作会话期间的触控锁编排：会话进行中、用户启用该选项，且 AI 最近确实在操作实体屏时才上锁
 * （全程在背景虚拟屏上操作则从不上锁），会话结束/停止/熄屏时放行。停止入口：音量下键（无障碍按键过滤）、电源键（熄屏）、
 * 客户端调用 phone_operation_end。
 */
object RemoteOperationController {
    private const val TAG = "niki914_zafiro_RemoteOperationController"
    private const val POLL_MS = 250L

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    stopFromUser("screen off")
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
        )

        scope.launch {
            // 上次进程若在锁定中被杀，先放行一次（root 侧计时本身也会放行）
            var maybeLocked = runCatching { XRepo.gateway.get().lockTouchDuringOperation }
                .getOrDefault(false)
            combine(
                RemoteOperationTracker.sessionActive,
                XRepo.gateway.configFlow.map { it.lockTouchDuringOperation },
            ) { session, enabled -> session && enabled }
                .distinctUntilChanged()
                .collectLatest { lockOn ->
                    if (lockOn) {
                        maybeLocked = true
                        var locked = false
                        var lastLockAt = 0L
                        try {
                            while (true) {
                                val now = System.currentTimeMillis()
                                if (RemoteOperationTracker.physicalTouchDemand(now)) {
                                    // 上锁，并按心跳续期 root 侧的放行计时
                                    if (!locked || now - lastLockAt >= RemoteTouchLock.HEARTBEAT_MS) {
                                        locked = RemoteTouchLock.lock()
                                        lastLockAt = now
                                        RemoteOperationTracker.setTouchLocked(locked)
                                        if (!locked) {
                                            Logger.w(TAG, "touch lock unavailable (need root + touchscreen node)")
                                        }
                                    }
                                } else if (locked) {
                                    // 实体屏操作告一段落（例如 AI 转去虚拟屏）：把屏幕还给用户
                                    RemoteTouchLock.unlock()
                                    locked = false
                                    RemoteOperationTracker.setTouchLocked(false)
                                }
                                delay(POLL_MS)
                            }
                        } finally {
                            RemoteOperationTracker.setTouchLocked(false)
                        }
                    } else if (maybeLocked) {
                        maybeLocked = false
                        RemoteTouchLock.unlock()
                    }
                }
        }
    }

    /** 用户的物理停止动作；只在显式会话进行中才有意义。 */
    fun stopFromUser(source: String) {
        if (!RemoteOperationTracker.sessionActive.value) return
        Logger.i(TAG, "user stop via $source")
        RemoteOperationTracker.requestStop()
    }
}
