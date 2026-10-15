// 保护：dumpsys 任务行的两种写法（A=uid:pkg / I=pkg/.Activity）都要归到正确的显示器与包名，
// 否则移动工具会找不到或搬错任务。
package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.impl.AppTask
import com.niki914.zafiro.chat.agentic.buildin.impl.parseAppTasks
import org.junit.Assert.assertEquals
import org.junit.Test

class MoveAppTaskParsingTest {
    @Test
    fun groupsRootTasksUnderTheirDisplayAndReadsBothPackageForms() {
        val dump = """
            Display #10 (activities from top to bottom):
              * Task{42b983b #680 type=standard A=10184:com.android.chrome U=0 visible=true}
            Display #0 (activities from top to bottom):
              * Task{7e64b71 #641 type=standard I=com.stratumauth.app/.MainActivity U=0 visible=false}
              * Task{5485e76 #1 type=home U=0 visible=false}
                * Task{dc92e2e #524 type=standard A=10310:nested.pkg U=0}
        """.trimIndent()

        assertEquals(
            listOf(
                AppTask(10, 680, "com.android.chrome"),
                AppTask(0, 641, "com.stratumauth.app"),
            ),
            parseAppTasks(dump),
        )
    }
}
