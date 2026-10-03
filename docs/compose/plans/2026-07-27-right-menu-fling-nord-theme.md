# Right Menu Fling And Nord Theme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让右侧 Liquid Glass 菜单复用向上 fling 收回规则，并将新用户默认主题切换为 Nord，提供轻微蓝白的浅色背景。

**Architecture:** `MorphingGlassContainer` 在 Compose pointer 流中计算 Y 速度并通过回调请求关闭；主题改动只修改无持久化偏好时的默认枚举和 Nord 浅色背景色，不迁移旧用户选择。

**Tech Stack:** Kotlin Compose pointer input、Compose VelocityTracker、Java preferences、Android XML colors、JUnit 4、Gradle。

---

### Task 1: 右侧菜单向上 fling 收回

**Files:**
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassContainer.kt`
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarView.kt`
- Test: `mastodon/src/test/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassHitTestTest.kt`

- [ ] 写失败测试，验证右侧向上速度超过系统阈值时请求关闭，慢速上滑和向下滑不关闭。
- [ ] 运行聚焦测试，确认 RED。
- [ ] 在 Compose pointer 流中使用 `VelocityTracker` 跟踪位置；UP 时调用现有 `isUpwardToolbarFling()`，通过 `onUpwardFling` 回调关闭右侧菜单。
- [ ] 运行聚焦测试和 Kotlin 编译，确认 PASS。

### Task 2: Nord 默认主题和浅色背景

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/GlobalUserPreferences.java`
- Modify: `mastodon/src/main/res/values/colors_custom.xml`

- [ ] 将无持久化主题偏好时的默认值从 `BLUE` 改为 `NORD`，不覆盖已有用户选择。
- [ ] 将 `nord_gray_25` 从中性灰白调整为 `#F6F8FC`，保持主体白色和轻微蓝色倾向。
- [ ] 运行资源处理、聚焦测试和 `assembleDebug`。
- [ ] 构建成功后只提交本计划涉及文件。
