package com.niki914.zafiro.app.ui.model

import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.api.McpService
import com.niki914.zafiro.api.McpServiceStatus
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.requireService
import com.niki914.zafiro.settings.model.RuntimeMcpServiceConfig
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class McpServiceToolItemUi(
    val name: String,
    val displayName: String,
    val description: String,
    val isDangerous: Boolean = false,
)

data class McpServiceSettingsUiState(
    val isLoading: Boolean = false,
    val enabled: Boolean = false,
    val status: McpServiceStatus = McpServiceStatus.Stopped,
    val port: Int = RuntimeMcpServiceConfig.DEFAULT_PORT,
    val portInput: String = RuntimeMcpServiceConfig.DEFAULT_PORT.toString(),
    val portError: String? = null,
    val host: String = RuntimeMcpServiceConfig.DEFAULT_HOST,
    val tokenInput: String = "",
    val tokenVisible: Boolean = false,
    val exposedTools: Set<String> = RuntimeMcpServiceConfig.DEFAULT_EXPOSED_TOOLS,
    val keepScreenOn: Boolean = true,
    val tools: List<McpServiceToolItemUi> = emptyList(),
)

sealed interface McpServiceSettingsIntent {
    data object Load : McpServiceSettingsIntent
    data class ToggleServer(val enabled: Boolean) : McpServiceSettingsIntent
    data class PortChanged(val portString: String) : McpServiceSettingsIntent
    data class TokenChanged(val token: String) : McpServiceSettingsIntent
    data object ToggleTokenVisibility : McpServiceSettingsIntent
    data class ToggleTool(val toolName: String, val checked: Boolean) : McpServiceSettingsIntent
    data class ToggleKeepScreenOn(val enabled: Boolean) : McpServiceSettingsIntent
}

sealed interface McpServiceSettingsEffect

class McpServiceSettingsViewModel :
    ComposeMVIViewModel<McpServiceSettingsIntent, McpServiceSettingsUiState, McpServiceSettingsEffect>() {

    private companion object {
        private const val LOG_TAG = "niki914_zafiro_McpServiceSettingsViewModel"
        private const val MIN_PORT = RuntimeMcpServiceConfig.MIN_PORT
        private const val MAX_PORT = RuntimeMcpServiceConfig.MAX_PORT
    }

    private val hostService = requireService<McpService>()

    init {
        viewModelScope.launch {
            hostService.status.collectLatest { liveStatus ->
                updateState { copy(status = liveStatus) }
            }
        }
        viewModelScope.launch {
            XRepo.mcpService.configFlow.collectLatest { config ->
                updateState {
                    copy(
                        enabled = config.enabled,
                        port = config.port,
                        host = config.host,
                        exposedTools = config.exposedTools,
                        keepScreenOn = config.keepScreenOn,
                    )
                }
            }
        }
    }

    override fun initUiState(): McpServiceSettingsUiState = McpServiceSettingsUiState()

    override suspend fun handleIntent(intent: McpServiceSettingsIntent) {
        when (intent) {
            McpServiceSettingsIntent.Load -> load()
            is McpServiceSettingsIntent.ToggleServer -> toggleServer(intent.enabled)
            is McpServiceSettingsIntent.PortChanged -> updatePort(intent.portString)
            is McpServiceSettingsIntent.TokenChanged -> updateToken(intent.token)
            McpServiceSettingsIntent.ToggleTokenVisibility -> updateState {
                copy(tokenVisible = !tokenVisible)
            }

            is McpServiceSettingsIntent.ToggleTool -> toggleTool(intent.toolName, intent.checked)
            is McpServiceSettingsIntent.ToggleKeepScreenOn ->
                XRepo.mcpService.setKeepScreenOnWhileRunning(intent.enabled)
        }
    }

    private suspend fun load() {
        val config = XRepo.mcpService.get()
        val allTools = McpToolCatalog.list()
        updateState {
            copy(
                enabled = config.enabled,
                port = config.port,
                portInput = config.port.toString(),
                portError = null,
                host = config.host,
                tokenInput = config.bearerToken,
                exposedTools = config.exposedTools,
                keepScreenOn = config.keepScreenOn,
                tools = allTools,
            )
        }
    }

    private suspend fun toggleServer(enabled: Boolean) {
        Logger.i(LOG_TAG, "user toggled MCP server enabled: $enabled")
        XRepo.mcpService.setEnabled(enabled)
    }

    private suspend fun updatePort(portString: String) {
        val parsedPort = portString.trim().toIntOrNull()
        if (parsedPort == null || parsedPort !in MIN_PORT..MAX_PORT) {
            updateState {
                copy(
                    portInput = portString,
                    portError = "Port must be between $MIN_PORT and $MAX_PORT",
                )
            }
            return
        }
        updateState {
            copy(
                portInput = portString,
                portError = null,
            )
        }
        XRepo.mcpService.setPort(parsedPort)
    }

    private suspend fun updateToken(token: String) {
        updateState { copy(tokenInput = token) }
        XRepo.mcpService.setBearerToken(token.trim())
    }

    private suspend fun toggleTool(toolName: String, checked: Boolean) {
        val current = currentState.exposedTools
        val updated = if (checked) {
            current + toolName
        } else {
            current - toolName
        }
        XRepo.mcpService.setExposedTools(updated)
    }
}
