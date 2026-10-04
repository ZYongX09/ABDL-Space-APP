# 3.0.1 / code32 正式发布

2026-10-04T07:30:41.333Z已发布用户指定的最终backdrop签名包。文件从 `ABDL-Space-3.0.1-32-backdrop.apk` 改名为 `ABDL-Space-3.0.1.apk`，只改名、不重建或重新签名。

- 下载：<https://r2.abdl-space.top/apk/ABDL-Space-3.0.1.apk>。
- 页面：<https://abdl-space.top/app>；<https://m.abdl-space.top/app>。
- APK本体：`top.abdl_space.app`，3.0.1/code32/minSdk26/targetSdk35，非debug。
- 大小86,621,157字节；SHA-256 `364069587871a31930efb24cbc443e4ea6e21b993ab28af55ce9021e07ed63b4`。
- v2签名重新核验通过，原证书SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`。
- 本地正式文件：`/home/ZYongX/projects/assets/releases/3.0.1-32-backdrop/ABDL-Space-3.0.1.apk`；原文件名备份、旧元数据、完整线上回下载及发布响应在同目录 `publication-3.0.1/`。

recovery `1667c81e` 经PR #3完整进入main `0cbd5a9a`，代码树与recovery完全一致，包含全部三轮液态玻璃/关于背景/私信标题/评论修复。不合并废弃develop；修复/历史分支不代表尚有遗漏代码。原仓根目录旧APK保留且未提交，QQ用户资源未替换。

R2新对象上传后先完整回下载核验，再调用既有JSON发布入口写latest元数据。API与主站/移动站代理均精确返回3.0.1/code32及两条原文日志；两个浏览器下载页的版本、日期、链接和日志已核验。不改域名、代理、密钥、后端鉴权或旧版退役策略，不部署业务代码。

## 更新日志

1.【修复】修复部分情况下无法评论的bug
2.【修复】修复无法显示液态玻璃效果的bug

## 验收边界

此次不重新构建或重跑Android全仓测试；指定包此前294项集中回归、最终34项安全边界复验通过，既有QQ静态资产断言失败仍保留。不把原真机错误日志当作修复后GPU/视觉验收；没有覆盖安装、账号数据保留、真实评论/QQ/付款/宝宝认证端到端验证。已经安装本地code32测试包的设备因versionCode相同不会自动发现更高版本，应手动安装正式包。

详细生产发布记录见后端仓 `docs/app-release-3.0.1-2026-10-04.md`。
