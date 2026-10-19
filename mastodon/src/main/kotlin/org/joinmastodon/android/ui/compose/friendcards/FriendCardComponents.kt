@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.joinmastodon.android.ui.compose.friendcards

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import me.grishka.appkit.imageloader.ViewImageLoader
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest
import org.joinmastodon.android.friendcards.FriendCardRules
import org.joinmastodon.android.friendcards.FriendCardGames
import org.joinmastodon.android.model.FriendCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun FriendCardNotice(text: String, action: String?, retry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (action != null && retry != null) TextButton(text = action, onClick = retry)
    }
}

@Composable
fun FriendCardPanel(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(lerp(MiuixTheme.colorScheme.background, MiuixTheme.colorScheme.onSurface, .055f)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        title?.let { Text(it, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
        content()
    }
}

@Composable
fun FriendCardActions(vararg actions: Pair<String, () -> Unit>, enabled: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        actions.forEach { (label, action) -> TextButton(text = label, enabled = enabled, onClick = action) }
    }
}

@Composable
fun FriendCardField(label: String, value: String, limit: Int, enabled: Boolean = true, hint: String = "", multiline: Boolean = false, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text("${FriendCardRules.length(value)}/$limit", fontSize = 12.sp, color = if (FriendCardRules.length(value) > limit) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        BasicTextField(value = value, onValueChange = onChange, enabled = enabled, singleLine = !multiline,
            textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 17.sp, lineHeight = 26.sp), cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = if (multiline) 72.dp else 28.dp),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, fontSize = 17.sp, lineHeight = 26.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary); inner() } })
    }
}

@Composable
fun FriendCardPrivacy(field: String, visible: Boolean, enabled: Boolean, onChange: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, role = Role.Checkbox, onClick = onChange).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("${FriendCardRules.fields[field]}可见范围", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(if (visible) "公开 · 点击设为仅自己" else "仅自己 · 点击设为公开", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
    }
}

@Composable
fun FriendCardChip(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    Text(text, fontSize = 14.sp, color = if (selected) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurface,
        modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(if (selected) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceVariant.copy(alpha = .65f))
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 44.dp) else Modifier)
            .padding(horizontal = 15.dp, vertical = 11.dp))
}

@Composable
fun FriendCardTagInput(label: String, enabled: Boolean, onAdd: (String) -> String?) {
    var value by remember { mutableStateOf(TextFieldValue()) }
    var error by remember { mutableStateOf<String?>(null) }
    val add = {
        if (enabled && value.composition == null && value.text.isNotBlank()) {
            error = onAdd(value.text)
            if (error == null) value = TextFieldValue()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(value = value, onValueChange = { value = it; error = null }, enabled = enabled, singleLine = true,
                textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 16.sp), cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.surfaceVariant).padding(12.dp),
                decorationBox = { inner -> Box { if (value.text.isEmpty()) Text(label, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 15.sp); inner() } })
            val active = enabled && value.text.isNotBlank() && value.composition == null
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceVariant)
                .clickable(enabled = active, role = Role.Button, onClick = { add() }).semantics { contentDescription = "添加$label" }, contentAlignment = Alignment.Center) {
                Text("+", fontSize = 26.sp, color = if (active) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        error?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 13.sp) }
    }
}

internal class FocusImage(context: Context) : ImageView(context) {
    var focusX = .5f
    var focusY = .4f
    var onDrawable: ((Boolean) -> Unit)? = null
    init { scaleType = ScaleType.MATRIX }
    override fun setImageDrawable(drawable: Drawable?) { super.setImageDrawable(drawable); position(); onDrawable?.invoke(drawable != null) }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); position() }
    fun position() {
        val d = drawable ?: return
        if (d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0 || width <= 0 || height <= 0) return
        val scale = maxOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        // A mathematically covered axis can undershoot by one Float ULP after multiplication.
        // Clamp the lower bound too, so valid dimensions never form an inverted interval.
        val minX = (width - d.intrinsicWidth * scale).coerceAtMost(0f)
        val minY = (height - d.intrinsicHeight * scale).coerceAtMost(0f)
        val dx = (width / 2f - d.intrinsicWidth * scale * focusX).coerceIn(minX, 0f)
        val dy = (height / 2f - d.intrinsicHeight * scale * focusY).coerceIn(minY, 0f)
        imageMatrix = Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) }
    }
}

