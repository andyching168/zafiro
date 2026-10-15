package com.niki914.zafiro.mcp.gateway

import com.niki914.zafiro.mcp.service.McpToolAdapter
import com.niki914.zafiro.settings.model.RuntimeMcpServiceConfig
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class GatewayToolException(val code: String, message: String) : Exception(message)

/**
 * Gateway 请求与 [McpToolAdapter] 之间的桥：工具清单、白名单判定、执行与结果序列化
 * 都复用 HTTP MCP 同一套实现，这里只做 JSON 形态转换和体积控制。
 */
class GatewayToolBridge(private val adapter: McpToolAdapter) {

    companion object {
        /** 网关 WebSocket maxPayload 为 16MB，base64 之后留出余量。 */
        const val MAX_RESULT_CHARS = 10 * 1024 * 1024
    }

    fun listTools(config: RuntimeMcpServiceConfig): JsonArray = JsonArray(
        adapter.exposedTools(config).map { tool ->
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(tool.name),
                    "description" to JsonPrimitive(tool.description),
                    "inputSchema" to inputSchema(tool.inputSchemaJson),
                )
            )
        }
    )

    suspend fun call(
        config: RuntimeMcpServiceConfig,
        name: String,
        arguments: JsonObject,
    ): JsonObject {
        val tool = adapter.exposedTools(config).firstOrNull { it.name == name }
            ?: throw if (adapter.isKnownTool(name)) {
                GatewayToolException("POLICY_DENIED", "tool ${name.take(80)} is not enabled for remote use on this device")
            } else {
                GatewayToolException("UNKNOWN_TOOL", "unknown tool ${name.take(80)}")
            }
        val result = adapter.execute(tool, arguments.toString())
        var size = 0
        val content = result.content.map { block ->
            when (block) {
                is TextContent -> {
                    size += block.text.length
                    JsonObject(
                        mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(block.text))
                    )
                }

                is ImageContent -> {
                    size += block.data.length
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("image"),
                            "data" to JsonPrimitive(block.data),
                            "mimeType" to JsonPrimitive(block.mimeType),
                        )
                    )
                }

                else -> JsonObject(
                    mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive("[unsupported content]"))
                )
            }
        }
        if (size > MAX_RESULT_CHARS) {
            throw GatewayToolException(
                "RESULT_TOO_LARGE",
                "tool result exceeds the ${MAX_RESULT_CHARS / (1024 * 1024)}MB transport limit",
            )
        }
        return JsonObject(
            mapOf(
                "content" to JsonArray(content),
                "isError" to JsonPrimitive(result.isError == true),
            )
        )
    }

    private fun inputSchema(raw: String?): JsonElement {
        val parsed = raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonObject }
        val base = parsed ?: JsonObject(emptyMap())
        return JsonObject(
            base + mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to (base["properties"] as? JsonObject ?: JsonObject(emptyMap())),
            )
        )
    }
}
