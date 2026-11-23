package xin.dshaa.nativeapp.ui.shell.native

import xin.dshaa.ui.shellapi.NativeShell
import xin.dshaa.ui.shellapi.NativeShells
import xin.dshaa.ui.shellapi.ShellPage
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import xin.dshaa.ui.shellapi.BuiltinPageIds
import xin.dshaa.nativeapp.ui.shell.DshDrawer
import xin.dshaa.nativeapp.ui.shell.slots.LocalSlotHasMessages
import xin.dshaa.nativeapp.ui.shell.slots.LocalSlotRegistry
import xin.dshaa.nativeapp.ui.shell.slots.SlotOverlay
import xin.dshaa.ui.shellapi.NativeShellHost
import xin.dshaa.ui.shellapi.NativeShellHostUi
import xin.dshaa.ui.shellapi.PageLevel
import xin.dshaa.ui.shellapi.ShellPageRegistry
import xin.dshaa.nativeapp.ui.shell.native.pages.NativeNav
import xin.dshaa.nativeapp.ui.shell.native.pages.NativePageDispatch
import xin.dshaa.nativeapp.ui.shell.native.pages.NativePages
import xin.dshaa.ui.design.theme.DshTheme2
import xin.dshaa.ui.design.theme.LiquidLevel
import xin.dshaa.ui.design.component.GlassDivider
import xin.dshaa.ui.design.component.LiquidBackdrop
import xin.dshaa.ui.design.component.liquidGlass
import xin.dshaa.ui.design.theme.LiquidRadius
import androidx.compose.ui.graphics.RectangleShape

