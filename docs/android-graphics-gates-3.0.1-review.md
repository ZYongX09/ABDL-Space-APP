# Android 3.0.1 / code32：图形门禁与私信标题修复

## 根因

以 main `4959bb10` 为基线。code31 的 `LiquidGlassCompatibility` 将 SDK 能力、`isLowRamDevice()` 和全进程运行时熔断合成一个布尔值；显示设置用它决定是否创建液态玻璃开关，关于页动态背景也用它决定是否创建 shader。因此低内存但系统支持的设备没有入口，任一图形/截图失败也会使其它效果和设置入口一起消失。

高性能设备没有入口的机制可以由代码证实，但没有用户设备日志，不能确定其首次失败来自截图预算、硬件加速、shader 还是资源分配。原固定 16 MiB 捕获预算包含全高度中间缓冲；高分辨率屏幕展开 520dp 菜单时，仅这些正常尺寸就可能超过预算，不能据此等同于设备不支持 shader。

私信 tab 的 toolbar 仅有 16dp `paddingEnd`，隐藏 48dp 返回键后没有补充 `paddingStart`，标题因此贴近边缘。

## 修复策略

- 设置入口只由 Android 13 / API33 系统能力决定，不受内存提示、应用 Context 或本次效果回退影响。API26–32 保留非 RuntimeShader 路径，应用 minSdk26 不变。
- 低内存标志或应用 heap class <=128 MiB 仅用作开启确认提示，说明卡顿、耗电和发热风险，不作运行时否决。正常性能保持原默认；有限内存且未保存选择时默认关闭，等待用户确认，显式保存的开启选择不被抹掉。
- 整行与实际 Switch 点击共用请求变更处理器；确认前不修改 model、磁盘偏好或发送已提交变更事件。取消、隐藏、后台及旧对话框按钮不能误保存开启；关闭无需性能警告。
- 用户请求选择与实际渲染状态分离。运行失败后入口和选择保留，首页安全回退，开启时解释本次回退与完全退出后重试，不通过设置按钮清除真实失败熔断。
- NAVIGATION、BACKGROUND、PAGE_BLUR 分别保存进程内失败状态和异步监听。首页捕获/导航失败不再停用关于背景；背景或页面模糊失败也不再隐藏导航选择。各自真实失败仍停止对应效果。
- 保留实际已附着 View 的硬件加速检查、可恢复异常边界、Canvas 恢复、资源清理、未知致命错误传播和 API 下限。
- 动态背景在软件截图/离屏 Canvas 的单帧中只画主题 surface 和内容，不尝试 RuntimeShader，也不把这类可预期帧判定为 GPU 不支持或熔断后续背景动画。
- 关于页所有背景录制和纹理模糊入口均使用 PAGE_BLUR 安全边界；BACKGROUND 回退仍保留纯色背景录制，避免独立失败后 DstIn 标题或卡片使用空白旧帧。
- 捕获保留 16 MiB 所有权缓冲/缓存预算及原像素尺寸输出。预算不足时只对中间缓冲选择有界 1/2 或 1/4 采样并逆映射到原尺寸条带；实际圆整尺寸、条带边缘、缓存和软件图片副本仍受预算约束。极端尺寸或最小采样仍不足则安全回退。
- 仅私信 tab 补 start/end 各16dp；独立私信入口保留原48dp返回键布局，重复 inset 与 RTL 保持逻辑方向和底部避让。
- versionName 更新为 `3.0.1`，versionCode 更新为 `32`。App 版本请求测试去除已过期的 code30 常量，仍核对实际 BuildConfig 上报值。

## 验证边界

没有在线 USB 设备或可用 Linux 桌面模拟器，本轮不能声称已完成两台反馈设备的 GPU/ART 或视觉验收。Robolectric 的原生软件 Canvas 绘制与硬件 Canvas 入口 stand-in 不等于厂商 GPU 测试；Java/Kotlin 异常保护不能捕获 native driver / RenderThread 进程崩溃。

