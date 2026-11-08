# 56 · 清单里的配置 schema/default（ToolPkg 风格，M32）

日期：2026-11-08 02:37–02:39（第三十棒续）

> 前情：M30 只认插件 `export const Config = z.object(...)`；`docs/04 §6.1` 里 ToolPkg/Operit
> 的 `manifest.json` 还允许把配置写在 `config: { schema, default }`。这一轮把这条路接上。

## 一、清单格式

```json
{
  "id": "com.example.manifestcfg",
  "name": "Manifest Config",
  "main": "index.js",
  "config": {
    "schema": { "type": "object", "properties": {
      "city":  { "type": "string", "default": "Shanghai" },
      "limit": { "type": "number", "default": 3 }
    }},
    "default": { "city": "Beijing" }
  }
}
```

- `config.schema`：JSON Schema（也容忍把 schema 直接写在 `config` 上）；
- `config.default`：清单默认值，**覆盖 schema 里的同名 default**；
- 插件同时导出 `Config` 时，**导出优先**；manifest 的 `config.default` 仍然作为基础默认值合并。

## 二、实现

### 1. `PluginManifest` 多两个字段

```kotlin
val configSchema: JsonObject? = null,
val configDefaults: JsonObject? = null,
```

`PluginPackage.parse` 从 `manifest.json` 读；`package.json` 里没有这两个键就是 null（DSH 包不受影响）。

### 2. 默认值的合并顺序

`PluginSchema.effectiveText(schema, userConfig, defaultsText = null)`：

```
manifest config.default  →  用户配置（对象深合并）  →  schema 里剩余的 default
```

- 深合并：用户只改 `nested.flag` 时，manifest 的 `nested` 其他键保留；
- `config.default` 覆盖 schema 的 scalar default（上例 city：schema=Shanghai，manifest=Beijing）；
- 没给 `defaultsText` 时行为和 M30 完全一样。

`PluginRecord.configDefaults` 现在语义是「基础默认值」（可能来自 manifest），
运行时、插件详情、保存校验都用 `effectiveText(schema, config, configDefaults)`。

### 3. 安装 / 重载

- 安装：`Config` 导出 → 用导出；没有 → 用 manifest schema；两遍试运行的 second pass 也带 manifest 默认值，
  这样 `apply` 在安装阶段就能看到真实配置；
- 重载：插件不一定还导出 Config（ToolPkg 的 schema 在 manifest 里），所以 `run.configSchema ?: record.configSchema`，
  默认值沿用 `record.configDefaults` 再补新 schema 的默认项。

### 4. 顺手补的范围校验

`schema.validate` 原来只查类型/必填/enum。这一轮补上：

- number/integer：`minimum` / `maximum`；
- string：`minLength` / `maxLength`；
- 配置保存时先合并默认值再校验，所以「必填但 default 有值」不会被误拒；
- 配置编辑弹层的数字输入会显示「范围：1 – 10」，并在保存前先挡一次（宿主 validate 仍是最后一道）。

## 三、证据

### 1. 单测

`:core:plugins:test` **64 条全过**（原 61 + manifest 配置解析 1 + 默认值深合并 1 + 范围/长度 1）。
新增覆盖：`PluginManifest.configSchema/configDefaults` 解析、registry 里 config 三字段 round-trip、
manifest 默认值 vs schema 默认值 vs 用户配置的优先级、minimum/maximum/minLength/maxLength。

`:core:tools:test`、`:core:engine:test`、`:core:prompt:test`、`:core:parity:test` 全过。

### 2. 真机（锁屏，纯 adb）

fixture：`dsh-plugin-manifestcfg.zip`（`manifest.json` + `index.js`，源码 `plugin-src/manifestcfg/`）。

**a. 导入后 manifest schema/default 落盘**

```
I/DshPlugin: debug import ok=true 已导入 Manifest Config 0.1.0；注册 1 个工具：manifestcfg_echo
"configSchema":"{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"default\":\"Shanghai\"},
  \"limit\":{\"type\":\"number\",\"default\":3}}}",
"configDefaults":"{\"city\":\"Beijing\",\"limit\":3}"
```

manifest 的 `city=Beijing` 覆盖了 schema 的 `Shanghai`。

**b. apply 收到基础默认值（工具是普通 DSH 简写参数）**

```
debug call name=manifestcfg_echo args={} isError=false
text={"config":{"city":"Beijing","limit":3},"args":{"text":"hi"}}
```

**c. 用户只改 limit，city 保留 manifest 默认**

```
dsh_plugin_config com.example.manifestcfg {"limit":9}
debug call text={"config":{"city":"Beijing","limit":9},"args":{"text":"hi"}}
```

**d. 范围校验（补的范围校验）**

```
dsh_plugin_config dsh-plugin-zconfig {"limit":99}
debug config target=dsh-plugin-zconfig 配置不合法：limit 不能大于 10
```

### 3. 构建 / 装机

- `:app:assembleDebug` BUILD SUCCESSFUL；APK **98,124,413 B**，MD5 `B83D6FDABA4BE5F82386B918F5927804`；
  已装机（02:38，覆盖安装）。
- 包后补了范围/长度校验 + 配置弹层范围提示，02:40 构建 MD5 `C069AAA36ACDC995077C4116A08BD928` 已装机；
  **没有重发群**，下个包会带上。
- 02:57 的 `0256` 包已带上本轮的 manifest 配置 + 范围校验（`docs/58`）；配置区截图仍等解锁。

## 四、边界

- **schema 只在安装时从 manifest 读**；之后改 manifest 需要「重新加载」或重装。重载会沿用旧 schema
  （因为 reload 只读入口 JS），manifest 改 schema 要重装。
  → **M37 已补**：`reload` 每次重读 `manifest.json`/`package.json`，schema/默认值/权限跟着变
  （见 `docs/61`）；本节保留为 M32 当时的行为记录。
- manifest 的 `config.default` 只做 JSON 深合并，不做 schema 校验；非法默认值会原样传给插件。
- 配置编辑 UI / 保存校验与 M30 相同；manifest 配置没有单独的「来源」标识。
- 插件 `package.json`（DSH）里的 `config` 字段没有接；只接了 `manifest.json`。

## 五、下一棒

1. **解锁后截图**（最高优先）：插件页空态 → zconfig 详情配置区 → 工作区「打开目录」。
2. 完整对话验证（zconfig_echo / async_echo / manifestcfg_echo）。→ 三条都已完成（02:53–02:55），见 `docs/58`。
3. 配置值校验再补 `pattern`（正则）与数组 minItems/maxItems；数字范围/字符串长度已做（本文 §二.4）。
   → **已完成（M38，docs/62）**；`uniqueItems`/`format` 仍未做。
4. manifest schema 改后热重载（现在「重新加载」只读入口 JS，manifest 变了要重装）。
   → **已完成（M37，docs/61）**：重载重读清单，schema/默认值/权限一起更新，用户配置按新 schema prune。
5. 项目/工作区页继续对齐 Operit 2；图标/菜单统一。
6. 群反馈先复现再改；不照单全收；不泄露令牌/密钥/内部路径。
