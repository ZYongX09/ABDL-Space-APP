# 宝宝新天地回复链路与主页标识跟进

基于Android develop b898598d，在fix/nbw-thread-ui-20261022保留前轮修复后实施用户反馈与追加项。仅开发线及正式签名release测试包，不更新main、版本JSON或正式下载，不覆盖用户设备。后端配套develop含09f07f8(create_reply+0085)、d906a71(profile筛选/空头像)，生产0084/0085仍未执行，旧Worker未切换。

## 前轮确实遗漏的Android路径

- 首页StatusCreatedEvent原来直接前插回复；默认个人帖流在父作者缺失时也误插。现以完整inReplyToId排除回复，重复/预加载ID不再次插入；包含回复筛选保持原语义。
- 可见与预加载转发内容的计数事件现更新getContentStatus，不误改转发外壳。
- Thread保存刚成功的本地回复，HTTP/context与事件交错按ID去重，旧快照不抹掉尚未确认回复，仍执行THREAD过滤。删除当前主帖会取消请求、停刷新、隐藏回复并关闭，晚到事件不能复活旧mainStatus。
- 现代HIDE过滤只作用于其实际context，HOME/PUBLIC匹配不错误隐藏THREAD回复。
- 通知栏NBW点赞改走已验证的轻量请求，楼层不冒操作根主题；携带原Status snapshot合并真实计数，旧pending intent缺snapshot不构造假正文。快捷回复失败同步状态明确本地保存提示。

## 追加的三个显示需求

1. 标准/紧凑帖卡用户名后追加NBWSourceSpan，只取内容作者真正nbw_UID身份。复用登录页ic_nbw彩色vector，清tint/filter，宽高均取当前Paint.textSize，随字号/密度缩放，不固定dp。普通本地用户即使绑定NBW也不冒加来源标识。
2. NBW个人主页不创建/显示相册页，首栏为timeline，tab/fragment/container/saved-state/insets映射保持一致。本地个人主页不变。GET账户帖子仍走本站/accounts/nbw_UID/statuses，由后端get_sync_threads按authorid筛选；相对Link解析同host/path的opaque max_id，不拿末条帖子ID当游标，空/短页有next也可继续。
3. 用户原图assets/nbw/nbw_headimg.png逐字节导入drawable-nodpi/nbw_default_avatar.png，1254×1254、1207307bytes、SHA256 d54a0475a200b2701d553553b9172c1c17e894b9fe3e314991329b52c193a3b5。真正NBW账户两个头像版本都缺失/空白才转本地android.resource；真实非空URL保留。注册精确URI downloader，通过Resources直接解码，供既有header/list/reply/profile共用，不网络上传，不重绘。实际ImageCache验证发现原ContentResolver路径不可用后已改直接解码，真实尺寸/加工链/pixel hash断言保留；AAPT会无损重压PNG，源码字节hash与打包后完整像素hash分开验，不强求压缩字节相同。

## 测试范围与运行边界

新增ThreadTimelineEventBehaviorTest 9项、NotificationActionHandlerServiceBehaviorTest 7项、NBWHeaderPresentationTest 2项、NBWAvatarBehaviorTest 2项、NBWProfileBehaviorTest 3项；既有6类52项完整保留。最终同一整合批次 **11类75次执行全部通过，0失败/错误/跳过**（CreateStatus含API26/28参数化），已归档最终JUnit，不按不同运行历史拼接。

Header测试使用native Canvas检查实际1em bounds与四色，不是仅source断言；Avatar通过注册ImageCache实际decode并核原PNG；Profile测试为真实生产tab/container/request方法的局部fixture，有反射mHost/mAdded，不冒称完整Profile生命周期或设备UI验收。前一轮8类首次可见footer测试缺真实UiUtils日期formatter初始化，已只补fixture，不删断言；用户追加消息中断前次运行，没有完整JUnit时不计通过。追加三类最初Account fixture缺url/avatar字段，已补生产真实格式；Profile请求fixture同时会发GetInstanceV2，测试改按实际请求类型定位而不凭数组位置。compileSdk37下Shadows重载引用移除的FingerprintManager，改静态ShadowLooper入口。Robolectric不能分配HARDWARE，先用独立Bitmap.copy探针确认Skia实际回ARGB_8888，再校验同配置解码；保留真实资源尺寸/像素/加工链测试，不能把软件测试说成硬件/真机验收。

整合构建在graphics独立worktree进行：生产Java与测试强制重编译，生产Kotlin未变化，复用已验证源匹配字节码；不生成debug，沿现正式签名/四ABI/native压缩/地图密钥守卫。真实账号/外站/真机闭环未操作；当前无设备/AVD。源码功能以c2f3d688合回并推送develop，原功能分支删除，main未动。最终assembleRelease/verifyMapKeys通过。

## 最终签名测试包

`/home/ZYongX/projects/ABDL-Space-3.1.0-code33-nbw-profile-replies-test.apk`

- 151,509,941 bytes，SHA256 `3133fde86dd7d6bd7c27ce26630b62d7278e30f3d194e37972f71cc200a9aae4`。
- top.abdl_space.app，3.1.0/code33，min26/target35/compile37，非debug；既有正式证书SHA256 fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475，V2签名、ZIP CRC、4字节zipalign通过。
- 四ABI、29个native库保留且展开SHA256与旧签名包相同；默认头像经编译资源表找到res/isn.png，逐像素比对用户原图完全一致。APK资源名优化不代表图片丢失，初版校验脚本按原文件名找不到是脚本问题，已改为资源表定位，不放宽资源存在断言。
- 同一最终源码11类75项通过，source/asset清单21路径与已提交develop和实际build inputs逐项hash一致。原8类68项和新增7项不拼接冒称，是同次JUnit结果。生产Kotlin未改复用既有匹配输出，main/test Java强制重编译，测试Kotlin重编译。
- 原包和正式下载保留；不升级版本号、不上传正式APK、不代用户安装。配套后端回复09f07f8、profile d906a71仅develop，0084/0085和Worker未部署，不能据测试包交付称线上四项已修。待获明确上线授权后才按独立迁移/部署顺序使其生效。
- 用户后续提出的AI聚合补充任务又明确暂停，本轮未调研CLIProxyAPI、未增加AI接口/模型配置/统计管理页、未改发帖AI调用。

证据：`/home/ZYongX/projects/abdl-nbw-reply-evidence-2026-10-22/`，最终android-all-verified.log、android-final-summary.json/JUnit、release-artifact.json、delivery-*及源码指纹；失败运行另保留，不计通过。
