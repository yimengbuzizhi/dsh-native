package xin.dshaa.core.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Operit 自带工具包（`app/src/main/assets/packages/` 下的 .js 文件）的兼容层（M63）。
 *
 * Operit 包的形状：文件头部用 `METADATA {...}` 块注释声明工具（name/description/parameters），
 * 文件体是 CommonJS：`const pkg = (function(){...})(); exports.xxx = pkg.xxx;`，
 * 宿主执行时注入 `OkHttp`、`Tools.*`、全局 `complete(payload)` 等。
 *
 * 鲸歌没有 Operit 的宿主 API，但可以把这类包**包成 DSH 插件**：
 * - 元数据在运行时用 JS 求值（Operit 的 METADATA 不是严格 JSON：time.js 的键没引号）；
 * - 每次 execute 重新执行包体（QuickJS 本来每次调用重建 runtime），拿到 `exports.<tool>`；
 * - `complete()` 的 payload 归一成工具返回值；字符串/对象直接返回；
 * - `OkHttp` 用 PRELUDE 的 `fetch`（受 `net:` 权限约束）实现常用 builder 子集；
 * - `Tools.*` 由 [OPERIT_TOOLS_JS] 实现（M67）：文件/系统/网络/UI 走宿主通道，
 *   设备类能力走 [PluginHostBridge]；其余组抛明确错误，不做空壳。
 *
 * 不进这层的包（依赖 Operit 私有数据库 / 子 Agent / 随包二进制）仍需逐个改写或保持未支持。
 */
object ToolPkg {

    /** 判定：有 METADATA 注释 + `exports.` 赋值，且没有 DSH 的 `apply` 导出。 */
    fun isToolPkg(source: String): Boolean =
        source.contains("METADATA") &&
            source.contains("exports.") &&
            !Regex("""export\s+(async\s+)?function\s+apply""").containsMatchIn(source) &&
            !source.contains("ctx.tools.register")

    /**
     * 解析 Operit 方言的 METADATA：键可以不加引号、条目之间可以没有逗号
     * （`time.js` 就是这种写法），所以不能用 JSON.parse，而用下面的容错解析器。
     */
    fun metadata(source: String): JsonObject? {
        val start = source.indexOf("METADATA")
        if (start < 0) return null
        val bodyStart = start + "METADATA".length
        val end = source.indexOf("*/", bodyStart)
        if (end < 0) return null
        return runCatching { MetaParser(source.substring(bodyStart, end)).parseObject() }.getOrNull()
    }

    /**
     * 把 METADATA 的 `env` 归一成 `[{name, description, required, default}]`（M68）。
     *
     * Operit 的 `env` 有两种写法（`ToolPackage.kt` 的 `EnvVarSerializer`）：
     * - 老写法纯字符串 `["GITHUB_TOKEN"]`：`required` 默认 **true**（EnvVar 的默认值）；
     * - 新写法对象 `{name, description:{zh,en}, required, defaultValue}`。
     * 归一之后既能生成插件配置 schema，也能在调用前检查「必填的 Key 还没填」。
     */
    fun envSpecs(meta: JsonObject?): JsonArray {
        val raw = meta?.get("env") as? JsonArray ?: return JsonArray(emptyList())
        return JsonArray(
            raw.mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> {
                        val name = element.content.trim()
                        if (name.isEmpty()) null else buildJsonObject {
                            put("name", name)
                            put("required", true)
                        }
                    }
                    is JsonObject -> {
                        val name = (element["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
                        if (name.isEmpty()) {
                            null
                        } else {
                            buildJsonObject {
                                put("name", name)
                                element["description"]?.let { put("description", it) }
                                val required = (element["required"] as? JsonPrimitive)?.content
                                put("required", required == null || required == "true")
                                element["defaultValue"]?.let { put("default", it) }
                            }
                        }
                    }
                    else -> null
                }
            },
        )
    }

