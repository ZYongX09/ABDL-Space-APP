package org.joinmastodon.android.ui.compose.utils

import android.os.Build

fun supportsRuntimeGraphicsEffects(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= 33
