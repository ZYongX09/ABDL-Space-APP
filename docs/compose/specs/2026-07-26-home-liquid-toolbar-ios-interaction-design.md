# 顶部 Liquid Glass iOS 连续交互设计

## [S1] 目标

仅在液态玻璃模式下重做首页顶部玻璃的交互与动画，使左侧时间线胶囊和右侧操作胶囊以同一玻璃实体连续变形成锚定菜单。保留真实 backdrop、可滚动顶部留白、Java 业务回调和旧模式 Toolbar/FAB。

## [S2] 单一可变形容器

左侧和右侧各使用一个 `MorphingGlassContainer`。闭合胶囊和展开菜单是同一 RenderNode 的两个几何状态，不同时绘制 Toolbar 玻璃与独立菜单玻璃。

状态机固定为 `Idle -> Pressed -> Expanding -> Expanded/Selecting -> Collapsing -> Idle`。动画反向时从当前进度继续，禁止先跳回端点。左右菜单互斥；切换目标时先收回当前容器至约 40%，再展开另一容器。

## [S3] 点击与滑动选择

普通点击按下时压缩并增强 lens/高光，松手回弹后从触发边界展开菜单。按住超过手势阈值后直接展开，手指可以连续拖入菜单；经过条目时玻璃高亮层跟随移动，松手执行当前条目，拖出菜单边界后松手取消。

系统返回、点击空白、切换主 Tab 和开始列表滚动都通过 `Collapsing` 收回，不立即移除。菜单关闭时顶部外区域不拦截帖子列表手势。普通点击、无障碍语义和键盘焦点仍可用。

## [S4] 几何与物理动画

单个进度驱动 `left/top/width/height/cornerRadius`、surface alpha、blur、lens、BloomStroke 和内容位置。展开以原胶囊真实 bounds 为起点、菜单 bounds 为终点。

按压释放阶段约 90ms，随后用欠阻尼弹簧展开，目标参数约为 `dampingRatio=0.82`、`stiffness=520`。收回采用约 `dampingRatio=0.9` 的更高阻尼。参数以测试固定，允许一次轻微过冲，不允许多次弹跳。

原标题和图标向菜单首行连续移动；其他条目按距锚点顺序、每项约 16ms 延迟，以 6-8dp 位移出现。关闭顺序反向。

## [S5] 二级菜单

列表和关注话题在同一玻璃外壳中切换。外壳宽高使用弹簧适配新内容，根页面向左约 18dp 退出，二级页面从右约 18dp 进入；返回时方向相反。不得重新缩放或重新创建整个菜单玻璃，不得产生 backdrop 闪白。

## [S6] Backdrop 与材质

进入 `Pressed` 时预先把顶部捕获范围从 72dp 扩到菜单范围。至少收到一帧扩展 backdrop 后才进入 `Expanding`，避免首帧无折射。

闭合态复用底栏 4dp blur、lens、动态 BloomStroke 和传感器高光。按压时 lens 与高光增强，展开时连续插值到以文字可读性为主的菜单参数。滑过条目的高亮是一块小型玻璃层，不使用纯色矩形。

## [S7] 架构边界

新增 `MorphingGlassContainer.kt` 承担状态机、几何、手势和材质插值；`HomeLiquidToolbarView.kt` 只负责业务状态映射和左右容器编排。Java 回调、`BackdropCaptureFrameLayout`、可滚动 RecyclerView 顶部 padding 和旧模式逻辑不改变。

所有几何和材质由一个 `Animatable`/状态机协调，禁止多个互不知情的 `animate*AsState` 同时驱动容器。

## [S8] 验证

- 单元测试覆盖状态合法转换、动画反转、左右菜单互斥、拖出取消和选中提交。
- 几何测试覆盖闭合/展开 bounds、锚点、圆角和二级菜单尺寸。
- 测试固定展开/收回弹簧参数和 backdrop 预热门槛。
- fresh Debug 构建并按项目规则提交。
- 真机验证快速连点、按住滑动选择、拖出取消、左右菜单互换、二级菜单、返回和滚动关闭。
- 60Hz/120Hz 下检查无跳变、无多次弹跳、无首帧无折射和无触摸遮挡。
- `gfxinfo` 对比当前版本，确认菜单动画和帖子滚动不出现主题切换问题中的 150ms CPU 长帧。
