/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import org.joinmastodon.android.R
import org.joinmastodon.android.security.ui.SecurityGraph
import org.joinmastodon.android.security.ui.SecurityGraphFactory

/**
 * Fail-closed app gate. Every first-party activity is protected by default; only [LockActivity]
 * itself is exempt. When the session is expired, a non-interactive shield is installed over the
 * activity's decor before its content can be touched, and a single [LockActivity] (guarded by
 * [lockInFlight]) is launched over the task. A duplicate launch during an in-flight one is a no-op.
 */
class AppGatekeeper(
	private val application: Application,
	private val authTracker: AuthTracker,
) : Application.ActivityLifecycleCallbacks {

	companion object {
		private val SHIELD_TAG = "abdl_security_shield"
	}

	private var lockInFlight = false


	fun install() {
		application.registerActivityLifecycleCallbacks(this)
	}

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		if (SecurityGraphFactory.isLockActivity(activity)) {
			lockInFlight = true
			return
		}
		if (activity.window.peekDecorView() != null) {
			installShield(activity)
		}
	}

	override fun onActivityStarted(activity: Activity) {}

	override fun onActivityResumed(activity: Activity) {
		if (SecurityGraphFactory.isLockActivity(activity)) return
		// The repository owns the final verdict; the repository call is quick (local prefs) and the
		// gatekeeper only runs on activity resume where a short blocking read is acceptable.
		val locked = kotlinx.coroutines.runBlocking { authTracker.shouldAuthenticate() == AuthenticationStatus.Expired }
		if (locked) {
			installShield(activity)
			launchLock(activity)
		} else {
			removeShield(activity)
		}
	}

	override fun onActivityPaused(activity: Activity) {}
	override fun onActivityStopped(activity: Activity) {}
	override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

	override fun onActivityDestroyed(activity: Activity) {
		removeShield(activity)
	}

	/** Called by [LockActivity] after a successful unlock. */
	fun onUnlocked(activity: Activity) {
		lockInFlight = false
		authTracker.onAuthenticated()
		removeShield(activity)
	}

	fun onLockFinishedWithoutSuccess() {
		lockInFlight = false
	}

	private fun launchLock(activity: Activity) {
		if (lockInFlight) return
		lockInFlight = true
		val intent = Intent(activity, SecurityGraphFactory.lockActivityClass()).apply {
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
		}
		activity.startActivity(intent)
		activity.overridePendingTransition(0, 0)
	}

	private fun installShield(activity: Activity) {
		val decor = activity.window.decorView as? ViewGroup ?: return
		if (decor.findViewWithTag<View>(SHIELD_TAG) != null) return
		val shield = FrameLayout(activity).apply {
			tag = SHIELD_TAG
			background = android.graphics.drawable.ColorDrawable(
				ContextCompat.getColor(activity, R.color.gray_50),
			)
			isClickable = true
			isFocusable = true
			importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
		}
		decor.addView(
			shield,
			ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
			),
		)
		shield.setOnClickListener { launchLock(activity) }
	}

	private fun removeShield(activity: Activity) {
		val decor = activity.window.decorView as? ViewGroup ?: return
		decor.findViewWithTag<View>(SHIELD_TAG)?.let { decor.removeView(it) }
	}
}
