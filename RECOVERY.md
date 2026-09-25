# 恢复说明（2026-09-25）

本分支基于云端还原提交 `174692f9fb5a9441f9727a93cd0fb9e1f5b3bb54`，从最后测试 APK、ZCode 会话数据库和构建日志恢复删除前的 Android 3.0.0 工作树。

已恢复并验证的主要内容：

- `versionCode 23` / `3.0.0-preview`。
- `minSdk 26`，兼容 Android 8–12L，并启用 core library desugaring。
- QQ 授权登录、账号绑定和官方 QQ Connect SDK 3.5.19。
- 热门时间线、浏览/分享热度打点、关注时间线。
- 赞助中心、购买流程、颜色与权益模型。
- 宝宝认证申请、私有照片上传、证书和验真入口。
- 徽章模型、新徽章提示与展示组件。
- 小说编辑核心模块和相关构建配置。

证据与验证：

- 最后测试 APK SHA-256：`08968012945837423b9e45f146beaa4d9a65a8fadac1e2f5f1b3a105dc69816e`。
- 官方 QQ SDK JAR SHA-256：`9d57fe61ff9026d34ac84bc63dc719f61da6aa40533a299cc6f73d4ce9df7af8`。
- Java/Kotlin 编译成功。
- Debug APK 构建成功，包名 `top.abdl_space.app.debug`，versionCode 23，minSdk 26。
- 功能契约测试已恢复；后续在 recovery 分支继续消除剩余集成测试差异。

安全处理：

- 未恢复 QQ App Key。
- 原有明文签名密码已改为环境变量或 `local.properties`。
- 不包含 release keystore 或其他私钥。

删除前记录的本地提交点为 `fe95138c feat(auth): 接入 QQ 授权登录与账号绑定`；该对象未推送且已丢失，本分支为基于证据的重建。
