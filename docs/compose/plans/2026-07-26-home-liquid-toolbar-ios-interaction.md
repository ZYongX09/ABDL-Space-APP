# Home Liquid Toolbar iOS Interaction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将首页顶部两块玻璃重构为可从胶囊连续变形成菜单的 iOS 风格物理交互，并支持按住滑动选择、连续二级菜单和 backdrop 预热。

**Architecture:** 新建纯 Kotlin 状态/几何模型与 `MorphingGlassContainer`。单个状态机和 `Animatable` 驱动同一玻璃 RenderNode 的边界、圆角、材质和内容转场；`HomeLiquidToolbarView` 仅编排左右容器及业务回调。

**Tech Stack:** Kotlin 2.4、Jetpack Compose pointer input/Animatable、miuix Backdrop/BloomStroke、Android haptics、JUnit 4。

---

### Task 1: 可变形玻璃纯状态机

**Covers:** [S2, S3, S4, S8]

**Files:**
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassState.kt`
- Test: `mastodon/src/test/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassStateTest.kt`

- [ ] **Step 1: 写合法状态转换失败测试**

```kotlin
@Test
fun pressExpandSelectAndCollapseFollowOneStateMachine() {
    var state = MorphingGlassState.idle(MorphingGlassSide.LEADING)
    state = state.reduce(MorphingGlassEvent.Press)
    assertEquals(MorphingGlassPhase.PRESSED, state.phase)
    state = state.reduce(MorphingGlassEvent.BackdropReady)
    assertEquals(MorphingGlassPhase.EXPANDING, state.phase)
    state = state.reduce(MorphingGlassEvent.ExpansionSettled)
    assertEquals(MorphingGlassPhase.EXPANDED, state.phase)
    state = state.reduce(MorphingGlassEvent.HoverItem(2))
    assertEquals(2, state.highlightedIndex)
    state = state.reduce(MorphingGlassEvent.Release)
    assertEquals(MorphingGlassPhase.COLLAPSING, state.phase)
}
```

- [ ] **Step 2: 运行测试确认 RED**

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:testDebugUnitTest --tests org.joinmastodon.android.ui.compose.navigation.MorphingGlassStateTest -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
```

Expected: 状态类型未定义导致编译失败。

- [ ] **Step 3: 实现最小 reducer**

定义 `MorphingGlassPhase`、`MorphingGlassSide`、sealed `MorphingGlassEvent` 和不可变 `MorphingGlassState`。非法事件返回原状态；`Cancel/OutsideTap/Back/ScrollStarted` 均进入 `COLLAPSING`。

- [ ] **Step 4: 增加反转、拖出取消与互斥测试**

```kotlin
@Test fun expansionCanReverseFromCurrentProgress() { /* assert no Idle jump */ }
@Test fun releaseOutsideClearsSelectionAndCollapses() { /* highlightedIndex == null */ }
@Test fun openingTrailingRequestsLeadingToCollapseFirst() { /* switch target retained */ }
```

- [ ] **Step 5: 实现并跑绿测试**

重复 Step 2 命令，Expected: 全部 PASS。

- [ ] **Step 6: 提交状态机**

```bash
git add mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassState.kt mastodon/src/test/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassStateTest.kt
git commit -m "test(ui): 固定顶部玻璃连续交互状态机"
```

### Task 2: 几何插值和物理参数

**Covers:** [S4, S5, S8]

**Files:**
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassGeometry.kt`
- Test: `mastodon/src/test/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassGeometryTest.kt`
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarModel.kt`

- [ ] **Step 1: 写 bounds/圆角插值失败测试**

```kotlin
@Test
fun geometryStartsAtPillAndEndsAtAnchoredMenu() {
    val closed = GlassBounds(12f, 40f, 180f, 48f, 24f)
    val open = GlassBounds(12f, 40f, 248f, 356f, 28f)
    assertEquals(closed, interpolateGlassBounds(closed, open, 0f))
    assertEquals(open, interpolateGlassBounds(closed, open, 1f))
}
```

- [ ] **Step 2: 运行测试确认 RED**

使用 `MorphingGlassGeometryTest` 的聚焦 Gradle 命令，Expected: 未定义。

- [ ] **Step 3: 实现几何和动画规格**

定义 `GlassBounds`、逐字段 `lerp`、屏幕边界 clamp，以及：

```kotlin
val expandSpring = spring<Float>(dampingRatio = 0.82f, stiffness = 520f)
val collapseSpring = spring<Float>(dampingRatio = 0.90f, stiffness = 620f)
```

二级页面内容位移为 `18dp`，行级 stagger 为 `16ms`，行进入位移为 `8dp`。

- [ ] **Step 4: 增加左右锚定和二级菜单尺寸测试**

覆盖右侧菜单不越过屏幕右边、左侧菜单保持左锚点、二级内容高度变化只改变外壳终点。

- [ ] **Step 5: 跑绿并提交**

```bash
git add mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassGeometry.kt mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarModel.kt mastodon/src/test/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassGeometryTest.kt
git commit -m "test(ui): 固定顶部玻璃形变几何与弹簧"
```

### Task 3: 单一 RenderNode 可变形容器

**Covers:** [S2, S4, S6, S7]

**Files:**
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassContainer.kt`
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarView.kt`

- [ ] **Step 1: 创建容器 API**

```kotlin
@Composable
internal fun MorphingGlassContainer(
    state: MorphingGlassState,
    closedBounds: GlassBounds,
    openBounds: GlassBounds,
    backdrop: Backdrop,
    onEvent: (MorphingGlassEvent) -> Unit,
    closedContent: @Composable () -> Unit,
    menuContent: @Composable MorphingGlassMenuScope.() -> Unit,
)
```

