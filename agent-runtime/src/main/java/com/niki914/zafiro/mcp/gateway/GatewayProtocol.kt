package com.niki914.zafiro.mcp.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * SelfHostDesktopMCP 设备通道（gateway/server.js `/device`）的线上协议。
 * 入站消息一律视为不可信：类型、长度、结构在这里一次性校验，通过后才进入业务层。
 */
internal object GatewayProtocol {
    const val PROTOCOL_VERSION = 1

    const val MAX_INBOUND_CHARS = 1 shl 20
    const val MAX_REQUEST_ID_CHARS = 128
    const val MAX_TOOL_NAME_CHARS = 128
    const val MAX_ARGUMENTS_CHARS = 256 * 1024

    const val CLOSE_REPLACED = 4000
    const val CLOSE_HELLO_TIMEOUT = 4001
    const val CLOSE_BAD_HELLO = 4002
    const val CLOSE_REVOKED = 4003

    sealed interface Inbound {
        data class HelloAck(val sessionId: String) : Inbound
        data object Pong : Inbound

        /** 桌面端后端配置；Android 不支持远程拉起后端，只确认收到。 */
        data object Config : Inbound
        data class Request(val requestId: String, val method: String, val params: JsonObject) :
            Inbound

        /** 结构合法但本端不认识的类型：忽略。 */
        data object Unknown : Inbound
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 返回 null 表示消息畸形（非 JSON、非对象、缺字段、超长）。 */
    fun parseInbound(raw: String): Inbound? {
        if (raw.length > MAX_INBOUND_CHARS) return null
        val obj = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return null
        val type = obj.str("type") ?: return null
        return when (type) {
            "hello_ack" -> obj.str("session_id")?.let { Inbound.HelloAck(it.take(128)) }
            "pong" -> Inbound.Pong
            "config" -> Inbound.Config
            "request" -> {
                val id = obj.str("request_id")?.takeIf { it.isNotEmpty() && it.length <= MAX_REQUEST_ID_CHARS }
                    ?: return null
                val method = obj.str("method")?.takeIf { it.length <= 64 } ?: return null
                val params = when (val p = obj["params"]) {
                    null -> JsonObject(emptyMap())
                    is JsonObject -> p
                    else -> return null
                }
                Inbound.Request(id, method, params)
            }

            else -> Inbound.Unknown
        }
    }

    fun hello(
        deviceId: String,
        name: String,
        platform: String,
        hostname: String,
        tools: JsonArray,
    ): String = JsonObject(
        mapOf(
            "type" to JsonPrimitive("hello"),
            "device_id" to JsonPrimitive(deviceId),
            "protocol_version" to JsonPrimitive(PROTOCOL_VERSION),
            "name" to JsonPrimitive(name),
            "platform" to JsonPrimitive(platform),
            "hostname" to JsonPrimitive(hostname),
            "tools" to tools,
            // 网关用它在「稳定模式」下列出工具；Android 的工具就是内置工具本身
            "builtin_tools" to tools,
            "custom_backends" to JsonPrimitive(false),
        )
    ).toString()

    fun ping(): String = """{"type":"ping"}"""

    /**
     * 网关管理页按名为 desktop-commander 的内置槽位展示工具，所以把 Android 工具报在这个槽位下
     * （prefix 为空，工具名保持原样）。仅用于展示，不代表启动了任何桌面后端。
     */
    fun backendsStatus(tools: JsonArray): String {
        val summaries = JsonArray(tools.mapNotNull { it as? JsonObject }.map { t ->
            JsonObject(
                mapOf(
                    "name" to (t["name"] ?: JsonPrimitive("")),
                    "description" to JsonPrimitive(
                        ((t["description"] as? JsonPrimitive)?.contentOrNull ?: "").take(160)
                    ),
                )
            )
        })
        val backend = JsonObject(
            mapOf(
                "name" to JsonPrimitive("desktop-commander"),
                "builtin" to JsonPrimitive(true),
                "template" to JsonPrimitive("builtin"),
                "status" to JsonPrimitive("running"),
                "prefix" to JsonPrimitive(""),
                "tools" to summaries,
                "enabled" to JsonArray(summaries.map { (it as JsonObject)["name"]!! }),
            )
        )
        return JsonObject(
            mapOf("type" to JsonPrimitive("backends_status"), "backends" to JsonArray(listOf(backend)))
        ).toString()
    }

    fun toolsChanged(tools: JsonArray): String = JsonObject(
        mapOf("type" to JsonPrimitive("tools_changed"), "tools" to tools)
    ).toString()

    fun result(requestId: String, result: JsonElement): String = JsonObject(
        mapOf(
            "type" to JsonPrimitive("response"),
            "request_id" to JsonPrimitive(requestId),
            "result" to result,
        )
    ).toString()

    fun error(requestId: String, code: String, message: String): String = JsonObject(
        mapOf(
            "type" to JsonPrimitive("response"),
            "request_id" to JsonPrimitive(requestId),
            "error" to JsonObject(
                mapOf("code" to JsonPrimitive(code), "message" to JsonPrimitive(message.take(300)))
            ),
        )
    ).toString()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
