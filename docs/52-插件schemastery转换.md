# 52 · 插件 schemastery 参数转换（M29）

日期：2026-11-08 01:55–02:10（第二十九棒）

> 前情：docs/50 §六.3 把「schemastery 转换」列为下一步；docs/51 把插件能力门做完。
> 这一轮补上「插件用 `z.object(...)` 写的参数也能接进对话」。设备整晚锁屏，本轮仍然没有 UI 截图。

## 一、这一轮的目标

docs/50 里，插件工具参数有三种写法：

| 写法 | 例子 | 之前 |
|---|---|---|
| 标准 JSON Schema | `{type:'object', properties:{…}}` | 原样接入 |
| DSH / ToolPkg 简写 | `{ todos: { type:'array', required:true } }` | 归一后接入 |
| **schemastery** | `parameters: z.object({ text: z.string() })` | 认不出，**不接入** |

这一轮把第三种接上，并且**宁可不接，也不能给模型一个假参数定义**：能一一对应的类型才翻译；
`tuple` / `transform` / `date` 这类没有 JSON Schema 对应物的，整条放弃（插件页仍显示「参数认不出，未接入」）。

## 二、为什么不能直接 `JSON.stringify` schemastery 的 schema

schemastery 的 schema 是**函数对象**（可调用、可链式），`JSON.stringify` 拿不到类型。
上游的做法是 `toJSON()` 把它序列化成 **uid + refs 的图**（`lib/index.mjs` 3.18.2）：

```json
{"uid":18,"refs":{
  "0":{"type":"const","value":"add","meta":{}},
  "3":{"type":"union","meta":{"required":true},"list":[0,1]},
  "6":{"type":"string","meta":{"description":"todo text","required":true}},
  "18":{"type":"object","meta":{"default":{}},"dict":{"action":3,"text":6}}}}
```

所以要做两件事：
1. **JS 侧**：插件 `import z from '@deepseek-ai/schemastery'` 时宿主给出的 `z` 必须真的能建 schema、能按上游格式序列化；
2. **Kotlin 侧**：把 `{uid, refs}` 图翻译成模型 API 认的 JSON Schema。

## 三、实现

### 1. 宿主最小 schemastery（`PluginModule.PRELUDE`）

之前 `__z` 是一个「什么都返回自己」的 Proxy——`z.object(...)` 拿到的还是 Proxy，序列化不出来，
所以只能标 `unsupported`。这一轮把它换成**照上游抄的最小实现**（约 200 行 JS）：

- 字段名照抄：`type` / `dict` / `list` / `inner` / `sKey` / `meta`；
- `toJSON()` 照抄：uid 分配、`__schemastery_refs__` 的收集与返回 `{uid, refs}`、嵌套 schema 返回 uid；
- 链式方法照抄：`required/description/default/min/max/step/role/pattern/extra/deprecated/experimental/set/push/i18n`；
- 构造器：`object/dict/array/tuple/union/intersect/const/any/never/string/number/boolean/bitset/function/date/is/lazy/transform/natural/percent`；
- **未知静态成员**（`z.someCustom()`）返回 `any` schema：老实现是宽松代理，插件里少见的构造器不该让整个模块加载失败；
- 校验器不实现：试运行和参数注入都不需要 `Schema.resolve`，只做一个宽松的默认值填充（`new Config()` 这种调用不会炸）。

**怎么保证抄对了**：在 node 里拿真实 `@deepseek-ai/schemastery@3.18.2` 和我们的实现跑同一组 schema
（object + required + union-of-const + natural + array + nested object + dict + 非 const union + lazy），
把两边 `toJSON()` 的图去掉 uid 数值后逐字段比较：**8/8 ALL MATCH**（脚本在
`<workspace>\.dsh\handoff\zaschema-oracle.mjs`，不进仓库）。

### 2. Kotlin 转换 `SchemasterySchema.kt`

