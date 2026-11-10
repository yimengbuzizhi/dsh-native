package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 一个 `export` 声明：导出名 → 模块内的本地标识符。 */
data class ModuleExport(val exported: String, val local: String)

/** ESM → 可塞进 `new Function` 的代码（imports 改 `__require`，exports 结尾统一收集）。 */
data class TransformedModule(
    val code: String,
    val imports: List<String>,
    val exports: List<ModuleExport>,
    val warnings: List<String>,
)

/**
 * DSH 插件模块的**试运行**支持（docs/05：插件页加载一次就是干跑，报错直接给 AI 看）。
 *
 * 做三件事，都不依赖 Node：
 * 1. [transform]：把 ESM 的 `import` / `export` 改成 CJS 形态（QuickJS 的全局脚本没有模块加载器，
 *    而 DSH 插件基本都是 `lib/index.js` 的 ESM 产物）。
 * 2. [dryRunScript]：拼一段自包含 JS：注入 `ctx`（tools.register 收集注册信息）、`defineTool`、
 *    `z` 这些宿主符号，跑插件模块，再调一次 `apply(ctx, {})`，把结果 `JSON.stringify` 返回。
 * 3. [parseDryRun]：把上面那段 JSON 解成 [PluginDryRun]。
 *
 * 边界（故意不做）：不解析相对 `require('./x')`（DSH 插件要求自包含打包）、不真正执行工具
 * （试运行只回答「这个包是什么、注册了什么、哪里报错」）。`apply` 返回 Promise 时会等它结算
 * （M31；试运行有超时中断，调用侧有工具超时）。
 *
 * 为什么是手写状态机而不是上 JS 打包器：宁抄勿研——插件页只需要「能跑一次、报错可见」，
 * 不引入 esbuild/babel 这类重依赖（APK 已经 98MB）。
 */
object PluginModule {

