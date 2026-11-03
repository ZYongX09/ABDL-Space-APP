# 3.3.0-beta / code35 反馈修复

## 本轮范围

用户要求核查多个品牌都看不到无缝动画设置的原因，并改成不支持时仍显示禁用开关；管理端新增翻译词库；液态玻璃与折扇发帖菜单新增“发小说”进入创作者中心；主页刷新文字避开toolbar/液态区域；修复小说各页自定义封面与文字占位在滑动时交替闪烁；Android版本更新为`3.3.0-beta`、`35`。

从Android develop `af5a721a`继续，不涉及V4、不重设计阅读器。日常成果只合回并推送develop，交付既有正式证书签名release测试APK；不会因版本更新而推进main、正式发布、安装设备或修改生产版本策略。词库后端与两站前端也只开发线交付，生产0103已登记id24不得重放；本轮新增0104仅本地验证。

## ROM能力核对

本地Demo附带KIT1.0.0.4/1.0.0.5门面及delegate实证仍要求boot classloader提供`com.androidex.animation.sharedview.ViewAnimationManager`及Listener、public静态完整V1 ABI与`isFeatureEnabled()`。不存在按品牌应强制启用、实例方法替代或私有反射绕过的依据。本App不会引入闭源AAR、HTTP Maven仓或动态`+`依赖。

原设置页只有检测成功才添加整项，因此不支持和检测失败的用户都看不到入口。探测ABI缓存还存在未同步发布中间状态的问题。修复将能力与偏好分开：入口始终存在，不可用时不可点击且显示具体原因，不伪造支持，也不为了禁用UI覆盖用户保存的偏好；实际导航的应用锁、FLAG_SECURE、内容隐私与来源有效性守卫保留。

## 小说入口与主页刷新

“发小说”使用统一小说功能图标，真实液态菜单与折扇菜单都接共享动作，进入既有创作者中心及资格申请视图，而非直接新建作品或绕过创作者申请。作者未获资格时仍走原业务。

主页文字保持中文“正在刷新中......”与英文“Refreshing…”，只调整其可见布局避让。缓存读取、账号/会话lease、权威网络替换、错误与过期保护不因布局修复改变。

## 自定义封面稳定性

原Compose AndroidView的factory每次重新创建会先清空图片并使用AppKit异步淡入，滑动detach/attach又取消但未恢复请求，造成文字占位与自定义图反复交替。小说专属加载层应使同一书籍/完整URL/账号会话的更新幂等，成功图有界保留；销毁、换书、换URL、退出或账号失效时正确取消并拒绝迟到回包，不把前书/前账号图片留给新内容。不改全局AppKit或封面上传逻辑。

## 版本与服务端兼容

`mastodon/build.gradle`的release版本为`3.3.0-beta`、versionCode35，网络User-Agent和`X-App-Version-Code`仍来自BuildConfig，不伪装旧版本。后端原原生UA正则不接受`-beta`；本轮最小补充精确beta及既有构建通道组合，保持锚定、拒浏览器混合或任意后缀，相关原生策略测试必须覆盖35。

这不代表生产已识别新beta UA，也不自动把35加入现有版本配置数组；任何生产配套变更须另获上线授权。

## 独立复核与追加边界修复

定点复核发现并同轮补修三处：AppKit真实HTTP异步取消可能吞新订阅（scope singleflight、500ms可撤回离场、独立attempt key、16flight/30s边界）；分屏/自由窗中global rect重复减screen origin（统一root/window坐标）；Creator快捷入口进程恢复重放（account与入口绑定、有界typed导航描述及锁定pending保存）。静态复核均确认原路径已关闭，不等于实机验收。

回归另发现系统更新假帖`app-update-required`并非后端真实可译资源，英文界面下会出现注定失败的翻译入口。共享资格函数只精确排除此sentinel，不限制普通opaque ID、机器人或中文帖子；原版本HTTP兼容测试固定中文上下文，新增英文sentinel与普通opaque对照。

