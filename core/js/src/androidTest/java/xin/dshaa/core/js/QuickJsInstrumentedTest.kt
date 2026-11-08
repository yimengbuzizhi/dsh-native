package xin.dshaa.core.js

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * QuickJS JNI 的真机冒烟（`connectedDebugAndroidTest`）。
 *
 * 覆盖只靠 JVM 测不到的四件事：
 * 1. `.so` 能加载、`evaluate` 能返回 JSON；
 * 2. 异步 host hook：JS 等 Promise，宿主线程 `complete` 后继续；
 * 3. 真并发：两个 host call 同时 pending，先到的 completion 不阻塞另一个；
 * 4. interrupt：死循环脚本能被另一个线程打断。
 */
@RunWith(AndroidJUnit4::class)
class QuickJsInstrumentedTest {

    @Test
    fun probeScripts() {
        val scripts = listOf(
            "1 + 1",
            "(async () => 42)()",
            "(async () => ({ x: 1 }))()",
            "(async () => { return { x: 42, s: \"abc\" }; })()",
            "(async () => { return { x: 42, s: \"中文\" }; })()",
            "(async () => { const x = 20 + 22; return { x, s: \"中文\" }; })()",
            "(async () => { const o = { x, s: \"中文\" }; return o; })()",
        )
        for (script in scripts) {
            QuickJs(object : JsHost {
                override fun callSync(method: String, payloadJson: String): String? = null
                override fun callAsync(method: String, payloadJson: String, callId: Int) = Unit
            }).use { js ->
                val outcome = js.evaluate(script)
                android.util.Log.i("QJS-PROBE", "$script => $outcome")
            }
        }
    }

    @Test
    fun evaluatesSyncScript() {
        val js = QuickJs(object : JsHost {
            override fun callSync(method: String, payloadJson: String): String? = null
            override fun callAsync(method: String, payloadJson: String, callId: Int) = Unit
        })
        js.use {
            val outcome = it.evaluate("(async () => { const x = 20 + 22; return { x, s: \"中文\" }; })()")
            assertEquals("error=" + outcome.error, JsStatus.COMPLETED, outcome.status)
            assertEquals("""{"x":42,"s":"中文"}""", outcome.valueJson)
        }
    }

    @Test
    fun awaitsAsyncHostCall() {
        val pending = AtomicReference<Int>()
        val js = QuickJs(object : JsHost {
            override fun callSync(method: String, payloadJson: String): String? = null
            override fun callAsync(method: String, payloadJson: String, callId: Int) {
                pending.set(callId)
            }
        })
        js.use {
            val result = AtomicReference<JsOutcome>()
            val thread = Thread { result.set(it.evaluate("(async () => await __dshCallAsync(\"echo\", \"{}\"))()")) }
            thread.start()
            waitUntil { pending.get() != null }
            it.complete(pending.get()!!, true, "\"hello\"")
            thread.join(TimeUnit.SECONDS.toMillis(10))
            assertEquals("error=" + result.get().error, JsStatus.COMPLETED, result.get().status)
            assertEquals("\"hello\"", result.get().valueJson)
        }
    }

    @Test
    fun runsTwoHostCallsConcurrently() {
        val calls = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val js = QuickJs(object : JsHost {
            override fun callSync(method: String, payloadJson: String): String? = null
            override fun callAsync(method: String, payloadJson: String, callId: Int) {
                calls += callId
            }
        })
        js.use {
            val script = """
                (async () => {
                  const a = __dshCallAsync("slow", "1");
                  const b = __dshCallAsync("slow", "2");
                  return [await a, await b];
                })()
            """.trimIndent()
            val result = AtomicReference<JsOutcome>()
            val thread = Thread { result.set(it.evaluate(script)) }
            thread.start()
            // 两个 call 都到宿主（说明第一个没有阻塞 JS 线程/第二个调用）
            waitUntil { calls.size == 2 }
            // 倒序完成：如果 JS 侧是「同步等待」，第二个结果会永远等不到
            it.complete(calls[1], true, "\"second\"")
            it.complete(calls[0], true, "\"first\"")
            thread.join(TimeUnit.SECONDS.toMillis(10))
            assertEquals("error=" + result.get().error, JsStatus.COMPLETED, result.get().status)
            assertEquals("""["first","second"]""", result.get().valueJson)
        }
    }

    @Test
    fun interruptStopsInfiniteLoop() {
        val js = QuickJs(object : JsHost {
            override fun callSync(method: String, payloadJson: String): String? = null
            override fun callAsync(method: String, payloadJson: String, callId: Int) = Unit
        })
        js.use {
            val result = AtomicReference<JsOutcome>()
            val started = CountDownLatch(1)
            val thread = Thread {
                started.countDown()
                result.set(it.evaluate("(async () => { while (true) {} })()"))
            }
            thread.start()
            started.await(5, TimeUnit.SECONDS)
            Thread.sleep(200)
            it.interrupt()
            thread.join(TimeUnit.SECONDS.toMillis(10))
            assertEquals(JsStatus.INTERRUPTED, result.get().status)
        }
    }

    @Test
    fun backendErrorRejectsPromise() {
        val pending = AtomicReference<Int>()
        val js = QuickJs(object : JsHost {
            override fun callSync(method: String, payloadJson: String): String? = null
            override fun callAsync(method: String, payloadJson: String, callId: Int) {
                pending.set(callId)
            }
        })
        js.use {
            val result = AtomicReference<JsOutcome>()
            val thread = Thread {
                result.set(it.evaluate("(async () => { try { await __dshCallAsync(\"agent\", \"{}\"); } catch (e) { throw new Error(\"wrapped: \" + e.message); } })()"))
            }
            thread.start()
            waitUntil { pending.get() != null }
            it.complete(pending.get()!!, false, """{"message":"boom","code":"AGENT_START","fatal":true}""")
            thread.join(TimeUnit.SECONDS.toMillis(10))
            assertEquals(JsStatus.ERROR, result.get().status)
            assertTrue(result.get().error!!.contains("wrapped: boom"))
            assertNull(result.get().valueJson)
        }
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val done = AtomicBoolean(false)
        while (!done.get()) {
            if (condition()) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met in ${timeoutMs}ms")
            Thread.sleep(10)
        }
    }
}
