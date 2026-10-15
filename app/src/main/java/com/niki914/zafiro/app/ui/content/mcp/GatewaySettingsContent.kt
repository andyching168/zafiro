package com.niki914.zafiro.app.ui.content.mcp

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.component.SettingsListItem
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.api.GatewayStatus
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.content.SettingControlledExpandableTextItem
import com.niki914.zafiro.app.ui.content.rememberEditableDetailFieldController
import com.niki914.zafiro.app.ui.model.GatewayPairingUi
import com.niki914.zafiro.app.ui.model.GatewaySettingsIntent
import com.niki914.zafiro.app.ui.model.GatewaySettingsViewModel

private enum class GatewayEditableField {
    Url,
    DeviceId,
    Name,
    Secret,
}

@Composable
fun GatewaySettingsContent(
    viewModel: GatewaySettingsViewModel = pageViewModel(),
) {
    val uiState by viewModel.uiStateFlow.collectAsState()
    val fieldController = rememberEditableDetailFieldController<GatewayEditableField>(
        requestedFocusField = null,
        onRequestedFocusHandled = {},
    )

    LaunchedEffect(Unit) {
        viewModel.sendIntent(GatewaySettingsIntent.Load)
    }

    SettingsListPageContent {
        SettingsGroupCard(title = stringResource(R.string.gateway_section_connection_state)) {
            SettingToggleItem(
                title = stringResource(R.string.gateway_enable_title),
                description = stringResource(R.string.gateway_enable_summary),
                checked = uiState.enabled,
                onCheckedChange = { checked ->
                    fieldController.clearActiveField()
                    viewModel.sendIntent(GatewaySettingsIntent.ToggleEnabled(checked))
                },
            )
            SettingsItemDivider()
            val statusText = when (uiState.status) {
                GatewayStatus.Disconnected -> stringResource(R.string.gateway_status_disconnected)
                GatewayStatus.Connecting -> stringResource(R.string.gateway_status_connecting)
                is GatewayStatus.Connected -> stringResource(R.string.gateway_status_connected)
                is GatewayStatus.Reconnecting -> stringResource(R.string.gateway_status_reconnecting)
                is GatewayStatus.Error -> stringResource(R.string.gateway_status_error)
            }
            SettingsListItem(
                title = stringResource(R.string.gateway_status_title),
                currentState = statusText,
                summary = (uiState.status as? GatewayStatus.Error)?.message,
            )
            SettingsItemDivider()
            SettingsListItem(
                title = stringResource(R.string.gateway_reconnect_title),
                summary = stringResource(R.string.gateway_reconnect_summary),
                onClick = { viewModel.sendIntent(GatewaySettingsIntent.Reconnect) },
            )
            SettingsItemDivider()
            SettingToggleItem(
                title = stringResource(R.string.gateway_auto_connect_title),
                description = stringResource(R.string.gateway_auto_connect_summary),
                checked = uiState.autoConnect,
                onCheckedChange = { checked ->
                    fieldController.clearActiveField()
                    viewModel.sendIntent(GatewaySettingsIntent.ToggleAutoConnect(checked))
                },
            )
        }

        SettingsGroupCard(title = stringResource(R.string.gateway_section_device)) {
            val pairing = uiState.pairing
            SettingsListItem(
                title = stringResource(R.string.gateway_pair_title),
                currentState = when (pairing) {
                    GatewayPairingUi.Idle -> null
                    GatewayPairingUi.Requesting -> stringResource(R.string.gateway_pair_requesting)
                    is GatewayPairingUi.WaitingApproval -> pairing.code
                    GatewayPairingUi.Paired -> stringResource(R.string.gateway_pair_done)
                    is GatewayPairingUi.Failed -> stringResource(R.string.gateway_status_error)
                },
                summary = when (pairing) {
                    is GatewayPairingUi.WaitingApproval ->
                        stringResource(R.string.gateway_pair_waiting_summary)
                    is GatewayPairingUi.Failed -> pairing.message
                    else -> stringResource(R.string.gateway_pair_summary)
                },
                onClick = { viewModel.sendIntent(GatewaySettingsIntent.Pair) },
            )
            SettingsItemDivider()
            SettingControlledExpandableTextItem(
                field = GatewayEditableField.Url,
                controller = fieldController,
                title = stringResource(R.string.gateway_url_title),
                value = uiState.urlInput,
                onValueChange = { viewModel.sendIntent(GatewaySettingsIntent.UrlChanged(it)) },
                placeholder = stringResource(R.string.gateway_url_placeholder),
                description = stringResource(R.string.gateway_url_description),
                enabled = true,
                minLines = 1,
                maxLines = 1,
            )
            SettingsItemDivider()
            SettingControlledExpandableTextItem(
                field = GatewayEditableField.DeviceId,
                controller = fieldController,
                title = stringResource(R.string.gateway_device_id_title),
                value = uiState.deviceIdInput,
                onValueChange = { viewModel.sendIntent(GatewaySettingsIntent.DeviceIdChanged(it)) },
                placeholder = stringResource(R.string.gateway_device_id_placeholder),
                enabled = true,
                minLines = 1,
                maxLines = 1,
            )
            SettingsItemDivider()
            SettingControlledExpandableTextItem(
                field = GatewayEditableField.Secret,
                controller = fieldController,
                title = stringResource(R.string.gateway_secret_title),
                value = uiState.secretInput,
                onValueChange = { viewModel.sendIntent(GatewaySettingsIntent.SecretChanged(it)) },
                placeholder = stringResource(R.string.gateway_secret_placeholder),
                description = if (uiState.secretConfigured) {
                    stringResource(R.string.gateway_secret_description_configured)
                } else {
                    stringResource(R.string.gateway_secret_description_empty)
                },
                enabled = true,
                minLines = 1,
                maxLines = 1,
                secretVisible = uiState.secretVisible,
                onToggleSecretVisibility = {
                    viewModel.sendIntent(GatewaySettingsIntent.ToggleSecretVisibility)
                },
                toggleSecretVisibleContentDescription = stringResource(R.string.gateway_secret_show),
                toggleSecretHiddenContentDescription = stringResource(R.string.gateway_secret_hide),
            )
            SettingsItemDivider()
            SettingControlledExpandableTextItem(
                field = GatewayEditableField.Name,
                controller = fieldController,
                title = stringResource(R.string.gateway_name_title),
                value = uiState.nameInput,
                onValueChange = { viewModel.sendIntent(GatewaySettingsIntent.NameChanged(it)) },
                placeholder = stringResource(R.string.gateway_name_placeholder),
                enabled = true,
                minLines = 1,
                maxLines = 1,
            )
        }

        SettingsGroupCard(title = stringResource(R.string.gateway_section_operation)) {
            SettingToggleItem(
                title = stringResource(R.string.gateway_lock_touch_title),
                description = stringResource(R.string.gateway_lock_touch_summary),
                checked = uiState.lockTouch,
                onCheckedChange = { viewModel.sendIntent(GatewaySettingsIntent.ToggleLockTouch(it)) },
            )
        }

        SettingsGroupCard(title = stringResource(R.string.gateway_section_tools)) {
            Text(
                text = stringResource(R.string.gateway_tools_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            uiState.tools.forEachIndexed { index, tool ->
                if (index > 0) SettingsItemDivider()
                SettingToggleItem(
                    title = tool.displayName,
                    description = tool.description,
                    checked = tool.name in uiState.exposedTools,
                    onCheckedChange = { checked ->
                        viewModel.sendIntent(GatewaySettingsIntent.ToggleTool(tool.name, checked))
                    },
                )
            }
        }
    }
}
