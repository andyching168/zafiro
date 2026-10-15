package com.niki914.zafiro.mcp.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 用户在手机上按了停止：在途工具被取消，或紧随其后的一次请求被拒绝。 */
class UserAbortedException : Exception("The user pressed stop on the phone")

/**
 * 外部 MCP 客户端（HTTP 端点或 Gateway）正在操作手机的进程内信号。
 * UI 层据此点亮「AI 正在操作」的屏幕边缘光效并保持屏幕常亮；截屏期间由 [withOverlaysHidden]
 * 让光效让路，保证 Agent 看到的画面不含装饰层。
 *
 * 「操作中」= 有工具调用在途，或客户端用 phone_operation_start 显式开了一段操作会话。
 * 会话有空闲 TTL：期间没有任何工具调用就自动结束，避免客户端忘记 end 导致光效与常亮永久滞留。
 *
 * 停止语义（[requestStop]）：有工具在跑就取消它们（调用方得到 [UserAbortedException]）；
 * 没有就让「下一个」请求先失败一次，由 Agent 决定重试还是询问用户。
 */
object RemoteOperationTracker {
    const val DEFAULT_SESSION_TTL_MS = 120_000L
    private const val ABORT_NEXT_VALID_MS = 60_000L
    private const val HIDE_SETTLE_MS = 150L
    private const val ENGAGED_LINGER_MS = 3_000L

    /** 距最近一次实体屏操作多久内仍视为「AI 正在操作实体屏」，要覆盖相邻两次操作间模型思考的空档。 */
    const val PHYSICAL_ACTION_WINDOW_MS = 20_000L

    private val running = AtomicInteger(0)
    private val capturing = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val inflight = ConcurrentHashMap.newKeySet<Deferred<*>>()
    private val userAborted = ConcurrentHashMap.newKeySet<Deferred<*>>()
    private val abortNextUntil = AtomicLong(0L)
    private val lastPhysicalActionAt = AtomicLong(0L)

    private val lock = Any()
    private var sessionTtlMs = DEFAULT_SESSION_TTL_MS
    private var sessionDeadline = 0L
    private var session = false
    private var watchdog: Job? = null

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _sessionActive = MutableStateFlow(false)

    /** 客户端显式开启的操作会话（触控锁只跟它走，不跟单次调用走，避免来回闪锁）。 */
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private val _lastCall = MutableStateFlow<ToolCallRecord?>(null)

    /** 最近一次屏幕类工具调用（phone_operation_start/end 不计）。 */
    val lastCall: StateFlow<ToolCallRecord?> = _lastCall.asStateFlow()

    private val _lastVirtualDisplayId = MutableStateFlow<Int?>(null)

    /** AI 最近一次操作的受管虚拟屏，观察界面据此选择要看的屏。 */
    val lastVirtualDisplayId: StateFlow<Int?> = _lastVirtualDisplayId.asStateFlow()

    private val _sessionEnd = MutableStateFlow<SessionEnd?>(null)

    /** 最近一段显式会话是怎么结束的：客户端 end / 空闲超时，还是用户停止。新会话开始时清空。 */
    val sessionEnd: StateFlow<SessionEnd?> = _sessionEnd.asStateFlow()

    private val _engaged = MutableStateFlow(false)

    /** [active] 的带尾巴版本：零散调用之间的短暂空档不算「停了」，避免通知与界面抖动。 */
    val engaged: StateFlow<Boolean> = _engaged.asStateFlow()

    private var lingerJob: Job? = null

    private val _overlaysSuppressed = MutableStateFlow(false)
    val overlaysSuppressed: StateFlow<Boolean> = _overlaysSuppressed.asStateFlow()

    private val _touchLocked = MutableStateFlow(false)

    /** 由触控锁实现回报：UI 据此显示「按音量下键停止」提示。 */
    val touchLocked: StateFlow<Boolean> = _touchLocked.asStateFlow()

    fun setTouchLocked(locked: Boolean) {
        _touchLocked.value = locked
    }

    private fun publish() {
        _sessionActive.value = synchronized(lock) { session }
        _active.value = running.get() > 0 || _sessionActive.value
        if (_active.value) {
            lingerJob?.cancel()
            lingerJob = null
            _engaged.value = true
        } else if (_engaged.value && lingerJob == null) {
            lingerJob = scope.launch {
                delay(ENGAGED_LINGER_MS)
                lingerJob = null
                _engaged.value = _active.value
            }
        }
    }

    fun noteCallStarted(toolName: String) {
        _lastCall.value = ToolCallRecord(toolName, null, CallState.Running, System.currentTimeMillis())
    }

