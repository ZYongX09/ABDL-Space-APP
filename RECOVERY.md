# 恢复说明（2026-09-27）

本分支基于云端还原提交 `174692f9fb5a9441f9727a93cd0fb9e1f5b3bb54`，从最后测试 APK、ZCode 会话数据库、Git 对象与构建日志恢复删除前的 Android 3.0.0 工作树。所有恢复仅发生在 `recovery/20260925`，未合并到 `main`/`develop`，也未发布 APK。

## 已恢复并验证的主要内容

- `versionCode 23` / `3.0.0-preview`。
- `minSdk 26`，兼容 Android 8–12L，并启用 core library desugaring。
- QQ 授权登录、账号绑定和官方 QQ Connect SDK 3.5.19。
- 热门时间线、浏览/分享热度打点、关注时间线。
- 赞助中心、购买流程、颜色与权益模型。
- 宝宝认证申请、私有照片上传、证书和验真入口。
- 小说编辑核心模块和相关构建配置；源码保留，但主页普通菜单与液态菜单入口保持隐藏。

## 最终界面与行为补全

在初步恢复验证后，又完成并推送以下独立修复轮次：

1. `eacad68f`：恢复第三方登录授权 BottomSheet，修复 NBW、QQ、网页 OAuth 点击闪退，并替换 Android 8 不支持的 `Context.getMainExecutor()`。
2. `f65a4df5`：恢复 Android 8–11 starting window 静态应用图标，并恢复 Android 12+ 系统 SplashScreen 动画图标。
3. `e7147436`：恢复主页聚合时间线中的交友宇宙卡片，移除独立交友时间线默认/可选入口并迁移旧偏好。
4. `50b4c66e`：底部导航恢复为“主页 / 消息 / 纸尿裤 / 我的”，消息页接入私信列表，旧搜索 tab 迁移到消息，旧交友 tab 回落主页。
5. `dc4afa95`：恢复最终版右上角液态玻璃工具栏：搜索+更多胶囊、独立发布按钮、普通帖/交友帖发布菜单。
6. `67cdaf4a`：恢复后台颜色文字徽章、列表/详情消费层、本人展示徽章选择/取消 API，以及与赞助者、宝宝认证解耦的展示逻辑。
7. `1c0d942e`：恢复图片查看器“查看原图”按钮、预览优先策略、赞助者/免费额度授权、提示弹窗、下载缓存与账号切换/关闭取消逻辑。
8. `e1b97dc8`：恢复“第三方开源许可”页面、依赖列表、滚动样式、QQ SDK 数据处理披露与中英文标题。

## 证据与综合验证

- 最后测试 APK SHA-256：`08968012945837423b9e45f146beaa4d9a65a8fadac1e2f5f1b3a105dc69816e`。
- 官方 QQ SDK JAR SHA-256：`9d57fe61ff9026d34ac84bc63dc719f61da6aa40533a299cc6f73d4ce9df7af8`。
- Java/Kotlin 编译成功。
- Android 资源链接成功。
- Debug APK 构建成功：
  - 路径：`mastodon/build/outputs/apk/debug/mastodon-debug.apk`
  - 包名：`top.abdl_space.app.debug`
  - versionCode：`23`
  - versionName：`3.0.0-preview-debug`
  - minSdk：`26`
  - targetSdk：`35`
  - 大小：`104,941,388` 字节
  - SHA-256：`9cacf5b65c0172a02bc7e4a84b26d18f76431f1ca7b7bbf4a55fca90a66ac4bd`
- 全量 Debug 单元/契约测试：`232/232` 通过，`0` failures，`0` errors，`0` skipped。
- 新 Debug APK 已确认包含以下关键恢复类：
  - `FriendRequestStatusDisplayItem`
  - `SponsorOriginalGate`
  - `HomeToolbarComposeMenuItem`
  - `BadgeSpan`
  - `OpenSourceLicensesFragment`
- 新 Debug APK 已确认包含以下关键恢复资源：
  - `id/btn_view_original`
  - `drawable/bg_view_original_pill`
  - `layout/item_friend_request_timeline`
  - `drawable/ic_tab_messages`
  - `drawable/splash_preview_window`
  - `drawable/bg_license_card`
  - `drawable/scrollbar_thumb_license`

## 设备 GUI 验证状态

2026-09-27 综合回归时，本机 Android Emulator 插件不支持当前 Linux 主机，且 `adb` 未发现已连接设备，设备列表为空。因此本轮未能执行冷启动、第三方登录点击、四项底栏、交友卡片、液态工具栏、文字徽章、查看原图弹窗和许可页面的实体设备 GUI 冒烟。

该限制不影响上述编译、资源链接、APK 构建、232 项测试与 APK 类/资源核对结果；设备 GUI 冒烟仍是后续具备 Android 设备或可用模拟器时的待执行验证项。

## 安全处理

- 未恢复或提交 QQ App Key。
- 原有明文签名密码已改为环境变量或 `local.properties`。
- 未提交 release keystore、私钥、签名密码、`local.properties` 或 `mastodon-release.apk`。
- 未部署后端/前端，未发布 APK。

删除前记录的本地提交点为 `fe95138c feat(auth): 接入 QQ 授权登录与账号绑定`；该对象未推送且已丢失，本分支为基于证据的重建。
