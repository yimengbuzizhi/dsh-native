package xin.dshaa.core.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
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