    private val fromImport = Regex("""^import\s+([\s\S]+?)\s+from\s+['"]([^'"]+)['"]\s*;?$""")
    private val bareImport = Regex("""^import\s+['"]([^'"]+)['"]\s*;?$""")
    private val exportDecl = Regex("""^export\s+(?:const|let|var|async\s+function\*?|function\*?|class)\s+([A-Za-z_$][\w$]*)""")
    private val exportList = Regex("""^export\s*\{([^}]*)\}\s*(?:from\s*['"]([^'"]+)['"]\s*)?;?\s*$""")
    private val exportStar = Regex("""^export\s*\*\s*(?:as\s+([A-Za-z_$][\w$]*)\s*)?from\s*['"]([^'"]+)['"]\s*;?$""")
    private val identifier = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*$""")

    fun transform(source: String): TransformedModule {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<String>(lines.size + 16)
        val imports = LinkedHashSet<String>()
        val exports = ArrayList<ModuleExport>()
        val warnings = ArrayList<String>()
        var counter = 0
        var i = 0

        fun newModuleVar(): String {
            counter += 1
            return "__dshm$counter"
        }

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()
            when {
                line.startsWith("import ") && !line.startsWith("import(") -> {
                    var statement = line
                    var last = i
                    while (!isCompleteImport(statement) && last + 1 < lines.size && last - i < 20) {
                        last += 1
                        statement += " " + lines[last].trim()
                    }
                    val parsed = parseImport(statement, ::newModuleVar)
                    if (parsed == null) {
                        warnings += "第 ${i + 1} 行的 import 没识别，原样保留"
                        out += raw
                        i += 1
                    } else {
                        imports += parsed.first
                        out += parsed.second
                        i = last + 1
                    }
                }

                line.startsWith("export default") -> {
                    val rest = line.removePrefix("export default").trim()
                    val linesOut = defaultExportLines(rest, exports)
                    if (linesOut == null) {
                        warnings += "第 ${i + 1} 行的 export default 没识别，原样保留"
                        out += raw
                    } else {
                        out += linesOut
                    }
                    i += 1
                }

                line.startsWith("export *") -> {
                    val match = exportStar.find(line)
                    if (match == null) {
                        warnings += "第 ${i + 1} 行的 export * 没识别，原样保留"
                        out += raw
                    } else {
                        val alias = match.groupValues[1]
                        val module = match.groupValues[2]
                        imports += module
                        val v = newModuleVar()
                        out += "const $v = __require(${quote(module)});"
                        if (alias.isEmpty()) {
                            out += "for (const __k in $v) { if (__k !== 'default' && __k !== '__esModule') { exports[__k] = $v[__k]; } }"
                        } else {
                            out += "const $alias = $v;"
                            exports += ModuleExport(alias, alias)
                        }
                    }
                    i += 1
                }

                line.startsWith("export {") -> {
                    val match = exportList.find(line)
                    if (match == null) {
                        warnings += "第 ${i + 1} 行的 export {} 没识别，原样保留"
                        out += raw
                    } else {
                        val specs = match.groupValues[1]
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        val module = match.groupValues[2]
                        if (module.isEmpty()) {
                            specs.forEach { spec ->
                                val parts = spec.split(Regex("""\s+as\s+""")).map { it.trim() }
                                val local = parts[0]
                                val exported = parts.getOrElse(1) { local }
                                if (identifier.matches(local)) exports += ModuleExport(exported, local)
                            }
                        } else {
                            imports += module
                            val v = newModuleVar()
                            out += "const $v = __require(${quote(module)});"
                            specs.forEach { spec ->
                                val parts = spec.split(Regex("""\s+as\s+""")).map { it.trim() }
                                val sourceName = parts[0]
                                val exported = parts.getOrElse(1) { sourceName }
                                if (identifier.matches(exported)) {
                                    out += "const $exported = $v.${sourceName};"
                                    exports += ModuleExport(exported, exported)
                                }
                            }
                        }
                    }
                    i += 1
                }

                line.startsWith("export ") -> {
                    val match = exportDecl.find(line)
                    if (match != null) {
                        val name = match.groupValues[1]
                        out += stripExportPrefix(raw)
                        exports += ModuleExport(name, name)
                    } else if (Regex("""^export\s+(?:const|let|var)\s*\{""").containsMatchIn(line)) {
                        out += stripExportPrefix(raw)
                        warnings += "解构导出不登记名字（不影响试运行）"
                    } else {
                        warnings += "第 ${i + 1} 行的 export 没识别，原样保留"
                        out += raw
                    }
                    i += 1
                }

                else -> {
                    out += raw
                    i += 1
                }
            }
        }
        return TransformedModule(
            code = out.joinToString("\n"),
            imports = imports.toList(),
            exports = exports.distinctBy { it.exported },
            warnings = warnings,
        )
    }

    /** 生成可直接交给 `QuickJs.evaluate` 的试运行脚本；返回值是 JSON 字符串。 */
    fun dryRunScript(module: TransformedModule, cwd: String? = null, configJson: String = "{}"): String =
        moduleScript(module, cwd, configJson) + EXPORTS_APPLY +
            "\n  return Promise.resolve(__dshApply).then(function () { return JSON.stringify(__dsh); });\n"

    /**
     * 生成「执行插件里某个工具」的脚本（M27）。
     *
     * 每次工具调用都是一个独立 QuickJS 实例：重新跑模块、重新 `apply(ctx)`，再调
     * `__dshDefs[tool].execute(args, exec)`。这样插件**注册逻辑和真正执行永远一致**，
     * 不会出现「试运行注册了、真正跑却换了闭包」；代价是每次调用重跑一次模块（工具调用低频，可接受）。
     *
     * [argsJson] 是模型给的参数 JSON 文本；[toolName] 是注册时的工具名。
     * 返回值仍是 JSON 字符串：`{ok:true,text}` 或 `{ok:false,error}`。
     */
    fun invokeScript(
        module: TransformedModule,
        toolName: String,
        argsJson: String,
        cwd: String? = null,
        configJson: String = "{}",
    ): String {
        val name = kotlinx.serialization.json.JsonPrimitive(toolName).toString()
        val args = kotlinx.serialization.json.JsonPrimitive(argsJson).toString()
        return moduleScript(module, cwd, configJson) + EXPORTS_APPLY + """
  const __toolName = $name;
  const __toolArgs = $args;
  function __dshFailure(err) {
    const message = (err && err.message !== undefined)
      ? (String(err.name || 'Error') + ': ' + String(err.message))
      : String(err);
    return (err && err.stack) ? (message + '\n' + String(err.stack)) : message;
  }
  function __dshRender(value) {
    if (value === undefined || value === null) { return ''; }
    if (typeof value === 'string') { return value; }
    if (typeof value === 'object' && typeof value.text === 'string') { return value.text; }
    try { return JSON.stringify(value, null, 2); } catch (e) { return String(value); }
  }
  function __dshInvoke() {
    const __toolDef = __dshDefs[__toolName];
    if (!__toolDef) {
      const __missing = '插件没有注册工具 ' + __toolName;
      return JSON.stringify({
        ok: false,
        error: __dsh.errors.length ? (__missing + '；apply 报错：' + __dsh.errors.join('\n')) : __missing
      });
    }
    if (typeof __toolDef.execute !== 'function') {
      return JSON.stringify({ ok: false, error: '工具 ' + __toolName + ' 没有 execute 函数' });
    }
    try {
      const __result = __toolDef.execute(JSON.parse(__toolArgs), __exec);
      return Promise.resolve(__result).then(
        function(value) { return JSON.stringify({ ok: true, text: __dshRender(value) }); },
        function(err) { return JSON.stringify({ ok: false, error: __dshFailure(err) }); }
      );
    } catch (err) {
      return JSON.stringify({ ok: false, error: __dshFailure(err) });
    }
  }
  // apply 可能是 async（返回 Promise）：等它结算再执行工具，别在注册完成前就查 __dshDefs。
  return Promise.resolve(__dshApply).then(__dshInvoke);
"""
    }

    /** 解析 [invokeScript] 的返回；坏 JSON 当成执行失败。 */
    fun parseInvoke(json: String): PluginInvokeResult {
        val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return PluginInvokeResult(ok = false, error = "插件执行没有返回 JSON：${json.take(300)}")
        val ok = runCatching { obj["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() }.getOrNull() ?: false
        val text = obj["text"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
        val error = obj["error"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        return PluginInvokeResult(ok = ok, text = text, error = error)
    }

    /**
     * 生成「执行插件斜杠命令」的脚本（M57 第二批，DSH `ctx.commands.register`）。
     *
     * 与 [invokeScript] 同一条链路：重跑模块 → `apply(ctx)` → 调
     * `__dshDefs['command:<name>'].handler({agent, rawInput, attachments})`。
     * 返回值 `{ok:true,text}` / `{ok:false,error}`；handler 返回 `{kind:'error',text}` 也算失败。
     */
    fun commandScript(
        module: TransformedModule,
        commandName: String,
        rawInput: String,
        cwd: String? = null,
        configJson: String = "{}",
    ): String {
        val name = kotlinx.serialization.json.JsonPrimitive(commandName).toString()
        val input = kotlinx.serialization.json.JsonPrimitive(rawInput).toString()
        return moduleScript(module, cwd, configJson) + EXPORTS_APPLY + """
  const __cmdName = $name;
  const __cmdInput = $input;
  function __dshCommandFailure(err) {
    const message = (err && err.message !== undefined)
      ? (String(err.name || 'Error') + ': ' + String(err.message))
      : String(err);
    return (err && err.stack) ? (message + '\n' + String(err.stack)) : message;
  }
  function __dshRunCommand() {
    const __cmdDef = __dshDefs['command:' + __cmdName];
    if (!__cmdDef || typeof __cmdDef.handler !== 'function') {
      return JSON.stringify({ ok: false, error: '插件没有注册命令 ' + __cmdName });
    }
    try {
      const __result = __cmdDef.handler({
        agent: undefined,
        rawInput: __cmdInput,
        attachments: []
      });
      return Promise.resolve(__result).then(
        function(value) {
          if (value && value.kind === 'error') {
            return JSON.stringify({ ok: false, error: String(value.text || '命令失败') });
          }
          if (value && typeof value.text === 'string') {
            return JSON.stringify({ ok: true, text: value.text });
          }
          if (typeof value === 'string') {
            return JSON.stringify({ ok: true, text: value });
          }
          try { return JSON.stringify({ ok: true, text: JSON.stringify(value, null, 2) }); }
          catch (e) { return JSON.stringify({ ok: true, text: String(value) }); }
        },
        function(err) { return JSON.stringify({ ok: false, error: __dshCommandFailure(err) }); }
      );
    } catch (err) {
      return JSON.stringify({ ok: false, error: __dshCommandFailure(err) });
    }
  }
  return Promise.resolve(__dshApply).then(__dshRunCommand);
"""
    }

    /** QuickJS 跑插件模块的公共前半段：注入宿主符号 → 执行模块 → 收集 exports。 */
    private fun moduleScript(module: TransformedModule, cwd: String?, configJson: String): String {
        val sb = StringBuilder()
        // 插件里的 `process.cwd()` / 相对路径都按当前工作区解析（和宿主 fs 的根保持一致）。
        sb.append("  const __dshCwd = ").append(quote(cwd ?: "/")).append(";\n")
        // apply(ctx, config) 的 config：宿主已经把配置 schema 的默认值和用户值合并好，
        // 这里只负责安全地注进 JS（JSON.parse 避免字符串拼接产生非法表达式）。
        sb.append("  const __dshConfigRaw = JSON.parse(")
            .append(kotlinx.serialization.json.JsonPrimitive(configJson).toString())
            .append(");\n")
        sb.append(PRELUDE)
        sb.append(HOST_BRIDGE)
        sb.append("  const __ret = (function(exports, module, require){\n")
        sb.append(module.code)
        sb.append("\nreturn {")
        module.exports.forEachIndexed { index, export ->
            if (!identifier.matches(export.local)) return@forEachIndexed
            if (index > 0) sb.append(", ")
            sb.append(quote(export.exported)).append(": (typeof ")
                .append(export.local).append(" === 'undefined' ? undefined : ").append(export.local).append(")")
        }
        sb.append("};\n")
        sb.append("  })(__exports, __module, __require);\n")
        return sb.toString()
    }

    /** 解析试运行脚本的返回 JSON；坏 JSON 也返回一个带 error 的结果，不抛。 */
    fun parseDryRun(json: String): PluginDryRun {
        val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return PluginDryRun(error = "试运行没有返回 JSON：${json.take(300)}")
        val tools = runCatching {
            obj["tools"]?.jsonArray?.mapNotNull { element ->
                val tool = element.jsonObject
                val name = tool.string("name") ?: return@mapNotNull null
                val schemaObject = tool["parameters"] as? kotlinx.serialization.json.JsonObject
                val state = tool.string("parametersState")
                val parameters = when {
                    // 明确「没写参数」→ 空 object schema（模型可以不带参数调用）
                    state == "missing" -> PluginSchema.emptyObjectSchema().toString()
                    // 写了但认不出（典型：schemastery 的 z.object）→ 不注入，别硬造
                    state == "unsupported" -> null
                    schemaObject != null -> PluginSchema.normalize(schemaObject)?.toString()
                    // 旧格式（没有 parametersState）：按 null 当没参数处理，保持向后兼容
                    else -> PluginSchema.emptyObjectSchema().toString()
                }
                PluginTool(
                    name = name,
                    description = tool.string("description").orEmpty(),
                    parameters = parameters,
                )
            }.orEmpty()
        }.getOrDefault(emptyList())
        val errors = runCatching {
            obj["errors"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        }.getOrDefault(emptyList())
        val configSchemaObject = obj["configSchema"] as? kotlinx.serialization.json.JsonObject
        val configSchema = configSchemaObject?.let { PluginSchema.normalize(it)?.toString() }
        val configError = obj.string("configError")
            ?: if (configSchemaObject != null && configSchema == null) "Config 参数形状认不出（这个类型还不能转成 JSON Schema）" else null
        fun sections(key: String): List<PluginPromptSection> = runCatching {
            obj[key]?.jsonArray?.mapNotNull { element ->
                val item = element.jsonObject
                val name = item.string("name") ?: return@mapNotNull null
                PluginPromptSection(
                    name = name,
                    order = item["order"]?.let { runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() } ?: 100,
                    text = item.string("text").orEmpty(),
                    complete = item["complete"]?.let {
                        runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
                    } ?: false,
                )
            }.orEmpty()
        }.getOrDefault(emptyList())
        val commands = runCatching {
            obj["commands"]?.jsonArray?.mapNotNull { element ->
                val item = element.jsonObject
                val name = item.string("name") ?: return@mapNotNull null
                PluginCommand(name = name, description = item.string("description").orEmpty())
            }.orEmpty()
        }.getOrDefault(emptyList())
        // M74：`ctx.systemPrompt.variable` 求值出的快照（name -> 文本），供 `{{name}}` 替换
        val promptVariables = runCatching {
            (obj["promptVariables"] as? kotlinx.serialization.json.JsonObject)
                ?.mapNotNull { (key, element) ->
                    val text = runCatching { element.jsonPrimitive.contentOrNull }.getOrNull() ?: return@mapNotNull null
                    key to text
                }?.toMap().orEmpty()
        }.getOrDefault(emptyMap())
        return PluginDryRun(
            name = obj.string("name"),
            version = obj.string("version"),
            inject = obj.stringList("inject"),
            tools = tools,
            imports = obj.stringList("imports"),
            relativeImports = obj.stringList("relativeImports"),
            applied = obj["applied"]?.let { runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull() } ?: false,
            error = errors.joinToString("\n").ifBlank { null },
            configSchema = configSchema,
            configError = configError,
            promptSections = sections("promptSections"),
            contexts = sections("contexts"),
            promptVariables = promptVariables,
            commands = commands,
            unsupported = obj.stringList("unsupported"),
        )
    }

    private fun isCompleteImport(statement: String): Boolean {
        val text = statement.trim()
        return text.endsWith(";") || fromImport.matches(text) || bareImport.matches(text)
    }

    /** 解析一条 import；返回 (模块名, 生成的 CJS 行)。 */
    private fun parseImport(statement: String, newModuleVar: () -> String): Pair<String, List<String>>? {
        bareImport.find(statement)?.let { match ->
            val module = match.groupValues[1]
            return module to listOf("__require(${quote(module)});")
        }
        val match = fromImport.find(statement) ?: return null
        val clause = match.groupValues[1].trim()
        val module = match.groupValues[2]
        val v = newModuleVar()
        val lines = ArrayList<String>()
        lines += "const $v = __require(${quote(module)});"

        if (clause.startsWith("*")) {
            val ns = clause.removePrefix("*").trim().removePrefix("as").trim()
            if (!identifier.matches(ns)) return null
            lines += "const $ns = $v;"
            return module to lines
        }

        // `import D, * as ns from 'm'`
        Regex("""^([A-Za-z_$][\w$]*)\s*,\s*\*\s*as\s+([A-Za-z_$][\w$]*)$""").find(clause)?.let { pair ->
            lines += "const ${pair.groupValues[1]} = __dshDefault($v);"
            lines += "const ${pair.groupValues[2]} = $v;"
            return module to lines
        }

        val braceStart = clause.indexOf('{')
        val braceEnd = clause.lastIndexOf('}')
        val defaultPart = (if (braceStart >= 0) clause.substring(0, braceStart) else clause)
            .trim()
            .trimEnd(',')
            .trim()
        if (defaultPart.isNotEmpty()) {
            if (!identifier.matches(defaultPart)) return null
            lines += "const $defaultPart = __dshDefault($v);"
        }
        if (braceStart in 0 until braceEnd) {
            val specs = clause.substring(braceStart + 1, braceEnd)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { spec ->
                    val parts = spec.split(Regex("""\s+as\s+""")).map { it.trim() }
                    if (parts.size >= 2) "${parts[0]}: ${parts[1]}" else parts[0]
                }
            if (specs.isNotEmpty()) lines += "const { ${specs.joinToString(", ")} } = $v;"
        }
        return module to lines
    }

    /** `export default ...` → 声明行；返回 null 表示没识别。 */
    private fun defaultExportLines(rest: String, exports: MutableList<ModuleExport>): List<String>? {
        if (rest.isEmpty()) return null
        val namedFunction = Regex("""^(?:async\s+)?function\s*\*?\s*([A-Za-z_$][\w$]*)\s*\(""").find(rest)
        if (namedFunction != null) {
            val name = namedFunction.groupValues[1]
            exports += ModuleExport("default", name)
            return listOf(rest)
        }
        if (Regex("""^(?:async\s+)?function\s*\*?\s*\(""").containsMatchIn(rest)) {
            exports += ModuleExport("default", "__dshDefaultExport")
            return listOf("const __dshDefaultExport = $rest")
        }
        val namedClass = Regex("""^class\s+([A-Za-z_$][\w$]*)[\s{]""").find(rest)
        if (namedClass != null) {
            val name = namedClass.groupValues[1]
            exports += ModuleExport("default", name)
            return listOf(rest)
        }
        exports += ModuleExport("default", "__dshDefaultExport")
        return listOf("const __dshDefaultExport = $rest")
    }

    private fun stripExportPrefix(line: String): String {
        val match = Regex("""^(\s*)export\s+""").find(line) ?: return line
        return match.groupValues[1] + line.substring(match.range.last + 1)
    }

    private fun quote(text: String): String =
        "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun kotlinx.serialization.json.JsonObject.stringList(key: String): List<String> =
        this[key]?.let { element ->
            runCatching { element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
        }.orEmpty()

    /**
     * 试运行脚本的固定前半段。
     *
     * 注意：`core:js` 的桥把整段脚本包进 `(function(){ ... })()` 再 `JS_Eval`，所以这里**不要**
     * 自己再套一层 IIFE，结尾用**顶层 `return`** 把结果交给桥（写成表达式的返回值会被丢掉，
     * 表现为「completed 但 value=null」——第一版就踩了这个坑）。
     *
     * 宿主符号刻意做成「宽松代理」：真实 DSH 插件会访问 ctx 上五花八门的服务
     * （ctx.llm / ctx.sessionProjections / ctx.http …），试运行阶段只关心
     * 「跑不跑得起来、注册了什么」，不因为这些服务没实现就误报失败。
     */
    private val PRELUDE = """
  const __dsh = { name: null, version: null, inject: [], tools: [], errors: [], imports: [], relativeImports: [], applied: false, configSchema: null, configError: null, promptSections: [], contexts: [], promptVariables: {}, commands: [], unsupported: [] };
  function __dshUnsupported(name) {
    if (__dsh.unsupported.indexOf(name) < 0) { __dsh.unsupported.push(name); }
    return __noop;
  }
  const __dshDefs = {};
  function __dshSchema(value) {
    if (value === undefined || value === null) { return null; }
    try {
      if (typeof value.toJSON === 'function') {
        const converted = value.toJSON();
        if (converted && typeof converted === 'object') { return JSON.parse(JSON.stringify(converted)); }
      }
    } catch (e) {}
    try {
      if (typeof value === 'object') { return JSON.parse(JSON.stringify(value)); }
    } catch (e) {}
    return null;
  }
  const __noop = function(){};
  // ── 全局计时器（M63）：Operit 的 time.js 等包用 setTimeout ───────────────
  // QuickJS 没有自己的事件循环线程；借宿主异步能力 `timer.wait`（宿主线程等待）在
  // Promise 完成时回调，和 net.fetch 走同一条异步通道。
  let __dshTimerSeq = 1;
  const __dshTimers = {};
  function __dshTimerCallback(handler, extra) {
    if (typeof handler === 'function') { return function(){ return handler.apply(null, extra); }; }
    return function(){ return (0, eval)(String(handler)); };
  }
  function setTimeout(handler, delay) {
    const id = __dshTimerSeq++;
    const extra = Array.prototype.slice.call(arguments, 2);
    const fn = __dshTimerCallback(handler, extra);
    const wait = Math.max(0, Number(delay) || 0);
    __dshTimers[id] = true;
    __dshCallAsync('timer.wait', JSON.stringify({ ms: wait })).then(function() {
      if (__dshTimers[id] !== true) { return; }
      delete __dshTimers[id];
      try { fn(); } catch (e) { try { console.error('setTimeout callback failed', e); } catch (_) {} }
    }, function() { delete __dshTimers[id]; });
    return id;
  }
  function clearTimeout(id) { if (id !== undefined && id !== null) { delete __dshTimers[id]; } }
  function setInterval(handler, delay) {
    const id = __dshTimerSeq++;
    const extra = Array.prototype.slice.call(arguments, 2);
    const fn = __dshTimerCallback(handler, extra);
    const wait = Math.max(1, Number(delay) || 0);
    let runs = 0;
    __dshTimers[id] = true;
    const tick = function() {
      if (__dshTimers[id] !== true) { return; }
      try { fn(); } catch (e) {}
      runs += 1;
      if (runs >= 1000) { delete __dshTimers[id]; return; }
      __dshCallAsync('timer.wait', JSON.stringify({ ms: wait })).then(tick, function() { delete __dshTimers[id]; });
    };
    __dshCallAsync('timer.wait', JSON.stringify({ ms: wait })).then(tick, function() { delete __dshTimers[id]; });
    return id;
  }
  function clearInterval(id) { clearTimeout(id); }
  function setImmediate(handler) {
    return setTimeout.apply(null, [handler, 0].concat(Array.prototype.slice.call(arguments, 1)));
  }
  function clearImmediate(id) { clearTimeout(id); }
  function __permissive(extra) {
    const base = function(){ return base; };
    if (extra) { for (const key in extra) { base[key] = extra[key]; } }
    // 坑（M29 真机）：get 里返回裸 base 的话，`const { z } = require('zod')` 解构后拿到的是
    // target（不是代理），`z.string()` 后面的链式调用就全断了（真包 dsh-tool-todo 就是这么炸的）。
    // 所以未知属性/调用/构造一律返回代理本身，链式调用能一直走下去。
    let self = null;
    try {
      self = new Proxy(base, {
        get: function(t, p) { if (p === 'then') { return undefined; } if (p in t) { return t[p]; } return self; },
        apply: function() { return self; },
        construct: function() { return self; }
      });
      return self;
    } catch (e) { return base; }
  }
  // ── 最小 schemastery（M29）──────────────────────────────────────────────
  // 抄自 @deepseek-ai/schemastery 3.18.2 的 lib/index.mjs：字段名（type/dict/list/inner/sKey/meta）、
  // uid+refs 序列化、链式方法都对齐（docs/52 有与真实包的逐字段对照）。只做「建 schema + 序列化」，
  // 不做严格校验：试运行和参数注入都不需要 validator。认不出的类型由 Kotlin 侧放弃转换。
  let __zsNextUid = 0;
  let __zsRefs = null;
  function __zsClone(value) {
    return (value !== null && typeof value === 'object') ? JSON.parse(JSON.stringify(value)) : value;
  }
  const __zsProto = Object.create(Function.prototype);
  function __zsNew(options) {
    const schema = function (data) { return __zsResolve(data, schema); };
    if (options) { Object.assign(schema, options); }
    Object.defineProperty(schema, 'uid', { value: __zsNextUid++ });
    Object.setPrototypeOf(schema, __zsProto);
    schema.meta = Object.assign({}, schema.meta);
    return schema;
  }
  function __zsCopy(schema) {
    const copy = function (data) { return __zsResolve(data, copy); };
    Object.assign(copy, schema);
    Object.defineProperty(copy, 'uid', { value: __zsNextUid++ });
    Object.setPrototypeOf(copy, __zsProto);
    copy.meta = Object.assign({}, schema.meta);
    return copy;
  }
  function __zsFrom(value) {
    if (typeof value === 'function' && value.type !== undefined) { return value; }
    if (value !== null && typeof value === 'object' && value.type) { return __zsNew(value); }
    if (value === String) { return __zsType('string').required(); }
    if (value === Number) { return __zsType('number').required(); }
    if (value === Boolean) { return __zsType('boolean').required(); }
    return __zsType('any');
  }
  function __zsType(type, extra) {
    const options = { type: type };
    if (extra) { Object.assign(options, extra); }
    return __zsNew(options);
  }
  function __zsDict(dict) {
    const result = {};
    for (const key in (dict || {})) { result[key] = __zsFrom(dict[key]); }
    return result;
  }
  function __zsLazyInner(schema) {
    if (!schema.inner || schema.inner.type === undefined) {
      schema.inner = __zsFrom(schema.builder());
      schema.inner.meta = Object.assign({}, schema.meta, schema.inner.meta);
    }
    return schema.inner;
  }
  function __zsResolve(data, schema) {
    if (!schema) { return data; }
    let value = data;
    if (value === undefined || value === null) {
      if (schema.meta && schema.meta.default !== undefined) { value = __zsClone(schema.meta.default); }
      else { return value; }
    }
    const type = schema.type;
    if (type === 'object') {
      const dict = schema.dict || {};
      const source = (value !== null && typeof value === 'object') ? value : {};
      const result = {};
      for (const key in dict) { result[key] = __zsResolve(source[key], dict[key]); }
      for (const key in source) { if (!(key in result)) { result[key] = source[key]; } }
      return result;
    }
    if (type === 'dict') {
      const source = (value !== null && typeof value === 'object') ? value : {};
      const result = {};
      for (const key in source) { result[key] = __zsResolve(source[key], schema.inner); }
      return result;
    }
    if (type === 'array') {
      return Array.isArray(value) ? value.map(function (item) { return __zsResolve(item, schema.inner); }) : value;
    }
    if (type === 'tuple') {
      return Array.isArray(value) ? value.map(function (item, index) {
        const inner = schema.list[index];
        return inner ? __zsResolve(item, inner) : item;
      }) : value;
    }
    if (type === 'intersect') {
      let result = {};
      for (const inner of (schema.list || [])) { Object.assign(result, __zsResolve(value, inner)); }
      return result;
    }
    if (type === 'union') {
      for (const inner of (schema.list || [])) { try { return __zsResolve(value, inner); } catch (e) {} }
      return value;
    }
    if (type === 'lazy') { return __zsResolve(value, __zsLazyInner(schema)); }
    return value;
  }
  __zsProto.toJSON = function () {
    const plain = Object.assign({}, this);
    if (__zsRefs) {
      if (!__zsRefs[this.uid]) { __zsRefs[this.uid] = JSON.parse(JSON.stringify(plain)); }
      return this.uid;
    }
    __zsRefs = {};
    try {
      __zsRefs[this.uid] = JSON.parse(JSON.stringify(plain));
      const result = { uid: this.uid, refs: __zsRefs };
      __zsRefs = null;
      return result;
    } catch (error) {
      __zsRefs = null;
      throw error;
    }
  };
  function __zsMeta(key) {
    return function (value) {
      const copy = __zsCopy(this);
      copy.meta[key] = (value === undefined) ? true : value;
      return copy;
    };
  }
  for (const key of ['required', 'disabled', 'collapse', 'hidden', 'loose', 'default', 'link', 'comment', 'description', 'max', 'min', 'step']) {
    __zsProto[key] = __zsMeta(key);
  }
  __zsProto.role = function (role, extra) {
    const copy = __zsCopy(this);
    copy.meta.role = role;
    copy.meta.extra = extra;
    return copy;
  };
  __zsProto.pattern = function (regexp) {
    const copy = __zsCopy(this);
    copy.meta.pattern = { source: String(regexp && regexp.source !== undefined ? regexp.source : regexp), flags: String((regexp && regexp.flags) || '') };
    return copy;
  };
  __zsProto.extra = function (key, value) {
    const copy = __zsCopy(this);
    copy.meta[key] = value;
    return copy;
  };
  __zsProto.deprecated = function () {
    const copy = __zsCopy(this);
    copy.meta.badges = (copy.meta.badges || []).concat([{ text: 'deprecated', type: 'danger' }]);
    return copy;
  };
  __zsProto.experimental = function () {
    const copy = __zsCopy(this);
    copy.meta.badges = (copy.meta.badges || []).concat([{ text: 'experimental', type: 'warning' }]);
    return copy;
  };
  __zsProto.set = function (key, value) { this.dict[key] = value; return this; };
  __zsProto.push = function (value) { this.list.push(value); return this; };
  __zsProto.simplify = function (value) { return value; };
  __zsProto.i18n = function () { return __zsCopy(this); };
  __zsProto.toString = function () { return 'Schema<' + String(this.type) + '>'; };

  const __z = function (data) { return data; };
  __z.any = function () { return __zsType('any'); };
  __z.never = function () { return __zsType('never'); };
  __z.const = function (value) { return __zsType('const', { value: value }); };
  __z.string = function () { return __zsType('string'); };
  __z.number = function () { return __zsType('number'); };
  __z.boolean = function () { return __zsType('boolean'); };
  __z.bitset = function (bits) { return __zsType('bitset', { bits: bits || {} }); };
  __z.function = function () { return __zsType('function'); };
  __z.date = function () { return __zsType('date'); };
  __z.is = function (constructor) { return __zsType('is', { constructor: constructor }); };
  __z.object = function (dict) {
    const schema = __zsType('object', { dict: __zsDict(dict) });
    schema.meta.default = {};
    return schema;
  };
  __z.dict = function (inner, sKey) {
    const schema = __zsType('dict', { inner: __zsFrom(inner), sKey: sKey ? __zsFrom(sKey) : __z.string() });
    schema.meta.default = {};
    return schema;
  };
  __z.array = function (inner) {
    const schema = __zsType('array', { inner: __zsFrom(inner) });
    schema.meta.default = [];
    return schema;
  };
  __z.tuple = function (list) {
    const schema = __zsType('tuple', { list: (list || []).map(__zsFrom) });
    schema.meta.default = [];
    return schema;
  };
  __z.union = function (list) { return __zsType('union', { list: (list || []).map(__zsFrom) }); };
  __z.intersect = function (list) { return __zsType('intersect', { list: (list || []).map(__zsFrom) }); };
  __z.lazy = function (builder) {
    const schema = __zsType('lazy', { builder: builder });
    // 抄上游：inner 先放一个只有 toJSON 的占位对象，序列化时才真正建内层 schema。
    schema.inner = {
      toJSON: function () {
        if (!schema.inner || schema.inner.type === undefined) {
          schema.inner = __zsFrom(schema.builder());
          schema.inner.meta = Object.assign({}, schema.meta, schema.inner.meta);
        }
        return schema.inner.toJSON();
      }
    };
    return schema;
  };
  __z.natural = function () { return __z.number().step(1).min(0); };
  __z.percent = function () { return __z.number().step(0.01).min(0).max(1).role('slider'); };
  __z.from = __zsFrom;
  __z.resolve = function (data, schema) { return [__zsResolve(data, schema)]; };
  __z.extend = function () { return __z; };
  __z.ValidationError = Error;
  __z.transform = function (inner, callback, preserve) {
    const schema = __zsType('transform', { inner: __zsFrom(inner), callback: callback, preserve: preserve });
    if (typeof callback === 'function' && !callback.toJSON) { callback.toJSON = function () { return String(callback); }; }
    return schema;
  };
  __z.regExp = function (flag) { return __z.string().role('regexp', { flag: flag || '' }); };
  // 未知的静态成员（z.xxx）当 any：老实现是宽松代理，插件里少见的构造器不该让整个模块加载失败；
  // 它们序列化出来是 any，Kotlin 侧不会假装知道类型。
  const __zExpose = new Proxy(__z, {
    get: function (target, prop) {
      if (prop === 'then') { return undefined; }
      if (typeof prop === 'string' && !(prop in target)) {
        return function () { return __zsType('any'); };
      }
      return target[prop];
    }
  });
  // DSH 的 defineTool 参数 DSL：{ path: { type:'string', required:true, description } }。
  // 鲸歌工具表要的是 JSON Schema，这里在注册前转一层；已经是 JSON Schema 的原样放行。
  function __dshParamSchema(p) {
    if (!p || typeof p !== 'object') { return { type: 'string' }; }
    if (p.type === 'object' && p.properties) { return p; }
    const out = {};
    if (p.type) { out.type = p.type; }
    if (p.description !== undefined) { out.description = String(p.description); }
    if (p.enum !== undefined) { out.enum = p.enum; }
    if (p.default !== undefined) { out.default = p.default; }
    if (p.items !== undefined) { out.items = __dshParamSchema(p.items); }
    if (p.properties !== undefined) {
      out.type = 'object';
      out.properties = {};
      for (const k in p.properties) { out.properties[k] = __dshParamSchema(p.properties[k]); }
      if (p.required) { out.required = p.required; }
    }
    return out;
  }
  function __dshParametersSchema(params) {
    if (!params || typeof params !== 'object') { return null; }
    if (params.type === 'object' && params.properties) { return params; }
    const properties = {};
    const required = [];
    for (const key in params) {
      const def = params[key];
      properties[key] = __dshParamSchema(def);
      if (def && def.required === true) { required.push(key); }
    }
    const schema = { type: 'object', properties: properties };
    if (required.length > 0) { schema.required = required; }
    return schema;
  }
  const __defineTool = function(def) {
    if (def && typeof def === 'object') {
      const converted = __dshParametersSchema(def.parameters);
      if (converted) { def.parameters = converted; }
    }
    return def;
  };
  const __toolsRegister = function(def) {
    if (def && typeof def.name === 'string') {
      if (__dsh.tools.map(function(t) { return t.name; }).indexOf(def.name) < 0) {
        const __params = __dshSchema(def.parameters);
        let __state = 'ok';
        if (def.parameters === undefined || def.parameters === null) {
          __state = 'missing';
        } else if (__params === null) {
          __state = 'unsupported';
        }
        __dsh.tools.push({
          name: def.name,
          description: (def.description === undefined || def.description === null) ? '' : String(def.description),
          parameters: __params,
          parametersState: __state
        });
      }
      if (!__dshDefs[def.name]) { __dshDefs[def.name] = def; }
    }
    return __noop;
  };
  const __ctx = __permissive({
    tools: __permissive({
      register: __toolsRegister,
      get: function() { return undefined; },
      list: function() { return []; },
      names: function() { return __dsh.tools.map(function(t) { return t.name; }); }
    }),
    // P4（M57）：提示词段 / 动态上下文。text 允许是函数：注册时用当前 ctx 求值一次
    //（手机端没有「每轮请求再进 JS 求值」的常驻运行时，函数型按静态快照处理）。
    systemPrompt: __permissive({
      section: function(options) {
        if (!options || typeof options.name !== 'string' || !options.name) { return __noop; }
        let text = options.text;
        if (typeof text === 'function') {
          try { text = text(__ctx); } catch (e) { text = ''; }
        }
        __dsh.promptSections.push({
          name: String(options.name),
          order: (typeof options.order === 'number') ? options.order : 100,
          text: (text === undefined || text === null) ? '' : String(text),
          complete: options.complete === true
        });
        return __noop;
      },
      context: function(options) {
        if (!options || typeof options.name !== 'string' || !options.name) { return __noop; }
        let text = options.text;
        if (typeof text === 'function') {
          try { text = text(__ctx); } catch (e) { text = ''; }
        }
        __dsh.contexts.push({
          name: String(options.name),
          order: (typeof options.order === 'number') ? options.order : 100,
          text: (text === undefined || text === null) ? '' : String(text)
        });
        return __noop;
      },
      // M74：动态变量 `variable(name, fn)`——段文本里写 `{{name}}`，由 PromptPack 渲染时替换。
      // 上游是每轮请求前求值；手机端没有常驻 JS 运行时，按 apply 时求值一次存快照
      //（和上面函数型 text 的处理一致）。也容忍 `variable({name, value/fn})` 的写法。
      variable: function(name, value) {
        let key = name;
        let fn = value;
        if (name && typeof name === 'object') {
          key = name.name;
          fn = (name.value !== undefined) ? name.value : name.fn;
        }
        if (typeof key !== 'string' || !key) { return __noop; }
        let resolved = fn;
        if (typeof resolved === 'function') {
          try { resolved = resolved(__ctx); } catch (e) { resolved = ''; }
        }
        // 异步变量求不出值：不写空字符串覆盖，直接跳过（PromptPack 会保留 `{{name}}` 原样）
        if (resolved && typeof resolved === 'object' && typeof resolved.then === 'function') { return __noop; }
        if (resolved !== null && typeof resolved === 'object') {
          try { resolved = JSON.stringify(resolved); } catch (e) { resolved = String(resolved); }
        }
        __dsh.promptVariables[String(key)] = (resolved === undefined || resolved === null) ? '' : String(resolved);
        return __noop;
      },
      suppressRuntimeContext: function() { return __dshUnsupported('systemPrompt.suppressRuntimeContext'); }
    }),
    // P5（M57）：斜杠命令。handler 在 commandScript 里被调用，不进模型轮次。
    commands: __permissive({
      register: function(def) {
        if (!def || typeof def.name !== 'string') { return __noop; }
        const name = String(def.name).replace(/^\//, '');
        if (!/^[a-z0-9_-]{1,64}$/.test(name)) { return __noop; }
        if (__dsh.commands.map(function(c) { return c.name; }).indexOf(name) >= 0) { return __noop; }
        __dsh.commands.push({ name: name, description: (def.description === undefined || def.description === null) ? '' : String(def.description) });
        if (typeof def.handler === 'function') { __dshDefs['command:' + name] = def; }
        return __noop;
      }
    }),
    // P6（M57，鲸歌私有扩展点）：通知。DSH 本身没有 ctx.notifications，社区插件用
    // 浏览器 Notification；这里两者都收敛到宿主 Android 通知。
    whale: __permissive({
      notify: function(options) {
        const opts = options || {};
        return __dshHostJson('plugin.notify', {
          title: String(opts.title || '鲸歌插件'),
          body: String(opts.body || ''),
          level: String(opts.level || 'info')
        });
      }
    }),
    sessionProjections: __permissive({ register: function() { return __dshUnsupported('sessionProjections'); } }),
    llm: __permissive({ register: function() { return __dshUnsupported('llm.register'); } }),
    ui: __permissive({
      registerRoute: function() { return __dshUnsupported('ui.registerRoute'); },
      registerView: function() { return __dshUnsupported('ui.registerView'); }
    }),
    effect: function() { return __dshUnsupported('ctx.effect'); },
    on: function() { return __dshUnsupported('ctx.on'); },
    plugin: function() { return undefined; },
    logger: { info: __noop, warn: __noop, error: __noop, debug: __noop },
    config: {}
  });
  // 社区通知插件直接 `new Notification(...)`：没有 DOM，收敛到宿主通知。
  function Notification(title, options) {
    const opts = options || {};
    __dshHostJson('plugin.notify', {
      title: String(title || '鲸歌插件'),
      body: String(opts.body || ''),
      level: 'info'
    });
  }
  Notification.permission = 'granted';
  Notification.requestPermission = function() { return Promise.resolve('granted'); };
  const __exec = __permissive({
    signal: { aborted: false },
    agent: undefined,
    session: undefined
  });
  function __require(id) {
    const name = String(id);
    __dsh.imports.push(name);
    if (name.charAt(0) === '.') { __dsh.relativeImports.push(name); return {}; }
    if (name === '@deepseek-ai/dsh-tools') { return { defineTool: __defineTool, default: { defineTool: __defineTool } }; }
    if (name === '@deepseek-ai/schemastery') { return { z: __zExpose, default: __zExpose, Schema: __zExpose }; }
    if (name === 'node:path' || name === 'path') {
      return {
        join: function() { return Array.prototype.slice.call(arguments).join('/'); },
        resolve: function() { return Array.prototype.slice.call(arguments).join('/'); },
        dirname: function(p) { const s = String(p); const i = s.lastIndexOf('/'); return i < 0 ? '.' : s.slice(0, i); },
        basename: function(p) { const s = String(p); const i = s.lastIndexOf('/'); return i < 0 ? s : s.slice(i + 1); },
        sep: '/'
      };
    }
    if (name === 'node:fs' || name === 'fs') {
      // 真给沙箱 fs（不是空实现）：DSH 工具插件里 import fs 是很常见的写法。
      return __dshFs;
    }
    if (name === 'node:fs/promises' || name === 'fs/promises') {
      return __dshFs.promises;
    }
    if (name === 'node:child_process' || name === 'child_process') {
      return {
        execSync: function(cmd, options) {
          const r = __dshHostOk('shell.exec', {
            command: String(cmd),
            timeoutMs: (options && options.timeout) || undefined
          });
          return (r.stdout === undefined || r.stdout === null) ? '' : String(r.stdout);
        },
        exec: function(cmd, options, callback) {
          const cb = (typeof options === 'function') ? options : callback;
          try {
            const out = this.execSync(cmd, (typeof options === 'object') ? options : undefined);
            if (typeof cb === 'function') { cb(null, out, ''); }
          } catch (e) {
            if (typeof cb === 'function') { cb(e, '', ''); }
          }
          return undefined;
        },
        spawn: __permissive({ on: __noop, stdout: __permissive({ on: __noop }), stderr: __permissive({ on: __noop }) })
      };
    }
    if (name === 'node:os' || name === 'os') {
      return {
        platform: function() { return 'android'; },
        homedir: function() { return '/'; },
        tmpdir: function() { return '/data/local/tmp'; },
        EOL: '\n'
      };
    }
    return __permissive({});
  }
  const __dshDefault = function(m) {
    if (m && m.__esModule) { return m.default; }
    return (m && m.default !== undefined) ? m.default : m;
  };
  const __module = { exports: {} };
  const __exports = __module.exports;
"""

    /**
     * 执行模块 exports + `apply(ctx, config)` 的公共收尾（试运行和工具调用共用）。
     *
     * 这里只填充 `__dsh`（名称/版本/配置 schema/注册工具/报错）和 `__dshDefs`（真实工具对象）；
     * 结尾由调用方决定：试运行 `return JSON.stringify(__dsh)`，工具调用走 execute。
     *
     * `Config` 导出（DSH 的 `export const Config = z.object(...)`）只做**序列化**：
     * 转成 `{uid,refs}` 图交给 Kotlin 归一成 JSON Schema；默认值由宿主按 schema 合并后，
     * 通过 `__dshConfigRaw` 传进来（不在这里猜配置语义，避免 JS/Kotlin 两套默认值实现）。
     */
    private val EXPORTS_APPLY = """
  if (__ret) {
    for (const key in __ret) {
      if (__ret[key] !== undefined) { __module.exports[key] = __ret[key]; }
    }
  }
  let __e = __module.exports || {};
  if (typeof __e.apply !== 'function' && __e.default && typeof __e.default.apply === 'function') { __e = __e.default; }
  __dsh.name = (typeof __e.name === 'string') ? __e.name : null;
  __dsh.version = (typeof __e.version === 'string') ? __e.version : null;
  __dsh.inject = Array.isArray(__e.inject) ? __e.inject.map(String) : [];
  if (__e.Config !== undefined && __e.Config !== null) {
    try {
      __dsh.configSchema = __dshSchema(__e.Config);
      if (__dsh.configSchema === null) { __dsh.configError = 'Config 序列化不出来（不是 schemastery schema？）'; }
    } catch (err) {
      __dsh.configError = 'Config 序列化报错：' + String((err && err.message) || err);
    }
  }
  let __dshApply = null;
  if (typeof __e.apply === 'function') {
    try {
      const __retValue = __e.apply(__ctx, __dshConfigRaw);
      __dsh.applied = true;
      if (__retValue && typeof __retValue.then === 'function') {
        // M31：async apply 不再只警告——等它结算完再收集工具/执行工具（试运行也有超时中断）。
        __dshApply = Promise.resolve(__retValue).catch(function (err) {
          const message = (err && err.message !== undefined)
            ? (String(err.name || 'Error') + ': ' + String(err.message))
            : String(err);
          __dsh.errors.push((err && err.stack) ? (message + '\n' + String(err.stack)) : message);
        });
      }
    } catch (err) {
      const message = (err && err.message !== undefined)
        ? (String(err.name || 'Error') + ': ' + String(err.message))
        : String(err);
      __dsh.errors.push((err && err.stack) ? (message + '\n' + String(err.stack)) : message);
    }
  } else {
    __dsh.errors.push('没有找到 apply 导出：这个包可能不是 DSH 插件（也可能是 ESM/CJS 写法暂不支持）');
  }
"""

    /**
     * M28 宿主桥（在 [PRELUDE] 之后、插件代码之前执行）。
     *
     * 为什么单独一段而不是改 PRELUDE：试运行/工具调用共用同一段桥，桥只负责把 JS 调用
     * **转成宿主调用**；真正的权限判断、路径校验、网络白名单、shell 上限都在 Kotlin 的
     * [PluginSandbox] 里。JS 侧不重复实现安全判断（少一处能写错的地方）。
     *
     * 覆盖的兼容面：
     * - `node:fs` / `fs`：readFileSync / writeFileSync / existsSync / readdirSync / statSync /
     *   mkdirSync / rmSync / renameSync / copyFileSync / promises.*（只支持文本，不支持 Buffer）；
     * - `ctx.http` / 全局 `fetch`：https + 域名白名单，返回 `{status, ok, headers, body}`；
     * - `node:https` / `node:http` 的 `get/request`：最小回调式实现（body 一次给完）；
     * - `node:child_process`：execSync / exec（需 shell 权限）；
     * - `process.cwd()`、`console.*`、`ctx.fs` / `ctx.shell`。
     */
    private val HOST_BRIDGE = """
  function __dshHostJson(method, payload) {
    try {
      const raw = __dshCallSync(method, JSON.stringify(payload || {}));
      if (!raw) { return { ok: false, error: '宿主没有返回结果：' + method }; }
      return JSON.parse(raw);
    } catch (e) {
      return { ok: false, error: '宿主调用失败：' + String((e && e.message) || e) };
    }
  }
  function __dshHostOk(method, payload) {
    const reply = __dshHostJson(method, payload);
    if (!reply || reply.ok !== true) {
      const err = new Error((reply && reply.error) || ('宿主拒绝：' + method));
      err.code = (reply && reply.code) || 'PLUGIN_HOST_ERROR';
      throw err;
    }
    return reply;
  }
  // 异步宿主回调的 payload 到 JS 时已经是对象（C 侧做过 JSON.parse），同步路径才是字符串。
  function __dshParseHost(raw) {
    if (raw !== null && typeof raw === 'object') { return raw; }
    return JSON.parse(String(raw));
  }
  function __dshStat(s) {
    return {
      isFile: function() { return !!s.isFile; },
      isDirectory: function() { return !!s.isDirectory; },
      size: s.size || 0,
      mtimeMs: s.mtimeMs || 0,
      mtime: new Date(s.mtimeMs || 0)
    };
  }
  const __dshFs = {
    readFileSync: function(p, options) {
      const r = __dshHostOk('fs.read', { path: String(p) });
      return (r.text === undefined || r.text === null) ? '' : String(r.text);
    },
    writeFileSync: function(p, data, options) {
      __dshHostOk('fs.write', { path: String(p), text: (data === undefined || data === null) ? '' : String(data) });
    },
    appendFileSync: function(p, data, options) {
      __dshHostOk('fs.write', { path: String(p), text: (data === undefined || data === null) ? '' : String(data), append: true });
    },
    existsSync: function(p) {
      const r = __dshHostJson('fs.exists', { path: String(p) });
      return !!(r && r.ok && r.exists);
    },
    readdirSync: function(p, options) {
      const r = __dshHostOk('fs.list', { path: String(p === undefined || p === null ? '.' : p) });
      return r.names || [];
    },
    statSync: function(p) { return __dshStat(__dshHostOk('fs.stat', { path: String(p) })); },
    lstatSync: function(p) { return __dshStat(__dshHostOk('fs.stat', { path: String(p) })); },
    mkdirSync: function(p, options) {
      __dshHostOk('fs.mkdir', { path: String(p), recursive: !!(options && options.recursive) });
    },
    rmSync: function(p, options) {
      __dshHostOk('fs.remove', {
        path: String(p),
        recursive: !!(options && options.recursive),
        force: !!(options && options.force)
      });
    },
    unlinkSync: function(p) { __dshHostOk('fs.remove', { path: String(p) }); },
    rmdirSync: function(p) { __dshHostOk('fs.remove', { path: String(p), recursive: true }); },
    renameSync: function(a, b) { __dshHostOk('fs.rename', { from: String(a), to: String(b) }); },
    copyFileSync: function(a, b) { __dshHostOk('fs.copy', { from: String(a), to: String(b) }); },
    constants: { F_OK: 0, R_OK: 4, W_OK: 2, X_OK: 1 }
  };
  __dshFs.promises = {
    readFile: function(p, o) { return new Promise(function(ok, no) { try { ok(__dshFs.readFileSync(p, o)); } catch (e) { no(e); } }); },
    writeFile: function(p, d, o) { return new Promise(function(ok, no) { try { __dshFs.writeFileSync(p, d, o); ok(); } catch (e) { no(e); } }); },
    appendFile: function(p, d, o) { return new Promise(function(ok, no) { try { __dshFs.appendFileSync(p, d, o); ok(); } catch (e) { no(e); } }); },
    readdir: function(p, o) { return new Promise(function(ok, no) { try { ok(__dshFs.readdirSync(p, o)); } catch (e) { no(e); } }); },
    stat: function(p) { return new Promise(function(ok, no) { try { ok(__dshFs.statSync(p)); } catch (e) { no(e); } }); },
    mkdir: function(p, o) { return new Promise(function(ok, no) { try { __dshFs.mkdirSync(p, o); ok(); } catch (e) { no(e); } }); },
    rm: function(p, o) { return new Promise(function(ok, no) { try { __dshFs.rmSync(p, o); ok(); } catch (e) { no(e); } }); },
    rename: function(a, b) { return new Promise(function(ok, no) { try { __dshFs.renameSync(a, b); ok(); } catch (e) { no(e); } }); },
    copyFile: function(a, b) { return new Promise(function(ok, no) { try { __dshFs.copyFileSync(a, b); ok(); } catch (e) { no(e); } }); }
  };
  function __dshHttpRequest(url, options) {
    const opts = options || {};
    const payload = {
      url: String(url),
      method: String(opts.method || 'GET').toUpperCase(),
      headers: opts.headers || {},
      body: (opts.body === undefined || opts.body === null) ? null : String(opts.body)
    };
    return __dshCallAsync('net.fetch', JSON.stringify(payload)).then(function(raw) {
      const r = __dshParseHost(raw);
      return {
        status: r.status || 0,
        ok: (r.status || 0) >= 200 && (r.status || 0) < 300,
        headers: r.headers || {},
        body: (r.body === undefined || r.body === null) ? '' : String(r.body),
        url: r.url || String(url)
      };
    }, function(err) {
      throw new Error((err && err.message) ? err.message : String(err));
    });
  }
  const __dshHttpApi = {
    request: function(options) { return __dshHttpRequest(options && options.url, options || {}); },
    get: function(url, options) {
      const o = Object.assign({}, options || {}); o.method = 'GET'; return __dshHttpRequest(url, o);
    },
    post: function(url, body, options) {
      const o = Object.assign({}, options || {}); o.method = 'POST'; o.body = body; return __dshHttpRequest(url, o);
    },
    put: function(url, body, options) {
      const o = Object.assign({}, options || {}); o.method = 'PUT'; o.body = body; return __dshHttpRequest(url, o);
    },
    patch: function(url, body, options) {
      const o = Object.assign({}, options || {}); o.method = 'PATCH'; o.body = body; return __dshHttpRequest(url, o);
    },
    'delete': function(url, options) {
      const o = Object.assign({}, options || {}); o.method = 'DELETE'; return __dshHttpRequest(url, o);
    }
  };
  function __dshFetch(url, init) {
    const opts = init || {};
    const method = String(opts.method || 'GET').toUpperCase();
    const body = (opts.body === undefined || opts.body === null) ? null : String(opts.body);
    return __dshHttpRequest(url, { method: method, headers: opts.headers || {}, body: body }).then(function(r) {
      return {
        ok: r.ok,
        status: r.status,
        statusText: '',
        url: r.url,
        headers: r.headers,
        text: function() { return Promise.resolve(r.body); },
        // 非标准字段：Operit 的 OkHttp shim（ToolPkg）按 response.body 取文本，补一份。
        body: r.body,
        json: function() { return Promise.resolve().then(function() { return JSON.parse(r.body || 'null'); }); }
      };
    });
  }
  function __dshNodeHttpGet(url, options, callback) {
    let cb = callback;
    let opts = options;
    if (typeof options === 'function') { cb = options; opts = {}; }
    const handlers = { data: [], end: [], error: [] };
    const request = {
      on: function(ev, fn) { if (handlers[ev]) { handlers[ev].push(fn); } return request; },
      setTimeout: function() { return request; },
      end: function() { return request; },
      abort: function() {}
    };
    __dshCallAsync('net.fetch', JSON.stringify({
      url: String(url),
      method: (opts && opts.method) ? String(opts.method) : 'GET',
      headers: (opts && opts.headers) ? opts.headers : {},
      body: (opts && opts.body !== undefined && opts.body !== null) ? String(opts.body) : null
    })).then(function(raw) {
      const r = __dshParseHost(raw);
      const res = {
        statusCode: r.status || 0,
        statusMessage: '',
        headers: r.headers || {},
        on: function(ev, fn) { if (handlers[ev]) { handlers[ev].push(fn); } return res; },
        resume: function() { return res; },
        setEncoding: function() { return res; },
        pause: function() { return res; }
      };
      if (typeof cb === 'function') { cb(res); }
      Promise.resolve().then(function() {
        handlers.data.forEach(function(fn) { try { fn(r.body || ''); } catch (e) {} });
        handlers.end.forEach(function(fn) { try { fn(); } catch (e) {} });
      });
    }, function(err) {
      const message = (err && err.message) ? err.message : String(err);
      handlers.error.forEach(function(fn) { try { fn(new Error(message)); } catch (e) {} });
    });
    return request;
  }
  const __dshNodeHttps = {
    get: __dshNodeHttpGet,
    request: function(options, callback) {
      let url = options;
      if (options && typeof options === 'object') {
        url = (options.href ? options.href : ('https://' + options.hostname + (options.port ? ':' + options.port : '') + (options.path || '/')));
      }
      return __dshNodeHttpGet(url, options, callback);
    }
  };
  __dshNodeHttps.default = __dshNodeHttps;
  const __dshChildProcess = {
    execSync: function(command, options) {
      const r = __dshHostOk('shell.exec', { command: String(command), timeoutMs: (options && options.timeout) || undefined });
      if (r.status !== 0) {
        const err = new Error('Command failed: ' + command + '\n' + (r.stderr || ''));
        err.status = r.status;
        err.stdout = r.stdout;
        err.stderr = r.stderr;
        throw err;
      }
      return r.stdout || '';
    },
    exec: function(command, options, callback) {
      let cb = callback;
      let opts = options;
      if (typeof options === 'function') { cb = options; opts = {}; }
      return __dshCallAsync('shell.exec', JSON.stringify({
        command: String(command),
        timeoutMs: (opts && opts.timeout) || undefined
      })).then(function(raw) {
        const r = __dshParseHost(raw);
        if (typeof cb === 'function') { cb(null, r.stdout || '', r.stderr || ''); }
        return r;
      }, function(err) {
        if (typeof cb === 'function') { cb(err); return undefined; }
        throw err;
      });
    },
    spawn: function() { throw new Error('child_process.spawn 暂不支持（用 exec / execSync）'); }
  };
  __dshChildProcess.default = __dshChildProcess;
  const __dshShellApi = {
    exec: function(command, options) {
      return __dshHostOk('shell.exec', { command: String(command), timeoutMs: (options && options.timeout) || undefined });
    },
    run: function(command, options) { return __dshShellApi.exec(command, options); }
  };
  __ctx.http = __dshHttpApi;
  __ctx.fs = __dshFs;
  __ctx.shell = __dshShellApi;
  const __dshConsole = {
    log: function() { __dshBridgeLog('info', arguments); },
    info: function() { __dshBridgeLog('info', arguments); },
    warn: function() { __dshBridgeLog('warn', arguments); },
    error: function() { __dshBridgeLog('error', arguments); },
    debug: function() { __dshBridgeLog('debug', arguments); }
  };
  function __dshBridgeLog(level, args) {
    const parts = Array.prototype.slice.call(args).map(function(v) {
      if (typeof v === 'string') { return v; }
      try { return JSON.stringify(v); } catch (e) { return String(v); }
    });
    try { __dshCallSync('log', JSON.stringify({ level: level, message: parts.join(' ') })); } catch (e) {}
  }
  const __dshProcess = {
    cwd: function() { return __dshCwd; },
    env: {},
    platform: 'android',
    arch: 'arm64',
    argv: ['plugin'],
    versions: { node: '0.0.0', dsh: 'native' },
    exit: function() { throw new Error('process.exit 不被支持（插件与 App 同进程）'); },
    nextTick: function(fn) { Promise.resolve().then(fn); }
  };
  if (typeof globalThis !== 'undefined') {
    globalThis.console = __dshConsole;
    globalThis.process = __dshProcess;
    globalThis.fetch = __dshFetch;
  }
  const __dshBaseRequire = __require;
  __require = function(id) {
    const name = String(id);
    if (name === 'node:fs' || name === 'fs') { __dsh.imports.push(name); return __dshFs; }
    if (name === 'node:child_process' || name === 'child_process') { __dsh.imports.push(name); return __dshChildProcess; }
    if (name === 'node:https' || name === 'https') { __dsh.imports.push(name); return __dshNodeHttps; }
    if (name === 'node:http' || name === 'http') { __dsh.imports.push(name); return __dshNodeHttps; }
    if (name === 'node:os' || name === 'os') {
      __dsh.imports.push(name);
      return {
        platform: function() { return 'android'; },
        homedir: function() { return __dshCwd; },
        tmpdir: function() { return __dshCwd; },
        cpus: function() { return []; },
        EOL: '\n'
      };
    }
    return __dshBaseRequire(id);
  };
"""
}
