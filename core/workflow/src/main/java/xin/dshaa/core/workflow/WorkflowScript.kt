package xin.dshaa.core.workflow

/**
 * 生成 workflow 脚本的 JS 外壳（逐条对齐上游 `dsh-workflow-ptc` 的 `WorkflowExecution`）：
 *
 * - 脚本体放进 `(async () => { ... })()`，因此支持顶层 `await` 与 `return`；
 * - 全局 `args` 是工具入参的 JSON（脚本里再声明 `const args` 会遮蔽它，与上游 vm context 一致）；
 * - `agent/parallel/pipeline/phase/log` 的错误文案、限额、fatal 传播规则照上游；
 * - 宿主 hook 走 `__dshCallAsync`（Promise）与 `__dshCallSync`（phase/log）。
 *
 * 这里不抽模板引擎：外壳是固定字符串，只做 `@ARGS@` / `@BODY@` 两处替换。
 */
object WorkflowScript {

    fun build(body: String, argsJson: String?, limits: WorkflowLimits): String {
        val argsLiteral = argsJson?.takeIf { it.isNotBlank() } ?: "null"
        return PRELUDE
            .replace("@ARGS@", argsLiteral)
            .replace("@MAX_TOTAL@", limits.maxTotalAgents.toString())
            .replace("@MAX_ITEMS@", limits.maxItemsPerCall.toString())
            .replace("@BODY@", body)
    }

    private val PRELUDE = """
"use strict";
class WorkflowError extends Error {
  constructor(message, code) {
    super(message);
    this.name = "WorkflowError";
    this.code = code;
    this.fatal = true;
  }
}
const __renderThrown = (e) => {
  try {
    const stack = e && e.stack;
    if (typeof stack === "string" && stack.length > 0) return stack;
    const message = e && e.message;
    if (typeof message === "string" && message.length > 0) return message;
    return String(e);
  } catch (_) {
    return "[unrenderable thrown value]";
  }
};
const __fatal = (e) => e instanceof WorkflowError && e.fatal;
const __fail = (message, code) => { throw new WorkflowError(message, code); };
const __defaultLabel = (prompt) => {
  const newline = prompt.indexOf("\n");
  const line = newline === -1 ? prompt : prompt.slice(0, newline);
  return line.length <= 48 ? line : line.slice(0, 47) + "…";
};
const __limits = { maxTotalAgents: @MAX_TOTAL@, maxItemsPerCall: @MAX_ITEMS@ };
const __supportedOptions = "label, phase, schema, provider, model";
const __deferredOptions = ["effort", "isolation", "agentType"];
let __started = 0;
let __currentPhase = null;
const __readAgentOptions = (rawOpts) => {
  if (rawOpts === undefined) return {};
  if (rawOpts === null || typeof rawOpts !== "object" || Array.isArray(rawOpts)) {
    __fail("agent() options must be an object", "INVALID_ARGUMENT");
  }
  const out = {};
  for (const key of Object.keys(rawOpts)) {
    if (key === "label" || key === "phase" || key === "schema" || key === "provider" || key === "model") {
      out[key] = rawOpts[key];
      continue;
    }
    if (__deferredOptions.indexOf(key) !== -1) {
      __fail('agent() option "' + key + '" is deferred and not supported by this engine (supported: ' + __supportedOptions + ')', "UNSUPPORTED_OPTION");
    }
    __fail('agent() option "' + key + '" is not recognized (supported: ' + __supportedOptions + ')', "UNSUPPORTED_OPTION");
  }
  for (const key of ["label", "phase", "provider", "model"]) {
    if (out[key] !== undefined && typeof out[key] !== "string") {
      __fail('agent() option "' + key + '" must be a string', "INVALID_ARGUMENT");
    }
  }
  return out;
};
const agent = async (rawPrompt, rawOpts) => {
  if (typeof rawPrompt !== "string" || rawPrompt.length === 0) {
    __fail("agent() requires a non-empty prompt string", "INVALID_ARGUMENT");
  }
  const options = __readAgentOptions(rawOpts);
  if (__started >= __limits.maxTotalAgents) {
    __fail("this run reached its total agent cap (" + __limits.maxTotalAgents + ") — a runaway-loop backstop; raise the applicable maxTotalAgents limit if the scale is intentional", "AGENT_CAP");
  }
  __started += 1;
  const seq = __started;
  const label = options.label !== undefined ? options.label : __defaultLabel(rawPrompt);
  const phase = options.phase !== undefined ? options.phase : __currentPhase;
  const payload = { seq: seq, prompt: rawPrompt, label: label };
  if (phase !== undefined && phase !== null) payload.phase = phase;
  if (options.schema !== undefined) payload.schema = options.schema;
  if (options.provider !== undefined) payload.provider = options.provider;
  if (options.model !== undefined) payload.model = options.model;
  let result;
  try {
    result = await __dshCallAsync("agent", JSON.stringify(payload));
  } catch (error) {
    if (error instanceof WorkflowError) throw error;
    const code = error && error.code ? error.code : "AGENT_START";
    const message = error && error.message ? error.message : __renderThrown(error);
    if (code === "UNSUPPORTED_SCHEMA" || code === "INVALID_ARGUMENT") __fail(message, code);
    __fail("agent() could not start a child: " + message, code);
  }
  return result === undefined ? null : result;
};
const __assertItemCap = (length, hook) => {
  if (length > __limits.maxItemsPerCall) {
    __fail(hook + " received " + length + " items — over the per-call cap (" + __limits.maxItemsPerCall + "); split the work or raise maxItemsPerCall in the engine config", "ITEM_CAP");
  }
};
const parallel = async (rawThunks) => {
  if (!Array.isArray(rawThunks)) {
    __fail("parallel() requires an array of zero-argument functions", "INVALID_ARGUMENT");
  }
  __assertItemCap(rawThunks.length, "parallel()");
  const thunks = rawThunks.map((thunk, index) => {
    if (typeof thunk !== "function") {
      __fail("parallel() item " + index + " is not a function", "INVALID_ARGUMENT");
    }
    return thunk;
  });
  return Promise.all(thunks.map(async (thunk) => {
    try {
      return await thunk();
    } catch (error) {
      if (__fatal(error)) throw error;
      return null;
    }
  }));
};
const pipeline = async (rawItems, ...rawStages) => {
  if (!Array.isArray(rawItems)) {
    __fail("pipeline() requires an items array", "INVALID_ARGUMENT");
  }
  __assertItemCap(rawItems.length, "pipeline()");
  if (rawStages.length === 0) {
    __fail("pipeline() requires at least one stage function", "INVALID_ARGUMENT");
  }
  const stages = rawStages.map((stage, index) => {
    if (typeof stage !== "function") {
      __fail("pipeline() stage " + index + " is not a function", "INVALID_ARGUMENT");
    }
    return stage;
  });
  return Promise.all(rawItems.map(async (item, index) => {
    let value = item;
    try {
      for (const stage of stages) {
        value = await stage(value, item, index);
      }
      return value;
    } catch (error) {
      if (__fatal(error)) throw error;
      return null;
    }
  }));
};
const phase = (title) => {
  if (typeof title !== "string" || title.length === 0) {
    __fail("phase() requires a non-empty title string", "INVALID_ARGUMENT");
  }
  __currentPhase = title;
  __dshCallSync("phase", JSON.stringify({ title: title }));
};
const log = (message) => {
  if (typeof message !== "string") {
    __fail("log() requires a message string", "INVALID_ARGUMENT");
  }
  __dshCallSync("log", JSON.stringify({ message: message }));
};
const args = @ARGS@;
(async () => {
@BODY@
})()
"""
}
