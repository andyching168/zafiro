package com.niki914.zafiro.runtime.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.niki914.logging.Logger
import com.niki914.zafiro.api.GatewayDeviceService
import com.niki914.zafiro.api.GatewayStatus
import com.niki914.zafiro.app.MainActivity
import com.niki914.zafiro.app.R
import com.niki914.zafiro.business.notification.AppNotificationChannel
import com.niki914.zafiro.business.notification.NotificationChannelManager
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 远程设备连接的前台壳：只负责「进程被系统视为前台」、状态通知与停止入口，
 * 连接本身由 [GatewayDeviceService] 持有。用户在设置里启用才会被拉起，停止即关闭开关。
 * 不持有 WakeLock；断网/切网由 [ConnectivityManager] 回调触发立即重连，Doze 下依赖系统放行的网络窗口。
 */
class GatewayConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val channelManager: NotificationChannelManager get() = requireService()
    private val gateway: GatewayDeviceService get() = requireService()

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat(buildNotification(GatewayStatus.Connecting))
        statusJob = scope.launch {
            gateway.status.collect { status ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(status))
            }
        }
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Logger.i(LOG_TAG, "stop requested from notification")
            scope.launch { XRepo.gateway.update { it.copy(enabled = false) } }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        networkCallback?.let {
            runCatching {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
        statusJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                gateway.onNetworkAvailable()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Logger.w(LOG_TAG, "network callback registration failed: ${it.message}") }
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(status: GatewayStatus): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, GatewayConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val statusText = when (status) {
            GatewayStatus.Disconnected -> R.string.gateway_status_disconnected
            GatewayStatus.Connecting -> R.string.gateway_status_connecting
            is GatewayStatus.Connected -> R.string.gateway_status_connected
            is GatewayStatus.Reconnecting -> R.string.gateway_status_reconnecting
            is GatewayStatus.Error -> R.string.gateway_status_error
        }
        return channelManager.buildNotification(AppNotificationChannel.Resident) {
            setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
            setContentTitle(getString(R.string.gateway_notification_title))
            setContentText("${getString(statusText)} · ${getString(R.string.gateway_notification_text)}")
            setContentIntent(contentIntent)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            addAction(0, getString(R.string.gateway_notification_action_stop), stopIntent)
        }
    }

    companion object {
        private const val LOG_TAG = "niki914_zafiro_GatewayConnectionService"
        private const val NOTIFICATION_ID = 1003
        private const val ACTION_STOP = "com.niki914.zafiro.action.GATEWAY_STOP"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, GatewayConnectionService::class.java)
                )
            } catch (e: Throwable) {
                Logger.e(LOG_TAG, "failed to start foreground service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayConnectionService::class.java))
        }
    }
}
