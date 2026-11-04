# 系统级无缝动画扩展与NBW全链路配置（开发线，2026-11-03）

## 范围与发布边界

本轮基于develop `20bf457d`，不涉及V4，版本维持`3.3.0-beta / 35`。用户要求修复帖子列表进入详情未触发ROM动画、接入纸尿裤与帖子内交友宇宙卡详情、支持设备自动默认开启并改名“系统级无缝动画”、减少液态首页顶部留白、统一深色折扇“发交友帖”背景，并对照Demo执行性能/体验优化；NBW要求管理员切备用后相关功能完整跟随。

只合回并推送develop、交付既有正式证书签名的release测试APK，不自动推main、正式发布、安装设备或变更生产NBW/AI配置。旧beta35测试包fbaa79de保留不覆盖；后端0104/id25等已执行迁移不得重放，新0105只本地预演。

## ROM实现约束与修复方向

本地SharedViewDemo及KIT门面仍委托boot classloader的公开ROM接口，不是APK通用动画引擎，不按品牌伪造支持、不引入闭源AAR/HTTP仓/动态版本。无法使用时保原导航；应用锁、FLAG_SECURE、敏感内容、账号会话、真实attached及source binding安全门禁保留。

旧帖子路径与小说/相册不同：帖子多row合成截图要求header/footer同时可见，reblog内容ID剥离点击occurrence，quote/不可见附着row又可能让绑定失败；高密度屏幕普通图文帖也容易超过1024²像素硬阈值。修复以真实点击occurrence定位，只绘制对应授权可见row及其实际clip，在原像素内存预算内缩采样，不使用整个RecyclerView截图，也不把邻帖/消失动画的私有像素纳入。

纸尿裤和交友宇宙卡复用现详情业务与非导出窄宿主，保留权限、账号及返回来源重绑/回收回退。返回前同步刷新有效落点，不持久化截图、私有正文或动画session。性能优化针对实际重复capture/硬预算回退/pending timeout保留等已证实路径，不把既有反射Method缓存重复算优化，也不虚报真机帧率。

## 设置与主页界面

设置改名“系统级无缝动画”，支持设备无保存选择时默认on；明确关闭和legacy false均保留，不在每次resume强制覆盖。普通全量偏好保存不把暂时不可用的视觉unchecked写成用户选择。不可用设备仍可见禁用项及原因。

液态首页只避让真实绘制玻璃边界与必要间距，不叠加固定toolbar高度、重复系统inset和列表顶部padding；普通模式真实toolbar保留。所有几何变化不能改变首页历史缓存权限、lease或网络行为。深色折扇仅将交友项背景与其他项统一，不擅自重做浅色设计、动作路由和小说图标。

## NBW客户端协议

新增公共`GET /api/nbw/endpoints`：schema_version1、opaque revision、primary/backup、api/authorize/public/media四个origin、ttl_seconds30，以及remote_mode。四origin只能是当前mode对应的两个固定HTTPS主机，拒userinfo、端口、编码域名等旁路，不能把任意https当可信。公共配置不带账号、token或secret。

主备决定请求去哪里；full/read_only/local_only及指定版本仅本站仍决定允许什么，取更严格能力。新客户端对snapshot/endpoint revision变化使旧NBW远端缓存和晚回包失效，不清本站数据；前台短TTL单飞刷新，后台停止，不为每张图片各发请求。

V2 login/bind start保持原PKCE/flow/completion/callback，新增endpoints快照用于严格校验返回授权地址。授权/token/userinfo由服务器固定同一endpoint；切换时不自动把原code重发另一域名。旧server缺新合同不能假装已全链路切备用，旧App常量也不能靠后端魔法改写。

媒体、头像、原文和主页在渲染/实际取用时经集中纯URL投影，仅映射可信NBW来源，保path/query/签名和stable UID/acct/uri，不修改任意用户正文或全局HTTP目的地。已进行OAuth单独保持原快照或明确取消重开，不用新地址重试写操作。

## 独立复核与回归边界