/** Existing image loader handles caching/cancellation; failure stays a compact, non-image title. */
@Composable
fun FriendCardCover(url: String?, title: String, focusX: Float, focusY: Float, ratio: Float = 2.2f, modifier: Modifier = Modifier) {
    var loaded by remember(url) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MiuixTheme.colorScheme.primaryContainer)) {
        if (!url.isNullOrBlank()) {
            Box(Modifier.fillMaxWidth().then(if (loaded) Modifier.aspectRatio(ratio) else Modifier.height(1.dp))) {
                key(url) {
                    AndroidView(factory = { context -> FocusImage(context).apply {
                        onDrawable = { loaded = it }
                        ViewImageLoader.load(this, null, UrlImageLoaderRequest(url, 1440, 1200))
                    } }, update = { image -> image.focusX = focusX; image.focusY = focusY; image.position() }, modifier = Modifier.fillMaxSize())
                }
                if (loaded) {
                    Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .8f)))).padding(top = 32.dp, start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                        Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (!loaded) Text(title, color = MiuixTheme.colorScheme.onPrimaryContainer, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(18.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun FriendCardProduct(item: FriendCard.Diaper, onOpen: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.surfaceVariant), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(enabled = item.available, role = Role.Button, onClick = onOpen).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MiuixTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Text("商品", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (!item.imageUrl.isNullOrBlank()) key(item.imageUrl) {
                    AndroidView(factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; ViewImageLoader.load(this, null, UrlImageLoaderRequest(item.imageUrl, 160, 160)) } }, modifier = Modifier.fillMaxSize())
                }
            }
            Column { Text(item.name?.takeIf { it.isNotBlank() } ?: "纸尿裤 #${item.id}", maxLines = 2, overflow = TextOverflow.Ellipsis); if (!item.available) Text("商品已下架 / 不可用", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        }
        if (onRemove != null) TextButton(text = "移除", onClick = onRemove)
    }
}

@Composable
fun FriendCardContents(card: FriendCard, games: Map<String, String> = emptyMap(), compact: Boolean = false, onDiaper: (FriendCard.Diaper) -> Unit = {}) {
    val basics = listOf("gender" to card.gender, "age" to card.age, "zodiac" to card.zodiac, "region" to card.region, "school" to card.school, "mbti" to card.mbti).filter { card.isPublic(it.first) && !it.second.isNullOrBlank() }
    if (basics.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { basics.take(if (compact) 4 else 6).forEach { FriendCardChip(it.second) } }
    if (card.isPublic("interaction_habits") && !card.interactionHabits.isNullOrBlank()) Text(card.interactionHabits, maxLines = if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    val tags = (if (card.isPublic("interests")) card.interests.orEmpty() else emptyList()) + (if (card.isPublic("games")) card.games.orEmpty().map { games[it] ?: FriendCardGames.gameName(it) } else emptyList())
    if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.take(if (compact) 4 else tags.size).forEach { FriendCardChip(it) }
        if (compact && tags.size > 4) FriendCardChip("+${tags.size - 4}")
    }
    if (card.isPublic("looking_for") && !card.lookingFor.isNullOrBlank()) {
        Text("我想找", fontWeight = FontWeight.SemiBold)
        Text(card.lookingFor, maxLines = if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
    }
    if (card.isPublic("boundaries") && !card.boundaries.isNullOrBlank()) Text("边界 · ${card.boundaries}", maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    if (!compact && card.isPublic("diapers") && !card.diapers.isNullOrEmpty()) {
        Text("我穿过", fontWeight = FontWeight.SemiBold)
        card.diapers.forEach { item -> FriendCardProduct(item, { onDiaper(item) }) }
    }
}
