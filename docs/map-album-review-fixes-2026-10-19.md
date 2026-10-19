# 地图与相册 review 修复（2026-10-19）

基于 Android `030e2ffb` 的四项 review 发现进行修复，工作分支为 `fix/map-album-review`。收尾复核后与最新 develop 的扩列卡修复整合；未修改 main、后端或生产数据库，不发布正式 APK、不修改线上版本信息或下载入口。

## 修复内容

1. 地图相机移动不再清除 Overlay 的有效 snapshot。导航和地图面板共用最近有效帧的生命周期：移动期间保留，下一次有效采样替换；退出、权限失效和 provider 切换仍由原有清理路径清除。
2. 相册策略轮询实现 `onBlocked`，与预览刷新复用 `PhotoViewer.showAlbumPhotoBlocked`。实时屏蔽会清除旧媒体、取消在途请求、同步 Attachment 标记和空 URL、显示屏蔽占位、隐藏重试入口并禁用下载/画质/点赞。媒体 helper 和直接动作入口同时检查屏蔽状态，不仅依赖按钮可见性。
3. 被屏蔽照片保留主人长按删除能力，仍要求当前有效会话、最新详情、相册主人及照片主人。查看与加载屏蔽预览继续禁止；取消确认不删除，确认后使用既有 DELETE 清理链路。访客及非照片主人不能删除。
4. 错误/宝宝认证门禁为非隐藏状态提供独立的“撤回已发布的位置”操作。它复用不受赞助资格、宝宝认证或旧修订号限制的安全撤回写入。首次“谁可以看到我”选择页不新增撤回、保留当前或停止出现快捷按钮。

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
- 无连接设备、无可用模拟器。未进行真机/GPU玻璃、双地图SDK或线上真实账号闭环验收；未执行 assembleRelease，也未产出本轮新测试包。
