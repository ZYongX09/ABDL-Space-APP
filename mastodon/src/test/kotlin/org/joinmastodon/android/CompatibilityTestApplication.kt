package org.joinmastodon.android

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import org.robolectric.Shadows.shadowOf

class CompatibilityTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // createComposeRule launches this host, but ui-test-manifest is debug-only.
        // Register it in Robolectric before JUnit rules launch the activity, without
        // adding a test activity or dependency to the production release manifest.
        val shadowPackageManager = shadowOf(packageManager)
        val activity = shadowPackageManager.addActivityIfNotPresent(
            ComponentName(this, ComponentActivity::class.java),
        )
        activity.enabled = true
        activity.exported = true
        activity.theme = android.R.style.Theme_Material_Light_NoActionBar
        shadowPackageManager.addOrUpdateActivity(activity)
    }
}
