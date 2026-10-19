# 地图与相册 review 修复（2026-10-19）

基于 Android `030e2ffb` 的四项 review 发现进行修复，工作分支为 `fix/map-album-review`。收尾复核后与最新 develop 的扩列卡修复整合；未修改 main、后端或生产数据库，不发布正式 APK、不修改线上版本信息或下载入口。

## 修复内容

1. 地图相机移动不再清除 Overlay 的有效 snapshot。导航和地图面板共用最近有效帧的生命周期：移动期间保留，下一次有效采样替换；退出、权限失效和 provider 切换仍由原有清理路径清除。
2. 相册策略轮询实现 `onBlocked`，与预览刷新复用 `PhotoViewer.showAlbumPhotoBlocked`。实时屏蔽会清除旧媒体、取消在途请求、同步 Attachment 标记和空 URL、显示屏蔽占位、隐藏重试入口并禁用下载/画质/点赞。媒体 helper 和直接动作入口同时检查屏蔽状态，不仅依赖按钮可见性。
3. 被屏蔽照片保留主人长按删除能力，仍要求当前有效会话、最新详情、相册主人及照片主人。查看与加载屏蔽预览继续禁止；取消确认不删除，确认后使用既有 DELETE 清理链路。访客及非照片主人不能删除。
4. 错误/宝宝认证门禁为非隐藏状态提供独立的“撤回已发布的位置”操作。它复用不受赞助资格、宝宝认证或旧修订号限制的安全撤回写入。首次“谁可以看到我”选择页不新增撤回、保留当前或停止出现快捷按钮。

## 本轮整合交付（2026-10-19，当前状态）

四项修复提交为 `37cd71fb`；在 graphics 工作树合入最新 develop `497030ce`，无冲突得到 `6e27794941a448f067f870f4211d30c1835217fd`。下述最终测试和 APK 均来自该**干净源码提交**；后续交付记录提交仅修改文档。扩列卡 `eb120882` 的页面 revision、列表快照及 JSON DELETE 修复完整包含在内。

### 单次最终 release 定向回归

最终 `:mastodon:testReleaseUnitTest` **34类 / 292项全部通过，0失败、0错误、0跳过**，耗时13分29秒；未运行全量，不累加历史112项或92项。逐类计数如下，各类均无失败、错误或跳过：

| 测试类（省略公共包前缀） | 项数 |
| --- | ---: |
| FriendUniverseHomeFeedContractTest | 4 |
| HomeLiquidToolbarActionsContractTest | 3 |
| albums.AlbumPhotoViewerTest | 20 |
| api.FriendCardDeleteHttpTest | 2 |
| api.FriendCardStatusWireTest | 6 |
| api.SponsorThanksHttpTest | 5 |
| fragments.ComposeReplyPublishingBehaviorTest | 12 |
| fragments.HomeComposeActionRoutingTest | 4 |
| fragments.albums.AlbumDetailBlockedStateTest | 11 |
| fragments.sponsors.SponsorThanksFragmentTest | 4 |
| friendcards.FriendCardCompositionBehaviorTest | 11 |
| friendcards.FriendCardDraftStoreTest | 9 |
| friendcards.FriendCardFragmentBehaviorTest | 10 |
| friendcards.FriendCardStateTest | 10 |
| friendcards.FriendCardWireTest | 4 |
| sponsors.SponsorThanksControllerTest | 15 |
| ui.compose.friendcards.FocusImagePositionTest | 3 |
| ui.compose.navigation.HomeNavigationTabsTest | 10 |
| ui.compose.navigation.MapNavigationBackdropTest | 14 |
| ui.displayitems.FriendCardEntryRoutingTest | 4 |
| ui.displayitems.FriendCardStatusDisplayItemTest | 10 |
| ui.map.FriendMapFragmentBehaviorTest | 13 |
| ui.map.FriendMapOverlayTest | 48 |
| ui.map.MapAvatarRendererHardwareBitmapTest | 6 |
| ui.map.MapClientAccessContractTest | 20 |
| ui.map.MapCoordinateConverterTest | 4 |
| ui.map.MapGlassContainerTest | 6 |
| ui.map.MapGlassMaterialTest | 6 |
| ui.map.MapGlassPolicyTest | 3 |
| ui.map.MapNavigationIconTest | 1 |
| ui.map.MapProviderRegistryTest | 3 |
| ui.map.MapSdkContractTest | 3 |
| ui.map.MapSheetStateTest | 6 |
| ui.map.MapSnapshotGateTest | 2 |
| **合计** | **292** |