帖子路径按精确 occurrence/quote/reblog、真实 attached row 和祖先可见性定位，源图不混入 RecyclerView 装饰或邻帖 overdraw；每次有效点击至多一次 capture，单图≤1,048,576像素、会话总保留≤2,097,152像素、最多4个会话。稳定卡片走 ROM View API，不重复截应用位图。pending watchdog 在 attach/取消/失败时移除，不持有延迟 Session lambda；返回同步更新 bounds，不再截图。附卡投影用无正文 typed stamp 防御性校验，变化后回退；未将未证实的原地 DTO 变化描述成生产泄漏。

独立定点复核发现的 NBW 五类问题已修：正常 TTL 更新清远端列表、NBW失败误关本站图片、音频缺认证能力租约、inline头像过早固定URL、AppKit equals 不比较自定义缓存key导致同URI旧代回流。现29秒提前续期但保留原30秒绝对到期；同revision正常更新不清空列表，失败不得延长租约，授权前强刷失败不能发POST。音频/图片/下载持当前账号、token、认证能力与endpoint世代；实际AppKit交付 equality/source 比较纳入scope。Profile刷新成功恢复可见。复核均为 focused 静态检查，不是全项目审计。

折扇320dp/fontScale1.6真实测量发现英文相册标签把48dp圆压至40×48；现标签优先让出宽度（weight、单行ellipsis），圆形保持完整48dp。深色五入口背景统一，浅色配色保持。两类定点回归14/14通过、源码前后不变；截图显示深浅色五个按钮、统一小说图标、设置标题换行与默认/不可用状态。两个软件画布无法执行的液态320/大字shader栅格用例已明确移除并留注释，未用skip/Assume假称通过；既有液态几何、实际bounds、hit-test、占位/Insets回归保留。Robolectric无法构造/绘制miuix的RuntimeShader，真实玻璃高光/模糊视觉与真实ROM动画仍须支持设备验收。

本轮新鲜独立reader-core40/40、novel-editor-core27/27通过，且两独立模块源码未变；阅读Settings的FlowRow ABI防回归保留。最终固定源码一次串行运行 **86个真实测试类、809/809通过、0 fail/error/skip**，前后28282个源码/资源/配置指纹完全一致；不是全仓测试，也不把多次重叠定点累加。覆盖真实holder/touch链、高密度bitmap、quote/reblog/private祖先、纸尿裤/交友原详情及返回、设置开关与偏好、首页/菜单、NBW传输/严格解析/授权/TTL/ABA/晚回包、AppKit实际交付、原有小说/阅读器/封面/翻译/相册门禁。同一源码串行 `assembleRelease` 成功；签名、非debug、CRC、16KiB对齐、完整Noto字体、地图key存在、实际en-US/中文LocaleConfig、非导出详情宿主，以及新APK内ReaderSettings FlowRow调用精确definition均核验通过。保留既有构建告警，不称零告警。

正式签名测试包：`ABDL-Space-3.3.0-beta-code35-seamless-nbw-test-20261104.apk`，168,773,904字节（约160.96MiB），SHA-256 `cfac28cd8dc5722a7c7a9d27d914e9f2628db9659672f071403ddd7d14cf1b8b`，签名证书SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`。版本仍 `3.3.0-beta / 35`；旧feedback包fbaa79de完整保留，新包独立文件名，不覆盖旧包、未安装或正式发布。

全量和包核验后只加强一个测试文件：赞助原图契约改用准确匹配账号切换listener及其内部失效调用的跨行断言，并以内存删除该唯一调用的反例确认测试会拒绝。该类新鲜4/4通过，生产源码/资源/配置/APK均未变。809项完整选择集和此4项定点是两次运行，不累计成813项，也不宣称加强后的全部测试重新完整跑过。

## NBW上线依赖（本轮未上线）

配套后端develop `8e2861f`、主站 `b8bb02e`、移动 `122b08c`已推送；后端完整2149项保留逐项相同9基线失败，types无新增，36文件实际lint与Worker只打包dry-run通过；两站完整508/508、515/515与build通过。三者生产main不变，候选0105未生产执行。

新App面对仍缺新discovery的旧生产后端会明确限制NBW入口/图片，不伪造primary可用或偷偷降协议；要在实际环境启用本轮全链路能力，须另行授权上线后端/必要0105及两站，再用新App。旧包无法由后端远程改掉固定授权主机。真实NBW备用域资源、Cookie和合作方回调注册仍未代验，不把mock当上游闭环。

完整协作合同及证据在`~/.local/share/abdl-seamless-nbw-20261103/`。
