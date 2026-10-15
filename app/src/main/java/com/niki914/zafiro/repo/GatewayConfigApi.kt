package com.niki914.zafiro.repo

import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.settings.model.RuntimeGatewayConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GatewayConfigApi internal constructor(
    private val repo: XRepo,
    private val cipher: SecretCipher = KeystoreSecretCipher,
) {
    private var loaded: RuntimeGatewayConfig? = null

    private val _configFlow = MutableStateFlow(RuntimeGatewayConfig())
    val configFlow: StateFlow<RuntimeGatewayConfig> = _configFlow.asStateFlow()

    suspend fun get(): RuntimeGatewayConfig {
        loaded?.let { return it }
        val parsed = GatewaySettingsCodec.parse(
            repo.readJson(StoreDescriptorRegistry.TOOLS_GATEWAY_ID), cipher
        )
        loaded = parsed
        _configFlow.value = parsed
        return parsed
    }

    suspend fun update(transform: (RuntimeGatewayConfig) -> RuntimeGatewayConfig): RuntimeGatewayConfig {
        var updated = RuntimeGatewayConfig()
        repo.updateJson(StoreDescriptorRegistry.TOOLS_GATEWAY_ID) { json ->
            updated = transform(GatewaySettingsCodec.parse(json, cipher))
            GatewaySettingsCodec.encode(updated, cipher)
        }
        loaded = updated
        _configFlow.value = updated
        return updated
    }

    internal fun resetForTest() {
        loaded = null
        _configFlow.value = RuntimeGatewayConfig()
    }
}
