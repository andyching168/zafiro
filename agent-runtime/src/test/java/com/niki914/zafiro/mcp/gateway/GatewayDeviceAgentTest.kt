package com.niki914.zafiro.mcp.gateway

import com.niki914.zafiro.api.GatewayStatus
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.util.SilentLoggerRule
import com.niki914.zafiro.mcp.service.McpToolAdapter
import com.niki914.zafiro.settings.model.RuntimeGatewayConfig
import com.niki914.zafiro.settings.model.RuntimeMcpServiceConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// 保护：Gateway 设备端的握手鉴权、白名单拒绝（shell 不得被远程执行）、超时/取消、断线重连、重复启动与畸形消息处理；
// 任何一处被改坏，远程端要么越权执行本机工具，要么连接静默失效。
class GatewayDeviceAgentTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    private class EchoTool : BuiltinTool() {
        override val name = "echo"
        override val description = "echo text"
        override val inputSchemaJson =
            """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""

        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
            val text = Json.parseToJsonElement(request.argumentsJson).jsonObject["text"]
                ?.jsonPrimitive?.contentOrNull.orEmpty()
            return BuiltinToolResult.success("echo: $text")
        }
    }

    private class SlowTool(val started: CompletableDeferred<Unit>, val cancelled: AtomicBoolean) :
        BuiltinTool() {
        override val name = "slow"
        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
            started.complete(Unit)
            try {
                delay(60_000)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled.set(true)
                throw e
            }
            return BuiltinToolResult.success("late")
        }
    }

    private class BrokenTool : BuiltinTool() {
        override val name = "broken"
        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
            throw IllegalStateException("boom")
    }

    private class DangerousTool : BuiltinTool() {
        override val name = "shell"
        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
            BuiltinToolResult.success("should never run")
    }

    private class ImageTool : BuiltinTool() {
        override val name = "echo_image"
        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
            val f = java.io.File.createTempFile("gateway-shot", ".png").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4)); deleteOnExit()
            }
            return BuiltinToolResult.success(
                "image",
                JsonObject(
                    mapOf(
                        "image" to JsonObject(
                            mapOf(
                                "path" to JsonPrimitive(f.absolutePath),
                                "mime_type" to JsonPrimitive("image/png"),
                            )
                        )
                    )
                ),
            )
        }
    }

    /** 服务端一次连接的脚本：收到的消息进队列，handler 决定如何应答。 */
    private class ServerSession(val socket: WebSocket) {
        val received = LinkedBlockingQueue<JsonObject>()
    }

    private lateinit var server: MockWebServer
    private val sessions = CopyOnWriteArrayList<ServerSession>()
    private val upgradeRequests = LinkedBlockingQueue<RecordedRequest>()
    private lateinit var scope: CoroutineScope
    private val slowStarted = CompletableDeferred<Unit>()
    private val slowCancelled = AtomicBoolean(false)

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    /** 接受一次升级；ack=true 时对 hello 回 hello_ack。 */
    private fun enqueueSocket(ack: Boolean = true) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                lateinit var session: ServerSession
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    session = ServerSession(webSocket)
                    sessions.add(session)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val obj = Json.parseToJsonElement(text).jsonObject
                    session.received.add(obj)
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "hello" -> if (ack) webSocket.send("""{"type":"hello_ack","session_id":"s-${sessions.size}"}""")
                        "ping" -> webSocket.send("""{"type":"pong"}""")
                    }
                }
            })
        )
    }

    private fun nextSession(index: Int): ServerSession {
        val deadline = System.currentTimeMillis() + 5_000
        while (sessions.size <= index) {
            check(System.currentTimeMillis() < deadline) { "server session #$index never opened" }
            Thread.sleep(20)
        }
        return sessions[index]
    }

    private fun ServerSession.next(type: String? = null): JsonObject {
        while (true) {
            val m = received.poll(5, TimeUnit.SECONDS) ?: error("no message from device")
            if (type == null || m["type"]?.jsonPrimitive?.contentOrNull == type) return m
        }
    }

    private fun ServerSession.request(id: String, method: String, params: String = "{}"): JsonObject {
        socket.send("""{"type":"request","request_id":"$id","method":"$method","params":$params}""")
        while (true) {
            val m = next("response")
            if (m["request_id"]?.jsonPrimitive?.contentOrNull == id) return m
        }
    }

    private fun newAgent(
        url: String = "ws://127.0.0.1:${server.port}/device",
        tools: List<BuiltinTool> = listOf(EchoTool(), SlowTool(slowStarted, slowCancelled), BrokenTool(), DangerousTool(), ImageTool()),
        gatewayTools: Set<String> = setOf("echo", "slow", "broken", "echo_image"),
        options: GatewayAgentOptions = GatewayAgentOptions(
            initialBackoffMs = 50, maxBackoffMs = 100, defaultCallTimeoutMs = 1_000,
        ),
    ): GatewayDeviceAgent {
        val adapter = McpToolAdapter(BuiltinToolRegistry(tools))
        val gateway = RuntimeGatewayConfig(
            enabled = true, url = url, deviceId = "pixel-1", deviceSecret = "s3cret",
            deviceName = "Pixel", exposedTools = gatewayTools,
        )
        return GatewayDeviceAgent(
            bridge = GatewayToolBridge(adapter),
            configProvider = { gateway },
            options = options,
            scope = scope,
        )
    }

    private fun GatewayDeviceAgent.awaitStatus(timeoutMs: Long = 5_000, p: (GatewayStatus) -> Boolean) {
        runBlocking {
            withTimeout(timeoutMs) {
                while (!p(status.value)) delay(20)
            }
        }
    }

    private fun setupSocket(ack: Boolean = true) {
        // 只为 MockWebServer 排队；会话对象在升级时创建
        enqueueSocket(ack)
    }

    @Test
    fun handshakeSendsBearerAndHelloThenConnects() {
        setupSocket()
        val agent = newAgent()
        runBlocking { agent.start() }
        val s = nextSession(0)
        val hello = s.next("hello")
        agent.awaitStatus { it is GatewayStatus.Connected }

        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("Bearer s3cret", req.getHeader("Authorization"))
        assertEquals("pixel-1", req.getHeader("X-Device-Id"))
        assertEquals("pixel-1", hello["device_id"]!!.jsonPrimitive.content)
        assertEquals(1, hello["protocol_version"]!!.jsonPrimitive.content.toInt())
        val names = hello["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(
            setOf("echo", "slow", "broken", "echo_image", "phone_operation_start", "phone_operation_end"),
            names.toSet(),
        )
        assertFalse("shell must not be advertised", "shell" in names)
        assertEquals(hello["tools"], hello["builtin_tools"])

        // 管理页从 desktop-commander 槽位读工具列表；缺了它页面显示 0 tools
        val status = s.next("backends_status")["backends"]!!.jsonArray.single().jsonObject
        assertEquals("desktop-commander", status["name"]!!.jsonPrimitive.content)
        assertEquals(6, status["tools"]!!.jsonArray.size)
        runBlocking { agent.stop() }
    }

    @Test
    fun toolsListCallErrorAndPolicyPaths() {
        setupSocket()
        val agent = newAgent()
        runBlocking { agent.start() }
        val s = nextSession(0)
        agent.awaitStatus { it is GatewayStatus.Connected }

        val list = s.request("r1", "tools/list")
        assertEquals(6, list["result"]!!.jsonObject["tools"]!!.jsonArray.size)

        val ok = s.request("r2", "tools/call", """{"name":"echo","arguments":{"text":"hi"}}""")
        val text = ok["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text, text.contains("echo: hi"))
        assertEquals("false", ok["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content)

        val unknown = s.request("r3", "tools/call", """{"name":"nope","arguments":{}}""")
        assertEquals("UNKNOWN_TOOL", unknown["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        // shell 在本机注册表里存在，但未对 Gateway 授权：必须拒绝且不得执行
        val denied = s.request("r4", "tools/call", """{"name":"shell","arguments":{}}""")
        assertEquals("POLICY_DENIED", denied["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        val broken = s.request("r5", "tools/call", """{"name":"broken","arguments":{}}""")
        assertEquals("true", broken["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content)

        val badArgs = s.request("r6", "tools/call", """{"name":"echo","arguments":"x"}""")
        assertEquals("INVALID_ARGUMENT", badArgs["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        val unsupported = s.request("r7", "resources/list")
        assertEquals("UNSUPPORTED_METHOD", unsupported["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        val img = s.request("r8", "tools/call", """{"name":"echo_image","arguments":{}}""")
        val first = img["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals(img.toString(), "image", first["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", first["mimeType"]!!.jsonPrimitive.content)
        assertTrue(first["data"]!!.jsonPrimitive.content.isNotEmpty())
        runBlocking { agent.stop() }
    }

    @Test
    fun toolTimeoutReplies() {
        setupSocket()
        val agent = newAgent(options = GatewayAgentOptions(defaultCallTimeoutMs = 1_000, initialBackoffMs = 50))
        runBlocking { agent.start() }
        val s = nextSession(0)
        agent.awaitStatus { it is GatewayStatus.Connected }
        val r = s.request("t1", "tools/call", """{"name":"slow","arguments":{}}""")
        assertEquals("TIMEOUT", r["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        runBlocking { agent.stop() }
    }

    @Test
    fun stopCancelsInFlightTool() {
        setupSocket()
        val agent = newAgent(options = GatewayAgentOptions(defaultCallTimeoutMs = 30_000))
        runBlocking { agent.start() }
        val s = nextSession(0)
        agent.awaitStatus { it is GatewayStatus.Connected }
        s.socket.send("""{"type":"request","request_id":"c1","method":"tools/call","params":{"name":"slow","arguments":{}}}""")
        runBlocking { withTimeout(5_000) { slowStarted.await() } }
        runBlocking { agent.stop() }
        val deadline = System.currentTimeMillis() + 3_000
        while (!slowCancelled.get() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("in-flight tool must be cancelled on stop", slowCancelled.get())
        assertEquals(GatewayStatus.Disconnected, agent.status.value)
    }

    @Test
    fun reconnectsAfterServerDropsConnection() {
        setupSocket()
        setupSocket()
        val agent = newAgent()
        runBlocking { agent.start() }
        val first = nextSession(0)
        agent.awaitStatus { it is GatewayStatus.Connected }
        first.socket.close(1001, "going away")
        val second = nextSession(1)
        second.next("hello")
        agent.awaitStatus { it is GatewayStatus.Connected && (it as GatewayStatus.Connected).sessionId == "s-2" }
        runBlocking { agent.stop() }
    }

    @Test
    fun duplicateStartKeepsSingleSession() {
        setupSocket()
        setupSocket()
        val agent = newAgent()
        runBlocking {
            agent.start(); agent.start(); agent.start()
        }
        nextSession(0)
        agent.awaitStatus { it is GatewayStatus.Connected }
        Thread.sleep(300)
        assertEquals(1, sessions.size)
        runBlocking { agent.stop() }
    }

    @Test
    fun malformedAndHostileMessagesAreIgnored() {
        setupSocket()
        val agent = newAgent()
        runBlocking { agent.start() }
        val s = nextSession(0)
        agent.awaitStatus { it is GatewayStatus.Connected }
        s.socket.send("not json {{{")
        s.socket.send("[1,2,3]")
        s.socket.send("""{"type":"request","request_id":"","method":"tools/call"}""")
        s.socket.send("""{"type":"request","request_id":"${"x".repeat(500)}","method":"tools/list"}""")
        s.socket.send("""{"type":"config","backends":[{"name":"evil","template":"custom","params":{"command":["sh","-c","id"]}}]}""")
        // 仍然存活并能正常服务
        val r = s.request("ok", "tools/list")
        assertTrue(r["result"] != null)
        assertTrue(agent.status.value is GatewayStatus.Connected)
        runBlocking { agent.stop() }
    }

    @Test
    fun authRejectionIsFatalAndNotRetried() {
        server.enqueue(MockResponse().setResponseCode(401))
        val agent = newAgent()
        runBlocking { agent.start() }
        agent.awaitStatus { it is GatewayStatus.Error }
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
        runBlocking { agent.stop() }
    }

    @Test
    fun revokedCloseIsFatal() {
        setupSocket()
        val agent = newAgent()
        runBlocking { agent.start() }
        val s = nextSession(0)
        agent.awaitStatus { it is GatewayStatus.Connected }
        s.socket.close(4003, "revoked")
        agent.awaitStatus { it is GatewayStatus.Error }
        assertTrue((agent.status.value as GatewayStatus.Error).message.contains("revoked"))
        Thread.sleep(200)
        assertEquals(1, sessions.size)
    }

    @Test
    fun bareHostUrlGetsDevicePath() {
        assertEquals("/device", GatewayDeviceAgent.toHttpUrl("wss://gw.example.com")!!.encodedPath)
        assertEquals("/device", GatewayDeviceAgent.toHttpUrl("wss://gw.example.com/")!!.encodedPath)
        assertEquals("/x/device", GatewayDeviceAgent.toHttpUrl("wss://gw.example.com/x/device")!!.encodedPath)
    }

    @Test
    fun insecureRemoteUrlIsRejectedWithoutConnecting() {
        val agent = newAgent(url = "ws://gateway.example.com/device")
        runBlocking { agent.start() }
        agent.awaitStatus { it is GatewayStatus.Error }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun manualReconnectOpensNewSession() {
        setupSocket()
        setupSocket()
        val agent = newAgent()
        runBlocking { agent.start() }
        nextSession(0)
        agent.awaitStatus { it is GatewayStatus.Connected }
        runBlocking { agent.reconnect() }
        nextSession(1)
        agent.awaitStatus { it is GatewayStatus.Connected && (it as GatewayStatus.Connected).sessionId == "s-2" }
        runBlocking { agent.stop() }
    }
}
