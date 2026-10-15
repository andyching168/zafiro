package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.api.VirtualDisplayService
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.device.AppInfoProvider
import com.niki914.zafiro.chat.agentic.device.AppMatchResult
import com.niki914.zafiro.chat.agentic.display.PHYSICAL_DISPLAY_ID
import com.niki914.zafiro.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.service.findService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private const val LOG_TAG = "niki914_zafiro_MoveAppBuiltins"
private const val SHELL_TIMEOUT_MS = 15_000L

internal data class AppTask(val displayId: Int, val taskId: Int, val packageName: String)

private val DISPLAY_HEADER = Regex("""^Display #(\d+)\b""")
private val ROOT_TASK = Regex("""^ {2}\* Task\{\S+ #(\d+) type=standard (?:A=\d+:|I=)([\w.]+)""")

/** 解析 `dumpsys activity activities` 的 Display / 根任务行，顺序即各屏由上到下的任务顺序。 */
internal fun parseAppTasks(dump: String): List<AppTask> {
    var displayId = -1
    val tasks = mutableListOf<AppTask>()
    for (line in dump.lineSequence()) {
        val header = DISPLAY_HEADER.find(line)
        if (header != null) {
            displayId = header.groupValues[1].toInt()
            continue
        }
        if (displayId < 0) continue
        val match = ROOT_TASK.find(line) ?: continue
        tasks += AppTask(displayId, match.groupValues[1].toInt(), match.groupValues[2])
    }
    return tasks
}

