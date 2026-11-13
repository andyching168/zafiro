package com.niki914.zafiro.mcp.gateway

// 保护：只有用户在设置页勾选转发、且仍处于启用的 MCP 服务器，才允许被 Gateway 远端调用；
// 去掉白名单或启用状态判断，远端客户端就能调用用户没有开放的服务器。

import com.niki914.okia.mcp.McpCallResult
import com.niki914.okia.mcp.McpClient
import com.niki914.okia.mcp.McpContentBlock
import com.niki914.okia.mcp.McpDiscoveredTool
import com.niki914.okia.mcp.McpServer
import com.niki914.zafiro.chat.util.SilentLoggerRule
import com.niki914.zafiro.settings.model.RuntimeMcpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class GatewayMcpForwarderTest {
    @get:Rule
    val silentLogger = SilentLoggerRule()

    private class FakeClient : McpClient {
        val calls = mutableListOf<String>()

        override suspend fun discoverTools(server: McpServer) =
            listOf(McpDiscoveredTool("ping", "ping tool", null))

        override suspend fun callTool(server: McpServer, toolName: String, argumentsJson: String): McpCallResult {
            calls += "${server.name}/$toolName"
            return McpCallResult(false, listOf(McpContentBlock.Text("pong")))
        }
    }

    private fun server(name: String, enabled: Boolean = true) =
        RuntimeMcpServer(name = name, url = "http://localhost/$name", enabled = enabled)

    @Test
    fun listsOnlyAllowedEnabledServers() = runBlocking {
        val forwarder = GatewayMcpForwarder(FakeClient()) {
            listOf(server("a"), server("b"), server("c", enabled = false))
        }

        val names = forwarder.list(setOf("a", "c")).map { it.wireName }

        assertEquals(listOf("mcp__a__ping"), names)
    }

    @Test
    fun rejectsCallToServerNotAllowed() = runBlocking {
        val client = FakeClient()
        val forwarder = GatewayMcpForwarder(client) { listOf(server("a"), server("b")) }

        val result = forwarder.call("mcp__b__ping", setOf("a"), "{}")

        assertNull(result)
        assertEquals(emptyList<String>(), client.calls)
    }

    @Test
    fun rejectsCachedToolOnceServerIsNoLongerAllowed() = runBlocking {
        val client = FakeClient()
        val forwarder = GatewayMcpForwarder(client) { listOf(server("a")) }
        forwarder.list(setOf("a"))

        val result = forwarder.call("mcp__a__ping", emptySet(), "{}")

        assertNull(result)
        assertEquals(emptyList<String>(), client.calls)
    }

    @Test
    fun forwardsCallToAllowedServer() = runBlocking {
        val client = FakeClient()
        val forwarder = GatewayMcpForwarder(client) { listOf(server("a")) }

        val result = forwarder.call("mcp__a__ping", setOf("a"), "{}")

        assertNotNull(result)
        assertEquals(listOf("a/ping"), client.calls)
    }
}
