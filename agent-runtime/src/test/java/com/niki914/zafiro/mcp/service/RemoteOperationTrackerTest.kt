package com.niki914.zafiro.mcp.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 保护：并发/异常结束的工具调用与忘记 end 的操作会话都不得让「操作中」卡在 true（光效与常亮永不熄灭）；截屏结束后必须恢复光效；
// 用户停止必须取消在途调用，或让紧随其后的下一个请求恰好失败一次（不多不少）。
class RemoteOperationTrackerTest {
    @Test
    fun activeClearsEvenWhenToolThrowsAndNestedCallsOverlap() = runBlocking {
        runCatching {
            RemoteOperationTracker.track {
                RemoteOperationTracker.track { assertTrue(RemoteOperationTracker.active.value) }
                assertTrue(RemoteOperationTracker.active.value)
                error("boom")
            }
        }
        assertFalse(RemoteOperationTracker.active.value)
    }

    @Test
    fun suppressionIsLiftedAfterCapture() = runBlocking {
        RemoteOperationTracker.withOverlaysHidden {
            assertTrue(RemoteOperationTracker.overlaysSuppressed.value)
        }
        assertFalse(RemoteOperationTracker.overlaysSuppressed.value)
    }

    @Test
    fun sessionStaysActiveUntilEndedAndExpiresWhenAbandoned() = runBlocking {
        RemoteOperationTracker.beginSession()
        assertTrue(RemoteOperationTracker.active.value)
        RemoteOperationTracker.endSession()
        assertFalse(RemoteOperationTracker.active.value)

        RemoteOperationTracker.beginSession(ttlMs = 150)
        assertTrue(RemoteOperationTracker.active.value)
        val deadline = System.currentTimeMillis() + 3_000
        while (RemoteOperationTracker.active.value && System.currentTimeMillis() < deadline) delay(20)
        assertFalse("abandoned session must expire", RemoteOperationTracker.active.value)
    }

    @Test
    fun stopCancelsInFlightCallAsUserAborted() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val result = async {
            runCatching {
                RemoteOperationTracker.track { started.complete(Unit); delay(60_000) }
            }.exceptionOrNull()
        }
        started.await()
        RemoteOperationTracker.requestStop()
        assertTrue(withTimeout(3_000) { result.await() } is UserAbortedException)
        // 在途调用已经被取消，下一个请求不应再被拦
        assertEquals("ok", RemoteOperationTracker.track { "ok" })
    }

    @Test
    fun stopWhenIdleFailsExactlyTheNextRequestOnce() = runBlocking {
        RemoteOperationTracker.requestStop()
        val first = runCatching { RemoteOperationTracker.track { "x" } }.exceptionOrNull()
        assertTrue(first is UserAbortedException)
        assertEquals("y", RemoteOperationTracker.track { "y" })
    }

    @Test
    fun sessionControlCallsAreNotAbortable() = runBlocking {
        RemoteOperationTracker.requestStop()
        assertEquals("end", RemoteOperationTracker.track(abortable = false) { "end" })
        // 被豁免的调用不消耗那一次拒绝
        assertTrue(runCatching { RemoteOperationTracker.track { "x" } }.exceptionOrNull() is UserAbortedException)
    }
}
