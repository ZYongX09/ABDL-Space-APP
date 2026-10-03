# Friend Universe Liquid Glass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Friend Universe home tab a Miuix large-title toolbar, real liquid-glass search/publish actions, and working debounced server search without rewriting its Java list.

**Architecture:** Keep `FriendRequestListFragment` as the source of list, paging, and request state. Add a focused Kotlin controller that renders the title and liquid actions over the existing shared bitmap backdrop, then let `HomeFragment` select the home or friend toolbar for the active tab. A small pure Kotlin model owns interpolation and search-generation rules so behavior is unit tested without Compose UI tests.

**Tech Stack:** Java 21, Kotlin 2.4, Jetpack Compose, miuix-ui/miuix-blur, AppKit fragments, RecyclerView, JUnit 4.

---

### Task 1: Define Large-Title And Search State

**Covers:** [S3, S5, S8]

**Files:**
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/FriendUniverseToolbarModel.kt`
- Create: `mastodon/src/test/kotlin/org/joinmastodon/android/ui/compose/navigation/FriendUniverseToolbarModelTest.kt`

- [ ] **Step 1: Write the failing state-model tests**

```kotlin
package org.joinmastodon.android.ui.compose.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendUniverseToolbarModelTest {
    @Test fun largeTitleProgressClampsToScrollRange() {
        assertEquals(0f, friendUniverseCollapseProgress(-8), 0.001f)
        assertEquals(0.5f, friendUniverseCollapseProgress(36), 0.001f)
        assertEquals(1f, friendUniverseCollapseProgress(100), 0.001f)
    }

    @Test fun toolbarMetricsInterpolateFromMiuixLargeTitle() {
        assertEquals(32f, friendUniverseTitleSizeSp(0f), 0.001f)
        assertEquals(18f, friendUniverseTitleSizeSp(1f), 0.001f)
        assertEquals(128, friendUniverseTopPaddingDp(liquidMode = true))
        assertEquals(0, friendUniverseTopPaddingDp(liquidMode = false))
    }

    @Test fun onlyLatestSearchGenerationMayApply() {
        assertTrue(friendUniverseMayApplySearch(requestGeneration = 3, currentGeneration = 3))
        assertFalse(friendUniverseMayApplySearch(requestGeneration = 2, currentGeneration = 3))
    }
}
```

- [ ] **Step 2: Run the model test and verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :mastodon:testDebugUnitTest --tests org.joinmastodon.android.ui.compose.navigation.FriendUniverseToolbarModelTest --no-daemon`

Expected: compilation fails because the `friendUniverse*` functions do not exist.

- [ ] **Step 3: Implement the minimal pure model**

```kotlin
package org.joinmastodon.android.ui.compose.navigation

import androidx.compose.ui.util.lerp

internal const val FRIEND_UNIVERSE_COLLAPSE_RANGE_PX = 72f

internal fun friendUniverseCollapseProgress(scrollY: Int): Float =
    (scrollY / FRIEND_UNIVERSE_COLLAPSE_RANGE_PX).coerceIn(0f, 1f)

internal fun friendUniverseTitleSizeSp(progress: Float): Float =
    lerp(32f, 18f, progress.coerceIn(0f, 1f))

internal fun friendUniverseTopPaddingDp(liquidMode: Boolean): Int = if (liquidMode) 128 else 8

internal fun friendUniverseCaptureHeightDp(searchExpanded: Boolean): Int = if (searchExpanded) 144 else 128

internal fun friendUniverseMayApplySearch(requestGeneration: Int, currentGeneration: Int): Boolean =
    requestGeneration == currentGeneration
```

- [ ] **Step 4: Run the model test and verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :mastodon:testDebugUnitTest --tests org.joinmastodon.android.ui.compose.navigation.FriendUniverseToolbarModelTest --no-daemon`

Expected: `BUILD SUCCESSFUL` with all three tests passing.

- [ ] **Step 5: Commit the model and tests**

```bash
git add mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/FriendUniverseToolbarModel.kt mastodon/src/test/kotlin/org/joinmastodon/android/ui/compose/navigation/FriendUniverseToolbarModelTest.kt
git commit -m "test(friend): 定义液态大标题与搜索状态"
```

### Task 2: Build The Friend Liquid Toolbar Controller

**Covers:** [S2, S3, S4, S5, S6]

**Files:**
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/FriendUniverseLiquidToolbarView.kt`
- Reuse: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/ViewBitmapBackdrop.kt`
- Reuse: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/liquid/`

- [ ] **Step 1: Add a Java-callable controller shell**

Create `FriendUniverseLiquidToolbarController` with `ComposeView(ViewCompositionStrategy.DisposeOnDetachedFromWindow)`, a `ViewBitmapBackdrop`, and these methods:

```kotlin
class FriendUniverseLiquidToolbarController(
    context: Context,
    private val onSearchChanged: Consumer<String>,
    private val onPublish: Runnable,
) {
    val view: View
    fun setBackdropBitmap(bitmap: Bitmap)
    fun setStatusBarInset(insetPx: Int)
    fun setScrollY(scrollY: Int)
    fun closeSearch(): Boolean
    fun dispose()
}
```

