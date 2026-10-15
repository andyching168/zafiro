package com.niki914.zafiro.app.ui.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.api.ExecutionEnvironment
import com.niki914.zafiro.api.VirtualDisplayInfo
import com.niki914.zafiro.api.VirtualDisplayService
import com.niki914.zafiro.api.VirtualDisplayState
import com.niki914.zafiro.mcp.service.RemoteOperationTracker
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AiEnvironmentUiState(
    val background: Boolean = false,
    val state: VirtualDisplayState = VirtualDisplayState.Stopped,
    val displays: List<VirtualDisplayInfo> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val running: Boolean = false,
    val preview: Bitmap? = null,
)

sealed interface AiEnvironmentIntent {
    data object Load : AiEnvironmentIntent
    data class SetBackground(val enabled: Boolean) : AiEnvironmentIntent
    data object CreateDisplay : AiEnvironmentIntent
    data object CloseDisplays : AiEnvironmentIntent
    data object StopTask : AiEnvironmentIntent
}

sealed interface AiEnvironmentEffect

class AiEnvironmentViewModel :
    ComposeMVIViewModel<AiEnvironmentIntent, AiEnvironmentUiState, AiEnvironmentEffect>() {

    private companion object {
        private const val LOG_TAG = "niki914_zafiro_AiEnvironmentViewModel"
        private const val PREVIEW_INTERVAL_MS = 1_000L
        private const val PREVIEW_MAX_SIDE = 960
    }

    private val service = requireService<VirtualDisplayService>()
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            service.state.collectLatest { s ->
                updateState {
                    copy(
                        state = s,
                        displays = (s as? VirtualDisplayState.Running)?.displays ?: emptyList(),
                        error = (s as? VirtualDisplayState.Error)?.message ?: error,
                    )
                }
            }
        }
        viewModelScope.launch {
            RemoteOperationTracker.active.collectLatest { updateState { copy(running = it) } }
        }
        viewModelScope.launch {
            XRepo.backgroundDisplayModeSetting.collectLatest { updateState { copy(background = it) } }
        }
        startPreview()
    }

    override fun initUiState(): AiEnvironmentUiState = AiEnvironmentUiState()

    override suspend fun handleIntent(intent: AiEnvironmentIntent) {
        when (intent) {
            AiEnvironmentIntent.Load -> XRepo.backgroundDisplayMode()
            is AiEnvironmentIntent.SetBackground -> XRepo.setBackgroundDisplayMode(intent.enabled)
            AiEnvironmentIntent.CreateDisplay -> {
                updateState { copy(busy = true, error = null) }
                service.create().onFailure { e ->
                    Logger.w(LOG_TAG, "create failed: ${e.message}")
                    updateState { copy(error = e.message ?: "Failed to create the virtual display") }
                }
                updateState { copy(busy = false) }
            }

            AiEnvironmentIntent.CloseDisplays -> {
                updateState { copy(busy = true, error = null, preview = null) }
                service.destroyAll()
                updateState { copy(busy = false) }
            }

            AiEnvironmentIntent.StopTask -> RemoteOperationTracker.requestStop()
        }
    }

    /** 页面存活期间每秒取一帧虚拟屏画面做预览；没有虚拟屏就不取。 */
    private fun startPreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            while (isActive) {
                val display = service.displays().firstOrNull()
                if (display != null) {
                    service.capture(display.displayId, jpeg = true, quality = 60, maxSide = PREVIEW_MAX_SIDE)
                        .onSuccess { frame ->
                            val bmp = BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size)
                            updateState { copy(preview = bmp) }
                        }
                } else if (currentState.preview != null) {
                    updateState { copy(preview = null) }
                }
                delay(PREVIEW_INTERVAL_MS)
            }
        }
    }

    override fun onCleared() {
        previewJob?.cancel()
        super.onCleared()
    }
}
