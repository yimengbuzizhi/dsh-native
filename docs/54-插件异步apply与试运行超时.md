# 54 · 插件异步 apply 与试运行超时（M31）

日期：2026-11-08 02:28–02:31（第三十棒续）

> 前情：`docs/50`/`52`/`53` 都把「apply 返回 Promise 时不等」列为边界；这一轮补上。
> 同时给试运行加了 12 秒看门狗：插件 `while(true)` 或 Promise 永远不结算，不会再把导入页焊死。

## 一、问题

DSH 插件的入口是 `apply(ctx, config)`，契约上允许返回 Promise：

```js
export const apply = (ctx, config) => Promise.resolve().then(() => {
  ctx.tools.register(defineTool({ name: 'x', … }))
})
```

之前 `EXPORTS_APPLY` 只做同步调用：

```js
const ret = __e.apply(__ctx, config)
if (ret && typeof ret.then === 'function') {
  __dsh.errors.push('apply 返回了 Promise（异步注册）；试运行只等同步注册')
}
```

后果：

- 工具在 Promise 的 `.then` 里注册 → 试运行时 `tools=[]`，插件页显示「没有注册工具」；
- 运行时 `invokeScript` 立刻查 `__dshDefs` → 「插件没有注册工具 x」；
- 试运行没有超时，`apply` 里死循环/永不结算的 Promise 会让导入一直转圈。

## 二、实现

### 1. `__dshApply`：统一等 apply 结算

`EXPORTS_APPLY` 新增：

```js
let __dshApply = null;
const ret = __e.apply(__ctx, __dshConfigRaw);
if (ret && typeof ret.then === 'function') {
  __dshApply = Promise.resolve(ret).catch(err => { __dsh.errors.push(...) });
}
```

- 同步 apply：`__dshApply` 仍是 null；
- 异步 apply：保存 Promise；reject 也转成 `__dsh.errors`，不让整个脚本变成未处理拒绝；
- 试运行结尾：`return Promise.resolve(__dshApply).then(() => JSON.stringify(__dsh))`；
- 工具调用：`return Promise.resolve(__dshApply).then(__dshInvoke)`，`__dshInvoke` 里才查
  `__dshDefs`、执行 `execute`。这样「注册完成」和「执行」严格有先后。

QuickJS 桥本来就等根 Promise（`invokeScript` 之前就返回 Promise），所以不需要改 JNI。

### 2. 试运行 12 秒看门狗

`PluginStore.dryRun` 在 `js.evaluate` 前起一个 daemon 线程，到点 `js.interrupt()`；
中断后状态是 `INTERRUPTED`，插件记录里写：

```
error: 试运行超时（>12 秒）或已被中断
```

这同时挡 `while(true)` 和「Promise 永不结算」两种情况。运行时工具调用本来就有
`ToolContext.timeoutMs` 的看门狗，这次只是把试运行补齐。

### 3. 顺手加的截图入口

解锁后要拍插件详情（配置区），一张张点卡片太慢。新增 debug 参数：

```
--es dsh_plugin_detail <插件id>    # 启动即落到插件页并打开该插件详情（只 debug 包）
```

实现：`MainActivity` 存 prefs → `AppViewModel.debugPluginDetailId` → `PluginsScreen`
的 `LaunchedEffect` 在列表就绪后 `detailId = target`。正常用户路径不受影响。
锁屏下已试启动：无 Compose 崩溃（Activity 被 keyguard 暂停，截图仍要等解锁）。

## 三、证据

### 1. 单测

`:core:plugins:test` **61 条全过**（新增/更新的脚本断言：试运行结尾是
`Promise.resolve(__dshApply).then(...)`、调用脚本是 `Promise.resolve(__dshApply).then(__dshInvoke)`）。
`:core:tools:test`、`:core:engine:test`、`:core:prompt:test`、`:core:parity:test` 全过。

### 2. 真机（锁屏，纯 adb）

**a. async apply 插件能注册工具**

fixture `dsh-plugin-asyncapply.zip`（源码 `plugin-src/asyncapply/`）：

