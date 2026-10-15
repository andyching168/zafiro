package com.niki914.zafiro.api

import kotlinx.coroutines.flow.StateFlow

/** AI 工具默认操作哪块屏幕。用户随时可在工具参数里用 display_id 显式覆盖。 */
enum class ExecutionEnvironment {
    /** 实体屏幕（Display 0），默认；与引入虚拟屏之前的行为完全一致。 */
    Physical,

    /** 背景虚拟屏：未指定 display_id 的屏幕工具落到受管的虚拟屏，没有虚拟屏就报错，绝不回退到实体屏。 */
    BackgroundVirtual,
}

data class VirtualDisplayInfo(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val dpi: Int,
)

sealed interface VirtualDisplayState {
    data object Stopped : VirtualDisplayState
    data object Starting : VirtualDisplayState
    data class Running(val displays: List<VirtualDisplayInfo>) : VirtualDisplayState
    data class Error(val message: String) : VirtualDisplayState
}

class DisplayFrame(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
)

/**
 * 背景虚拟屏。虚拟屏由一个 shell 身份的辅助进程持有（受信任 VirtualDisplay 只能由 shell/root 创建），
 * App 与它之间是本机回环连接；App 或连接消失时辅助进程会释放所有虚拟屏。
 */
interface VirtualDisplayService {
    val state: StateFlow<VirtualDisplayState>
    val environment: StateFlow<ExecutionEnvironment>

    fun setEnvironment(environment: ExecutionEnvironment)

    /** 宽高/dpi 为 0 表示用实体屏的尺寸与密度。 */
    suspend fun create(width: Int = 0, height: Int = 0, dpi: Int = 0): Result<VirtualDisplayInfo>

    suspend fun destroy(displayId: Int): Result<Unit>

    suspend fun destroyAll()

    fun displays(): List<VirtualDisplayInfo>

    fun managedDisplay(displayId: Int): VirtualDisplayInfo? = displays().firstOrNull { it.displayId == displayId }

    suspend fun capture(
        displayId: Int,
        jpeg: Boolean = false,
        quality: Int = 80,
        maxSide: Int = 0,
    ): Result<DisplayFrame>

    /** 在指定虚拟屏启动 Activity，[component] 形如 `pkg/.Activity` 或 `pkg/pkg.Activity`。 */
    suspend fun launch(displayId: Int, component: String): Result<Unit>
}
