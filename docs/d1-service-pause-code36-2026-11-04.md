# D1每日资源暂停提示与v3.3.0/code36（2026-11-05开发交付，未正式发布）

## 范围与交付边界

用户要求D1每日额度耗尽导致服务异常时显示完整资源暂停说明，按北京时间自适应“今天/明天”，并更新v3.3.0(36)；随后确认需要旧版App兼容。上一轮冷启动修复85621052及签名b39a6653保留为基础，不涉及V4或重做阅读器。

本轮仅后端与现有Android开发，合回并推develop、交付同正式证书release测试包。不自动上线后端/网页、不推进main、不正式发布或安装设备，不调整线上套餐、额度、NBW/AI策略或版本数组，无数据库迁移。

## D1识别与旧版兼容

仅可信D1执行失败的官方明确daily row read/write limit进入此提示；普通429、翻译业务额度、泛D1_ERROR/7500、查询超时、存储满、Workers请求/CPU额度不混同。后端保原error字符串兼容并补D1_DAILY_QUOTA_EXHAUSTED机器码；正常新协议503，确认旧原生版本普通API路径429以避开旧App对5xx固定服务器错误资源覆盖。

code34/35普通首页/列表可读服务端error；发帖弹窗亦会保留原文。但旧翻译、部分小说和验证码/人机协议会自行覆盖错误文案，无法后端保证全界面；独立旧登录可能显示完整JSON，跨午夜旧屏幕字符串也不会自动更新。不会将普通okhttp UA猜为旧原生版本，不以兼容提示放松鉴权或自动重发写操作。

## 新App展示与时间

共享descriptor/formatter由中央HTTP错误和独立登录/小说传输复用，不逐页复制日期算法，不改变原status、typed error、unknown outcome或retryability。前台完整说明可多行阅读并去重，后台静默请求不额外弹窗；未知HTML不原样展示，不把暂停信息写进草稿/账号偏好/首页快照。

中文原文：

> 为了避免网络攻击造成服务资源浪费，我们每天限量提供服务资源；今日服务资源已耗尽，服务已暂停，预计%1$s早上8时重新恢复服务

有可信D1实际reset则优先；本轮仅官方每日UTC00:00规则可用，后端推算的时间标记estimated，不冒称平台actual。固定Asia/Shanghai：北京时间0:00–7:59为今天8时，8:00–23:59为明天8时；服务端与设备时钟差有界处理。显示/重新绑定/回前台更新日期词；旧错误跨过自身预计reset后提示重试确认，不能自动续成新一天或宣称服务必定恢复，不自动重发授权/发帖/上传。

版本配置更新为3.3.0/code36，保留Android8最低版本及原SDK/依赖，library自己的版本不跟改；正式版线上仍3.2.0/code34，开发版本名去beta不等于已正式发布。

## 追加ROM无缝页面返回按钮反馈（2026-11-05）

用户继续要求检查并修复通过ROM无缝动画进入页面时顶部toolbar没有返回按钮的问题。已通过实际AppKit1.4.8字节码与普通导航对照确认根因：`Nav.go()`向详情参数注入`_can_go_back=true`，`canGoBack()`直接读取该参数；无缝承载页直接创建并显示根Fragment，漏了该导航契约，非ROM不支持或自动按栈深隐藏。最小修正在`SeamlessDetailActivity`创建根页前补同一标记，点击仍沿原AppKit系统返回链、fragment返回回调和Activity finish/返回动画；不更换阅读器，不在主界面全局强制图标。真实附着platform Toolbar/ImageButton专项已通过7/7：四路参数契约及可见native返回图标、普通导航不受影响、toolbar点击返回与无重复capture、子页callback/pop与根finish、系统返回、Nav.finish、重建恢复。FriendCard详情原本自带Compose导航且隐藏native toolbar，保持原设计。Robolectric默认onBackPressed绕过真实finish override，专项局部委托实际Android9 onBackPressed后保留全部返回动画断言；不是替生产调用hook，不代表支持ROM真机验收。

## 验证状态

较大相关选择集首次声明101类，运行中发现扩列卡非D1普通5xx文案被`localizedMessage`覆盖成固定server_error，已将该分支恢复既有原文语义，D1仍走独立实时banner。首次在396条pass/10条fail日志时主动中止，保留日志与中止原因，不计作完整执行总数。QQLoginContract另两项失败已用85621052固定git archive输入运行完全相同的未改纯文件契约测试，12项/10pass/同2fail、0error/skip复现：中文资源旧路径断言及既有xxxhdpi动画WebP静态格式断言。测试/资源不改、不删除用例；最终101类回归保留并精确比较这两项基线失败，非全仓全绿。

