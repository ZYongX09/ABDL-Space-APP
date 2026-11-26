# Android 构建链升级与 35dp 基线

本分支从 35dp 签名测试包对应的源码快照恢复（基线提交 `726858b5` 加当时未提交的变更），不从 Expressive 分支挑 UI 代码。只升级工具链：Gradle 9.7.0、AGP 9.3.1、Kotlin/Compose 编译器 2.4.21、JDK 21、Android compile SDK 37.1；应用 `minSdk 26`、`targetSdk 35`、Java/Kotlin JVM target 17、包名、版本和签名规则不变。

## 为什么本次不升级 Material 3

[1.5.0 的官方发布说明](https://developer.android.com/jetpack/androidx/releases/compose-material3#1.5.0)稳定了 Expressive 主题、按钮组等可选组件，并更改了部分既有组件的行为。它不会自动把传统 View 或 Miuix 换成 Expressive，但会带入 Compose 1.12.0、Lifecycle 2.10.0 和新的 Ripple 组件。阅读器已有 `ModalBottomSheet`、滑块和 `FlowRow`；弹层部分展开停靠点和拖动可能不同，而底栏、时间线和大多数旧组件也没有直接收益。这轮保留应用原解析的 Compose Foundation 1.11.2、Material 3 1.4.0、Miuix 0.9.3。以后可在阅读器专项验证后单独评估，不能以发布说明代替真实交互验证。

## 已知迁移问题

- AGP 9 使用内置 Kotlin；阅读模块保留 Room KAPT，使用 `com.android.legacy-kapt`。SDK 使用标准 `37.1` 包，不设置关闭 SDK/AAR 检查的属性。平台对 Java/Kotlin 的目标仍为 17。
- 将旧 DSL 中的 `aaptOptions`、`lintOptions` 和默认 ProGuard 文件切到受支持配置，不打开代码或资源压缩。原地图密钥入口、沙盒隔离和签名分支仍在原位置。
- `screenshotsUiTest` 仍承担仪器测试。显式保留原 `testDebugUnitTest` / `testReleaseUnitTest`，并保持 AGP 旧版跨配置依赖约束，避免阅读器编译图中的 Lifecycle 和 Annotation 被静默降级。
- 阅读器的 `foundation:1.11.2` 保留，不从旧 BOM 回退到 `FlowRow` 的旧 ABI。使用前后依赖图、阅读器设置回归及 release APK 检查分清编译和运行结果。
- 资源合并曾在约 1.1 GiB JVM 堆失败。本地建议单 worker、约 1.8 GiB 构建堆，并将较大的打包中间产物置于有足够空间的盘。遇到进程中断先复用已有任务产物，不通过关闭 desugaring 或降低运行系统范围来解决。
