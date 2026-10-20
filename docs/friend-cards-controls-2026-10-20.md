# 扩列卡控件与输入法细化（2026-10-20）

在已推送 develop 的 `a74c2dde` 基础上，从 `fix/friend-cards-controls` 完成用户提出的六项调整。只回合 develop、提交推送并交既有正式签名 release 测试包；不合 main，不正式发布，不改下载入口、版本信息、后端或生产数据库。

## 本轮调整

1. 详情移除原生顶部标题栏，在 `onUpdateToolbar` 生命周期中设置 GONE，配置变化重建也不恢复占位。上方改为 44dp 关闭触控区与紧凑的“管理我的扩列卡”圆角按钮；主人与访客均管理当前账号自己的卡，不携带所查看卡片的 ID。液态玻璃遵循应用开关、硬件加速和 RuntimeShader 支持，使用真实 LayerBackdrop 记录下方内容，并复用现有振动色彩/18dp 模糊/折射/独立高光；不支持时显示半透明回退。
2. 功能图标直接采用用户提供的 `~/Downloads/friendcard.png` 原始 PNG，不重画，不裁切、不重采样，透明通道保留。原图 SHA-256：`f8408b9fc963661a424ffa2a5deced29222a8759fe10cb073bdcf84a27fbb08a`。更新经典/液态菜单、折扇创建入口、主页与发帖菜单；全局 people/ContactsBook 不被替换。
3. 圆形私信入口移除 Hi 文本，使用用户 `~/Downloads/chat.svg` 的两段原始 pathData、1024 视口转为 VectorDrawable；只按背景调整 tint，点击/无障碍描述仍为“发私信”。
4. 编辑底栏仅一枚“下一阶段”。前两步进入下一阶段，第三步经原有验证进入公开/旧端预览与明确发布确认，不直接发帖。去掉额外保存本机草稿/发布前预览按钮；逐次输入的持久化、离开处理及 pending operation 原样保留。
5. 扩列卡由 AppKit 原生容器与 Activity adjustResize 持有系统/键盘 inset；去掉额外 Compose 根 imePadding 和底栏 navigationBarsPadding，避免第二层避让扩大空白。只变更扩列卡页面，未改变其他独立 Activity 的输入法策略。
6. 宣言上方开引号由受固定高度限制的文字改为 36×26dp 装饰 Canvas，两笔完整形状不再依赖字体基线裁切，不进入屏幕阅读文本。

## 验证边界

本轮补充真实 Fragment 原生 toolbar 隐藏、主人/访客关闭/管理路由、单一下一步/最终验证、自动保存与异步回包保留焦点/选择/滚动、原生 inset 收起/展开/重复分发/恢复、引号原生像素检查和图标资源精确性回归。

原生渲染沿用 API28 Native Skia + View.draw(Canvas)，组件测试使用确定性 Miuix light/dark，真实 Fragment 截图使用生产 Base/MiuixAppTheme。API28 回退截图不代表 API33+ 硬件液态玻璃效果已真机验收。

真机可连接且旧 App 可启动，但停在系统生物识别验证；未绕过认证、提取 token、修改账号或发布测试内容。真实输入法动画/硬件玻璃/覆盖安装/线上账号完整闭环不由离线测试代替。

首轮定向 UI 回归 19 类 / 117 项通过（含键盘 API28/30），随后在逐图检查中发现 Compose XML vector 的原生离线绘制出现空白图形；PNG 图标正常。采用项目已使用的 AndroidView/ImageView 资源与 tint 路径保持原始矢量，并增加实际控件与原生资源的像素形状对比，第二轮 3 类 / 27 项通过。既有资源路径无改写，不把空白图片视为验收通过。

第一次整合选择 39 类 / 318 项中 3 项感谢榜 fixture 失败，原因为测试按 getDeclaredConstructors()[0] 反射创建 PendingImageRequest；Robolectric 注入构造函数顺序不稳定，取到了 0 参数构造器却传入 3 参数。通过 javap 核实实际 AppKit 三参数私有构造器后，测试替身改为精确按参数类型获取；未更改感谢榜生产代码、断言或跳过测试。失败轮次 XML/HTML 留在仓外 `abdl-friend-cards-controls-integration-attempt-junit.tar.gz`。

最终同一生产源码的整合定向选择为 **39 类 / 318 项通过，0 失败、0 错误、0 跳过**。包含新增图标/控件/键盘验证、发帖兼容、地图/相册/感谢榜和折扇/液态菜单。不同轮次不相加，不声称全量测试。JUnit XML/HTML 保存在 `/home/ZYongX/projects/abdl-friend-cards-controls-final-junit.tar.gz`；逐类摘要为 `abdl-friend-cards-controls-test-summary.json`。

原生渲染共 25 张，持久副本为 `/home/ZYongX/projects/abdl-friend-cards-controls-renders/`，包括更新后详情主人/访客、创建浅/深三阶段、完整引号、大字和图标像素样例。已人工检查主要状态；液态玻璃硬件分支仍未真机验收。

这次整合测试已通过后，打包在 l8DexDesugarLibRelease 因 `~/.gradle/caches` 在运行期间消失而失败（desugar JSON 缺失），该次不算成功出包。使用仓外独立 Gradle home `~/.local/share/abdl-gradle-home` 重试，不改项目或依赖配置、不交付旧包。独立缓存首次下载遇到 Google Maven TLS，核实本机代理可达后给 Gradle daemon 显式临时代理参数，再次 `assembleRelease` 与 `verifyMapKeys` 成功。构建中仍有已有 D8/Kotlin metadata 兼容警告，但不是构建失败；未据此更改 Kotlin/AGP/混淆配置。

## 正式签名测试包（不发布）

- 路径：`/home/ZYongX/projects/ABDL-Space-3.1.0-code33-friend-cards-controls-test.apk`。
- 大小：236,480,766 bytes；SHA-256：`1f0124e9e4d8a28c502bbd8ccbf76daf82aeddc794829dc0b35a67b3805470ea`。
- `apksigner verify` V2 签名通过，证书 SHA-256：`fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`，沿用既有正式签名。
- `top.abdl_space.app`，3.1.0 / code33，非 debuggable，min26/target35/compile37，arm64-v8a/armeabi-v7a/x86/x86_64 四 ABI 保留；ZIP 28,506 项 CRC 全通过。
- `verifyMapKeys` 确认 release APK 携带既有地图配置；未输出密钥、未生成 debug APK、未改版本信息或下载入口。
- 最终出包日志：`/home/ZYongX/projects/abdl-friend-cards-controls-isolated-assemble-retry.log`。
- 签名/badging 记录：`abdl-friend-cards-controls-signature.log`、`abdl-friend-cards-controls-badging.log`（同目录）。

与线上同 versionCode，仅文件名区分本轮测试包；构建成功不等于覆盖安装、硬件液态玻璃和真实输入法动画已真机验收。
