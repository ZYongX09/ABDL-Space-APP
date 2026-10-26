# 新版 App：六板块收敛、位置菜单 miuix 化、绑定提醒与统一 AI 标记

日期：2026-10-27（功能完成于 10-26/27 夜间）。基于 develop `db0733c3`，功能分支 `feat/app-ai-forum-controls-20261026`。本轮回应用户六项实测需求。**仍只交付 develop 与正式签名测试包：不正式发版、main 不动（`c8dd0ccd`）、真实账号/设备验收不被构建替代。**

## 1. 发帖页六板块收敛（含 AI 推荐）

新增 `NBWForumPolicy`：`FORUM_IDS = [18 闲聊, 28 自拍, 27 分享, 26 小说/漫画, 3 交友, 19 社群]`（官方指引 L237-238）。

- `NBWClassificationController.menuEntries` 只投影这六个板块；`required=false` 的板块保留「默认子分类」（typeid=0）选项，`required=true` 且无普通用户可用子分类的板块整项隐藏；名称/子分类合法性仍以上游实时目录为权威。
- AI 推荐同样只从六板块挑选（`NBWForumPolicy.isLegal` 约束 manual 选择；`RecommendNBWForum` 请求结果经服务器端同样收敛，见后端仓文档）。
- 过期选择（旧缓存 fid 不在六板块）继续走既有 `nbw_v2_selection_expired` 显示与发布防泄漏路径。

## 2. 位置选择菜单 miuix 化

新 `ComposeLocationController`：真实 `btn_location` 点击后锚定按钮弹出 miuix 单选菜单（`OverlayCascadingListPopup` + `DropdownItem`，与分类菜单同一容器），四个精度选项、深浅主题、选中态对勾、禁用态灰字均按 miuix 语义渲染；行为不变——打开菜单不请求权限、不读位置；选择时重新校验权限，无权限回落省级并弹出既有 `LocationPermissionSheet`；缓存城市有权限可选、无权限点击回落。

第一版使用 `OverlayListPopup` + `BasicComponent` 在 Robolectric 下触发 miuix `ListPopupColumn` intrinsic 测量崩溃（固定宽度约束下 `Constraints.copy` 产生 maxWidth<minWidth，Component.kt:220），切换为已验证的 `OverlayCascadingListPopup` 容器后消除；该崩溃仅出现在 intrinsic 测量路径，真实设备同样存在风险，故不用旧容器。

## 3. 绑定提醒（面子工程，不阻发帖）

按用户最终裁决（三次明确指示，记录在后端仓 `SCOPE-DECISIONS.md`）：

- 标题「未绑定无法发帖」，副文「未绑定宝宝新天地账户无法发帖。请先绑定或连接后再继续使用。」，按钮「去绑定」跳转 `NBWPostRegisterActivity(show_back=true)`。
- 不添加「本站仍可正常发布」等限定语；**实际行为不变**：无任何代码禁止本站发帖，发布、AI 分类、同步尝试全部照常，仅展示提醒卡片。
- 注册流程 NBW 绑定引导确认未删（RegisterInfoFragment 成功后仍进入绑定页）。

## 4. 新版 App AI 标记

`MastodonAPIController` 全局请求头新增 `X-ABDL-App-AI: 1`（与既有 `X-ABDL-NBW-Protocol: 2` 并列，仅本轮新构建携带）。后端据此为 App AI 功能选择管理端配置的上游；token/模型不下发 App。`AppVersionReportingHttpTest` 断言该头存在。

## 5. 测试稳定性修复（非断言放宽）

- `ComposeLocationMenuInteractionTest` 早期两项因「冷 JVM 首次组合 + Espresso 60 秒 master 策略」触发 `AppNotIdleException`（ attempts=1 证明循环只执行一次即超时；反编译确认 idle 循环非死锁）。修复：测试引入 `espresso-core:3.5.0` testImplementation（与 compose-ui-test 的运行时传递版本一致，仅补编译可见性），`@Before` 将 master/idling 策略放宽到 5 分钟，`settle()` 先经 30×16ms 有界帧预排再等待。全部业务断言未放宽。
- 方法名 `unboundReminderHasExactCopyAndNeverBlocksLocalPublishing` 同步移除已删除限定语的描述。

## 6. 验证与测试包

- 同一次串行执行：`:mastodon:testReleaseUnitTest`（14 个测试类，覆盖上一轮 12 类 + 本轮新增 location/AppVersionReporting 两类）**142/142 PASSED / 0 FAILED**，通过后同一命令 `assembleRelease` 成功（BUILD SUCCESSFUL in 18m 9s）。
- 位置菜单 5/5（无数据无权限四行可用性、选中回显、无 geo 字段序列化、缓存+权限路径、真实按钮进入）。
- 实际 native Canvas 渲染图已检查：geo 菜单深浅色（锚定、选中对勾、禁用灰字、miuix 圆角浮层）正常；Robolectric 软件渲染不是真机验收。
- 正式签名测试包：`/home/ZYongX/projects/ABDL-Space-3.2.0-code34-nbw-v2-app-controls-test.apk`，151555166 bytes，SHA-256 `456d28df1d6ab5e959e87f01913492b32fa5c8a1c2ee07499ac49eac8db42140`；仍 3.2.0/code34、非 debuggable、既有证书 `fd2098a3…`，apksigner V2、ZIP CRC、16KiB 对齐均通过，上一轮测试包完整保留。

日志：`/home/ZYongX/projects/app-ai-controls-evidence-20261026/`（`android-full-regression-assemble.log`、`android-location-idling-policy2.log`、`apk-verification.json`）。本轮不推 main、不发版；生产 Worker 与两站部署边界见各仓文档。
