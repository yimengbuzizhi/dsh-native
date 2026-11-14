package xin.dshaa.nativeapp.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xin.dshaa.nativeapp.device.DevicePermissions
import xin.dshaa.nativeapp.ui.permissions.PermissionChecklist
import xin.dshaa.nativeapp.ui.permissions.permissionSpecs
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * 首次启动的使用指导（仿 Operit 的 PermissionGuideScreen，裁成 3 页）。
 *
 * 为什么要有：Shizuku / 无障碍 / 悬浮窗 不是 Android 运行时权限，点一次系统弹窗就完事，
 * 用户得知道「为什么开、去哪儿开、开完怎么回来」。所以第一页讲是什么，第二页当场给权限清单，
 * 第三页告诉你去哪里改。第二页直接复用设置里的 [PermissionChecklist]，两处行为永远一致。
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val c = DshTheme2.colors
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 返回键：引导内部先回上一页，第一页才交回系统（退出 App）
    androidx.activity.compose.BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        // 右上角跳过：不强迫配置（权限页以后能从设置里进）
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Spacer(Modifier.weight(1f))
            Text(
                "跳过",
                style = MaterialTheme.typography.labelLarge,
                color = if (pager.currentPage == 2) androidx.compose.ui.graphics.Color.Transparent else c.faint,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onDone() }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Top,
        ) { page ->
            when (page) {
                0 -> WelcomePage()
                1 -> PermissionsPage()
                else -> ReadyPage()
            }
        }

        // 底部：页码点 + 主按钮
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(3) { index ->
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (index == pager.currentPage) 18.dp else 6.dp, height = 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (index == pager.currentPage) c.brand else c.line2),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(
                onClick = {
                    if (pager.currentPage == 2) {
                        onDone()
                    } else {
                        scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = c.brand,
            ) {
                Row(
                    Modifier.padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (pager.currentPage) {
                            0 -> "下一步"
                            1 -> "下一步"
                            else -> "开始使用"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = c.onHero,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.ArrowForward, null, tint = c.onHero, modifier = Modifier.size(17.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "权限随时可在「设置 → 功能与工具 → 权限与能力」里改",
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // 引用一次，保证引导页把 Shizuku 状态也预热一下（binder 连接是异步的）
            Spacer(Modifier.height(0.dp))
        }
    }
}

@Composable
private fun WelcomePage() {
    val c = DshTheme2.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(26.dp))
        Box(Modifier.size(96.dp).clip(CircleShape).background(c.brand), contentAlignment = Alignment.Center) {
            Box(Modifier.size(58.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(c.text))
                    Box(Modifier.size(12.dp).clip(CircleShape).background(c.text))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("鲸歌", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text(
            "不用电脑、不开浏览器：模型直接住在手机上，能读屏幕、点手机、跑命令。",
            style = MaterialTheme.typography.bodyMedium,
            color = c.sub,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(26.dp))
        FeatureRow(Icons.Rounded.ChatBubble, "聊天就是首页", "打开就写，会话按工作区分组，左滑出侧边栏。")
        FeatureRow(Icons.Rounded.Shield, "设备能力", "Shizuku 给 shell，无障碍给读屏与点击，悬浮球随时呼出。")
        FeatureRow(Icons.Rounded.FolderOpen, "工作区 = 文件根", "每个工作区是一块可写目录，切换时工具根跟着换。")
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, desc: String) {
    val c = DshTheme2.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.brandSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.brand, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = c.sub)
        }
    }
}

@Composable
private fun PermissionsPage() {
    val c = DshTheme2.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Text(
            "先把能力打开",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = c.text,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "这些不是账号权限，是让模型真的能操作手机。缺哪项点哪项，开完回来会自动刷新。",
            style = MaterialTheme.typography.bodySmall,
            color = c.sub,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        PermissionChecklist(Modifier.weight(1f))
    }
}

@Composable
private fun ReadyPage() {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val specs = androidx.compose.runtime.remember { permissionSpecs(context) }
    val ready = specs.count { it.ready }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.size(72.dp).clip(CircleShape).background(c.successSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Shield, null, tint = c.success, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("可以开始了", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = c.text)
        Spacer(Modifier.height(6.dp))
        Text(
            if (ready == specs.size) "权限都齐了。" else "还有 ${specs.size - ready} 项没开，之后能在设置里补齐。",
            style = MaterialTheme.typography.bodyMedium,
            color = c.sub,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Column(Modifier.fillMaxWidth()) {
            TipRow("聊天", "发第一条消息就会建会话，左上角 ☰ 是侧边栏。")
            TipRow("工作区", "侧边栏顶部可切换；会话按工作区隔离。")
            TipRow("设备", "设置 → 功能与工具，虚拟屏/终端/无障碍都在里面。")
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun TipRow(title: String, desc: String) {
    val c = DshTheme2.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(3.dp).height(30.dp).clip(CircleShape).background(c.brand.copy(alpha = 0.55f)))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = c.sub)
        }
    }
}
