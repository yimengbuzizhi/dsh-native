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

    /**
     * M84-H：常驻运行时的启动脚本。
     *
     * 与 [dryRunScript] 同一条链路（同一份 PRELUDE/宿主桥/模块包装），区别是宿主拿到这个脚本后
     * 用 `QuickJsSession.start` 起 runtime 并**留着**；之后每条会话事件通过
     * `__dshDispatch('projection.event', payloadJson)` 进 JS 折叠，闭包与状态都在同一个 runtime 里。
     *
     * 返回值是 JSON 字符串：`{ok, keys, projections, errors}`。
     */
    fun residentScript(module: TransformedModule, cwd: String? = null, configJson: String = "{}"): String =
        moduleScript(module, cwd, configJson) + EXPORTS_APPLY + RESIDENT_DISPATCH + """
  return Promise.resolve(__dshApply).then(function () {
    return JSON.stringify({
      ok: true,
      keys: __dshProjectionKeys.slice(0),
      subscriptions: Object.keys(__dshEventHandlers),
      cleanups: __dshCleanups.length,
      streams: Object.keys(__dshStreamProviders),
      projections: __dsh.projections,
      shells: __dsh.shells,
      agents: __dsh.agents,
      storages: __dsh.storages,
      prompts: __dsh.prompts,
      capabilities: __dsh.capabilities,
      errors: __dsh.errors
    });
  });
"""

    /**
     * 常驻运行时的 dispatch 入口（只在 [residentScript] 里出现）。
     *
     * 协议（宿主 → JS）：
     * - `projection.event`：`{sessionId, header, inheritedEventCount, event}`，返回
     *   `{ok, keys, values:[{key,seq,view}], errors:[{key,error}]}`；
     * - `projection.snapshot`：`{sessionId}` → `{ok, entries:[{key,stateVersion,seq,state,view}]}`（checkpoint 落盘）；
     * - `projection.restore`：`{sessionId, entries:[…]}` → 把 state/seq 灌回 cell，`seq <= cell.seq` 的旧事件不再折；
     * - `projection.reset`：清掉所有会话的投影 cell；
     * - `ping`：返回已注册的投影 key（验收/探活用）。
     *
     * `apply` 必须是同步 fold：返回 Promise 直接算插件错误（照 DSH 的同步纪律）。
     */
    private val RESIDENT_DISPATCH = """
  function __dshProjectionDispatch(payload) {
    const sessionId = String(payload.sessionId || '');
    const event = payload.event;
    if (!sessionId || !event || typeof event !== 'object') {
      return { ok: false, error: 'projection.event 缺少 sessionId/event' };
    }
    const seq = (typeof event.seq === 'number') ? event.seq : null;
    const values = [];
    const errors = [];
    for (let i = 0; i < __dshProjectionKeys.length; i++) {
      const key = __dshProjectionKeys[i];
      const def = __dshProjectionDefs[key];
      if (!def) { continue; }
      const cellKey = sessionId + '\u0000' + key;
      let cell = __dshProjectionCells[cellKey];
      try {
        if (!cell) {
          let initial = null;
          if (typeof def.init === 'function') {
            initial = def.init(payload.header || {}, (typeof payload.inheritedEventCount === 'number') ? payload.inheritedEventCount : 0);
          }
          if (initial && typeof initial.then === 'function') { throw new Error('init 必须是同步函数'); }
          cell = { state: initial };
          __dshProjectionCells[cellKey] = cell;
        }
        // M84-N：checkpoint 恢复后重投的旧事件不再 fold（apply 不要求幂等）。
        if (seq !== null && typeof cell.seq === 'number' && seq <= cell.seq) { continue; }
        if (seq !== null) { cell.seq = seq; }
        const next = (typeof def.apply === 'function') ? def.apply(cell.state, event) : cell.state;
        if (next && typeof next.then === 'function') { throw new Error('apply 必须是同步函数'); }
        // DSH 纪律：不关心的事件返回同一个 state 引用 → 零下游工作。
        if (next === cell.state) { continue; }
        cell.state = next;
        let view = next;
        if (def.wire && typeof def.wire.view === 'function') {
          view = def.wire.view(next);
          if (view && typeof view.then === 'function') { throw new Error('wire.view 必须是同步函数'); }
        }
        cell.view = __dshJsonSafe(view);
        cell.hasView = true;
        values.push({ key: key, seq: seq, view: cell.view });
      } catch (err) {
        errors.push({ key: key, error: String((err && err.stack) ? err.stack : err) });
      }
    }
    return { ok: errors.length === 0, keys: __dshProjectionKeys.slice(0), values: values, errors: errors };
  }
  // M84-N：checkpoint 快照（宿主定期落盘）与恢复（App 重启后只重投更新的事件）。
  function __dshProjectionSnapshot(payload) {
    const sessionId = String((payload && payload.sessionId) || '');
    if (!sessionId) { return { ok: false, error: 'projection.snapshot 缺少 sessionId' }; }
    const entries = [];
    const errors = [];
    for (let i = 0; i < __dshProjectionKeys.length; i++) {
      const key = __dshProjectionKeys[i];
      const cell = __dshProjectionCells[sessionId + '\u0000' + key];
      if (!cell) { continue; }
      const def = __dshProjectionDefs[key] || {};
      try {
        entries.push({
          key: key,
          stateVersion: (typeof def.stateVersion === 'number') ? def.stateVersion : 1,
          seq: (typeof cell.seq === 'number') ? cell.seq : -1,
          state: __dshJsonSafe(cell.state),
          view: cell.hasView ? cell.view : null
        });
      } catch (err) {
        errors.push({ key: key, error: String((err && err.stack) ? err.stack : err) });
      }
    }
    return { ok: errors.length === 0, entries: entries, errors: errors };
  }
  function __dshProjectionRestore(payload) {
    const sessionId = String((payload && payload.sessionId) || '');
    const list = (payload && Array.isArray(payload.entries)) ? payload.entries : [];
    if (!sessionId) { return { ok: false, error: 'projection.restore 缺少 sessionId' }; }
    let restored = 0;
    const errors = [];
    for (let i = 0; i < list.length; i++) {
      const entry = list[i] || {};
      const key = String(entry.key || '');
      // 插件重装/升级后不再声明的 key 直接丢（旧 checkpoint 不作数）。
      if (!key || __dshProjectionKeys.indexOf(key) < 0) { continue; }
      try {
        const seq = (typeof entry.seq === 'number') ? entry.seq : -1;
        __dshProjectionCells[sessionId + '\u0000' + key] = {
          state: entry.state,
          seq: seq,
          view: (entry.view === undefined) ? null : entry.view,
          hasView: entry.view !== null && entry.view !== undefined
        };
        restored++;
      } catch (err) {
        errors.push({ key: key, error: String((err && err.stack) ? err.stack : err) });
      }
    }
    return { ok: errors.length === 0, restored: restored, errors: errors };
  }
  function __dshEmitEvent(name, sessionId, header, event) {
    // 具体事件名 + DSH 的通用 'session/event' 两级订阅；'*' 收全部。
    const names = [name];
    if (name && name !== 'session/event') { names.push('session/event'); }
    let handlers = [];
    for (let n = 0; n < names.length; n++) {
      handlers = handlers.concat(__dshEventHandlers[names[n]] || []);
    }
    if (name !== '*') { handlers = handlers.concat(__dshEventHandlers['*'] || []); }
    const errors = [];
    let handled = 0;
    for (let i = 0; i < handlers.length; i++) {
      try {
        const result = handlers[i](event, { name: name, sessionId: sessionId, header: header || {} });
        handled++;
        // handler 允许 async：宿主不等它（事件回调不是投影 fold），异常记进插件错误。
        if (result && typeof result.then === 'function') {
          result.catch(function (err) {
            __dsh.errors.push('ctx.on(' + name + ') 异步报错：' + String((err && err.stack) ? err.stack : err));
          });
        }
      } catch (err) {
        errors.push({ event: name, error: String((err && err.stack) ? err.stack : err) });
      }
    }
    return { handled: handled, errors: errors };
  }
  // M85-A：整壳 dispatch。返回值统一是 {ok, tree} 或 {ok:false,error}；
  // mount/state/action/event 都允许返回 Promise（宿主 dispatch 会等它结算）。
  function __dshShellDispatch(method, payload) {
    const shellId = String(payload.id || payload.shell || '');
    const def = __dshShellDefs[shellId];
    if (!def) { return { ok: false, error: '没有注册壳：' + shellId }; }
    const state = (payload.state && typeof payload.state === 'object') ? payload.state : {};
    let produced;
    try {
      if (method === 'mount') {
        produced = def.mount(state);
      } else if (method === 'state') {
        const fn = def.onState || def.state || def.mount;
        produced = fn.call(def, state);
      } else if (method === 'action') {
        const fn = def.onAction || def.action;
        if (typeof fn !== 'function') { return { ok: false, error: '壳没有 onAction：' + shellId }; }
        produced = fn.call(def, String(payload.action || ''), (payload.value === undefined ? (payload.payload || {}) : payload.value), state);
      } else if (method === 'event') {
        const fn = def.onEvent || def.event;
        if (typeof fn !== 'function') { return { ok: false, error: '壳没有 onEvent：' + shellId }; }
        produced = fn.call(def, String(payload.event || ''), state);
      } else {
        return { ok: false, error: '未知 shell dispatch：' + method };
      }
    } catch (err) {
      return { ok: false, error: '壳 ' + method + ' 抛错：' + String((err && err.stack) ? err.stack : err) };
    }
    return Promise.resolve(produced).then(function (value) {
      if (value === undefined || value === null) {
        return { ok: false, error: '壳 ' + method + ' 没有返回 UI 树' };
      }
      const safe = __dshJsonSafe(value);
      // M85-F：webview 后端返回 {title?, html}，原样交回宿主；surface 后端仍包成 {tree}。
      if (safe && typeof safe === 'object' && typeof safe.html === 'string') {
        return {
          ok: true,
          title: (safe.title === undefined || safe.title === null) ? null : String(safe.title),
          html: safe.html
        };
      }
      return { ok: true, tree: safe };
    }, function (err) {
      return { ok: false, error: '壳 ' + method + ' 失败：' + String((err && err.stack) ? err.stack : err) };
    });
  }
  // M85-D：回合实现 dispatch（agent/v1）。宿主每轮传 {id, turn:{turnId,sessionId,input,state}}，
  // run(turn, ctx) 允许 async；返回值统一归一成 {ok, stopReason, text}。
  // ctx 只开放宿主原语：notify / status / abort / tools.names（tools.invoke、llm.stream、
  // storage.append 在 M85-D 明确返回「未开放」，不静默成功）。
  function __dshAgentDispatch(payload) {
    const agentId = String(payload.id || '');
    const def = __dshAgentDefs[agentId];
    if (!def) { return { ok: false, error: '没有注册回合插件：' + agentId }; }
    const turn = (payload.turn && typeof payload.turn === 'object') ? payload.turn : {};
    const ctxApi = {
      notify: function(title, body) {
        const reply = __dshHostJson('agent.notify', {
          title: String(title === undefined || title === null ? '' : title),
          body: String(body === undefined || body === null ? '' : body)
        });
        return !!(reply && reply.ok === true);
      },
      status: function(text) {
        const reply = __dshHostJson('agent.status', {
          text: (text === undefined || text === null) ? '' : String(text)
        });
        return !!(reply && reply.ok === true);
      },
      abort: function() {
        const reply = __dshHostJson('agent.abort', {});
        return !!(reply && reply.abort === true);
      },
      tools: {
        names: function() {
          const reply = __dshHostJson('agent.tools.names', {});
          return (reply && reply.ok === true && Array.isArray(reply.names)) ? reply.names.slice(0) : [];
        },
        invoke: function(name, args) {
          return __dshHostJson('agent.tools.invoke', {
            name: String(name || ''),
            arguments: (args === undefined ? null : args)
          });
        }
      },
      llm: {
        stream: function() { return __dshHostJson('agent.llm.stream', {}); }
      },
      storage: {
        append: function() { return __dshHostJson('agent.storage.append', {}); }
      }
    };
    let produced;
    try {
      produced = def.run(turn, ctxApi);
    } catch (err) {
      return { ok: false, error: 'agent.run 抛错：' + String((err && err.stack) ? err.stack : err) };
    }
    return Promise.resolve(produced).then(function (value) {
      if (typeof value === 'string') {
        return { ok: true, stopReason: 'completed', text: value };
      }
      if (!value || typeof value !== 'object') {
        return { ok: false, error: 'agent.run 没有返回 {stopReason,text}' };
      }
      return {
        ok: true,
        stopReason: String(value.stopReason || 'completed'),
        text: (value.text === undefined || value.text === null) ? '' : String(value.text)
      };
    }, function (err) {
      return { ok: false, error: 'agent.run 失败：' + String((err && err.stack) ? err.stack : err) };
    });
  }
  // M85-E：存储实现 dispatch（storage/v1）。v1 只读：宿主只调 list/load；
  // list() 返回 [{id,title,updatedAt,...}]，load(id) 返回原始会话文档（JSONL 文本）。
  // 写入路径不在 v1 开放（宿主会拒绝），第三方存储不会假装能落库。
  function __dshStorageDispatch(method, payload) {
    const storeId = String(payload.id || '');
    const def = __dshStorageDefs[storeId];
    if (!def) { return { ok: false, error: '没有注册存储插件：' + storeId }; }
    let produced;
    try {
      if (method === 'list') {
        if (typeof def.list !== 'function') { return { ok: false, error: '存储插件没有 list：' + storeId }; }
        produced = def.list();
      } else if (method === 'load') {
        if (typeof def.load !== 'function') { return { ok: false, error: '存储插件没有 load：' + storeId }; }
        produced = def.load(String(payload.sessionId || ''));
      } else {
        return { ok: false, error: 'storage/v1 只支持 list/load：' + method };
      }
    } catch (err) {
      return { ok: false, error: '存储操作抛错：' + String((err && err.stack) ? err.stack : err) };
    }
    return Promise.resolve(produced).then(function (value) {
      if (method === 'list') {
        let items = value;
        if (value && !Array.isArray(value) && Array.isArray(value.sessions)) { items = value.sessions; }
        if (!Array.isArray(items)) { return { ok: false, error: 'storage.list 需要返回数组' }; }
        return { ok: true, sessions: __dshJsonSafe(items) };
      }
      let doc = value;
      if (value && typeof value === 'object' && !Array.isArray(value)) { doc = value.doc; }
      if (typeof doc !== 'string') { return { ok: false, error: 'storage.load 需要返回会话文档字符串' }; }
      return { ok: true, doc: doc };
    }, function (err) {
      return { ok: false, error: '存储操作失败：' + String((err && err.stack) ? err.stack : err) };
    });
  }
  // M85-E：提示词实现 dispatch（prompt/v1）。宿主传内置拼装结果 base + 本轮参数，
  // 插件可以整体替换或包一层；返回 string 或 {text}。
  function __dshPromptDispatch(payload) {
    const promptId = String(payload.id || '');
    const def = __dshPromptDefs[promptId];
    if (!def || typeof def.assemble !== 'function') {
      return { ok: false, error: '没有注册提示词插件：' + promptId };
    }
    const request = {
      base: String(payload.base || ''),
      model: String(payload.model || ''),
      provider: String(payload.provider || ''),
      tools: Array.isArray(payload.tools) ? payload.tools.slice(0) : [],
      planMode: payload.planMode === true,
      personaPrefix: String(payload.personaPrefix || ''),
      personaSuffix: String(payload.personaSuffix || ''),
      variables: (payload.variables && typeof payload.variables === 'object') ? payload.variables : {}
    };
    let produced;
    try {
      produced = def.assemble(request);
    } catch (err) {
      return { ok: false, error: 'prompt.assemble 抛错：' + String((err && err.stack) ? err.stack : err) };
    }
    return Promise.resolve(produced).then(function (value) {
      if (typeof value === 'string') { return { ok: true, text: value }; }
      if (Array.isArray(value)) {
        return { ok: true, text: value.map(function (item) { return String(item); }).join('\n\n') };
      }
      if (value && typeof value === 'object' && typeof value.text === 'string') {
        return { ok: true, text: value.text };
      }
      return { ok: false, error: 'prompt.assemble 需要返回字符串 / {text}' };
    }, function (err) {
      return { ok: false, error: 'prompt.assemble 失败：' + String((err && err.stack) ? err.stack : err) };
    });
  }
  // M85-F：能力提供方 dispatch（capability/v1）。宿主/插件页/调试通道调用一个能力 id，
  // invoke(args) 允许 async；返回值归一成 {ok, value, error}。调用方拿不到宿主方法，
  // 只能拿到这个插件自己声明的实现（宿主已经做过开关/授权/超时门）。
  function __dshCapabilityDispatch(payload) {
    const capId = String(payload.id || '');
    const def = __dshCapabilityDefs[capId];
    if (!def || typeof def.invoke !== 'function') {
      return { ok: false, error: '没有注册能力：' + capId };
    }
    const args = (payload.args === undefined || payload.args === null) ? {} : payload.args;
    let produced;
    try {
      produced = def.invoke(args);
    } catch (err) {
      return { ok: false, error: 'capability.invoke 抛错：' + String((err && err.stack) ? err.stack : err) };
    }
    return Promise.resolve(produced).then(function (value) {
      if (value && typeof value === 'object' && value.ok === false) {
        return { ok: false, error: String(value.error || '能力调用失败') };
      }
      if (value && typeof value === 'object' && value.ok === true) {
        return { ok: true, value: __dshJsonSafe(value.value === undefined ? null : value.value) };
      }
      return { ok: true, value: __dshJsonSafe(value === undefined ? null : value) };
    }, function (err) {
      return { ok: false, error: 'capability.invoke 失败：' + String((err && err.stack) ? err.stack : err) };
    });
  }
  globalThis.__dshDispatch = function(method, payloadJson) {
    const name = String(method || '');
    let payload = {};
    if (payloadJson) {
      try { payload = JSON.parse(String(payloadJson)); }
      catch (e) { return { ok: false, error: 'dispatch payload 不是 JSON：' + String((e && e.message) || e) }; }
    }
    payload = payload || {};
    // M84-H + M84-I：一条会话事件同时喂投影 fold 和 ctx.on 订阅者。
    if (name === 'session.event' || name === 'projection.event') {
      const projection = __dshProjectionDispatch(payload);
      const event = payload.event || {};
      const eventName = String(event.type || payload.name || '');
      const emitted = eventName ? __dshEmitEvent(eventName, String(payload.sessionId || ''), payload.header, event) : { handled: 0, errors: [] };
      return {
        ok: projection.ok !== false && emitted.errors.length === 0,
        keys: projection.keys || [],
        values: projection.values || [],
        errors: (projection.errors || []).concat(emitted.errors || []),
        handled: emitted.handled
      };
    }
    if (name === 'projection.reset') {
      for (const key in __dshProjectionCells) { delete __dshProjectionCells[key]; }
      return { ok: true, keys: __dshProjectionKeys.slice(0) };
    }
    // M84-N：checkpoint 落盘/恢复。快照把每个 cell 的 state/seq/view 交回宿主；恢复把它们灌回来。
    if (name === 'projection.snapshot') { return __dshProjectionSnapshot(payload); }
    if (name === 'projection.restore') { return __dshProjectionRestore(payload); }
    // M84-I：停用/重载插件时跑 ctx.effect 的 cleanup。
    if (name === 'shutdown') {
      const errors = [];
      let cleaned = 0;
      const pending = __dshCleanups.slice(0);
      __dshCleanups.length = 0;
      for (let i = 0; i < pending.length; i++) {
        try {
          const result = pending[i]();
          cleaned++;
          if (result && typeof result.then === 'function') {
            result.catch(function (err) {
              __dsh.errors.push('ctx.effect cleanup 异步报错：' + String((err && err.stack) ? err.stack : err));
            });
          }
        } catch (err) {
          errors.push({ cleanup: i, error: String((err && err.stack) ? err.stack : err) });
        }
      }
      return { ok: errors.length === 0, cleaned: cleaned, errors: errors };
    }
    // M84-J/M84-M：插件自己产出模型回合。请求体是完整的 Anthropic 形状（和宿主真实请求同形）。
    // 第二个参数 emit(chunk) 是 M84-M 的增量通道：同步调宿主 'llm.emit'，边算边把文本/工具块送回去。
    // 返回值可以是 string / {text, toolCalls, stopReason, usage} / Promise；宿主等它结算。
    if (name === 'llm.stream') {
      const providerId = String(payload.id || '');
      const provider = __dshStreamProviders[providerId];
      if (!provider || typeof provider.stream !== 'function') {
        return { ok: false, error: '没有 stream 供应商：' + providerId };
      }
      const callId = String(payload.callId || '');
      let emitted = 0;
      const emit = function(chunk) {
        const reply = __dshHostJson('llm.emit', { callId: callId, chunk: chunk });
        if (!reply || reply.ok !== true) {
          throw new Error((reply && reply.error) || '宿主没有接收流片段');
        }
        emitted++;
        return true;
      };
      let produced;
      try {
        produced = provider.stream(payload.request || {}, emit);
      } catch (err) {
        return { ok: false, error: 'stream 抛错：' + String((err && err.stack) ? err.stack : err) };
      }
      return Promise.resolve(produced).then(function (value) {
        if (typeof value === 'string') { return { ok: true, text: value, emitted: emitted }; }
        if (value && typeof value === 'object') {
          return {
            ok: true,
            text: (typeof value.text === 'string') ? value.text : '',
            toolCalls: Array.isArray(value.toolCalls) ? value.toolCalls : null,
            stopReason: value.stopReason || '',
            model: value.model || '',
            usage: value.usage || null,
            emitted: emitted
          };
        }
        return { ok: false, error: 'stream 没有返回结果（string 或 {text}）' };
      }, function (err) {
        return { ok: false, error: 'stream 失败：' + String((err && err.stack) ? err.stack : err) };
      });
    }
    if (name.indexOf('shell.') === 0) {
      return __dshShellDispatch(name.substring(6), payload);
    }
    if (name === 'agent.run') {
      return __dshAgentDispatch(payload);
    }
    // M85-E：storage/v1 只读（list/load）与 prompt/v1（assemble）。
    if (name === 'storage.list' || name === 'storage.load') {
      return __dshStorageDispatch(name.substring(8), payload);
    }
    if (name === 'prompt.assemble') {
      return __dshPromptDispatch(payload);
    }
    // M85-F：能力提供方（capability/v1）。宿主只调一个能力 id，插件返回结果。
    if (name === 'capability.invoke') {
      return __dshCapabilityDispatch(payload);
    }
    if (name === 'ping') {
      return {
        ok: true,
        keys: __dshProjectionKeys.slice(0),
        subscriptions: Object.keys(__dshEventHandlers),
        cleanups: __dshCleanups.length,
        streams: Object.keys(__dshStreamProviders),
        shells: __dsh.shells,
        agents: __dsh.agents,
        storages: __dsh.storages,
        prompts: __dsh.prompts,
        capabilities: __dsh.capabilities,
        applied: __dsh.applied === true
      };
    }
    return { ok: false, error: '未知 dispatch：' + name };
  };
"""

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
        // M84-E2：配置式供应商 / 页面路由（坏 id、重复 id 直接丢，和 JS 侧规则一致）
        val llmProviders = runCatching {
            val seen = HashSet<String>()
            obj["llmProviders"]?.jsonArray?.mapNotNull { element ->
                val item = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val provider = PluginDecl.provider(
                    id = item.string("id"),
                    label = item.string("label"),
                    protocol = item.string("protocol"),
                    baseUrl = item.string("baseUrl"),
                    model = item.string("model"),
                    apiKey = item.string("apiKey"),
                    hasStream = item["hasStream"]?.let {
                        runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
                    },
                ) ?: return@mapNotNull null
                if (!seen.add(provider.id)) return@mapNotNull null
                provider
            }.orEmpty()
        }.getOrDefault(emptyList())
        val routes = runCatching {
            val seen = HashSet<String>()
            obj["routes"]?.jsonArray?.mapNotNull { element ->
                val item = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val route = PluginDecl.route(
                    id = item.string("id"),
                    title = item.string("title"),
                    description = item.string("description"),
                    page = item["page"]?.let {
                        runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
                    } ?: false,
                ) ?: return@mapNotNull null
                if (!seen.add(route.id)) return@mapNotNull null
                route
            }.orEmpty()
        }.getOrDefault(emptyList())
        // M84-H：会话投影（key + stateVersion + 是否有 wire.view）。定义本身留在常驻 JS 里。
        val projections = runCatching {
            val seen = HashSet<String>()
            obj["projections"]?.jsonArray?.mapNotNull { element ->
                val item = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val projection = PluginDecl.projection(
                    key = item.string("key"),
                    stateVersion = item["stateVersion"]?.let {
                        runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull()
                    },
                    hasView = item["hasView"]?.let {
                        runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
                    },
                    hasApply = item["hasApply"]?.let {
                        runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
                    },
                ) ?: return@mapNotNull null
                if (!seen.add(projection.key)) return@mapNotNull null
                projection
            }.orEmpty()
        }.getOrDefault(emptyList())
        // M85-A：整壳（id + label + 有哪些回调）。定义本身留在常驻 JS 里。
        val shells = runCatching {
            val seen = HashSet<String>()
            obj["shells"]?.jsonArray?.mapNotNull { element ->
                val item = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val shell = PluginDecl.shell(
                    id = item.string("id"),
                    label = item.string("label"),
                    backend = item.string("backend"),
                    hasState = item["hasState"]?.let {
                        runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
                    },
                    hasAction = item["hasAction"]?.let {
                        runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
                    },
                    hasEvent = item["hasEvent"]?.let {
                        runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
                    },
                ) ?: return@mapNotNull null
                if (!seen.add(shell.id)) return@mapNotNull null
                shell
            }.orEmpty()
        }.getOrDefault(emptyList())
        val loadingErrors = ArrayList<String>()
        val loadingElements = obj["loadings"] as? kotlinx.serialization.json.JsonArray
        if (obj.containsKey("loadings") && loadingElements == null) loadingErrors += "加载页声明必须是数组"
        if (loadingElements != null && loadingElements.size > LoadingSpec.MAX_DECLARATIONS) {
            loadingErrors += "加载页声明超过 ${LoadingSpec.MAX_DECLARATIONS} 项"
        }
        val loadings = loadingElements.orEmpty().take(LoadingSpec.MAX_DECLARATIONS).mapNotNull { element ->
            val parsed = LoadingSpec.parse(element)
            parsed.error?.let { loadingErrors += "loading/v1：$it" }
            parsed.loading
        }.let { declarations ->
            if (declarations.map { it.id }.distinct().size != declarations.size) loadingErrors += "加载页 id 重复"
            declarations.distinctBy { it.id }
        }
        // M85-D：回合实现（id + label）。run 闭包留在常驻 JS 里。
        val agents = runCatching {
            val seen = HashSet<String>()
            obj["agents"]?.jsonArray?.mapNotNull { element ->
                val item = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val agent = PluginDecl.agent(
                    id = item.string("id"),
                    label = item.string("label"),
                ) ?: return@mapNotNull null
                if (!seen.add(agent.id)) return@mapNotNull null
                agent
            }.orEmpty()
        }.getOrDefault(emptyList())
        // M85-E：存储实现（id + label + 回调能力）。list/load 闭包留在常驻 JS 里。
        val storages = runCatching {
            val seen = HashSet<String>()
            obj["storages"]?.jsonArray?.mapNotNull { element ->
                val item = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val storage = PluginDecl.storage(
                    id = item.string("id"),
                    label = item.string("label"),
                    readOnly = item["readOnly"]?.let {
                        runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
                    },
                    hasList = item["hasList"]?.let {
                        runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
                    },
                    hasLoad = item["hasLoad"]?.let {
                        runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
                    },
                ) ?: return@mapNotNull null
                if (!seen.add(storage.id)) return@mapNotNull null
                storage
            }.orEmpty()
        }.getOrDefault(emptyList())
        // M85-E：提示词实现（id + label）。assemble 闭包留在常驻 JS 里。
        val prompts = runCatching {
            val seen = HashSet<String>()
            obj["prompts"]?.jsonArray?.mapNotNull { element ->
                val item = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val prompt = PluginDecl.prompt(
                    id = item.string("id"),
                    label = item.string("label"),
                ) ?: return@mapNotNull null
                if (!seen.add(prompt.id)) return@mapNotNull null
                prompt
            }.orEmpty()
        }.getOrDefault(emptyList())
        // M85-F：能力提供方（id + label + 说明 + 分类）。invoke 闭包留在常驻 JS 里。
        val capabilities = runCatching {
            val seen = HashSet<String>()
            obj["capabilities"]?.jsonArray?.mapNotNull { element ->
                val item = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val capability = PluginDecl.capability(
                    id = item.string("id"),
                    label = item.string("label"),
                    description = item.string("description"),
                    kind = item.string("kind"),
                ) ?: return@mapNotNull null
                if (!seen.add(capability.id)) return@mapNotNull null
                capability
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
        // M83：服务容器（ctx.provide / ctx.inject / ctx.effect / ctx.on）
        val provides = runCatching {
            (obj["provides"] as? kotlinx.serialization.json.JsonObject)
                ?.mapNotNull { (key, element) ->
                    val text = runCatching { element.jsonPrimitive.contentOrNull }.getOrNull() ?: return@mapNotNull null
                    key to text
                }?.toMap().orEmpty()
        }.getOrDefault(emptyMap())
        val injectDeps = runCatching {
            obj["injects"]?.jsonArray?.mapNotNull { element ->
                val dep = element.jsonObject
                val name = dep.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PluginDependencyRef(
                    name = name,
                    optional = dep["optional"]?.let {
                        runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
                    } ?: false,
                )
            }.orEmpty()
        }.getOrDefault(emptyList())
        return PluginDryRun(
            name = obj.string("name"),
            version = obj.string("version"),
            inject = obj.stringList("inject"),
            tools = tools,
            imports = obj.stringList("imports"),
            relativeImports = obj.stringList("relativeImports"),
            applied = obj["applied"]?.let { runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull() } ?: false,
            error = (errors + loadingErrors).joinToString("\n").ifBlank { null },
            configSchema = configSchema,
            configError = configError,
            promptSections = sections("promptSections"),
            contexts = sections("contexts"),
            promptVariables = promptVariables,
            suppressRuntimeContext = obj["suppressRuntimeContext"]?.let {
                runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
            } ?: false,
            commands = commands,
            llmProviders = llmProviders,
            routes = routes,
            projections = projections,
            shells = shells,
            loadings = loadings,
            agents = agents,
            storages = storages,
            prompts = prompts,
            capabilities = capabilities,
            unsupported = obj.stringList("unsupported"),
            provides = provides,
            injects = injectDeps,
            missingServices = obj.stringList("missingDeps"),
            effects = obj.stringList("effects"),
            subscriptions = obj.stringList("subscriptions"),
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
  const __dsh = { name: null, version: null, inject: [], tools: [], errors: [], imports: [], relativeImports: [], applied: false, configSchema: null, configError: null, promptSections: [], contexts: [], promptVariables: {}, suppressRuntimeContext: false, commands: [], llmProviders: [], routes: [], projections: [], shells: [], loadings: [], agents: [], storages: [], prompts: [], capabilities: [], unsupported: [], provides: {}, injects: [], missingDeps: [], effects: [], subscriptions: [] };
  function __dshUnsupported(name) {
    if (__dsh.unsupported.indexOf(name) < 0) { __dsh.unsupported.push(name); }
    return __noop;
  }
  const __dshDefs = {};
  // M84-H：会话投影。定义留在 JS 侧（常驻运行时里由宿主事件驱动），元数据进 __dsh.projections。
  const __dshProjectionDefs = {};
  const __dshProjectionKeys = [];
  const __dshProjectionCells = {};
  // M84-I：事件订阅 / 清理器。只有常驻运行时能把宿主事件送进来、并在停用时跑 cleanup。
  const __dshEventHandlers = {};
  const __dshCleanups = [];
  // M84-J：带 stream 回调的模型供应商。宿主发起请求时 dispatch 'llm.stream' 进常驻运行时。
  const __dshStreamProviders = {};
  // M85-A：整壳。mount/onState/onAction 闭包留在常驻 runtime，宿主每次 dispatch 进来取新树。
  const __dshShellDefs = {};
  const __dshShellIds = [];
  // M85-D：回合实现（agent/v1）。run 闭包留在常驻 runtime；宿主的每轮 dispatch 'agent.run'。
  const __dshAgentDefs = {};
  const __dshAgentIds = [];
  // M85-E：存储（storage/v1，只读）与提示词（prompt/v1）。list/load/assemble 闭包留在常驻 runtime。
  const __dshStorageDefs = {};
  const __dshPromptDefs = {};
  // M85-F：能力提供方（capability/v1）。invoke 闭包留在常驻 runtime；宿主的插件页/调试通道
  // dispatch 'capability.invoke' 进来。root/NFC/Shizuku/adb-shell 等特权能力只能这样由插件提供。
  const __dshCapabilityDefs = {};
  function __dshJsonSafe(value) {
    if (value === undefined) { return null; }
    try { return JSON.parse(JSON.stringify(value)); } catch (e) { return String(value); }
  }
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
  // ── M84-P：Buffer（UTF-8 / hex / base64）与 EventEmitter（Node 兼容矩阵用）──────────
  // QuickJS 没有 Node 的 Buffer；插件里 `Buffer.from(x).toString('base64')` 很常见，
  // 这里给一个只做字节数组的最小实现（不接 ArrayBuffer/TypedArray 互转，按需再加）。
  const __dshBase64Chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  function __dshUtf8Encode(text) {
    const bytes = [];
    const s = String(text);
    for (let i = 0; i < s.length; i++) {
      let code = s.charCodeAt(i);
      if (code >= 0xD800 && code <= 0xDBFF && i + 1 < s.length) {
        const next = s.charCodeAt(i + 1);
        if (next >= 0xDC00 && next <= 0xDFFF) {
          code = 0x10000 + ((code - 0xD800) << 10) + (next - 0xDC00);
          i += 1;
        }
      }
      if (code < 0x80) { bytes.push(code); }
      else if (code < 0x800) { bytes.push(0xC0 | (code >> 6), 0x80 | (code & 0x3F)); }
      else if (code < 0x10000) { bytes.push(0xE0 | (code >> 12), 0x80 | ((code >> 6) & 0x3F), 0x80 | (code & 0x3F)); }
      else { bytes.push(0xF0 | (code >> 18), 0x80 | ((code >> 12) & 0x3F), 0x80 | ((code >> 6) & 0x3F), 0x80 | (code & 0x3F)); }
    }
    return bytes;
  }
  function __dshUtf8Decode(bytes) {
    let out = '';
    for (let i = 0; i < bytes.length;) {
      const b0 = bytes[i++] & 0xFF;
      let code;
      if (b0 < 0x80) { code = b0; }
      else if (b0 >= 0xC0 && b0 < 0xE0) { code = ((b0 & 0x1F) << 6) | ((bytes[i++] || 0) & 0x3F); }
      else if (b0 >= 0xE0 && b0 < 0xF0) { code = ((b0 & 0x0F) << 12) | (((bytes[i++] || 0) & 0x3F) << 6) | ((bytes[i++] || 0) & 0x3F); }
      else if (b0 >= 0xF0) { code = ((b0 & 0x07) << 18) | (((bytes[i++] || 0) & 0x3F) << 12) | (((bytes[i++] || 0) & 0x3F) << 6) | ((bytes[i++] || 0) & 0x3F); }
      else { code = 0xFFFD; }
      if (code > 0xFFFF) {
        const rest = code - 0x10000;
        out += String.fromCharCode(0xD800 + (rest >> 10), 0xDC00 + (rest & 0x3FF));
      } else { out += String.fromCharCode(code); }
    }
    return out;
  }
  function __dshHexEncode(bytes) {
    let out = '';
    for (let i = 0; i < bytes.length; i++) { const b = bytes[i] & 0xFF; out += (b < 16 ? '0' : '') + b.toString(16); }
    return out;
  }
  function __dshHexDecode(text) {
    const s = String(text).replace(/[^0-9a-fA-F]/g, '');
    const out = [];
    for (let i = 0; i + 1 < s.length; i += 2) { out.push(parseInt(s.slice(i, i + 2), 16) & 0xFF); }
    return out;
  }
  function __dshBase64Encode(bytes) {
    let out = '';
    for (let i = 0; i < bytes.length; i += 3) {
      const b0 = bytes[i] & 0xFF;
      const b1 = (i + 1 < bytes.length) ? (bytes[i + 1] & 0xFF) : 0;
      const b2 = (i + 2 < bytes.length) ? (bytes[i + 2] & 0xFF) : 0;
      const triplet = (b0 << 16) | (b1 << 8) | b2;
      out += __dshBase64Chars.charAt((triplet >> 18) & 0x3F) + __dshBase64Chars.charAt((triplet >> 12) & 0x3F);
      out += (i + 1 < bytes.length) ? __dshBase64Chars.charAt((triplet >> 6) & 0x3F) : '=';
      out += (i + 2 < bytes.length) ? __dshBase64Chars.charAt(triplet & 0x3F) : '=';
    }
    return out;
  }
  function __dshBase64Decode(text) {
    const clean = String(text).replace(/[^A-Za-z0-9+/=]/g, '');
    const out = [];
    let buffer = 0;
    let bits = 0;
    for (let i = 0; i < clean.length; i++) {
      const c = clean.charAt(i);
      if (c === '=') { break; }
      const idx = __dshBase64Chars.indexOf(c);
      if (idx < 0) { continue; }
      buffer = (buffer << 6) | idx;
      bits += 6;
      if (bits >= 8) { bits -= 8; out.push((buffer >> bits) & 0xFF); }
    }
    return out;
  }
  function DshBuffer(bytes) {
    const raw = Array.isArray(bytes) ? bytes : [];
    const list = [];
    for (let i = 0; i < raw.length; i++) { list.push(Number(raw[i]) & 0xFF); }
    this.bytes = list;
    this.length = list.length;
  }
  DshBuffer.prototype.toString = function(encoding) {
    const enc = String(encoding || 'utf8').toLowerCase();
    if (enc === 'hex') { return __dshHexEncode(this.bytes); }
    if (enc === 'base64') { return __dshBase64Encode(this.bytes); }
    if (enc === 'latin1' || enc === 'binary') {
      let out = '';
      for (let i = 0; i < this.bytes.length; i++) { out += String.fromCharCode(this.bytes[i]); }
      return out;
    }
    return __dshUtf8Decode(this.bytes);
  };
  DshBuffer.prototype.toJSON = function() { return { type: 'Buffer', data: this.bytes.slice(0) }; };
  DshBuffer.prototype.slice = function(start, end) { return new DshBuffer(this.bytes.slice(start, end)); };
  DshBuffer.prototype.equals = function(other) {
    if (!DshBuffer.isBuffer(other) || other.length !== this.length) { return false; }
    for (let i = 0; i < this.bytes.length; i++) { if (this.bytes[i] !== other.bytes[i]) { return false; } }
    return true;
  };
  DshBuffer.isBuffer = function(value) { return value instanceof DshBuffer; };
  DshBuffer.isEncoding = function(encoding) {
    const enc = String(encoding || '').toLowerCase();
    return enc === 'utf8' || enc === 'utf-8' || enc === 'hex' || enc === 'base64' || enc === 'latin1' || enc === 'binary';
  };
  DshBuffer.from = function(input, encoding) {
    if (DshBuffer.isBuffer(input)) { return new DshBuffer(input.bytes.slice(0)); }
    if (Array.isArray(input)) { return new DshBuffer(input); }
    if (typeof input === 'number') { return DshBuffer.alloc(input); }
    if (typeof input === 'string') {
      const enc = String(encoding || 'utf8').toLowerCase();
      if (enc === 'hex') { return new DshBuffer(__dshHexDecode(input)); }
      if (enc === 'base64') { return new DshBuffer(__dshBase64Decode(input)); }
      if (enc === 'latin1' || enc === 'binary') {
        const list = [];
        for (let i = 0; i < input.length; i++) { list.push(input.charCodeAt(i) & 0xFF); }
        return new DshBuffer(list);
      }
      return new DshBuffer(__dshUtf8Encode(input));
    }
    return new DshBuffer([]);
  };
  DshBuffer.alloc = function(size, fill) {
    const n = Math.max(0, Number(size) | 0);
    let value = 0;
    if (typeof fill === 'number') { value = fill & 0xFF; }
    else if (typeof fill === 'string' && fill.length > 0) { value = fill.charCodeAt(0) & 0xFF; }
    const list = [];
    for (let i = 0; i < n; i++) { list.push(value); }
    return new DshBuffer(list);
  };
  DshBuffer.concat = function(list, totalLength) {
    const chunks = Array.isArray(list) ? list : [];
    const out = [];
    for (let i = 0; i < chunks.length; i++) {
      const item = chunks[i];
      if (!item) { continue; }
      const bytes = DshBuffer.isBuffer(item) ? item.bytes : DshBuffer.from(item).bytes;
      for (let j = 0; j < bytes.length; j++) { out.push(bytes[j]); }
    }
    if (typeof totalLength === 'number' && totalLength >= 0 && totalLength < out.length) { out.length = totalLength; }
    return new DshBuffer(out);
  };
  DshBuffer.byteLength = function(input, encoding) { return DshBuffer.from(input, encoding).length; };
  function DshEventEmitter() { this._events = {}; }
  DshEventEmitter.prototype.on = function(name, fn) {
    if (typeof fn !== 'function') { return this; }
    const key = String(name);
    const list = this._events[key] || (this._events[key] = []);
    list.push(fn);
    return this;
  };
  DshEventEmitter.prototype.addListener = DshEventEmitter.prototype.on;
  DshEventEmitter.prototype.prependListener = function(name, fn) {
    if (typeof fn !== 'function') { return this; }
    const key = String(name);
    const list = this._events[key] || (this._events[key] = []);
    list.unshift(fn);
    return this;
  };
  DshEventEmitter.prototype.once = function(name, fn) {
    const self = this;
    if (typeof fn !== 'function') { return this; }
    function wrapped() {
      self.off(name, wrapped);
      return fn.apply(self, arguments);
    }
    wrapped.__dshOriginal = fn;
    return this.on(name, wrapped);
  };
  DshEventEmitter.prototype.off = function(name, fn) {
    const key = String(name);
    const list = this._events[key];
    if (!list) { return this; }
    const kept = [];
    for (let i = 0; i < list.length; i++) {
      const item = list[i];
      if (item === fn || item.__dshOriginal === fn) { continue; }
      kept.push(item);
    }
    this._events[key] = kept;
    return this;
  };
  DshEventEmitter.prototype.removeListener = DshEventEmitter.prototype.off;
  DshEventEmitter.prototype.removeAllListeners = function(name) {
    if (name === undefined || name === null) { this._events = {}; }
    else { delete this._events[String(name)]; }
    return this;
  };
  DshEventEmitter.prototype.emit = function(name) {
    const key = String(name);
    const list = this._events[key];
    if (!list || list.length === 0) { return false; }
    const args = Array.prototype.slice.call(arguments, 1);
    const copy = list.slice(0);
    for (let i = 0; i < copy.length; i++) { copy[i].apply(this, args); }
    return true;
  };
  DshEventEmitter.prototype.listenerCount = function(name) {
    const list = this._events[String(name)];
    return list ? list.length : 0;
  };
  DshEventEmitter.prototype.listeners = function(name) { return (this._events[String(name)] || []).slice(0); };
  DshEventEmitter.prototype.setMaxListeners = function() { return this; };
  DshEventEmitter.defaultMaxListeners = 10;
  function __dshEventsOnce(emitter, name) {
    return new Promise(function(resolve) {
      emitter.once(name, function() { resolve(Array.prototype.slice.call(arguments)); });
    });
  }
  const __dshEventsModule = {
    EventEmitter: DshEventEmitter,
    once: __dshEventsOnce,
    default: DshEventEmitter
  };
  const __dshBufferModule = { Buffer: DshBuffer, default: { Buffer: DshBuffer } };
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
      // M84-E1：抑制宿主的运行时上下文快照（DSH `systemPrompt.suppressRuntimeContext`）。
      // 手机端没有常驻运行时：apply 时记一个布尔，启用期间宿主每轮都不再注入 runtime context；
      // 其它 `ctx.systemPrompt.context` 段仍照常注入（它们不是 runtime context）。
      suppressRuntimeContext: function() {
        __dsh.suppressRuntimeContext = true;
        return __noop;
      },
      // M85-E：提示词实现（prompt/v1）。被选中后，宿主每轮把内置拼装结果作为 base 交给
      // assemble({base, model, provider, tools, planMode, personaPrefix, personaSuffix, variables})，
      // 插件返回最终 system 文本（整体替换或包一层）。同 id 可覆盖 builtin.prompt.dsh。
      register: function(def) {
        if (!def || typeof def !== 'object') {
          return __dshUnsupported('systemPrompt.register(invalid)');
        }
        const id = String(def.id || def.name || '').trim().slice(0, 80);
        if (!id) { return __dshUnsupported('systemPrompt.register(no id)'); }
        if (typeof def.assemble !== 'function') {
          return __dshUnsupported('systemPrompt.register(no assemble)');
        }
        if (__dshPromptDefs[id]) { return __noop; }
        __dshPromptDefs[id] = def;
        __dsh.prompts.push({
          id: id,
          label: String(def.label || def.title || id).slice(0, 120)
        });
        return __noop;
      }
    }),
    // M85-E：存储实现（storage/v1，v1 只读）。被选中后，宿主通过 dispatch 'storage.list' /
    // 'storage.load' 向插件要会话摘要 / 原始会话文档；写入仍由内置 native 实现负责。
    // exportList / exportLoad 是给「只读导出器」用的宿主导出原语：只读内置 builtin.storage.jsonl，
    // 不碰当前活动存储（不会递归），需要 storage 授权。
    storage: __permissive({
      register: function(def) {
        if (!def || typeof def !== 'object') {
          return __dshUnsupported('storage.register(invalid)');
        }
        const id = String(def.id || def.name || '').trim().slice(0, 80);
        if (!id) { return __dshUnsupported('storage.register(no id)'); }
        if (typeof def.list !== 'function' && typeof def.load !== 'function') {
          return __dshUnsupported('storage.register(no list/load)');
        }
        if (__dshStorageDefs[id]) { return __noop; }
        __dshStorageDefs[id] = def;
        __dsh.storages.push({
          id: id,
          label: String(def.label || def.title || id).slice(0, 120),
          readOnly: true,
          hasList: typeof def.list === 'function',
          hasLoad: typeof def.load === 'function'
        });
        return __noop;
      },
      exportList: function() {
        return __dshHostJson('storage.exportList', {});
      },
      exportLoad: function(sessionId) {
        return __dshHostJson('storage.exportLoad', { sessionId: String(sessionId || '') });
      }
    }),
    // M85-F：能力提供方（capability/v1）。插件声明一个能力 id + invoke(args)；
    // 宿主只登记 + 权限门（capability 授权）+ 超时，插件页/调试通道按 id 调用。
    // 特权能力（root/NFC/Shizuku/adb-shell）也走这里，宿主不写死任何一条。
    capability: __permissive({
      register: function(def) {
        if (!def || typeof def !== 'object') {
          return __dshUnsupported('capability.register(invalid)');
        }
        const id = String(def.id || def.name || '').trim().slice(0, 80);
        if (!id || !/^[A-Za-z0-9][A-Za-z0-9._:-]*$/.test(id)) {
          return __dshUnsupported('capability.register(no valid id)');
        }
        if (typeof def.invoke !== 'function') {
          return __dshUnsupported('capability.register(no invoke)');
        }
        if (__dshCapabilityDefs[id]) { return __noop; }
        __dshCapabilityDefs[id] = def;
        __dsh.capabilities.push({
          id: id,
          label: String(def.label || def.title || id).slice(0, 120),
          description: String(def.description || '').slice(0, 300),
          kind: String(def.kind || def.category || '').replace(/[^A-Za-z0-9._]/g, '').slice(0, 32)
        });
        return __noop;
      }
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
      },
      // M83 阶段 3 开桥：OCR（ML Kit，已在 APK 里；要 device 权限）与 zstd 压缩/解压
      //（zstd-jni，已在 APK 里；路径要过 fs 权限）。都是同步宿主调用。
      ocr: function(path) {
        return __dshHostOk('ocr.recognize', { path: String(path === undefined || path === null ? '' : path) });
      },
      zstd: function(options) {
        const opts = options || {};
        return __dshHostOk('zip.zstd', {
          mode: (opts.mode === undefined || opts.mode === null) ? 'compress' : String(opts.mode),
          path: String(opts.path === undefined || opts.path === null ? '' : opts.path),
          out: (opts.out === undefined || opts.out === null) ? '' : String(opts.out),
          level: (typeof opts.level === 'number') ? opts.level : undefined
        });
      }
    }),
    // M84-H：会话投影。DSH 的真实契约是一个 key + stateSchema + init(header, inherited)
    // + 同步 fold apply(state, event) + 可选 wire.view。手机端现在有常驻 JS 运行时：
    // 定义留在 JS 里，宿主每提交一条会话事件就 dispatch 进来折叠一次，返回值是 wire 视图。
    // 没有 key / 不是对象的注册按 unsupported 记（不假装成功）。
    sessionProjections: __permissive({
      register: function(definition) {
        if (!definition || typeof definition !== 'object') {
          return __dshUnsupported('sessionProjections.register(invalid)');
        }
        const key = (typeof definition.key === 'string') ? definition.key.trim().slice(0, 80) : '';
        if (!key) {
          return __dshUnsupported('sessionProjections.register(no key)');
        }
        const version = (typeof definition.stateVersion === 'number') ? definition.stateVersion : 1;
        const hasView = !!(definition.wire && typeof definition.wire.view === 'function');
        const hasApply = typeof definition.apply === 'function';
        if (__dshProjectionKeys.indexOf(key) < 0) {
          __dshProjectionKeys.push(key);
          __dshProjectionDefs[key] = definition;
          __dsh.projections.push({
            key: key,
            stateVersion: version,
            hasView: hasView,
            hasApply: hasApply
          });
        }
        return __permissive({
          key: key,
          stateVersion: version,
          stateOf: function(session) {
            const id = (session && (session.id || session.sessionId)) ? String(session.id || session.sessionId) : '';
            const cell = id ? __dshProjectionCells[id + '\u0000' + key] : undefined;
            return cell ? cell.state : undefined;
          },
          dispose: function() {
            if (__dshProjectionDefs[key]) { delete __dshProjectionDefs[key]; }
            return Promise.resolve(undefined);
          }
        });
      }
    }),
    // M84-E2 / M84-J：模型供应商。没有 stream 回调 = 配置式（宿主包装成内置通道）；
    // 有 stream 回调 = 插件自己产出模型回合（常驻运行时里执行，请求体是完整的 Anthropic 形状）。
    llm: __permissive({
      register: function(provider) {
        if (!provider || typeof provider !== 'object') { return __noop; }
        const id = String(provider.id || provider.name || '');
        const hasStream = typeof provider.stream === 'function';
        if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(id)) {
          if (hasStream) { return __dshUnsupported('llm.register(stream)'); }
          return __noop;
        }
        if (__dsh.llmProviders.some(function(p) { return p.id === id; })) { return __noop; }
        const rawProtocol = String(provider.protocol || provider.api || '').toLowerCase();
        const protocol = (rawProtocol === 'openai' || rawProtocol === 'openai-completions' ||
          rawProtocol === 'openai-compatible' || rawProtocol === 'chat') ? 'openai' :
          ((rawProtocol === 'anthropic' || rawProtocol === 'anthropic-messages' ||
            rawProtocol === 'messages' || rawProtocol === 'claude') ? 'anthropic' : '');
        const label = String(provider.label || provider.title || id).slice(0, 120);
        const baseUrl = String(provider.baseUrl || provider.baseURL || provider.endpoint || '').slice(0, 400);
        const model = String(provider.model || '').slice(0, 200);
        const apiKey = String(provider.apiKey || provider.key || '').slice(0, 500);
        __dsh.llmProviders.push({
          id: id,
          label: label,
          protocol: protocol,
          baseUrl: baseUrl,
          model: model,
          apiKey: apiKey,
          hasStream: hasStream
        });
        if (hasStream) {
          __dshStreamProviders[id] = provider;
        }
        return __noop;
      }
    }),
    // M84-E2：页面路由。最小版只登记元数据（id/title/description），
    // 插件页显示入口；点开时跑插件自己的 dsh.client（没有 client 时如实提示）。
    // registerView（往宿主页面插任意 Composable）仍未实现，继续返回 unsupported。
    ui: __permissive({
      registerRoute: function(route) {
        if (!route) { return __noop; }
        let id = '';
        let title = '';
        let description = '';
        if (typeof route === 'string') {
          id = route;
        } else if (typeof route === 'object') {
          id = String(route.path || route.id || route.name || '');
          title = String(route.title || route.label || '');
          description = String(route.description || '');
        }
        id = id.trim();
        if (!id) { return __noop; }
        if (__dsh.routes.some(function(r) { return r.id === id; })) { return __noop; }
        const fullPage = route.page === true || route.fullscreen === true || route.kind === 'page';
        __dsh.routes.push({
          id: id.slice(0, 160),
          title: title.slice(0, 120) || id.slice(0, 120),
          description: description.slice(0, 300),
          page: fullPage
        });
        return __noop;
      },
      // M84-G：整页插件页面。registerPage 等价于 registerRoute({..., page: true})；
      // id 与宿主内置页同名时，宿主用插件页替换该内置页（例如 About）。
      registerPage: function(route) {
        if (!route) { return __noop; }
        if (typeof route === 'string') { return this.registerRoute({ path: route, page: true }); }
        const copy = {};
        for (const key in route) { copy[key] = route[key]; }
        copy.page = true;
        return this.registerRoute(copy);
      },
      // loading/v1 is collected as immutable JSON; it never needs a resident runtime.
      registerLoading: function(def) {
        if (!def || typeof def !== 'object' || typeof def.id !== 'string' || !def.tree || typeof def.tree !== 'object') {
          __dsh.errors.push('loading/v1：registerLoading 需要 {id,label,tree}');
          return __noop;
        }
        if (__dsh.loadings.length >= 8) {
          __dsh.errors.push('loading/v1：最多注册 8 个加载页');
          return __noop;
        }
        if (__dsh.loadings.some(function(item) { return item.id === def.id; })) {
          __dsh.errors.push('loading/v1：重复 id ' + def.id);
          return __noop;
        }
        try {
          const raw = JSON.stringify(def, function(key, value) {
            if (typeof value === 'function' || typeof value === 'undefined' || typeof value === 'symbol') {
              throw new Error('加载页只接受静态 JSON');
            }
            return value;
          });
          if (raw.length > 32768) { throw new Error('加载页声明过大'); }
          __dsh.loadings.push(JSON.parse(raw));
        } catch (err) {
          __dsh.errors.push('loading/v1：' + String((err && err.message) || err));
        }
        return __noop;
      },
      registerView: function() { return __dshUnsupported('ui.registerView'); },
      // M85-A：整壳。一个插件可以带多个壳；宿主按 `dsh_shell` 选择并 dispatch
      // shell.mount / shell.state / shell.action / shell.event。定义留在常驻 runtime，
      // 元数据（id/label/有哪些回调）进 __dsh.shells 供宿主/插件页展示。
      registerShell: function(shell) {
        if (!shell || typeof shell !== 'object') {
          return __dshUnsupported('ui.registerShell(invalid)');
        }
        const id = String(shell.id || shell.name || '').trim().slice(0, 80);
        if (!id) { return __dshUnsupported('ui.registerShell(no id)'); }
        if (typeof shell.mount !== 'function') {
          return __dshUnsupported('ui.registerShell(no mount)');
        }
        if (__dshShellDefs[id]) { return __noop; }
        __dshShellDefs[id] = shell;
        __dshShellIds.push(id);
        // M85-F：后端 = surface（默认，返回 Surface 树）或 webview（返回整屏 HTML）。
        const backend = String(shell.backend || shell.kind || 'surface').toLowerCase();
        __dsh.shells.push({
          id: id,
          label: String(shell.label || shell.title || id).slice(0, 120),
          backend: backend === 'webview' ? 'webview' : 'surface',
          hasState: typeof shell.onState === 'function' || typeof shell.state === 'function',
          hasAction: typeof shell.onAction === 'function' || typeof shell.action === 'function',
          hasEvent: typeof shell.onEvent === 'function' || typeof shell.event === 'function'
        });
        return __noop;
      }
    }),
    // M85-D：回合实现（agent/v1）。宿主按 `dsh_agent` 选中后每轮 dispatch 'agent.run'；
    // run 闭包留在常驻 runtime，元数据（id/label）进 __dsh.agents 供插件页/安全提示展示。
    // 插件仍需 `agent` 授权；没有授权时宿主会拒绝选择（同 id 覆盖也一样）。
    agent: __permissive({
      register: function(def) {
        if (!def || typeof def !== 'object') {
          return __dshUnsupported('agent.register(invalid)');
        }
        const id = String(def.id || def.name || '').trim().slice(0, 80);
        if (!id) { return __dshUnsupported('agent.register(no id)'); }
        if (typeof def.run !== 'function') {
          return __dshUnsupported('agent.register(no run)');
        }
        if (__dshAgentDefs[id]) { return __noop; }
        __dshAgentDefs[id] = def;
        __dshAgentIds.push(id);
        __dsh.agents.push({
          id: id,
          label: String(def.label || def.title || id).slice(0, 120)
        });
        return __noop;
      }
    }),
    // M85-A：壳 → 宿主的同步命令。宿主只做白名单分发（send/stop/…），
    // 插件不能借它执行任意宿主调用。
    surface: __permissive({
      command: function(name, args) {
        const command = String(name || '').trim();
        if (!command) { return Promise.resolve({ ok: false, error: 'surface.command 缺少命令名' }); }
        return __dshHostJson('surface.command', {
          command: command,
          args: (args === undefined || args === null) ? {} : args
        });
      }
    }),
    // ── M83 阶段 1：服务容器（宿主侧快照 + 依赖注入）────────────────────
    // 手机端每次 apply 都是新 QuickJS：服务值是宿主传进来的 JSON 快照，
    // provide/inject 只登记数据，真正的「谁提供、谁缺」由宿主 PluginServices.resolve 算。
    provide: function(name, value) {
      if (typeof name !== 'string' || !name) { return __noop; }
      let json = 'null';
      try { json = JSON.stringify(value === undefined ? null : value); } catch (e) { json = JSON.stringify(String(value)); }
      if (json === undefined || json === null) { json = 'null'; }
      __dsh.provides[name] = json;
      try { __dshHostJson('plugin.services.provide', { name: name, value: json }); } catch (e) {}
      return __noop;
    },
    inject: function(deps, callback) {
      const list = Array.isArray(deps) ? deps : ((deps === undefined || deps === null) ? [] : [deps]);
      const meta = [];
      for (let i = 0; i < list.length; i++) {
        const d = list[i];
        let name = '';
        let optional = false;
        if (typeof d === 'string') { name = d; }
        else if (d && typeof d === 'object') {
          name = String(d.name || '');
          optional = (d.optional === true) || (d.required === false);
        }
        if (!name) { continue; }
        meta.push({ name: name, optional: optional });
        __dsh.injects.push({ name: name, optional: optional });
      }
      if (meta.length === 0) { return __noop; }
      const names = meta.map(function(m) { return m.name; });
      const reply = __dshHostJson('plugin.services.values', { names: names });
      if (!reply || reply.ok !== true) {
        // 没有宿主（JVM 单测 / 无服务容器的试运行）：不误报缺依赖，跳过回调。
        return __noop;
      }
      const values = reply.values || {};
      const missing = [];
      for (let i = 0; i < meta.length; i++) {
        if (!Object.prototype.hasOwnProperty.call(values, meta[i].name) && !meta[i].optional) {
          missing.push(meta[i].name);
        }
      }
      if (missing.length > 0) {
        for (let i = 0; i < missing.length; i++) {
          if (__dsh.missingDeps.indexOf(missing[i]) < 0) { __dsh.missingDeps.push(missing[i]); }
        }
        return __noop;
      }
      if (typeof callback !== 'function') { return __noop; }
      const scope = Object.create(__ctx);
      for (let i = 0; i < names.length; i++) {
        const raw = values[names[i]];
        let parsed = raw;
        if (typeof raw === 'string') { try { parsed = JSON.parse(raw); } catch (e) { parsed = raw; } }
        scope[names[i]] = parsed;
      }
      try {
        const r = callback(scope);
        if (r && typeof r.then === 'function') {
          Promise.resolve(r).catch(function(err) {
            __dsh.errors.push('ctx.inject 回调异步报错：' + String((err && err.message) || err));
          });
        }
      } catch (err) {
        __dsh.errors.push('ctx.inject 回调报错：' + String((err && err.message) || err));
      }
      return __noop;
    },
    effect: function(fn) {
      if (typeof fn === 'function') {
        try {
          const cleanup = fn();
          __dsh.effects.push((typeof cleanup === 'function') ? 'cleanup' : 'effect');
          // M84-I：常驻运行时停用插件时真的回调 cleanup（异步 cleanup 不阻塞，异常记日志）。
          if (typeof cleanup === 'function') { __dshCleanups.push(cleanup); }
        } catch (err) {
          __dsh.errors.push('ctx.effect 报错：' + String((err && err.message) || err));
        }
      }
      try { __dshHostJson('plugin.services.effect', {}); } catch (e) {}
      return __noop;
    },
    on: function(event, handler) {
      if (typeof event !== 'string' || !event) { return __noop; }
      if (__dsh.subscriptions.indexOf(event) < 0) { __dsh.subscriptions.push(event); }
      // M84-I：handler 留在常驻运行时里；宿主事件（turn/start、tool/result…）dispatch 进来时调用。
      if (typeof handler === 'function') {
        if (!__dshEventHandlers[event]) { __dshEventHandlers[event] = []; }
        if (__dshEventHandlers[event].length < 32) { __dshEventHandlers[event].push(handler); }
        if (!__dshDefs['event:' + event]) { __dshDefs['event:' + event] = { handler: handler }; }
      }
      try { __dshHostJson('plugin.services.on', { event: event }); } catch (e) {}
      return __noop;
    },
    plugin: function(sub, config) {
      try {
        if (sub && typeof sub.apply === 'function') { sub.apply(__ctx, config); }
        else if (typeof sub === 'function') { sub(__ctx, config); }
      } catch (err) {
        __dsh.errors.push('ctx.plugin 组合子插件报错：' + String((err && err.message) || err));
      }
      return __noop;
    },
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
    if (name === 'node:buffer' || name === 'buffer') {
      return __dshBufferModule;
    }
    if (name === 'node:events' || name === 'events') {
      return __dshEventsModule;
    }
    if (name === 'node:timers' || name === 'timers') {
      return {
        setTimeout: setTimeout,
        clearTimeout: clearTimeout,
        setInterval: setInterval,
        clearInterval: clearInterval,
        setImmediate: setImmediate,
        clearImmediate: clearImmediate
      };
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
     *   mkdirSync / rmSync / renameSync / copyFileSync / promises.*（只支持文本，返回/接受 Buffer 时按 UTF-8 转）；
     * - `ctx.http` / 全局 `fetch`：https + 域名白名单，返回 `{status, ok, headers, body}`；
     * - `node:https` / `node:http` 的 `get/request`：最小回调式实现（body 一次给完）；
     * - `node:child_process`：execSync / exec（需 shell 权限）；
     * - `node:buffer` + 全局 `Buffer`（M84-P）：UTF-8 / hex / base64 / latin1 的 from/alloc/concat/toString/equals；
     * - `node:events`（M84-P）：EventEmitter（on/once/off/emit/listenerCount）+ events.once；
     * - `node:timers`（M84-P）：复用全局 setTimeout/setInterval/clear*；
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
    globalThis.Buffer = DshBuffer;
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