The Compose root must use `CompositionLocalProvider(LocalAppState provides AppState())` and `MiuixAppTheme`, matching `HomeLiquidToolbarController`.

- [ ] **Step 2: Render the Miuix large title**

Use `friendUniverseCollapseProgress(scrollYState)` and `friendUniverseTitleSizeSp(progress)` to render “交友宇宙”. Keep the expanded baseline below the status bar and interpolate upward into an 18sp compact title. Apply `graphicsLayer`/offset interpolation directly from scroll state so dragging and fling do not wait for a separate settle animation.

- [ ] **Step 3: Render real liquid search and publish controls**

Reuse the home toolbar's `drawBackdrop`, `blur`, `lens`, `vibrancy`, `iosIndicatorSpecular`, `rememberGravityRotatedHighlight`, and outline treatment. Search and publish start as separate 48dp circular glass controls using existing selector/vector drawables through `AndroidView(ImageView)`.

The publish control calls `onPublish.run()`. The search control toggles `searchExpandedState`; expanded content uses one morphing glass container with a `BasicTextField`, clear icon, and close icon. Use `LaunchedEffect(searchTextState)` with `delay(350)` before `onSearchChanged.accept(searchTextState.trim())`; suppress the initial empty callback until the user opens search.

- [ ] **Step 4: Implement close and disposal behavior**

`closeSearch()` returns false when already closed; otherwise it clears focus, collapses the field, emits an empty query when needed, and returns true. `dispose()` clears callback-facing state, disposes the composition, and removes all children/references held by the controller host.

- [ ] **Step 5: Compile the controller**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :mastodon:compileDebugKotlin --no-daemon`

Expected: `BUILD SUCCESSFUL` with no unresolved miuix or Compose symbols.

- [ ] **Step 6: Commit the toolbar controller**

```bash
git add mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/FriendUniverseLiquidToolbarView.kt
git commit -m "feat(friend): 添加液态大标题工具栏"
```

### Task 3: Connect Java List State And Working Search

**Covers:** [S2, S3, S4, S5, S7]

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/FriendRequestListFragment.java:56-320`

- [ ] **Step 1: Expose toolbar callbacks and scroll state**

Add a nullable `FriendUniverseLiquidToolbarController` field and these focused methods:

```java
public void setLiquidToolbarController(FriendUniverseLiquidToolbarController controller){
    liquidToolbarController=controller;
    updateLiquidMode();
    if(controller!=null)
        controller.setScrollY(Math.max(0, recyclerView==null ? 0 : recyclerView.computeVerticalScrollOffset()));
}

public void onLiquidSearchChanged(String query){
    applySearch(query);
}

public void onLiquidPublish(){
    openCreateRequest();
}
```

The RecyclerView scroll listener must forward `Math.max(0, recyclerView.computeVerticalScrollOffset())` to the controller every scroll event.

- [ ] **Step 2: Centralize publish navigation and liquid fallback UI**

Move the existing FAB navigation body into `openCreateRequest()`. In `updateLiquidMode()`, hide the AppKit toolbar/menu and FAB only when the controller is attached, update RecyclerView top padding through `friendUniverseTopPaddingDp(liquid)`, and keep the original 8dp/FAB behavior when detached.

Move the fraud banner into the RecyclerView as adapter type `TYPE_BANNER` so it scrolls with content. Dismissing it updates the preference and removes that adapter row without touching request pagination indices.

- [ ] **Step 3: Add generation-safe debounced search application**

Implement `applySearch(String query)` as the only query transition:

```java
private void applySearch(String query){
    String normalized=query==null ? "" : query.trim();
    if(normalized.equals(currentSearch))
        return;
    currentSearch=normalized;
    searchGeneration++;
    currentPage=1;
    hasMore=true;
    loadingMore=false;
    data.clear();
    adapter.notifyDataSetChanged();
    loadData(searchGeneration);
}
```

Capture `requestGeneration` in both first-page and pagination callbacks. Before applying results, require `friendUniverseMayApplySearch(requestGeneration, searchGeneration)`. This prevents an older query from replacing a newer one. Down-to-refresh calls `loadData(searchGeneration)` and pagination carries the same generation.

- [ ] **Step 4: Preserve empty/error/loading behavior**

Keep the existing `LoaderFragment` progress and toast behavior. On search errors retain `currentSearch`; always clear `SwipeRefreshLayout.refreshing` and `loadingMore` for the current generation. Ignore stale callbacks without mutating current loading/result state.

- [ ] **Step 5: Compile Java and Kotlin integration**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :mastodon:compileDebugJavaWithJavac --no-daemon`

Expected: `BUILD SUCCESSFUL`; Java resolves the Kotlin controller and top-padding/model bridge functions.

- [ ] **Step 6: Commit list integration**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/fragments/FriendRequestListFragment.java
git commit -m "feat(friend): 接入液态工具栏与页内搜索"
```

