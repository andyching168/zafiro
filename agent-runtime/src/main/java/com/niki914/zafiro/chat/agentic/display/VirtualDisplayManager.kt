package com.niki914.zafiro.chat.agentic.display

import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.api.DisplayFrame
import com.niki914.zafiro.api.ExecutionEnvironment
import com.niki914.zafiro.api.VirtualDisplayInfo
import com.niki914.zafiro.api.VirtualDisplayService
import com.niki914.zafiro.api.VirtualDisplayState
import com.niki914.zafiro.chat.agentic.AgentTenantContext
import com.niki914.zafiro.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 把 shell 身份辅助进程拉起来，并告诉它回连的端口与口令。默认实现走 root，其次 Shizuku。 */
fun interface HelperLauncher {
    suspend fun launch(port: Int, token: String): Result<Unit>
}

/**
 * 背景虚拟屏管理器。
 *
 * 持有一个到 shell 辅助进程的回环连接（App 监听、辅助进程回连，与已在 HyperOS 实测的 PoC 同向）。
 * 辅助进程拥有真正的 VirtualDisplay；连接一断它就释放全部虚拟屏并退出，所以 App 崩溃不会留下孤儿屏。
 * 最后一块虚拟屏被销毁时辅助进程也会被一并停掉。
 */
class VirtualDisplayManager(
    private val launcher: HelperLauncher = ShellHelperLauncher(),
    private val defaultMetrics: suspend () -> Triple<Int, Int, Int> = ::physicalMetrics,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : VirtualDisplayService {

    companion object {
        private const val LOG_TAG = "niki914_zafiro_VirtualDisplayManager"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 20_000L
        private const val LAUNCH_TIMEOUT_MS = 30_000L
        private const val MAX_FRAME_B64_CHARS = 24 * 1024 * 1024

        private suspend fun physicalMetrics(): Triple<Int, Int, Int> {
            val dm = ContextProvider.await().resources.displayMetrics
            return Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private val lifecycle = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private val writeLock = Any()
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    private val _state = MutableStateFlow<VirtualDisplayState>(VirtualDisplayState.Stopped)
    override val state: StateFlow<VirtualDisplayState> = _state.asStateFlow()

    private val _environment = MutableStateFlow(ExecutionEnvironment.Physical)
    override val environment: StateFlow<ExecutionEnvironment> = _environment.asStateFlow()

    private val infos = ConcurrentHashMap<Int, VirtualDisplayInfo>()

    override fun setEnvironment(environment: ExecutionEnvironment) {
        _environment.value = environment
    }

    override fun displays(): List<VirtualDisplayInfo> = infos.values.sortedBy { it.displayId }

    private fun publish() {
        val current = _state.value
        if (current is VirtualDisplayState.Running || current is VirtualDisplayState.Starting) {
            _state.value = if (infos.isEmpty() && current is VirtualDisplayState.Running) {
                VirtualDisplayState.Running(emptyList())
            } else {
                VirtualDisplayState.Running(displays())
            }
        }
    }

    override suspend fun create(width: Int, height: Int, dpi: Int): Result<VirtualDisplayInfo> =
        lifecycle.withLock {
            try {
                ensureHelper()
                val (dw, dh, ddpi) = defaultMetrics()
                val w = if (width > 0) width else dw
                val h = if (height > 0) height else dh
                val d = if (dpi > 0) dpi else ddpi
                val reply = request(
                    buildJsonObject {
                        put("cmd", "create")
                        put("width", w)
                        put("height", h)
                        put("dpi", d)
                        put("name", "Zafiro Background")
                    }
                )
                val id = reply["display"]?.asPrim()?.intOrNull
                    ?: error("helper returned no display id")
                if (id <= 0) error("helper returned invalid display id $id")
                val info = VirtualDisplayInfo(
                    displayId = id,
                    width = reply["width"]?.asPrim()?.intOrNull ?: w,
                    height = reply["height"]?.asPrim()?.intOrNull ?: h,
                    dpi = reply["dpi"]?.asPrim()?.intOrNull ?: d,
                )
                infos[id] = info
                _state.value = VirtualDisplayState.Running(displays())
                Logger.i(LOG_TAG, "virtual display created id=$id ${info.width}x${info.height}/${info.dpi}")
                Result.success(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(LOG_TAG, "create failed: ${e.message}")
                if (infos.isEmpty()) teardown(e.message ?: "Failed to create virtual display")
                Result.failure(e)
            }
        }

    override suspend fun destroy(displayId: Int): Result<Unit> = lifecycle.withLock {
        if (!infos.containsKey(displayId)) {
            return@withLock Result.failure(IllegalArgumentException("display $displayId is not managed"))
        }
        try {
            request(buildJsonObject {
                put("cmd", "destroy")
                put("display", displayId)
            })
            infos.remove(displayId)
            if (infos.isEmpty()) {
                // 最后一块屏没了就把辅助进程也停掉，不留后台进程
                runCatching { request(buildJsonObject { put("cmd", "stop") }, 3_000) }
                teardown(null)
            } else {
                _state.value = VirtualDisplayState.Running(displays())
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun destroyAll() {
        lifecycle.withLock {
            if (socket != null) {
                runCatching { request(buildJsonObject { put("cmd", "stop") }, 3_000) }
            }
            teardown(null)
        }
    }

    override suspend fun capture(
        displayId: Int,
        jpeg: Boolean,
        quality: Int,
        maxSide: Int,
    ): Result<DisplayFrame> {
        if (!infos.containsKey(displayId)) {
            return Result.failure(IllegalArgumentException("display $displayId is not managed"))
        }
        return try {
            val reply = request(buildJsonObject {
                put("cmd", "screenshot")
                put("display", displayId)
                put("format", if (jpeg) "jpeg" else "png")
                put("quality", quality.coerceIn(10, 100))
                put("max_side", maxSide.coerceAtLeast(0))
            })
            val data = reply["data"]?.asPrim()?.contentOrNull ?: error("helper returned no frame")
            if (data.length > MAX_FRAME_B64_CHARS) error("frame too large")
            Result.success(
                DisplayFrame(
                    bytes = Base64.getDecoder().decode(data),
                    mimeType = reply["mime"]?.asPrim()?.contentOrNull ?: "image/png",
                    width = reply["width"]?.asPrim()?.intOrNull ?: 0,
                    height = reply["height"]?.asPrim()?.intOrNull ?: 0,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun launch(displayId: Int, component: String): Result<Unit> {
        if (!infos.containsKey(displayId)) {
            return Result.failure(IllegalArgumentException("display $displayId is not managed"))
        }
        return try {
            request(
                buildJsonObject {
                    put("cmd", "launch")
                    put("display", displayId)
                    put("component", component)
                },
                LAUNCH_TIMEOUT_MS,
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- helper process + wire ----

    private suspend fun ensureHelper() {
        if (socket?.isClosed == false && readerJob?.isActive == true) return
        teardown(null)
        _state.value = VirtualDisplayState.Starting

        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        try {
            launcher.launch(server.localPort, token).getOrThrow()
            val accepted = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { server.accept() }
            } ?: error("virtual display helper did not connect within ${CONNECT_TIMEOUT_MS / 1000}s")
            val reader = BufferedReader(InputStreamReader(accepted.getInputStream(), Charsets.UTF_8))
            val hello = withTimeout(CONNECT_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { reader.readLine() }
            } ?: error("helper closed the connection during handshake")
            val helloObj = json.parseToJsonElement(hello).jsonObject
            if (helloObj["token"]?.asPrim()?.contentOrNull != token) {
                accepted.close()
                error("virtual display helper failed the handshake")
            }
            accepted.tcpNoDelay = true
            socket = accepted
            writer = BufferedWriter(OutputStreamWriter(accepted.getOutputStream(), Charsets.UTF_8))
            readerJob = scope.launch { readLoop(reader, accepted) }
            Logger.i(LOG_TAG, "helper connected uid=${helloObj["uid"]} sdk=${helloObj["sdk"]}")
        } finally {
            runCatching { server.close() }
        }
    }

    private suspend fun readLoop(reader: BufferedReader, owner: Socket) {
        try {
            while (true) {
                val line = reader.readLine() ?: break
                val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                val id = obj["id"]?.asPrim()?.intOrNull ?: continue
                pending.remove(id)?.complete(obj)
            }
        } catch (_: Exception) {
            // connection broken
        }
        // 辅助进程没了：它持有的虚拟屏随之消失
        if (socket === owner) {
            Logger.w(LOG_TAG, "helper connection lost; virtual displays are gone")
            pending.values.forEach { it.completeExceptionally(IllegalStateException("helper connection lost")) }
            pending.clear()
            infos.clear()
            socket = null
            writer = null
            _state.value = VirtualDisplayState.Error("Virtual display helper stopped")
        }
    }

    private suspend fun request(cmd: JsonObject, timeoutMs: Long = REQUEST_TIMEOUT_MS): JsonObject {
        val w = writer ?: error("virtual display helper is not running")
        val id = nextId.getAndIncrement()
        val waiter = CompletableDeferred<JsonObject>()
        pending[id] = waiter
        val payload = JsonObject(cmd + ("id" to JsonPrimitive(id))).toString()
        try {
            withContext(Dispatchers.IO) {
                synchronized(writeLock) {
                    w.write(payload)
                    w.write("\n")
                    w.flush()
                }
            }
            val reply = withTimeout(timeoutMs) { waiter.await() }
            if (reply["ok"]?.asPrim()?.booleanOrNull != true) {
                error(reply["error"]?.asPrim()?.contentOrNull ?: "helper command failed")
            }
            return reply
        } finally {
            pending.remove(id)
        }
    }

    private fun teardown(errorMessage: String?) {
        runCatching { socket?.close() }
        socket = null
        writer = null
        readerJob?.cancel()
        readerJob = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        infos.clear()
        _state.value = if (errorMessage != null) VirtualDisplayState.Error(errorMessage)
        else VirtualDisplayState.Stopped
    }

    private fun kotlinx.serialization.json.JsonElement.asPrim(): JsonPrimitive? = this as? JsonPrimitive
}

/**
 * 经统一的终端会话池（root，其次 Shizuku）以 shell 身份拉起辅助进程：
 * `CLASSPATH=<Zafiro APK> app_process / <ServerClass> ...`，后台运行，由回连的 socket 控制其生命周期。
 */
class ShellHelperLauncher(
    private val apkPath: suspend () -> String = {
        ContextProvider.await().applicationInfo.sourceDir
    },
) : HelperLauncher {

    private companion object {
        private const val LOG_TAG = "niki914_zafiro_ShellHelperLauncher"
        private const val SERVER_CLASS = "com.niki914.zafiro.vdisplay.server.VirtualDisplayServer"
        private const val TENANT = "virtual-display"
        private const val COMMAND_TIMEOUT_MS = 10_000L
    }

    override suspend fun launch(port: Int, token: String): Result<Unit> = withContext(AgentTenantContext(TENANT)) {
        val apk = apkPath()
        require(apk.matches(Regex("[A-Za-z0-9._/=@+~-]+"))) { "unexpected apk path" }
        val command = "CLASSPATH=$apk nohup app_process / $SERVER_CLASS port=$port token=$token " +
                ">/dev/null 2>&1 &"
        var lastError = "no root or Shizuku shell is available"
        for (identity in listOf("root", "shizuku")) {
            val session = (TerminalSessionPool.open(identity) as? TerminalOpenOutcome.Success)?.session
                ?: continue
            when (val outcome = TerminalSessionPool.executeBlocking(session, command, COMMAND_TIMEOUT_MS)) {
                is TerminalCommandOutcome.Success -> {
                    Logger.i(LOG_TAG, "helper launched via $identity")
                    return@withContext Result.success(Unit)
                }

                else -> {
                    lastError = "helper launch via $identity did not complete: ${outcome::class.simpleName}"
                    Logger.w(LOG_TAG, lastError)
                }
            }
        }
        Result.failure(IllegalStateException(lastError))
    }
}
