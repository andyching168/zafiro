package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.api.VirtualDisplayInfo
import com.niki914.zafiro.api.VirtualDisplayService
import com.niki914.zafiro.chat.agentic.ScreenCaptureIngestor
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.display.DisplayTarget
import com.niki914.zafiro.chat.agentic.toImageToolResult
import com.niki914.zafiro.chat.agentic.toToolError
import com.niki914.zafiro.service.findService
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

private fun VirtualDisplayInfo.toJson() = JsonObject(
    mapOf(
        "display_id" to JsonPrimitive(displayId),
        "width" to JsonPrimitive(width),
        "height" to JsonPrimitive(height),
        "dpi" to JsonPrimitive(dpi),
    )
)

private fun service(): VirtualDisplayService? = findService<VirtualDisplayService>()

private fun unavailable() = BuiltinToolResult.failure(
    code = "VIRTUAL_DISPLAY_UNAVAILABLE",
    message = "The virtual display service is not available.",
)

private fun argInt(argumentsJson: String, key: String): Int? =
    (runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()?.get(key) as? JsonPrimitive)
        ?.takeIf { !it.isString }?.intOrNull

private fun displayIdSchema(extra: String = "") = """
{
  "type": "object",
  "properties": {
    "display_id": { "type": "integer", "description": "Virtual display id from virtual_display_list.$extra" }
  },
  "required": ["display_id"]
}
""".trimIndent()

class VirtualDisplayCreateBuiltin : BuiltinTool() {
    override val name = "virtual_display_create"
    override val description = "Create a background virtual display that apps can run on without touching the " +
            "physical screen. Returns display_id. Pass that display_id to launch_app, screenshot, " +
            "screen_operation_accessibility and screen_operation_shell to work on it. " +
            "width/height/dpi default to the physical screen. Requires root or Shizuku."
    override val defaultEnabled = true
    override val inputSchemaJson = """
{
  "type": "object",
  "properties": {
    "width": { "type": "integer", "description": "Pixels, 64-4096. Default: physical screen width." },
    "height": { "type": "integer", "description": "Pixels, 64-4096. Default: physical screen height." },
    "dpi": { "type": "integer", "description": "72-640. Default: physical screen density." }
  }
}
    """.trimIndent()

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val svc = service() ?: return unavailable()
        val w = argInt(request.argumentsJson, "width") ?: 0
        val h = argInt(request.argumentsJson, "height") ?: 0
        val d = argInt(request.argumentsJson, "dpi") ?: 0
        if ((w != 0 && w !in 64..4096) || (h != 0 && h !in 64..4096) || (d != 0 && d !in 72..640)) {
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS",
                message = "width/height must be 64-4096 and dpi 72-640.",
            )
        }
        return svc.create(w, h, d).fold(
            onSuccess = {
                BuiltinToolResult.success(
                    message = "Virtual display ${it.displayId} created.",
                    data = it.toJson(),
                    hint = "Use launch_app with display_id=${it.displayId} to open an app there.",
                )
            },
            onFailure = {
                BuiltinToolResult.failure(
                    code = "VIRTUAL_DISPLAY_CREATE_FAILED",
                    message = it.message ?: "Failed to create the virtual display.",
                    hint = "Virtual displays need root or Shizuku. Nothing was done on the physical screen.",
                )
            },
        )
    }
}

class VirtualDisplayListBuiltin : BuiltinTool() {
    override val name = "virtual_display_list"
    override val description = "List the background virtual displays Zafiro currently manages."
    override val defaultEnabled = true
    override val inputSchemaJson = """{ "type": "object", "properties": {} }"""

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val svc = service() ?: return unavailable()
        val list = svc.displays()
        return BuiltinToolResult.success(
            message = "${list.size} virtual display(s).",
            data = JsonObject(
                mapOf(
                    "environment" to JsonPrimitive(svc.environment.value.name),
                    "displays" to JsonArray(list.map { it.toJson() }),
                )
            ),
        )
    }
}

class VirtualDisplayDestroyBuiltin : BuiltinTool() {
    override val name = "virtual_display_destroy"
    override val description = "Destroy a background virtual display and everything running on it. " +
            "Call this when the background task is finished."
    override val defaultEnabled = true
    override val inputSchemaJson = displayIdSchema()

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val svc = service() ?: return unavailable()
        val id = argInt(request.argumentsJson, "display_id")
            ?: return BuiltinToolResult.failure("INVALID_ARGUMENTS", "display_id is required.")
        return svc.destroy(id).fold(
            onSuccess = { BuiltinToolResult.success("Virtual display $id destroyed.") },
            onFailure = {
                BuiltinToolResult.failure(
                    code = "INVALID_DISPLAY_ID",
                    message = it.message ?: "Could not destroy display $id.",
                    hint = "Call virtual_display_list to see valid ids.",
                )
            },
        )
    }
}

class VirtualDisplayPreviewBuiltin : BuiltinTool() {
    override val name = "virtual_display_preview"
    override val description = "Capture what is currently shown on a background virtual display as an image."
    override val defaultEnabled = true
    override val inputSchemaJson = displayIdSchema()

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val svc = service() ?: return unavailable()
        val id = argInt(request.argumentsJson, "display_id")
            ?: return BuiltinToolResult.failure("INVALID_ARGUMENTS", "display_id is required.")
        if (svc.managedDisplay(id) == null) {
            return BuiltinToolResult.failure(
                code = "INVALID_DISPLAY_ID",
                message = "display_id $id is not a virtual display managed by Zafiro.",
                hint = "Call virtual_display_list to see valid ids.",
            )
        }
        return withContext(DisplayTarget(id)) {
            when (val r = ScreenCaptureIngestor.captureForTool()) {
                is ScreenCaptureIngestor.ToolCaptureResult.Ok ->
                    r.ingest.toImageToolResult(message = "Virtual display $id captured.")

                is ScreenCaptureIngestor.ToolCaptureResult.Err -> BuiltinToolResult.failure(
                    code = "SCREENSHOT_FAILED",
                    message = r.message ?: "Capture failed.",
                    hint = "Nothing may have been drawn on this display yet; launch an app there first.",
                )

                is ScreenCaptureIngestor.ToolCaptureResult.IngestErr -> {
                    val (code, message) = r.error.toToolError()
                    BuiltinToolResult.failure(code = code, message = message)
                }
            }
        }
    }
}
