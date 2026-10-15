package com.niki914.zafiro.chat.agentic.display

import com.niki914.zafiro.api.ExecutionEnvironment
import com.niki914.zafiro.api.VirtualDisplayService
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.service.findService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 一次工具调用要操作的屏幕。放在协程上下文里，所有屏幕操作都从这里取同一个 Display ID。 */
class DisplayTarget(val displayId: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DisplayTarget>
}

const val PHYSICAL_DISPLAY_ID = 0

suspend fun currentDisplayId(): Int =
    currentCoroutineContext()[DisplayTarget]?.displayId ?: PHYSICAL_DISPLAY_ID

class DisplayRejection(val code: String, val message: String, val hint: String)

/**
 * 工具参数 display_id → 实际目标屏幕。
 *
 * - 显式 display_id：0 是实体屏；其它必须是受管的虚拟屏，否则拒绝（不接受任意 Display ID）。
 * - 未指定：执行环境为实体屏（默认）时是 Display 0，与旧行为一致；为背景虚拟屏时落到受管虚拟屏，
 *   没有就拒绝——任何情况下都不会在虚拟屏不可用时悄悄改去操作实体屏。
 */
object DisplayRouting {

    sealed interface Resolution {
        data class Target(val displayId: Int) : Resolution
        data class Rejected(val rejection: DisplayRejection) : Resolution
    }

    fun resolve(argumentsJson: String): Resolution {
        val explicit = when (val parsed = parseDisplayId(argumentsJson)) {
            is Parsed.Invalid -> return Resolution.Rejected(parsed.rejection)
            is Parsed.Value -> parsed.id
        }
        val service = findService<VirtualDisplayService>()

        if (explicit != null) {
            if (explicit == PHYSICAL_DISPLAY_ID) return Resolution.Target(PHYSICAL_DISPLAY_ID)
            val managed = service?.managedDisplay(explicit)
                ?: return Resolution.Rejected(
                    DisplayRejection(
                        code = "INVALID_DISPLAY_ID",
                        message = "display_id $explicit is not a virtual display managed by Zafiro.",
                        hint = "Call virtual_display_list to see valid ids, or omit display_id.",
                    )
                )
            return Resolution.Target(managed.displayId)
        }

        if (service != null && service.environment.value == ExecutionEnvironment.BackgroundVirtual) {
            val target = service.displays().firstOrNull()
                ?: return Resolution.Rejected(
                    DisplayRejection(
                        code = "NO_VIRTUAL_DISPLAY",
                        message = "Background virtual display mode is on, but no virtual display exists. " +
                                "The action was NOT performed on the physical screen.",
                        hint = "Call virtual_display_create first, or pass display_id=0 to act on the physical screen explicitly.",
                    )
                )
            return Resolution.Target(target.displayId)
        }
        return Resolution.Target(PHYSICAL_DISPLAY_ID)
    }

    private sealed interface Parsed {
        data class Value(val id: Int?) : Parsed
        data class Invalid(val rejection: DisplayRejection) : Parsed
    }

    private fun parseDisplayId(argumentsJson: String): Parsed {
        val obj = runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()
            ?: return Parsed.Value(null) // 参数本身的错误交给各工具原有的校验去报
        val raw = obj["display_id"] ?: return Parsed.Value(null)
        val prim = raw as? JsonPrimitive
        // 部分 MCP 客户端会把未知参数一律序列化成字符串，纯数字串按整数处理
        val id = prim?.contentOrNull?.trim()?.takeIf { it.matches(Regex("-?[0-9]{1,9}")) }?.toIntOrNull()
        if (prim == null || prim.contentOrNull == "null") return Parsed.Value(null)
        if (id == null || id < 0) {
            return Parsed.Invalid(
                DisplayRejection(
                    code = "INVALID_DISPLAY_ID",
                    message = "display_id must be a non-negative integer.",
                    hint = "Use 0 for the physical screen or an id from virtual_display_list.",
                )
            )
        }
        return Parsed.Value(id)
    }
}

/** 解析 display_id 并把整个工具调用放进对应屏幕的上下文里；被拒绝时不执行任何操作。 */
suspend fun <T> withDisplay(
    argumentsJson: String,
    reject: (DisplayRejection) -> T,
    block: suspend () -> T,
): T = when (val resolution = DisplayRouting.resolve(argumentsJson)) {
    is DisplayRouting.Resolution.Rejected -> reject(resolution.rejection)
    is DisplayRouting.Resolution.Target -> withContext(DisplayTarget(resolution.displayId)) { block() }
}

fun DisplayRejection.toToolFailure(): BuiltinToolResult =
    BuiltinToolResult.failure(code = code, message = message, hint = hint)
