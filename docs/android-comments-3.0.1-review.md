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

## 2026-10-22 帖子生命周期与宝宝新天地契约补充（本轮）

### 基线与边界

- 工作区 `/home/ZYongX/projects/ABDL-Space-APP-friend-cards-review`，基线 `65e14033086540f589f9b3ba6eb3fe5730c6dca3`。修复在 `fix/nbw-post-lifecycle-20261022` 实施后以 `90620424` 快进并推送 develop，临时分支已删除；未合并 main。
- 已核对主工作树及 graphics 工作树的 AGENTS：日常修复从 develop 切功能分支、保留 develop 包含 main、不擅自正式发布、不生成 debug 交付包、不读取或复制密钥。本轮 Android 代码调查者未运行构建、未触碰 graphics 缓存或设备，构建验证由协调者在独立 graphics 工作树负责。
- 本节只记录本轮新增代码及待验证项；上文历史测试数字和旧包信息不是本轮结果。本轮后端配套迁移 `0084` 及路由修复仍待部署，客户端代码落盘不等于线上联调通过。

### 根因与客户端最小修复

1. **删除已经生效但提示 `Status.createdAt=null`**：原 `DeleteStatus` 把 DELETE 的返回体当完整 `Status`，服务器删除成功后的残缺对象在必需字段验证失败，导致不调用成功回调、不清缓存、不发删除事件。后端返回非完整 Status 是协议不匹配，客户端也不应为了确认删除去要求可渲染的帖子。现改用 `ResultlessMastodonAPIRequest<Void>`；HTTP 2xx 无论部分对象、空体或 204 都按无返回值成功处理，非 2xx 仍保留错误。`UiUtils.confirmDeletePost` 使用原帖子回调并清缓存/发事件，不删除 `Status.createdAt` 的必需约束。
2. **打开编辑时报 `spoilerText=null`**：错误入口是 `GET /api/v1/statuses/:id/source`，不是 PUT 返回的 Status。全局 Gson 使用 snake_case，原 source 返回 camelCase `spoilerText` 时无法填充 `@RequiredField spoilerText`。客户端显式标准名 `spoiler_text`、兼容旧 `spoilerText`；id/text/spoiler 三项仍必需，缺失或 null 仍报错。后端应改为标准 snake_case。
3. **编辑误套发帖初始值**：原编辑页沿用 AI/分享默认板块和创建帖门禁，却把现为板块选择器的旧 visibility 按钮禁用；未绑定用户连“不予同步”也无法选择。编辑评论仅有 `editStatus`，未识别原 `in_reply_to_id`，又会当根帖做绑定/AI/geo。现从 source 可选元数据还原原设置；默认保存不做绑定检查/AI、不发送 NBW/geo 初始值。用户显式改目标才执行原创建帖的绑定/推荐逻辑；`-1` 明确选择不予同步。评论编辑保留完整父 ID、隐藏板块与位置，不走根帖同步。编辑保留语言、敏感标记、心理危机、原附件与原 visibility；geo 仅用户改动后发送，null 明确清空。同步选择与位置脏状态/位置值随重建保存。
4. **宝宝新天地点赞/收藏**：客户端仍向本站 `POST /api/v1/statuses/:id/{favourite,unfavourite,bookmark,unbookmark}`，不直接请求外站、不包含 S2S 凭据。普通帖子继续验证完整 Status；`nbw_` 根主题改用轻量真实交互响应，严格匹配请求 ID 与对应 boolean，已知 count 精确覆盖，未知 count 不虚构正文/作者。请求中禁止再次叠加操作；失败恢复原 snapshot；重复回调不重复加减。收藏数加入事件同步。HTTP403 且机器码 `NBW_BINDING_REQUIRED` 才弹绑定 Dialog，普通组权限403不误导；跳已有绑定页携带发起请求的 account，已有账号 OAuth 使用该账户 UID，非当前账户的一键注册不冒用全局邮箱。
5. **NBW 回复和 context**：客户端创建回复仍用 `POST /api/v1/statuses`，`in_reply_to_id` 保留所点帖的完整字符串，包括 `nbw_<tid>` 或 `nbw_<tid>_<pid>`，不做整数解析或擅自替换为根主题。不带 `nbw_fid` 与 geo。读上下文仍用 `GET /api/v1/statuses/:id/context`，ancestors/descendants 必须由后端映射完整 Status 与 typed 父 ID。本地成功回填增加去重和列表插入通知；外站楼层点赞/收藏未获支持时不请求根主题代操作。

