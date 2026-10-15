package com.niki914.zafiro.repo

import com.niki914.zafiro.settings.model.RuntimeGatewayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// 保护：Device Secret 不得以明文落盘；未显式勾选时 Gateway 默认白名单不含 shell/ssh/python，且用户的显式选择能完整往返。
class GatewaySettingsCodecTest {

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = if (plain.isEmpty()) "" else "enc(" + plain.reversed() + ")"
        override fun decrypt(encoded: String) =
            if (encoded.startsWith("enc(")) encoded.removePrefix("enc(").removeSuffix(")").reversed() else ""
    }

    @Test
    fun secretIsNeverWrittenInPlaintext() {
        val json = GatewaySettingsCodec.encode(
            RuntimeGatewayConfig(enabled = true, url = "wss://g/device", deviceId = "p1", deviceSecret = "topsecret"),
            cipher,
        )
        assertFalse(json.contains("topsecret"))
        assertEquals("topsecret", GatewaySettingsCodec.parse(json, cipher).deviceSecret)
    }

    @Test
    fun defaultWhitelistExcludesHighRiskToolsAndExplicitChoiceRoundTrips() {
        val fresh = GatewaySettingsCodec.parse("""{"enabled":true}""", cipher)
        assertFalse("shell" in fresh.exposedTools)
        assertFalse("ssh" in fresh.exposedTools)
        assertFalse("execute_python" in fresh.exposedTools)

        val chosen = fresh.copy(exposedTools = setOf("screenshot", "shell"))
        val back = GatewaySettingsCodec.parse(GatewaySettingsCodec.encode(chosen, cipher), cipher)
        assertEquals(setOf("screenshot", "shell"), back.exposedTools)
    }
}
