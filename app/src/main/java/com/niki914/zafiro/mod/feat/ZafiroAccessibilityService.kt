package com.niki914.zafiro.mod.feat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.niki914.logging.Logger
import com.niki914.zafiro.app.overlay.PointerOverlay
import com.niki914.zafiro.app.overlay.RemoteOperationController
import com.niki914.zafiro.app.overlay.RemoteOperationGlowOverlay
import com.niki914.zafiro.mcp.service.RemoteOperationTracker
import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import com.niki914.zafiro.chat.agentic.accessibility.IAccessibility

class ZafiroAccessibilityService : AccessibilityService(), IAccessibility {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AccessibilityController.setService(this)
        AccessibilityController.clearPointerOverlay()
        val overlay = PointerOverlay()
        overlay.init(this)
        AccessibilityController.pointerOverlay = overlay
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
            || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            AccessibilityController.recordUiEvent()
        }
    }

    /** 操作会话进行中，音量下键作为「停止」；其余时间原样放行，不影响正常调音量。 */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!RemoteOperationTracker.sessionActive.value) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            RemoteOperationController.stopFromUser("volume down")
        }
        return true
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onDestroy() {
        instance = null
        RemoteOperationGlowOverlay.detachNow()
        AccessibilityController.clearPointerOverlay()
        AccessibilityController.clearService()
        super.onDestroy()
    }

    // -- IAccessibility implementation --

    override val windowRoot: AccessibilityNodeInfo?
        get() = rootInActiveWindow

    override fun performAction(
        node: AccessibilityNodeInfo,
        action: Int,
        text: String?,
    ): Boolean {
        return if (action == AccessibilityNodeInfo.ACTION_SET_TEXT && text != null) {
            val bundle = Bundle()
            bundle.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
            node.performAction(action, bundle)
        } else {
            node.performAction(action)
        }
    }

    override fun dispatchGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return super.dispatchGesture(gesture, null, null)
    }

    override fun captureScreenImage(listener: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Logger.w(TAG, "captureScreenImage needs API 30+")
            listener(null)
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = runCatching {
                    Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                }.getOrNull()
                // Bitmap 自己持有 buffer 引用，这一份引用随即释放
                runCatching { buffer.close() }
                listener(bitmap)
            }

            override fun onFailure(errorCode: Int) {
                Logger.w(TAG, "takeScreenshot failed: errorCode=$errorCode")
                listener(null)
            }
        })
    }

    companion object {
        private const val TAG = "niki914_zafiro_AccessibilityService"

        @Volatile
        var instance: ZafiroAccessibilityService? = null
            private set
    }
}
