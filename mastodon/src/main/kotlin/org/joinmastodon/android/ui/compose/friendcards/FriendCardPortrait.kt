@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.joinmastodon.android.ui.compose.friendcards

import android.widget.ImageView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import org.joinmastodon.android.R
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import me.grishka.appkit.imageloader.ViewImageLoader
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest
import org.joinmastodon.android.api.requests.friendcards.FriendCardInput
import org.joinmastodon.android.friendcards.FriendCardGames
import org.joinmastodon.android.model.FriendCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class FriendCardPalette(val background: Color, val panel: Color, val accent: Color)

internal fun friendCardPalette(card: FriendCard): FriendCardPalette {
    val colors = listOf(Color(0xFF70434F), Color(0xFF263F43), Color(0xFF48634C), Color(0xFF455775))
    val index = (card.title.orEmpty().hashCode() and Int.MAX_VALUE) % colors.size
    val background = colors[index]
    return FriendCardPalette(background, lerp(background, Color.Black, .10f), lerp(background, Color.White, .78f))
}

internal fun FriendCardCompletion(input: FriendCardInput, hasCover: Boolean): Int {
    val values = listOf(hasCover, input.title.isNotBlank(), input.gender.isNotBlank(), input.age.isNotBlank(),
        input.zodiac.isNotBlank(), input.region.isNotBlank(), input.school.isNotBlank(), input.mbti.isNotBlank(),
        input.interactionHabits.isNotBlank(), input.interests.isNotEmpty(), input.games.isNotEmpty(),
        input.diaperIds.isNotEmpty(), input.lookingFor.isNotBlank(), input.boundaries.isNotBlank())
    return values.count { it } * 100 / values.size
}

