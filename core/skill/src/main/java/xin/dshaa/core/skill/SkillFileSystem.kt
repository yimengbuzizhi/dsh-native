package xin.dshaa.core.skill

import java.io.File
import java.io.IOException

/**
 * 文件系统技能 provider（对齐上游 `@deepseek-ai/dsh-skill-filesystem`）。
 *
 * 发现规则（逐条照抄）：
 * - 每个根目录下：子目录 = 目录型技能（读 `<dir>/SKILL.md`）；`*.md` 平铺文件 = 单文件技能；
 * - 根目录按 rank 升序扫（project > 自定义 > user > bundled），**同名先到先得**；
 * - 目录内条目按名字码元序；`skipSystem` 的根跳过 `.system`；
 * - 读失败/解析失败只忽略那一条（上游 `logger.warn`），根目录本身打不开才把快照标成不完整；
 * - 技能的 `resourceBase` 是目录：目录型 = 技能自己的目录，平铺 = 根目录（相对路径按它解析）。
 *
 * Android 的根（App 层组装，见 `AppViewModel.skillRoots()`）：
 * `<workspace>/.dsh/skills`（project-dsh）→ `<workspace>/.agents/skills`（project-agents）
 * → `<files>/skills`（user-dsh，skipSystem）→ `<files>/agents/skills`（user-agents）。
 */
object SkillFileSystemProvider {
    /** 上游 provider 名（写进 `skill` 工具结果与目录消息之外的诊断信息）。 */
    const val NAME = "filesystem"

    /** 上游的五个 rank：数字越小越优先。 */
    const val PROJECT_DSH_RANK = 100
    const val PROJECT_AGENTS_RANK = 200
    const val CUSTOM_RANK = 300
    const val USER_DSH_RANK = 400
    const val USER_AGENTS_RANK = 500
    const val BUNDLED_RANK = 600
}

/** 一个技能根目录。 */
data class SkillRoot(
    val path: File,
    /** project-dsh / project-agents / custom / user-dsh / user-agents / bundled。 */
    val source: String,
    val rank: Int,
    /** user-dsh 根跳过 `.system`（上游 `skipSystem: true`）。 */
    val skipSystem: Boolean = false,
)

/** 解析后的一条候选（与 [SkillSummary] 的差别：还没定 provider/source）。 */
internal data class ParsedSkill(
    val name: String,
    val description: String,
    val whenToUse: String?,
    val invocation: SkillInvocation,
    val body: String,
)

/**
 * 文件系统技能仓库：每次 [snapshot] 现场扫盘（5 个目录、几十个文件，代价可忽略；
 * 上游用 chokidar 监听 + revision 缓存，Android 不需要）。
 */