2026-11-04首次`:mastodon:compileReleaseJavaWithJavac` exit0。2026-11-05最新专项`android-targeted2`共12类、157/157通过，0failure/error/skip，28295个源码配置文件前后指纹不变；包括版本/实际Controller HTTP、严格JSON、日期与时钟差、attached-vsync多行界面、HumanCheck UNKNOWN、NBW、QQ旧owner收尾、小说metadata与toolbar返回7项。不把源码@Test方法数当实际执行数（版本/HC部分用例参数化），也不把旧809/96和新157累加冒完整测试集。

专项首轮157项5fail全部保留：两项为dialog清理/滚动断言，显式解绑后dismiss、测试关闭平滑滚动并保终点全文断言修复；三项因Robolectric平台back替身绕过真实finish override，使用本测试局部真实平台back后验证通过。singleLine内部setText解绑、父GONE隐藏时有限边界调度、可选IP身份追踪故障传播及旧error过期metadata等复核项已定点修正。

320dp/fontScale1.6的中文深色页脚与中英文可滚动dialog实际native绘制PNG已产出，正文可完整读取/滚到尾且按钮可见；这是API28 native测试环境，不是硬件玻璃/OEM真机验收。

冻结完整相关选择集`android-final2`已完成101类、978项：975pass/3fail、0error/skip，28295文件指纹前后不变。失败为上述同2项QQ已复现基线，以及`NovelReaderSettingsActivityTest.externalDetailRootSettingsCannotTriggerTheParentReturnFinish`在读取work详情阶段8秒等待超时（尚未点击Settings，unexpected=[]）。随后不改源码/测试，独立完整该类`android-reader-recheck1`6/6通过，四份前后指纹与大选择集全同。保留原超时，不把两轮数目累加或将原975/978改写成978/978；交付门禁为`complete_with_known_baseline_and_independent_timeout_recheck`，详见仓外accepted报告。正式签名包已完成最终二进制与同源码核验，详情如下。

## 正式证书签名的release测试包

- 文件：`/home/ZYongX/projects/ABDL-Space-3.3.0-code36-d1-seamless-fix-test-20261105.apk`
- package：`top.abdl_space.app`；versionName `3.3.0`、versionCode `36`，非debug。
- 大小：168791948字节（约160.97MiB）。
- SHA256：`db383d07e93c53558a7ff2714e2f7a4ee8666dc02da3b600275212678541d75f`。
- 原正式证书SHA256：`fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`。
- `assembleRelease` exit0；证书、ZIP CRC、16KiB对齐、完整Noto字体、中英locale、地图key存在性、无缝host非导出均通过。
- 实际APK DEX中的ReaderSettings FlowRow调用均有定义并与实际Foundation1.11.2依赖匹配，不只是检查源码/旧包。
- 28295份源码配置与978项完整相关选择集及独立6项复跑一致，测试与打包前后hash未变。构建仍有既有D8/Kotlin metadata告警，未为了静音升级工具链或依赖；构建exit0和实际二进制检查结果保留。
- 旧code35冷启动修复包`b39a6653`与无缝/NBW包`cfac28cd`保留；本包包含此前coldstart修复，正式证书可覆盖安装。不自动安装、不改正式下载入口。

配套后端同轮最终full4为2205/2196pass/逐项同9旧fail，types315零新增/零消除，55文件lint0、Worker只打包dry-run0；未推main、未执行生产查询/迁移或部署。D1识别和旧版后端兼容须配套后端以后另授权上线才在生产生效；本包的toolbar修复无需等待后端即可验证。

最终验证收尾中。已覆盖官方读写/cause/result/batch和负例、保留旧34/35通用错误语义的实际TextView绑定对照（不是运行旧APK）、完整Hono入口/鉴权、07:59:59/08:00/午夜/跨月年/设备非中国时区、原生多入口/320dp大字与前台去重；较大选择集包含coldstart/NBW/翻译/reader回归，实际包的ReaderSettingsABI另做DEX解析，未完成不提前标通过。所有故障测试采用受控传输，不触发真实D1额度或真实用户内容外发。

证据：`~/.local/share/abdl-d1-service-pause-20261104/`。未完成的步骤不标通过，不把先前96或809项累加作为本轮全量。