private suspend fun runPrivilegedShell(command: String): Result<String> {
    var lastError = "No root or Shizuku shell is available."
    for (identity in listOf("root", "shizuku")) {
        val outcome = try {
            TerminalSessionPool.openAndExecute(
                identity = identity,
                cwd = null,
                command = command,
                timeoutMs = SHELL_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.w(LOG_TAG, "shell exception identity=$identity: ${e.message}")
            lastError = e.message ?: lastError
            continue
        }
        val session = (outcome as? TerminalCommandOutcome.Success)?.session
            ?: (outcome as? TerminalCommandOutcome.Timeout)?.session
        if (session != null) {
            runCatching { TerminalSessionPool.close(session) }
        }
        if (outcome is TerminalCommandOutcome.Success && outcome.result.exitCode == 0) {
            val stdout = outcome.result.stdout.toByteArray().decodeToString()
            val stderr = outcome.result.stderr.toByteArray().decodeToString()
            if (listOf("Error", "Exception", "Permission Denial").none { stderr.contains(it, true) }) {
                return Result.success(stdout)
            }
            lastError = stderr.trim()
        } else {
            lastError = "Shell command failed with identity=$identity."
        }
    }
    return Result.failure(IllegalStateException(lastError))
}

private fun args(argumentsJson: String): JsonObject? =
    runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()

private fun JsonObject.intArg(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

private fun JsonObject.stringArg(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** 把 package_name / app_name 解析成包名；失败时返回可直接回给 Agent 的失败结果。 */
private suspend fun resolvePackage(obj: JsonObject): Pair<String?, BuiltinToolResult?> {
    obj.stringArg("package_name")?.let { return it to null }
    val appName = obj.stringArg("app_name")
        ?: return null to BuiltinToolResult.failure(
            code = "MISSING_REQUIRED_FIELD",
            message = "package_name or app_name is required.",
        )
    return when (val result = AppInfoProvider.cache().findByAppName(appName)) {
        is AppMatchResult.Found -> result.app.packageName to null
        is AppMatchResult.Candidates -> null to BuiltinToolResult.failure(
            code = "AMBIGUOUS_APP_MATCH",
            message = "Multiple apps match '$appName'. Retry with an exact package_name.",
            hint = result.apps.joinToString { "${it.appName} (${it.packageName})" },
        )

        AppMatchResult.NotFound -> null to BuiltinToolResult.failure(
            code = "APP_NOT_FOUND",
            message = "No installed app matches '$appName'.",
        )
    }
}

private suspend fun listTasks(): Result<List<AppTask>> =
    runPrivilegedShell("""dumpsys activity activities | grep -E '^Display #|^  \* Task\{'""")
        .map { parseAppTasks(it) }

private suspend fun moveTask(task: AppTask, targetDisplayId: Int): BuiltinToolResult {
    val moved = runPrivilegedShell("am display move-stack ${task.taskId} $targetDisplayId")
    if (moved.isFailure) {
        return BuiltinToolResult.failure(
            code = "MOVE_APP_FAILED",
            message = moved.exceptionOrNull()?.message ?: "Could not move the app task.",
            hint = "Moving tasks between displays needs root or Shizuku. Nothing was changed.",
        )
    }
    val after = listTasks().getOrNull()?.firstOrNull { it.taskId == task.taskId }
    if (after?.displayId != targetDisplayId) {
        return BuiltinToolResult.failure(
            code = "MOVE_APP_FAILED",
            message = "The system accepted the move but ${task.packageName} is on display " +
                    "${after?.displayId ?: "unknown"}, not $targetDisplayId.",
            hint = "Some apps refuse to be resized onto another display. Use launch_app with display_id instead.",
        )
    }
    return BuiltinToolResult.success(
        message = "${task.packageName} moved to display $targetDisplayId.",
        data = JsonObject(
            mapOf(
                "package_name" to JsonPrimitive(task.packageName),
                "task_id" to JsonPrimitive(task.taskId),
                "from_display_id" to JsonPrimitive(task.displayId),
                "display_id" to JsonPrimitive(targetDisplayId),
            )
        ),
    )
}

private fun invalidDisplay(displayId: Int) = BuiltinToolResult.failure(
    code = "INVALID_DISPLAY_ID",
    message = "display_id $displayId is not a virtual display managed by Zafiro.",
    hint = "Call virtual_display_list to see valid ids.",
)

private fun notRunning(packageName: String, where: String) = BuiltinToolResult.failure(
    code = "APP_NOT_RUNNING",
    message = "$packageName has no running task $where.",
    hint = "Launch the app first, or check which display it is on.",
)

class VirtualDisplayMoveAppBuiltin : BuiltinTool() {
    override val name = "virtual_display_move_app"
    override val description = "Move an app that is already running on the physical screen onto a background " +
            "virtual display, keeping its state. Afterwards operate it with display_id. " +
            "Use virtual_display_return_app to bring it back. Requires root or Shizuku."
    override val defaultEnabled = true
    override val inputSchemaJson = """
{
  "type": "object",
  "properties": {
    "display_id": { "type": "integer", "description": "Target virtual display id from virtual_display_list." },
    "package_name": { "type": "string", "description": "Exact package name of the running app." },
    "app_name": { "type": "string", "description": "App display name; used when package_name is omitted." }
  },
  "required": ["display_id"]
}
    """.trimIndent()

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val svc = findService<VirtualDisplayService>()
            ?: return BuiltinToolResult.failure(
                "VIRTUAL_DISPLAY_UNAVAILABLE", "The virtual display service is not available.",
            )
        val obj = args(request.argumentsJson)
            ?: return BuiltinToolResult.failure("INVALID_ARGUMENTS", "Arguments must be a JSON object.")
        val displayId = obj.intArg("display_id")
            ?: return BuiltinToolResult.failure("INVALID_ARGUMENTS", "display_id is required.")
        if (svc.managedDisplay(displayId) == null) return invalidDisplay(displayId)

        val (packageName, failure) = resolvePackage(obj)
        if (failure != null || packageName == null) return failure!!
        if (packageName == ContextProvider.await().packageName) {
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS",
                message = "Zafiro itself cannot be moved to a virtual display.",
            )
        }
        val tasks = listTasks().getOrElse {
            return BuiltinToolResult.failure(
                code = "MOVE_APP_FAILED",
                message = it.message ?: "Could not list tasks.",
                hint = "Moving tasks between displays needs root or Shizuku.",
            )
        }
        if (tasks.any { it.packageName == packageName && it.displayId == displayId }) {
            return BuiltinToolResult.success(
                message = "$packageName is already on display $displayId.",
                data = JsonObject(mapOf("display_id" to JsonPrimitive(displayId))),
            )
        }
        val task = tasks.firstOrNull { it.packageName == packageName && it.displayId == PHYSICAL_DISPLAY_ID }
            ?: return notRunning(packageName, "on the physical screen")
        return moveTask(task, displayId)
    }
}

class VirtualDisplayReturnAppBuiltin : BuiltinTool() {
    override val name = "virtual_display_return_app"
    override val description = "Move an app from a background virtual display back to the physical screen, " +
            "keeping its state. Requires root or Shizuku."
    override val defaultEnabled = true
    override val inputSchemaJson = """
{
  "type": "object",
  "properties": {
    "package_name": { "type": "string", "description": "Exact package name of the app on the virtual display." },
    "app_name": { "type": "string", "description": "App display name; used when package_name is omitted." },
    "display_id": { "type": "integer", "description": "Virtual display it currently runs on. Default: search every managed display." }
  }
}
    """.trimIndent()

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val svc = findService<VirtualDisplayService>()
            ?: return BuiltinToolResult.failure(
                "VIRTUAL_DISPLAY_UNAVAILABLE", "The virtual display service is not available.",
            )
        val obj = args(request.argumentsJson)
            ?: return BuiltinToolResult.failure("INVALID_ARGUMENTS", "Arguments must be a JSON object.")
        val sourceId = obj.intArg("display_id")
        if (sourceId != null && svc.managedDisplay(sourceId) == null) return invalidDisplay(sourceId)

        val (packageName, failure) = resolvePackage(obj)
        if (failure != null || packageName == null) return failure!!

        val managed = svc.displays().map { it.displayId }.toSet()
        val tasks = listTasks().getOrElse {
            return BuiltinToolResult.failure(
                code = "MOVE_APP_FAILED",
                message = it.message ?: "Could not list tasks.",
                hint = "Moving tasks between displays needs root or Shizuku.",
            )
        }
        val task = tasks.firstOrNull {
            it.packageName == packageName && it.displayId != PHYSICAL_DISPLAY_ID &&
                    (sourceId?.let { id -> it.displayId == id } ?: (it.displayId in managed))
        } ?: return notRunning(packageName, "on a virtual display")
        return moveTask(task, PHYSICAL_DISPLAY_ID)
    }
}
