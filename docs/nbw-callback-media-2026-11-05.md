# NBW授权回跳与备用接口配图修复（2026-11-05开发交付，未正式发布）

## 用户反馈与边界

1. NBW授权后浏览器唤起App，画面闪一下又回浏览器；手动返回App发现授权成功。
2. 当前备用接口下V2同步帖子显示裂图。

用户批准先定位并修复，基于Android develop93f65ac3创建fix/nbw-callback-media-20261105。仅开发、原证书release测试包及develop交付，保持3.3.0/code36，不覆盖旧db383d07包。本轮不自动发布后端/网页/App、不安装设备、不执行迁移或改主备/能力策略；此前D1后端825上线授权不沿用。

## 授权回跳定位

绑定/重新授权完成hint分支只消费内存AuthorizationTicket并刷新认证repository，随后直接finish OAuthActivity，没有恢复原授权owner页面/task。这与服务端已授权、临时router关闭后露出浏览器的现象吻合。独立NBW登录已有completion/installer导航，但也未定向真实owner task，不能只凭CLEAR_TOP|SINGLE_TOP保证跨task复用。

修复沿现有flow/ticket补进程内owner task/component/来源，严格回调后先恢复已验证的自有owner，再关闭router。优先保原页面与show_back/account语义；不清空绑定任务栈、不拿当前账号兜底、不自动重发authorization或exchange，PKCE/completion proof及TTL/单次/会话门禁不变。真实系统task/OEM验收不以Robolectric替身冒充。

## 备用公开图片取样

2026-11-05 02:51–02:52UTC，仅匿名公开GET：discovery200，mode=backup/remote_mode=full；小页limit3实际3项。取实际DTO中同一公开帖的两附件9084/9085，url/preview_url/remote_url均为备用域/data/attachment/下无query的JPEG，没有猜路径或换host。

- 两图HEAD200 image/jpeg；有界Range GET最终206、JPEG magic、530245/407093字节，与Content-Length及Content-Range总长相同。
- 不带Cookie/Referer/Authorization/API key，不跟重定向；DNS解析全部公网、固定已验IP连接、TLS SNI/主机名验证。
- 首图首次GET在收到458752字节后触发诊断脚本20秒总流时限；同URL/IP/UA唯一安全重试2.748秒完整，第二图2.895秒完整。该事实不能直接证明App15秒单次读取超时。
- 无404/403/HTML/重定向/TLS错误；不能再把备用资源缺失或签名失效当已确定根因。未比较猜造的主域URL，不宣称真实主备资源合同等价。

App实际取图使用AppKit1.4.8 HTTPImageDownloader独立OkHttpClient，仅UA由MastodonApp设MastodonAndroid/3.3.0，不复用API认证/协议。标准NBW媒体已有URL投影及scope，不应通过删除scope、添加Cookie或回退故障主域掩盖裂图。

已通过实际AppKit1.4.8字节码确认本地缓存缺陷：NBWImageRequests.ScopedRequest.getDiskCacheKey把`/nbw-...`拼在原hash后；DiskLruCache.Entry把key直接当相对文件名构造`root/<key>.0.tmp`，只创建root，不创建hash子目录。HTTP响应成功写FileOutputStream时会因不存在的`root/<hash>/`目录报FileNotFoundException。真实ImageCache→受控HTTP→PNG解码红测`media-disk-red1`已实际复现1项1fail：HTTPImageDownloader收到响应后，ImageCache.lambda$getInternal$6写带`/nbw-...`缓存路径时FileNotFoundException。媒体生产与新回归test前后SHA相同；当轮仅4个独立callback测试文件继续补齐，已单列指纹核验，不能把该红测称全仓冻结门禁。随后仅将完整scope key编码为安全平面hash文件名，保留revision/session/权限隔离、不改服务器媒体地址。

## 验证状态

`media-disk-green1`已用未修改的同一回归test由红转绿：1/1通过，0error/skip，28298份源码配置前后不变。真实AppKit ImageCache/HTTP downloader从受控MockWebServer下载本地产生的PNG，写平面缓存文件并native解码；primary/backup均验，不是手动调用callback或预填缓存。修复仅构造期对完整source+scope生成平面hash，内存key、等价比较与generation/session/TTL守卫不变。

回调实现已落并独立定点复核；旋转前尚未打开浏览器的D1 FAILED提示恢复边界已修，仅允许原有效内存事务display-only恢复，不给completion/exchange新权限。`callback-targeted1`实际5类57/57通过，0fail/error/skip，Gradle exit0；执行期仅另一owner增加媒体测试，整仓source fence列出该单一测试文件变化，故保留结果但不当最终冻结门禁。媒体增强首轮`media-delivery-full1`5项均因缓存异步打开/主线程等待未就绪失败，保留记录；只补测试有界disk-ready等待及主线程pump后，`media-delivery-full2`5/5通过、0fail/error/skip且28298文件指纹无变化。覆盖真实View/List→MediaGrid显示，主备双向、same-URI revision/策略撤销、token/session/切号再切回/绝对TTL的迟到解码不交付、同端点STALE续租及非NBW原始请求仍正常。未手调callback、未放宽生产安全gate。最终冻结`final-nbw1`已完成30类、315/315通过、0fail/error/skip，28298份源码配置指纹前后不变。包含callback57项、媒体5项及NBW传输/协议/缓存/权限、D1提示、冷启动和原无缝返回相关回归，不把红绿/专项数目相加称全仓。签名包同源码构建及实际二进制核验已完成，见下节。任务路由在AppTask API边界用受控seam模拟分发，真实生产router选择/Activity回调仍执行，但实际系统/OEM任务栈行为不以测试seam冒充。

公开媒体取样验证响应/magic，受控App链验证写盘/解码，两者都不替代真实设备复验。仓外证据`~/.local/share/abdl-nbw-callback-media-20261105/`，不保存公开帖子正文、完整媒体URL、真实图片字节或有效签名。

## 正式证书签名的release测试包

- 文件：`/home/ZYongX/projects/ABDL-Space-3.3.0-code36-nbw-callback-media-test-20261105.apk`
- 大小：168796716字节（约160.98MiB）。
- SHA256：`6f352765d07f23abb5544f86a96499783a09eff7bb8d99e1e551907dc5c86380`。
- package `top.abdl_space.app`，versionName `3.3.0`、versionCode `36`，非debug。
- 原正式证书SHA256：`fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`。
- `assembleRelease` exit0；签名、CRC、16KiB对齐、完整字体/中英文locale、地图key存在性及无缝host非导出检查均通过。
- 实际APK DEX中的ReaderSettings FlowRow调用在当前Foundation1.11.2中有对应实现，未更换或重做阅读器。
- 28298份源码配置与最终315项相关回归一致，测试、打包前后无源码变化。不把不同阶段的57/5/315相加成全量；315项是本轮声明的相关选择集，不是全仓测试。
- 旧code36 D1/无缝修复测试包`db383d07`保留，本包包含其开发基线；同正式证书可覆盖安装，不要求清数据。文件名和SHA区分两个同为code36的包，不更新正式下载。

本轮只有Android源码改动。后端仍main f277cb4/Worker825、develop19f3d1e；网页、数据库迁移、主备及能力策略均未修改。两项修复无需配套后端重新上线。本包尚未安装设备，真实授权成功后前台任务栈及OEM效果仍待用户实测，不能以AppTask边界测试seam替代。
