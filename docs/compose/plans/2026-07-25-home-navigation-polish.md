# Home Navigation Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move private messages to a permanent Home toolbar action, reduce the bottom Liquid Glass bar to five tabs, prevent drag/long-press conflicts, and synchronize the real-content backdrop with scrolling frames.

**Architecture:** Keep the existing AppKit child fragments and tab selection state machine. Conversations become a normal pushed destination from `HomeTabFragment`; the bottom bar owns only five persistent primary destinations. `BackdropCaptureFrameLayout` observes descendant invalidations and schedules at most one capture per display frame while content is changing, stopping automatically when invalidations stop.

**Tech Stack:** Java 17 Android Views/AppKit, Kotlin Compose, miuix blur, JUnit 4, Android Choreographer/View invalidation.

---

## Requirements

### [S1] Private message entry

The Home timeline toolbar always shows a private-message action. It opens `ConversationsFragment` with the active account and displays the unread conversation count badge.

### [S2] Five primary tabs

Remove private messages from both Liquid Glass and legacy `tab_bar.xml`. Keep Home, Search, Diaper, Friend Requests, and Profile. Label the first Liquid Glass item `首页`.

### [S3] Gesture arbitration

Dragging the selected glass indicator must never trigger a long-click callback. Home has no long-click behavior. Search and Profile retain their existing long-click actions only when the pointer remains within touch slop and reaches the long-press timeout without beginning a drag.

### [S4] Backdrop frame synchronization

When descendants invalidate during RecyclerView scrolling, image crossfades, or other View animations, schedule one backdrop capture on the next animation frame. Coalesce repeated invalidations in the same frame and stop scheduling when content stops changing.

### [S5] Demo parity boundary

Match the demo's five-item density and click/drag behavior while preserving ABDL-specific icons, badges, AppKit fragments, and real View backdrop bridge. Do not migrate the whole Home pager or FAB architecture to Compose.

### [S6] Glass overflow space

The selected prism must remain fully visible at its maximum pressed scale. Add transparent top layout space without moving the visible navigation pill or changing bottom insets.

### [S7] Conversation page system bars

The conversation list has a standard back button, applies the status-bar inset to its toolbar, and applies the navigation-bar inset to list bottom padding. Chat list and detail use the explicitly supplied account scope.

### Task 1: Five-tab navigation mapping

**Covers:** [S2, S5]

**Files:**
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidNavigationView.kt`
- Modify: `mastodon/src/main/res/layout/tab_bar.xml`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java`
- Modify: `mastodon/src/test/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeNavigationTabsTest.kt`

- [ ] **Step 1: Change the mapping test to expect five tabs and no message tab**

```kotlin
assertArrayEquals(
    intArrayOf(
        R.id.tab_home,
        R.id.tab_search,
        R.id.tab_diaper,
        R.id.tab_friend_request,
        R.id.tab_profile,
    ),
    HomeNavigationTabs.ids,
)
assertEquals(0, HomeNavigationTabs.indexOf(R.id.tab_messages))
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:testDebugUnitTest --tests org.joinmastodon.android.ui.compose.navigation.HomeNavigationTabsTest -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon`

Expected: FAIL because `tab_messages` is still present and the array has six entries.

- [ ] **Step 3: Remove `tab_messages` from both navigation renderers**

In `HomeLiquidNavigationView.kt`, remove `R.id.tab_messages`, its `NavigationItem`, and `R.drawable.ic_mail_24px`. Use the literal user-visible label `首页` for index 0. Keep badge indices aligned: diaper index 2, profile index 4.

In `tab_bar.xml`, delete the complete `FrameLayout` whose id is `tab_messages`.

In `HomeFragment`, remove `conversationsFragment` creation, restoration, hide/show transaction entries, inset forwarding, `fragmentForTab` branch, and saved-state entry. Conversations are no longer retained as a Home child tab.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Step 2 command.

Expected: PASS.

### Task 2: Permanent toolbar private-message action and unread badge

**Covers:** [S1]

**Files:**
- Modify: `mastodon/src/main/res/menu/home_custom.xml`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/HomeTabFragment.java`
- Inspect/reuse: `mastodon/src/main/java/org/joinmastodon/android/chat/ui/ConversationsFragment.java`
- Inspect/reuse: `mastodon/src/main/java/org/joinmastodon/android/chat/ChatController.java`

- [ ] **Step 1: Add an always-visible message action**

Add before the overflow action:

```xml
<item
    android:id="@+id/messages_action"
    android:icon="@drawable/ic_mail_24px"
    android:title="私信"
    android:showAsAction="always" />
```

- [ ] **Step 2: Wire the action to conversations**

In `HomeTabFragment.onOptionsItemSelected`, handle `R.id.messages_action` with:

```java
Bundle args=new Bundle();
args.putString("account", accountID);
Nav.go(getActivity(), ConversationsFragment.class, args);
```

- [ ] **Step 3: Reuse the existing action-view badge pattern**

Create an action view for `messages_action` using the same toolbar action sizing/tint conventions already used by other badged actions. The click listener must call `onOptionsItemSelected(messagesAction)`. Load the account-scoped conversation summary through the existing `ChatController` and set the badge text to the total unread count, hiding it at zero. Refresh when the fragment is shown and on existing chat update events; do not add a second chat database or polling loop.

- [ ] **Step 4: Compile Java**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:compileDebugJavaWithJavac -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon`

Expected: `BUILD SUCCESSFUL`.

### Task 3: Drag-safe long-press handling

**Covers:** [S3, S5]

