package xin.dshaa.core.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginModuleTest {

    @Test
    fun rewritesNamedAndDefaultImports() {
        val module = PluginModule.transform(
            """
            import { defineTool } from '@deepseek-ai/dsh-tools'
            import z from '@deepseek-ai/schemastery'
            import * as path from 'node:path'
            import 'side-effect'
            export const name = 'tool-demo'
            export const apply = (ctx) => { ctx.tools.register(defineTool({ name: 'demo', description: 'd' })) }
            """.trimIndent(),
        )
        assertTrue(module.code.contains("const { defineTool } = __dshm1;"))
        assertTrue(module.code.contains("const z = __dshDefault(__dshm2);"))
        assertTrue(module.code.contains("const path = __dshm3;"))
        assertTrue(module.code.contains("__require('side-effect');"))
        assertTrue(module.code.contains("const name = 'tool-demo'"))
        assertEquals(listOf("apply", "name"), module.exports.map { it.exported }.sorted())
        assertEquals(
            listOf("@deepseek-ai/dsh-tools", "@deepseek-ai/schemastery", "node:path", "side-effect"),
            module.imports,
        )
        assertTrue(module.warnings.isEmpty())
    }

    @Test
    fun rewritesDefaultExports() {
        val named = PluginModule.transform("export default function apply(ctx) {\n  ctx.logger.info('x')\n}")
        assertTrue(named.code.startsWith("function apply(ctx) {"))
        assertEquals(listOf(ModuleExport("default", "apply")), named.exports)

        val anonymous = PluginModule.transform("export default async function (ctx) {}")
        assertTrue(anonymous.code.contains("const __dshDefaultExport = async function (ctx) {}"))
        assertEquals(listOf(ModuleExport("default", "__dshDefaultExport")), anonymous.exports)

        val value = PluginModule.transform("export default { name: 'x', apply() {} }")
        assertTrue(value.code.contains("const __dshDefaultExport = { name: 'x', apply() {} }"))
        assertEquals("default", value.exports.single().exported)
    }

    @Test
    fun rewritesExportListsAndStars() {
        val list = PluginModule.transform("const a = 1\nconst b = 2\nexport { a, b as c }")
        assertEquals(listOf("a", "c"), list.exports.map { it.exported })
        assertEquals(listOf("a", "b"), list.exports.map { it.local })

        val reExport = PluginModule.transform("export { x as y } from 'mod-x'")
        assertTrue(reExport.code.contains("const y = __dshm1.x;"))
        assertEquals("y", reExport.exports.single().exported)

        val star = PluginModule.transform("export * from 'mod-y'")
        assertTrue(star.code.contains("exports[__k] = __dshm1[__k];"))
        assertTrue(star.exports.isEmpty())
    }

    @Test
    fun keepsCommonJsSourcesIntact() {
        val source = """
            'use strict'
            Object.defineProperty(exports, '__esModule', { value: true })
            exports.name = 'tool-cjs'
            exports.apply = function (ctx) { ctx.tools.register({ name: 'cjs_tool' }) }
        """.trimIndent()
        val module = PluginModule.transform(source)
        assertEquals(source, module.code)
        assertTrue(module.exports.isEmpty())
    }

    @Test
    fun joinsMultilineImports() {
        val module = PluginModule.transform(
            "import {\n  a,\n  b as c\n} from 'mod'\nconsole.log(a, c)",
        )
        assertTrue(module.code.contains("const { a, b: c } = __dshm1;"))
        assertTrue(module.code.contains("console.log(a, c)"))
    }

    @Test
    fun dryRunScriptCollectsExportsAndPluginCode() {
        val module = PluginModule.transform(
            """
            export const name = 'tool-x'
            export const inject = ['tools']
            export const apply = (ctx) => { ctx.tools.register({ name: 'x_tool', description: 'x' }) }
            """.trimIndent(),
        )
        val script = PluginModule.dryRunScript(module)
        assertTrue(script.contains("__ctx"))
        assertTrue(script.contains("'name': (typeof name === 'undefined' ? undefined : name)"))
        assertTrue(script.contains("ctx.tools.register({ name: 'x_tool', description: 'x' })"))
        // 桥把脚本包在函数体里：结果必须走顶层 return；async apply 要等 Promise 结算再收集工具。
        assertTrue(script.trimEnd().endsWith("return Promise.resolve(__dshApply).then(function () { return JSON.stringify(__dsh); });"))
        assertTrue(script.contains("__dshApply = Promise.resolve(__retValue).catch"))
    }

    @Test
    fun parsesDryRunResult() {
        val run = PluginModule.parseDryRun(
            """
            {"name":"tool-x","version":"1.0.0","inject":["tools"],
             "tools":[{"name":"x_tool","description":"x"}],
             "imports":["@deepseek-ai/dsh-tools"],"relativeImports":[],"applied":true,"errors":[]}
            """.trimIndent(),
        )
        assertEquals("tool-x", run.name)
        assertEquals("1.0.0", run.version)
        assertEquals(listOf("tools"), run.inject)
        assertEquals("x_tool", run.tools.single().name)
        assertTrue(run.applied)
        assertEquals(null, run.error)

        val failed = PluginModule.parseDryRun("""{"applied":false,"errors":["boom"]}""")
        assertEquals("boom", failed.error)
    }

    @Test
    fun survivesUnrecognizedExportWithWarning() {
        val module = PluginModule.transform("export as namespace Foo")
        assertTrue(module.warnings.isNotEmpty())
        assertNotNull(module.code)
    }

    @Test
    fun parsesServiceContainerDeclarations() {
        val run = PluginModule.parseDryRun(
            """
            {"applied":true,"errors":[],
             "provides":{"clock":"{\"now\":1}"},
             "injects":[{"name":"clock","optional":false},{"name":"log","optional":true}],
             "missingDeps":["log2"],
             "effects":["cleanup"],
             "subscriptions":["turn/end"]}
            """.trimIndent(),
        )
        assertEquals("{\"now\":1}", run.provides["clock"])
        assertEquals(listOf("clock", "log"), run.injects.map { it.name })
        assertEquals(listOf(false, true), run.injects.map { it.optional })
        assertEquals(listOf("log2"), run.missingServices)
        assertEquals(listOf("cleanup"), run.effects)
        assertEquals(listOf("turn/end"), run.subscriptions)

        // 脚本里不能再把这些原语当 unsupported 直接吞掉
        val script = PluginModule.dryRunScript(PluginModule.transform("export const apply = (ctx) => {}"))
        assertTrue(script.contains("plugin.services.values"))
        assertTrue(script.contains("plugin.services.provide"))
        assertTrue(script.contains("ctx.inject 回调报错"))
        assertTrue(!script.contains("__dshUnsupported('ctx.on')"))
        assertTrue(!script.contains("__dshUnsupported('ctx.effect')"))
    }

    @Test
    fun dryRunCapturesParametersSchema() {
        val module = PluginModule.transform(
            """
            export const apply = (ctx) => {
              ctx.tools.register({
                name: 'echo',
                description: 'e',
                parameters: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] }
              })
            }
            """.trimIndent(),
        )
        val script = PluginModule.dryRunScript(module)
        assertTrue(script.contains("const __params = __dshSchema(def.parameters);"))

        // 宿主返回的 JSON 里 parameters 是对象 → 归一化后写进 PluginTool
        val run = PluginModule.parseDryRun(
            """{"applied":true,"errors":[],"tools":[{"name":"echo","description":"e",
               "parameters":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}]}""",
        )
        assertEquals("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""", run.tools.single().parameters)
    }

    @Test
    fun invokeScriptCallsRegisteredToolAndAwaitsPromise() {
        val module = PluginModule.transform(
            """
            export const apply = (ctx) => {
              ctx.tools.register({ name: 'echo', execute: async () => ({ text: 'ok' }) })
            }
            """.trimIndent(),
        )
        val script = PluginModule.invokeScript(module, "echo", """{"text":"hi"}""")
        assertTrue(script.contains("const __toolName = \"echo\";"))
        assertTrue(script.contains("""const __toolArgs = "{\"text\":\"hi\"}";"""))
        assertTrue(script.contains("__dshDefs[__toolName]"))
        assertTrue(script.contains("Promise.resolve(__dshApply).then(__dshInvoke)"))
        assertTrue(script.contains("Promise.resolve(__result).then"))
        // 工具调用不能结尾返回试运行的 JSON，否则拿不到工具结果
        assertTrue(!script.contains("return JSON.stringify(__dsh);"))
    }

    @Test
    fun missingAndUnsupportedParametersAreDistinguished() {
        val missing = PluginModule.parseDryRun(
            """{"tools":[{"name":"noargs","parametersState":"missing"}]}""",
        ).tools.single()
        assertEquals("""{"type":"object","properties":{}}""", missing.parameters)

        val unsupported = PluginModule.parseDryRun(
            """{"tools":[{"name":"z","parametersState":"unsupported"}]}""",
        ).tools.single()
        assertNull(unsupported.parameters)
    }

    @Test
    fun parsesSuppressRuntimeContextFlag() {
        val module = PluginModule.transform(
            """
            export const apply = (ctx) => {
              ctx.systemPrompt.suppressRuntimeContext()
              ctx.systemPrompt.context({ name: 'c', text: 'still here' })
            }
            """.trimIndent(),
        )
        val script = PluginModule.dryRunScript(module)
        assertTrue(script.contains("__dsh.suppressRuntimeContext = true;"))

        assertTrue(PluginModule.parseDryRun("""{"applied":true,"suppressRuntimeContext":true}""").suppressRuntimeContext)
        // 旧试运行结果（没有这个字段）必须按 false 读，不能因为缺字段误抑制
        assertTrue(!PluginModule.parseDryRun("""{"applied":true}""").suppressRuntimeContext)
    }

    @Test
    fun parsesShellsAndResidentDispatch() {
        val module = PluginModule.transform(
            """
            export const apply = (ctx) => {
              ctx.ui.registerShell({
                id: 'm85a.chat',
                label: 'M85-A 最小壳',
                mount(state) { return { title: '壳', tree: { type: 'text', text: 'hi' } }; },
                onState(state) { return { tree: { type: 'text', text: 'state' } }; },
                onAction(id, payload, state) { return { tree: { type: 'text', text: id } }; }
              })
            }
            """.trimIndent(),
        )
        val script = PluginModule.dryRunScript(module)
        assertTrue(script.contains("registerShell"))
        assertTrue(script.contains("__dsh.shells.push"))
        val resident = PluginModule.residentScript(module)
        assertTrue(resident.contains("__dshShellDispatch"))
        assertTrue(resident.contains("shells: __dsh.shells"))

        val run = PluginModule.parseDryRun(
            """
            {"applied":true,"shells":[
              {"id":"m85a.chat","label":"M85-A 最小壳","hasState":true,"hasAction":true},
              {"id":"m85a.chat","label":"重复"},
              {"id":"  "}
            ]}
            """.trimIndent(),
        )
        val shell = run.shells.single()
        assertEquals("m85a.chat", shell.id)
        assertEquals("M85-A 最小壳", shell.label)
        assertTrue(shell.hasState)
        assertTrue(shell.hasAction)
        assertFalse(shell.hasEvent)
        // 旧试运行结果没有 shells → 空表（向后兼容）
        assertTrue(PluginModule.parseDryRun("""{"applied":true}""").shells.isEmpty())
    }

    @Test
    fun parsesAgentsAndResidentDispatch() {
        val module = PluginModule.transform(
            """
            export const apply = (ctx) => {
              ctx.agent.register({
                id: 'm85.echo',
                label: 'Echo 回合',
                run(turn, ctx) { return { stopReason: 'completed', text: turn.input } }
              })
            }
            """.trimIndent(),
        )
        val script = PluginModule.dryRunScript(module)
        assertTrue(script.contains("agent"))
        assertTrue(script.contains("__dsh.agents.push"))
        val resident = PluginModule.residentScript(module)
        assertTrue(resident.contains("__dshAgentDispatch"))
        assertTrue(resident.contains("agents: __dsh.agents"))
        assertTrue(resident.contains("agent.run"))

        val run = PluginModule.parseDryRun(
            """
            {"applied":true,"agents":[
              {"id":"m85.echo","label":"Echo 回合"},
              {"id":"m85.echo","label":"重复"},
              {"id":"  "}
            ]}
            """.trimIndent(),
        )
        val agent = run.agents.single()
        assertEquals("m85.echo", agent.id)
        assertEquals("Echo 回合", agent.label)
        // 旧试运行结果没有 agents → 空表（向后兼容）
        assertTrue(PluginModule.parseDryRun("""{"applied":true}""").agents.isEmpty())
    }

    @Test
    fun parsesStoragesAndPrompts() {
        val module = PluginModule.transform(
            """
            export const apply = (ctx) => {
              ctx.storage.register({
                id: 'builtin.storage.jsonl',
                label: '只读导出器',
                list() { return [] },
                load(id) { return '' }
              })
              ctx.systemPrompt.register({
                id: 'builtin.prompt.dsh',
                label: '标记提示词',
                assemble(req) { return req.base + '\nMARK' }
              })
            }
            """.trimIndent(),
        )
        val script = PluginModule.dryRunScript(module)
        assertTrue(script.contains("__dsh.storages.push"))
        assertTrue(script.contains("__dsh.prompts.push"))
        val resident = PluginModule.residentScript(module)
        assertTrue(resident.contains("__dshStorageDispatch"))
        assertTrue(resident.contains("__dshPromptDispatch"))
        assertTrue(resident.contains("storages: __dsh.storages"))
        assertTrue(resident.contains("prompts: __dsh.prompts"))
        assertTrue(resident.contains("storage.list"))
        assertTrue(resident.contains("prompt.assemble"))

        val run = PluginModule.parseDryRun(
            """
            {"applied":true,
             "storages":[{"id":"builtin.storage.jsonl","label":"只读导出器","readOnly":false,"hasList":true,"hasLoad":true}],
             "prompts":[{"id":"builtin.prompt.dsh","label":"标记提示词"},{"id":"  "}]}
            """.trimIndent(),
        )
        val storage = run.storages.single()
        assertEquals("builtin.storage.jsonl", storage.id)
        // v1 第三方存储只读：插件写 readOnly=false 也不放开写入。
        assertTrue(storage.readOnly)
        assertTrue(storage.hasList)
        assertTrue(storage.hasLoad)
        val prompt = run.prompts.single()
        assertEquals("builtin.prompt.dsh", prompt.id)
        assertEquals("标记提示词", prompt.label)
        // 旧试运行结果没有 storages/prompts → 空表（向后兼容）
        val legacy = PluginModule.parseDryRun("""{"applied":true}""")
        assertTrue(legacy.storages.isEmpty())
        assertTrue(legacy.prompts.isEmpty())
    }

    @Test
    fun parsesCapabilitiesAndResidentDispatch() {
        val module = PluginModule.transform(
            """
            export const apply = (ctx) => {
              ctx.capability.register({
                id: 'nfc',
                label: 'NFC 读卡',
                description: '演示能力',
                kind: 'device',
                invoke(args) { return { ok: true, value: { tag: 'M85F' } } }
              })
              ctx.capability.register({ id: 'bad id', invoke() {} })
              ctx.capability.register({ id: 'no-invoke' })
            }
            """.trimIndent(),
        )
        val script = PluginModule.dryRunScript(module)
        assertTrue(script.contains("__dsh.capabilities.push"))
        assertTrue(script.contains("capability.register"))
        val resident = PluginModule.residentScript(module)
        assertTrue(resident.contains("__dshCapabilityDispatch"))
        assertTrue(resident.contains("capabilities: __dsh.capabilities"))
        assertTrue(resident.contains("capability.invoke"))

        val run = PluginModule.parseDryRun(
            """
            {"applied":true,"capabilities":[
              {"id":"nfc","label":"NFC 读卡","description":"演示能力","kind":"device"},
              {"id":"nfc","label":"重复"},
              {"id":"  "}
            ]}
            """.trimIndent(),
        )
        val capability = run.capabilities.single()
        assertEquals("nfc", capability.id)
        assertEquals("NFC 读卡", capability.label)
        assertEquals("演示能力", capability.description)
        assertEquals("device", capability.kind)
        // 旧试运行结果没有 capabilities → 空表（向后兼容）
        assertTrue(PluginModule.parseDryRun("""{"applied":true}""").capabilities.isEmpty())
    }

    @Test
    fun parsesLlmProvidersAndRoutes() {
        val module = PluginModule.transform(
            """
            export const apply = (ctx) => {
              ctx.llm.register({ id: 'e2.provider', label: 'E2 供应商', protocol: 'openai',
                baseUrl: 'https://api.example.com', model: 'e2-model', apiKey: 'sk-test' })
              ctx.llm.register({ id: '../bad id' })
              ctx.ui.registerRoute({ path: '/e2/panel', title: 'E2 面板', description: '演示页' })
              ctx.ui.registerRoute('/e2/string')
              ctx.ui.registerView({ mount() {} })
              ctx.sessionProjections.register({ key: 'todos' })
            }
            """.trimIndent(),
        )
        val script = PluginModule.dryRunScript(module)
        assertTrue(script.contains("__dsh.llmProviders.push"))
        assertTrue(script.contains("__dsh.routes.push"))
        // registerView 仍是明确的 unsupported；sessionProjections 已经真做了（M84-H）
        assertTrue(script.contains("__dshUnsupported('ui.registerView')"))
        assertTrue(script.contains("__dsh.projections.push"))
        // 常驻脚本要装 dispatch 入口（宿主事件回传协议）
        val resident = PluginModule.residentScript(module)
        assertTrue(resident.contains("__dshDispatch"))
        assertTrue(resident.contains("__dshProjectionDispatch"))

        val run = PluginModule.parseDryRun(
            """
            {"applied":true,
             "llmProviders":[
               {"id":"e2.provider","label":"E2 供应商","protocol":"openai","baseUrl":"https://api.example.com",
                "model":"e2-model","apiKey":"sk-test"},
               {"id":"../bad id","label":"bad"},
               {"id":"m84j.echo","label":"JS 流","hasStream":true},
               {"id":"e2.provider","label":"重复"}],
             "routes":[{"id":"/e2/panel","title":"E2 面板","description":"演示页"},{"id":"About","title":"整页","page":true},{"id":""}],
             "projections":[{"key":"m84hTurns","stateVersion":2,"hasView":true},
                            {"key":"m84hTurns","stateVersion":9},
                            {"key":"  "}],
             "unsupported":["ui.registerView"]}
            """.trimIndent(),
        )
        val provider = run.llmProviders.first()
        assertEquals("e2.provider", provider.id)
        assertEquals("openai", provider.protocol)
        assertEquals("e2-model", provider.model)
        assertEquals("sk-test", provider.apiKey)
        // 重复 id / 非法 id 丢掉，只留前两条（第二条带 stream 回调）
        assertEquals(2, run.llmProviders.size)
        assertTrue(run.llmProviders[1].hasStream)
        assertEquals("openai", run.llmProviders[0].protocol)
        assertEquals(2, run.routes.size)
        assertEquals("/e2/panel", run.routes[0].id)
        assertEquals("演示页", run.routes[0].description)
        assertEquals("About", run.routes[1].id)
        assertTrue(run.routes[1].page)
        val projection = run.projections.single()
        assertEquals("m84hTurns", projection.key)
        assertEquals(2, projection.stateVersion)
        assertTrue(projection.hasView)
        assertTrue(projection.hasApply)
        assertEquals(listOf("ui.registerView"), run.unsupported)

        // 旧试运行结果没有这两个字段 → 空表（向后兼容）
        assertTrue(PluginModule.parseDryRun("""{"applied":true}""").llmProviders.isEmpty())
        assertTrue(PluginModule.parseDryRun("""{"applied":true}""").routes.isEmpty())
        assertTrue(PluginModule.parseDryRun("""{"applied":true}""").projections.isEmpty())
    }

    @Test
    fun parsesInvokeResult() {
        val ok = PluginModule.parseInvoke("""{"ok":true,"text":"hello"}""")
        assertTrue(ok.ok)
        assertEquals("hello", ok.text)
        assertNull(ok.error)

        val failed = PluginModule.parseInvoke("""{"ok":false,"error":"Error: boom"}""")
        assertTrue(!failed.ok)
        assertEquals("Error: boom", failed.error)

        assertTrue(!PluginModule.parseInvoke("oops").ok)
    }

    @Test
    fun capturesConfigSchemaAndInjectsConfig() {
        val module = PluginModule.transform(
            """
            export const Config = { city: { type: 'string', default: 'Shanghai' } }
            export const apply = (ctx, config) => { ctx.tools.register({ name: 'x', execute: () => config.city }) }
            """.trimIndent(),
        )
        val script = PluginModule.dryRunScript(module, configJson = """{"city":"Beijing"}""")
        assertTrue(script.contains("""const __dshConfigRaw = JSON.parse("{\"city\":\"Beijing\"}");"""))
        assertTrue(script.contains("__e.apply(__ctx, __dshConfigRaw)"))

        val run = PluginModule.parseDryRun(
            """{"applied":true,"configSchema":{"city":{"type":"string","default":"Shanghai"}},
                "tools":[]}""",
        )
        assertEquals("""{"type":"object","properties":{"city":{"type":"string","default":"Shanghai"}}}""", run.configSchema)
        assertNull(run.configError)

        val broken = PluginModule.parseDryRun("""{"applied":true,"configError":"Config 序列化不出来","tools":[]}""")
        assertNull(broken.configSchema)
        assertEquals("Config 序列化不出来", broken.configError)
    }
}