| schemastery | JSON Schema |
|---|---|
| `object` | `{type:object, properties:{…}, required:[…]}` |
| `dict` | `{type:object, additionalProperties:{…}}` |
| `array` | `{type:array, items:{…}}` |
| `string` | `{type:string}` + `minLength/maxLength`（meta.min/max） |
| `number` | `{type:number}` + `minimum/maximum` |
| `boolean` | `{type:boolean}` |
| `const(v)` | `{enum:[v]}` |
| `union` 全是 const | `{enum:[…]}` |
| `union` 其他 | `{anyOf:[…]}` |
| `intersect` 全是 object | 合并 properties/required |
| `any` | `{}`（布尔 schema） |
| `lazy` | 翻内层 |
| `tuple` / `transform` / `is` / `date` / `bitset` / `function` | **null（不注入）** |

规矩：
- `meta.description` 带上（i18n 的 `{"":"…","zh":"…"}` 取第一条）；
- **不写 `default`**：宿主目前不套默认值，写进参数 schema 就是骗模型（边界见 §五）；
- `object` 不写 `additionalProperties:false`：schemastery 默认会把未知键合并回来，写 false 反而更严；
- 深度上限 12（lazy 可以自引用），根必须是 object，否则整条 null。

### 3. 接入点 `PluginSchema.normalize`

顺序变成：schemastery 图（`{uid,refs}`）→ JSON Schema → 简写包装 → 认不出返回 null。
试运行/运行时都走这一个入口，`files/plugins.json` 里存的是**转换后的** JSON Schema。

### 4. 顺带修的真机 bug：`__permissive` 解构后链式断掉

把真实 `@deepseek-ai/dsh-tool-todo` 包塞进设备验证时，试运行报错只有一行栈、没有 message。
用 node 复现（把 prelude + 真实 `lib/index.js` 拼起来跑）拿到真正原因：

```
TypeError: z$1.string is not a function
```

`import { z as z$1 } from 'zod'` 解构时，`__permissive` 的 `get` 返回的是**裸 target**，
拿到手就不再是代理；`z$1.object({...})` 之后所有链式调用全部断开。
修复：`get` / `apply` / `construct` 一律返回**代理自身**（`self`），宽松代理可以一路链下去。
修完真实 todo 包试运行通过（`applied:true`、注册 `todo_write`、0 错误）。

## 四、证据

### 1. 单测

`:core:plugins:test` **56 条全过**（原 46 + SchemasterySchemaTest 8 + PluginSchemaTest 2）。
`SchemasterySchemaTest` 用的输入全部是**真实 schemastery 3.18.2 `toJSON()` 的输出**（不是手捏的格式），
覆盖：object/required/enum/description/array/dict、非 const union 的 anyOf、lazy、unsupported（tuple/date/transform）、
自引用图（返回 null 不炸栈）、根不是 object、i18n 描述。
`:core:tools:test`、`:core:engine:test`、`:core:prompt:test`、`:core:parity:test` 全过。

### 2. 真机（锁屏，纯 adb；每次 `am start` 前先 `force-stop`）

新增 fixture：`dsh-plugin-zfull.zip`（object/union-const/string/array/nested，带中文 description）、
`dsh-plugin-zunsupported.zip`（tuple 参数，反例）；另外用真实 npm 包 `@deepseek-ai/dsh-tool-todo@0.2.1-rc.2` 打了
`dsh-tool-todo-real.zip`（`package.json` + `lib/index.js`，代码未改）。

**a. zfull 导入后真的接进工具表**

```
I/DshPlugin: debug import ok=true 已导入 dsh-plugin-zfull 0.1.0；注册 1 个工具：zfull_run
I/DshPlugin: debug import injected=[zfull_run]
```

`files/plugins.json` 里落的 schema（原文）：

```json
{"type":"object","properties":{
  "action":{"enum":["add","list"],"description":"要做什么"},
  "text":{"type":"string","description":"条目文本"},
  "count":{"type":"number","minimum":0},
  "tags":{"type":"array","items":{"type":"string"}},
  "nested":{"type":"object","properties":{"flag":{"type":"boolean"}},"required":["flag"]}},
 "required":["action","text"]}
```

**b. 直接调用（不经模型）**

```
I/DshPlugin: debug call name=zfull_run args={"action":"add","text":"牛奶","tags":["a"],"nested":{"flag":true}}
             isError=false text=Z {"action":"add","text":"牛奶","tags":["a"],"nested":{"flag":true}}
```

