package com.niki914.zafiro.mcp.gateway

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 保护：配对流程拿到的 device_secret 只在批准后返回；窗口未开（404）或被拒必须给出可读失败而不是挂起。
class GatewayPairingClientTest {
    private val server = MockWebServer().also { it.start() }
    private val url get() = "ws://127.0.0.1:${server.port}/device"

    @After
    fun tearDown() = server.shutdown()

    private fun pair(): GatewayPairResult = runBlocking {
        GatewayPairingClient(pollIntervalMs = 10).pair(url, "pixel-1", "Pixel", "host", "android") { _, _ -> }
    }

    @Test
    fun returnsSecretOnceApproved() {
        server.enqueue(MockResponse().setBody("""{"code":"ABCD-EFGH","poll_token":"p","expires_in":30}"""))
        server.enqueue(MockResponse().setBody("""{"status":"pending"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"approved","device_id":"pixel-1","device_secret":"sek"}"""))
        assertEquals(GatewayPairResult.Approved("pixel-1", "sek"), pair())
        assertEquals("/pair/request", server.takeRequest().path)
    }

    @Test
    fun closedWindowAndDenialFailCleanly() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))
        assertTrue((pair() as GatewayPairResult.Failed).message.contains("admin.js pair"))

        server.enqueue(MockResponse().setBody("""{"code":"ABCD-EFGH","poll_token":"p","expires_in":30}"""))
        server.enqueue(MockResponse().setBody("""{"status":"denied"}"""))
        assertEquals(GatewayPairResult.Failed("Pairing was denied"), pair())
    }
}
