package org.joinmastodon.android.ui.compose.friendcards

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.compose.navigation.WithIsolatedGlassShaderCache
import org.joinmastodon.android.ui.compose.navigation.liquid.iosIndicatorSpecular
import org.joinmastodon.android.ui.compose.navigation.liquid.lens
import org.joinmastodon.android.ui.compose.navigation.liquid.vibrancy
import org.joinmastodon.android.ui.compose.navigation.rememberIsolatedGlassHighlight
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop

@Composable
internal fun FriendCardDetailControls(
    backdrop: Backdrop?,
    enabled: Boolean,
    onClose: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        DetailControlSurface(backdrop, true, "关闭", onClose, circle = true) {
            FriendCardResourceIcon(R.drawable.ic_fluent_dismiss_24_regular, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        DetailControlSurface(backdrop, enabled, "管理我的扩列卡", onManage, circle = false) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FriendCardResourceIcon(R.drawable.ic_friend_card, tint = Color.White, modifier = Modifier.size(24.dp))
                Text("管理我的扩列卡", fontSize = 13.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun DetailControlSurface(
    backdrop: Backdrop?,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
    circle: Boolean,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(.72f, 520f), label = "friendCardControlPress")
    val shape = if (circle) CircleShape else RoundedCornerShape(24.dp)
    val highlight = rememberIsolatedGlassHighlight(iosIndicatorSpecular, extraDegrees = -45f)
    WithIsolatedGlassShaderCache {
        Box(Modifier.heightIn(min = 44.dp).then(if (circle) Modifier.width(44.dp) else Modifier.widthIn(max = 240.dp))
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .45f }
            .then(if (backdrop != null) Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = { vibrancy(); blur(18.dp.toPx(), 18.dp.toPx()); lens(12.dp.toPx(), 16.dp.toPx()) },
                highlight = { highlight.value.copy(alpha = .75f) },
                onDrawSurface = { drawRect(Color.White.copy(alpha = .16f)) },
            ) else Modifier.background(Color.White.copy(alpha = .14f), shape))
            .clip(shape).clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.padding(horizontal = if (circle) 0.dp else 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center) { content() }
    }
}
