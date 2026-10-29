package org.joinmastodon.android.novel.app

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.grishka.appkit.imageloader.ViewImageLoader
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest
import org.joinmastodon.android.novel.app.design.NovelIcons
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.joinmastodon.android.novel.app.design.NovelTypography
import org.joinmastodon.android.novel.app.models.Author
import org.joinmastodon.android.novel.app.models.Work

@Composable internal fun NovelHeading(text: String, modifier: Modifier = Modifier, size: Int = 23) {
    Text(text, modifier, color = NovelTheme.colors.text, fontFamily = NovelTypography.serif, fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = (size + 8).sp)
}

@Composable internal fun NovelIconButton(icon: ImageVector, label: String, onClick: () -> Unit, circular: Boolean = false, enabled: Boolean = true) {
    val colors = NovelTheme.colors
    IconButton(onClick, Modifier.size(44.dp).then(if (circular) Modifier.background(colors.surfaceVariant.copy(alpha = .45f), CircleShape) else Modifier), enabled = enabled) {
        Icon(icon, label, Modifier.size(23.dp), tint = if (enabled) colors.text else colors.textSecondary)
    }
}

@Composable internal fun NovelHeader(title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NovelIconButton(NovelIcons.ChevronLeft, "返回", onBack, circular = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NovelHeading(title, size = 26)
            subtitle?.let { Text(it, color = NovelTheme.colors.textSecondary, fontSize = 11.sp, lineHeight = 17.sp) }
        }
        actions()
    }
}

@Composable internal fun NovelPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true, icon: ImageVector? = null) {
    val c = NovelTheme.colors
    Button(onClick, modifier.heightIn(min = 44.dp), enabled = enabled, shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) c.primary else c.primaryContainer, contentColor = if (primary) c.onPrimary else c.primary),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        icon?.let { Icon(it, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable internal fun NovelLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick, modifier.heightIn(min = 40.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(text, fontSize = 13.sp, color = NovelTheme.colors.primary) }
}

@Composable internal fun NovelRule() { HorizontalDivider(color = NovelTheme.colors.outline, thickness = 1.dp) }

@Composable internal fun NovelTag(text: String) {
    Text(text, Modifier.clip(RoundedCornerShape(6.dp)).background(NovelTheme.colors.primaryContainer).padding(horizontal = 8.dp, vertical = 3.dp), color = NovelTheme.colors.primary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable internal fun NovelTabs(items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, underline: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (id, title) ->
            val active = id == selected
            val c = NovelTheme.colors
            Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).then(if (!underline) Modifier.background(if (active) c.primaryContainer else c.surfaceVariant.copy(alpha = .35f)) else Modifier)
                .clickable { onSelect(id) }.padding(vertical = if (underline) 12.dp else 11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = if (active) c.primary else c.textSecondary, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                if (underline) { Spacer(Modifier.height(11.dp)); Box(Modifier.size(24.dp, 3.dp).background(if (active) c.primary else Color.Transparent, RoundedCornerShape(2.dp))) }
            }
        }
    }
}

/** A typographic cover uses real work metadata whenever the author has not uploaded an image. */
@Composable internal fun NovelBookCover(work: Work, modifier: Modifier = Modifier, width: Dp = 76.dp) {
    val paper = listOf(Color(0xFFA64D34) to Color(0xFFFFF4DF), Color(0xFFDADDCA) to Color(0xFF384D36), Color(0xFF2D4854) to Color(0xFFEADFC4), Color(0xFFD8BFA0) to Color(0xFF543F31), Color(0xFF697460) to Color(0xFFF6F0E0))
    val (background, foreground) = paper[(work.id.hashCode().toLong().let { if (it < 0) -it else it } % paper.size).toInt()]
    Box(modifier.width(width).aspectRatio(.72f).clip(RoundedCornerShape(3.dp)).background(background).semantics { contentDescription = "${work.title}，封面" }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(work.title, color = foreground, fontFamily = NovelTypography.serif, fontSize = if (width > 100.dp) 21.sp else 15.sp, lineHeight = if (width > 100.dp) 29.sp else 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(work.author.name, color = foreground, fontSize = 8.sp, maxLines = 1)
        }
        val url = work.coverUrl?.takeIf { it.startsWith("https://") }
        if (url != null) key(url) {
            AndroidView(factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO; ViewImageLoader.load(this, null, UrlImageLoaderRequest(url, 500, 700)) } }, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable internal fun NovelAvatar(author: Author, size: Dp = 48.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(NovelTheme.colors.primaryContainer), contentAlignment = Alignment.Center) {
        Text(author.name.take(1), color = NovelTheme.colors.primary, fontSize = (size.value * .46f).sp, fontFamily = NovelTypography.serif)
        val url = author.avatar.takeIf { it.startsWith("https://") }
        if (url != null) key(url) { AndroidView(factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; ViewImageLoader.load(this, null, UrlImageLoaderRequest(url, 160, 160)) } }, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable internal fun NovelWorkRow(work: Work, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NovelBookCover(work)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(work.title, color = NovelTheme.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${work.author.name} / ${work.typeLabel}", color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
                Text(work.description, color = NovelTheme.colors.textSecondary, fontSize = 11.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { work.tags.take(3).forEach { NovelTag(it) } }
                trailing?.invoke()
            }
        }
        NovelRule()
    }
}

@Composable internal fun NovelSection(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        NovelHeading(title, Modifier.weight(1f), 21)
        if (action != null) NovelLink(action, onAction)
    }
}

@Composable internal fun <T> NovelLoadState(load: NovelLoad<T>, empty: Boolean, emptyTitle: String, onRetry: () -> Unit) {
    val c = NovelTheme.colors
    if (load.loading) Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(25.dp), color = c.primary, strokeWidth = 2.dp) }
    if (load.error != null) Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(load.error, color = c.error, fontSize = 13.sp); NovelPill("重新加载", onRetry, primary = false)
    } else if (!load.loading && empty) Column(Modifier.fillMaxWidth().padding(vertical = 52.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(NovelIcons.BookOpen, null, Modifier.size(34.dp), tint = c.textSecondary)
        NovelHeading(emptyTitle, size = 19)
        Text("新的故事，正在路上", color = c.textSecondary, fontSize = 12.sp)
    }
}

@Composable internal fun NovelMetric(value: Long, label: String, modifier: Modifier = Modifier, delta: Double? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(value.novelNumber(), color = NovelTheme.colors.text, fontSize = 27.sp, fontWeight = FontWeight.Medium)
        Text(label, color = NovelTheme.colors.textSecondary, fontSize = 12.sp)
        if (delta != null) Text("较上周 ${if (delta > 0) "+" else ""}${delta.novelDelta()}%", color = NovelTheme.colors.primary, fontSize = 11.sp)
    }
}

internal fun Long.novelNumber(): String = NumberFormat.getIntegerInstance().format(this)
private fun Double.novelDelta(): String = if (this % 1.0 == 0.0) toLong().toString() else "%.1f".format(this)
internal fun Long.novelDate(): String = if (this <= 0) "" else DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(this))
