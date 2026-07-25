# iOS-like Liquid Navigation Implementation Plan

> [!NOTE]
> This document may not reflect the current implementation.
> See the final report for up-to-date state:
> [Final Report](../reports/ios-liquid-navigation.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the ABDL Space home screen bottom bar with the demo's full iOS-like liquid glass interaction inside a `ComposeView`, enabled by default and switchable in Display settings.

**Architecture:** Keep all six existing AppKit child fragments and `HomeFragment` tab behavior unchanged. A focused Kotlin bridge renders the copied liquid navigation in a bottom `ComposeView`, using a theme-colored Compose backdrop because a sibling Android View tree cannot be captured by miuix `LayerBackdrop`; disabling the preference inflates the existing `tab_bar.xml` unchanged.

**Tech Stack:** Java 17, Kotlin 2.4.10, Android Views/AppKit, Jetpack Compose, miuix-ui/miuix-blur 0.9.3, Otto events, SharedPreferences.

---

### Task 1: Copy liquid glass building blocks

**Files:**
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/animation/DampedDragAnimation.kt`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/animation/InteractiveHighlight.kt`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/liquid/CombinedBackdrop.kt`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/liquid/InnerShadow.kt`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/liquid/Lens.kt`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/liquid/Vibrancy.kt`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/liquid/IosLiquidGlassNavigationBar.kt`

- [ ] **Step 1: Copy the Apache-2.0 demo sources without behavior changes**

Copy the seven source files from `/home/ZYongX/projects/miuix/example/shared/src/commonMain/kotlin/component/{animation,liquid}/`, change only package/import paths to `org.joinmastodon.android.ui.compose.navigation.*`, and preserve the copyright/SPDX/adaptation notices.

- [ ] **Step 2: Expose the navigation Composable to the app bridge**

Change only its visibility and name boundary:

```kotlin
@Composable
internal fun IosLiquidGlassNavigationBar(
    items: List<NavigationItem>,
    selectedIndex: Int,
    onItemClick: (Int) -> Unit,
    backdrop: LayerBackdrop?,
    isBlurActive: Boolean,
    modifier: Modifier = Modifier,
    badge: (Int) -> (@Composable () -> Unit)? = { null },
)
```

- [ ] **Step 3: Compile Kotlin to catch package/API drift**

Run: `./gradlew :mastodon:compileDebugKotlin -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon`

Expected: `BUILD SUCCESSFUL`; no unresolved demo package imports.

### Task 2: Add the View-to-Compose home navigation bridge

**Files:**
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidNavigationView.kt`
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/MiuixAppTheme.kt`

- [ ] **Step 1: Define the stable six-tab contract**

Create `HomeLiquidNavigationView.kt` with integer tab IDs in this order:

```kotlin
private val tabIds = intArrayOf(
    R.id.tab_home,
    R.id.tab_search,
    R.id.tab_diaper,
    R.id.tab_messages,
    R.id.tab_friend_request,
    R.id.tab_profile,
)
```

Expose a Java-friendly factory accepting the selected ID, avatar URL, badge values, click callback, and long-click callback. Keep mutable values in Compose state so Java can update selection and badges without recreating the entire fragment.

- [ ] **Step 2: Build the theme-colored backdrop**

Inside the `ComposeView`, provide the existing `LocalAppState` and `MiuixAppTheme`, create one `rememberLayerBackdrop()`, and record a theme surface gradient behind the liquid bar:

```kotlin
Box(Modifier.fillMaxWidth().layerBackdrop(backdrop)) {
    Box(
        Modifier
            .matchParentSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
                        MiuixTheme.colorScheme.surface.copy(alpha = 0.92f),
                    ),
                ),
            ),
    )
    IosLiquidGlassNavigationBar(
        items = items,
        selectedIndex = selectedIndex,
        onItemClick = { onTabSelected(tabIds[it]) },
        backdrop = backdrop,
        isBlurActive = true,
        badge = ::badgeForIndex,
    )
}
```

- [ ] **Step 3: Adapt app resources to Compose navigation items**

Use `painterResource` for the five drawable tabs and an async/profile image Composable for the avatar slot. Preserve Chinese accessibility labels from app resources, unread count badge text, and the paper-diaper `新功能` badge.

- [ ] **Step 4: Preserve long-press behavior**

Add long-click handling to each tab item through `combinedClickable` or a focused optional callback in the copied navigation component. Long-press must pass the original resource ID to Java, so Search and Profile retain their existing actions.

- [ ] **Step 5: Compile the bridge**

Run: `./gradlew :mastodon:compileDebugKotlin -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon`

Expected: `BUILD SUCCESSFUL`.

### Task 3: Add the default-on display preference

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/GlobalUserPreferences.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/settings/SettingsDisplayFragment.java`
- Modify: `mastodon/src/main/res/values/strings.xml`
- Modify: `mastodon/src/main/res/values-zh-rCN/strings.xml`

- [ ] **Step 1: Add persisted global preference**

Add:

```java
public static boolean useIosLiquidNavigation;
```

Load with a default of `true`:

```java
useIosLiquidNavigation=prefs.getBoolean("useIosLiquidNavigation", true);
```

Save with:

```java
.putBoolean("useIosLiquidNavigation", useIosLiquidNavigation)
```

- [ ] **Step 2: Add strings**

Add English fallback and Simplified Chinese:

```xml
<string name="settings_ios_liquid_navigation">iOS-style bottom navigation</string>
<string name="settings_ios_liquid_navigation_summary">Use the floating liquid glass navigation bar on the home screen</string>
```

```xml
<string name="settings_ios_liquid_navigation">iOS 风格底部导航栏</string>
<string name="settings_ios_liquid_navigation_summary">在主界面使用悬浮液态玻璃导航栏</string>
```

- [ ] **Step 3: Add the Display settings switch**

Add a `CheckableListItem` initialized from `GlobalUserPreferences.useIosLiquidNavigation`. In `onHidden()`, copy its checked state back, call `GlobalUserPreferences.save()`, then post the existing `StatusDisplaySettingsChangedEvent`.

- [ ] **Step 4: Compile Java and resources**

Run: `./gradlew :mastodon:compileDebugJavaWithJavac -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon`

Expected: `BUILD SUCCESSFUL`.

### Task 4: Integrate dual-mode navigation in HomeFragment

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java`
- Modify: `mastodon/src/main/res/layout/tab_bar.xml`

- [ ] **Step 1: Extract existing View bar setup without changing behavior**

Move current inflate/listener/avatar/badge initialization into `createClassicTabBar(LayoutInflater)`. Keep `tab_bar.xml` as the fallback implementation and do not rewrite it.

- [ ] **Step 2: Add Compose bar creation**

When `GlobalUserPreferences.useIosLiquidNavigation` is true, create the Kotlin bridge `ComposeView`, attach it below the fragment container, and wire callbacks to `onTabSelected` and `onTabLongClick`. When false, use `createClassicTabBar`.

- [ ] **Step 3: Synchronize navigation state**

Update all existing selection paths to call one helper:

```java
private void selectTabInNavigation(@IdRes int tab){
    if(liquidNavigationController!=null)
        liquidNavigationController.setSelectedTab(tab);
    else
        tabBar.selectTab(tab);
}
```

Use it from `onCreateView`, `onViewStateRestored`, `setCurrentTab`, Search long-press, and any direct `tabBar.selectTab` call.

- [ ] **Step 4: Synchronize avatar and badges**

Route `updateUnreadNotificationsBadge`, `updateDiaperNewFeatureBadge`, and `markDiaperFeatureSeen` to either classic Views or the Compose controller. Keep the current classic behavior byte-for-byte when the preference is off.

- [ ] **Step 5: Preserve system insets**

For the Compose bar, send the bottom system inset into the bridge and let the Composable own its bottom spacing. For the classic bar, retain the current `tabBarWrap.setPadding` logic.

- [ ] **Step 6: Apply setting changes immediately**

In `onStatusDisplaySettingsChanged`, detect whether the desired bar mode differs from the active mode. Recreate only the navigation host while preserving `currentTab` and all child fragments.

- [ ] **Step 7: Compile Java/Kotlin integration**

Run: `./gradlew :mastodon:compileDebugKotlin :mastodon:compileDebugJavaWithJavac -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon`

Expected: `BUILD SUCCESSFUL`.

### Task 5: Add license attribution and verify end to end

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/settings/OpenSourceLicensesFragment.java`

- [ ] **Step 1: Add AndroidLiquidGlass attribution**

Add:

```java
new OpenSourceLibrary(
    "AndroidLiquidGlass",
    "Apache 2.0",
    "https://github.com/Kyant0/AndroidLiquidGlass",
    "iOS 风格液态玻璃底部导航栏的折射、拖动和高光效果参考实现"
),
```

- [ ] **Step 2: Run static checks**

Run: `git diff --check`

Expected: no output and exit code 0.

- [ ] **Step 3: Build installable debug APK**

Run: `./gradlew :mastodon:assembleDebug -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon`

Expected: `BUILD SUCCESSFUL`; APK at `mastodon/build/outputs/apk/debug/mastodon-debug.apk`.

- [ ] **Step 4: Perform focused device verification**

Verify on the connected device:

1. Fresh/default preference shows the iOS-like bar.
2. Tap and drag among all six tabs; selected fragment and indicator stay synchronized.
3. Re-tap Home/Search behavior, Search long-press, and Profile long-press remain intact.
4. Profile avatar, unread badge, and diaper `新功能` badge render correctly.
5. Light/dark themes and gesture/three-button navigation Insets do not overlap.
6. Turn the Display switch off: classic bar returns immediately and survives restart.
7. Turn it on: liquid bar returns immediately and survives restart.

- [ ] **Step 5: Capture performance evidence**

Run `adb shell dumpsys gfxinfo top.abdl_space.app.debug reset`, exercise the six tabs for 30 seconds, then run `adb shell dumpsys gfxinfo top.abdl_space.app.debug`. Record frame percentiles and device thermal status; compare with the classic-bar mode to catch persistent GPU/sensor regressions.