首轮增量尝试实际为30类266项（264通过、2失败），不是292项通过。`javap` 证实两个源码合同测试的 `.class` 仍包含旧交友逻辑常量，另4个 Kotlin 测试类尚未生成（共26项），而受 Git 管理的源码和生产实现已为新逻辑。保留该轮日志、XML和摘要后，以仓外临时 init 脚本强制生产 Kotlin/Java 与全部测试 Kotlin/Java 非增量编译，禁用本次 build cache，再核验新合同常量、4个补齐类、6个生产 Screen 的 revision 参数及 DELETE 的 `new JsonObject` 字节码；**未修改任何源码断言或项目持久配置**。随后完整重跑34类，才获得上述292项最终结果。

- 最终逐类 JSON：`/home/ZYongX/projects/abdl-integrated-review-targeted-final-summary.json`。
- 最终日志：`/home/ZYongX/projects/abdl-integrated-review-targeted-final-2026-10-19.log`；XML与HTML归档：`/home/ZYongX/projects/abdl-integrated-review-targeted-final-junit.tar.gz`。
- 首轮失败证据：`/home/ZYongX/projects/abdl-integrated-review-targeted-2026-10-19.log`、`/home/ZYongX/projects/abdl-integrated-review-incremental-attempt-summary.json`、`/home/ZYongX/projects/abdl-integrated-review-incremental-attempt-junit.tar.gz`。
- 非增量编译与字节码复核：`/home/ZYongX/projects/abdl-integrated-review-nonincremental-compile-2026-10-19.log`、`/home/ZYongX/projects/abdl-integrated-review-class-verification.json`。

### 正式签名 release 测试 APK（不发布）

