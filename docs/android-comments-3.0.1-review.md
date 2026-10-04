# Android 评论发布修复（保持 3.0.1 / code32）

## 根因与范围

以图形修复提交 `9bb6abe2` 为基线。`ComposeFragment` 在回复模式下跳过宝宝新天地绑定检查，所以页面局部绑定状态保持 `BINDING_CHECKING`。发布按钮与菜单点击却无条件要求 `BINDING_BOUND`，导致输入有效评论后按钮不能使用，即使账号已绑定也不会发送请求。

Git 历史表明 2026-08-27 `cef3229b` 增加回复禁用全部宝宝新天地同步逻辑时，遗漏了既有按钮/菜单绑定条件。本次不是图形修复引入，也不是普通评论 POST 接口或 JSON 字段名变更。

## 最小修复

- 统一 `needsNewBabyWorldBinding()`：只有非回复且用户选择同步宝宝新天地时才需要绑定。
- 发布按钮、菜单点击及初始发布门禁使用相同条件；不伪造绑定成功状态。
- 回复保留既有直接发送路径，不执行绑定刷新或 AI 板块推荐；`in_reply_to_id` 原样保留 `p_`/`c_` 字符串，不携带 NBW 同步板块及位置字段。
- 不同步的普通发帖也不再被绑定检查误挡；切换同步板块后立即重算发布按钮，恢复同步时仍执行原绑定门禁。
- 正文非空/长度、附件上传完成、投票选项及推荐中状态校验保持原样。
- versionName 仍为 `3.0.1`，versionCode 仍为 `32`，签名不轮换。不覆盖上一轮同版本 APK；新包使用独立修复后缀并保存在 Gradle 输出外。

## 后端检查边界

本站普通评论由 `POST /api/v1/statuses` JSON 发送，与现有客户端一致。本轮不修改或部署后端、不发布线上元数据、不发送真实评论。

只读检查另发现后端 NBW 跨站评论写入能力及 typed-ID 上下文遍历问题，不属于本次客户端按钮被误拦的直接根因；本轮没有变更这些服务端行为，不能据此声称跨站 NBW 评论已实现或所有服务端线程问题已解决。

## 验证边界

当前无在线 USB 设备或可用 Linux 桌面模拟器。本轮以真实 Fragment/菜单行为回归及 MockWebServer 离线 HTTP 契约验证，不能替代真机发表评论、服务端持久化和帖子详情回填的线上端到端验收。

## 验证结果

- `ComposeReplyPublishingBehaviorTest` **9/9**：SDK28真实 ComposeFragment、正文 Editable、原生 Toolbar 菜单和生产发布流程，只有 transport exec 边界替换为离线请求记录。回复检查中/未绑定/检查失败/已绑定与 `p_`/`c_` 均能到达 CreateStatus；独立菜单点击检查；正文/附件/投票守卫；关闭同步后的即时重算及普通绑定/AI推荐通过。
- `CreateStatusHttpTest` **9次SDK执行全通过**：API26所有分支、API35成功契约；真实 POST JSON、字符串回复 ID、无 NBW/geo、Bearer/版本及幂等header、完整单Status解析、401/403/422及错误响应分支。
- 上一轮图形/设置/私信 **222次全通过**；首页发布与菜单 **6/6**；版本上报HTTP **36/36**。两轮focused合计 **282次执行，0 failures，0 errors，0 skipped**。行为测试写入晚于首轮testcompile，首轮273项没有执行它；随后独立编译执行9项，结果单独存档，未将首轮视为包含它。
- Debug Java编译及 `:mastodon:assembleRelease` 通过。测试首次独立编译遇到 SDK37移除FingerprintManager导致 Java `Shadows.shadowOf` 重载解析失败，改用显式 `ShadowApplication` / `Shadow.extract` 后编译与9项行为回归通过，不修改生产依赖或版本。
- `git diff --check` 通过；只读复核未发现本轮同步权限放宽或按钮/发送门禁不一致。
- 本轮未重跑全部800项回归；上一轮唯一QQ既有用户图标与静态格式契约不符的失败仍记录于图形修复报告，未替换资源或宣称全仓测试全绿。

## 交付

- 新包：`/home/ZYongX/projects/assets/releases/3.0.1-32-comments/ABDL-Space-3.0.1-32-comments.apk`（86,621,157 bytes）。
- APK本体：versionName **3.0.1**、versionCode **32**、minSdk26、targetSdk35，非debug；`comments`仅文件名后缀，不是版本后缀。
- APK SHA-256：`9fcaa2d3a79546141e3936f456a875defec41a982d85ed2c9a32fb4cd856c9f9`。
- APK v2签名验证通过，仍为原证书SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`。
- 前一轮3.0.1/code32包及根目录旧包均保持原哈希，未覆盖。新包因versionCode相同，不会被按版本号比较的更新检查视为更高版本；本轮未发布线上更新。
- 验证日志与两轮XML分别保存在新交付目录的 `validation/focused-test-results/`、`validation/compose-behavior-results/`，避免Gradle下次覆盖。
- 在已有 `fix/3.0.1-graphics-gates` 分支提交推送；原仓main、后端及线上下载未变。
