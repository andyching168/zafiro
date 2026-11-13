package com.niki914.zafiro.mcp.gateway

import com.niki914.logging.Logger
import com.niki914.okia.mcp.McpCallResult
import com.niki914.okia.mcp.McpClient
import com.niki914.okia.mcp.McpServer
import com.niki914.okia.mcp.McpTransport
import com.niki914.okia.tooling.ToolWireName
import com.niki914.zafiro.settings.model.RuntimeMcpServer
import kotlin.coroutines.cancellation.CancellationException

/**
 * 把用户在 Zafiro 里配置的 MCP 服务器经 Gateway 转发给远端客户端。
 *
 * 这些服务器多半只在手机本机可达（localhost / 局域网），Gateway 主机连不到，
 * 所以由手机端做代理：发现工具、按线缆名 `mcp__<server>__<tool>` 暴露、收到调用后转发。
 * 只转发 [allowed] 里的服务器，该集合来自本机设置页的显式勾选。
 */
class GatewayMcpForwarder(
    private val client: McpClient,
    private val servers: suspend () -> List<RuntimeMcpServer>,
) {
    private companion object {
        private const val LOG_TAG = "niki914_zafiro_GatewayMcpForwarder"
    }

    class ForwardedTool(
        val wireName: String,
        val serverName: String,
        val toolName: String,
        val description: String,
        val inputSchemaJson: String?,
    )

    @Volatile
    private var index: Map<String, ForwardedTool> = emptyMap()

    /** 发现 [allowed] 中已启用服务器的工具；单台服务器失败只跳过它，不影响其它。 */
    suspend fun list(allowed: Set<String>): List<ForwardedTool> {
        if (allowed.isEmpty()) {
            index = emptyMap()
            return emptyList()
        }
        val found = LinkedHashMap<String, ForwardedTool>()
        for (configured in servers()) {
            if (!configured.enabled || configured.name !in allowed) continue
            val tools = try {
                client.discoverTools(configured.toClientServer())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(LOG_TAG, "discover failed for ${configured.name}: ${e.message}")
                continue
            }
            for (tool in tools) {
                val wire = ToolWireName.forMcp(configured.name, tool.name)
                found.putIfAbsent(
                    wire,
                    ForwardedTool(
                        wireName = wire,
                        serverName = configured.name,
                        toolName = tool.name,
                        description = tool.description.orEmpty(),
                        inputSchemaJson = tool.inputSchemaJson,
                    ),
                )
            }
        }
        index = found
        return found.values.toList()
    }

    /** 转发一次调用；工具不存在、服务器未勾选或已停用时返回 null。 */
    suspend fun call(wireName: String, allowed: Set<String>, argumentsJson: String): McpCallResult? {
        val tool = (index[wireName] ?: list(allowed).firstOrNull { it.wireName == wireName })
            ?.takeIf { it.serverName in allowed }
            ?: return null
        val configured = servers().firstOrNull { it.name == tool.serverName && it.enabled } ?: return null
        return client.callTool(configured.toClientServer(), tool.toolName, argumentsJson)
    }

    private fun RuntimeMcpServer.toClientServer() = McpServer(
        name = name,
        transport = McpTransport.Http(url),
        headers = headers,
        enabled = enabled,
    )
}