@Composable
private fun NativeShellContent(vm: NativeShellHost, hostUi: NativeShellHostUi) {
    val c = DshTheme2.colors
    val conversation = vm.conversation
    // null = 聊天（落地页）；非空 = 一级页面 id（BuiltinPageIds）
    var screenName by rememberSaveable { mutableStateOf<String?>(null) }
    var subScreenName by rememberSaveable { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val currentSub = NativePages.byId(subScreenName)

    // 转场方向（M79）：同层级互跳（设置→会话列表）没有深度差，用这个记住是「进」还是「退」。
    var navForward by remember { mutableStateOf(true) }
    // 0.3.4（用户第 3 条）：显式的「从哪进」状态，返回就回哪。
    // backPage = 当前一级页/二级页的来路：null = 聊天，Settings = 设置首页，其它 = 对应一级页；
    // backSub 只在来路本身是设置二级页时非空（例如在二级页打开抽屉后再导航）。
    var backPage by rememberSaveable { mutableStateOf<String?>(null) }
    var backSub by rememberSaveable { mutableStateOf<String?>(null) }
    // H3 补（真机导航回归 2026-11-16）：进二级页会临时把 backPage 改成 Settings 供 closeSub 用；
    // 返回设置后必须恢复「设置页自己的返回目标」，否则「设置→二级→返回→再返回」原地卡在设置。
    var subReturnPage by rememberSaveable { mutableStateOf<String?>(null) }
    var subReturnSub by rememberSaveable { mutableStateOf<String?>(null) }
    // docs/190-A：一级页的返回栈（与 subReturn* 对称）。go() 打开一级页时把「当前页自己的返回
    // 目标」存这里，backFromPage() 回来时恢复；否则「聊天→设置→记忆库→返回→设置→返回」原地不动。
    var pageReturnPage by rememberSaveable { mutableStateOf<String?>(null) }
    var pageReturnSub by rememberSaveable { mutableStateOf<String?>(null) }

    /** 当前路由快照交给纯状态机 [NativeNavBackPolicy]，UI 只负责把结果写回 Compose 状态。 */
    fun navState(): NativeNavBackPolicy.State = NativeNavBackPolicy.State(
        screen = screenName,
        sub = subScreenName,
        backPage = backPage,
        backSub = backSub,
        pageReturnPage = pageReturnPage,
        pageReturnSub = pageReturnSub,
        subReturnPage = subReturnPage,
        subReturnSub = subReturnSub,
    )

    fun applyNavState(next: NativeNavBackPolicy.State) {
        screenName = next.screen
        subScreenName = next.sub
        backPage = next.backPage
        backSub = next.backSub
        pageReturnPage = next.pageReturnPage
        pageReturnSub = next.pageReturnSub
        subReturnPage = next.subReturnPage
        subReturnSub = next.subReturnSub
    }
    /**
     * H4 候选 E（docs/182）：从设置首页进二级页时，设置首页留在下面继续组合；返回时不再
     * 重新组合整页（真机实测二级页→设置单帧 150–300ms，主要是目标页首次组合）。
     * 从聊天直进二级页（模型芯片→模型配置）时不需要重新组合设置首页，保持 false，避免多组合一页。
     */
    var retainSettingsHome by rememberSaveable { mutableStateOf(false) }
    /** 最近打开过的二级页：关闭后仍保留组合（藏在设置首页后面），再进同一页不再重新组合。
     *  只在本进程内保留；冷启恢复设置页时不把上次的二级页也组合出来做后台工作。 */
    var lastSub by remember { mutableStateOf<String?>(null) }
    // 打开抽屉那一刻的路由快照（抽屉里的入口完成后按它返回）。
    var drawerOriginPage by rememberSaveable { mutableStateOf<String?>(null) }
    var drawerOriginSub by rememberSaveable { mutableStateOf<String?>(null) }
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }
    // 0.3.4（用户第 3 条）：抽屉既可能由顶栏 ☰（openDrawer）打开，也可能被手势拉出——
    // 手势走 ModalNavigationDrawer 内部、不经过 openDrawer 回调。所以在抽屉**真正打开**的
    // 那一刻统一记来路，两种打开方式行为一致。
    androidx.compose.runtime.LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            drawerOriginPage = screenName
            drawerOriginSub = subScreenName
        }
    }
    val go: (String) -> Unit = { id ->
        // M84-B：页面由内置插件提供；被关掉时不进（入口已隐藏，这里防调试通道/旧状态残留）
        val target = NativePages.byId(id)
        if (target != null && target.level == PageLevel.Primary &&
            vm.builtinScreenEnabled(id)
        ) {
            // docs/190-A：一级页压栈——返回目标是当前页，当前页自己的返回目标存 pageReturn*。
            val next = NativeNavBackPolicy.onPrimaryOpen(navState(), id)
            if (next != navState()) {
                navForward = true
                retainSettingsHome = next.screen == BuiltinPageIds.Settings
                applyNavState(next)
            }
        }
    }
    /** 从聊天页直接进的功能页：返回落回聊天页。 */
    val goFromChat: (String) -> Unit = { id ->
        val target = NativePages.byId(id)
        if (target != null && target.level == PageLevel.Primary &&
            vm.builtinScreenEnabled(id)
        ) {
            navForward = true
            retainSettingsHome = false
            // 显式「从聊天进」：不管调用时在哪，返回目标一律是聊天。
            applyNavState(NativeNavBackPolicy.onPrimaryOpen(NativeNavBackPolicy.chat(), id))
        }
    }
    /**
     * 二级页统一从「设置」下挂；页面插件关掉时拒绝进入。
     * H3（docs/182）：返回目标按**真实来路**记录——聊天直进（模型芯片 → 模型配置）返回聊天，
     * 从设置进返回设置首页。0.3.4 一律记 Settings，聊天直进时返回键会「原地卡住」。
     */
    val openSub: (String) -> Unit = { id ->
        val target = NativePages.byId(id)
        if (target != null && target.level == PageLevel.Secondary &&
            vm.builtinScreenEnabled(id)
        ) {
            navForward = true
            retainSettingsHome = screenName == BuiltinPageIds.Settings
            applyNavState(NativeNavBackPolicy.onSubOpen(navState(), id, BuiltinPageIds.Settings))
        }
    }
    /** 抽屉里点一级页：返回目标是打开抽屉时的来路（0.3.4 第 3 条）。 */
    val goFromDrawer: (String) -> Unit = { id ->
        val target = NativePages.byId(id)
        if (target != null && target.level == PageLevel.Primary &&
            vm.builtinScreenEnabled(id)
        ) {
            // 宽屏常驻侧边栏没有抽屉开合事件（drawerState 一直 Closed），来路就是当前页。
            val originPage = if (drawerState.isOpen) drawerOriginPage else screenName
            val originSub = if (drawerState.isOpen) drawerOriginSub else subScreenName
            val next = NativeNavBackPolicy.onPrimaryOpen(
                navState(), id, originPage = originPage, originSub = originSub,
            )
            if (next != navState()) {
                navForward = true
                retainSettingsHome = next.screen == BuiltinPageIds.Settings
                applyNavState(next)
            }
        }
    }
    /** 抽屉里的二级入口（工作区管理 / 助手配置 / 远程 DSH…）：返回同样回抽屉来路，不绕经设置。 */
    val openSubFromDrawer: (String) -> Unit = { id ->
        val target = NativePages.byId(id)
        if (target != null && target.level == PageLevel.Secondary &&
            vm.builtinScreenEnabled(id)
        ) {
            val originPage = if (drawerState.isOpen) drawerOriginPage else screenName
            val originSub = if (drawerState.isOpen) drawerOriginSub else subScreenName
            navForward = true
            retainSettingsHome = originPage == BuiltinPageIds.Settings
            applyNavState(
                NativeNavBackPolicy.onSubOpen(
                    navState(), id, BuiltinPageIds.Settings,
                    originPage = originPage, originSub = originSub,
                ),
            )
        }
    }
    // M84-B：恢复出来的路由落在「已被内置插件关掉」的页面时，退回聊天/设置。
    androidx.compose.runtime.LaunchedEffect(screenName) {
        val page = screenName ?: return@LaunchedEffect
        if (!vm.builtinScreenEnabled(page)) {
            screenName = null
            subScreenName = null
        }
    }
    androidx.compose.runtime.LaunchedEffect(subScreenName) {
        val sub = subScreenName ?: return@LaunchedEffect
        lastSub = sub
        if (!vm.builtinScreenEnabled(sub)) subScreenName = null
    }
    /** 返回：回到显式记录的来路（聊天 / 设置首页 / 对应一级页），并恢复那一页自己的来路。 */
    val backFromPage: () -> Unit = {
        navForward = false
        applyNavState(NativeNavBackPolicy.onBackFromPrimary(navState()))
        retainSettingsHome = screenName == BuiltinPageIds.Settings
    }
    /** 二级页返回：先回显式记录的来路（设置内部进二级页时来路就是设置），再恢复设置页自己的返回目标。 */
    val closeSub: () -> Unit = {
        navForward = false
        applyNavState(NativeNavBackPolicy.onSubClose(navState()))
        retainSettingsHome = screenName == BuiltinPageIds.Settings
    }
    // 宽屏（平板 / 折叠屏展开）侧边栏常驻：600dp 是 Material 的 compact/expanded 断点
    val wideLayout = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    android.util.Log.i(
        "DshShell",
        "native ui config width=${androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp}dp " +
            "density=${androidx.compose.ui.platform.LocalDensity.current.density} wide=$wideLayout",
    )
    // 设置页的滚动位置提到这里：进出二级页不再被重置到顶部（用户 23:00 第 3 条）
    val settingsScroll = androidx.compose.foundation.rememberScrollState()

    // 落地页必须是聊天：冷启动 / 删除当前会话后回来，都补一个空草稿（不建会话文件）。
    androidx.compose.runtime.LaunchedEffect(screenName, conversation.stateKey, conversation.state == null) {
        if (screenName == null) conversation.ensureDraft()
    }

    // 系统返回键：抽屉 → 关抽屉；二级页 → 回设置；一级页 → 回聊天；子代理回放 → 回父会话；
    // 聊天页本身不拦截（交给系统退出 App，RikkaHub 同款语义）。
    androidx.activity.compose.BackHandler(
        enabled = drawerState.isOpen || currentSub != null || screenName != null || (conversation.isLocal && vm.canGoBackFromChat),
    ) {
        navForward = false
        when {
            drawerState.isOpen -> closeDrawer()
            currentSub != null -> closeSub()
            // 功能页回到来路（0.3.4：聊天开的回聊天，设置开的回设置，抽屉开的回抽屉来路）
            screenName != null -> backFromPage()
            else -> vm.closeChat()
        }
    }

    // M85-C2：页面的动作交给页面 dispatch，路由状态只留在这里（页面不再认识路由状态机）。
    val nav = NativeNav(
        openDrawer = openDrawer,
        toChat = {
            navForward = false
            retainSettingsHome = false
            applyNavState(NativeNavBackPolicy.chat())
        },
        go = go,
        goFromChat = goFromChat,
        openSub = openSub,
        closeSub = closeSub,
        backFromPage = backFromPage,
        settingsScroll = settingsScroll,
        wideLayout = wideLayout,
    )

    // 调试通道：`--es dsh_import_zip` 直接把旧版导入页顶到前面（真机无 SAF 也能验收）
    androidx.compose.runtime.LaunchedEffect(vm.importRequest) {
        if (vm.importRequest != null) openSub(BuiltinPageIds.DataMigration)
    }

    // 调试通道：`--es dsh_open_screen agents` 启动即落到指定页面（一级页或二级页都认）
    androidx.compose.runtime.LaunchedEffect(vm.debugScreenRequest) {
        val requested = vm.debugScreenRequest ?: return@LaunchedEffect
        if (requested.equals("chat", ignoreCase = true) || requested.equals("home", ignoreCase = true)) {
            // 验收用：清回聊天落地页（关掉页面后测试「路由被拦」需要一个已知起点）
            applyNavState(NativeNavBackPolicy.chat())
        } else {
            val match = NativePages.find(requested)
            if (match != null) {
                if (match.level == PageLevel.Primary) {
                    go(match.id)
                } else {
                    openSub(match.id)
                }
            }
        }
        vm.consumeDebugScreen()
    }

    // M87-H5：插件通知点击回到聊天落地页（用户拍板：只清页面栈，不深链会话/插件页）。
    androidx.compose.runtime.LaunchedEffect(vm.landingChatRequest) {
        if (vm.landingChatRequest <= 0L) return@LaunchedEffect
        navForward = false
        applyNavState(NativeNavBackPolicy.chat())
        vm.consumeLandingChatRequest()
    }

    // 验收用：路由落的哪一页（logcat -s DshPage）；关掉的页面不会被记录，等于「被拦回」。
    androidx.compose.runtime.LaunchedEffect(screenName, subScreenName) {
        android.util.Log.i("DshPage", "route page=${screenName ?: "Chat"} sub=${subScreenName ?: "-"}")
    }

    // 抽屉内容：窄屏是滑出的 Modal 抽屉；宽屏（平板/折叠屏展开，>=600dp）常驻在左侧（M74，用户 23:00 第 5 条）
    // M83：参数 permanent 决定窄屏 Modal 抽屉 / 宽屏常驻直角面板（两种模式的白色缺口问题见 DshDrawer 注释）。
    val drawerContent: @Composable (Boolean) -> Unit = { permanent ->
            DshDrawer(
                permanent = permanent,
                currentSessionId = conversation.state?.sessionId,
                sessions = conversation.sessions,
                workspace = vm.currentWorkspace,
                workspaces = vm.workspaces,
                assistantName = conversation.assistantName,
                assistantAvatar = conversation.assistantAvatar,
                assistantProfiles = vm.settings.assistantProfiles,
                activeAssistantId = vm.settings.activeAssistantId,
                onSwitchAssistant = { id -> vm.settings.setActiveAssistant(id) },
                onManageAssistants = {
                    closeDrawer()
                    openSubFromDrawer(BuiltinPageIds.Assistant)
                },
                model = conversation.model,
                toolCount = conversation.toolCount,
                remoteProfileId = conversation.remoteProfileId,
                remoteProfiles = vm.remote.profiles,
                localFeatures = conversation.isLocal,
                onSelectTarget = { profileId ->
                    conversation.selectTarget(profileId)
                    applyNavState(NativeNavBackPolicy.chat())
                    closeDrawer()
                },
                onManageRemote = { closeDrawer(); openSubFromDrawer(BuiltinPageIds.RemoteDsh) },
                badgeFor = { id -> conversation.sessions.firstOrNull { it.id == id }?.let { it.badge ?: if (it.running) xin.dshaa.ui.shellapi.chat.SessionBadge.RUNNING else null } },
                onOpenSession = { summary ->
                    closeDrawer()
                    navForward = false
                    applyNavState(NativeNavBackPolicy.chat())
                    conversation.openSession(summary.id)
                },
                onNewChat = {
                    closeDrawer()
                    navForward = false
                    applyNavState(NativeNavBackPolicy.chat())
                    conversation.newChat()
                },
                onSwitchWorkspace = { path ->
                    closeDrawer()
                    navForward = false
                    applyNavState(NativeNavBackPolicy.chat())
                    vm.switchWorkspace(path)
                },
                onCreateWorkspace = { name, path ->
                    closeDrawer()
                    navForward = false
                    applyNavState(NativeNavBackPolicy.chat())
                    vm.createWorkspace(name, path)
                },
                onManageWorkspaces = {
                    closeDrawer()
                    openSubFromDrawer(BuiltinPageIds.Workspaces)
                },
                onSelectScreen = { id ->
                    closeDrawer()
                    goFromDrawer(id)
                },
                sessionsPageEnabled = conversation.isLocal && vm.builtinScreenEnabled(BuiltinPageIds.Sessions),
                // M85-C3-4：抽屉「插件页面」段只读 page/v1 注册表；同 id 覆盖项走内置页入口，不重复列
                pluginPages = ShellPageRegistry.drawerPluginPages(
                    shellPages = NativePages.declarations,
                    pluginPages = vm.pluginPageEntries(),
                ).map { page ->
                    xin.dshaa.nativeapp.ui.shell.PluginPageShortcut(
                        pluginId = page.pluginId.orEmpty(),
                        pluginName = page.pluginName.orEmpty(),
                        routeId = page.routeId.orEmpty(),
                        title = page.title,
                    )
                },
                onOpenPluginPage = { entry ->
                    closeDrawer()
                    vm.openPluginPageEntry(
                        xin.dshaa.ui.shellapi.PluginPageEntry(
                            pluginId = entry.pluginId,
                            pluginName = entry.pluginName,
                            routeId = entry.routeId,
                            title = entry.title,
                        ),
                    )
                },
                onRenameSession = { id, title -> vm.renameSession(id, title) },
                onDeleteSession = { id -> vm.deleteSession(id) },
                onDeleteSessionPermanently = { id -> vm.deleteSessionPermanently(id) },
            )
    }

    /**
     * 页面级背景：每个页面自己铺一层不透明「墙」。
     *
     * 为什么必须有：设置首页在打开二级页时是**保活组合**的（见上面的 M88 注释），二级页如果
     * 是透明的，就会看到下面那一页的所有文字一层层叠上来（用户 2026-11-19 截图：标题/行文字
     * 全部重影）。AnimatedContent 换页时旧页也会留到动画结束，同理。
     *
     * docs/187 §4.2：液态玻璃壳铺 `LiquidBackdrop`（水族箱墙面），原生壳铺 0.3.5 的实底色。
     */
    val PageBackdrop: @Composable (@Composable () -> Unit) -> Unit = { inner ->
        if (DshTheme2.liquidEnabled) {
            LiquidBackdrop(Modifier.fillMaxSize()) { inner() }
        } else {
            Box(Modifier.fillMaxSize().background(DshTheme2.colors.background)) { inner() }
        }
    }

    // 屏幕主体：宽屏时和常驻抽屉并排，窄屏时铺满
    val body: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            // M79：所有一级页/二级页共用一套转场（用户 2026-11-12：进二级页、退出都没有动画）。
            // H4 候选 A（docs/182 §H4）：新页 160ms 淡入 + 1/8 屏轻位移，旧页不动画，去掉 SizeTransform。
            // 外层 target 改成一/二级**页面**（二级页的 sub 交给内层）：从设置进二级页时设置首页
            // 留在下面继续组合（候选 E），返回设置不再重新组合整页——真机实测那一帧要 150–300ms。
            AnimatedContent(
                targetState = screenName,
                transitionSpec = {
                    val from = if (initialState == null) 0 else 1
                    val to = if (targetState == null) 0 else 1
                    val forward = if (from == to) navForward else to > from
                    val dir = if (forward) 1 else -1
                    (
                        fadeIn(animationSpec = tween(160)) +
                            slideInHorizontally(animationSpec = tween(160)) { full -> dir * full / 8 }
                        ) togetherWith ExitTransition.None
                },
                label = "dsh-screen",
            ) { page ->
                PageBackdrop {
                if (page == BuiltinPageIds.Settings) {
                    Box(Modifier.fillMaxSize()) {
                        // 设置首页：在设置栈里一直保留组合（含二级页打开时），返回时不再重新
                        // 组合整页；聊天直进二级页（retain=false）不组合首页，返回聊天时也不多组合。
                        if (retainSettingsHome ||
                            (subScreenName == null && screenName == BuiltinPageIds.Settings)
                        ) {
                            NativePageDispatch(vm, BuiltinPageIds.Settings, null, nav, hostUi)
                        }
                        // 二级页单独一层：首次打开组合；关闭后保留组合（alpha=0、zIndex 沉到首页后面），
                        // 再进同一页直接显示，不再付整页组合的 150–300ms。
                        // M88-F（docs/183 §6）：保留组合不变，只给这一层补位移 + alpha——
                        // 进入 +1/10 屏宽→0 / 180ms，退出反向 140ms，同级切换 6% / 160ms。
                        val subVisible = subScreenName != null
                        var prevSub by remember { mutableStateOf<String?>(null) }
                        val subShift = remember { Animatable(0f) }
                        val subContentAlpha = remember { Animatable(1f) }
                        val subAlpha by animateFloatAsState(
                            targetValue = if (subVisible) 1f else 0f,
                            animationSpec = tween(SubpageShift.durationMs(prevSub, subScreenName)),
                            label = "dsh-sub-alpha",
                        )
                        val screenWidthPx = with(LocalDensity.current) {
                            LocalConfiguration.current.screenWidthDp.dp.toPx()
                        }
                        LaunchedEffect(subScreenName) {
                            val from = prevSub
                            val to = subScreenName
                            prevSub = to
                            val dir = SubpageShift.dir(from, to)
                            val fraction = SubpageShift.fraction(from, to)
                            val ms = SubpageShift.durationMs(from, to)
                            when {
                                dir > 0 -> {
                                    subShift.snapTo(fraction)
                                    if (from != null) {
                                        // 同级切换：6% 位移 + 一次短淡入（不是硬切）。
                                        launch {
                                            subContentAlpha.snapTo(0.4f)
                                            subContentAlpha.animateTo(1f, tween(ms))
                                        }
                                    } else {
                                        subContentAlpha.snapTo(1f)
                                    }
                                    subShift.animateTo(0f, tween(ms))
                                }
                                // 退出：与进入反向滑回右侧（位移取正值），同时 alpha 140ms 淡出。
                                dir < 0 -> subShift.animateTo(fraction, tween(ms))
                            }
                        }
                        val shownSub = subScreenName ?: lastSub
                        if (shownSub != null && (subVisible || retainSettingsHome)) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .offset {
                                        IntOffset((subShift.value * screenWidthPx).roundToInt(), 0)
                                    }
                                    .zIndex(if (subVisible || subAlpha > 0.01f) 1f else -1f)
                                    .alpha(subAlpha * subContentAlpha.value)
                                    .then(
                                        if (!subVisible && subAlpha <= 0.01f) {
                                            Modifier.clearAndSetSemantics {}
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                PageBackdrop { NativePageDispatch(vm, BuiltinPageIds.Settings, shownSub, nav, hostUi) }
                            }
                        }
                    }
                } else {
                    // M85-C2：页面内容由壳自己的 dispatch 画（标题/图标/层级都在 `NativePages`）
                    NativePageDispatch(vm, page, null, nav, hostUi)
                }
                }
            }
        }
    }

    // M87-D：把插槽注册表交给整棵壳（抽屉/浮层也要读），会话有无消息供 `when.session` 过滤。
    val hasSlotMessages = conversation.state?.messages?.isNotEmpty() == true
    // docs/187 §4.2：液态玻璃壳整棵铺在「水族箱」背景上（页面内容透出它）；
    // 原生壳铺 0.3.5 的实底 `background`，页面里的透明背景就落到这层上。
    val ShellBackdrop: @Composable (@Composable () -> Unit) -> Unit = { inner ->
        if (DshTheme2.liquidEnabled) {
            LiquidBackdrop(Modifier.fillMaxSize(), animated = false, withSheen = false) { inner() }
        } else {
            Box(Modifier.fillMaxSize().background(DshTheme2.colors.background)) { inner() }
        }
    }
    ShellBackdrop {
        CompositionLocalProvider(
            LocalSlotRegistry provides vm.slotRegistry,
            LocalSlotHasMessages provides hasSlotMessages,
        ) {
            if (wideLayout) {
                // M83：宽屏不再用 Material 的 PermanentNavigationDrawer。它内部会再套一层
                // PermanentDrawerSheet（默认容器色 surfaceContainerLow ≈ #FAFAFA、带圆角），
                // 深色主题下侧边栏右上/右下角会露出两块白色缺口（用户：「四角/边缘有白色空白」）。
                // 这里用普通 Row 拼常驻侧边栏：左侧一整块竖玻璃，右边一条发丝分隔线。
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .width(304.dp)
                            .fillMaxHeight()
                            .padding(start = 12.dp, end = 12.dp, top = 6.dp).liquidGlass(shape = RoundedCornerShape(LiquidRadius.xl), level = LiquidLevel.Panel),
                    ) { drawerContent(true) }
                    GlassDivider(Modifier.fillMaxHeight(), horizontal = false)
                    Box(Modifier.weight(1f).fillMaxHeight()) { body() }
                }
            } else {
                androidx.compose.material3.ModalNavigationDrawer(
                    drawerState = drawerState,
                    // 0.3.4（用户第 2 条）：侧边栏手势只在聊天页与设置首页生效；二级页/其它一级页不认边缘滑动。
                    // 宽屏常驻抽屉不走这里，不受影响。
                    gesturesEnabled = subScreenName == null &&
                        (screenName == null || screenName == BuiltinPageIds.Settings),
                    drawerContent = {
                        // Warm recreation in this host can leave the initial sheet at x=0 even
                        // with a correct closed offset. Keep measuring for anchors, but do not
                        // place fully closed content (including its semantics and touch targets).
                        androidx.compose.ui.layout.Layout(content = { drawerContent(false) }) { measurables, constraints ->
                            val sheets = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                            val width = sheets.maxOfOrNull { it.width } ?: 0
                            val height = sheets.maxOfOrNull { it.height } ?: 0
                            layout(width, height) {
                                val offset = drawerState.currentOffset
                                if (drawerState.isOpen || (!offset.isNaN() && offset > -width.toFloat())) {
                                    sheets.forEach { it.placeRelative(0, 0) }
                                }
                            }
                        }
                    },
                ) { body() }
            }
            // M87-D：shell.overlay 画在壳最上层（Modal 抽屉 / 常驻侧边栏 / 二级页之上）。
            // 聊天页底部是输入条：浮层让开约 128dp，别挡住发送/附件；其它页贴右下角。
            SlotOverlay("shell.overlay", bottomInset = if (screenName == null) 128 else 0)
            // docs/190-C：Node 启动小卡片画在**任何页面**之上（非模态，不挡其它操作）。
            NodeBootOverlay(
                progress = vm.nodeBootProgress,
                logs = vm.nodeHostLogs,
                onDismiss = { vm.dismissNodeBoot() },
                onRetry = { vm.retryNodeBoot() },
                onStop = { vm.stopNodeBoot() },
            )
        }
    }
}