**c. docs/50 里那个「一定不注入」的 `dsh-tool-zschema` 现在注入了**

```
I/DshPlugin: debug import ok=true 已导入 dsh-tool-zschema 0.1.0；注册 1 个工具：plugin_z
I/DshPlugin: debug import injected=[plugin_z]
列表里：plugin_z -> {"type":"object","properties":{"text":{"type":"string"}}}
```

**d. 反例仍然不注入**

```
I/DshPlugin: debug import ok=true 已导入 dsh-plugin-zunsupported 0.1.0；注册 1 个工具：zunsupported_run
I/DshPlugin: debug import injected=[]
（plugins.json 里这个工具没有 toolSchemas 条目）
```

**e. 真实 dsh-tool-todo 包**

```
W/DshPlugin: 工具名冲突，未注入：todo_write（@deepseek-ai/dsh-tool-todo）
I/DshPlugin: debug import ok=true 已导入 @deepseek-ai/dsh-tool-todo 0.2.1-rc.2；注册 1 个工具：todo_write
I/DshPlugin: debug import injected=[]
```

注册的 `todo_write` 用的是 DSH 简写，归一化正确（原文，节选）：
`{"todos":{"type":"array","description":"The COMPLETE task list…","items":{"type":"object","additionalProperties":false,"properties":{"content":{"type":"string","required":true,…},"status":{"type":"string","enum":["pending","in_progress","completed"],…}}}}}, "required":["todos"]`。
冲突是**预期**的：App 自己已经有内置 `todo_write`，`ToolRegistry` 不许插件覆盖内置工具。

**f. 模型请求体里确实带着转换后的 schema（最强证据）**

设备上 `files/run1/run.jsonl` 记到了这一轮的 `llm.request`：`tools` 共 **46** 个，其中

```json
{"name":"zfull_run","description":"schemastery parameters: object / union-of-const / string / array / nested object.",
 "input_schema":{"type":"object","properties":{
   "action":{"enum":["add","list"],"description":"要做什么"},
   "text":{"type":"string","description":"条目文本"},
   "count":{"type":"number","minimum":0},
   "tags":{"type":"array","items":{"type":"string"}},
   "nested":{"type":"object","properties":{"flag":{"type":"boolean"}},"required":["flag"]}},
  "required":["action","text"]}}
```

`plugin_z` 也在表里。**注意**：这一轮那次模型补全被锁屏/Doze 卡住（`turn start` 之后没有 `turn end`，
SSE 只到 `message_start`），所以没有「模型调用插件 → 最终回复」的完整对话证据；
工具表证据来自落盘的请求体，工具执行证据来自 b。下轮解锁后可以补一次完整对话（大概率同 docs/50 §三.4）。

### 3. 清理