- 路径：`/home/ZYongX/projects/ABDL-Space-3.1.0-code33-integrated-review-test.apk`；**236,164,820 bytes**。
- SHA-256：`5c0f321b3eb1b4c28d9276cdd6cfc77e59f7fadbc4252fc8c290e6de07f9746a`；与本次构建输出逐字一致。
- `assembleRelease` 与 `verifyMapKeys` 成功，确认1个 release 包携带配置凭据，未输出密钥；未生成 debug APK。
- `aapt dump badging` 确认 `top.abdl_space.app`、**3.1.0 / code33**、min26/target35/compile37；非 debuggable；保留 arm64-v8a、armeabi-v7a、x86、x86_64 四ABI。
- `apksigner verify --verbose --print-certs` 的 V2验证通过，证书SHA-256为 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`，与既有扩列修复包相同；ZIP全部28,504条目CRC校验通过。
- 构建日志：`/home/ZYongX/projects/abdl-integrated-review-assemble-release-2026-10-19.log`；包核验 JSON：`/home/ZYongX/projects/abdl-integrated-review-apk-verification.json`；签名及badging日志：`/home/ZYongX/projects/abdl-integrated-review-package-verification.log`。

所有 Gradle 串行执行，复用 graphics 构建目录、不 clean。使用 JDK21、Gradle1200MiB/Test512MiB、单worker、in-process Kotlin及仅本次JVM代理；凭据只从用户级文件 source，不复制或提交。根盘低于300MiB时的首次前置闸阻止了启动；协调完成旧主树 gitignored intermediates 的可恢复冷存后才真正执行测试。旧APK、未跟踪工具和源码均保留，最终包副本落盘后余量约954MiB。

最终再查仍为**0设备/0AVD**。未进行真机/GPU玻璃、双地图SDK在线认证、双账号、真实COS、旧端往返、覆盖安装及小屏大字暗色验收；无确认授权的实网账号及宝宝凭证，未提取真实用户token或伪造认证。历史全量OOM和旧QQ资源格式断言限制未由本轮定向回归消除，不宣称全量全绿。Android main保持 `ed555e85`，不合main、不触发发布、不改线上版本JSON或下载入口。

## 已有修复专项验证（整合前）

在 `030e2ffb` 加本轮四项修复的源码状态下，串行执行 `:mastodon:testReleaseUnitTest`，以下 6 类共 **112/112 通过**，零失败、零错误、零跳过；不将其与另一源码状态的扩列卡92项累加为整合后结果：

| 测试类 | 数量 |
| --- | ---: |
| FriendMapFragmentBehaviorTest | 13 |
| FriendMapOverlayTest（API26/31/35） | 48 |
| MapGlassMaterialTest | 6 |
| MapNavigationBackdropTest | 14 |
| AlbumPhotoViewerTest | 20 |
| AlbumDetailBlockedStateTest | 11 |

新增相册页面测试使用真实 Adapter 绑定、点击/长按、删除确认及离线 HTTP fixture；实时屏蔽测试通过真实 API callback 驱动策略轮询，检查旧画面清除、占位、Attachment、请求取消和动作禁止。地图测试检查 Overlay 与导航快照在移动时同时保留，退出后清除，以及错误/认证门禁撤回与首次选择页无快捷入口。

Gradle 生产 Kotlin/Java、测试 Kotlin/Java 编译和资源处理成功。`git diff --check` 与 XML 解析通过。最终日志位于仓库外 `/home/ZYongX/projects/abdl-review-fixes-proxy-regression.log`，对应 XML 报告位于 `mastodon/build/test-results/testReleaseUnitTest/`。

## 收尾源码复核

- 最新 develop `497030ce` 已包含 `eb120882` 的扩列卡真实重组及 JSON DELETE 修复，触及文件与本轮四项修复不重叠。整合后应在同一源码状态重新运行地图、相册、感谢榜及扩列卡专项，再构建正式签名 release 测试包；不执行 debug 或正式发布。
- 原始定位要求是“获取定位权限 → 地图显示定位位置（不管用户是否选择隐藏都把地图显示定位位置）”。保留已交付的本地 camera 居中语义；隐藏时不上传坐标、不生成公开自身标记或模糊圈。现有 Fragment 测试通过真实定位回调检查隐藏居中及零上报，不额外扩展 marker 功能。
- 协议、宝宝认证、首次可见性选择及定位顺序保持不变。安全撤回不发送 `visibility_confirmed` 或旧 `settings_version`；回包仍经 `applySettings` 重新走门禁，首次选择页不增加撤回、停止出现或保留当前快捷入口。
- 相册策略变化先取消授权、预览、下载、权限延后动作及旧 generation 回调，再清理显示源、Attachment 和评论；被屏蔽照片仍须有效会话、最新详情、相册及照片双主人身份才能确认删除。

## 环境问题与限制

- 早期在线运行被 Google Maven TLS 握手失败阻断；离线运行缺少 Kotlin/Robolectric 缓存。通过本次 Gradle JVM 的代理参数恢复依赖后，完整目标测试集成功，未更改项目代理配置、依赖版本或测试断言来跳过问题。
- 独立 javac 对本轮五个生产 Java 和四个测试类编译通过；其第一次独立 Robolectric 执行因 SDK 运行 jar 缺失未进入测试体，不能当作业务测试通过。上述112项结果来自后续成功的 Gradle 运行，不与独立结果累加。
- 整合前阶段无连接设备、无可用模拟器，尚未执行 assembleRelease 或产出新包；该构建状态已由上方本轮成功交付取代。当前仍无设备/AVD，真机/GPU玻璃、双地图SDK或线上真实账号闭环验收依然未完成。
