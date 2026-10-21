# 扩列卡整合页、全屏详情与入口调整（2026-10-21）

基于 Android develop `9beac3d5` 实施用户八项调整；后端默认值合同基于 develop `c3e5242`。开发只回合/推送 develop、交正式签名 release 测试包，不推进 main、不发布 App、不执行生产 SQL 或迁移。已上线的社交账号服务不等于本轮后端默认值变更已上线；新版 Android 显式发送新建字段公开状态，不依赖旧后端的默认推断。

## 八项调整

1. 新建卡的所有可选资料默认公开，包括学校；新添加社交账号显式公开。用户可逐项隐藏。既有卡、已保存草稿的字段列表及逐行 false 继续以原值为准，不批量公开旧数据。后端省略字段时的新默认值另有旧规范化 hash 的有限兼容，不改既有操作快照或认证门槛。
2. 详情背景从原生 root y=0 延伸至透明系统状态栏下方；状态栏/刘海实际 top 只用于悬浮按钮和文字避让。保留原生左右安全区、底部手势/导航和编辑页键盘避让；不修改 Activity 全局 decor flags，API26/27 不调用 API28 cutout 方法。
3. 隐藏/删除经典及液态主页 More 的扩列卡推荐入口，删除本人资料右上角菜单里的我的扩列卡入口，同时移除对应处理。保留兼容 ID 符号不等于保留可见菜单。
4. 将扩列广场与我的卡片合为同一 Fragment 状态所有者、底部两页胶囊切换，不增加全局主导航 Tab、不叠加 Nav 栈。参考 `assets/card.pen`、`card2.png` 及实际为 PNG 的 `card1.svg`。主次操作、真实当前状态、历史卡、空态、搜索和本地已加载卡片分类遵循项目功能，不制造在线标记。相同公开地区筛选不读取 GPS；无本人公开地区会说明缺口。分页沿服务端 cursor 追加，不假随机重排。
5. 普通发帖删除“插入我的扩列卡”菜单项，底部工具栏加入图标，复用既有读取本人当前卡、预览/移除/替换、卡片单独发布与禁止 NBW 同步链路。六个按钮统一36dp触控框/6dp padding/24dp绘制区，横向滚动按钮组，字数计数器固定在滚动组外。
6. 主页“发交友帖”恢复旧 `FriendRequestCreateFragment`；经典折扇和液态菜单新增独立“扩列卡”，进入整合页的我的卡片。四项菜单关闭动画按实际项数计算。
7. 图标偏小的主要原因是原 PNG 大量透明留白，而非仅位图格式。对原始 alpha 轮廓机械描摹成24dp VectorDrawable，形状统一占22dp主轴，四周约1dp光学边距；保留五个前景组件、人物/卡框/背卡/加号洞，不重新设计图形。已删除同名 nodpi PNG，避免资源选择仍覆盖矢量。原 Downloads 图片保留。
8. 普通插卡帖的倾斜54×75dp缩略卡按实际旋转后包围盒预留边距，再加1物理像素AA余量，行内不按未旋转内容区剪切。保留白边、-4度倾斜、内层合法封面圆角裁切和隐私请求守卫。

## 状态一致性

整合页隐藏/退出清空当前、历史和推荐数据并取消旧 generation 回调；返回重验后按稳定卡 ID 恢复位置。当前与历史由独立响应读取，若发现同/新版本历史反映当前卡已被替换，最多自动重新核对一次，重复冲突清数据并要求刷新，不显示失效管理按钮。渲染与 key 表都排除相同 ID，避免 LazyColumn 重复 key。

底部加载按钮使用包含当前已加载数量的 key，避免追加后锚点跟着按钮跳过新卡。非卡片行保存最近前置卡 ID、行 delta 和 offset，返回时沿有界 cursor 找回该卡再恢复底部，不仅保存一个会被第一页钳制的 index。搜索只读公开的宣言/兴趣/我想找/地区，社交账号、学校等不加入本地关键词检索。

## 图标轮廓验证

原图 `~/Downloads/friendcard.png` SHA-256 `f8408b9fc963661a424ffa2a5deced29222a8759fe10cb073bdcf84a27fbb08a`。按 alpha≥128 描摹曲线并统一缩放，源形状区 `(254,239)-(997,1002)`。矢量5 path/6闭合轮廓、180立方曲线段，换行拼接 pathData SHA-256 `74d7c41c34be4ba8c431bf13f5a7e3624c771caa83826e40a8817432691a3722`。

原尺寸对齐轮廓 IoU **99.59198%**，紧裁区 alpha MAE **0.66953%**；不是逐像素完全相同，剩余差异为曲线拟合和抗锯齿。24px归一化尺寸下源图重采样与矢量 alpha MAE1.7177%。已人工查看白底源图/描摹/叠加图及24/48/72px比较。

## 验证与交付

后端默认值专项从61/61增加到70/70，scoped ESLint/diff-check通过。新鲜完整序列基线617总/608pass/9fail，对比最终626总/617pass/9fail，失败名称多重集合相同，新增9项通过；未宣称全项目类型或全量测试全绿，未部署该默认值增量。证据 `/home/ZYongX/projects/abdl-friend-card-public-defaults-evidence/`。旧已部署社交账号及LINK能力保持原状。

最终 Android 定向整合回归 **46类 / 370项全部通过，0失败、0错误、0跳过**，包括 hub/我的卡片/广场/入口路由/底部插卡/默认公开/全屏状态栏/缩略卡裁切以及此前地图、相册、社交账号、液态菜单回归。不是全项目全量测试。最终日志 `/home/ZYongX/projects/abdl-friend-card-hub-final-release.log`；逐类摘要 `/home/ZYongX/projects/abdl-friend-card-hub-test-summary.json`；JUnit归档 `/home/ZYongX/projects/abdl-friend-card-hub-final-junit.tar.gz`。

原生截图持久副本 `/home/ZYongX/projects/abdl-friend-card-hub-renders/` 共21张，含我的卡片/广场浅深色、空态/空历史、真实详情状态及8种插卡封面/失效/长标题。图标矢量证明持久副本 `/home/ZYongX/projects/abdl-friendcard-vector-proof/`，轮廓IoU99.59198%。后续对同一生产源码再次强制非增量编译的功能专项26类170项全部通过；该轮不与370项相加，独立摘要/归档为 `abdl-friend-card-hub-final-feature-{summary.json,junit.tar.gz}`。

正式签名测试包（不发布）：`/home/ZYongX/projects/ABDL-Space-3.1.0-code33-friend-card-hub-test.apk`，236,320,296 bytes，SHA-256 `e0274982553e9e7efb172f8eef77296229e97cab353bbe90a6dd02a54b068609`；V2签名通过，证书SHA-256 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`；top.abdl_space.app 3.1.0/code33，min26/target35/compile37，四ABI；verifyMapKeys通过。

不使用真实用户token、不伪造宝宝认证、不在生产创建测试卡或改写账号。软件 native Skia 截图和离线回归不替代物理 GPU 玻璃、真实输入法、旧端和双账号业务验收。
