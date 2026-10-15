package com.niki914.zafiro.api

import kotlinx.coroutines.flow.StateFlow

sealed interface GatewayStatus {
    object Disconnected : GatewayStatus
    object Connecting : GatewayStatus
    data class Connected(val sessionId: String) : GatewayStatus
    data class Reconnecting(val attempt: Int, val delayMs: Long) : GatewayStatus

    /** 不可自动恢复的失败（鉴权被拒、设备被撤销、配置无效等）：停止重试，等用户处理。 */
    data class Error(val message: String) : GatewayStatus
}

/**
 * 以设备身份主动连入 SelfHostDesktopMCP Gateway（WSS 出站），把本机工具暴露给远端 MCP 客户端。
 * 与 [McpService]（本机 HTTP 端点）相互独立。
 */
interface GatewayDeviceService {
    val status: StateFlow<GatewayStatus>

    /** 按当前配置建立连接；已在运行则不重复建立会话。 */
    suspend fun start()
    suspend fun stop()

    /** 丢弃当前会话并立即重连（重置退避）。 */
    suspend fun reconnect()

    /** 网络恢复等外部信号：非 Connected 时立即重试，已连接则忽略。 */
    fun onNetworkAvailable()
}
