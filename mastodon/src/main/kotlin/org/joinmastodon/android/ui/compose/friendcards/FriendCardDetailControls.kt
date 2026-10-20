package org.joinmastodon.android.ui.compose.friendcards

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    contrast: FriendCardContrastSampler? = null,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        DetailControlSurface(backdrop, true, "关闭", onClose, circle = true,
            contrast = contrast, control = FriendCardContrastControl.Close,
            ink = contrast?.closeInk ?: FriendCardContrastInk.Unknown) { tint ->
            FriendCardResourceIcon(R.drawable.ic_fluent_dismiss_24_regular, tint = tint, modifier = Modifier.size(20.dp))
        }
        DetailControlSurface(backdrop, enabled, "管理我的扩列卡", onManage, circle = false,
            contrast = contrast, control = FriendCardContrastControl.Manage,
            ink = contrast?.manageInk ?: FriendCardContrastInk.Unknown) { tint ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FriendCardResourceIcon(R.drawable.ic_friend_card, tint = tint, modifier = Modifier.size(24.dp))
                Text("管理我的扩列卡", fontSize = 13.sp, color = tint)
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
    contrast: FriendCardContrastSampler?,
    control: FriendCardContrastControl,
    ink: FriendCardContrastInk,
    content: @Composable (Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(.72f, 520f), label = "friendCardControlPress")
    val shape = if (circle) CircleShape else RoundedCornerShape(24.dp)
    val darkInk = ink == FriendCardContrastInk.Black
    val tint by animateColorAsState(if (darkInk) Color(0xFF101416) else Color.White,
        tween(220), label = "friendCardControlTint")
    // Unknown/software/readback failure is deliberately protective, not a fabricated sampled colour.
    val surfaceTarget = when (ink) {
        FriendCardContrastInk.Unknown -> Color.Black.copy(alpha = .72f)
        FriendCardContrastInk.Black -> Color.White.copy(alpha = if (backdrop != null) .60f else .80f)
        FriendCardContrastInk.White -> Color.Black.copy(alpha = if (backdrop != null) .60f else .80f)
    }
    val surface by animateColorAsState(surfaceTarget, tween(220), label = "friendCardControlSurface")
    val edge by animateColorAsState((if (darkInk) Color.Black else Color.White).copy(alpha = .18f),
        tween(220), label = "friendCardControlEdge")
    val shadow by animateColorAsState((if (darkInk) Color.Black else Color(0xFF071013)).copy(alpha = if (darkInk) .30f else .58f),
        tween(220), label = "friendCardControlShadow")
    val highlight = rememberIsolatedGlassHighlight(iosIndicatorSpecular, extraDegrees = -45f)
    Box(Modifier.friendCardContrastBounds(contrast, control)) {
    WithIsolatedGlassShaderCache {
        Box(Modifier.heightIn(min = 44.dp).then(if (circle) Modifier.width(44.dp) else Modifier.widthIn(max = 240.dp))
            .graphicsLayer {
                scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .45f
                this.shape = shape; shadowElevation = 3.dp.toPx()
                ambientShadowColor = shadow; spotShadowColor = shadow
            }
            .then(if (backdrop != null) Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = { vibrancy(); blur(18.dp.toPx(), 18.dp.toPx()); lens(12.dp.toPx(), 16.dp.toPx()) },
                highlight = { highlight.value.copy(alpha = .75f) },
                onDrawSurface = { drawRect(surface) },
            ) else Modifier.background(surface, shape))
            .border(1.dp, edge, shape)
            .clip(shape).clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.padding(horizontal = if (circle) 0.dp else 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center) { content(tint) }
    }
    }
}
