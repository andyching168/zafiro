package com.niki914.zafiro.chat.agentic.display

import com.niki914.zafiro.api.VirtualDisplayState
import com.niki914.zafiro.chat.util.SilentLoggerRule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// 保护：虚拟屏生命周期——握手口令校验、受管 id 才能截图/启动、最后一块屏销毁后辅助进程被停止、
// 辅助进程断开后状态与屏幕列表被清空（不留悬空的 display id 让工具继续操作）。
class VirtualDisplayManagerTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    /** 在测试里扮演 shell 辅助进程：连回 App 并按协议应答。 */
    private class FakeHelper(val token: (String) -> String = { it }) {
        val commands = CopyOnWriteArrayList<String>()
        val stopped = AtomicBoolean(false)
        private var nextDisplay = 4
        @Volatile
        var socket: Socket? = null

        fun launcher() = HelperLauncher { port, tokenArg ->
            thread(isDaemon = true) {
                val s = Socket("127.0.0.1", port)
                socket = s
                val out = s.getOutputStream().bufferedWriter()
                val input = BufferedReader(InputStreamReader(s.getInputStream()))
                out.write("""{"hello":true,"token":"${token(tokenArg)}","uid":2000,"sdk":36}""" + "\n")
                out.flush()
                while (true) {
                    val line = input.readLine() ?: break
                    val req = Json.parseToJsonElement(line).jsonObject
                    val cmd = req["cmd"]!!.jsonPrimitive.content
                    commands += cmd
                    val reply = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                        "id" to req["id"]!!, "ok" to JsonPrimitive(true),
                    )
                    when (cmd) {
                        "create" -> {
                            reply["display"] = JsonPrimitive(nextDisplay++)
                            reply["width"] = req["width"]!!
                            reply["height"] = req["height"]!!
                            reply["dpi"] = req["dpi"]!!
                        }

                        "screenshot" -> {
                            reply["data"] = JsonPrimitive(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
                            reply["mime"] = JsonPrimitive("image/png")
                            reply["width"] = JsonPrimitive(1)
                            reply["height"] = JsonPrimitive(1)
                        }

                        "stop" -> stopped.set(true)
                    }
                    out.write(JsonObject(reply).toString() + "\n")
                    out.flush()
                    if (cmd == "stop") break
                }
                s.close()
            }
            Result.success(Unit)
        }
    }

    private fun manager(helper: FakeHelper) =
        VirtualDisplayManager(launcher = helper.launcher(), defaultMetrics = { Triple(1080, 2400, 420) })

    @Test
    fun createCaptureAndDestroyLastDisplayStopsTheHelper() = runBlocking {
        val helper = FakeHelper()
        val m = manager(helper)

        val info = m.create().getOrThrow()
        assertEquals(4, info.displayId)
        assertEquals(listOf(info), m.displays())
        assertTrue(m.state.value is VirtualDisplayState.Running)

        val frame = m.capture(info.displayId).getOrThrow()
        assertEquals("image/png", frame.mimeType)
        assertEquals(3, frame.bytes.size)

        m.destroy(info.displayId).getOrThrow()
        assertTrue(m.displays().isEmpty())
        assertEquals(VirtualDisplayState.Stopped, m.state.value)
        assertTrue("helper must be told to stop", helper.stopped.get())
    }

    @Test
    fun unmanagedDisplayIdsAreRefusedBeforeTouchingTheHelper() = runBlocking {
        val helper = FakeHelper()
        val m = manager(helper)
        val id = m.create().getOrThrow().displayId

        assertTrue(m.capture(0).isFailure)
        assertTrue(m.capture(id + 100).isFailure)
        assertTrue(m.launch(0, "com.android.settings/.Settings").isFailure)
        assertTrue(m.destroy(id + 100).isFailure)
        assertFalse("screenshot" in helper.commands)
        assertFalse("launch" in helper.commands)
        m.destroyAll()
    }

    @Test
    fun wrongHandshakeTokenIsRejected() = runBlocking {
        val m = manager(FakeHelper(token = { "not-the-token" }))
        val result = m.create()
        assertTrue(result.isFailure)
        assertTrue(m.displays().isEmpty())
        assertNull(m.managedDisplay(4))
    }

    @Test
    fun helperDeathClearsDisplaysAndReportsError() = runBlocking {
        val helper = FakeHelper()
        val m = manager(helper)
        val id = m.create().getOrThrow().displayId
        helper.socket!!.close()

        val deadline = System.currentTimeMillis() + 3_000
        while (m.displays().isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("display list must be cleared when the helper disappears", m.displays().isEmpty())
        assertTrue(m.state.value is VirtualDisplayState.Error)
        assertTrue(m.capture(id).isFailure)
    }
}