```js
export const apply = (ctx, config) => Promise.resolve().then(() => {
  ctx.tools.register(defineTool({
    name: 'async_echo',
    parameters: z.object({ text: z.string().default('x') }),
    execute: async (args) => ({ text: JSON.stringify({ tag: config.tag, args }) }),
  }))
})
```

导入日志：

```
I/DshPlugin: debug import ok=true 已导入 dsh-plugin-asyncapply 0.1.0；注册 1 个工具：async_echo
I/DshPlugin: debug import injected=[async_echo]
```

**b. async apply 注册的工具能执行（execute 也是 async）**

```
debug call name=async_echo args={} isError=false
text={"tag":"async","args":{"text":"x"}}
```

**c. 永不结算的 apply 12 秒超时**

fixture `dsh-plugin-hang.zip`：`export const apply = () => new Promise(() => {})`。
导入耗时约 12.1 秒，返回：

```
I/DshPlugin: debug import ok=true 已导入 dsh-plugin-hang 0.1.0；试运行报错（见详情）
I/DshPlugin: debug import injected=[]
```

`plugins.json`：

```json
"error":"试运行超时（>12 秒）或已被中断"
```

**d. 同步插件回归**

`zconfig_echo`（M30 的 fixture）仍然：

```
text={"config":{"city":"Shanghai","verbose":false,"limit":3,"mode":"safe","nested":{"flag":true}},
      "args":{"text":"hi","opts":{"n":7}}}
```

### 3. 构建 / 装机

- `:app:assembleDebug` BUILD SUCCESSFUL；APK **98,124,413 B**，MD5 `16672920ECBD9F4797FA3B2293E141D6`；
  已装机（02:31，覆盖安装）。
- 安装包：`D:\dsh\.dsh\handoff\DSH-native-20261108-0231.apk`。
- **02:31:32 已发 QQ 群 11316535**：群文件显示 `DSH-native-202...31.apk (93.58MB)`、**上传成功**；
  02:32 发了说明：Config 落盘/配置区（等解锁补截图）、参数默认值与校验、async apply、试运行 12s 超时、
  这版界面只多配置区、覆盖安装。
- 包发出后仓库又合入两个小 UI 修正（工作区管理页路径缩写、配置弹层滚动/限宽），02:34 本地构建
  MD5 `C432A28689594E2D71C9B29B622A0293` 已装机；**已在 02:57 的 `0256` 包补发**（见 `docs/58`）。

## 四、边界

- **试运行不执行真正的异步宿主能力**：试运行的 `JsHost.callAsync` 是空实现（不连 `PluginSandbox`），
  所以 async apply 里 `await fetch(...)` 这类会一直 pending 到 12 秒超时。插件初始化要访问网络的，
  本来也不该在试运行阶段做。
- 试运行超时固定 12 秒，不给插件配置；以后有慢插件再加。
- `apply` 的 Promise 只等一次；`apply` 里再 `ctx.plugin(...)` 注册的子插件仍然走宽松代理。
- 每次工具调用仍然重跑模块 + apply（没有常驻 QuickJS），与 docs/50 相同；这次只是把「等 apply」做对了。

## 五、下一棒

1. **解锁后第一件事：UI 截图**（M30 的插件配置区 + M29 的插件卡片/详情 + 设置页回归）。
   可用 `--es dsh_plugin_detail dsh-plugin-zconfig` 直接开详情。
2. 完整对话验证：让模型调 `zconfig_echo` / `async_echo`，录 run.jsonl，补
   `llm.request → tool.call → tool_result → 最终回复`。→ `zconfig_echo` **02:53**、
   `async_echo` **02:54** 都已完成，见 `docs/58`。
3. 配置值严格校验（M30 已做键名 + 类型/必填的最小校验；还差数字范围/enum 的 UI 提示）。
4. manifest `config.schema` / `config.default` 接入。→ **M32 完成**，见 `docs/56`。
5. 项目/工作区页对齐 Operit 2、图标/菜单统一；界面美化等真实截图和用户意见。
6. 群反馈先复现再改；不照单全收；不泄露令牌/密钥/内部路径。
