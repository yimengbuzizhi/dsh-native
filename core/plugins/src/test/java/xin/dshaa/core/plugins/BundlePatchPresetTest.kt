package xin.dshaa.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M74：`cordis.patch.yml` 的 `config:` 子树解析（助手预设）。
 *
 * 样本照上游 `dsh-web-app/presets/standard.patch.yml` 的真实形状写：
 * 缩进 2 空格、`- id: x` 行内映射、`config:` 嵌套、块标量 `|`、`cordis:group` 里
 * 再嵌一层 list、`!!js` 表达式、行尾注释。
 */
class BundlePatchPresetTest {

    private val patch = """
        # Agent preset standard: one declaration inserted after the web patch.
        - insert:
            - id: preset-standard
              name: '@deepseek-ai/dsh-agent-preset'
              config:
                id: standard
                name: 标准助手
                description: 默认的编码助手预设
                order: 1
                plugins:
                  - id: persona
                    name: '@deepseek-ai/dsh-persona'
                    config:
                      prefix: |
                        You are a coding agent powered by the {{model}} model.
                        你叫鲸歌，说话简短。
                      suffix: |
                        不要用感叹号。
                  - id: tool-bash
                    name: '@deepseek-ai/dsh-tool-bash'
                    disabled: !!js process.platform === 'win32'
                  - id: planning
                    name: cordis:group
                    group: true
                    config:
                      - id: plan-mode
                        name: '@deepseek-ai/dsh-plan-mode'
                        config:
                          section: |
                            You are in plan mode.
    """.trimIndent()

    @Test
    fun parsesAgentPresetWithPersonaPrefixAndSuffix() {
        val bundle = BundlePatchParser.parse(patch)
        assertEquals(1, bundle.presets.size)
        val preset = bundle.presets.first()
        assertEquals("standard", preset.presetId)
        assertEquals("标准助手", preset.name)
        assertEquals("默认的编码助手预设", preset.description)
        assertEquals(1, preset.order)
        // 块标量：多行原样保留，{{model}} 留给 PromptPack 替换
        assertEquals(
            "You are a coding agent powered by the {{model}} model.\n你叫鲸歌，说话简短。",
            preset.personaPrefix,
        )
        assertEquals("不要用感叹号。", preset.personaSuffix)
        // 3 个 plugins 行 + group 里的 1 行
        assertEquals(4, preset.pluginCount)
        // M61 的子插件列表照旧
        assertTrue(bundle.children.any { it.name == "@deepseek-ai/dsh-agent-preset" })
    }

    @Test
    fun patchWithoutPresetHasNoPresetsAndKeepsChildren() {
        val text = """
            - insert:
                - id: timer
                  name: '@deepseek-ai/cordis-plugin-timer'
                - id: hmr
                  name: '@deepseek-ai/dsh-hmr'
                  disabled: true
        """.trimIndent()
        val bundle = BundlePatchParser.parse(text)
        assertTrue(bundle.presets.isEmpty())
        assertEquals(2, bundle.children.size)
    }

    @Test
    fun presetWithOnlyPersonaSuffixIsOk() {
        val text = """
            - insert:
                - id: p
                  name: '@deepseek-ai/dsh-agent-preset'
                  config:
                    id: quiet
                    plugins:
                      - name: '@deepseek-ai/dsh-persona'
                        config:
                          suffix: 只回答一个字。
        """.trimIndent()
        val preset = BundlePatchParser.parse(text).presets.single()
        assertEquals("quiet", preset.presetId)
        assertEquals("quiet", preset.name)
        assertEquals("", preset.personaPrefix)
        assertEquals("只回答一个字。", preset.personaSuffix)
    }

    @Test
    fun brokenYamlDegradesToNoPresets() {
        val text = "- insert:\n  \t- id: x\n      name: [unclosed"
        // 不抛异常即可（M61 的组合包展示不受影响）
        val bundle = BundlePatchParser.parse(text)
        assertTrue(bundle.presets.isEmpty())
    }
}