    fun noteCallFinished(toolName: String, state: CallState) {
        val previous = _lastCall.value
        val display = previous?.takeIf { it.toolName == toolName }?.displayId
        _lastCall.value = ToolCallRecord(toolName, display, state, System.currentTimeMillis())
    }

    /**
     * [abortable] 为 false 的调用（phone_operation_start/end）不受停止影响，
     * 否则用户按下停止后客户端连「结束会话」都做不了。
     */
    suspend fun <T> track(abortable: Boolean = true, block: suspend () -> T): T {
        if (abortable && consumeAbortNext()) throw UserAbortedException()
        running.incrementAndGet()
        touchSession()
        publish()
        try {
            return coroutineScope {
                val work = async { block() }
                if (abortable) inflight.add(work)
                try {
                    work.await()
                } catch (e: CancellationException) {
                    if (work in userAborted) throw UserAbortedException()
                    throw e
                } finally {
                    inflight.remove(work)
                    userAborted.remove(work)
                }
            }
        } finally {
            running.decrementAndGet()
            touchSession()
            publish()
        }
    }

    private fun consumeAbortNext(): Boolean {
        val until = abortNextUntil.get()
        if (until == 0L) return false
        if (!abortNextUntil.compareAndSet(until, 0L)) return false
        return System.currentTimeMillis() <= until
    }

    /** 用户在手机上按下停止：结束会话，并取消在途调用（没有在途调用则让下一个请求失败一次）。 */
    fun requestStop() {
        val live = inflight.toList()
        if (live.isEmpty()) {
            abortNextUntil.set(System.currentTimeMillis() + ABORT_NEXT_VALID_MS)
        } else {
            live.forEach {
                userAborted.add(it)
                it.cancel()
            }
        }
        endSession(SessionEndReason.Stopped)
    }

    /**
     * 屏幕工具解析出目标屏幕后调用。只有针对实体屏（Display 0）的操作才会让触控锁有意义；
     * 全程在虚拟屏上操作时这里永远不会被点亮，触控锁也就不会动作。
     */
    fun noteDisplayTarget(displayId: Int) {
        if (displayId == 0) lastPhysicalActionAt.set(System.currentTimeMillis())
        else _lastVirtualDisplayId.value = displayId
        _lastCall.value = _lastCall.value?.let { if (it.state == CallState.Running) it.copy(displayId = displayId) else it }
    }

    /** 此刻是否有理由挡住实体屏的误触：会话进行中，且最近确实在操作实体屏。 */
    fun physicalTouchDemand(now: Long = System.currentTimeMillis()): Boolean {
        if (!_sessionActive.value) return false
        val last = lastPhysicalActionAt.get()
        return last != 0L && now - last <= PHYSICAL_ACTION_WINDOW_MS
    }

    fun beginSession(ttlMs: Long = DEFAULT_SESSION_TTL_MS) {
        lastPhysicalActionAt.set(0L)
        _sessionEnd.value = null
        synchronized(lock) {
            sessionTtlMs = ttlMs
            session = true
            sessionDeadline = System.currentTimeMillis() + ttlMs
            if (watchdog?.isActive != true) {
                watchdog = scope.launch { watch() }
            }
        }
        publish()
    }

    fun endSession(reason: SessionEndReason = SessionEndReason.Completed) {
        lastPhysicalActionAt.set(0L)
        synchronized(lock) {
            if (session) _sessionEnd.value = SessionEnd(reason, System.currentTimeMillis())
            session = false
            watchdog?.cancel()
            watchdog = null
        }
        publish()
    }

    private fun touchSession() {
        synchronized(lock) {
            if (session) sessionDeadline = System.currentTimeMillis() + sessionTtlMs
        }
    }

    private suspend fun watch() {
        while (true) {
            val wait = synchronized(lock) {
                if (!session) return
                sessionDeadline - System.currentTimeMillis()
            }
            if (wait <= 0) {
                if (running.get() == 0) {
                    endSession()
                    return
                }
                touchSession()
            } else {
                delay(wait)
            }
        }
    }

    /** 抓屏前隐藏装饰层并等它真正消失；光效没亮时不引入任何延迟。 */
    suspend fun <T> withOverlaysHidden(block: suspend () -> T): T {
        val needSettle = _active.value
        capturing.incrementAndGet()
        _overlaysSuppressed.value = true
        try {
            if (needSettle) delay(HIDE_SETTLE_MS)
            return block()
        } finally {
            _overlaysSuppressed.value = capturing.decrementAndGet() > 0
        }
    }
}
