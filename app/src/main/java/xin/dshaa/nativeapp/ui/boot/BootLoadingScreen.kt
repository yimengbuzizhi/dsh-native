package xin.dshaa.nativeapp.ui.boot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*
import xin.dshaa.core.plugins.LoadingSpec
import xin.dshaa.nativeapp.data.LoadingSnapshot

/** No AppViewModel, shell bindings, JS, file/network or plugin action callbacks. */
@Composable
fun BootLoadingScreen(
    snapshot: LoadingSnapshot?,
    state: BootState,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onUseDefault: (() -> Unit)? = null,
    onOpenRecovery: (() -> Unit)? = null,
) {
    val declaration = remember(snapshot?.revision, snapshot?.loading) {
        snapshot?.loading?.let { value ->
            runCatching { LoadingSpec.parse(LoadingSpec.declaration(value)).loading }.getOrNull()
        }
    }
    // Fault controls remain host-owned and visible even if the chosen tree draws no content.
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (state.status == BootStatus.FAILED || declaration == null) {
            BootRecoveryContent(state, onRetry, onUseDefault, onOpenRecovery)
        } else {
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                LoadingNode(remember(declaration.treeJson) { Json.parseToJsonElement(declaration.treeJson).jsonObject }, state)
            }
        }
    }
}

/** Minimal host fallback, independent of built-in or third-party loading implementation. */
@Composable
private fun BootRecoveryContent(state: BootState, onRetry: (() -> Unit)?, onUseDefault: (() -> Unit)?, onOpenRecovery: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (state.status == BootStatus.FAILED) "启动未完成" else "正在启动", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text((state.failure ?: state.message).take(2000), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) TextButton(onClick = onRetry) { Text("重试") }
        if (onUseDefault != null) TextButton(onClick = onUseDefault) { Text("使用默认加载页") }
        if (onOpenRecovery != null) TextButton(onClick = onOpenRecovery) { Text("管理插件 / 修复角色") }
    }
}

@Composable
private fun LoadingNode(node: JsonObject, state: BootState) {
    fun text(key: String): String? = (node[key] as? JsonPrimitive)?.contentOrNull
    fun number(key: String, fallback: Float = 0f): Float = (node[key] as? JsonPrimitive)?.floatOrNull ?: fallback
    val type = text("type").orEmpty()
    var modifier: Modifier = Modifier
    if (node.containsKey("corner")) modifier = modifier.clip(RoundedCornerShape(number("corner").dp))
    if (node.containsKey("background")) modifier = modifier.background(loadingColor(text("background")))
    modifier = modifier.padding(number("padding").dp)
    val children = (node["children"] as? JsonArray).orEmpty()
    val align = text("align") ?: "center"
    when (type) {
        "column" -> Column(modifier, verticalArrangement = Arrangement.spacedBy(number("gap").dp), horizontalAlignment = when (align) {
            "start" -> Alignment.Start
            "end" -> Alignment.End
            else -> Alignment.CenterHorizontally
        }) { children.forEach { LoadingNode(it.jsonObject, state) } }
        "row" -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(number("gap").dp), verticalAlignment = when (align) {
            "start" -> Alignment.Top
            "end" -> Alignment.Bottom
            else -> Alignment.CenterVertically
        }) { children.forEach { LoadingNode(it.jsonObject, state) } }
        "stack" -> Box(modifier, contentAlignment = when (align) {
            "start" -> Alignment.TopStart
            "end" -> Alignment.BottomEnd
            else -> Alignment.Center
        }) { children.forEach { LoadingNode(it.jsonObject, state) } }
        "text" -> Text(
            text = text("bind")?.let(state::binding) ?: text("text").orEmpty(),
            modifier = modifier,
            color = loadingColor(text("color") ?: "text"),
            fontSize = number("size", 16f).sp,
            fontWeight = when (text("weight")) { "bold" -> FontWeight.Bold; "medium" -> FontWeight.Medium; else -> FontWeight.Normal },
        )
        "progress" -> {
            val progress = (node["value"] as? JsonPrimitive)?.floatOrNull
            if (progress == null) CircularProgressIndicator(modifier = modifier, color = loadingColor(text("color") ?: "brand"))
            else CircularProgressIndicator(progress = { progress }, modifier = modifier, color = loadingColor(text("color") ?: "brand"))
        }
        "icon" -> Icon(
            imageVector = when (text("name")) { "extension" -> Icons.Rounded.Extension; "hourglass" -> Icons.Rounded.HourglassEmpty; "info" -> Icons.Rounded.Info; else -> Icons.Rounded.Waves },
            contentDescription = null,
            modifier = modifier.size(number("size", 32f).dp),
            tint = loadingColor(text("color") ?: "brand"),
        )
        "spacer" -> Spacer(modifier.size(number("size", 12f).dp))
        "divider" -> HorizontalDivider(modifier)
    }
}

@Composable
private fun loadingColor(token: String?): Color {
    val colors = MaterialTheme.colorScheme
    return when (token) {
        "background" -> colors.background
        "surface" -> colors.surface
        "brand" -> colors.primary
        "sub" -> colors.onSurfaceVariant
        "faint" -> colors.onSurfaceVariant.copy(alpha = 0.65f)
        "warn" -> colors.error
        else -> colors.onSurface
    }
}