### Task 4: Route The Shared Home Backdrop To The Active Toolbar

**Covers:** [S2, S4, S6]

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java:80-170, 560-663`

- [ ] **Step 1: Add the friend toolbar lifecycle**

Add `FriendUniverseLiquidToolbarController friendLiquidToolbarController`. Create it beside the existing home controller with callbacks `friendRequestFragment::onLiquidSearchChanged` and `friendRequestFragment::onLiquidPublish`. On view teardown, dispose it and detach it from `FriendRequestListFragment`.

- [ ] **Step 2: Select one toolbar per active tab**

Replace the home-only visibility branch with:

```java
boolean homeVisible=GlobalUserPreferences.useIosLiquidNavigation && currentTab==R.id.tab_home;
boolean friendVisible=GlobalUserPreferences.useIosLiquidNavigation && currentTab==R.id.tab_friend_request;
homeToolbarView.setVisibility(homeVisible ? View.VISIBLE : View.GONE);
friendToolbarView.setVisibility(friendVisible ? View.VISIBLE : View.GONE);
```

Do not overlay both controllers at once. Switching away closes the home menu or friend search as appropriate. `onBackPressed()` first closes the currently visible expanded toolbar state.

- [ ] **Step 3: Share capture bitmap and compute active capture height**

In the existing capture listener, send the top bitmap to whichever top controller is visible and continue sending the bottom bitmap to `HomeLiquidNavigationController`. Compute top capture height from `homeToolbarCaptureHeightDp(...)` for home, `friendUniverseCaptureHeightDp(searchExpanded)` for friend, and zero for other tabs.

- [ ] **Step 4: Forward insets and setting changes**

Forward `topSystemInset` to both controllers. Recreate/destroy both when `useIosLiquidNavigation` changes, then call each child fragment's mode update so original Toolbar/FAB return immediately in fallback mode.

- [ ] **Step 5: Run focused unit tests and compilation**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :mastodon:testDebugUnitTest --tests org.joinmastodon.android.ui.compose.navigation.FriendUniverseToolbarModelTest --tests org.joinmastodon.android.ui.compose.navigation.HomeLiquidToolbarModelTest :mastodon:compileDebugJavaWithJavac --no-daemon`

Expected: `BUILD SUCCESSFUL`, all focused tests pass.

- [ ] **Step 6: Commit shared-host integration**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java
git commit -m "feat(friend): 共享首页真实玻璃背景"
```

### Task 5: Full Build And Device Verification

**Covers:** [S6, S7, S8]

**Files:**
- Verify: `mastodon/build/outputs/apk/debug/mastodon-debug.apk`

- [ ] **Step 1: Run formatting and focused tests**

Run: `git diff --check`

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :mastodon:testDebugUnitTest --tests org.joinmastodon.android.ui.compose.navigation.FriendUniverseToolbarModelTest --no-daemon`

Expected: no whitespace errors and all tests pass.

- [ ] **Step 2: Build the complete debug APK**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :mastodon:assembleDebug -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon`

Expected: `BUILD SUCCESSFUL` and APK at `mastodon/build/outputs/apk/debug/mastodon-debug.apk`.

- [ ] **Step 3: Commit after the successful build**

Project rules require a commit after every successful Android build. Stage only files from this plan and commit any final verification adjustment:

```bash
git add mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java mastodon/src/main/java/org/joinmastodon/android/fragments/FriendRequestListFragment.java mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/FriendUniverseLiquidToolbarView.kt mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/FriendUniverseToolbarModel.kt mastodon/src/test/kotlin/org/joinmastodon/android/ui/compose/navigation/FriendUniverseToolbarModelTest.kt docs/compose/plans/2026-07-28-friend-universe-liquid-glass.md
git commit -m "feat(friend): 完成交友宇宙液态玻璃改造"
```

If earlier task commits already contain every file and the tree has no plan-related changes, do not create an empty commit.

- [ ] **Step 4: Install and verify on device**

Run: `adb devices`

Run: `adb -s <device> install -r mastodon/build/outputs/apk/debug/mastodon-debug.apk`

Verify on the Friend Universe tab:

1. Expanded title size and position match the miuix large-title visual language.
2. Slow drag and fling collapse/expand continuously without jumping.
3. Cards visibly refract through both glass action buttons.
4. Search expands, debounces, returns server-filtered results, clears, and ignores stale responses.
5. Publish opens `FriendRequestCreateFragment`; no duplicate FAB appears.
6. Pull-to-refresh, pagination, empty state, detail, edit/delete/report menus still work.
7. Light, dark, and Nord themes remain readable.
8. Disabling liquid navigation restores the original Toolbar and FAB.

- [ ] **Step 5: Check runtime crashes**

Run: `adb -s <device> logcat -d -v threadtime | rg -A 50 -B 5 'FATAL EXCEPTION|Process: top.abdl_space.app.debug|FriendUniverseLiquidToolbar|FriendRequestListFragment'`

Expected: no target-process fatal exception during the verification path.
