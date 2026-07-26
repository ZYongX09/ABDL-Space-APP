# Home Liquid Glass Toolbar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在液态导航模式下，为首页时间线增加真实内容采样的顶部双玻璃 Toolbar 和玻璃锚定菜单，同时保留旧模式的原生 Toolbar/FAB/PopupMenu。

**Architecture:** `HomeTabFragment` 继续拥有时间线、公告、更新、列表和话题业务状态，通过一个 Java/Kotlin 控制器桥接到顶部 `ComposeView`。`BackdropCaptureFrameLayout` 每帧只重绘一次内容树，并同时发布顶部和底部窄区域 Bitmap；顶部 Compose 树用外部 backdrop 绘制左右玻璃，并用 `exportedBackdrop` 让锚定菜单安全采样 Toolbar 玻璃。

**Tech Stack:** Android Fragment/AppKit、Java 17、Kotlin 2.4、Jetpack Compose、miuix blur/backdrop、JUnit 4、Android `gfxinfo`/ADB。

---

### Task 1: 顶部 Toolbar 状态模型与菜单映射

**Covers:** [S1, S2, S3, S7]

**Files:**
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarModel.kt`
- Test: `mastodon/src/test/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarModelTest.kt`

- [ ] **Step 1: 写常驻 action 和提醒映射的失败测试**

```kotlin
class HomeLiquidToolbarModelTest {
    @Test
    fun liquidModeKeepsOnlyComposeAndOverflowActions() {
        assertEquals(
            listOf(HomeToolbarAction.COMPOSE, HomeToolbarAction.OVERFLOW),
            liquidToolbarActions(),
        )
    }