    /** 把 Operit 包源码包成一个 ESM DSH 插件（原文件另存为 `toolpkg-original.js`）。 */
    fun wrap(source: String): String {
        val literal = JsonPrimitive(source).toString()
        val meta = metadata(source)
        val metaJson = (meta ?: buildJsonObject {
            put("name", JsonPrimitive("toolpkg"))
            put("tools", JsonArray(emptyList()))
        }).toString()
        val envJson = envSpecs(meta).toString()
        val metaError = if (meta == null) "METADATA parse failed" else ""
        val errorLiteral = JsonPrimitive(metaError).toString()
        return """
// 由鲸歌 ToolPkg 兼容层自动生成：Operit 包 -> DSH 插件（M63）。不要手改，重装会覆盖。
const __toolPkgSource = $literal;
const __meta = $metaJson;
const __metaError = $errorLiteral;
const __envSpec = $envJson;

function __txt(value) {
  if (!value) return '';
  if (typeof value === 'string') return value;
  return value.zh || value.en || '';
}

function __schema(parameters) {
  const params = Array.isArray(parameters) ? parameters : [];
  const properties = {};
  const required = [];
  params.forEach(function (p) {
    if (!p || !p.name) return;
    const type = p.type === 'number' ? 'number'
      : p.type === 'boolean' ? 'boolean'
      : p.type === 'array' ? 'array'
      : p.type === 'object' ? 'object'
      : 'string';
    properties[p.name] = { type: type, description: __txt(p.description) };
    if (p.required === true) required.push(p.name);
  });
  const schema = { type: 'object', properties: properties };
  if (required.length > 0) schema.required = required;
  return schema;
}

/**
 * METADATA `env` → 插件配置 schema（M68）。
 *
 * Operit 的 env 是「包级环境变量」，宿主在设置页里填值，包体用 `getEnv(name)` 读。
 * 鲸歌没有全局 env 面板，把同一组 Key 变成**这个插件自己的配置**（`plugins.json` 的 config）：
 * 插件详情 → 配置里按 env 生成输入框，`getEnv` 读当前生效配置（schema 默认值 + 用户值）。
 * 这样 10 个「需要 API Key」的包不用改一行源码就能用。
 */
function __envSchema() {
  const spec = Array.isArray(__envSpec) ? __envSpec : [];
  const properties = {};
  const required = [];
  spec.forEach(function (item) {
    if (!item || !item.name) { return; }
    const prop = { type: 'string' };
    const desc = __txt(item.description);
    if (desc) { prop.description = desc; }
    if (item.default !== undefined && item.default !== null) { prop.default = String(item.default); }
    properties[item.name] = prop;
    if (item.required === true) { required.push(item.name); }
  });
  if (Object.keys(properties).length === 0) { return null; }
  const schema = { type: 'object', properties: properties };
  if (required.length > 0) { schema.required = required; }
  return schema;
}

/** 当前生效的插件配置（`apply(ctx, config)` 传进来的那份，已合并 schema 默认值）。 */
let __toolpkgConfig = {};

/** `getEnv(name)`：对齐 Operit 语义——没填返回空字符串（不是 undefined），也不抛错。 */
function __envValue(key) {
  const name = String(key === undefined || key === null ? '' : key).trim();
  if (!name) { return ''; }
  const value = __toolpkgConfig[name];
  if (value === undefined || value === null) { return ''; }
  return String(value);
}

/** 必填 env 还没填的清单（Operit 是在运行包之前拦下来并提示「Please set them」）。 */
function __missingEnv() {
  const spec = Array.isArray(__envSpec) ? __envSpec : [];
  const missing = [];
  spec.forEach(function (item) {
    if (!item || !item.name || item.required !== true) { return; }
    if (__envValue(item.name) === '') { missing.push(item.name); }
  });
  return missing;
}

/** OkHttp 的常用子集：url/method/headers/body/build/execute + statusCode/content/isSuccessful/json。 */
function __okhttp() {
  /** Operit 的 OkHttp 响应对象：包会用 .content / .statusCode / .isSuccessful() / .json()。 */
  function makeResponse(response) {
    const text = (response.body === undefined || response.body === null) ? '' : String(response.body);
    const headers = response.headers || {};
    return {
      statusCode: response.status,
      statusMessage: '',
      content: text,
      headers: headers,
      isSuccessful: function () { return response.status >= 200 && response.status < 300; },
      text: function () { return text; },
      json: function () { return JSON.parse(text); },
      header: function (name) {
        const key = String(name || '');
        return headers[key] || headers[key.toLowerCase()] || '';
      }
    };
  }
  function makeRequest() {
    const request = {
      requestUrl: '',
      httpMethod: 'GET',
      headers: {},
      bodyValue: null,
      bodyType: 'json',
      url: function (value) { this.requestUrl = String(value); return this; },
      method: function (value) { this.httpMethod = String(value || 'GET').toUpperCase(); return this; },
      headers: function (value) { Object.assign(this.headers, value || {}); return this; },
      body: function (value, type) { this.bodyValue = value; this.bodyType = type || 'json'; return this; },
      build: function () {
        const self = this;
        return {
          execute: async function () {
            const headers = Object.assign({}, self.headers);
            let body = null;
            if (self.bodyValue !== null && self.bodyValue !== undefined) {
              if (self.bodyType === 'form') {
                body = Object.keys(self.bodyValue).map(function (key) {
                  return encodeURIComponent(key) + '=' + encodeURIComponent(self.bodyValue[key]);
                }).join('&');
                headers['Content-Type'] = 'application/x-www-form-urlencoded';
              } else if (self.bodyType === 'text' || self.bodyType === 'raw') {
                body = String(self.bodyValue);
              } else {
                body = JSON.stringify(self.bodyValue);
                headers['Content-Type'] = 'application/json';
              }
            }
            const response = await fetch(self.requestUrl, {
              method: self.httpMethod,
              headers: headers,
              body: body
            });
            return makeResponse(response);
          }
        };
      }
    };
    return request;
  }
  function makeClient() {
    return {
      newRequest: makeRequest,
      get: function (url, headers) {
        const request = makeRequest().url(url).headers(headers || {});
        return request.build().execute();
      },
      post: function (url, body, headers) {
        const request = makeRequest().url(url).method('POST').body(body).headers(headers || {});
        return request.build().execute();
      },
      put: function (url, body, headers) {
        const request = makeRequest().url(url).method('PUT').body(body).headers(headers || {});
        return request.build().execute();
      },
      delete: function (url, headers) {
        const request = makeRequest().url(url).method('DELETE').headers(headers || {});
        return request.build().execute();
      },
      shutdown: function () {}
    };
  }
  return {
    newClient: makeClient,
    newBuilder: function () {
      return {
        connectTimeout: function () { return this; },
        readTimeout: function () { return this; },
        writeTimeout: function () { return this; },
        build: function () { return makeClient(); }
      };
    }
  };
}

$OPERIT_TOOLS_JS

/**
 * Operit 原生 `toolCall` 的最小兼容（M67）：`extended_http_tools` 这类包不经过 `Tools.*`，
 * 直接调宿主原生工具名（http_request / multipart_request / manage_cookies）。
 * 只映射鲸歌真的能做的；其余抛错，不返回假成功。
 */
function __operitToolCall(input, maybeParams) {
  const name = (input && typeof input === 'object') ? String(input.name || '') : String(input || '');
  const params = (input && typeof input === 'object') ? (input.params || {}) : (maybeParams || {});
  if (name === 'http_request') { return __toolHttpRequest(params); }
  if (name === 'multipart_request') { return __toolMultipartRequest(params); }
  throw new Error('原生工具 ' + name + ' 鲸歌暂不支持');
}

function __toolHeaders(value) {
  if (!value) { return {}; }
  if (typeof value === 'object') { return Object.assign({}, value); }
  try {
    const parsed = JSON.parse(String(value));
    return (parsed && typeof parsed === 'object') ? parsed : {};
  } catch (e) {
    return {};
  }
}

function __toolHeaderValue(headers, name) {
  if (!headers) { return ''; }
  const target = String(name || '').toLowerCase();
  const keys = Object.keys(headers);
  for (let i = 0; i < keys.length; i += 1) {
    if (keys[i].toLowerCase() === target) { return String(headers[keys[i]]); }
  }
  return '';
}

function __toolHttpRequest(params) {
  const headers = __toolHeaders(params.headers);
  const method = String(params.method || 'GET').toUpperCase();
  const bodyType = String(params.body_type || 'json').toLowerCase();
  let body = null;
  if (params.body !== undefined && params.body !== null) {
    if (typeof params.body === 'object') {
      body = JSON.stringify(params.body);
      if (!__toolHeaderValue(headers, 'Content-Type')) { headers['Content-Type'] = 'application/json'; }
    } else {
      body = String(params.body);
      if (bodyType === 'json' && !__toolHeaderValue(headers, 'Content-Type')) {
        headers['Content-Type'] = 'application/json';
      } else if (bodyType === 'form' && !__toolHeaderValue(headers, 'Content-Type')) {
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
      } else if (bodyType === 'xml' && !__toolHeaderValue(headers, 'Content-Type')) {
        headers['Content-Type'] = 'application/xml';
      }
    }
  }
  const payload = { url: String(params.url || ''), method: method, headers: headers };
  if (body !== null && method !== 'GET' && method !== 'HEAD') { payload.body = body; }
  return __dshCallAsync('net.fetch', JSON.stringify(payload)).then(function (raw) {
    const r = __dshParseHost(raw);
    const content = (r.body === undefined || r.body === null) ? '' : String(r.body);
    return {
      url: r.url || payload.url,
      statusCode: r.status || 0,
      statusMessage: '',
      headers: r.headers || {},
      contentType: __toolHeaderValue(r.headers || {}, 'Content-Type'),
      size: content.length,
      content: content
    };
  }, function (err) {
    throw new Error((err && err.message) ? err.message : String(err));
  });
}

function __toolMultipartRequest(params) {
  let formData = params.form_data || params.fields || {};
  if (typeof formData === 'string') {
    try { formData = JSON.parse(formData); } catch (e) { formData = {}; }
  }
  let files = params.files || [];
  if (typeof files === 'string') {
    try { files = JSON.parse(files); } catch (e) { files = []; }
  }
  const normalized = (Array.isArray(files) ? files : []).map(function (file) {
    return {
      field: file.field_name || file.field || 'file',
      path: file.file_path || file.path,
      contentType: file.content_type || file.contentType,
      name: file.name
    };
  });
  return __dshCallAsync('net.upload', JSON.stringify({
    url: String(params.url || ''),
    method: String(params.method || 'POST').toUpperCase(),
    fields: formData || {},
    files: normalized,
    headers: __toolHeaders(params.headers)
  })).then(function (raw) {
    const r = __dshParseHost(raw);
    const body = (r.body === undefined || r.body === null) ? '' : String(r.body);
    return {
      url: r.url || String(params.url || ''),
      statusCode: r.status || 0,
      statusMessage: '',
      headers: {},
      contentType: '',
      size: body.length,
      content: body
    };
  }, function (err) {
    throw new Error((err && err.message) ? err.message : String(err));
  });
}

/** 执行一次 Operit 包体，返回 exports 与 complete() 捕获值。 */
function __loadToolPkg() {
  const exports = {};
  const module = { exports: exports };
  let completed = null;
  const complete = function (value) { completed = value; };
  const Tools = __operitTools();
  const execute = new Function(
    'exports', 'module', 'complete', 'OkHttp', 'Tools', 'console', 'toolCall', 'getEnv',
    __toolPkgSource + '\n//# sourceURL=toolpkg.js'
  );
  execute(exports, module, complete, __okhttp(), Tools, console, __operitToolCall, __envValue);
  return {
    exports: exports,
    moduleExports: module.exports,
    completed: function () { return completed; }
  };
}

export const name = __meta.name || 'toolpkg';
export const inject = [];

/** 插件配置 = METADATA env 生成的 schema；没有 env 的包不产生配置项。 */
export const Config = __envSchema() || undefined;

export function apply(ctx, config) {
  __toolpkgConfig = (config && typeof config === 'object') ? config : {};
  // 诊断工具只在**需要诊断**时注册（M68）：METADATA 解析失败，或这个包有 env 配置
  // （要能查「Key 填没填」）。以前每个 ToolPkg 都注册一个，工具名撞车把 logcat 刷满。
  if (__metaError || (Array.isArray(__envSpec) && __envSpec.length > 0)) {
    ctx.tools.register({
      name: 'toolpkg_status',
      description: '内部诊断：查看 Operit ToolPkg 的 METADATA 解析、包体执行状态与 env 配置。',
      parameters: { type: 'object', properties: {} },
      async execute() {
        let load = '';
        try {
          const pkg = __loadToolPkg();
          load = 'ok keys=' + Object.keys(pkg.exports).join(',');
        } catch (e) {
          load = 'load failed: ' + String(e && e.message || e);
        }
        const spec = Array.isArray(__envSpec) ? __envSpec : [];
        return JSON.stringify({
          metaError: __metaError,
          metaName: __meta.name,
          tools: (__meta.tools || []).map(function (t) { return t && t.name; }),
          load: load,
          env: spec.map(function (item) {
            const value = __envValue(item.name);
            return {
              name: item.name,
              required: item.required === true,
              configured: value !== '',
              valueLength: value.length
            };
          }),
          missingRequiredEnv: __missingEnv(),
          configKeys: Object.keys(__toolpkgConfig),
          sourceLength: __toolPkgSource.length
        }, null, 2);
      }
    });
  }
  const tools = Array.isArray(__meta.tools) ? __meta.tools : [];
  tools.forEach(function (tool) {
    if (!tool || !tool.name) return;
    ctx.tools.register({
      name: tool.name,
      description: __txt(tool.description) || ('ToolPkg ' + tool.name),
      parameters: __schema(tool.parameters),
      async execute(args) {
        const missing = __missingEnv();
        if (missing.length > 0) {
          throw new Error(
            'Operit 包「' + (__meta.name || '') + '」需要先填环境变量：' + missing.join('、') +
            '（在插件详情 → 配置 里填，保存后重试）'
          );
        }
        const pkg = __loadToolPkg();
        const fn = pkg.exports[tool.name]
          || (pkg.moduleExports && pkg.moduleExports[tool.name]);
        if (typeof fn !== 'function') {
          throw new Error('Operit 包没有导出 ' + tool.name);
        }
        const value = await fn(args || {});
        await new Promise(function (resolve) { setTimeout(resolve, 0); });
        const completed = pkg.completed();
        if (completed) {
          if (completed.success === false) {
            return '错误: ' + (completed.message || '执行失败');
          }
          if (completed.data !== undefined && completed.data !== null) {
            return typeof completed.data === 'string' ? completed.data : JSON.stringify(completed.data, null, 2);
          }
          return completed.message || '完成';
        }
        if (typeof value === 'string') return value;
        return JSON.stringify(value, null, 2);
      }
    });
  });
}
""".trimIndent()
    }
}

