package xin.dshaa.nativeapp.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dshaa.ui.shellapi.AgentTargetUi
import xin.dshaa.ui.shellapi.RoleTargetUi
import xin.dshaa.ui.shellapi.visibleInRoleList

/**
 * 0.3.4（用户 2026-11-15 第 4 条 b）：插件页「角色」不再列停用/未授权的第三方实现；
 * 内置实现始终保留（可用性由内置开关决定，行内带原因）。
 */
class RoleTargetVisibilityTest {

    private fun thirdParty(enabled: Boolean = true, permitted: Boolean = true) = RoleTargetUi(
        role = "storage",
        implementationId = "export",
        label = "只读导出",
        pluginId = "demo.role",
        provider = "Demo",
        builtin = false,
        enabled = enabled,
        permitted = permitted,
        selectionValue = "demo.role#export",
    )

    @Test fun disabledOrUnauthorizedThirdPartyHidesFromRoleList() {
        assertTrue(thirdParty().visibleInRoleList())
        assertFalse(thirdParty(enabled = false).visibleInRoleList())
        assertFalse(thirdParty(permitted = false).visibleInRoleList())
    }

    @Test fun builtinAlwaysStaysVisibleEvenWhenItsPluginIsOff() {
        val builtin = RoleTargetUi(
            role = "shell",
            implementationId = "builtin.shell.native",
            label = "原生界面",
            builtin = true,
            enabled = false,
            active = false,
            selectionValue = "native",
            unavailableReason = "内置界面插件已停用",
        )
        assertTrue(builtin.visibleInRoleList())
    }

    @Test fun thirdPartyShellFollowsItsOwnSwitch() {
        // 2026-11-15 用户反馈：内置里关掉「鲸歌可选壳」后，角色页仍显示「鲸歌默认壳」。
        // 插件壳是插件实现（有开关），不是宿主内置实现，停用后必须从角色页消失。
        // docs/187 §5.1 之后 `builtin.shell.whale` 已删除，示例用第三方插件壳。
        val pluginShell = RoleTargetUi(
            role = "shell",
            implementationId = "m85f.webview-shell",
            label = "第三方壳（WebView）· webview",
            pluginId = "m85f.webview-shell",
            provider = "示例壳插件",
            builtin = false,
            enabled = false,
            permitted = true,
            selectionValue = "m85f.webview-shell#m85f.webview-shell",
            unavailableReason = "提供方插件已停用",
        )
        assertFalse(pluginShell.visibleInRoleList())
        assertTrue(pluginShell.copy(enabled = true).visibleInRoleList())
    }

    @Test fun liquidGlassShellIsBuiltinAndAlwaysListed() {
        // 液态玻璃壳（docs/187 §4.2）是宿主内置实现：`builtin=true` + `pluginId=null`，
        // 所以它即使「停用」（回原生）也要留在角色页，让用户能再选回来。
        val liquid = RoleTargetUi(
            role = "shell",
            implementationId = "builtin.shell.liquid",
            label = "界面：液态玻璃（实验）",
            pluginId = null,
            builtin = true,
            enabled = false,
            permitted = true,
            active = false,
            selectionValue = "liquid",
        )
        assertTrue(liquid.visibleInRoleList())
    }

    @Test fun agentTargetsFollowTheSameRule() {
        val thirdParty = AgentTargetUi(
            agentId = "m85.echo",
            label = "回声回合",
            pluginId = "m85.echo-agent",
            builtin = false,
            enabled = false,
        )
        assertFalse(thirdParty.visibleInRoleList())
        assertFalse(thirdParty.copy(enabled = true, permitted = false).visibleInRoleList())
        assertTrue(thirdParty.copy(enabled = true, permitted = true).visibleInRoleList())
        assertTrue(thirdParty.copy(pluginId = null, builtin = true).visibleInRoleList())
    }
}
