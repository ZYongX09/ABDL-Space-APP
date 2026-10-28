# 相册预览与帖子卡片封面缓存修复

日期：2026-10-28。用户要求修复三个问题：宝宝相册重复看图仍黑屏加载、相册帖子卡片不立即复用缓存、扩列卡帖子封面偶发不显示。基于 Android develop `b2f15107`，只修改既有3.2.0客户端；不改并行V4、后端、网页或生产策略。仍仅交付develop与正式签名release测试包，main/正式下载不动。

## 根因与修复

### 1. 重复看相册图片仍重新加载

原`PhotoViewer`相册分支每次进入先GET刷新权限，再noStore下载预览并解码；成功后只放当前holder，没有跨页/跨次打开的预览RAM。关闭或换页清图，所以“之前看过”也不会免除图片网络与解码。

新增独立`AlbumPreviewMemoryCache`：只有本次fresh ACL成功、当前会话/代际有效后才查预览RAM，同图重开或A→B→A可复用已解码Bitmap。固定受管理COS预览的内容身份只剥除精确七个签名字段，保留其他内容query；实际网络URL不改。缓存按account及同一个session/token实例隔离，8条/24MiB/单张8MiB，elapsed TTL≤2分钟且不超过已知签名期限，命中不续期；切号/退出/停止/已知撤权失效。

权限GET仍每次发生，不在返回前显示旧私有图，不能承诺离线瞬开或消除全部网络等待。protected图片（包括主人）、HD、原图和下载均不进入新增缓存，原有fresh授权/noStore及额度逻辑保持。403/404、blocked、缺预览、保护变化清RAM并拒绝迟到回包。捕获token身份以拒绝同session换token后的旧ACL/图片。

### 2. 相册帖子卡片缓存不能立刻显示

COS预览每次按当前整秒重新签60秒，跨秒读取就可能换URL；原通用ImageCache以完整URL及尺寸为key，因此同图的新签名不等于旧key。另原holder每次bind无条件清图，在仅model rebind或loader未激活时没有自己的热缓存回填。

修复同步精确top-cache直显、同identity保留图，仍由原AppKit加载器下载。新增严格`AlbumPublicCoverMemory`只为当前许可的公开、非敏感、非保护帖子投影做短期RAM别名：固定HTTPS COS host、owner/batchUUID/photoUUID/preview.jpg或webp、七个唯一有效签名参数、未知query拒绝复用；新签名本身必须有效，旧entry仍受原expiry与非滑动elapsedTTL限制，16条/8MiB/单张2MiB。不改全局URL normalizer、请求URL或磁盘缓存key，不与私有viewer RAM互通。

`AlbumUi`普通缩略图也先同步查相同尺寸top cache，命中不再经过异步回调/post；新绑定取消旧请求，旧异步回包不能覆盖新图。

### 3. 扩列卡封面偶发缺失

publication/LINK的字段取值映射正确，正常完整列表bind顺序也是同步模型绑定后同步热缓存回填，不是已证实的异步先后顺序bug。确认的缺陷是：AppKit按位置保留失败状态，扩列卡失败回调原本只清图，不接统一失败重试；短暂网络问题因此可放大为持续无封面。

两个卡片holder接入既有失败重试调度，每个holder/identity最多主动schedule两次、预算LRU64且跨同图rebind保持；全局调度可能联动别的卡片，不能称全局网络attempt最多两次。保持失败后的原紧凑布局，不新增空banner。绑定identity包括account/parent/entity/presentation/request key，复用到另一卡/URL/隐私态时清图。

公开样本p_2322封面实测200 image/jpeg、80720字节、边缘HIT约1365ms；说明该样本当时网络正常，不能据此排除所有网络故障。用户所报问题存在明确客户端恢复缺口，未凭一个网络样本宣称所有缺图都来自代码。

## 保护与展示行为

CW/private/direct/sensitive/不可用/下载保护与DTO identity都在真正调度及恢复时动态检查；隐藏状态0/null图片请求、拒绝迟到图。构造候选request无IO，支持CW展开复用原contentItems后正常加载，普通和空白CW都覆盖。相册保护占位与扩列原焦点/裁切/边框/链接版式保持。

独立只读复核没有确认阻断；public parser另有10,096项纯内存合成检查。复核范围限定本轮缓存与权限接线，不宣称全仓安全。

## 验证过程与结果

- 首轮缩略图/现有鉴权18/18通过。
- 新缓存及viewer/卡片综合110项首跑107通过，3项失败为测试夹具：两项取消请求的loopback时序超时及一项整秒签名期限误当固定60,000ms。前者改固定loopback base、独立held-server/双latch/Connection close，保持B显示时A仍未放行、迟到不盖图断言；后者按保存的实际deadline−1ms/到deadline精确检查，不延长生产TTL。精准复测全部通过。
- 首次扩大综合门禁触及旧45分钟总时限，401项已通过、无断言失败、在途测试中断，未执行assemble。保留原记录；仅仓外观察脚本将总时限调为120分钟后同产品源码完整重跑，没有删测试或降低断言。
- 完整相关综合 **407项/406通过/1失败**，唯一失败为未改动`ComposeReplyPublishingBehaviorTest.legalManualPairBodyCwAndChosenGeoSurviveRealActivityRecreation`：Robolectric后台CacheController数据库线程在空Context上打开数据库死亡。该测试文件与基线逐字相同，同源码隔离复跑通过，随后同命令assembleRelease成功。不能把407项写成一次全绿；相册/封面相关全部通过。
- 六张真实top-cache→holder.bind→nativeCanvas图已检查（相册/扩列publication/LINK各深浅色）；bitmap可见、焦点和布局正常。
- 测试与打包前后28381个源码/构建配置指纹一致；旧QQ图片格式基线问题本轮未更改，不把相关回归称全仓全绿。

## 测试包

`/home/ZYongX/projects/ABDL-Space-3.2.0-code34-album-card-cache-fix-test.apk`

151590495字节，SHA-256 `41ecc5995387e2551883fb6422e37a7c9540bdcce766817deb865c2f3128b914`。仍3.2.0/code34，非debuggable，既有正式证书`fd2098a3…`；apksigner/ZIP CRC/16KiB对齐通过。包含此前NBW V2登录与私信IME修复，旧包完整保留。

证据：`/home/ZYongX/projects/album-card-image-cache-evidence-20261027/`（日志、根因记录、匿名公共网络样本、源指纹、签名和6张renders）。本轮没有连接设备、没有访问真实私有相册/用户凭据；真实设备、真实账号与COS全闭环未代验。后端仍既有Worker788，不需此次上线后端或迁移。
