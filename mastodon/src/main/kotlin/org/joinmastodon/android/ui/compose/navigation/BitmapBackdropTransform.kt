// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
package org.joinmastodon.android.ui.compose.navigation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.DefaultCameraDistance
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.unit.Density
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Matches Miuix LayerBackdrop's inverse layer transform for native bitmap consumers. */
internal class BitmapBackdropTransform : GraphicsLayerScope {
    override var size: Size = Size.Unspecified
    override var density = 1f
    override var fontScale = 1f
    override var scaleX = 1f
    override var scaleY = 1f
    override var alpha = 1f
    override var translationX = 0f
    override var translationY = 0f
    override var shadowElevation = 0f
    override var ambientShadowColor: Color = DefaultShadowColor
    override var spotShadowColor: Color = DefaultShadowColor
    override var rotationX = 0f
    override var rotationY = 0f
    override var rotationZ = 0f
    override var cameraDistance = DefaultCameraDistance
    override var transformOrigin = TransformOrigin.Center
    override var shape: Shape = RectangleShape
    override var clip = false
    override var renderEffect: RenderEffect? = null
    override var blendMode = BlendMode.SrcOver
    override var colorFilter: ColorFilter? = null
    override var compositingStrategy = CompositingStrategy.Auto

    fun DrawTransform.inverse(density: Density, consumerSize: Size, block: GraphicsLayerScope.() -> Unit) {
        this@BitmapBackdropTransform.size = consumerSize
        this@BitmapBackdropTransform.density = density.density
        fontScale = density.fontScale
        block()
        if (rotationZ == 0f) {
            if (scaleX != 0f && scaleY != 0f) scale(1f / scaleX, 1f / scaleY, Offset.Zero)
            return
        }
        val angle = rotationZ * PI / 180
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        val a = c * scaleX
        val b = s * scaleY
        val cc = -s * scaleX
        val d = c * scaleY
        val determinant = a * d - b * cc
        if (determinant == 0f) return
        val matrix = Matrix()
        matrix[0, 0] = d / determinant
        matrix[0, 1] = -b / determinant
        matrix[1, 0] = -cc / determinant
        matrix[1, 1] = a / determinant
        transform(matrix)
    }
}