设备上的测试插件、`plugins.json`、临时 zip、`run1`、`m29run` 全部删除，插件页回到空态；
fixture 留在 `<workspace>\.dsh\handoff\plugins-fixtures\`：
`dsh-plugin-zfull.zip`、`dsh-plugin-zunsupported.zip`、`dsh-tool-todo-real.zip`（旧的一批也在）。

### 4. 构建 / 发包

- `:app:assembleDebug` BUILD SUCCESSFUL，APK **98,124,413 B**，MD5 `8CAB1BF3ED323718961ED728348EB4EC`；
  已装机（02:06）。dex 里确认含 `__zsProto` / `SchemasterySchema` / `additionalProperties`。
- 安装包：`<workspace>\.dsh\handoff\DSH-native-20261108-0212.apk`。
- **02:11:42 已发 QQ 群 11316535**：群文件显示 `DSH-native-202...12.apk (93.58MB)`、**上传成功**；
  02:12 发了说明：插件参数支持 schemastery、修 zod 解构链、真实 todo 包可导入（同名内置工具不覆盖）、
  这版没改界面、覆盖安装、界面美化欢迎截图具体意见（先复现再改）。

## 五、边界（别在群里夸口）

- **不是 schemastery 的完整实现**：只有「建 schema + 按上游格式序列化」+ 宽松默认值填充，没有校验器；
  插件如果依赖 `Schema.resolve` 的报错文本/严格类型，行为会不同。
- **不套默认值**：`z.number().default(1)` 转出来的 schema 不写 default，插件 `execute` 拿到的就是模型给的参数
  （`undefined`）。~~等宿主做「按 schema 填默认值」那一轮再补。~~ **M30 已补**：见 `docs/53`（显式 default
  写进 schema，宿主执行前补默认值；隐式空对象/空数组不写）。
- **不支持的类型整条不注入**：tuple / transform / is / date / bitset / function；
  不是「转了但转错」，是「干脆不接」。
- **配置 schema（`Config`）还不落盘**：~~现在只是不炸；插件配置 UI / 配置校验还没做。~~
  **M30 已补**：Config 图落盘成 JSON Schema、默认值/用户值合并、插件详情可编辑（`docs/53`）。
- **`apply` 异步仍未等**、**每次调用重跑模块**：与 docs/50 相同，没变。~~M31 已补异步 apply~~，见 `docs/54`。
- `__permissive` 修成「返回代理自身」后，链式调用不会断；但它依然不是真的实现，
  插件真依赖 zod 的校验/解析逻辑时结果不可靠（跑得通 ≠ 跑得对）。

## 六、下一棒

1. **UI 截图**（用户解锁后第一件事）：插件页空态 → 导入 `dsh-plugin-fs.zip` → 卡片「权限 1」→ 详情；
   `dsh-plugin-net.zip` 的「确认启用」红框；`dsh-plugin-zfull.zip` 看「已接入 1」；再回归设置页思考强度、输入条。
2. **完整对话验证**（解锁、网络正常时）：发一句「调用 zfull_run」录 run.jsonl，补上 docs/50 §三.4 那种链路证据。
3. 插件配置：`Config` schema 落盘 + 插件详情里的配置项（schemastery 图已经能拿到）。→ **M30 完成**，见 `docs/53`
4. 宿主按 JSON Schema 给插件工具参数填默认值（和「工具参数校验」一起做）。→ **M30 完成**，见 `docs/53`
5. 插件运行时常驻化（初始化副作用只跑一次）。
6. 项目/工作区页对齐 Operit 2、图标/菜单统一；界面美化等真实截图和用户意见。
7. 群反馈先复现再改；不照单全收；不泄露令牌/密钥/内部路径。

## 七、命令（下一棒直接抄）

```powershell
# 构建 / 测试
cd <workspace>\native
cmd /c "set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot&& .tools\gradle-8.9\bin\gradle.bat :app:assembleDebug --console=plain > build-debug.log 2>&1"
.tools\gradle-8.9\bin\gradle.bat :core:plugins:test :core:tools:test :core:engine:test :core:prompt:test :core:parity:test

# 装机
C:\Android\platform-tools\adb.exe install --no-streaming -r -t app\build\outputs\apk\debug\app-debug.apk

# 导入 zfull（锁屏可用；每次 am start 前先 force-stop）
adb push <workspace>\.dsh\handoff\plugins-fixtures\dsh-plugin-zfull.zip /data/local/tmp/zfull.zip
adb shell "run-as xin.dshaa.nativeapp cp /data/local/tmp/zfull.zip files/zfull.zip"
adb shell am force-stop xin.dshaa.nativeapp
adb shell "am start -n xin.dshaa.nativeapp/.MainActivity --es dsh_plugin_zip /data/user/0/xin.dshaa.nativeapp/files/zfull.zip"
adb logcat -d -v time | Select-String DshPlugin
adb shell "run-as xin.dshaa.nativeapp cat files/plugins.json"

# 直接调插件工具（参数 base64，避开 adb shell 吃引号）
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('{"action":"add","text":"hello"}'))
adb shell "am start -n xin.dshaa.nativeapp/.MainActivity --es dsh_plugin_call zfull_run --es dsh_plugin_args_b64 $b64"

# 本地对照（需要 node）：真实 schemastery vs 宿主最小实现
node <workspace>\.dsh\handoff\zaschema-oracle.mjs
node <workspace>\.dsh\handoff\m29-todo-check.mjs   # 真实 dsh-tool-todo 的试运行
```