### 后端约定与未实现范围

- Source 保留必需 `{id,text,spoiler_text}`。可选扩展为 `in_reply_to_id`, `visibility`, `language`, `sensitive`, `mental_crisis`, `geo_province/city/district`, `nbw_fid:number|null`, `nbw_tid:number|null`, `nbw_sync_enabled:boolean`, `nbw_sync:{state,reason?}`；旧服务缺扩展时客户端按“保留原设置”处理，不默认为 AI 同步。
- PUT 字段缺省保留；`nbw_fid:null` 保留，`nbw_fid:-1` 禁止未来未同步双发，不删除已经存在的外站帖子；geo 显式 null 清空对应值。已同步主题不得通过再次 `create_thread` 假装编辑成功。
- NBW 点赞/收藏成功为轻量 `{id:'nbw_<tid>',favourited?:boolean,favourites_count?:number,bookmarked?:boolean}`，只返回真实已知值。对应动作 boolean 必须存在且 ID 匹配，不能补假作者/正文；本地帖子仍完整 Status。严格根 ID，0/前导零/溢出/楼层 ID 不得改到错误主题。
- 未绑定交互为 HTTP403 `{error:'请先绑定宝宝新天地账号后再操作',code:'NBW_BINDING_REQUIRED'}`。登录失效401、组权限403、上游/服务500沿普通错误回滚；同步未决或旧作者不明的409保留中文错误，不误判成绑定。
- 后续复核补齐 OAuth 账户连续性：两个绑定入口用随机 nonce 保存逐请求 account/完整 state/时间；后端回调以 `client_state` 回显原始值。App 仅在完整匹配、10分钟内且原账户仍存在时单次消费，不按回调时的 lastActive 选账户；缺失/过期/篡改/重放失败关闭。正式测试包的这条新绑定流程依赖配套后端，旧生产尚未部署 `client_state` 支持，不能据本地测试称真实 OAuth 已验收。
- 创建/编辑 Status 可选携带 `nbw_sync`；`unsupported` / `failed` 或回复 `binding_required` 时，客户端明确提示只保存到 ABDL Space、外站未同步。未绑定的普通本地回复不硬拦。
- **用户第6项贴的是 `delete_thread` 文档，不是回复 API。** Android 仓内当前实现及历史说明只证明普通 `POST /statuses` 与 NBW 写回复能力尚缺，未提供足以实现的 `reply_thread` 参数/鉴权/父楼层/成功与错误码协议。不得从删除文档猜造回复接口。外站写回复和远端编辑依旧未实现；目前 `reply_api_unavailable` / `edit_api_unavailable` 仅准确标注本地成功，不宣称跨站成功。应由后端在获得正式写回复/编辑契约后补齐 S2S 与集成测试。

### 真实测试与运行命令

新增或扩展测试，不仅是源码正则断言：

- `StatusLifecycleHttpTest`：9项真实 loopback HTTP/controller/Gson/validation，覆盖 DELETE部分/空/204/403、source snake/camel与仍严格必需、PUT完整Status与同步元数据、轻量交互与ID/字段校验、绑定机器码及普通403、外站与本地混合context typed父ID。
- `StatusInteractionBehaviorTest`：6项真实 controller 状态转换与 Activity/Dialog，覆盖401/403/500 snapshot回滚和重复回调、NBW实时计数覆盖及共享reblog不改正文、绑定Dialog与原account导航、普通403不弹绑定、楼层/无效ID不发送不改状态，以及nonce事务并发、篡改、过期、重放和账号消失。
- `ComposeReplyPublishingBehaviorTest`：在原13项上新增5项，覆盖普通编辑原敏感/心理危机/语言/附件保留与不隐式重发/定位、旧source编辑与明确不予同步、评论编辑四类父ID、NBW回复本地成功提示、实际Activity重建后板块/位置状态和显式清空。既有 `FriendCardEntryRoutingTest` 增加 source 元数据 Parcel 传递断言。

