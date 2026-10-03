package org.joinmastodon.android.ui.compose

import android.app.Activity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

fun Activity.installComposeLifecycle() {
    val view = window.decorView
    view.setViewTreeLifecycleOwner(this as LifecycleOwner)
    view.setViewTreeSavedStateRegistryOwner(this as SavedStateRegistryOwner)
}
