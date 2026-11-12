package xin.dshaa.nativeapp.data

import android.content.Context
import java.io.File

/**
 * 随 App 一起发的能力说明（bundled skill）。
 *
 * 鲸歌的技能发现只扫文件系统（`files/skills`、工作区 `.dsh/skills` 等），assets 里的
 * SKILL.md 不会自动出现；启动时把内置技能复制到 user-dsh 根，用户/模型就能像普通技能一样
 * `/dsh-plugin-adapter` 调用。
 */
object BundledSkills {

    private data class Bundled(val dir: String, val asset: String)

    private val skills = listOf(
        Bundled("dsh-plugin-adapter", "skills/dsh-plugin-adapter/SKILL.md"),
    )

    /** 内置技能名（M81：这些不给「删除」入口，只能关；用户自己导入的才能删）。 */
    val names: Set<String> = skills.mapTo(LinkedHashSet()) { it.dir }

    fun isBundled(name: String): Boolean = name in names

    fun ensureInstalled(context: Context) {
        skills.forEach { skill ->
            runCatching {
                val dir = File(context.filesDir, "skills/${skill.dir}").apply { mkdirs() }
                val target = File(dir, "SKILL.md")
                if (target.isFile && target.length() > 0) return@forEach
                context.assets.open(skill.asset).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