/** 内置 native 壳（`builtin.shell.native`）：把现有 Compose UI 原样包进 `shell/v1`。 */
object BuiltinNativeShell : NativeShell {
    override val id: String = NativeShells.BUILTIN_ID
    override val label: String = "原生界面"

    /** M85-C2：把壳的 22 页声明交给宿主（只有 id/标题/层级；图标和渲染留在壳里）。 */
    override val pages: List<ShellPage> = NativePages.declarations

    /** M85-C2：调试通道的宽松匹配由壳解释（id/标题/`<id>Screen`）。 */
    override fun findPage(requested: String): ShellPage? = NativePages.find(requested)?.declaration

    @Composable
    override fun Root(vm: NativeShellHost, ui: NativeShellHostUi) {
        if (!vm.onboardingDone) {
            // 引导页背景由壳层背景提供（再多铺一层会让软件渲染直接跪掉）：
            // 液态玻璃壳铺墙，原生壳铺实底（docs/187 §4.2）。
            if (DshTheme2.liquidEnabled) {
                LiquidBackdrop(Modifier.fillMaxSize(), animated = false, withSheen = false) {
                    Onboarding(onDone = { vm.completeOnboarding() })
                }
            } else {
                Box(Modifier.fillMaxSize().background(DshTheme2.colors.background)) {
                    Onboarding(onDone = { vm.completeOnboarding() })
                }
            }
        } else {
            NativeShellContent(vm, ui)
        }
    }

    @Composable
    override fun PluginManager(host: NativeShellHost, ui: NativeShellHostUi, onBack: () -> Unit) {
        xin.dshaa.nativeapp.ui.shell.native.pages.NativePluginsPage(host, ui, onBack, onBack)
    }

    /** M85-C3：引导页实现留在壳里；宿主（可选 Surface 壳路径）只通过契约要它。 */
    @Composable
    override fun Onboarding(onDone: () -> Unit) {
        xin.dshaa.nativeapp.ui.onboarding.OnboardingScreen(onDone = onDone)
    }
}