    @Test
    fun reminderStateOnlyBadgesOverflowAndMatchingMenuRows() {
        val state = homeToolbarReminderState(
            hasUnreadAnnouncements = true,
            hasUpdate = false,
        )
        assertTrue(state.overflowBadged)
        assertTrue(state.announcementsBadged)
        assertFalse(state.settingsBadged)
    }
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:testDebugUnitTest --tests org.joinmastodon.android.ui.compose.navigation.HomeLiquidToolbarModelTest -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
```

Expected: `HomeToolbarAction`、`liquidToolbarActions` 和 `homeToolbarReminderState` 未定义导致编译失败。

- [ ] **Step 3: 实现最小纯状态模型**

```kotlin
internal enum class HomeToolbarAction { COMPOSE, OVERFLOW }

internal data class HomeToolbarReminderState(
    val overflowBadged: Boolean,
    val announcementsBadged: Boolean,
    val settingsBadged: Boolean,
)

internal fun liquidToolbarActions() = listOf(
    HomeToolbarAction.COMPOSE,
    HomeToolbarAction.OVERFLOW,
)

internal fun homeToolbarReminderState(
    hasUnreadAnnouncements: Boolean,
    hasUpdate: Boolean,
) = HomeToolbarReminderState(
    overflowBadged = hasUnreadAnnouncements || hasUpdate,
    announcementsBadged = hasUnreadAnnouncements,
    settingsBadged = hasUpdate,
)
```

- [ ] **Step 4: 增加时间线、新帖子和二级菜单状态测试**

```kotlin
@Test
fun newPostsReplacesTimelineLabelWithoutChangingTimelineSelection() {
    val state = HomeLiquidToolbarState(
        selectedTimeline = 2,
        showNewPosts = true,
        menuPage = HomeToolbarMenuPage.NONE,
    )
    assertEquals(HomeToolbarLeadingMode.NEW_POSTS, state.leadingMode)
    assertEquals(2, state.selectedTimeline)
}

@Test
fun listAndHashtagPagesStayInsideTheAnchoredMenu() {
    assertEquals(HomeToolbarMenuPage.LISTS, HomeToolbarMenuPage.ROOT.openLists())
    assertEquals(HomeToolbarMenuPage.HASHTAGS, HomeToolbarMenuPage.ROOT.openHashtags())
    assertEquals(HomeToolbarMenuPage.ROOT, HomeToolbarMenuPage.LISTS.back())
}
```

- [ ] **Step 5: 实现状态和菜单页转换并运行测试**

实现 `HomeLiquidToolbarState`、`HomeToolbarLeadingMode`、`HomeToolbarMenuPage` 的最小转换函数，然后重复 Step 2 命令。

Expected: 全部测试通过。

- [ ] **Step 6: 提交状态模型**

```bash
git add mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarModel.kt mastodon/src/test/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarModelTest.kt
git commit -m "test(ui): 固定顶部液态 Toolbar 状态映射"
```

### Task 2: 一次绘制发布顶部与底部 Backdrop

**Covers:** [S4, S6, S7]

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/ui/views/BackdropCaptureFrameLayout.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java`
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/ViewBitmapBackdrop.kt`
- Test: `mastodon/src/test/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeBackdropRegionsTest.kt`

- [ ] **Step 1: 写区域计算失败测试**

```kotlin
@Test
fun topAndBottomRegionsAreClampedWithoutOverlapAssumptions() {
    assertEquals(IntRange(0, 179), topCaptureRange(viewHeight = 1640, requestedHeight = 180))
    assertEquals(IntRange(1416, 1639), bottomCaptureRange(viewHeight = 1640, requestedHeight = 224))
    assertEquals(IntRange.EMPTY, topCaptureRange(viewHeight = 0, requestedHeight = 180))
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:testDebugUnitTest --tests org.joinmastodon.android.ui.compose.navigation.HomeBackdropRegionsTest -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
```

Expected: 区域函数未定义。

- [ ] **Step 3: 实现纯区域函数**

在 `ViewBitmapBackdrop.kt` 增加 `topCaptureRange` 和 `bottomCaptureRange`，只负责 clamp，不读取 Android View。

- [ ] **Step 4: 将捕获 listener 改为双区域结果**

在 `BackdropCaptureFrameLayout` 定义：

```java
public interface CaptureListener{
    void onCaptured(Bitmap top, Bitmap bottom);
}
```

保留一个共享 `captureBitmap` 和一次 `super.dispatchDraw(captureCanvas)`。完成共享绘制后，用复用的顶部/底部 Bitmap Canvas 从共享结果复制对应区域，再调用一次 `onCaptured(top, bottom)`。不得为顶部和底部分别调用 `dispatchDraw()`。

- [ ] **Step 5: 接入 HomeFragment 双区域分发**

`HomeFragment` 保存顶部控制器引用。捕获回调中将 `top` 发给 `HomeLiquidToolbarController`，将 `bottom` 发给现有 `HomeLiquidNavigationController`。任一控制器为空时仍只执行一次捕获，并跳过无消费者区域复制。

- [ ] **Step 6: 运行聚焦测试与 Java/Kotlin 编译**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:testDebugUnitTest --tests org.joinmastodon.android.ui.compose.navigation.HomeBackdropRegionsTest :mastodon:compileDebugJavaWithJavac -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
```

Expected: 测试和编译成功；`BackdropCaptureFrameLayout` 仍只包含一个 `super.dispatchDraw(captureCanvas)` 调用点。

- [ ] **Step 7: 提交共享捕获**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/ui/views/BackdropCaptureFrameLayout.java mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/ViewBitmapBackdrop.kt mastodon/src/test/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeBackdropRegionsTest.kt
git commit -m "perf(ui): 共享顶部和底部玻璃背景捕获"
```

### Task 3: 顶部双玻璃 Compose 组件

**Covers:** [S1, S2, S4, S5]

**Files:**
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarView.kt`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/liquid/HomeGlassSurface.kt`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/HomeTabFragment.java`

- [ ] **Step 1: 创建顶部控制器接口**

`HomeLiquidToolbarController` 必须提供：

```kotlin
fun setBackdropBitmap(bitmap: Bitmap)
fun setBottomInset(statusBarInsetPx: Int)
fun setTimelines(items: List<HomeToolbarTimeline>, selectedIndex: Int)
fun setShowNewPosts(show: Boolean)
fun setReminderState(hasUnreadAnnouncements: Boolean, hasUpdate: Boolean)
fun setLists(items: List<HomeToolbarListItem>)
fun setHashtags(items: List<HomeToolbarHashtagItem>)
fun closeMenu()
fun dispose()
```

构造参数回调包括：选择时间线、查看新帖子、发帖、打开设置/公告/编辑时间线/列表/话题。

- [ ] **Step 2: 实现通用玻璃 surface**

`HomeGlassSurface.kt` 使用现有 miuix `drawBackdrop`：

```kotlin
effects = {
    vibrancy()
    blur(6.dp.toPx(), 6.dp.toPx())
    lens(
        refractionHeight = 14.dp.toPx(),
        refractionAmount = 18.dp.toPx(),
    )
}
```

形状为圆角胶囊；surface 在浅色使用高透明白、深色使用高透明深色，并保留 1dp 低对比描边和按压高光。RuntimeShader 不可用时使用相同 shape 的半透明背景。

- [ ] **Step 3: 实现左右两块玻璃布局**

顶部根节点处理 status bar inset。左块自适应标题宽度但必须给右块保留空间；右块固定容纳 48dp 发帖按钮与 48dp 更多按钮。更多红点固定在更多图标右上角。

左块在 `showNewPosts=true` 时显示上箭头和“查看新帖子”，点击回调后不修改选中时间线；否则显示当前时间线图标、标题和下拉箭头。

- [ ] **Step 4: 在液态模式创建顶部宿主**

`HomeFragment` 在与内容相同的 `FrameLayout` 顶部叠加 `toolbarHost`。只有当前主 Tab 为首页且液态模式开启时显示；其他 Tab 隐藏。Activity 重建和 `onDestroyView()` 中 dispose 控制器并清空引用。

- [ ] **Step 5: HomeTabFragment 输出状态并隐藏原 Toolbar/FAB**

液态模式下：

- 原 Toolbar 内容保持存在但设为不可见，不占用交互；顶部安全区由 Compose 宿主负责。
- `fab.setVisibility(View.GONE)`，`showFab()/hideFab()` 不重新显示它。
- Java 回调继续调用当前 `BaseStatusListFragment.onFabClick()`。
- `updateSwitcherIcon()`、新帖子按钮状态变化和数据请求完成后同步控制器。

旧模式不执行这些分支。

- [ ] **Step 6: 编译顶部组件**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:compileDebugKotlin :mastodon:compileDebugJavaWithJavac -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
```

Expected: Kotlin/Java 编译成功。

- [ ] **Step 7: 提交双玻璃 Toolbar**

```bash
git add mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarView.kt mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/liquid/HomeGlassSurface.kt mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java mastodon/src/main/java/org/joinmastodon/android/fragments/HomeTabFragment.java
git commit -m "feat(ui): 添加首页顶部双液态玻璃 Toolbar"
```

### Task 4: 时间线与更多玻璃锚定菜单

**Covers:** [S3, S4, S5]

**Files:**
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarMenu.kt`
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarView.kt`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/HomeTabFragment.java`

- [ ] **Step 1: 导出 Toolbar backdrop**

在顶部 Compose 根创建 `toolbarBackdrop = rememberLayerBackdrop()`。左右玻璃 `drawBackdrop` 使用 `exportedBackdrop = toolbarBackdrop`，菜单只采样 `toolbarBackdrop`；禁止在菜单或 Toolbar 后追加会形成自引用的 `layerBackdrop(toolbarBackdrop)`。

- [ ] **Step 2: 实现时间线锚定菜单**

左菜单宽度跟随最长时间线标题但限制在屏幕宽度内。每行包含图标、标题和选中标记，点击调用时间线切换并关闭菜单。

- [ ] **Step 3: 实现更多菜单一级页面**

顺序固定为设置、公告、编辑时间线、列表、关注的话题。公告和设置行分别读取提醒红点。列表/话题为空时隐藏对应入口，保持当前行为。

- [ ] **Step 4: 实现同浮层二级页面**

点击列表或关注的话题时设置 `HomeToolbarMenuPage.LISTS/HASHTAGS`，使用淡入淡出或短横向位移动画替换内容；顶部返回行设置为 `HomeToolbarMenuPage.ROOT`。条目点击调用现有 `ListTimelineCustomFragment` 或 `UiUtils.openHashtagTimeline()` 路径。

- [ ] **Step 5: 接入关闭规则**

透明全屏 scrim 负责空白点击关闭，但不绘制暗色遮罩。系统返回优先关闭菜单；主 Tab 切换、页面隐藏和当前时间线开始滚动时调用 `closeMenu()`。

- [ ] **Step 6: 编译并运行状态测试**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:testDebugUnitTest --tests org.joinmastodon.android.ui.compose.navigation.HomeLiquidToolbarModelTest :mastodon:compileDebugKotlin :mastodon:compileDebugJavaWithJavac -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
```

Expected: 测试和编译成功。

- [ ] **Step 7: 提交玻璃菜单**

```bash
git add mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarMenu.kt mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarView.kt mastodon/src/main/java/org/joinmastodon/android/fragments/HomeTabFragment.java
git commit -m "feat(ui): 添加首页顶部玻璃锚定菜单"
```

### Task 5: 旧模式回退、提醒同步与主题重建

**Covers:** [S1, S2, S3, S5, S7]

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/HomeTabFragment.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java`
- Modify: `mastodon/src/main/res/menu/home_custom.xml`

- [ ] **Step 1: 保留旧模式 action 提升机制**

`updateOverflowMenu()` 仅在旧模式执行：公告/设置提醒可继续切换 `announcementsAction/settingsAction`。液态模式始终让这两个 action 不可见，并仅更新 Compose reminder state。

- [ ] **Step 2: 保留旧模式 FAB 与 PopupMenu**

关闭液态导航时恢复 `fab`、原 Toolbar switcher、`overflowPopup` 和现有子菜单。开关即时切换只重建顶部/底部导航宿主，不重新创建时间线 Fragment。

- [ ] **Step 3: 修正主题重建恢复**

确认顶部控制器在 `onDestroyView()` dispose，新的 Activity 使用新 context 创建。复用已恢复的 child 时间线 Fragment，不增加 `HomeTimelineFragment/LocalTimelineFragment/NBWTimelineFragment/FederatedTimelineFragment` 数量。

- [ ] **Step 4: 编译并检查格式**

Run:

```bash
git diff --check
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:compileDebugKotlin :mastodon:compileDebugJavaWithJavac -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
```

Expected: 无 whitespace error，编译成功。

- [ ] **Step 5: 提交兼容行为**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/fragments/HomeTabFragment.java mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java mastodon/src/main/res/menu/home_custom.xml
git commit -m "fix(ui): 保留顶部液态 Toolbar 的旧模式回退"
```

### Task 6: 完整构建、性能与真机验收

**Covers:** [S6, S7]

**Files:**
- Modify: `docs/compose/reports/ios-liquid-navigation.md`

- [ ] **Step 1: 运行全部聚焦测试**

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:testDebugUnitTest --tests org.joinmastodon.android.ui.compose.navigation.HomeNavigationTabsTest --tests org.joinmastodon.android.ui.compose.navigation.HomeLiquidToolbarModelTest --tests org.joinmastodon.android.ui.compose.navigation.HomeBackdropRegionsTest -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
```

Expected: 全部测试通过。

- [ ] **Step 2: fresh 构建 Debug APK**

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
```

Expected: `BUILD SUCCESSFUL`，APK 位于 `mastodon/build/outputs/apk/debug/mastodon-debug.apk`。构建成功后按项目规则提交本阶段改动。

- [ ] **Step 3: 安装并验证 UI**

```bash
adb install -r mastodon/build/outputs/apk/debug/mastodon-debug.apk
adb shell am force-stop top.abdl_space.app.debug
adb shell monkey -p top.abdl_space.app.debug -c android.intent.category.LAUNCHER 1
```

肉眼验证浅色/深色下左右玻璃、真实帖子折射、发帖、更多红点、时间线菜单、更多一级/二级菜单、新帖子提示和旧模式回退。

- [ ] **Step 4: 验证主题重建实例数量**

主题切换前后执行：

```bash
adb shell dumpsys activity top > /tmp/home-liquid-toolbar.txt
rg -c 'HomeTimelineFragment\{' /tmp/home-liquid-toolbar.txt
rg -c 'LocalTimelineFragment\{' /tmp/home-liquid-toolbar.txt
rg -c 'NBWTimelineFragment\{' /tmp/home-liquid-toolbar.txt
rg -c 'FederatedTimelineFragment\{' /tmp/home-liquid-toolbar.txt
rg -c 'androidx.compose.ui.platform.ComposeView' /tmp/home-liquid-toolbar.txt
```

Expected: 时间线和顶部 Compose 宿主数量切换前后不增长。

- [ ] **Step 5: 对比滚动性能**

```bash
adb shell dumpsys gfxinfo top.abdl_space.app.debug reset
adb shell input swipe 600 1200 600 400 1800
adb shell dumpsys gfxinfo top.abdl_space.app.debug | rg 'Janky frames:|50th percentile:|90th percentile:|95th percentile:|Total attached Views'
```

分别记录液态顶部开启和旧模式结果。若顶部开启导致明显回归，使用 trace 区分共享 capture 与 Compose glass 绘制，再仅调整 backdrop downscale。

- [ ] **Step 6: 更新报告并提交**

在 `docs/compose/reports/ios-liquid-navigation.md` 记录顶部 Toolbar、玻璃菜单、共享捕获、主题重建数量和性能结果。

```bash
git add docs/compose/reports/ios-liquid-navigation.md
git commit -m "docs(ui): 记录顶部液态 Toolbar 验证结果"
```
