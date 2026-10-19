# 扩列卡参考图 UI 重构（2026-10-19）

## 范围与发布边界

参考用户提供的 8 张 QQ 截图（本机 `~/Downloads/Screenshot_2026-10-09-*` 与 `Screenshot_2026-10-11-*`），重构 Android 扩列推荐、创建/编辑、主人/访客详情。截图只用于观察布局，不复制个人资料、照片或图像资源入包。

从 `develop` 的 `7b2c9b58` 开始，工作分支 `feat/friend-cards-reference-ui`。保留已整合的地图、相册、感谢赞助者修复。仅回合 develop、提交推送并交正式签名 release 测试包，不合 main，不改正式版本、下载链接、后端或数据库。

## 视觉与交互

- 扩列推荐：精简精选标题/数量、我的卡入口、刷新；24dp 圆角整页色卡、局部封面渐变、大号宣言、半透明资料标签与分组摘要，底部固定头像、姓名和圆形 Hi 私信入口。保持上下翻页、分页去重、返回重验和当前位置恢复。
- 详情：封面按 3:2、焦点裁切并渐变接入底色；无封面紧凑宣言；整块半透明公开资料区。原生工具栏随色卡统一色调。主人固定编辑/管理按钮，访客固定头像和私信，主页、原帖、举报/屏蔽保留在可滚动内容中。大字体下主人按钮纵向重排。
- 创建/编辑：三段式表单、固定完成度与阶段切换、圆角分组、独立宣言、行式基础资料和可见性图标、可移除兴趣标签、双列游戏块、真实商品库选择、独立我想找/边界声明、底部固定下一阶段/预览及本机草稿。阶段切换回顶部，普通异步刷新/输入不重建表单。
- 扩列标记帖：保留此前的中等摘要、2.2:1 真实封面与局部底部渐变，统一圆角/底色/半透明标签，不增加大私信按钮或重复作者/互动条。
- 普通帖插卡：保留标题链接，不擅自改成整张扩列卡；原帖文字及旧端 fallback 不变。

## 保留的业务边界

- 仅创建需要当前有效宝宝认证，浏览与详情不加认证门槛，不增加 18+ 或其他年龄门禁。
- 只有宣言、我想找为必填；完成度仅为填写提示，可选资料和封面可留空。
- 学校默认私密；每项公开范围、公开投影、旧端文字投影、原卡编辑语义保持不变。
- 草稿持久化、账号隔离、确定拒绝与不确定网络结果区分、同一 pending operation 重试保持不变。
- 自定义兴趣/游戏加号、输入法组合文本保护、添加后清空、库内商品及选择取消/清空语义保持不变。
- 不伪造匹配率、回复率、战绩、等级、在线状态，不引入没有后端支持的快速匹配或更换模板功能。

## 验证方法与限制

新增完成度规则及原生 Compose 组件回归/截图测试。继续使用真实 Fragment、Base ComposeView、MiuixAppTheme 的页面重组测试；补充主人/访客详情动作与真实页面渲染。

渲染使用 Robolectric API28 native Skia，对真实 View/Compose 层级执行 Canvas 绘制。PixelCopy 的窗口帧回调在此宿主不可用，最初 captureToImage 尝试超时，不作为有效截图。组件矩阵的主题使用确定性 Miuix light/dark scheme，页脚标有 fixture，不冒充真实 Fragment；另有生产 Fragment 层级截图。合成几何封面仅替换测试图片加载源，FocusImage、焦点矩阵和渐变仍为生产组件。

## 最终验证与测试包

最终同一生产源码下 `:mastodon:testReleaseUnitTest` 的定向整合选择为 **36 类 / 303 项通过，0 失败、0 错误、0 跳过**，包含扩列卡、发帖兼容、地图、相册、感谢赞助者。不是全量测试，不累计前轮次数。生产及测试 Kotlin/Java 已非增量重编译；最终整合运行使用这些最新 class，没有复用旧源码的测试结论。

原生渲染共 22 张：19 张组件矩阵（浅/深、3 个创建阶段、合成封面/无封面、320dp/1.6 倍大字等）及 3 张真实 Fragment 详情主人/访客与推荐页。已人工检查主要状态，组件矩阵中的 fixture 页脚不代表真实 Fragment 的页脚。原始目录 `/tmp/abdl-friend-card-ui-renders`；持久副本 `/home/ZYongX/projects/abdl-friend-cards-reference-ui-renders/`。

过程中实际发现并修复：游戏格宽度像素取整导致意外单列；深色表单分组明暗；合成非 Bitmap 封面超出局部区域的裁切；LazyColumn sticky header 遮挡滚入可见区的游戏删除按钮。最后一项改为独立固定顶栏、为列表预留真实空间，原有真实点击测试增加持久草稿断言，最终 303 项整合回归全部通过。此前失败日志保留，不算通过结果。

- APK：`/home/ZYongX/projects/ABDL-Space-3.1.0-code33-friend-cards-reference-ui-test.apk`，236,197,588 bytes。
- SHA-256：`79a7ff00d2e5ba6547b4a8a4ec262d68a762e2ed72c1feec40957fcf44c3b0e5`。
- 既有正式证书 SHA-256：`fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`，V2 签名验证通过，与既有测试包同签名。
- `top.abdl_space.app`，3.1.0 / code33，非 debuggable，min26/target35/compile37，四 ABI 保留。ZIP 28,504 条目 CRC 全通过。
- `assembleRelease`、`verifyMapKeys` 成功；未生成 debug APK。与线上同 versionCode，仅测试包文件名区分；构建不等于发布。
- 最终构建日志：`/home/ZYongX/projects/abdl-friend-cards-reference-ui-verified-release.log`。
- 逐类测试摘要：`/home/ZYongX/projects/abdl-friend-cards-reference-ui-test-summary.json`。
- 签名/badging：`/home/ZYongX/projects/abdl-friend-cards-reference-ui-signature.log`、`abdl-friend-cards-reference-ui-badging.log`。

本轮短暂发现连接设备，但随后不稳定且前台为其他应用；未覆盖安装、不操作账号或发帖。物理设备与真实账号业务验收仍不作为已完成。

真实在线认证、COS 上传、实际发帖/私信、旧安装包往返与物理设备覆盖安装未由离线单测或截图替代；本轮不读取真实用户 token、不伪造认证、不发布测试内容。
