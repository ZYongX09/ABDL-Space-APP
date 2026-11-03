package org.joinmastodon.android.novel.app.editor

import org.joinmastodon.android.R
import androidx.compose.ui.res.stringResource

import android.graphics.Bitmap
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.joinmastodon.android.novel.app.LocalNovelCoverScope
import org.joinmastodon.android.novel.app.NovelRemoteCover
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.joinmastodon.android.novel.app.design.NovelTypography

private data class NovelLocalCoverPreview(val path: String, val entity: String, val scope: org.joinmastodon.android.novel.app.NovelCoverScope, val bitmap: Bitmap)

/** Decode only the current private selection; a stale result cannot resurrect a replaced image. */
@Composable
internal fun NovelEditorCover(data: NovelMetadata, modifier: Modifier = Modifier, entity: String = "editor-preview", placeholder: @Composable (() -> Unit)? = null) {
    val scope = LocalNovelCoverScope.current
    val path = data.localCover
    val preview by produceState<NovelLocalCoverPreview?>(null, path, entity, scope) {
        value = null
        if (path != null && scope?.isActive() == true) {
            val decoded = withContext(Dispatchers.IO) { runCatching { NovelCoverImages.decode(File(path)) }.getOrNull() }
            if (scope.isActive()) value = decoded?.let { NovelLocalCoverPreview(path, entity, scope, it) }
        }
    }
    // Never recycle a bitmap handed to Compose: an older render frame may still hold its ImageBitmap.
    // The bounded 512px preview is reclaimed once both composition and renderer release it.
    val bitmap = preview?.takeIf { it.path == path && it.entity == entity && it.scope === scope && scope != null && scope.active && scope.isActive() }?.bitmap
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(NovelTheme.colors.primaryContainer), contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            if (placeholder != null) placeholder()
            else Text(data.title, fontFamily = NovelTypography.serif, fontSize = 15.sp, modifier = Modifier.padding(10.dp))
        }
        bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.novel_en_local_cover_preview), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        // A pending local replacement always wins over the previously published remote cover.
        NovelRemoteCover(entity, "editor", data.coverUrl?.takeUnless { data.coverPending }, Modifier.fillMaxSize(),
            stringResource(R.string.novel_en_uploaded_cover_preview))
    }
}