@Composable
fun FriendCardPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, outlined: Boolean = false, light: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    val foreground = if (light) Color.White else if (outlined) colors.primary else colors.onPrimary
    val background = if (outlined) Color.Transparent else if (light) Color.White.copy(alpha = .16f) else colors.primary
    Box(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
        .background(background.copy(alpha = if (enabled) background.alpha else background.alpha * .45f))
        .then(if (outlined) Modifier.border(1.dp, foreground.copy(alpha = if (enabled) .4f else .18f), RoundedCornerShape(16.dp)) else Modifier)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp), contentAlignment = Alignment.Center) {
        Text(text, color = foreground.copy(alpha = if (enabled) 1f else .45f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun FriendCardIdentity(card: FriendCard, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, light: Boolean = true) {
    val name = card.owner?.displayName?.takeIf { it.isNotBlank() } ?: card.owner?.username?.takeIf { it.isNotBlank() } ?: "扩列用户"
    val foreground = if (light) Color.White else MiuixTheme.colorScheme.onSurface
    Row(modifier.then(if (onClick != null) Modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(foreground.copy(alpha = .16f)).border(1.dp, foreground.copy(alpha = .25f), CircleShape), contentAlignment = Alignment.Center) {
            Text(name.take(1), color = foreground, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            val url = card.owner?.avatar?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            if (url != null) key(url) {
                AndroidView(factory = { context -> ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    ViewImageLoader.load(this, null, UrlImageLoaderRequest(url, 160, 160))
                } }, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, color = foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            card.owner?.username?.takeIf { it.isNotBlank() }?.let {
                Text("@$it", color = foreground.copy(alpha = .72f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun FriendCardHello(enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(54.dp).clip(CircleShape).background(Color.White.copy(alpha = if (enabled) 1f else .5f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = "发私信" }, contentAlignment = Alignment.Center) {
        FriendCardResourceIcon(R.drawable.ic_friend_card_chat, tint = Color(0xFF233036), modifier = Modifier.size(29.dp))
    }
}

@Composable
fun FriendCardPortrait(card: FriendCard, modifier: Modifier = Modifier, games: Map<String, String> = emptyMap(), compact: Boolean = false, topClearance: Dp = 0.dp, onDiaper: (FriendCard.Diaper) -> Unit = {}) {
    val palette = friendCardPalette(card)
    if (compact) {
        BoxWithConstraints(modifier.fillMaxSize().background(palette.background)) {
            val room = minOf(maxHeight.value / LocalDensity.current.fontScale, maxHeight.value * maxWidth.value / 360f)
            Column(Modifier.fillMaxSize()) {
                PortraitHeading(card, palette, compact = true, showCover = room >= 400, room = room)
                if (room >= 220) Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(palette.panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(if (room >= 450) 16.dp else 10.dp)) {
                    PortraitContents(card, games, compact = true, roomy = room >= 400, palette = palette, onDiaper = onDiaper, room = room)
                }
            }
        }
    } else {
        Column(modifier.fillMaxWidth().background(palette.background)) {
            PortraitHeading(card, palette, compact = false, topClearance = topClearance)
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 22.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(palette.panel).padding(20.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                PortraitContents(card, games, compact = false, roomy = true, palette = palette, onDiaper = onDiaper)
            }
        }
    }
}

@Composable
private fun PortraitHeading(card: FriendCard, palette: FriendCardPalette, compact: Boolean, showCover: Boolean = true, room: Float = Float.POSITIVE_INFINITY, topClearance: Dp = 0.dp) {
    var loaded by remember(card.coverUrl) { mutableStateOf(false) }
    val contentInvalidated = LocalFriendCardContentInvalidated.current
    val hasCover = showCover && !card.coverUrl.isNullOrBlank()
    Box(Modifier.fillMaxWidth()) {
        if (hasCover) Box(Modifier.fillMaxWidth().then(if (loaded) Modifier.aspectRatio(if (compact) 2.2f else 1.5f) else Modifier.height(1.dp)).clipToBounds()) {
            key(card.coverUrl) {
                AndroidView(factory = { context -> FocusImage(context).apply {
                    onDrawable = { loaded = it }
                    onContentInvalidated = contentInvalidated
                    animate().setUpdateListener { onContentInvalidated?.invoke() }
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    ViewImageLoader.load(this, null, UrlImageLoaderRequest(card.coverUrl, 1440, 1200))
                } }, update = { image -> image.onContentInvalidated = contentInvalidated; image.focusX = card.coverFocusX; image.focusY = card.coverFocusY; image.position() },
                    onRelease = { image -> image.onContentInvalidated = null; image.animate().setUpdateListener(null) }, modifier = Modifier.fillMaxSize())
            }
            if (loaded) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .30f to Color.Transparent, 1f to palette.background)))
        }
        Column(Modifier.fillMaxWidth().then(if (hasCover && loaded) Modifier.align(Alignment.BottomStart) else Modifier)
            .padding(start = if (compact) 24.dp else 28.dp, end = 24.dp, top = if (compact && room < 200) 12.dp else if (compact) 26.dp else maxOf(40.dp, topClearance), bottom = if (compact && room < 200) 12.dp else 24.dp)) {
            if (!compact || room >= 200) FriendCardQuotation()
            Text(card.title.orEmpty(), color = Color.White, fontSize = if (compact) 25.sp else 29.sp, lineHeight = if (compact) 33.sp else 39.sp,
                fontWeight = FontWeight.Bold, maxLines = if (compact && room < 100) 1 else if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun FriendCardQuotation(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 36.dp, height = 26.dp)) {
        val unit = minOf(size.width / 36f, size.height / 26f)
        for (offset in listOf(2f, 19f)) {
            val shape = Path().apply {
                moveTo((offset + 13f) * unit, 2f * unit)
                lineTo((offset + 10f) * unit, 2f * unit)
                cubicTo((offset + 3f) * unit, 2f * unit, offset * unit, 9f * unit, offset * unit, 17f * unit)
                lineTo(offset * unit, 24f * unit)
                lineTo((offset + 12f) * unit, 24f * unit)
                lineTo((offset + 12f) * unit, 13f * unit)
                lineTo((offset + 6f) * unit, 13f * unit)
                cubicTo((offset + 6f) * unit, 9f * unit, (offset + 9f) * unit, 6f * unit, (offset + 13f) * unit, 6f * unit)
                close()
            }
            drawPath(shape, Color.White.copy(alpha = .18f))
        }
    }
}

@Composable
private fun PortraitContents(card: FriendCard, games: Map<String, String>, compact: Boolean, roomy: Boolean, palette: FriendCardPalette, onDiaper: (FriendCard.Diaper) -> Unit, room: Float = Float.POSITIVE_INFINITY) {
    val basics = listOf("gender" to card.gender, "age" to card.age, "region" to card.region, "mbti" to card.mbti, "zodiac" to card.zodiac,
        "school" to card.school, "interaction_habits" to card.interactionHabits)
        .filter { card.isPublic(it.first) && !it.second.isNullOrBlank() }
    if (basics.isNotEmpty() && (!compact || roomy)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PortraitLabel("👤", "关于我", palette.accent)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxLines = if (!compact) Int.MAX_VALUE else if (room >= 540) 2 else 1) {
                val count = if (!compact) basics.size else if (room >= 540) 5 else 3
                basics.take(count).forEach { (_, text) ->
                    Text(text.orEmpty(), color = Color.White, fontSize = 14.sp, maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = .18f)).padding(horizontal = 13.dp, vertical = 8.dp))
                }
            }
        }
    }
    val interests = if (card.isPublic("interests")) card.interests.orEmpty() else emptyList()
    val gameNames = if (card.isPublic("games")) card.games.orEmpty().map { games[it] ?: FriendCardGames.gameName(it) } else emptyList()
    val liked = if (compact) (interests + gameNames).distinct() else interests
    if (liked.isNotEmpty() && (!compact || room >= 320)) PortraitTextSection("💖", "我喜欢", liked.joinToString(" | "), palette, if (!compact) Int.MAX_VALUE else if (room >= 540) 2 else 1)
    if (!compact && gameNames.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PortraitLabel("🎮", "我在玩", palette.accent)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                gameNames.forEach { name ->
                    Box(Modifier.widthIn(min = 120.dp, max = 240.dp).clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(Color.White.copy(alpha = .20f), Color.White.copy(alpha = .07f)))).padding(16.dp)) {
                        Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    if (card.isPublic("looking_for") && !card.lookingFor.isNullOrBlank()) PortraitTextSection("🔎", "我想找", card.lookingFor, palette, if (compact) 2 else Int.MAX_VALUE)
    if ((!compact || room >= 560) && card.isPublic("boundaries") && !card.boundaries.isNullOrBlank()) PortraitTextSection("💔", "我的雷区", card.boundaries, palette, if (compact) 2 else Int.MAX_VALUE)
    if (!compact && card.isPublic("diapers") && !card.diapers.isNullOrEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PortraitLabel("🧷", "我穿过", palette.accent)
            card.diapers.forEach { item -> FriendCardProduct(item, { onDiaper(item) }) }
        }
    }
    if (!compact && card.socialAccounts.orEmpty().any { it.isPublic }) FriendCardSocialDetails(card.socialAccounts.orEmpty())
}

@Composable
private fun PortraitLabel(icon: String, label: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(icon, fontSize = 15.sp)
        Text(label, fontSize = 13.sp, color = accent)
    }
}

@Composable
private fun PortraitTextSection(icon: String, label: String, value: String, palette: FriendCardPalette, maxLines: Int) {
    val compact = maxLines != Int.MAX_VALUE
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        PortraitLabel(icon, label, palette.accent)
        Text(value, color = Color.White, fontSize = if (compact) 15.sp else 16.sp, lineHeight = if (compact) 23.sp else 25.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}
