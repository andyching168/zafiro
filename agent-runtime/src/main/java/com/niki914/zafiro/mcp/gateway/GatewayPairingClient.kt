package com.niki914.zafiro.mcp.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

sealed interface GatewayPairResult {
    data class Approved(val deviceId: String, val deviceSecret: String) : GatewayPairResult
    data class Failed(val message: String) : GatewayPairResult
}

/**
 * 对应 gateway/pairing.js：`/pair/request` 取得配对码，网关主机上执行 `admin.js` 批准后，
 * `/pair/poll` 一次性返回 device_secret。窗口未开时网关对两条路径都回 404。
 */
class GatewayPairingClient(
    private val client: OkHttpClient = GatewayDeviceAgent.defaultClient(),
    private val pollIntervalMs: Long = 2_000,
) {
    suspend fun pair(
        gatewayUrl: String,
        deviceId: String,
        name: String,
        hostname: String,
        platform: String,
        onCode: (code: String, expiresInSec: Int) -> Unit,
    ): GatewayPairResult = withContext(Dispatchers.IO) {
        val base = GatewayDeviceAgent.toHttpUrl(gatewayUrl)
            ?: return@withContext GatewayPairResult.Failed("Gateway URL must be wss://…/device")
        val req = post(
            base, "/pair/request",
            JsonObject(
                mapOf(
                    "device_id" to JsonPrimitive(deviceId),
                    "name" to JsonPrimitive(name),
                    "hostname" to JsonPrimitive(hostname),
                    "platform" to JsonPrimitive(platform),
                )
            ),
        ) ?: return@withContext GatewayPairResult.Failed("Cannot reach the gateway")
        when (req.first) {
            200 -> Unit
            404 -> return@withContext GatewayPairResult.Failed(
                "Pairing is closed. On the gateway host run: node gateway/admin.js pair"
            )
            else -> return@withContext GatewayPairResult.Failed(
                "Pairing refused: ${req.second.str("error") ?: "HTTP ${req.first}"}"
            )
        }
        val code = req.second.str("code")
        val poll = req.second.str("poll_token")
        val expires = (req.second["expires_in"] as? JsonPrimitive)?.intOrNull ?: 180
        if (code == null || poll == null) {
            return@withContext GatewayPairResult.Failed("Gateway returned an invalid pairing response")
        }
        onCode(code, expires)

        val deadline = System.currentTimeMillis() + expires * 1_000L + 5_000
        while (System.currentTimeMillis() < deadline) {
            delay(pollIntervalMs)
            val r = post(base, "/pair/poll", JsonObject(mapOf("poll_token" to JsonPrimitive(poll))))
                ?: continue
            if (r.first == 404) return@withContext GatewayPairResult.Failed("Pairing ended")
            when (r.second.str("status")) {
                "approved" -> {
                    val id = r.second.str("device_id")
                    val secret = r.second.str("device_secret")
                    return@withContext if (id != null && secret != null) {
                        GatewayPairResult.Approved(id, secret)
                    } else {
                        GatewayPairResult.Failed("Gateway returned an invalid approval")
                    }
                }

                "denied" -> return@withContext GatewayPairResult.Failed("Pairing was denied")
                "expired" -> return@withContext GatewayPairResult.Failed("Pairing code expired")
                "consumed" -> return@withContext GatewayPairResult.Failed("Pairing was already used")
            }
        }
        GatewayPairResult.Failed("Pairing timed out")
    }

    private fun post(base: HttpUrl, path: String, body: JsonObject): Pair<Int, JsonObject>? {
        val prefix = base.encodedPath.removeSuffix("/").removeSuffix("/device")
        val url = base.newBuilder().encodedPath(prefix + path).build()
        val request = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return try {
            client.newCall(request).execute().use { resp ->
                val parsed = runCatching { Json.parseToJsonElement(resp.body?.string().orEmpty()) }
                    .getOrNull() as? JsonObject ?: JsonObject(emptyMap())
                resp.code to parsed
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
