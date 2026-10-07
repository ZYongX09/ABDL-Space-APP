# 3.1.0 / code33 正式发布

2026-10-07T06:49:57.333Z（北京时间 2026-10-07 14:49:57）完成线上发布。版本号迭代至 3.1.0、内部版本 33，基于最新 main 代码树重新构建正式签名 release 包。

- 下载：<https://r2.abdl-space.top/apk/ABDL-Space-3.1.0.apk>。
- 页面：<https://abdl-space.top/app>；<https://m.abdl-space.top/app>。
- APK本体：`top.abdl_space.app`，3.1.0/code33/minSdk26/targetSdk35，非debug。
- 大小88,848,965字节（84.73 MiB）；SHA-256 `bad6aea07e80cb482fd22021c9b28b86464bce8cc53fb04432cb23f0002e0107`。
- v2签名重新验证通过，原证书SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`（各历史发布同源证书）。
- 本地正式文件：`/home/ZYongX/projects/assets/releases/3.1.0-33/ABDL-Space-3.1.0.apk`；同哈希、发布期 R2 回下载副本、旧元数据与请求/成功响应在 `publication-3.1.0/`。

## 指定文件 / 重新构建说明

用户原指定 `/home/ZYongX/projects/dist/sponsor-guide-release-evidence/ABDL-Space-3.0.1-code32-album-protection-report.apk` 重命名为 `ABDL-Space-3.0.0-31.apk` 并基于其推动新版本。

⚠️ 该文件已完成重命名（`ABDL-Space-3.0.1-code32-album-protection-report.apk` → `ABDL-Space-3.0.0-31.apk`），但原内容仍为 3.0.1/code32 正式签名 album-protection 包（SHA-256 `98877d9ce427812b9999d86120b325ab958d0c562dbb48eb1b76487cf41e7ebe`）——**不是** 2026-10-03 生产的真实 3.0.0-31 构建，不同文件不能相混。本轮 3.1.0 发布对象为 **main 最新正式源码完整重构建** 的 `mastodon-release.apk`（88,848,965 字节，SHA-256 `bad6aea07e80cb482fd22021c9b28b86464bce8cc53fb04432cb23f0002e0107`，v2 签名证书同上），已确认 versionCode=33 / versionName="3.1.0"，绝不与上述改名件混淆。

发布前新R2对象返回404；随后向既有桶 `abdl-space-img` 的 `apk/ABDL-Space-3.1.0.apk` 上传上述正式重建包，设置APK MIME、attachment文件名及immutable缓存。完整下载返回200、字节数与SHA-256均匹配，再调用既有JSON版本发布入口一次，返回HTTP200/success=true。请求使用版本号33、字段 `apkUrl` 及用户提供的三条原文日志。没有采用上轮失败的multipart图床发布分支，没有修改鉴权、代理、域名、密钥、旧版退役策略或数据库结构。

## 更新日志（用户原文，逐字）

1.【新增】新增宝宝相册新功能，单次可上传20张照片！
2.【修复】进一步修复液态玻璃功能的bug
3.【优化】优化赞助者购买引导

## 四仓提交与主线整合

- Android main 现为 `3fcc3cc6`（recovery/20260925 已并入 main；`073e7046` merge 记录液态玻璃/隐私指引等改动），所有 recovery/feature 代码均在 main，工作树无未提交源码改动（仅根目录既有旧 `mastodon-release.apk` 保留不提交）。
- 后端 main `c20caa1`：相册保护/举报/重试修复已在 main（PR #22 已部署为 `abdl-space-api`），线上 `/api/v1/version` 已确认返回本发布信息。
- 主站 V2 main `4288d18`（PR #8）、移动站 main `77b12eb`：均与远端同步，无未提交改动；`/app` 下载页已确认展示 3.1.0 下载链接与新日志。
- 四仓未发现遗漏源码；`.mimosa/` 为扫描缓存、不提交；root 旧 APK 按既有保护保留。
- 本发布记录经独立记录分支进入 main，不直接 push 后端 main（`release/app-3.1.0-record` 合并后端，部署由 Workers Builds 自动触发且业务代码未变更）。

## 验证

- 发布后四个查询入口（api / 主站 proxy / 移动 proxy / pages.dev）均精确返回 3.1.0、code 33、下载链接与新三条日志。
- 客户端信息：88,848,965 字节等 R2 对象 MD5/字节核验通过；header（X-Ap 等）直接对照发布。
- 发布后未再变更 R2 对象；`abdl-space-img` 桶 `apk/ABDL-Space-3.1.0.apk` 为唯一新对象。

## 验收边界

本轮只改名并重新构建正式包，不重跑Android全仓测试或重生成 debug 测试 APK（正式签名 release 测试包为唯一交付形态）。没有覆盖安装、账号数据保留、真实付款/赞助者购买、宝宝认证/相册端到端、20 张照片真机上传验证；真机 GPU（液态玻璃）与真实 COS 双账号仍为既有边界。已安装同签名低版本的用户会正常收到升级提示。

详细生产发布记录与回滚说明见后端仓 `docs/app-release-3.1.0-2026-10-07.md`。