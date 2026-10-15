package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.zafiro.repo.XRepo

/** MCP 服务页与 Gateway 页共用的「可对外暴露工具」清单（内置安全 / 会话类 / Python / 自定义）。 */
object McpToolCatalog {

    private val SAFE_BUILTIN_TOOL_ORDER = listOf(
        "screen_operation_accessibility",
        "screen_operation_shell",
        "screenshot",
        "launch_app",
        "find_installed_apps",
        "open_uri",
        "load_skill",
        "notify",
        "view_image",
        "memory",
    )

    private val TOOL_DISPLAY_INFO = mapOf(
        "screen_operation_accessibility" to ("Accessibility UI Tree" to "Read UI tree, perform clicks, scrolls, and text input"),
        "screen_operation_shell" to ("Shell Coordinates" to "Inject taps and swipes using screen pixel coordinates"),
        "screenshot" to ("Screen Capture" to "Capture screen images for visual inspection"),
        "launch_app" to ("Launch Application" to "Open installed Android apps by package name"),
        "find_installed_apps" to ("Find Installed Apps" to "Query list of installed packages on device"),
        "open_uri" to ("Open URI / Deep Link" to "Open web URLs or app deep links via Android Intent"),
        "load_skill" to ("Load Skill" to "Read skill instructions and documentation"),
        "notify" to ("System Notification" to "Post notifications to the Android status bar"),
        "view_image" to ("View Image" to "Read and inspect image files from storage"),
        "memory" to ("Agent Memory" to "Store and recall persistent memory facts"),
        "shell" to ("Shell" to "Local Android persistent shell environment"),
        "ssh" to ("SSH" to "Remote SSH persistent terminal"),
        "execute_python" to ("Execute Python" to "Execute arbitrary Python code in python process"),
        "py_meta_tools" to ("Python Meta Tools" to "Dynamically register and manage custom python tools"),
    )

    suspend fun list(): List<McpServiceToolItemUi> {
        val builtinRegistry = BuiltinToolRegistry.default()
        val registeredBuiltins = builtinRegistry.all()
            .filter { !it.name.startsWith("mcp__") }
            .associateBy { it.name }

        val safeTools = SAFE_BUILTIN_TOOL_ORDER.mapNotNull { name ->
            registeredBuiltins[name]?.let { tool ->
                val (displayTitle, displayDesc) = TOOL_DISPLAY_INFO[name]
                    ?: (tool.name to tool.description)
                McpServiceToolItemUi(
                    name = tool.name,
                    displayName = displayTitle,
                    description = displayDesc,
                    isDangerous = false,
                )
            }
        }

        val sessionTools = listOf("shell", "ssh").mapNotNull { name ->
            registeredBuiltins[name]?.let { tool ->
                val (displayTitle, displayDesc) = TOOL_DISPLAY_INFO[tool.name]
                    ?: (tool.name to tool.description)
                McpServiceToolItemUi(
                    name = tool.name,
                    displayName = displayTitle,
                    description = displayDesc,
                    isDangerous = true,
                )
            }
        }

        val pythonTools = listOf("execute_python", "py_meta_tools").mapNotNull { name ->
            registeredBuiltins[name]?.let { tool ->
                val (displayTitle, displayDesc) = TOOL_DISPLAY_INFO[name]
                    ?: (tool.name to tool.description)
                McpServiceToolItemUi(
                    name = tool.name,
                    displayName = displayTitle,
                    description = displayDesc,
                    isDangerous = true,
                )
            }
        }

        val customPyTools = runCatching { XRepo.customPyTools.list() }
            .getOrDefault(emptyList())
            .filter { !it.name.startsWith("mcp__") }
            .sortedBy { it.name }
            .map { custom ->
                McpServiceToolItemUi(
                    name = custom.name,
                    displayName = custom.name,
                    description = custom.description.ifBlank { "Custom Python tool" },
                    isDangerous = false,
                )
            }

        return safeTools + sessionTools + pythonTools + customPyTools
    }
}
