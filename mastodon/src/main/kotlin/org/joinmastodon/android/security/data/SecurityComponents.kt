/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.data

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import org.joinmastodon.android.security.domain.AndroidElapsedRealtimeProvider

object SecurityComponents {
	@Volatile
	private var repository: SecurityRepository? = null

	fun createRepository(context: Context): SecurityRepository {
		val applicationContext = context.applicationContext
		requireMainProcess(applicationContext)
		repository?.let { return it }
		return synchronized(this) {
			repository ?: SecurityRepositoryImpl(
				store = EncryptedPreferencesSecurityStore(applicationContext),
				pinCipher = AndroidKeystorePinCipher(applicationContext.packageName),
				elapsedRealtimeProvider = AndroidElapsedRealtimeProvider,
				bootSessionMarkerProvider = AndroidBootSessionMarkerProvider(applicationContext),
			).also { repository = it }
		}
	}

	private fun requireMainProcess(context: Context) {
		val processName = currentProcessName(context)
		check(isMainProcess(context.packageName, processName)) {
			"Security repository is available only in the main process"
		}
	}

	internal fun isMainProcess(packageName: String, processName: String?): Boolean =
		packageName == processName

	private fun currentProcessName(context: Context): String? {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			Application.getProcessName()?.let { return it }
		}
		val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
		return activityManager?.runningAppProcesses
			?.firstOrNull { it.pid == Process.myPid() }
			?.processName
	}
}
