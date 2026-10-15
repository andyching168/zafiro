package com.niki914.zafiro.mcp.service

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.AgentTenantContext
import com.niki914.zafiro.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * 用 root 关闭触摸屏的内核输入设备（`/sys/class/input/inputN/inhibited`）。
 *
 * 实体触摸在内核层被丢弃；AI 的 `dispatchGesture` / `input tap` 走 InputManager 注入，
 * 不经过这个设备，因此完全不受影响，也不需要任何覆盖层。节点通过 `properties` 里的
 * INPUT_PROP_DIRECT 位识别触摸屏，不写死编号。
 *
 * 兜底：每次上锁都在 root 侧起一个独立计时进程，[FAILSAFE_SECONDS] 秒后自行写回 0。
 * App 被杀、卡死都会在这个时限内自动放行；正常期间由调用方每 [HEARTBEAT_MS] 续期。
 */
object RemoteTouchLock {
    private const val TAG = "niki914_zafiro_RemoteTouchLock"
    const val HEARTBEAT_MS = 10_000L
    private const val FAILSAFE_SECONDS = 30
    private const val PID_FILE = "/data/local/tmp/zafiro_touch_failsafe.pid"
    private const val COMMAND_TIMEOUT_MS = 5_000L

    private val FIND_NODE = """
        n=""
        for d in /sys/class/input/input*; do
          [ -e "${'$'}d/inhibited" ] || continue
          p=${'$'}(cat "${'$'}d/properties" 2>/dev/null)
          [ -n "${'$'}p" ] || continue
          [ ${'$'}(( p & 2 )) -ne 0 ] || continue
          n="${'$'}d"; break
        done
        [ -n "${'$'}n" ] || exit 3
    """.trimIndent()

    private val KILL_FAILSAFE = """[ -f $PID_FILE ] && kill ${'$'}(cat $PID_FILE) 2>/dev/null"""

    /** 上锁并（重新）起 [FAILSAFE_SECONDS] 秒的放行计时。返回是否成功。 */
    suspend fun lock(): Boolean = run(
        """
        $FIND_NODE
        $KILL_FAILSAFE
        echo 1 > "${'$'}n/inhibited" || exit 4
        nohup sh -c "sleep $FAILSAFE_SECONDS; echo 0 > ${'$'}n/inhibited" >/dev/null 2>&1 </dev/null &
        echo ${'$'}! > $PID_FILE
        """.trimIndent()
    )

    suspend fun unlock(): Boolean = run(
        """
        $FIND_NODE
        $KILL_FAILSAFE
        echo 0 > "${'$'}n/inhibited"
        """.trimIndent()
    )

    private const val TENANT = "remote-touch-lock"

    private var session: String? = null

    /** 经统一的终端会话池以 root 身份执行（权限链与 Agent 的 shell 一致，不直连 su）。 */
    private suspend fun run(script: String): Boolean = withContext(AgentTenantContext(TENANT)) {
        try {
            val handle = session ?: (TerminalSessionPool.open("root") as? TerminalOpenOutcome.Success)
                ?.session?.also { session = it }
                ?: run {
                    Logger.w(TAG, "root shell unavailable")
                    return@withContext false
                }
            when (val outcome = TerminalSessionPool.executeBlocking(handle, script, COMMAND_TIMEOUT_MS)) {
                is TerminalCommandOutcome.Success -> {
                    val code = outcome.result.exitCode ?: -1
                    if (code != 0) Logger.w(TAG, "touch lock command failed code=$code")
                    code == 0
                }

                else -> {
                    session = null
                    Logger.w(TAG, "touch lock command did not complete: $outcome")
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session = null
            Logger.w(TAG, "touch lock command error: ${e.message}")
            false
        }
    }
}
