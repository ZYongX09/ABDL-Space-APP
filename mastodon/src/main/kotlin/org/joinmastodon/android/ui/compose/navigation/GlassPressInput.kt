package org.joinmastodon.android.ui.compose.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.joinmastodon.android.ui.compose.navigation.motion.GlassJelly

/**
 * Shared press channel between a glass surface and its Compose container. The leading toolbar
 * glass is dispatched by the full-screen touch host, so its events have to be forwarded here;
 * the trailing and compose surfaces feed it from their own pointerInput.
 */
internal class GlassPressInput {
	val jelly = GlassJelly()
	var tick by mutableIntStateOf(0)
	var request by mutableIntStateOf(0)
	var downGeneration by mutableIntStateOf(0)

	val needsFrame: Boolean get() = !jelly.isSettled

	fun down(x: Float, y: Float) {
		jelly.down(x, y)
		downGeneration++
		request++
	}

	fun move(x: Float, y: Float) {
		jelly.move(x, y)
		request++
	}

	fun release() {
		jelly.release()
		request++
	}

	fun releaseDrag() {
		jelly.releaseDrag()
		request++
	}
}
