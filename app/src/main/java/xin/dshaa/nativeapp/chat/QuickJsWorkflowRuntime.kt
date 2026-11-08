package xin.dshaa.nativeapp.chat

import xin.dshaa.core.js.JsHost
import xin.dshaa.core.js.JsOutcome
import xin.dshaa.core.js.JsStatus
import xin.dshaa.core.js.QuickJs
import xin.dshaa.core.workflow.WorkflowJsHost
import xin.dshaa.core.workflow.WorkflowJsResult
import xin.dshaa.core.workflow.WorkflowJsRuntime
import xin.dshaa.core.workflow.WorkflowJsStatus
import java.util.concurrent.atomic.AtomicReference

/**
 * `core:workflow` 的 JsRuntime 实现：包真实 QuickJS（`core:js`）。
 *
 * 一次 run 建一个 QuickJS runtime（脚本执行完就销毁）；`complete`/`interrupt`
 * 可能从任意线程调用，所以用 AtomicReference 持有当前实例。
 */
class QuickJsWorkflowRuntime : WorkflowJsRuntime {

    private val current = AtomicReference<QuickJs?>(null)

    override fun run(code: String, filename: String, host: WorkflowJsHost): WorkflowJsResult {
        val jsHost = object : JsHost {
            override fun callSync(method: String, payloadJson: String): String = host.callSync(method, payloadJson)

            override fun callAsync(method: String, payloadJson: String, callId: Int) =
                host.callAsync(method, payloadJson, callId)
        }
        val js = QuickJs(jsHost)
        current.set(js)
        return try {
            map(js.evaluate(code, filename))
        } finally {
            current.set(null)
            js.close()
        }
    }

    override fun complete(callId: Int, ok: Boolean, payloadJson: String?) {
        current.get()?.complete(callId, ok, payloadJson)
    }

    override fun interrupt() {
        current.get()?.interrupt()
    }

    private fun map(outcome: JsOutcome): WorkflowJsResult = when (outcome.status) {
        JsStatus.COMPLETED -> WorkflowJsResult(WorkflowJsStatus.COMPLETED, valueJson = outcome.valueJson ?: "null")
        JsStatus.INTERRUPTED -> WorkflowJsResult(WorkflowJsStatus.INTERRUPTED)
        JsStatus.ERROR -> WorkflowJsResult(WorkflowJsStatus.ERROR, error = outcome.error)
    }
}
