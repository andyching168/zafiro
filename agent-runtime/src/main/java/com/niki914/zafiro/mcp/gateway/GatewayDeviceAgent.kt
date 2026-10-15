package com.niki914.zafiro.mcp.gateway

import com.niki914.logging.Logger
import com.niki914.zafiro.api.GatewayDeviceService
import com.niki914.zafiro.api.GatewayStatus
import com.niki914.zafiro.settings.model.RuntimeGatewayConfig
import com.niki914.zafiro.settings.model.RuntimeMcpServiceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

data class GatewayAgentOptions(
    val platform: String = "android",
    val hostname: String = "android",
    val pingIntervalMs: Long = 20_000,
    val handshakeTimeoutMs: Long = 15_000,
    val livenessTimeoutMs: Long = 50_000,
    val initialBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 30_000,
    val defaultCallTimeoutMs: Long = 60_000,
    val maxCallTimeoutMs: Long = 300_000,
    val maxInFlight: Int = 4,
)

/**
 * Android 原生 Device Agent：出站 WSS 连 Gateway，处理 hello / ping / request。
 * 工具清单与执行全部委托给 [GatewayToolBridge]（与 HTTP MCP 同源）。
 *
 * 生命周期：[start] 只会起一个连接循环（重复调用被忽略）；[stop] 取消循环并关闭 socket，
 * 在途工具调用随会话作用域一并取消。
 */
