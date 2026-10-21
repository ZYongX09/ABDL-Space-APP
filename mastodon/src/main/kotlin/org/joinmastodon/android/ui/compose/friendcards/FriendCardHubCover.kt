package org.joinmastodon.android.ui.compose.friendcards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.grishka.appkit.imageloader.ViewImageLoader
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest
import org.joinmastodon.android.model.FriendCard

/** A failed/absent cover occupies no banner space. Never substitute stock imagery or a title tile. */
@Composable
fun FriendCardHubCover(card: FriendCard, ratio: Float, modifier: Modifier = Modifier) {
    val url = card.coverUrl?.takeIf { it.isNotBlank() } ?: return
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()
        .then(if (loaded) Modifier.aspectRatio(ratio) else Modifier.height(0.dp))
        .clipToBounds().alpha(if (loaded) 1f else 0f)
        .then(if (loaded) Modifier.semantics { contentDescription = "${card.title}的封面" } else Modifier)) {
        key(url) {
            AndroidView(factory = { context ->
                FocusImage(context).apply {
                    onDrawable = { loaded = it && (drawable?.intrinsicWidth ?: 0) > 0 && (drawable?.intrinsicHeight ?: 0) > 0 }
                    ViewImageLoader.load(this, null, UrlImageLoaderRequest(url, 1080, 800))
                }
            }, update = { image ->
                image.focusX = card.coverFocusX.takeIf { it.isFinite() && it in 0f..1f } ?: .5f
                image.focusY = card.coverFocusY.takeIf { it.isFinite() && it in 0f..1f } ?: .4f
                image.position()
            }, onRelease = { image ->
                image.onDrawable = null
                image.setImageDrawable(null)
            }, modifier = Modifier.fillMaxSize())
        }
    }
}