背景测试在 API33/35 核对真实 shader 构造、uniform 更新和两种 preset 绘制。Robolectric 4.14.1 的 `ShadowNativeRuntimeShader.nativeUpdateUniforms` 标量重载在 API33–34 是空 stub（字节码仅 return），故 API33 检查非透明绘制、域隔离和无回退，逐帧像素变化仅在 API35 的真实 native uniform 路径断言；不把模拟器空实现归因于用户设备。

首轮 fresh 工作树构建因宿主内存换页停在资源哈希阶段，被主动停止；改用单 worker、同进程 Kotlin 编译与测试 heap 覆盖继续验证，不修改系统配置或仓库全局构建设置。最终测试和 APK 核验结果另附在下方。

本轮不发布线上 APK 元数据、不部署服务器，不覆盖原仓根目录旧 APK 或现有 code31 发布备份。新交付包保存在 Gradle 输出目录之外。

## 最终回归结果

- `:mastodon:testDebugUnitTest` 全量执行 **757 次：756 通过，1 失败，0 errors，0 skipped**。唯一失败为已发布基线中的 `QQLoginContractTest.qqAssetsComeFromDensitySpecificWebpResources`：测试要求 xxxhdpi `ic_field_qq.webp` 为96×96的静态 VP8L，而用户选择并在正式发布提交 `3803dc94` 纳入的是30×30的 VP8X 动画 WebP。此次不替换既有用户资源，也不放宽该断言来冒充全绿；这是既有资产与测试契约不一致，不是本轮图形修改引入。
- 图形兼容/本轮直接相关回归 **222 次全部通过**：系统兼容24、偏好90、源码接线3、私信布局13、显示设置真实交互38、背景shader6、安全边界9、Compose生命周期/关于回退13、捕获26。
- `:reader-core:testDebugUnitTest` **32/32**，`:novel-editor-core:testDebugUnitTest` **11/11**。总计800次执行，799通过，1项上述既有失败。
- 背景 shader 构造、两preset绘制、API35的逐帧像素变化、软件截图不熔断、纯色录制保留、效果域隔离通过。高分辨率/奇数采样26项通过；RGB565仅允许单量化步长色差，alpha/坐标/几何仍精确检查，奇数条带边缘另对比未裁剪参考。
- 整行与真实 M3Switch 开启提醒、取消/确认持久化、低内存默认与已保存选择、后台/隐藏/旧对话框身份、运行回退解释通过。
- 全量测试因唯一QQ基线失败停止合并任务中的后续assemble；Release单独构建，不声称组合命令全通过。
- `git diff --check` 通过。运行日志与XML副本位于 `/home/ZYongX/projects/assets/releases/3.0.1-32/validation/`，不依赖可被后续Gradle覆盖的test-results。

## 签名交付

`:mastodon:assembleRelease` 单独执行通过（111个任务）。交付为 `/home/ZYongX/projects/assets/releases/3.0.1-32/ABDL-Space-3.0.1-32.apk`，86,621,157 bytes；APK 本体 `top.abdl_space.app`、versionName `3.0.1`、versionCode `32`、minSdk26、targetSdk35，非 debug 包。

- APK SHA-256：`2b5af4dd7109b3434a116718b54069ce7e23a066736578bf9cb835a9fb66bc55`。
- APK v2 签名验证通过；原证书 SHA-256：`fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`，未轮换签名。
- 原仓根目录旧 `mastodon-release.apk` 保持 SHA-256 `0c711549b92e4dd3e1617fcca3858fd542bf12fce988415f1bd7cdb76b63b30a`；QQ用户资源保持 `6145f6e197a0e61b3f5d6bda77ab8c90b086fca6db64cde4c3541b915c687c5f`。
- Build 仍有既有翻译非位置格式、Gradle弃用/flatDir、Miuix/Kotlin/D8 metadata 及JPush stack-map警告，未阻断构建；不声称全仓Lint或完整安全审计通过。
- 代码交付分支 `fix/3.0.1-graphics-gates`，本轮不合入main、不部署服务器、不发布线上元数据。没有真机覆盖安装、GPU效果和视觉验收。
