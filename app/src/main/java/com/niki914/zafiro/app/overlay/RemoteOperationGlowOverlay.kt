package com.niki914.zafiro.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.niki914.logging.Logger
import com.niki914.zafiro.app.R
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.mod.feat.ZafiroAccessibilityService
import com.niki914.zafiro.mcp.service.RemoteOperationTracker
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * 外部 MCP 操作期间的屏幕边缘流光：全屏、不拦截触摸、不可聚焦、对无障碍隐藏；
 * 窗口自带 FLAG_KEEP_SCREEN_ON，所以无论当前前台是哪个 App，操作期间屏幕都不会变暗。
 * 截屏期间由 [RemoteOperationTracker.overlaysSuppressed] 立即隐藏，不进入 Agent 看到的画面。
 */
object RemoteOperationGlowOverlay {
    private const val TAG = "niki914_zafiro_RemoteOperationGlow"
    private const val LINGER_MS = 1_200L
    private const val FADE_MS = 350L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var glowView: GlowView? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        attachHintObserver(app, scope)
        scope.launch {
            val shown = RemoteOperationTracker.active.transformLatest { active ->
                if (active) {
                    emit(true)
                } else {
                    delay(LINGER_MS)
                    emit(false)
                }
            }
            combine(shown, RemoteOperationTracker.overlaysSuppressed) { s, suppressed ->
                s to suppressed
            }.distinctUntilChanged().collect { (visible, suppressed) ->
                // 外部 MCP 没有「回合结束」事件：操作收尾时由这里替它收回指针游标
                if (!visible) AccessibilityController.onTurnEnd()
                mainHandler.post { render(app, visible, suppressed) }
            }
        }
    }

    private fun render(context: Context, visible: Boolean, suppressed: Boolean) {
        if (!visible) {
            glowView?.let { view ->
                view.animate().cancel()
                view.animate().alpha(0f).setDuration(FADE_MS).withEndAction { detach() }.start()
            }
            return
        }
        val view = glowView ?: attach(context) ?: return
        view.animate().cancel()
        if (suppressed) {
            view.alpha = 0f
        } else {
            view.animate().alpha(1f).setDuration(FADE_MS).start()
        }
    }

    private fun attachHintObserver(context: Context, scope: CoroutineScope) {
        scope.launch {
            RemoteOperationTracker.touchLocked.collect { locked ->
                mainHandler.post {
                    glowView?.setHint(
                        if (locked) context.getString(R.string.remote_operation_stop_hint) else null
                    )
                }
            }
        }
    }

    /**
     * 优先用无障碍服务的 TYPE_ACCESSIBILITY_OVERLAY：它画在状态栏与导航栏之上，才能真正铺满屏幕；
     * 普通 TYPE_APPLICATION_OVERLAY 在导航栏之下，会在底部留出一条。服务不在线时退回后者（需悬浮窗权限）。
     */
    private fun attach(context: Context): GlowView? {
        val service = ZafiroAccessibilityService.instance
        val type: Int
        val host: Context
        if (service != null) {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            host = service
        } else {
            val granted = runCatching {
                requireService<PermissionManager>().status(Permission.OVERLAY) == PermissionState.GRANTED
            }.getOrDefault(false)
            if (!granted) return null
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            host = context
        }
        val wm = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = GlowView(host).apply {
            setHint(
                if (RemoteOperationTracker.touchLocked.value)
                    context.getString(R.string.remote_operation_stop_hint) else null
            )
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
        }
        return try {
            wm.addView(view, lp)
            windowManager = wm
            glowView = view
            view.startAnimation()
            view
        } catch (e: Exception) {
            Logger.w(TAG, "failed to add glow overlay: ${e.message}")
            null
        }
    }

    /** 无障碍服务被销毁前调用：窗口挂在服务 Context 上，必须随服务一起撤掉。 */
    fun detachNow() {
        mainHandler.post { detach() }
    }

    private fun detach() {
        val view = glowView ?: return
        view.stopAnimation()
        runCatching { windowManager?.removeViewImmediate(view) }
        glowView = null
        windowManager = null
    }

    private class GlowView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val colors = intArrayOf(
            Color.parseColor("#4285F4"), Color.parseColor("#9B72CB"), Color.parseColor("#D96570"),
            Color.parseColor("#FBBC04"), Color.parseColor("#34A853"), Color.parseColor("#4285F4"),
        )
        // 由外向内叠加的描边层（dp, alpha）：宽而淡的外晕 + 窄而亮的内芯，硬件加速即可，无需模糊滤镜
        private val layers = listOf(34f to 0.10f, 22f to 0.16f, 13f to 0.30f, 6f to 0.60f, 2.5f to 0.95f)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()
        private val matrix = Matrix()
        private var shader: SweepGradient? = null
        private var angle = 0f
        private var breath = 1f
        private var spin: ValueAnimator? = null
        private var pulse: ValueAnimator? = null

        private var hint: String? = null
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 15f * density
            setShadowLayer(4f * density, 0f, 0f, Color.BLACK)
        }

        fun setHint(text: String?) {
            hint = text
            invalidate()
        }

        fun startAnimation() {
            spin = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 4_000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { angle = it.animatedValue as Float; invalidate() }
                start()
            }
            pulse = ValueAnimator.ofFloat(0.75f, 1f).apply {
                duration = 1_600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { breath = it.animatedValue as Float }
                start()
            }
        }

        fun stopAnimation() {
            spin?.cancel(); pulse?.cancel()
            spin = null; pulse = null
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            shader = SweepGradient(w / 2f, h / 2f, colors, null)
        }

        /** 取屏幕真实圆角（API 31+）；取不到时用接近常见机型的默认值。 */
        private fun screenCornerRadius(): Float {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val insets = rootWindowInsets
                val r = listOf(
                    RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                    RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT,
                ).maxOfOrNull { insets?.getRoundedCorner(it)?.radius ?: 0 } ?: 0
                if (r > 0) return r.toFloat()
            }
            return 44f * density
        }

        override fun onDraw(canvas: Canvas) {
            val sh = shader ?: return
            matrix.setRotate(angle, width / 2f, height / 2f)
            sh.setLocalMatrix(matrix)
            paint.shader = sh
            val corner = screenCornerRadius()
            for ((widthDp, alpha) in layers) {
                val w = widthDp * density
                paint.strokeWidth = w
                paint.alpha = (alpha * breath * 255).toInt().coerceIn(0, 255)
                rect.set(w / 2f, w / 2f, width - w / 2f, height - w / 2f)
                canvas.drawRoundRect(rect, corner, corner, paint)
            }
            hint?.let {
                canvas.drawText(it, width / 2f, height - 64f * density, hintPaint)
            }
        }
    }
}