在 Android 工作树根目录执行（由协调者负责；以下为命令，不代表已通过）：

```sh
./gradlew :mastodon:testReleaseUnitTest --offline --no-daemon --max-workers=1 \
  --tests '*StatusLifecycleHttpTest' \
  --tests '*StatusInteractionBehaviorTest' \
  --tests '*ComposeReplyPublishingBehaviorTest' \
  --tests '*CreateStatusHttpTest' \
  --tests '*FriendCardStatusWireTest' \
  --tests '*FriendCardEntryRoutingTest'
./gradlew :mastodon:compileReleaseJavaWithJavac --offline --no-daemon --max-workers=1
./gradlew :mastodon:assembleRelease --offline --no-daemon --max-workers=1
git diff --check
```

协调者已实际完成生产 Kotlin/Java 非增量编译，以及上述6个类共 **52次测试执行，0失败/错误/跳过**（CreateStatus含API26/28参数化）。Dialog实际点击会进入原account绑定页；创建/编辑/删除与轻量互动均经真实请求解析/Fragment行为验证。后续新增旧绑定引导入口同nonce协议，已重新编译Java并用javap确认实际调用createBindingState，交互6项再次通过；最终包为这一源码版本。API28 Robolectric 的 Dialog 行为断言不等于截图或真机视觉验收。本轮不覆盖安装、不读取账号/密钥、不操作用户设备，未进行带真实账号的发/改/删/赞/藏或外站持久化端到端验收。

协调者的旧基线 Compose 回归卡在 `idleMainLooper → Choreographer → Editor.PositionListener.onPreDraw`，线程证据 `/home/ZYongX/projects/abdl-post-sync-evidence-2026-10-22/android-baseline-thread-dump.txt:174-317`；该次已停止，不能计为测试成功。测试类已有 PAUSED Looper，但 Robolectric 默认 vsync 会自动推进虚拟时钟，持续预绘制无法排空。生产冻结后仅按授权补测试 fixture：Compose 与交互 Activity 测试 `@Before ShadowChoreographer.setPaused(true)`、关闭 Activity 后恢复；只排当前任务、不自动追未来帧，不删断言、不改变正文编辑器或生产发送逻辑。交互测试显式 PAUSED Looper。构建方受内存限制采用独立 fork（`forkEvery=1`）。后续fixture修正还包括真实Activity保存/销毁/重建（避开Robolectric recreate强制window focus的空root限制），原状态断言不删；Dialog按钮改为show后原生click listener，实际导航断言通过。早期失败/中断不计通过；最终6类52项通过证据保存在仓外JUnit归档。

### 最终正式签名测试包

- `/home/ZYongX/projects/ABDL-Space-3.1.0-code33-nbw-lifecycle-test.apk`，150,645,612 bytes（约143.67 MiB）；SHA-256 `12b20aae232d3a137ac424ddc600956671e58903bfe3a2a0fcee547ea772617e`。
- top.abdl_space.app，3.1.0/code33，min26/target35/compile37，非debug；既有正式证书SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`，V2签名、ZIP CRC及4字节zipalign通过。
- 四ABI与29个native库均保留，DEFLATE压缩延续上轮，各库展开SHA-256与native-compressed基线完全一致；地图密钥verifyMapKeys通过，不输出值。
- 新版入口最后重编译后，低堆768MiB的DEX合并曾OOM；未清缓存/改项目配置，仅本次改1536MiB、ActiveProcessorCount=2重试，最终assembleRelease成功。此前先完成25分钟非增量Kotlin/Java编译；后续仅Java改变，复用已核对不变的Kotlin输出，测试源码非增量重编译。没有把失败运行算通过。
- 仓外证据 `/home/ZYongX/projects/abdl-post-sync-evidence-2026-10-22/` 包含全部日志、两批JUnit、source parity、APK签名/完整性和release-artifact.json。旧包保留，不提升版本、不改正式下载/版本JSON、不上传。
- 后端功能971c165、最终记录7cd4164均仅develop，0084及Worker尚未上线。只装此包不能让线上外站互动/回复后端修复生效；新绑定回调同样依赖配套Worker。未覆盖用户连接设备，未做真实账户/外站闭环或真机视觉验收。