class FileSystemSkills(
    private val roots: List<SkillRoot>,
    private val warn: (String) -> Unit = {},
    /** 被用户关掉的技能名：发现/加载都当它不存在（默认空，测试与引擎不用改）。 */
    private val disabled: () -> Set<String> = { emptySet() },
) : SkillHost {

    /** 最近的忽略原因（设置页「技能」用来解释为什么某个文件没被收进目录）。 */
    private val warnings = java.util.Collections.synchronizedList(ArrayList<String>())

    fun lastWarnings(): List<String> = synchronized(warnings) { warnings.toList() }

    /** 当前扫描的根（设置页展示用）。 */
    fun roots(): List<SkillRoot> = roots

    override fun snapshot(): SkillSnapshot {
        val merged = LinkedHashMap<String, SkillSummary>()
        var complete = true
        for (root in roots.sortedBy { it.rank }) {
            val entries = listRoot(root)
            if (entries == null) {
                complete = false
                continue
            }
            for (entry in entries.sortedBy { it.name }) {
                if (root.skipSystem && entry.name == ".system") continue
                val file = when {
                    entry.isDirectory -> File(entry, "SKILL.md")
                    entry.isFile && entry.name.endsWith(".md") -> entry
                    else -> continue
                }
                val parsed = parseFile(file) ?: continue
                // M81：关掉的技能照样进快照（包管理里要能再打开），只是 enabled=false
                val enabled = parsed.name !in disabled()
                if (merged.containsKey(parsed.name)) {
                    // 上游：同名低优先级条目只打一条 warning，不进目录
                    note("skill \"${parsed.name}\" from ${file.absolutePath} ignored because a higher-priority skill already exists")
                    continue
                }
                merged[parsed.name] = SkillSummary(
                    name = parsed.name,
                    description = parsed.description,
                    whenToUse = parsed.whenToUse,
                    invocation = parsed.invocation,
                    source = root.source,
                    rank = root.rank,
                    path = file.absolutePath,
                    directory = if (entry.isDirectory) entry.absolutePath else root.path.absolutePath,
                    enabled = enabled,
                )
            }
        }
        return SkillSnapshot(merged.values.sortedBy { it.name }, complete)
    }

    override fun load(name: String): SkillLoad {
        if (!isSkillName(name)) return SkillLoad.InvalidName(name)
        if (name in disabled()) return SkillLoad.Unknown(name)
        val summary = snapshot().skills.firstOrNull { it.name == name } ?: return SkillLoad.Unknown(name)
        if (!summary.modelInvocable) return SkillLoad.NotModelInvocable(name)
        val parsed = parseFile(File(summary.path)) ?: return SkillLoad.Unknown(name)
        // 文件在发现之后被改过名字：上游判成「unknown or no longer available」并清缓存
        if (parsed.name != name) return SkillLoad.Unknown(name)
        return SkillLoad.Ok(summary.toContent(parsed.body))
    }

    /**
     * 用户直接 `/名字` 调用：跳过 modelInvocable（`disable-model-invocation` 的技能
     * 只能这样进入），但 `user-invocable: false` 的技能不可用。
     */
    override fun loadForUser(name: String): SkillLoad {
        if (!isSkillName(name)) return SkillLoad.InvalidName(name)
        if (name in disabled()) return SkillLoad.Unknown(name)
        val summary = snapshot().skills.firstOrNull { it.name == name } ?: return SkillLoad.Unknown(name)
        if (!summary.userInvocable) return SkillLoad.Unknown(name)
        val parsed = parseFile(File(summary.path)) ?: return SkillLoad.Unknown(name)
        if (parsed.name != name) return SkillLoad.Unknown(name)
        return SkillLoad.Ok(summary.toContent(parsed.body))
    }

    /** null = 根目录读失败（快照不完整）；空列表 = 目录不存在或没有条目。 */
    private fun listRoot(root: SkillRoot): List<File>? {
        val dir = root.path
        if (!dir.exists()) return emptyList()
        if (!dir.isDirectory) return emptyList()
        return try {
            dir.listFiles()?.toList() ?: emptyList()
        } catch (e: IOException) {
            note("skill root \"${dir.absolutePath}\" skipped: ${e.message}. Other roots remain available; discovery retries on the next lookup.")
            null
        } catch (e: SecurityException) {
            note("skill root \"${dir.absolutePath}\" skipped: ${e.message}. Other roots remain available; discovery retries on the next lookup.")
            null
        }
    }

    /** 读 + 解析一个 SKILL.md；失败只记 warning 并返回 null（该技能被忽略）。 */
    private fun parseFile(file: File): ParsedSkill? {
        if (!file.isFile) return null
        val raw = try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            note("skill file ${file.absolutePath} ignored: ${e.message}")
            return null
        }
        val parsed = try {
            SkillFrontmatter.parse(raw)
        } catch (e: SkillFrontmatter.Invalid) {
            note("skill file ${file.absolutePath} ignored: invalid YAML frontmatter: ${e.message}")
            return null
        }
        if (parsed == null) {
            note("skill file ${file.absolutePath} ignored: missing YAML frontmatter")
            return null
        }
        val name = parsed.data["name"]?.takeIf { it.isNotEmpty() }
        val description = parsed.data["description"]?.takeIf { it.isNotEmpty() }
        if (name == null || description == null) {
            note("skill file ${file.absolutePath} ignored: frontmatter requires name and description")
            return null
        }
        if (!isSkillName(name)) {
            note("skill file ${file.absolutePath} ignored: invalid skill name \"$name\"")
            return null
        }
        val invocation = try {
            SkillFrontmatter.invocation(parsed.data)
        } catch (e: SkillFrontmatter.Invalid) {
            note("skill file ${file.absolutePath} ignored: invalid invocation frontmatter: ${e.message}")
            return null
        }
        return ParsedSkill(
            name = name,
            description = description,
            whenToUse = parsed.data["whenToUse"]?.takeIf { it.isNotEmpty() },
            invocation = invocation,
            body = parsed.body,
        )
    }

    private fun note(message: String) {
        warnings.add(message)
        if (warnings.size > 50) warnings.removeAt(0)
        runCatching { warn(message) }
    }

    companion object {
        /**
         * 上游 `findProjectRoot`：从 cwd 往上找第一个带 `.git` 的目录；找不到就以 cwd 为项目根。
         * 测试/沙箱里的工作区通常没有 `.git`，所以项目根 = 工作区本身。
         */
        fun findProjectRoot(cwd: File): File {
            var current = cwd.absoluteFile
            while (true) {
                if (File(current, ".git").exists()) return current
                val parent = current.parentFile ?: return cwd.absoluteFile
                if (parent == current) return cwd.absoluteFile
                current = parent
            }
        }

        /** 按上游顺序拼出根列表；[workspace] 用来定位项目根，[home] 是 App 的 files 目录（DSH home）。 */
        fun androidRoots(workspace: File, home: File): List<SkillRoot> {
            val project = findProjectRoot(workspace)
            return listOf(
                SkillRoot(File(project, ".dsh/skills"), "project-dsh", SkillFileSystemProvider.PROJECT_DSH_RANK),
                SkillRoot(File(project, ".agents/skills"), "project-agents", SkillFileSystemProvider.PROJECT_AGENTS_RANK),
                SkillRoot(File(home, "skills"), "user-dsh", SkillFileSystemProvider.USER_DSH_RANK, skipSystem = true),
                SkillRoot(File(home, "agents/skills"), "user-agents", SkillFileSystemProvider.USER_AGENTS_RANK),
            )
        }
    }
}
