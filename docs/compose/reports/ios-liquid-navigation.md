---
feature: ios-liquid-navigation
status: delivered-code-pending-device-verification
specs: []
plans:
  - docs/compose/plans/2026-07-25-ios-liquid-navigation.md
branch: develop
commits: uncommitted
---

# iOS-like Liquid Navigation — Final Report

## What Was Built

ABDL Space 主界面新增了默认开启的 iOS-like 液态玻璃底部导航栏。它完整保留现有首页、搜索、纸尿裤、私信、纸飞机和个人主页六个 AppKit Fragment，只将底部栏替换为 `ComposeView` 中的液态玻璃组件。

导航栏支持点击、重复点击、长按、拖动切换、阻尼回弹、折射、色散、按压形变和重力传感器高光。个人页入口继续显示账号头像，通知数量与纸尿裤“新功能”徽章会同步更新。

“设置 → 显示”新增“iOS 风格底部导航栏”开关。偏好默认值为开启，切换后立即保存并让主界面在 Compose 液态栏和原 `tab_bar.xml` 经典栏之间切换。

## Architecture

`HomeFragment` 保留子 Fragment、切页和业务回调，只新增一个可替换的 `navigationHost`。开启偏好时由 `HomeLiquidNavigationController` 创建 `ComposeView`；关闭时原样 inflate 经典 `tab_bar.xml`。

`HomeLiquidNavigationController` 维护选中 ID、通知徽章、纸尿裤徽章和底部 inset 的 Compose state，并通过 Java 友好的 setter 接收 `HomeFragment` 更新。`HomeNavigationTabs` 固定维护六个资源 ID 与 Compose 索引的映射。

液态效果代码位于 `ui/compose/navigation/animation` 和 `ui/compose/navigation/liquid`，从 miuix demo 的 Apache-2.0 实现复制并调整包名。因为 miuix `LayerBackdrop` 不能跨 `ComposeView` 采样兄弟 Android View，组件使用应用主题 surface 渐变作为同树 backdrop，同时保留折射、色散和高光效果。

### Design Decisions

- 选择 ComposeView 只承载底栏，因为迁移六个现有 Fragment 到 Compose 会显著扩大范围并破坏成熟业务逻辑。
- 选择主题色 backdrop 而不是周期截图下层 View，因为截图采样会给常驻主界面带来持续 CPU/GPU 开销和可见延迟。
- 保留经典栏作为设置回退路径，便于低性能设备和不偏好该视觉效果的用户关闭。
- 外部状态同步只移动指示器，不触发业务回调；点击与拖动完成显式提交一次，避免程序化恢复被误判为重复点击。

## Usage

首次运行或没有历史偏好时，主界面自动显示液态玻璃导航栏。

可在“设置 → 显示 → iOS 风格底部导航栏”关闭或重新开启。设置立即生效并持久化，不需要重启应用。

## Verification

- `HomeNavigationTabsTest` 先因映射实现缺失失败，实现后通过。
- Kotlin、Java 和资源集成编译通过。
- `./gradlew :mastodon:assembleDebug -x checkDebugAarMetadata -x checkDebugDuplicateClasses --no-daemon` 最终成功。
- `git diff --check` 通过。
- 代码审查发现的当前 Tab 手势遮挡、外部同步重复回调、设置延迟生效、API 24-26 双重 inset 和 retained Fragment 旧 View 引用均已修复并重新构建。
- APK 产物：`mastodon/build/outputs/apk/debug/mastodon-debug.apk`，94,425,706 bytes。
- 测试设备当前未连接，`adb install` 返回 `no devices/emulators found`；真机视觉、六 Tab 拖动、长按和性能对照仍需设备验收。

## Journey Log

- [pivot] 单独 ComposeView 无法采样兄弟 AppKit View，因此使用主题 surface 渐变作为安全、稳定的同树 backdrop。
- [lesson] 顶层 indicator 的 pointer input 会阻断下层同位置 Tab 手势，当前 Tab 的点击和长按必须由 indicator 自身处理。
- [lesson] 外部 selected state 与用户提交不能共用无来源的 `snapshotFlow`，否则恢复状态会产生伪点击。
- [lesson] retained Fragment 必须在 `onDestroyView()` 清空所有 View 和 Activity-context 控制器引用。

## Source Materials

| File | Role | Notes |
|------|------|-------|
| `docs/compose/plans/2026-07-25-ios-liquid-navigation.md` | Implementation plan | Implemented with review-driven interaction fixes |
| `/home/ZYongX/projects/miuix/example/shared/src/commonMain/kotlin/component/liquid/LiquidGlassNavigationBar.kt` | Upstream reference | Apache-2.0, adapted from AndroidLiquidGlass |
