package com.niki914.zafiro.chat.agentic.display

import com.niki914.zafiro.api.DisplayFrame
import com.niki914.zafiro.api.ExecutionEnvironment
import com.niki914.zafiro.api.VirtualDisplayInfo
import com.niki914.zafiro.api.VirtualDisplayService
import com.niki914.zafiro.api.VirtualDisplayState
import com.niki914.zafiro.service.ServiceRegistry
import com.niki914.zafiro.service.installService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 保护：屏幕工具的目标屏幕解析——无效/未受管的 display_id 必须被拒绝，背景模式下没有虚拟屏时绝不能悄悄落到实体屏。
// 任一分支被改坏，AI 的点击/输入就可能误打到用户正在用的实体屏幕。
class DisplayRoutingTest {

    private class FakeService(
        initial: ExecutionEnvironment,
        private val managed: List<VirtualDisplayInfo>,
    ) : VirtualDisplayService {
        override val state: StateFlow<VirtualDisplayState> = MutableStateFlow(VirtualDisplayState.Stopped)
        override val environment = MutableStateFlow(initial)
        override fun setEnvironment(environment: ExecutionEnvironment) {
            this.environment.value = environment
        }

        override suspend fun create(width: Int, height: Int, dpi: Int) = error("unused")
        override suspend fun destroy(displayId: Int) = error("unused")
        override suspend fun destroyAll() = Unit
        override fun displays() = managed
        override suspend fun capture(displayId: Int, jpeg: Boolean, quality: Int, maxSide: Int): Result<DisplayFrame> =
            error("unused")

        override suspend fun launch(displayId: Int, component: String) = error("unused")
    }

    private val vd = VirtualDisplayInfo(displayId = 7, width = 1080, height = 2400, dpi = 420)

    @After
    fun tearDown() = ServiceRegistry.clearForTest()

    private fun install(env: ExecutionEnvironment, managed: List<VirtualDisplayInfo>) =
        installService<VirtualDisplayService>(FakeService(env, managed))

    private fun target(json: String) =
        (DisplayRouting.resolve(json) as DisplayRouting.Resolution.Target).displayId

    private fun rejection(json: String) =
        (DisplayRouting.resolve(json) as DisplayRouting.Resolution.Rejected).rejection

    @Test
    fun defaultEnvironmentKeepsPhysicalScreenWhenNoDisplayGiven() {
        install(ExecutionEnvironment.Physical, listOf(vd))
        assertEquals(0, target("{}"))
        assertEquals(0, target("""{"operation":"read"}"""))
    }

    @Test
    fun explicitIdMustBeZeroOrAManagedVirtualDisplay() {
        install(ExecutionEnvironment.Physical, listOf(vd))
        assertEquals(7, target("""{"display_id":7}"""))
        assertEquals(0, target("""{"display_id":0}"""))
        assertEquals("INVALID_DISPLAY_ID", rejection("""{"display_id":3}""").code)
        assertEquals("INVALID_DISPLAY_ID", rejection("""{"display_id":-1}""").code)
        // 数字串按整数处理（部分 MCP 客户端会把未知参数序列化成字符串），其它形态仍然拒绝
        assertEquals(7, target("""{"display_id":"7"}"""))
        assertEquals("INVALID_DISPLAY_ID", rejection("""{"display_id":"7; rm"}""").code)
        assertEquals("INVALID_DISPLAY_ID", rejection("""{"display_id":7.5}""").code)
    }

    @Test
    fun backgroundModeWithoutVirtualDisplayRejectsInsteadOfFallingBackToPhysical() {
        install(ExecutionEnvironment.BackgroundVirtual, emptyList())
        val r = rejection("{}")
        assertEquals("NO_VIRTUAL_DISPLAY", r.code)
        assertTrue(r.message.contains("NOT performed on the physical screen"))
    }

    @Test
    fun backgroundModeRoutesDefaultToTheVirtualDisplayButHonoursExplicitPhysical() {
        install(ExecutionEnvironment.BackgroundVirtual, listOf(vd))
        assertEquals(7, target("{}"))
        assertEquals(0, target("""{"display_id":0}"""))
    }

    @Test
    fun withoutAnyServiceOnlyPhysicalScreenIsReachable() {
        assertEquals(0, target("{}"))
        assertEquals("INVALID_DISPLAY_ID", rejection("""{"display_id":5}""").code)
    }
}