## 验证与交付状态

生产Kotlin/Java编译已通过。首次测试编译因SDK37下Shadows.shadowOf重载引用已移除FingerprintManager失败，未执行测试，不计通过；仅测试改明确ShadowActivity提取。首次真实18类165项中151通过、14失败，源码前后不变：ROM43、Creator/资格14、真实液态/折扇/卡片入口、Compose封面8、HTTP取消4通过；失败分别为新几何fixture注册账号次序、legacygraphics的alpha、暂停native frame阻塞callback、旧中文资源静态断言和系统假帖翻译。均按诊断精准修正并保持真实门禁、5秒callback预算与opacity断言；完整冻结复验进行中，不把先前局部通过冒充最终全过。

后端与两站词库已交develop `09dfeb0`/`0b7b7e1`/`0103718`，无生产部署/0104迁移；后端2115项2106通过、同基线9fail，最终182翻译/词库专项全通过、types328无新增及Worker dry-run。两站474/474、483/483及build过；实际浏览器多别名保存/世代同步/搜索/草稿保护/320深浅和大字已验，移动截图工具超时不记视觉通过。

分批最终真实执行：封面5类43/43、导航/ROM/几何/发小说/版本/Creator恢复13类124/124，均零fail/skip/source变化。几何原点fixture曾只改attach-info被traversal重置，已同步真实ViewRoot.mWinFrame且保留全部非零原点断言；此前中断仅69pass/2fail的无完成报告不算全过。两组是分次运行，不称单次全仓167通过。

## 追加：阅读设置点击退出

用户后续补充“小说阅读页面点击设置按钮会退出阅读页面”。静态实际class与旧code34 APK DEX交叉确认：reader-core用旧Compose foundation-layout1.7.6编译的FlowRow调用，缺少新Alignment.Vertical参数；App经miuix已运行1.11.2，APK内无旧签名，设置弹层进入即有NoSuchMethodError，不是返回/finish回调问题。只将reader-core的foundation编译依赖对齐App现有1.11.2，不更换FlowRow、阅读布局、分页、BOM、target或AppLock。

新编译class已改为App运行库存在的新FlowRow签名，独立复核同时确认现有Material3四种调用精确descriptor均兼容，无需扩大BOM或SavedState版本pin。最初离线编译/核心测试因既定传递artifact缺缓存失败，单次联网解析补齐后编译成功；独立reader40/40、editor27/27已真实强制重跑通过且source不变，旧XML不计本轮。

新增App模块真实Activity设置点击、Back/scrim、同章节source anchor及锁定/注销门禁6项首轮因精确offline fixture未建模实例metadata等清理失败，未计通过；只修fixture正常默认配色、精确路由建模和小说内容零请求门禁。最终新6项、原ReaderBehavior5项、Polish6项合跑17/17，全部fresh、0fail/skip、源码指纹前后不变；原legacy默认slot首轮分页等待失败的用例本次不改源即通过，不把未证实原因称为生产回归。普通书详情、外部详情根、直接章节intent实际点击Settings均保持Reader及source anchor，关闭面板不退出宿主，应用锁/失效账号守卫保持。新beta35 APK的真实DEX链接验证仍待最终打包。

最终40类相邻回归391/391全部fresh、0fail/error/skip、源码前后28268文件指纹一致，含首页历史、骨架、翻译、相册、NBW、英语/大字、小说截图与阅读编辑等。本轮采用串行分批验证，不将分次有重叠专项累加成单次全仓测试。正式签名release测试APK正在构建，包内版本/签名/CRC/16KiB对齐/完整字体与地图凭据，以及严格读取新code35 DEX中Settings FlowRow调用/定义一致性由交付脚本核验。

不会以旧XML、UP-TO-DATE结果或代码静态断言冒充本轮真实执行。未安装到设备，未正式发布App；真实支持ROM上的硬件动画效果不以Robolectric fake-ROM通过替代。

证据：`~/.local/share/abdl-beta35-20261103/`。
