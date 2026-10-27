# 私信输入框输入法避让修复

日期：2026-10-27。用户报告“私信页面在输入时输入框不会有输入法避让”。修复基于 Android develop `758adce2`，仅针对既有 3.2.0 原生私信 `ChatFragment`；不修改并行 V4 工作树、不发布 main 或正式下载、不更改后端。

## 根因与最小修复

`ChatFragment.onApplyWindowInsets()` 原实现仅读取 `getStableInsetBottom()`。stable inset 代表固定系统栏，不包含输入法高度。此页根为普通垂直 `LinearLayout`（`fragment_chat.xml`），没有 `FragmentRootLinearLayout` 自动避让；现有全局布局监听只滚消息列表，不能上移输入框。

核对缓存 AppKit 1.4.8 的实际 `FragmentStackContainer.onApplyWindowInsets`：宿主把原始 Insets 副本分发给实现 `WindowInsetsAwareFragment` 的页面，再消费系统 Insets；没有先替普通 ChatFragment 预留一次键盘高度。因此只改页面底部预留：

- 取 stable 导航底部与当前 `systemWindowInsetBottom` 的最大值；API30+再合并 `Type.ime() | Type.navigationBars()` 的实际底部。
- 保留 `baseInputBottom + bottom`，取 max 而不是相加，避免键盘/导航重复预留和多次分发累积。
- 顶部工具栏、布局 XML、输入/发送事件、草稿、私信网络和全局 softInputMode 均不改。

## 回归

新增 `chat/ui/ChatFragmentLayoutTest.java`：真实 `ChatFragment` 生命周期、原 XML 与 AppKit 宿主，空账号隔离，不使用真实会话、收发私信或访问网络。由真实宿主分发 Insets，测量输入框/发送按钮和消息区坐标，不用测试自定义 padding 代替生产逻辑。

新增18项覆盖API26/28/30/35：

- 键盘升起后输入框与发送按钮上移精确差值（280与24的差256），消息区相应缩小，导航栏不叠加两次。
- 重复分发、键盘高度变化、隐藏恢复、工具栏高度不累加。
- API30+的typed IME变化而legacy bottom仍为导航高度时依然避让。
- 无键盘时保留导航安全区；横屏与多行输入仍处于可见窗口内。

测试夹具首轮用 AndroidX builder 在旧平台只维护 compat stable 值，平台副本仍为0；修正为构造真实legacy Insets及stable字段并增加精确前置断言。第二轮又发现现代builder设置navigation类型会覆盖legacy combined bottom，删除冲突的夹具setter，保留stable/legacy/typed IME独立输入。两轮失败日志保留，没有放宽生产行为或几何断言。

最终同一次串行 `testReleaseUnitTest`：**173/173通过，0失败/跳过**（新增私信18、既有会话列表13、上一轮NBW/发帖/位置/账号兼容142），随后同一命令 `assembleRelease` 成功。`git diff --check`通过。

## 测试包与设备边界

- 文件：`/home/ZYongX/projects/ABDL-Space-3.2.0-code34-dm-ime-fix-test.apk`
- 大小：151554314字节；SHA-256 `b277d44921b1e96418d14f78977fb89eaf94af06a146d071327413f9cba8fbf2`。
- 仍为3.2.0/code34，既有正式证书 `fd2098a3d3493222c247a7ba2787ec48fd8f62d38e9432ed4d6290a057dea475`，非debuggable；apksigner、ZIP CRC、16KiB对齐均通过。旧测试包保留。
- 已连接设备的只读检查显示安装4.0.0/code35，且停在系统生物识别。未降级覆盖、未卸载/清数据、未解锁认证、未读取真实私聊。没有模拟器可用；Robolectric跨版本几何回归不是实际输入法/真机验收。

证据目录：`/home/ZYongX/projects/chat-ime-fix-evidence-20261027/`。本轮仅交付develop与正式签名测试包；Android main仍`c8dd0ccd`，不正式发布。
