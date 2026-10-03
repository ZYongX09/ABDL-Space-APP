# About ComponentActivity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the About Compose UI in an independent `ComponentActivity` matching the miuix demo host, without reducing blur or dynamic background behavior.

**Architecture:** `SettingsMainFragment` launches `ComposeAboutActivity` instead of pushing a ComposeView inside AppKit's fragment stack. The Activity owns edge-to-edge, theme providers, and Compose lifecycle. The existing `OpenSourceLicensesFragment` remains authoritative; About returns a navigation request to `MainActivity`, which opens the fragment through AppKit `Nav.go()`.

**Tech Stack:** Android `ComponentActivity`, Jetpack Compose, miuix 0.9.3, AppKit `Nav`, Android intents.

---

### Task 1: Build the demo-equivalent About Activity host

**Files:**
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/fragments/settings/ComposeAboutActivity.kt`
- Modify: `mastodon/src/main/AndroidManifest.xml`

- [ ] Replace the current `ComposeView`/Material3 wrapper with `ComponentActivity.setContent`, `enableEdgeToEdge`, `MiuixAppTheme`, `LocalAppState`, and `AboutPage`.
- [ ] Keep status/navigation bars transparent and disable navigation bar contrast enforcement on API 29+.
- [ ] Register the non-exported Activity in the manifest.
- [ ] Run `./gradlew :mastodon:compileDebugKotlin -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon`; expect `BUILD SUCCESSFUL`.

### Task 2: Switch navigation and preserve the existing license page

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/settings/SettingsMainFragment.java`
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/AboutPage.kt`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/MainActivity.java`

- [ ] Launch `ComposeAboutActivity` from `onAboutClick()` with the current account ID as an extra.
- [ ] Add an `onOpenSourceLicenses` callback to `AboutPage`; invoke it from the “开放源代码许可” preference instead of directly calling AppKit from Compose UI.
- [ ] In the Activity callback, launch/reuse `MainActivity` with an `open_source_licenses` flag and finish the About Activity.
- [ ] In both `MainActivity.onCreate()` and `onNewIntent()`, consume the flag once and call `Nav.go(this, OpenSourceLicensesFragment.class, account args)`.
- [ ] Preserve GPL-3.0 text/link and `isFullSize=false`; restore all other effects to demo behavior.

### Task 3: Build and device-verify the host hypothesis

**Files:**
- Verify: `mastodon/build/outputs/apk/debug/mastodon-debug.apk`

- [ ] Run `./gradlew :mastodon:assembleDebug -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon`; expect `BUILD SUCCESSFUL`.
- [ ] Install with `adb install -r mastodon/build/outputs/apk/debug/mastodon-debug.apk`.
- [ ] Open About and verify it is a separate Activity using `adb shell dumpsys activity top`.
- [ ] Confirm the Activity's ViewRoot is Compose-only with `adb shell dumpsys gfxinfo top.abdl_space.app.debug` and compare attached View count against the previous AppKit-hosted 506 views.
- [ ] Verify full status-bar background, normal back behavior, GPL-3.0, and navigation to the existing open-source license page.
