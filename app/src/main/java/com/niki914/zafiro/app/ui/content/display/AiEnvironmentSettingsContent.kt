package com.niki914.zafiro.app.ui.content.display

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.component.SettingsListItem
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.api.VirtualDisplayState
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.AiEnvironmentIntent
import com.niki914.zafiro.app.ui.model.AiEnvironmentViewModel

@Composable
fun AiEnvironmentSettingsContent(
    viewModel: AiEnvironmentViewModel = pageViewModel(),
) {
    val ui by viewModel.uiStateFlow.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.sendIntent(AiEnvironmentIntent.Load)
    }

    SettingsListPageContent {
        SettingsGroupCard(title = stringResource(R.string.ai_env_section_mode)) {
            SettingToggleItem(
                title = stringResource(R.string.ai_env_background_title),
                description = stringResource(R.string.ai_env_background_summary),
                checked = ui.background,
                onCheckedChange = { viewModel.sendIntent(AiEnvironmentIntent.SetBackground(it)) },
            )
        }

        SettingsGroupCard(title = stringResource(R.string.ai_env_section_display)) {
            val stateText = when (ui.state) {
                VirtualDisplayState.Stopped -> stringResource(R.string.ai_env_state_stopped)
                VirtualDisplayState.Starting -> stringResource(R.string.ai_env_state_starting)
                is VirtualDisplayState.Running -> stringResource(R.string.ai_env_state_running)
                is VirtualDisplayState.Error -> stringResource(R.string.ai_env_state_error)
            }
            val first = ui.displays.firstOrNull()
            SettingsListItem(
                title = stringResource(R.string.ai_env_state_title),
                currentState = stateText,
                summary = ui.error ?: first?.let {
                    stringResource(R.string.ai_env_display_info, it.displayId, it.width, it.height, it.dpi)
                },
            )
            SettingsItemDivider()
            if (ui.displays.isEmpty()) {
                SettingsListItem(
                    title = stringResource(R.string.ai_env_create_title),
                    summary = stringResource(R.string.ai_env_create_summary),
                    onClick = if (ui.busy) null else {
                        { viewModel.sendIntent(AiEnvironmentIntent.CreateDisplay) }
                    },
                )
            } else {
                SettingsListItem(
                    title = stringResource(R.string.ai_env_close_title),
                    summary = stringResource(R.string.ai_env_close_summary),
                    onClick = if (ui.busy) null else {
                        { viewModel.sendIntent(AiEnvironmentIntent.CloseDisplays) }
                    },
                )
            }
        }

        SettingsGroupCard(title = stringResource(R.string.ai_env_section_task)) {
            SettingsListItem(
                title = stringResource(R.string.ai_env_task_title),
                currentState = if (ui.running) stringResource(R.string.ai_env_task_running)
                else stringResource(R.string.ai_env_task_idle),
            )
            SettingsItemDivider()
            SettingsListItem(
                title = stringResource(R.string.ai_env_stop_title),
                summary = stringResource(R.string.ai_env_stop_summary),
                onClick = { viewModel.sendIntent(AiEnvironmentIntent.StopTask) },
            )
        }

        val preview = ui.preview
        if (preview != null) {
            SettingsGroupCard(title = stringResource(R.string.ai_env_section_preview)) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            }
        }
    }
}
