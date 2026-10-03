# 3.0.0 / code31 正式发布

2026-10-03T13:14:08.755Z 已发布指定 code31 正式签名 APK。原文件 `mastodon/build/outputs/apk/release/ABDL-Space-3.0.0-code31-liquid-glass.apk` 改名为 `ABDL-Space-3.0.0-31.apk`，只改名、不重建。

- 下载：https://r2.abdl-space.top/apk/ABDL-Space-3.0.0-31.apk
- 页面：https://abdl-space.top/app；https://m.abdl-space.top/app。
- 包名 `top.abdl_space.app`，3.0.0/code31/minSdk26/targetSdk35。
- 86,617,521字节（82.60MiB），比原线上preview包小32.1%。
- SHA-256：`8ca19243233495835f61acde85d1b638909a2568f5694e3d3ac8ded697badbbd`。
- v2签名验证通过，证书SHA-256：`fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`。
- 完整线上回下载大小与哈希均一致；API、主站和移动站版本代理一致返回正式版及用户提供的14条更新日志。

recovery `3803dc94` 经PR #1并入main `2fb82935`，合并代码树完全一致，保留code31与全部恢复功能。develop为废弃分支，不单独合并。现有QQ图标已提交，根目录旧 `mastodon-release.apk` 保留且不提交。持久发布备份在项目群 `/home/ZYongX/projects/assets/releases/3.0.0-31/`，不在Gradle构建输出内。

首次后端multipart上传因图床返回HTML失败，随后通过既有R2桶上传APK并用既有JSON接口发布metadata。未增加管理员鉴权、未修改域名/代理/密钥，也未开启旧版退役策略。完整日志与过程见后端仓 `docs/app-release-3.0.0-31-2026-10-03.md`。

本轮未重新构建或运行Android全量测试。既有 `mastodon/src/test/java/org/joinmastodon/android/api/AppVersionReportingHttpTest.java:140` 仍硬编码版本30，与code31不符，记录为后续测试维护问题，不能为它回退正式版本。此处不把先前focused/Robolectric通过视为真机覆盖安装或全部兼容验收；本次未进行真机升级、数据保留、QQ授权/付款/宝宝认证端到端测试。
