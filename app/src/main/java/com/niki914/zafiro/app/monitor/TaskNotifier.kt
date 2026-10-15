package com.niki914.zafiro.app.monitor

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.niki914.logging.Logger
import com.niki914.zafiro.app.R
import com.niki914.zafiro.business.notification.AppNotificationChannel
import com.niki914.zafiro.business.notification.NotificationChannelManager
import com.niki914.zafiro.mcp.service.RemoteOperationTracker
import com.niki914.zafiro.mcp.service.TaskLifecycle
import com.niki914.zafiro.mcp.service.TaskSnapshot
import com.niki914.zafiro.mcp.service.TaskSource
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 任务进行期间的持续通知。关闭观察界面不会移除它，任务结束才更新为结局并自动消失。
 *
 * Live Updates（promoted ongoing）只在「用户发起、有明确起止、需要持续关注」时申请：原生 Agent 回合，
 * 或外部客户端用 phone_operation_start 显式开启的操作会话。零散的工具调用只给标准持续通知。
 * 是否真的被系统提升由 ROM 决定，这里只记录系统给出的事实。
 */
object TaskNotifier {
    private const val TAG = "niki914_zafiro_TaskNotifier"
    const val NOTIFICATION_ID = 1101
    const val ACTION_STOP_TASK = "com.niki914.zafiro.action.TASK_STOP"
    private const val REQUEST_CONTENT = 111
    private const val REQUEST_STOP = 112
    private const val FINAL_TIMEOUT_MS = 10_000L
    private const val CHIP_TEXT = "AI"

    /** Notification.Builder#setShortCriticalText 写入的 extras 键；compat 版本尚无对应 API。 */
    private const val SHORT_CRITICAL_TEXT_KEY = "android.shortCriticalText"

    private data class Key(
        val lifecycle: TaskLifecycle,
        val detail: String?,
        val tool: String?,
        val promoted: Boolean,
    )

    fun start(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch {
            TaskMonitor.snapshot
                .map { Key(it.lifecycle, it.detail, it.lastCall?.toolName, wantsPromotion(it)) to it }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (_, snapshot) -> render(app, snapshot) }
        }
    }

    private fun wantsPromotion(s: TaskSnapshot): Boolean {
        if (s.lifecycle != TaskLifecycle.RUNNING && s.lifecycle != TaskLifecycle.PAUSED) return false
        return when (s.source) {
            TaskSource.NATIVE_AGENT -> true
            TaskSource.REMOTE_MCP -> RemoteOperationTracker.sessionActive.value
            null -> false
        }
    }

    private suspend fun render(context: Context, snapshot: TaskSnapshot) {
        val manager = NotificationManagerCompat.from(context)
        if (snapshot.lifecycle == TaskLifecycle.IDLE) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val running = snapshot.lifecycle == TaskLifecycle.RUNNING || snapshot.lifecycle == TaskLifecycle.PAUSED
        val promoted = wantsPromotion(snapshot)
        val stateText = context.getString(stateRes(snapshot.lifecycle))
        val text = snapshot.detail
            ?: if (running && snapshot.source == TaskSource.REMOTE_MCP && snapshot.lastCall == null) {
                context.getString(R.string.task_monitor_waiting_next)
            } else {
                snapshot.lastCall?.let { context.getString(R.string.task_monitor_last_call, it.toolName) }
            }
        val content = listOfNotNull(stateText, text).joinToString(" · ")

        val posted = runCatching {
            requireService<NotificationChannelManager>().post(AppNotificationChannel.Resident, NOTIFICATION_ID) {
                setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
                setContentTitle(context.getString(R.string.task_monitor_title))
                setContentText(content)
                setContentIntent(contentIntent(context))
                setCategory(NotificationCompat.CATEGORY_PROGRESS)
                setOnlyAlertOnce(true)
                setOngoing(running)
                setAutoCancel(!running)
                if (running) {
                    addAction(0, context.getString(R.string.task_monitor_stop_task), stopIntent(context))
                } else {
                    setTimeoutAfter(FINAL_TIMEOUT_MS)
                }
                if (promoted && Build.VERSION.SDK_INT >= 36) {
                    addExtras(
                        Bundle().apply {
                            putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true)
                            putString(SHORT_CRITICAL_TEXT_KEY, CHIP_TEXT)
                        }
                    )
                }
            }
        }.getOrElse {
            Logger.w(TAG, "post failed: ${it.message}")
            false
        }
        if (posted) logPromotion(context, promoted)
    }

    /** 只记录系统的真实判定；API 36 之前没有这个概念。 */
    private fun logPromotion(context: Context, requested: Boolean) {
        if (Build.VERSION.SDK_INT < 36) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val active = nm.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID }?.notification
        val flag = active != null && (active.flags and Notification.FLAG_PROMOTED_ONGOING) != 0
        Logger.i(
            TAG,
            "live-update requested=$requested canPost=${nm.canPostPromotedNotifications()} " +
                    "promotable=${active?.hasPromotableCharacteristics()} promotedFlag=$flag",
        )
    }

    private fun stateRes(l: TaskLifecycle): Int = when (l) {
        TaskLifecycle.IDLE -> R.string.task_monitor_state_idle
        TaskLifecycle.RUNNING -> R.string.task_monitor_state_running
        TaskLifecycle.PAUSED -> R.string.task_monitor_state_paused
        TaskLifecycle.COMPLETED -> R.string.task_monitor_state_completed
        TaskLifecycle.FAILED -> R.string.task_monitor_state_failed
        TaskLifecycle.STOPPED -> R.string.task_monitor_state_stopped
    }

    private fun contentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            TaskMonitorActivity.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun stopIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_STOP,
            Intent(context, TaskActionReceiver::class.java).setAction(ACTION_STOP_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** 通知上的「停止任务」。 */
class TaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TaskNotifier.ACTION_STOP_TASK) TaskMonitor.stopTask()
    }
}
