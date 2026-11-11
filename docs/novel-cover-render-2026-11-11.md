# 小说封面操作中闪烁：绘制层修复（2026-11-11）

## 新反馈与范围

上一轮 `10998905` / 业务 `44c5ace4` / APK `554569de…` 没有解决用户实机反馈。用户进一步确认：只在滑动或其他页面操作时，自定义封面与下层文字封面反复交替。

此轮从上一修复建立 `fix/novel-cover-render-20261111`，继续保留用户指定的 `2dac60b4` / v5 玻璃效果，旧 v5 包和上一小说包均保留。不是通过继续扩大图片缓存、冻结页面采样或降低玻璃效果掩盖问题。

## 源码确认与推断边界

- `NovelBookCover` 一直绘制文字封面，再由 `NovelRemoteCover` 的原生 AndroidView/ImageView 覆盖。
- 小说根页的 `NovelCapturedPage` 将 ComposeView 嵌在 `BackdropCaptureFrameLayout` 内；玻璃启用时，滑动及内容失效会触发第二次软件画布采样。
- 既有采样器会临时替换 live ImageView 的 HARDWARE bitmap drawable；条带之外的图片会换成空绘制的占位，采样后恢复原 drawable。
- 这条临时改图路径真实存在，能说明为什么重绘时下层文字可能被露出，但没有真机 GPU/PixelCopy 证据证明它就是此设备的唯一原因。编辑/详情等无采样路径也必须作对照。

上一轮测试着重加载、缓存、detach与软件像素，并未把生产小说封面与开启采样的宿主一起逐步滚动。本轮补齐该交叉路径；不能把上一轮54项通过当作本轮验证。

## 实现

- `NovelRemoteCover` 的静态 BitmapDrawable 改为 Compose `Image`，与文字及页面共同绘制；不再创建实际 cover ImageView，不再进入采样器的临时 drawable 替换流程。
- 继续复用 `NovelCoverBinding` / 作用域 / AppKit；Composition 拥有 presentation，进入绑定、退出释放；换URL/书籍/账号得到空的新presentation，旧回包不能填回。
- 请求明确为 `Bitmap.Config.ARGB_8888`，保持500×700最大解码尺寸，不改全局 AppKit，不每帧复制位图。画布采样可直接读取该像素配置。
- 成功图片不因重新绘制而重启原生200ms淡入。无封面/失败时仍显示原文字封面，本地pending封面仍优先。
- `drawWithContent` 保留账号有效性和当前绑定校验；若状态在验证时被清空或图像已回收，跳过旧 painter 当前帧，而不是等重组后再清除。
- 非位图drawable保持独立callback与Compose Canvas绘制；动画任务执行/取消/退出后移除，不持有无限任务记录。
- `NovelCoverImageView` 仅保留原加载生命周期回归入口，生产小说静态封面不再用它。

## 验证计划与结果

- 保留并适配现有9项真实页面封面测试：成功图像像素/透明度、同key重组与新工厂、500×700压力、URL/ABA失效、账号/退出、编辑pending本地优先。断言对象改为Composition-owned presentation和实际Compose Image，不再靠已移除的原生View判断。
- 新增presentation/config用例；新增3项 `NovelCoverCapturedScrollTest`，实际生产采样宿主、RenderNode普通记录、交付底部采样条以及随后可见根节点像素；逐步滚动/metadata操作/重新创建、采样开关对照、无采样直接编辑页。
- 第一轮生产编译通过，但新增测试有两处在SideEffect内读取Composable主题，测试编译失败，未执行用例；修正为先读取再提交副作用，重新执行。
- 第二轮统一测试44项中42通过、2失败；失败为新增测试误假设软件根绘制不会刷新嵌套硬件display list，实际根绘制会调用dirty layers的 `updateDisplayList` 并可能在硬件记录Canvas上再遍历capture。断言改为启用时仅允许0或1次额外发布、关闭时严格0；保留显式记录帧+1、注册身份、透明度与每个封面像素检查，没有关闭采样或改业务以通过测试。
- 修正观察顺序后三项逐步滚动/采样开关/直接编辑对照专项3/3通过。最终九组同源码回归、构建及签名/字节信息完成后补记。

## 验收边界

Robolectric API28 native Skia及真实RenderNode记录/软件采样只能验证受控绘制链路，不等于设备GPU帧验收。本机无可用真机/模拟器；交付仍需用户在原设备连续慢滑、快滑、筛选/返回后查看已加载的封面。若仍失败，不继续把推断当根因；应取实际设备帧与加载/绘制状态关联记录。

只出原正式证书release测试包，不出debug包、不改版本、不推main/develop、不改后端和网页。玻璃导航、全局采样器、阅读器和上传协议均不改。
