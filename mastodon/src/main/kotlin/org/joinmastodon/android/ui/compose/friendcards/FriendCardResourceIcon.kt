package org.joinmastodon.android.ui.compose.friendcards

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun FriendCardResourceIcon(@DrawableRes resource: Int, tint: Color, modifier: Modifier = Modifier) {
    AndroidView(factory = { context -> ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    } }, update = { image ->
        if (image.tag != resource) { image.setImageResource(resource); image.tag = resource }
        val color = tint.toArgb()
        if (image.imageTintList?.defaultColor != color) image.imageTintList = ColorStateList.valueOf(color)
    }, modifier = modifier)
}
