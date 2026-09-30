/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.joinmastodon.android.R
import org.joinmastodon.android.security.ui.lock.LockActivity
import org.joinmastodon.android.ui.utils.UiUtils

class AppGatekeeper(
	private val application: Application,
	private val authTracker: AuthTracker,
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
	mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
	private val lockLauncher: (Activity) -> Unit = { activity ->
		activity.startActivity(Intent(activity, LockActivity::class.java))
	},
) : Application.ActivityLifecycleCallbacks {
	private class ActivityState {
		var resumed = false
		var check: Job? = null
		val continuations = ArrayDeque<Runnable>()
		val accessibility = WeakHashMap<View, Int>()
	}

	private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
	private val activities = WeakHashMap<Activity, ActivityState>()
	private var lockActivity: WeakReference<Activity>? = null
	private var launchPending = false

	fun install() = application.registerActivityLifecycleCallbacks(this)

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		if (activity is LockActivity) {
			lockActivity = WeakReference(activity)
			launchPending = false
			return
		}
		activities.getOrPut(activity) { ActivityState() }
		if (!authTracker.isAccessKnownValid()) installShield(activity)
	}

	override fun onActivityStarted(activity: Activity) = Unit

	override fun onActivityResumed(activity: Activity) {
		if (activity is LockActivity) return
		val state = activities.getOrPut(activity) { ActivityState() }
		state.resumed = true
		checkAccess(activity, state)
	}

	override fun onActivityPaused(activity: Activity) {
		activities[activity]?.let {
			it.resumed = false
			it.check?.cancel()
			it.check = null
		}
	}

	override fun onActivityStopped(activity: Activity) = Unit
	override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

	override fun onActivityDestroyed(activity: Activity) {
		activities[activity]?.check?.cancel()
		removeShield(activity)
		activities.remove(activity)
		if (lockActivity?.get() === activity) {
			lockActivity = null
			launchPending = activity.isChangingConfigurations
		}
	}

	fun onUnlocked() {
		launchPending = false
		lockActivity = null
		authTracker.onAuthenticated()
		activities.entries.toList().forEach { (activity, state) ->
			if (state.resumed) checkAccess(activity, state)
		}
	}

	fun onLockFinishedWithoutSuccess() {
		launchPending = false
		lockActivity = null
		authTracker.onAuthenticateScreen()
		activities.values.forEach { it.continuations.clear() }
	}

	fun runAfterUnlock(activity: Activity, continuation: Runnable) {
		if (activity.isFinishing || activity.isDestroyed) return
		val state = activities.getOrPut(activity) { ActivityState() }
		state.continuations.addLast(continuation)
		if (!authTracker.isAccessKnownValid()) installShield(activity)
		if (state.resumed) checkAccess(activity, state)
	}

	private fun checkAccess(activity: Activity, state: ActivityState) {
		state.check?.cancel()
		if (!authTracker.isAccessKnownValid()) installShield(activity)
		state.check = scope.launch {
			val generation = authTracker.sessionGeneration()
			val status = try {
				withContext(ioDispatcher) { authTracker.shouldAuthenticate() }
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				AuthenticationStatus.Expired
			}
			if (activities[activity] !== state || !state.resumed || activity.isFinishing || activity.isDestroyed) return@launch
			if (generation != authTracker.sessionGeneration()) {
				checkAccess(activity, state)
				return@launch
			}
			if (status == AuthenticationStatus.Expired) {
				installShield(activity)
				launchLock(activity)
				return@launch
			}
			removeShield(activity)
			val callbacks = state.continuations.toList()
			state.continuations.clear()
			for (callback in callbacks) {
				if (activity.isFinishing || activity.isDestroyed) break
				callback.run()
			}
		}
	}

	private fun launchLock(activity: Activity) {
		if (launchPending || lockActivity?.get()?.let { !it.isFinishing && !it.isDestroyed } == true) return
		launchPending = true
		try {
			lockLauncher(activity)
		} catch (_: RuntimeException) {
			launchPending = false
		}
	}

	private fun installShield(activity: Activity) {
		val decor = activity.window.decorView as? ViewGroup ?: return
		val state = activities.getOrPut(activity) { ActivityState() }
		for (index in 0 until decor.childCount) {
			val child = decor.getChildAt(index)
			if (child.tag != SHIELD_TAG && !state.accessibility.containsKey(child)) {
				state.accessibility[child] = child.importantForAccessibility
				child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
			}
		}
		decor.findViewWithTag<View>(SHIELD_TAG)?.let { it.bringToFront(); return }
		val shield = FrameLayout(activity).apply {
			tag = SHIELD_TAG
			background = ColorDrawable(UiUtils.getThemeColor(activity, R.attr.colorM3Background))
			isClickable = true
			isFocusable = true
			filterTouchesWhenObscured = true
		}
		decor.addView(shield, ViewGroup.LayoutParams(-1, -1))
	}

	private fun removeShield(activity: Activity) {
		val decor = activity.window.decorView as? ViewGroup ?: return
		decor.findViewWithTag<View>(SHIELD_TAG)?.let(decor::removeView)
		activities[activity]?.accessibility?.let { original ->
			original.forEach { (view, importance) -> view.importantForAccessibility = importance }
			original.clear()
		}
	}

	companion object { internal const val SHIELD_TAG = "abdl_security_shield" }
}
