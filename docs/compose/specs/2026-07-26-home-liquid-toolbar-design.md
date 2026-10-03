# 首页顶部 Liquid Glass Toolbar 设计

## [S1] 目标与范围

仅在 `GlobalUserPreferences.useIosLiquidNavigation=true` 时，将首页时间线页顶部 Toolbar �造成两块独立 Liquid Glass，并将相关菜单改为玻璃锚定浮层。其他主 Tab、二级页面以及关闭液态导航后的旧 Toolbar/FAB/PopupMenu 行为保持不变。

左侧玻璃承载时间线图标、标题和下拉入口；右侧玻璃仅常驻发帖与更多按钮。公告未读和应用更新提醒不再把按钮提升到 Toolbar 外部。右下角发帖 FAB 在液态模式隐藏，旧模式继续保留。

## [S2] 顶部布局与行为

首页列表内容延伸到 Toolbar 下方，顶部 Compose 宿主悬浮覆盖真实时间线内容。状态栏安全区由顶部宿主统一处理，列表仍可滚动到玻璃下方。

左侧玻璃：

- 显示当前时间线图标、标题和下拉箭头。
- 点击展开时间线玻璃菜单。
- 有新帖子时，同一块玻璃临时切换为“查看新帖子”；点击沿用现有回顶/刷新逻辑，之后恢复时间线标题。

右侧玻璃：

- 发帖按钮调用当前时间线 Fragment 的既有 `onFabClick()`，不复制发布流程。
- 更多按钮展开右侧玻璃菜单。
- 公告未读或应用更新可用时，更多按钮显示一个汇总红点。

液态模式不显示现有右下角 FAB。旧模式继续使用原生 Toolbar、FAB、`home_custom.xml` 和系统 `PopupMenu`。

## [S3] 玻璃菜单

时间线菜单锚定在左侧玻璃下方，按现有 `timelines[]` 显示图标、标题和选中态。

更多菜单锚定在右侧玻璃下方，首层保留现有入口：设置、公告、编辑时间线、列表、关注的话题。列表和关注的话题在同一浮层中切换为二级页面，顶部提供返回，不创建系统级联菜单。

公告未读或应用更新可用时，对应菜单条目显示独立红点；点击后沿用现有清除逻辑。提醒项始终留在菜单内，不提升为 Toolbar action。

菜单在点击空白区域、系统返回、切换主 Tab或开始列表滚动时关闭。菜单与 Toolbar 位于同一顶部 Compose 宿主；Toolbar 的 `drawBackdrop` 通过 `exportedBackdrop` 导出已合成的玻璃结果，菜单采样导出的 backdrop，避免 glass-on-glass 自引用循环。

材质效果顺序固定为 `vibrancy -> blur -> lens`。菜单使用比按钮更强的高斯模糊、主题自适应半透明 surface、细描边和足够的文字对比度。Android 12 以下或 RuntimeShader 不可用时，回退为相同布局的高透明度圆角 surface。

## [S4] 真实内容采样

顶部玻璃必须显示其下方真实时间线内容，不使用 synthetic 渐变。现有 `BackdropCaptureFrameLayout` 扩展为共享顶部和底部捕获：一次后代 invalidation 只安排一次下一帧捕获，一次 View 树软件绘制生成共享结果，再输出顶部与底部窄区域。

顶部和底部不得各自调用一次 `dispatchDraw()`。捕获继续兼容直接硬件 `BitmapDrawable` 和 `BlurhashCrossfadeDrawable` 内部硬件图，并在捕获后立即恢复，不降低正常帖子图片的硬件渲染路径。

菜单展开不触发额外 Android View 捕获，只消费顶部 Compose 已导出的 Toolbar backdrop。滚动/动画活跃时每显示帧最多捕获一次，静止时自动停止。

## [S5] 状态桥与生命周期

Java `HomeTabFragment` 向顶部 Compose 控制器提供：当前时间线索引与条目、时间线切换、查看新帖子、发帖、更多菜单动作、列表/话题数据、公告提醒和更新提醒。

顶部宿主销毁、Activity 主题重建、液态开关关闭或离开首页时间线页时，必须 dispose composition、断开捕获 listener、清除菜单状态和 View 引用。不得重复添加时间线 Fragment、顶部宿主或 capture listener。

主题变化后 Compose 使用新 Activity context 与主题重建；旧模式切换回来时恢复当前时间线和现有页面 Fragment，不重新加载页面数据。

## [S6] 性能边界

顶部实现不能重新引入主题切换后重复 Fragment 导致的 `150ms` CPU 帧。顶部和底部共享一次捕获，优先减少捕获区域、复用 Bitmap 和控制 downscale；不得以关闭真实内容、lens、blur 或动态高光作为默认优化。

若共享捕获仍造成明显掉帧，先测量捕获和 Compose 绘制耗时，再只降低顶部/底部 backdrop 位图采样比例。菜单浮层本身不得增加第二条 View 截图链。

## [S7] 验证

- 单元测试验证液态模式只暴露发帖和更多两个常驻 action，公告/更新只影响更多红点和菜单条目。
- 单元测试验证时间线菜单、查看新帖子状态、一级/二级更多菜单映射。
- 旧模式验证原 Toolbar action 提升、FAB 和 PopupMenu 行为不变。
- fresh Debug 构建并按项目规则提交。
- 真机验证浅色/深色、主题切换、5 个主 Tab、时间线切换、查看新帖子、发帖、更多菜单和二级菜单。
- 主题切换前后检查 child 时间线 Fragment、ComposeView 和 capture listener 数量不增长。
- 使用 `gfxinfo`/FrameTimeline 对比仅底栏模式与顶部+底部玻璃模式的帖子滚动帧耗时，并肉眼检查菜单文字可读性、真实内容折射和 glass-on-glass 稳定性。
