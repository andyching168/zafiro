package com.niki914.zafiro.mcp.service

import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult

/**
 * 只对外部 MCP 客户端开放的会话标记工具：不碰设备，只切换「AI 正在操作」的可视提示与屏幕常亮。
 * 不进 Agent 的内置注册表，也不受工具白名单约束（无副作用，也不能用来提权）。
 */
class PhoneOperationStartTool : BuiltinTool() {
    override val name = NAME
    override val description =
        "Call this before a sequence of phone actions. It shows the on-screen \"AI is operating\" " +
                "glow and keeps the screen awake until phone_operation_end is called. " +
                "Ends automatically after 2 minutes without any tool call."
    override val inputSchemaJson = """{"type":"object","properties":{}}"""

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        RemoteOperationTracker.beginSession()
        return BuiltinToolResult.success("Phone operation started.")
    }

    companion object {
        const val NAME = "phone_operation_start"
    }
}

class PhoneOperationEndTool : BuiltinTool() {
    override val name = NAME
    override val description =
        "Call this when the phone actions are finished: removes the glow and releases the keep-awake."
    override val inputSchemaJson = """{"type":"object","properties":{}}"""

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        RemoteOperationTracker.endSession()
        return BuiltinToolResult.success("Phone operation ended.")
    }

    companion object {
        const val NAME = "phone_operation_end"
    }
}
