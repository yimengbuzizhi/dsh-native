package xin.dshaa.ui.design.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xin.dshaa.ui.design.theme.DshTheme2
import java.io.File

/**
 * 头像：真实图片（用户从相册选、复制到 filesDir/avatars 后存路径），没有图时用名字首字兜底。
 * 对应 Operit `AvatarPicker` / `CharacterItem` 里的 36-70dp 圆形头像 + 兜底 Person 图标，
 * 我们用「首字 + 品牌色底」兜底，避免额外图标资源。
 */
object AvatarLoader {
    private val cache = LruCache<String, Bitmap>(24)

    fun load(context: Context, spec: String): Bitmap? {
        if (spec.isBlank()) return null
        cache.get(spec)?.let { return it }
        val bitmap = runCatching {
            val bytes = when {
                spec.startsWith("content://") || spec.startsWith("file://") ->
                    context.contentResolver.openInputStream(Uri.parse(spec))?.use { it.readBytes() }
                else -> File(spec).takeIf { it.isFile }?.readBytes()
            } ?: return@runCatching null
            decodeDownscaled(bytes, 512)
        }.getOrNull()
        if (bitmap != null) cache.put(spec, bitmap)
        return bitmap
    }

    private fun decodeDownscaled(bytes: ByteArray, max: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > max) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    fun clear() = cache.evictAll()
}

@Composable
fun DshAvatar(
    avatar: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    corner: Dp = size / 2,
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    var bitmap by remember(avatar) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(avatar) {
        bitmap = if (avatar.isBlank()) null else withContext(Dispatchers.IO) {
            AvatarLoader.load(context, avatar)?.asImageBitmap()
        }
    }
    val shape = if (corner >= size / 2) CircleShape else RoundedCornerShape(corner)
    Box(
        modifier.size(size).clip(shape).background(c.brandSoft),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = name, modifier = Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Text(
                name.trim().take(1).ifBlank { "D" },
                color = c.brand,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