class GatewayDeviceAgent(
    private val bridge: GatewayToolBridge,
    private val configProvider: suspend () -> RuntimeGatewayConfig,
    private val client: OkHttpClient = defaultClient(),
    private val options: GatewayAgentOptions = GatewayAgentOptions(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : GatewayDeviceService {

    companion object {
        private const val LOG_TAG = "niki914_zafiro_GatewayDeviceAgent"
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        /** 通道必须是 wss；ws 只放行回环/模拟器宿主，便于本地调试。 */
        internal fun toHttpUrl(raw: String): okhttp3.HttpUrl? {
            val trimmed = raw.trim()
            val converted = when {
                trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
                trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
                else -> return null
            }
            val parsed = converted.toHttpUrlOrNull() ?: return null
            // 只填了主机（配对时就是这样得到的基址）时补上网关固定的 /device 路径
            val url = if (parsed.encodedPath == "/") parsed.newBuilder().encodedPath("/device").build() else parsed
            if (!url.isHttps && url.host !in LOOPBACK_HOSTS) return null
            return url
        }
    }

    private sealed interface Outcome {
        /** 值得退避重试的断线。[immediate] 为用户/网络触发，跳过退避。 */
        data class Retry(val reason: String, val immediate: Boolean = false, val wasConnected: Boolean = false) : Outcome
        data class Fatal(val message: String) : Outcome
    }

    private val lifecycle = Mutex()
    private var loopJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var activeOutcome: CompletableDeferred<Outcome>? = null

    private val _status = MutableStateFlow<GatewayStatus>(GatewayStatus.Disconnected)
    override val status: StateFlow<GatewayStatus> = _status.asStateFlow()

    override suspend fun start() {
        lifecycle.withLock {
            if (loopJob?.isActive == true) {
                Logger.i(LOG_TAG, "start ignored: connection loop already running")
                return
            }
            _status.value = GatewayStatus.Connecting
            loopJob = scope.launch { runLoop() }
        }
    }

    override suspend fun stop() {
        lifecycle.withLock {
            val job = loopJob
            loopJob = null
            job?.cancelAndJoinQuietly()
            _status.value = GatewayStatus.Disconnected
        }
    }

    override suspend fun reconnect() {
        lifecycle.withLock {
            val running = loopJob?.isActive == true
            if (!running) {
                _status.value = GatewayStatus.Connecting
                loopJob = scope.launch { runLoop() }
                return
            }
            activeOutcome?.complete(Outcome.Retry("manual reconnect", immediate = true))
            wake.trySend(Unit)
        }
    }

    override fun onNetworkAvailable() {
        if (loopJob?.isActive != true) return
        if (_status.value is GatewayStatus.Connected) return
        wake.trySend(Unit)
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        cancel()
        try {
            join()
        } catch (_: CancellationException) {
        }
    }

    private suspend fun runLoop() {
        var attempt = 0
        var backoff = options.initialBackoffMs
        while (currentCoroutineContext().isActive) {
            val config = configProvider()
            val invalid = validate(config)
            if (invalid != null) {
                _status.value = GatewayStatus.Error(invalid)
                return
            }
            _status.value = if (attempt == 0) GatewayStatus.Connecting
            else GatewayStatus.Reconnecting(attempt, 0)
            val outcome = connectOnce(config)
            when (outcome) {
                is Outcome.Fatal -> {
                    Logger.w(LOG_TAG, "gateway connection stopped: ${outcome.message}")
                    _status.value = GatewayStatus.Error(outcome.message)
                    return
                }

                is Outcome.Retry -> {
                    if (outcome.wasConnected || outcome.immediate) {
                        attempt = 0
                        backoff = options.initialBackoffMs
                    }
                    attempt++
                    val wait = if (outcome.immediate) 0L else jitter(backoff)
                    Logger.i(LOG_TAG, "disconnected (${outcome.reason}); retry in ${wait}ms attempt=$attempt")
                    _status.value = GatewayStatus.Reconnecting(attempt, wait)
                    if (wait > 0) {
                        withTimeoutOrNull(wait) { wake.receive() }
                    }
                    backoff = (backoff * 2).coerceAtMost(options.maxBackoffMs)
                }
            }
        }
    }

    private fun jitter(ms: Long): Long = (ms * (0.8 + Random.nextDouble() * 0.4)).toLong()

    private fun validate(config: RuntimeGatewayConfig): String? {
        if (toHttpUrl(config.url) == null) return "Gateway URL must be wss://…/device"
        if (config.deviceId.isBlank()) return "Device ID is required"
        if (config.deviceSecret.isBlank()) return "Device secret is required: pair with the gateway or enter one"
        return null
    }

    /** 对外工具只取本机设置页为 Gateway 单独勾选的集合；与本机 HTTP MCP 的白名单互不影响。 */
    private fun effectiveToolConfig(config: RuntimeGatewayConfig) =
        RuntimeMcpServiceConfig(exposedTools = config.exposedTools)

    private suspend fun connectOnce(config: RuntimeGatewayConfig): Outcome = coroutineScope {
        val url = toHttpUrl(config.url)!!
        val outcome = CompletableDeferred<Outcome>()
        activeOutcome = outcome
        val sessionScope = CoroutineScope(SupervisorJob(coroutineContext[Job]))
        val inFlight = AtomicInteger(0)
        val lastRxAt = AtomicLong(System.currentTimeMillis())
        val ackedFlag = AtomicBoolean(false)
        var socket: WebSocket? = null

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.deviceSecret}")
            .header("X-Device-Id", config.deviceId)
            .build()

        Logger.i(LOG_TAG, "connecting to ${url.host} as ${config.deviceId}")

        fun send(text: String) {
            if (socket?.send(text) != true) Logger.w(LOG_TAG, "send dropped: socket closed or queue full")
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastRxAt.set(System.currentTimeMillis())
                sessionScope.launch {
                    val tools = bridge.listTools(effectiveToolConfig(config))
                    webSocket.send(
                        GatewayProtocol.hello(
                            deviceId = config.deviceId,
                            name = config.deviceName.ifBlank { config.deviceId },
                            platform = options.platform,
                            hostname = options.hostname,
                            tools = tools,
                        )
                    )
                    webSocket.send(GatewayProtocol.backendsStatus(tools))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastRxAt.set(System.currentTimeMillis())
                when (val msg = GatewayProtocol.parseInbound(text)) {
                    null -> Logger.w(LOG_TAG, "dropped malformed gateway message (${text.length} chars)")
                    is GatewayProtocol.Inbound.HelloAck -> {
                        ackedFlag.set(true)
                        _status.value = GatewayStatus.Connected(msg.sessionId)
                        Logger.i(LOG_TAG, "connected to gateway")
                    }

                    GatewayProtocol.Inbound.Pong, GatewayProtocol.Inbound.Unknown -> Unit
                    GatewayProtocol.Inbound.Config ->
                        Logger.i(LOG_TAG, "gateway config ignored: backends are not supported on Android")

                    is GatewayProtocol.Inbound.Request -> {
                        if (!ackedFlag.get()) return
                        handleRequest(sessionScope, msg, config, inFlight, ::send)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                outcome.complete(classifyClose(code, reason, ackedFlag.get()))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                outcome.complete(classifyClose(code, reason, ackedFlag.get()))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val http = response?.code
                outcome.complete(
                    when (http) {
                        401, 403 -> Outcome.Fatal("Gateway rejected the device credentials (HTTP $http)")
                        404 -> Outcome.Fatal("Gateway has no /device endpoint at this URL (HTTP 404)")
                        else -> Outcome.Retry(
                            reason = t.javaClass.simpleName + (http?.let { " HTTP $it" } ?: ""),
                            wasConnected = ackedFlag.get(),
                        )
                    }
                )
            }
        }

        socket = client.newWebSocket(request, listener)
        val watchdog = launch {
            val startedAt = System.currentTimeMillis()
            var lastPing = 0L
            while (isActive) {
                delay(250)
                val now = System.currentTimeMillis()
                val acked = ackedFlag.get()
                if (!acked && now - startedAt > options.handshakeTimeoutMs) {
                    outcome.complete(Outcome.Retry("handshake timeout"))
                    return@launch
                }
                if (acked && now - lastRxAt.get() > options.livenessTimeoutMs) {
                    outcome.complete(Outcome.Retry("gateway silent", wasConnected = true))
                    return@launch
                }
                if (acked && now - lastPing >= options.pingIntervalMs) {
                    lastPing = now
                    send(GatewayProtocol.ping())
                }
            }
        }
        try {
            outcome.await()
        } finally {
            watchdog.cancel()
            activeOutcome = null
            sessionScope.cancel()
            socket?.cancel()
        }
    }

    private fun classifyClose(code: Int, reason: String, wasConnected: Boolean): Outcome = when (code) {
        GatewayProtocol.CLOSE_REVOKED -> Outcome.Fatal("Device was revoked on the gateway")
        GatewayProtocol.CLOSE_BAD_HELLO -> Outcome.Fatal("Gateway rejected the handshake (protocol mismatch?)")
        GatewayProtocol.CLOSE_REPLACED -> Outcome.Fatal("Replaced by another connection using the same device ID")
        else -> Outcome.Retry("closed $code ${reason.take(40)}", wasConnected = wasConnected)
    }

    private fun handleRequest(
        sessionScope: CoroutineScope,
        req: GatewayProtocol.Inbound.Request,
        config: RuntimeGatewayConfig,
        inFlight: AtomicInteger,
        send: (String) -> Unit,
    ) {
        if (inFlight.incrementAndGet() > options.maxInFlight) {
            inFlight.decrementAndGet()
            send(GatewayProtocol.error(req.requestId, "BUSY", "too many concurrent tool calls"))
            return
        }
        sessionScope.launch {
            try {
                send(dispatch(req, config))
            } catch (e: CancellationException) {
                throw e
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private suspend fun dispatch(
        req: GatewayProtocol.Inbound.Request,
        config: RuntimeGatewayConfig,
    ): String {
        val id = req.requestId
        return try {
            val toolConfig = effectiveToolConfig(config)
            when (req.method) {
                "tools/list" -> GatewayProtocol.result(
                    id,
                    JsonObject(mapOf("tools" to bridge.listTools(toolConfig))),
                )

                "tools/call" -> {
                    val name = (req.params["name"] as? JsonPrimitive)
                        ?.takeIf { it.isString }?.contentOrNull
                        ?.takeIf { it.isNotEmpty() && it.length <= GatewayProtocol.MAX_TOOL_NAME_CHARS }
                        ?: return GatewayProtocol.error(id, "INVALID_ARGUMENT", "missing or invalid tool name")
                    val args = when (val a = req.params["arguments"]) {
                        null -> JsonObject(emptyMap())
                        is JsonObject -> a
                        else -> return GatewayProtocol.error(id, "INVALID_ARGUMENT", "arguments must be an object")
                    }
                    if (args.toString().length > GatewayProtocol.MAX_ARGUMENTS_CHARS) {
                        return GatewayProtocol.error(id, "INVALID_ARGUMENT", "arguments too large")
                    }
                    val timeoutMs = callTimeout(args)
                    val result = withTimeout(timeoutMs) { bridge.call(toolConfig, name, args) }
                    GatewayProtocol.result(id, result)
                }

                else -> GatewayProtocol.error(id, "UNSUPPORTED_METHOD", req.method.take(64))
            }
        } catch (e: GatewayToolException) {
            GatewayProtocol.error(id, e.code, e.message ?: e.code)
        } catch (e: TimeoutCancellationException) {
            GatewayProtocol.error(id, "TIMEOUT", "tool call timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(LOG_TAG, "tool dispatch failed: ${e.message}", e)
            GatewayProtocol.error(id, "TOOL_ERROR", e.message ?: "tool execution failed")
        }
    }

    private fun callTimeout(args: JsonObject): Long {
        val requested = (args["timeout_ms"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val base = if (requested != null && requested > 0) requested + 4_000 else options.defaultCallTimeoutMs
        return base.coerceIn(1_000, options.maxCallTimeoutMs)
    }
}
