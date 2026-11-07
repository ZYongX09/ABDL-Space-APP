# ABDL Space 3.3.0（36）

## 更新日志

【新增】全新「小说」功能测试版本上线，让故事从此光芒万丈!
【新增】赞助者支付方式新增「支付宝官方渠道」，支付更加方便快捷~
【新增】我们已首发兼容小米、荣耀、OPPO、VIVO四个品牌系统的「一镜到底动画」功能（特定机型、特定系统版本生效）
【新增】针对特定语言的帖子，新增AI翻译功能，跨国交流更便捷~
【优化】增加首屏缓存机制，提升启动速度
【优化】增加部分页面的流光加载动画，提示加载体验
【优化】优化APP英文适配

## 安装与下载

- 版本名：3.3.0；内部版本号：36。
- 文件名：`ABDL-Space-3.3.0-36.apk`。
- 包名：`top.abdl_space.app`；Android 8.0（API 26）及以上。
- 大小：**169,036,758 字节**（约 161.21 MiB）。
- SHA256：`a137079dd8b571e2f9cb48ccbc0b55918160e9bcd873567e7e114b70a8d7cab6`。
- [官网下载与安装说明](https://abdl-space.top/app)。
- [正式 APK（R2）](https://r2.abdl-space.top/apk/ABDL-Space-3.3.0-36.apk)。
- [GitHub Release](https://github.com/ZYongX09/ABDL-Space-APP/releases/tag/v3.3.0)。

安装包与既有正式版使用相同签名（证书 SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`），可按 Android 系统提示覆盖升级。发布使用已经构建、核验的同一 APK，仅按要求改名，不重新签名或以另一次构建替换。

## 源码与验证

本版功能源码来自 code36 开发线，正式发布提交更新 README 与本日志，不改变应用生产源码或构建配置。`v3.3.0` 指向经 `develop` 非 squash 整合至 `main` 的正式源码。

- APK V2 签名、包名/版本号（`top.abdl_space.app`、3.3.0、36）与证书指纹通过核验。
- 本轮同源码单元/截图回归 **31/33 通过**；2 项小说编辑器截图断言仍按旧文案查找节点（`写第一章  →` / `解析并预览  →`），未通过。
- 本轮没有宣称真机验收通过：支付宝支付结果页、小说更新并发布、一镜到底动画与英文适配均需在对应机型、系统版本和后端权限下实测。
- 支付宝渠道的支付结果页面仅在服务端确认发放成功（`GRANTED`）后展示；客户端不把 SDK 回调当作发放证据。

小说、支付渠道与一镜到底动画均按功能测试范围提供；实际可用性取决于后端接口、支付开通状态、机型/系统版本和账户权限。

## 正式发布结果

- GitHub Release 于 **2026-11-07T09:34:32Z** 发布：[v3.3.0](https://github.com/ZYongX09/ABDL-Space-APP/releases/tag/v3.3.0)，非 draft、非 prerelease，设为 latest。
- `v3.3.0` 为注释标签，固定指向正式源码提交 `b7047889391b226e0a18ccc710890b19700005bc`；后续补写记录不移动标签、不重新构建 APK。
- R2 对象 `abdl-space-img/apk/ABDL-Space-3.3.0-36.apk`：**完整回下载** 169,036,758 字节，SHA256 与本地一致；响应为 `application/vnd.android.package-archive`、`attachment; filename="ABDL-Space-3.3.0-36.apk"`、`public, max-age=31536000, immutable`。
- GitHub 附件同样做了**完整回下载**：169,036,758 字节，SHA256 一致；本次文件名不含括号，GitHub 未做规范化。
- 版本数据于 **2026-11-07T09:58:22.088Z** 登记；API 直连、主站代理、移动站代理三个 `GET /api/v1/version` 的版本名、内部号、下载 URL、字节数与用户原文日志完全一致。
- `main` 与 `develop` 均为 `b7047889`，`develop ⊇ main`；本轮未改后端代码、未重新构建 APK。
- 未做浏览器渲染验收与真机覆盖升级验收。

私有发布证据：`~/.local/share/abdl-space-release/20261107-app-3.3.0/`。

## 发布规则

`develop` 是开发集成分支，`main` 是正式源码分支；开发线始终包含正式线全部提交，`develop` → `main` 不使用 squash。安装包不入 Git，R2 上传完整回下载与 SHA256/大小核验后才登记线上版本。旧版安装包与标签不覆盖，不运行已弃用的 Google Play/Actions 发布流程。
