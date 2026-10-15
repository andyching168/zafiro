package com.niki914.zafiro.app.ui.model

import android.os.Build
import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.api.GatewayDeviceService
import com.niki914.zafiro.api.GatewayStatus
import com.niki914.zafiro.mcp.gateway.GatewayPairResult
import com.niki914.zafiro.mcp.gateway.GatewayPairingClient
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class GatewaySettingsUiState(
    val enabled: Boolean = false,
    val autoConnect: Boolean = true,
    val status: GatewayStatus = GatewayStatus.Disconnected,
    val urlInput: String = "",
    val deviceIdInput: String = "",
    val nameInput: String = "",
    /** 只保存本次编辑输入；已保存的密钥永不回显。 */
    val secretInput: String = "",
    val secretConfigured: Boolean = false,
    val secretVisible: Boolean = false,
    val pairing: GatewayPairingUi = GatewayPairingUi.Idle,
    val exposedTools: Set<String> = emptySet(),
    val lockTouch: Boolean = false,
    val tools: List<McpServiceToolItemUi> = emptyList(),
)

sealed interface GatewayPairingUi {
    data object Idle : GatewayPairingUi
    data object Requesting : GatewayPairingUi
    data class WaitingApproval(val code: String) : GatewayPairingUi
    data object Paired : GatewayPairingUi
    data class Failed(val message: String) : GatewayPairingUi
}

sealed interface GatewaySettingsIntent {
    data object Load : GatewaySettingsIntent
    data class ToggleEnabled(val enabled: Boolean) : GatewaySettingsIntent
    data class ToggleAutoConnect(val enabled: Boolean) : GatewaySettingsIntent
    data class UrlChanged(val value: String) : GatewaySettingsIntent
    data class DeviceIdChanged(val value: String) : GatewaySettingsIntent
    data class NameChanged(val value: String) : GatewaySettingsIntent
    data class SecretChanged(val value: String) : GatewaySettingsIntent
    data object ToggleSecretVisibility : GatewaySettingsIntent
    data object Reconnect : GatewaySettingsIntent
    data object Pair : GatewaySettingsIntent
    data class ToggleLockTouch(val enabled: Boolean) : GatewaySettingsIntent
    data class ToggleTool(val toolName: String, val checked: Boolean) : GatewaySettingsIntent
}

sealed interface GatewaySettingsEffect

class GatewaySettingsViewModel :
    ComposeMVIViewModel<GatewaySettingsIntent, GatewaySettingsUiState, GatewaySettingsEffect>() {

    private companion object {
        private const val LOG_TAG = "niki914_zafiro_GatewaySettingsViewModel"
    }

    private val service = requireService<GatewayDeviceService>()

    init {
        viewModelScope.launch {
            service.status.collectLatest { updateState { copy(status = it) } }
        }
        viewModelScope.launch {
            XRepo.gateway.configFlow.collectLatest { config ->
                updateState {
                    copy(
                        enabled = config.enabled,
                        autoConnect = config.autoConnect,
                        secretConfigured = config.deviceSecret.isNotBlank(),
                        exposedTools = config.exposedTools,
                        lockTouch = config.lockTouchDuringOperation,
                    )
                }
            }
        }
    }

    override fun initUiState(): GatewaySettingsUiState = GatewaySettingsUiState()

    override suspend fun handleIntent(intent: GatewaySettingsIntent) {
        when (intent) {
            GatewaySettingsIntent.Load -> {
                val config = XRepo.gateway.get()
                val allTools = McpToolCatalog.list()
                updateState {
                    copy(
                        enabled = config.enabled,
                        autoConnect = config.autoConnect,
                        urlInput = config.url,
                        deviceIdInput = config.deviceId,
                        nameInput = config.deviceName,
                        secretConfigured = config.deviceSecret.isNotBlank(),
                        exposedTools = config.exposedTools,
                        lockTouch = config.lockTouchDuringOperation,
                        tools = allTools,
                    )
                }
            }

            is GatewaySettingsIntent.ToggleEnabled -> {
                Logger.i(LOG_TAG, "user toggled gateway device enabled: ${intent.enabled}")
                XRepo.gateway.update { it.copy(enabled = intent.enabled) }
            }

            is GatewaySettingsIntent.ToggleAutoConnect ->
                XRepo.gateway.update { it.copy(autoConnect = intent.enabled) }

            is GatewaySettingsIntent.UrlChanged -> {
                updateState { copy(urlInput = intent.value) }
                XRepo.gateway.update { it.copy(url = intent.value.trim()) }
            }

            is GatewaySettingsIntent.DeviceIdChanged -> {
                updateState { copy(deviceIdInput = intent.value) }
                XRepo.gateway.update { it.copy(deviceId = intent.value.trim()) }
            }

            is GatewaySettingsIntent.NameChanged -> {
                updateState { copy(nameInput = intent.value) }
                XRepo.gateway.update { it.copy(deviceName = intent.value.trim()) }
            }

            is GatewaySettingsIntent.SecretChanged -> {
                updateState { copy(secretInput = intent.value) }
                XRepo.gateway.update { it.copy(deviceSecret = intent.value.trim()) }
            }

            GatewaySettingsIntent.ToggleSecretVisibility ->
                updateState { copy(secretVisible = !secretVisible) }

            GatewaySettingsIntent.Reconnect -> service.reconnect()
            GatewaySettingsIntent.Pair -> pair()
            is GatewaySettingsIntent.ToggleLockTouch ->
                XRepo.gateway.update { it.copy(lockTouchDuringOperation = intent.enabled) }
            is GatewaySettingsIntent.ToggleTool -> XRepo.gateway.update {
                it.copy(
                    exposedTools = if (intent.checked) it.exposedTools + intent.toolName
                    else it.exposedTools - intent.toolName
                )
            }
        }
    }

    private suspend fun pair() {
        val config = XRepo.gateway.get()
        val deviceId = config.deviceId.ifBlank { defaultDeviceId() }
        val name = config.deviceName.ifBlank { Build.MODEL }
        updateState { copy(pairing = GatewayPairingUi.Requesting, deviceIdInput = deviceId) }
        val result = GatewayPairingClient().pair(
            gatewayUrl = config.url,
            deviceId = deviceId,
            name = name,
            hostname = Build.MODEL,
            platform = "android-${Build.VERSION.RELEASE}",
        ) { code, _ -> updateStateFromCallback(GatewayPairingUi.WaitingApproval(code)) }
        when (result) {
            is GatewayPairResult.Approved -> {
                XRepo.gateway.update {
                    it.copy(deviceId = result.deviceId, deviceSecret = result.deviceSecret, deviceName = name)
                }
                updateState { copy(pairing = GatewayPairingUi.Paired, deviceIdInput = result.deviceId, nameInput = name) }
            }

            is GatewayPairResult.Failed ->
                updateState { copy(pairing = GatewayPairingUi.Failed(result.message)) }
        }
    }

    private fun updateStateFromCallback(pairing: GatewayPairingUi) {
        viewModelScope.launch { updateState { copy(pairing = pairing) } }
    }

    /** 网关要求 `^[a-z0-9][a-z0-9._-]{1,62}$`。 */
    private fun defaultDeviceId(): String {
        val cleaned = Build.MODEL.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-', '.', '_')
        return cleaned.ifBlank { "android" }.take(40)
    }
}
