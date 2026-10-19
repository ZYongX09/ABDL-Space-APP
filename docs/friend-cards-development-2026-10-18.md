# 扩列卡 v1 开发与验证记录（2026-10-18）

## 范围与发布边界

本轮按已批准方案在 `feat/friend-cards` 实现，基于graphics-worktree的develop `4b54e601`；不使用陈旧friend-map主工作树。后端已在独立仓开发并合入develop `8944ae9`，新迁移为 `0080_friend_cards.sql`。本轮未将扩列推进main、未执行生产0080、未部署Worker、未更新正式APK下载或版本号。测试包构建不等于正式发布。

新版仅替换旧交友创建/浏览入口，旧friend_requests接口、历史交友阅读保留；五栏导航与独立地图不变。两个网页无需新页面即可阅读文字fallback，不宣称已支持网页结构化卡片UI。

## 2026-10-19 review 修复（开发线，专项已通过）

独立 `fix/friend-cards-review` 基于最新 develop `030e2ffb`，未接触并行地图／相册修复工作区。

- Base 将 revision 传入真实主题内容及各 Screen；各页对业务字段、页列表和商品选集生成本轮只读快照，LazyColumn／VerticalPager 不再隐藏捕获同一个普通可变容器。
- 不通过 `key(revision)` 重建整屏；推荐请求不驱动跳页，只有显式刷新信号才复位；商品搜索只在查询变化时归顶，不在勾选或分页时归顶。
- 删除卡片发送空 JSON `{}`，让真实请求携带 `application/json`；不放宽后端写入请求守卫。
- 新增独立真实 Compose 页面回归与 Controller→MockWebServer HTTP 回归。前者保留真实 Fragment／Base ComposeView／Miuix 主题／生产 Screen，仅替换远程执行与导航，不使用 LocalViews。
- 使用 JDK21 顺序重新编译 release Kotlin、Java 及全部测试源码，通过。最新单次扩列／兼容专项 **92/92，通过14个测试类，零失败／错误／跳过**，包含新增11项真实 Compose 与2项真实HTTP；由最终JUnit XML逐项统计，不累加历史运行。覆盖首帧资格加载退出、错误重试、连续输入、未提交标签及焦点／选区／滚动保持、公开/旧文字预览、商品返回、详情／历史回包、分页追加和选集计数。
- 日志 `/tmp/friend-cards-review-android-targeted-final.log`，逐类计数 `/tmp/friend-cards-review-android-targeted-summary.json`。先前新测试曾有5处API链式Unit编译错误及1处hint消失后定位失败，均只修测试调用／定位，最终11项真实页面完整通过；没有替换主题或屏蔽业务断言。
- 另一任务的Gradle运行期间本轮停止自己的并发尝试并等待，未停止对方进程。根分区剩余空间不足1GiB且不支持reflink，最终通过仓库外临时Gradle init脚本将本轮buildDirectory指向 `/tmp/friend-cards-review-android-build`；未改项目Gradle配置、系统SDK或删除用户缓存/旧APK。临时构建从本轮源码重新编译，不复用其它工作区APK。
- 本轮未重新运行Android全量；原全量OOM/旧QQ资源断言限制仍保留，不宣称全项目全绿。USB设备现已可见，但本轮尚未在其上安装新包或完成真实后端／COS／双账号闭环。

## 功能

- 创建/编辑三阶段与发布预览；互动习惯自由文本且示例不保存。
- 兴趣自定义输入右侧圆形加号：非空启用、去重、成功加上方并清空；常见游戏预设和自定义名称。
- 独立库内纸尿裤搜索多选，跨搜索/分页选集、取消不改、零项清空、账号/草稿绑定；返回图文标签，主体打开既有商品详情，移除按钮独立。
- 本机按账号及卡隔离草稿；发送前持久化不可变operation快照，结果不确定只能同键同载荷重试。
- 我的当前卡、历史、暂停/恢复、删除；编辑原卡不重发或顶帖。
- VerticalPager一屏一卡推荐，分页/游标去重、返回按卡ID恢复并重验；空/全重复批次仍有游标则续载。
- 完整详情、商品图文标签、关联原帖、当前账号私信、举报与屏蔽。
- 仅创建检查当前有效宝宝认证；未认证/审核中/驳回/撤销分别说明并跳既有认证页，返回重查；不加18+或认证读取门禁。
- generic COS封面上传，10MiB限制、图片压缩/EXIF清理、原资源ID及焦点，帖子2.2:1和详情3:2预览，不使用参考截图作为正式素材。
- 帖内中等摘要：真实图片下沿局部渐变和两行标题、精选关于我/兴趣游戏/两行我想找、标签4+N；学校/互动习惯/全部商品不在帖摘要展开。无图/加载失败紧凑收缩，真实图片成功才展开。
- Status可选扩展字段容错，未知或损坏schema回退文字；保留原帖头尾/CW/过滤/媒体。普通帖只显示标题链接，精确剥离服务端HTML尾注，不吞用户正文。
- Compose插卡、预览/移除与编辑回显、friend_card_id三态；不向不支持此扩展的NBW误同步。自动卡编辑进入卡编辑页。

