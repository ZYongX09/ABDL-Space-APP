/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.data

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import org.joinmastodon.android.security.domain.BootSessionMarkerProvider

class AndroidBootSessionMarkerProvider internal constructor(
	private val bootCountReader: BootCountReader,
) : BootSessionMarkerProvider {
	constructor(context: Context) : this(SettingsBootCountReader(context.applicationContext))
	override fun marker(): String {
		val bootCount = try {
			bootCountReader.readBootCount()
		} catch (error: BootSessionMarkerUnavailableException) {
			throw error
		} catch (error: CancellationException) {
			throw error
		} catch (_: Exception) {
			throw BootSessionMarkerUnavailableException()
		}
		if (bootCount < 0) throw BootSessionMarkerUnavailableException()
		return bootCount.toString()
	}
}

fun interface BootCountReader {
	@Throws(BootSessionMarkerUnavailableException::class)
	fun readBootCount(): Int
}

class BootSessionMarkerUnavailableException : Exception("Boot session marker is unavailable")

private class SettingsBootCountReader(
	context: Context,
) : BootCountReader {
	private val contentResolver = context.contentResolver

	override fun readBootCount(): Int = try {
		Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT)
	} catch (_: Settings.SettingNotFoundException) {
		throw BootSessionMarkerUnavailableException()
	} catch (_: SecurityException) {
		throw BootSessionMarkerUnavailableException()
	} catch (_: IllegalStateException) {
		throw BootSessionMarkerUnavailableException()
	}
}
