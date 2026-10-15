package com.niki914.zafiro.chat.agentic.buildin.impl

import android.content.Context
import android.content.Intent
import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.api.VirtualDisplayService
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.display.PHYSICAL_DISPLAY_ID
import com.niki914.zafiro.chat.agentic.display.currentDisplayId
import com.niki914.zafiro.chat.agentic.display.toToolFailure
import com.niki914.zafiro.chat.agentic.display.withDisplay
import com.niki914.zafiro.service.findService
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.device.AppInfo
import com.niki914.zafiro.chat.agentic.device.AppInfoProvider
import com.niki914.zafiro.chat.agentic.device.AppMatchResult
import com.niki914.zafiro.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class LaunchAppBuiltin : BuiltinTool() {
    override val name: String = "launch_app"

    override val description: String =
        "Launch an installed Android app by package_name or fuzzy app_name. If app_name matches multiple apps, this tool returns all candidates with app names and package names instead of launching."

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? get() = LAUNCH_APP_SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
        withDisplay(
            argumentsJson = request.argumentsJson,
            reject = { it.toToolFailure() },
        ) { launch(request) }

    private suspend fun launch(request: BuiltinToolRequest): BuiltinToolResult {
        val args = try {
            parseArguments(request.argumentsJson)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS_JSON",
                message = "launch_app arguments must be a JSON object with package_name or app_name.",
                hint = """Example: {"app_name":"微信"} or {"package_name":"com.tencent.mm"}""",
                fieldErrors = mapOf(
                    "argumentsJson" to (throwable.message ?: "Invalid JSON object.")
                ),
            )
        }

        if (args.packageName.isNullOrBlank() && args.appName.isNullOrBlank()) {
            return BuiltinToolResult.failure(
                code = "MISSING_REQUIRED_FIELD",
                message = "launch_app requires package_name or app_name.",
                fieldErrors = mapOf("package_name" to "Provide package_name or app_name."),
            )
        }

        val packageName = args.packageName?.takeIf { it.isNotBlank() }
            ?: return launchByAppName(args.appName.orEmpty())
        return launchPackage(packageName = packageName, appInfo = null)
    }

    private suspend fun launchByAppName(appName: String): BuiltinToolResult {
        return when (val result = AppInfoProvider.cache().findByAppName(appName)) {
            is AppMatchResult.Found -> launchPackage(
                packageName = result.app.packageName,
                appInfo = result.app,
            )

            is AppMatchResult.Candidates -> BuiltinToolResult.failure(
                code = "AMBIGUOUS_APP_MATCH",
                message = "Multiple apps match '$appName'. Call launch_app again with one package_name from candidates.",
                hint = "Use one exact package_name from data.candidates.",
                data = JsonObject(
                    mapOf(
                        "app_name" to JsonPrimitive(appName),
                        "candidates" to result.apps.toJsonArray(),
                    )
                ),
            )

            AppMatchResult.NotFound -> BuiltinToolResult.failure(
                code = "APP_NOT_FOUND",
                message = "No installed app matches '$appName'.",
                hint = "The app may be installed under a different name. Retry with an alternative name, or ask the user which app to open.",
                data = JsonObject(mapOf("app_name" to JsonPrimitive(appName))),
            )
        }
    }

    private suspend fun launchPackage(
        packageName: String,
        appInfo: AppInfo?,
    ): BuiltinToolResult {
        val context = ContextProvider.await().applicationContext
        val displayId = currentDisplayId()
        val event = if (displayId == PHYSICAL_DISPLAY_ID) {
            context.startApp(packageName)
        } else {
            context.startOnVirtualDisplay(packageName, displayId)
        }
        val data = linkedMapOf<String, JsonElement>(
            "package_name" to JsonPrimitive(packageName),
        )
        appInfo?.let {
            data["app_name"] = JsonPrimitive(it.appName)
            data["is_system_app"] = JsonPrimitive(it.isSystemApp)
        }
        return when (event) {
            LaunchEvent.Launched -> BuiltinToolResult.success(
                message = "App launched.",
                data = JsonObject(data),
            )

            is LaunchEvent.Failed -> BuiltinToolResult.failure(
                code = "APP_LAUNCH_FAILED",
                message = event.message,
                hint = "Confirm the package exists and has a launcher activity.",
                data = JsonObject(data),
            )
        }
    }

    /** 在受管虚拟屏启动；失败就是失败，不会改去实体屏启动。 */
    private suspend fun Context.startOnVirtualDisplay(packageName: String, displayId: Int): LaunchEvent {
        val component = packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToString()
            ?: return LaunchEvent.Failed("No launcher activity found for package '$packageName'.")
        val service = findService<VirtualDisplayService>()
            ?: return LaunchEvent.Failed("Virtual display service is not available.")
        return service.launch(displayId, component).fold(
            onSuccess = { LaunchEvent.Launched },
            onFailure = { LaunchEvent.Failed(it.message ?: "Failed to launch on display $displayId.") },
        )
    }

    private suspend fun Context.startApp(packageName: String): LaunchEvent {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return LaunchEvent.Failed("No launcher activity found for package '$packageName'.")

        val componentName = launchIntent.component?.flattenToString()
        val command = if (componentName != null) {
            "am start -n '$componentName'"
        } else {
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p '$packageName'"
        }

        for (identity in listOf("root", "shizuku")) {
            val outcome = try {
                TerminalSessionPool.openAndExecute(
                    identity = identity,
                    cwd = null,
                    command = command,
                    timeoutMs = LAUNCH_TIMEOUT_MS,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(LOG_TAG, "am start exception identity=$identity: ${e.message}")
                null
            }
            val session = (outcome as? TerminalCommandOutcome.Success)?.session
                ?: (outcome as? TerminalCommandOutcome.Timeout)?.session
            if (session != null) {
                runCatching { TerminalSessionPool.close(session) }
            }
            if (outcome is TerminalCommandOutcome.Success && outcome.result.exitCode == 0) {
                val stdout = outcome.result.stdout.toByteArray().decodeToString()
                val stderr = outcome.result.stderr.toByteArray().decodeToString()
                val output = "$stdout\n$stderr"
                val hasError = output.contains("Error:", ignoreCase = true) ||
                        output.contains("Permission Denial", ignoreCase = true) ||
                        output.contains("SecurityException", ignoreCase = true)
                if (!hasError) {
                    Logger.d(LOG_TAG, "startApp succeeded via identity=$identity")
                    return LaunchEvent.Launched
                }
                Logger.w(LOG_TAG, "am start identity=$identity reported error: $output")
            } else {
                Logger.w(
                    LOG_TAG,
                    "am start failed identity=$identity outcome=${outcome?.let { it::class.simpleName }}"
                )
            }
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(launchIntent)
            LaunchEvent.Launched
        } catch (throwable: Throwable) {
            LaunchEvent.Failed(throwable.message ?: "Failed to launch package '$packageName'.")
        }
    }

    private fun parseArguments(argumentsJson: String): LaunchAppArguments {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (throwable: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        } catch (throwable: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", throwable)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")
        return LaunchAppArguments(
            packageName = obj.string("package_name").trim().ifBlank { null },
            appName = obj.string("app_name").trim().ifBlank { null },
        )
    }

    private fun List<AppInfo>.toJsonArray(): JsonArray {
        return JsonArray(
            map { app ->
                JsonObject(
                    mapOf(
                        "app_name" to JsonPrimitive(app.appName),
                        "package_name" to JsonPrimitive(app.packageName),
                        "is_system_app" to JsonPrimitive(app.isSystemApp),
                    )
                )
            }
        )
    }

    private fun JsonObject.string(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private data class LaunchAppArguments(
        val packageName: String?,
        val appName: String?,
    )

    private sealed interface LaunchEvent {
        data object Launched : LaunchEvent
        data class Failed(val message: String) : LaunchEvent
    }

    companion object {
        private const val LOG_TAG = "niki914_zafiro_LaunchAppBuiltin"
        private const val LAUNCH_TIMEOUT_MS = 10_000L

        private const val LAUNCH_APP_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "display_id": {
                  "type": "integer",
                  "description": "Which screen to act on. Omit for the default (physical screen unless background virtual mode is on). 0 = physical screen; other values must be a virtual display id from virtual_display_list. Tokens/coordinates are only valid on the screen that produced them."
                },
                "package_name": {
                  "type": "string",
                  "description": "Exact Android package name to launch, for example com.tencent.mm."
                },
                "app_name": {
                  "type": "string",
                  "description": "App display name to launch. Fuzzy matching is allowed; ambiguous matches return candidates."
                }
              }
            }
        """
    }
}
