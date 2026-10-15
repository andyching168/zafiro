package com.niki914.zafiro.app.monitor

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.niki914.uikit.base.BaseTheme
import com.niki914.zafiro.api.VirtualDisplayService
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ThemeController
import com.niki914.zafiro.mcp.service.TaskLifecycle
import com.niki914.zafiro.mcp.service.TaskSnapshot
import com.niki914.zafiro.mcp.service.TaskSource
import com.niki914.zafiro.service.findService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 只读的虚拟屏观察界面。它只周期性取一帧画面来看，不转发任何触控、不改变虚拟屏的焦点或任务；
 * 返回键与「停止观察」只关闭本界面，任务照常进行。
 */
class TaskMonitorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TaskMonitor.start()
        runBlocking { runCatching { ThemeController.load() } }
        setContent {
            val prefs = ThemeController.prefs
            BaseTheme(
                darkTheme = prefs.resolveDarkTheme(isSystemInDarkTheme()),
                dynamicColor = prefs.seedColor == null,
                seedColor = prefs.seedColor?.let { Color(it) },
            ) {
                TaskMonitorScreen(onClose = { finish() })
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, TaskMonitorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

private const val FRAME_INTERVAL_MS = 1_000L
private const val FRAME_MAX_SIDE = 960
private const val FRAME_QUALITY = 70

@Composable
private fun TaskMonitorScreen(onClose: () -> Unit) {
    val snapshot by TaskMonitor.snapshot.collectAsState()
    var displayId by remember { mutableStateOf(TaskMonitor.displayToWatch()) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                displayId = TaskMonitor.displayToWatch()
                val id = displayId
                val service = findService<VirtualDisplayService>()
                if (id != null && service != null) {
                    service.capture(id, jpeg = true, quality = FRAME_QUALITY, maxSide = FRAME_MAX_SIDE)
                        .getOrNull()
                        ?.let { f ->
                            val bitmap = withContext(Dispatchers.Default) {
                                BitmapFactory.decodeByteArray(f.bytes, 0, f.bytes.size)
                            }
                            if (bitmap != null) frame = bitmap.asImageBitmap()
                        }
                } else {
                    frame = null
                }
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.task_monitor_stop_watching))
                }
                Text(
                    text = stringResource(R.string.task_monitor_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.task_monitor_view_only),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                PreviewFrame(frame = frame, hasDisplay = displayId != null)
            }

            StatusBlock(snapshot = snapshot, displayId = displayId)

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (snapshot.canStop) {
                    TextButton(onClick = { TaskMonitor.stopTask() }) {
                        Text(stringResource(R.string.task_monitor_stop_task))
                    }
                } else {
                    Box {}
                }
                Button(onClick = onClose) {
                    Text(stringResource(R.string.task_monitor_stop_watching))
                }
            }
        }
    }
}

@Composable
private fun PreviewFrame(frame: ImageBitmap?, hasDisplay: Boolean) {
    val shape = RoundedCornerShape(28.dp)
    val bitmap = frame
    val ratio = bitmap?.let { it.width.toFloat() / it.height.toFloat() } ?: 0.5f
    Surface(
        shape = shape,
        border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.aspectRatio(ratio),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(
                        if (hasDisplay) R.string.task_monitor_no_frame else R.string.task_monitor_no_display
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusBlock(snapshot: TaskSnapshot, displayId: Int?) {
    val stateText = stringResource(
        when (snapshot.lifecycle) {
            TaskLifecycle.IDLE -> R.string.task_monitor_state_idle
            TaskLifecycle.RUNNING -> R.string.task_monitor_state_running
            TaskLifecycle.PAUSED -> R.string.task_monitor_state_paused
            TaskLifecycle.COMPLETED -> R.string.task_monitor_state_completed
            TaskLifecycle.FAILED -> R.string.task_monitor_state_failed
            TaskLifecycle.STOPPED -> R.string.task_monitor_state_stopped
        }
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stateText,
            style = MaterialTheme.typography.titleMedium,
        )
        val detail = snapshot.detail
        val lastCall = snapshot.lastCall
        val line = when {
            detail != null -> detail
            snapshot.lifecycle == TaskLifecycle.RUNNING && snapshot.source == TaskSource.REMOTE_MCP ->
                stringResource(R.string.task_monitor_waiting_next)

            else -> null
        }
        if (line != null) {
            Text(text = line, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        if (lastCall != null) {
            Text(
                text = stringResource(R.string.task_monitor_last_call, lastCall.toolName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (displayId != null) {
            Text(
                text = stringResource(R.string.task_monitor_display, displayId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (snapshot.canStop && snapshot.source == TaskSource.REMOTE_MCP) {
            Text(
                text = stringResource(R.string.task_monitor_stop_remote_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