## 验证

### 已完成

- 隔离于含未提交相册实验修改的graphics工作树，以仅含扩列提交的工作树完成 `:mastodon:assembleRelease --offline`。构建为3.1.0/code33（保持已发布版本号/包名），输出先写在隔离树构建目录，不覆盖原APK。
- release Kotlin与Java源码编译通过；因恢复被清理的依赖缓存及设备内存/磁盘有限，首次完整构建耗时较长。隔离构建使用临时 `/tmp` SDK平台视图和现有本地配置的临时链接；未修改仓库Gradle配置、未升级依赖、未触及Cloudflare。
- 发布用测试包副本：`/home/ZYongX/projects/ABDL-Space-APP-3.1.0-code33-friend-cards-test.apk`，**236,145,560 bytes**，SHA-256 `408274cd0b967ccc17941f5f849d5d0235af595fe9c4a76233f7683d3e64c34b`。包名`top.abdl_space.app`，versionCode 33 / versionName 3.1.0，minSDK 26 / targetSDK 35。
- `apksigner verify --verbose`通过，V2 signer SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475` 与原正式证书相同；Gradle `verifyMapKeys`通过。原图形工作树APK SHA为`f65af923...`的独立地图包保持原样。
- 初次交付的扩列专项 release 单测 **79/79通过**：12个测试类；包含本地状态/草稿/DTO、真实Fragment回调、焦点矩阵、帖子wire/drawable、navigation/Compose及兼容分支。Fragment回调测试替换了Compose视图，**未覆盖本次review确认的真实重组问题**；不能把这79项作为页面刷新验收。
- 后端初次交付完整测试 **578总 / 569pass / 9既有fail**，比当时基线新增38pass，失败名称集合不变；专项145/145，类型329诊断无新增。当时独立复核修复的是尾注撤回、墓碑封面引用、source、正文长度和投影边界，不等于本次review的五项已经关闭；这不是完整安全审计。
- 后端Worker `wrangler deploy --dry-run`本地打包成功；**只dry-run，没有执行部署或生产迁移**。
- 独立页面复核的两项P2（Float裁切反区间、空推荐批次停载）已修，并纳入初次交付专项。
- 主代理复核纠正了最初holder与已确认中等设计不符的问题，叠字、局部渐变、摘要字段排除、加载失败收缩均有真实View绑定测试。
- `git diff --check`已检查。

### 未通过/未完成

- Android完整release回归曾实跑 **1243测试 / 14失败**：旧FriendUniverse源码合同断言和AlbumReport异步Toast时序两处之后做了修正；但完整run还包含11项Robolectric SDK资源加载 `OutOfMemoryError` 及1项旧QQ xxxhdpi WebP VP8L格式断言（资源实际为VP8X）。完整suite修正后的最终结果**尚未取得**。之后尝试1GB Robolectric堆的full rerun，仍有Android 15 SDK资源加载OOM，完整报告未能完成；两项兼容测试被同时运行的full任务抢占过一次，也没有得到最终定向重跑报告。因此不能宣称full绿或回归0失败。
- Android扩列 release专项重跑 **79/79通过**，涵盖对应模块/合入的旧导航及Compose行为测试。
- 正式签名release包确已构建并交付，详见已完成项。Linux无模拟器/USB，未完成真机视觉、小屏大字/暗色、真实服务端/COS网络、双账号及真实旧安装包闭环；构建和单测不替代这些验收。
- 再次完整测试若需稳定，应在有足够可用内存的环境/Android CI顺序运行；保留QQ资源格式独立失败，不替换既有资源、伪造全绿。

## 日志与旧包保留

日志在仓库外：
- `/home/ZYongX/projects/friend-cards-android-baseline.log`：早期基线因ENOSPC中断，不能作通过依据。
- `/home/ZYongX/projects/friend-cards-android-compile.log`：首次release编译通过。
- `/home/ZYongX/projects/friend-cards-android-targeted.log`：75项专项通过。
- `/home/ZYongX/projects/friend-cards-android-full-tests.log`：完整回归首轮，状态见后续更新。
- `/tmp/abdl-friend-cards-reviewed-full-tests.log`等：后端结果。

既有地图/感谢榜测试包已保留为仓库外 `ABDL-Space-3.1.0-code33-map-thanks-before-friend-cards.apk`，SHA256 `f65af923aa52f74ab1cef03657e950071a44293fe57402beca2e0a6dfb525ab7`，不是本轮扩列包。正式证书SHA256应为 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`。