/**
 * Operit METADATA 方言的容错解析器（无逗号、无引号键）。
 * 只覆盖元数据里会出现的形状：对象 / 数组 / 字符串 / 数字 / true/false/null。
 */
private class MetaParser(private val text: String) {
    private var index = 0

    fun parseObject(): JsonObject? = runCatching {
        val value = value()
        value as? JsonObject
    }.getOrNull()

    private fun value(): JsonElement {
        skip()
        if (index >= text.length) throw IllegalStateException("eof")
        return when (val c = text[index]) {
            '{' -> obj()
            '[' -> arr()
            '"', '\'' -> JsonPrimitive(string(c))
            else -> bareValue()
        }
    }

    private fun obj(): JsonObject {
        index++
        val map = LinkedHashMap<String, JsonElement>()
        while (true) {
            skip()
            if (index >= text.length) break
            if (text[index] == '}') {
                index++
                break
            }
            val key = if (text[index] == '"' || text[index] == '\'') string(text[index]) else bareKey()
            skip()
            if (index < text.length && text[index] == ':') index++
            map[key] = value()
            skip()
            if (index < text.length && text[index] == ',') index++
        }
        return JsonObject(map)
    }

    private fun arr(): JsonArray {
        index++
        val list = ArrayList<JsonElement>()
        while (true) {
            skip()
            if (index >= text.length) break
            if (text[index] == ']') {
                index++
                break
            }
            list.add(value())
            skip()
            if (index < text.length && text[index] == ',') index++
        }
        return JsonArray(list)
    }

    private fun string(quote: Char): String {
        index++
        val sb = StringBuilder()
        while (index < text.length) {
            val c = text[index++]
            when {
                c == quote -> return sb.toString()
                c == '\\' && index < text.length -> {
                    val n = text[index++]
                    sb.append(
                        when (n) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            else -> n
                        },
                    )
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun bareValue(): JsonElement {
        val start = index
        while (index < text.length && text[index] !in " \t\r\n,}]") index++
        val token = text.substring(start, index).trim()
        val number = token.toDoubleOrNull()
        return when {
            token == "true" -> JsonPrimitive(true)
            token == "false" -> JsonPrimitive(false)
            token == "null" -> JsonPrimitive(null as String?)
            number != null -> JsonPrimitive(number)
            else -> JsonPrimitive(token)
        }
    }

    private fun bareKey(): String {
        skip()
        val start = index
        while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_' || text[index] == '-')) index++
        return text.substring(start, index)
    }

    private fun skip() {
        while (index < text.length && text[index].isWhitespace()) index++
    }
}