**Files:**
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/liquid/IosLiquidGlassNavigationBar.kt`
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/animation/DampedDragAnimation.kt`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java`

- [ ] **Step 1: Remove Home's debug long-click behavior**

Delete the `tab_home && BuildConfig.DEBUG` branch from `HomeFragment.onTabLongClick`. Search and Profile remain unchanged.

- [ ] **Step 2: Expose whether the active gesture moved beyond touch slop**

Extend `DampedDragAnimation` with a per-gesture boolean that is reset on down and set only after accumulated movement exceeds `viewConfiguration.touchSlop`. Expose it as a read-only property such as `val hasDragged: Boolean`.

- [ ] **Step 3: Replace overlapping `combinedClickable` handlers with one arbitration path**

Keep ordinary tab cells clickable, matching the demo. On the selected indicator, do not attach `combinedClickable` in addition to drag modifiers. Handle release and long-press from the same pointer gesture: a stationary release invokes the current tab click; a stationary timeout invokes `onItemLongClick` only for supported indices; any drag cancels both click and long-press. Ensure drag stop still commits the final target exactly once.

- [ ] **Step 4: Compile Kotlin**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:compileDebugKotlin -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon`

Expected: `BUILD SUCCESSFUL`.

### Task 4: Descendant-driven frame capture

**Covers:** [S4]

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/ui/views/BackdropCaptureFrameLayout.java`

- [ ] **Step 1: Add coalesced animation-frame scheduling**

Override `onDescendantInvalidated(View child, View target)`, call `super`, and when a capture listener is active and the container is not currently capturing, schedule one `postInvalidateOnAnimation()` guarded by a `captureFrameScheduled` boolean.

```java
@Override
public void onDescendantInvalidated(View child, View target){
    super.onDescendantInvalidated(child, target);
    scheduleCaptureFrame();
}
```

Clear the guard at the beginning of `dispatchDraw`. Do not schedule from invalidations caused by temporary drawable replacement while `capturing` is true.

- [ ] **Step 2: Stop work when capture is disabled or detached**

When `setCaptureListener(null)` is called or the view detaches, clear the scheduled flag. The design intentionally does not run a permanent frame callback; the next frame exists only if descendants continue invalidating.

- [ ] **Step 3: Compile Java and check formatting**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:compileDebugJavaWithJavac -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
git diff --check -- mastodon/src/main/java/org/joinmastodon/android/ui/views/BackdropCaptureFrameLayout.java
```

Expected: build success and no `git diff --check` output.

### Task 5: Full build and device regression

**Covers:** [S1, S2, S3, S4, S5]

**Files:**
- Verify all files above

- [ ] **Step 1: Run focused unit tests**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:testDebugUnitTest --tests org.joinmastodon.android.ui.compose.navigation.HomeNavigationTabsTest -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon`

Expected: all tests pass.

- [ ] **Step 2: Build the installable APK**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Install and cold-start**

Run:

```bash
adb install -r mastodon/build/outputs/apk/debug/mastodon-debug.apk
adb logcat -c
adb shell am force-stop top.abdl_space.app.debug
adb shell monkey -p top.abdl_space.app.debug -c android.intent.category.LAUNCHER 1
```

Expected: installation succeeds and the Home screen remains alive.

- [ ] **Step 4: Verify behavior on device**

Confirm five bottom items, first label `首页`, no private-message bottom item, permanent toolbar message icon with unread badge, message icon opens conversations, Home long press does nothing, Search/Profile stationary long press works, dragging never opens search/account switcher, and fast list scrolling produces a backdrop that visually tracks the list rather than updating in low-frequency jumps.

- [ ] **Step 5: Verify crash logs and process survival**

Run:

```bash
adb shell input swipe 360 1250 360 350 450
adb shell input swipe 360 1250 360 350 450
sleep 10
adb shell pidof top.abdl_space.app.debug
adb logcat -d -v threadtime | rg -n -C 3 'Process: top\.abdl_space\.app\.debug|Software rendering doesn.t support hardware bitmaps|BackdropCaptureFrameLayout'
```

Expected: ABDL PID remains present and the filtered log has no matches.

- [ ] **Step 6: Commit only related files**

Stage only files listed in Tasks 1-4 and commit with:

```bash
git commit -m "fix(ui): 完善首页液态导航交互与私信入口"
```

### Task 6: Prism overflow and conversation safe areas

**Covers:** [S6, S7]

**Files:**
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/liquid/IosLiquidGlassNavigationBar.kt`
- Modify: `mastodon/src/main/res/layout/fragment_conversations.xml`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/chat/ui/ConversationsFragment.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/chat/ui/ChatFragment.java`

- [ ] **Step 1: Add transparent prism overflow space**

Add `12.dp` top padding outside the positioned glass `Box`, and compensate inside with the same negative visual displacement or reduced outer top position so the visible 64dp pill stays at its current screen Y coordinate. The Compose root height grows by 12dp while the bar itself does not move down.

- [ ] **Step 2: Replace the conversation title with a toolbar**

Use a horizontal `LinearLayout` with id `toolbar`, a 48dp `ImageButton` id `back_btn` using `ic_arrow_back_24`, and a title `TextView`. Preserve the existing surface colors and 64dp base height.

- [ ] **Step 3: Apply WindowInsets in the conversation list**

Implement `WindowInsetsAwareFragment`, store base toolbar height and RecyclerView bottom padding, wire the back button to `getActivity().onBackPressed()`, increase toolbar height/padding by the top inset, and increase RecyclerView bottom padding by the stable bottom inset.

- [ ] **Step 4: Use the supplied account in chat detail**

In `ChatFragment.onCreate`, read `args.getString("account")` before falling back to the last active account.

- [ ] **Step 5: Build and install**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon`

Expected: `BUILD SUCCESSFUL`. Install when ADB reconnects and verify the prism top edge, conversation back button, status-bar clearance, and bottom list clearance.
