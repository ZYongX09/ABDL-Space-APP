package org.joinmastodon.android.novel.app.editor

import android.graphics.Bitmap
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.grishka.appkit.imageloader.ViewImageLoader
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.joinmastodon.android.novel.app.design.NovelTypography

/** Decode only the current private selection; a stale result cannot resurrect a replaced image. */
@Composable
internal fun NovelEditorCover(data: NovelMetadata, modifier: Modifier = Modifier, placeholder: @Composable (() -> Unit)? = null) {
    val path = data.localCover
    val preview by produceState<Pair<String, Bitmap>?>(null, path) {
        value = null
        if (path != null) {
            val decoded = withContext(Dispatchers.IO) { runCatching { NovelCoverImages.decode(File(path)) }.getOrNull() }
            value = decoded?.let { path to it }
        }
    }
    // Never recycle a bitmap handed to Compose: an older render frame may still hold its ImageBitmap.
    // The bounded 512px preview is reclaimed once both composition and renderer release it.
    val bitmap = preview?.takeIf { it.first == path }?.second
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(NovelTheme.colors.primaryContainer), contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            if (placeholder != null) placeholder()
            else Text(data.title, fontFamily = NovelTypography.serif, fontSize = 15.sp, modifier = Modifier.padding(10.dp))
        }
        bitmap?.let { Image(it.asImageBitmap(), "本地封面预览", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        // A pending local replacement always wins over the previously published remote cover.
        val url = data.coverUrl?.takeIf { !data.coverPending && okhttp3.HttpUrl.parse(it)?.isHttps == true }
        if (url != null) key(url) {
            AndroidView(factory = { context -> ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "已上传封面预览"
                ViewImageLoader.load(this, null, UrlImageLoaderRequest(url, 500, 700))
            } }, modifier = Modifier.fillMaxSize(), onRelease = { it.setImageDrawable(null) })
        }
    }
}