- [ ] **Step 2: 用单个 Animatable 驱动几何**

容器使用 `Animatable(0f)`；`EXPANDING` 动到 1，`COLLAPSING` 动到 0，反转不得 `snapTo`。通过 `graphicsLayer`/自定义 layout 设置插值后的坐标和尺寸，shape 圆角同步插值。

- [ ] **Step 3: 连续插值材质**

复用现有 4dp blur、lens、BloomStroke 和重力高光。按压进度增强 lens/highlight；展开进度降低 lens 至菜单可读参数。整个状态只绘制一次 `drawBackdrop`。

- [ ] **Step 4: 替换 AnimatedVisibility 双组件路径**

删除 `HomeLiquidToolbarView` 中独立 Toolbar `GlassSurface` + `AnimatedVisibility GlassMenu` 组合。左右各实例化一个 `MorphingGlassContainer`，闭合和菜单内容由同一容器提供。

- [ ] **Step 5: 编译并提交**

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:compileDebugKotlin -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
git add mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassContainer.kt mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarView.kt
git commit -m "feat(ui): 让顶部玻璃连续形变为菜单"
```

### Task 4: 按住滑动选择与触觉反馈

**Covers:** [S3, S6, S8]

**Files:**
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassContainer.kt`
- Test: `mastodon/src/test/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassHitTestTest.kt`

- [ ] **Step 1: 写菜单行 hit-test 失败测试**

```kotlin
@Test
fun pointerMapsToRowsAndOutsideCancels() {
    val rows = listOf(0f..48f, 48f..96f, 96f..144f)
    assertEquals(1, hitTestMenuRow(70f, rows))
    assertNull(hitTestMenuRow(170f, rows))
}
```

- [ ] **Step 2: 实现纯 hit-test 并跑绿**

边界采用半开区间，避免分隔线同时命中两行。

- [ ] **Step 3: 实现统一 pointerInput**

一个 `awaitEachGesture` 处理按下、阈值、拖入、hover、松手与取消。按住路径不能再触发闭合态 `clickable`；普通点击和无障碍点击通过语义 action 调用同一事件。

- [ ] **Step 4: 添加玻璃 hover highlight 和 haptics**

高亮层用独立小型 `drawBackdrop`，位置由弹簧跟随 `highlightedIndex`。索引变化调用轻触觉，确认调用中触觉；相同索引不重复触发。

- [ ] **Step 5: 编译测试并提交**

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:testDebugUnitTest --tests org.joinmastodon.android.ui.compose.navigation.MorphingGlassHitTestTest :mastodon:compileDebugKotlin -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
git add mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassContainer.kt mastodon/src/test/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassHitTestTest.kt
git commit -m "feat(ui): 添加顶部玻璃滑动选择交互"
```

### Task 5: 二级菜单连续转场与 backdrop 预热

**Covers:** [S5, S6, S7]

**Files:**
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarView.kt`
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassContainer.kt`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java`

- [ ] **Step 1: 增加 backdrop readiness 握手**

`HomeFragment` 在容器 `Pressed` 时扩展捕获范围；`HomeLiquidToolbarController.setBackdropBitmap()` 记录扩展尺寸已到达，并发送 `BackdropReady`。状态机收到该事件前保持 Pressed，不开始 Expanding。

- [ ] **Step 2: 实现二级页面方向转场**

同一菜单外壳保持不变；根页面和二级页面使用 18dp 相反方向位移与 alpha，外壳高度终点通过同一 geometry Animatable 更新。返回方向反转。

- [ ] **Step 3: 统一关闭入口**

空白点击、Back、Tab 切换、列表滚动调用 `RequestCollapse`，等待进度到 0 后才缩小 capture 范围；禁止提前把 backdrop 切回 72dp。

- [ ] **Step 4: 编译并提交**

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:compileDebugKotlin :mastodon:compileDebugJavaWithJavac -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
git add mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarView.kt mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassContainer.kt mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java
git commit -m "fix(ui): 预热顶部菜单折射并连续切换层级"
```

### Task 6: 完整验证与性能对照

**Covers:** [S8]

**Files:**
- Modify: `docs/compose/reports/ios-liquid-navigation.md`

- [ ] **Step 1: 运行全部聚焦测试**

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:testDebugUnitTest --tests org.joinmastodon.android.ui.compose.navigation.HomeLiquidToolbarModelTest --tests org.joinmastodon.android.ui.compose.navigation.MorphingGlassStateTest --tests org.joinmastodon.android.ui.compose.navigation.MorphingGlassGeometryTest --tests org.joinmastodon.android.ui.compose.navigation.MorphingGlassHitTestTest -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
```

- [ ] **Step 2: fresh 构建 Debug APK**

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon
```

Expected: `BUILD SUCCESSFUL`；成功后按项目规则提交。

- [ ] **Step 3: 真机交互矩阵**

验证普通点击、按住滑动选择、拖出取消、快速连点、左右菜单切换、二级菜单、返回、空白关闭、列表滚动关闭和主题切换。检查菜单首帧已有折射，关闭后列表触摸穿透。

- [ ] **Step 4: 60Hz/120Hz 性能测量**

使用 `gfxinfo framestats` 分别测菜单展开/收回、二级切换和帖子滚动。记录 50/90/95/99 分位与真实 jank；不得出现 150ms CPU 中位数或持续捕获。

- [ ] **Step 5: 更新报告并提交**

```bash
git add docs/compose/reports/ios-liquid-navigation.md
git commit -m "docs(ui): 记录顶部玻璃连续交互验证"
```
