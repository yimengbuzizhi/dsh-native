package xin.dshaa.core.plugins

/**
 * 插件清单热重载（M37）：`reload` 时用磁盘上的新清单 + 一次试运行结果重算记录。
 *
 * 为什么要有这一步：之前「重新加载」只重跑入口 JS，`name/version/main/configSchema/configDefaults/
 * permissions` 都是安装时的快照；改 `manifest.json` 必须重装（docs/56 §四）。ToolPkg 风格把
 * 配置 schema 放在清单里，作者调 schema 的频率比调 JS 高，重装会丢 `installedAt` 和已授权限。
 *
 * 合并规则（文档：[docs/61]）：
 * - **身份**：`id/path/enabled/installedAt` 永远保留（目录和授权关系不能因为一次 reload 变）；
 *   `name/version/description/main/format` 跟新清单。
 * - **配置**：schema 优先级 = 插件导出的 `Config` → 清单 `config.schema` → 记录里的旧 schema；
 *   用户配置按新 schema prune（旧键丢弃，同名键保留）；默认值按新清单重算。
 * - **权限**：以新清单为准；旧授权按新清单过滤（撤销的权限立刻失效），但新清单里
 *   「自动允许」的能力（fs:workspace / ui）按安装时同样的规则补授。
 */
object PluginReload {

    fun apply(record: PluginRecord, manifest: PluginManifest, run: PluginDryRun): PluginRecord {
        val manifestSchema = manifest.configSchema?.let { PluginSchema.normalize(it)?.toString() }
        // 导出 Config 优先；清单 schema 次之；都没有才沿用旧 schema（插件这次可能只是没导出成功）。
        val schema = run.configSchema ?: manifestSchema ?: record.configSchema
        val requested = PluginPermissions.parseAll(manifest.permissions)
        val grants = PluginPermissions.sanitizeGrants(
            requested,
            (record.grantedPermissions + PluginPermissions.autoGrants(requested)).toList(),
        )
        return record.copy(
            name = manifest.name,
            version = manifest.version,
            description = manifest.description,
            main = manifest.main,
            format = manifest.format.label,
            tools = run.tools.map { it.name },
            toolDescriptions = run.tools.associate { it.name to it.description },
            toolSchemas = run.tools.mapNotNull { tool -> tool.parameters?.let { tool.name to it } }.toMap(),
            imports = run.imports,
            error = run.error,
            permissions = manifest.permissions,
            grantedPermissions = grants.toList(),
            configSchema = schema,
            config = if (schema != null) PluginSchema.prune(schema, record.config) else "{}",
            configDefaults = PluginSchema.effectiveText(schema, "{}", manifest.configDefaults?.toString()),
            configError = run.configError,
            clientUi = manifest.clientUi,
            bundlePatch = manifest.bundlePatch,
            enginesDsh = manifest.enginesDsh,
            promptSections = run.promptSections,
            contexts = run.contexts,
            promptVariables = run.promptVariables,
            commands = run.commands,
            unsupported = run.unsupported,
        )
    }
}
