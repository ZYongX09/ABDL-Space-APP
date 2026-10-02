package org.joinmastodon.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.joinmastodon.android.fragments.settings.OpenSourceLicensesFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = CompatibilityTestApplication::class)
class OpenSourceLicensesPageTest {
	@Test
	fun actualPageInflatesCorrectedRowsAndLocalizedFooter() {
		withPage { activity, fragment, page ->
			assertEquals(activity.getString(R.string.open_source_licenses), fragment.title.toString())
			val rows = rows(page)
			assertTrue(rows.size > 40)
			assertEquals("1.4.8, Unlicense", summary(rows, "AppKit (grishka)"))
			assertEquals("1.2.0-beta01, Apache-2.0", summary(rows, "LiteX SwipeRefreshLayout"))
			assertEquals("1.1.0, Apache-2.0", summary(rows, "LiteX Concurrent"))
			assertEquals("2.8.3, Apache-2.0", summary(rows, "AndroidX Room"))
			assertEquals("2.6.1, Apache-2.0", summary(rows, "AndroidX SQLite"))
			assertEquals("1.1.0, Apache-2.0", summary(rows, "AndroidX Biometric"))
			assertEquals("1.0.2, Apache-2.0", summary(rows, "AndroidX NavigationEvent / NavigationEvent Compose"))
			assertEquals("4.1.1, MIT", summary(rows, "MaterialKolor Material Color Utilities (Kotlin port)"))
			assertTrue(rows.all { it.isClickable })
			assertFalse(rows.any { name(it) == "AndroidX Room / SQLite" })
			val footer = page.findViewById<TextView>(R.id.footer_note)
			assertEquals(activity.getString(R.string.third_party_licenses_footer), footer.text.toString())
		}
	}

	@Test
	fun clickingAnActualRowOpensItsUpstreamHttpsUrl() {
		withPage { activity, _, page ->
			val appkit = rows(page).single { name(it) == "AppKit (grishka)" }
			assertTrue(appkit.performClick())
			val intent = shadowOf(activity).nextStartedActivity
			assertNotNull(intent)
			assertEquals(Intent.ACTION_VIEW, intent.action)
			assertEquals("https://github.com/grishka/appkit", intent.data.toString())
		}
	}

	@Test
	fun clickingWithoutAnAvailableBrowserShowsFeedback() {
		withPage { activity, fragment, page ->
			ShadowToast.reset()
			fragment.browserUnavailable = true
			assertTrue(rows(page).first().performClick())
			assertEquals(activity.getString(R.string.no_app_to_handle_action), ShadowToast.getTextOfLatestToast())
		}
	}

	private fun rows(page: View): List<View> {
		val container = page.findViewById<ViewGroup>(R.id.list_container)
		return (0 until container.childCount).map { container.getChildAt(it) }
	}

	private fun name(row: View) = row.findViewById<TextView>(R.id.lib_name).text.toString()
	private fun summary(rows: List<View>, name: String) =
		rows.single { this.name(it) == name }.findViewById<TextView>(R.id.lib_summary).text.toString()

	private fun withPage(block: (Activity, BrowserTestFragment, View) -> Unit) {
		val controller = Robolectric.buildActivity(Activity::class.java).create()
		val activity = controller.get()
		activity.setTheme(R.style.Theme_Mastodon_Light)
		val fragment = BrowserTestFragment()
		// Attach through FragmentManager so real Fragment.startActivity has an activity host.
		// Render the actual content without launching MainActivity/accounts.
		activity.fragmentManager.beginTransaction().add(fragment, "licenses").commit()
		activity.fragmentManager.executePendingTransactions()
		try {
			val page = fragment.onCreateContentView(LayoutInflater.from(activity), null, null)
			block(activity, fragment, page)
		} finally {
			controller.destroy()
		}
	}

	class BrowserTestFragment : OpenSourceLicensesFragment() {
		var browserUnavailable = false
		override fun startActivity(intent: Intent) {
			if (browserUnavailable) throw ActivityNotFoundException("No browser installed")
			super.startActivity(intent)
		}
	}
}
