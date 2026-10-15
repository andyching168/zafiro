package com.niki914.zafiro.repo

import com.niki914.zafiro.repo.SettingsJsonCodecUtils.boolean
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.stringValues
import com.niki914.zafiro.settings.model.RuntimeGatewayConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Gateway 设备配置的落盘格式。Device Secret 以 [SecretCipher] 密文存放；
 * 工具白名单只来自用户在本机设置页的显式勾选（缺省为不含 shell/ssh/python 的安全集合），
 * Gateway 协议里没有任何消息能写到这里。
 */
internal object GatewaySettingsCodec {
    private const val KEY_ENABLED = "enabled"
    private const val KEY_URL = "url"
    private const val KEY_DEVICE_ID = "deviceId"
    private const val KEY_SECRET = "deviceSecretEnc"
    private const val KEY_NAME = "deviceName"
    private const val KEY_AUTO_CONNECT = "autoConnect"
    private const val KEY_EXPOSED_TOOLS = "exposedTools"
    private const val KEY_LOCK_TOUCH = "lockTouchDuringOperation"

    fun parse(json: String, cipher: SecretCipher): RuntimeGatewayConfig {
        val obj = parseObject(json)
        return RuntimeGatewayConfig(
            enabled = obj.boolean(KEY_ENABLED, default = false),
            url = obj.string(KEY_URL).trim(),
            deviceId = obj.string(KEY_DEVICE_ID).trim(),
            deviceSecret = cipher.decrypt(obj.string(KEY_SECRET)),
            deviceName = obj.string(KEY_NAME).trim(),
            autoConnect = obj.boolean(KEY_AUTO_CONNECT, default = true),
            exposedTools = (obj[KEY_EXPOSED_TOOLS] as? JsonArray)?.stringValues()?.toSet()
                ?: RuntimeGatewayConfig.DEFAULT_EXPOSED_TOOLS,
            lockTouchDuringOperation = obj.boolean(KEY_LOCK_TOUCH, default = false),
        )
    }

    fun encode(config: RuntimeGatewayConfig, cipher: SecretCipher): String = JsonObject(
        mapOf(
            KEY_ENABLED to JsonPrimitive(config.enabled),
            KEY_URL to JsonPrimitive(config.url),
            KEY_DEVICE_ID to JsonPrimitive(config.deviceId),
            KEY_SECRET to JsonPrimitive(cipher.encrypt(config.deviceSecret)),
            KEY_NAME to JsonPrimitive(config.deviceName),
            KEY_AUTO_CONNECT to JsonPrimitive(config.autoConnect),
            KEY_EXPOSED_TOOLS to JsonArray(config.exposedTools.sorted().map { JsonPrimitive(it) }),
            KEY_LOCK_TOUCH to JsonPrimitive(config.lockTouchDuringOperation),
        )
    ).toString()
}
