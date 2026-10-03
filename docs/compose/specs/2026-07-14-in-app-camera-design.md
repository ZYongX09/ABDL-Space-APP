# ABDL Space 应用内相机设计

## [S1] 问题

媒体选择器第一格当前通过 `ACTION_IMAGE_CAPTURE` 启动系统相机。在测试小米设备上，系统相机启动后 `camopt_killer` 会直接 SIGKILL 调用方进程；系统同时报告 `MainActivity` 没有可保存状态，因此拍照返回时任务栈为空并回到桌面。该行为无法通过延迟启动或释放预览可靠规避。

## [S2] 目标

- 不启动系统相机，在 ABDL Space 进程内完成拍照和录像。
- 保留相册第一格相机入口，并提供接近 Telegram 的全屏相机交互。
- 支持照片、最长 60 秒视频、前后摄像头切换、闪光灯、双指缩放和点按对焦。
- 拍摄后提供“重拍”和“使用照片/视频”确认。
- 兼容帖子附件、个人二维码图片和扫码页图片入口。
- 不引入 CameraX，不复制 Telegram 的业务模块或主题依赖。

## [S3] 页面与交互

新增全屏 `MediaCameraActivity`。页面使用黑色背景，中间为相机预览，顶部包含返回、闪光灯和录像计时，底部包含图库缩略图、快门和前后摄像头切换。

- 单击快门拍照。
- 长按快门开始录像，松开停止；达到 60 秒自动停止。
- 录像时快门显示红色状态和环形进度。
- 双指手势调整数字变焦；点按预览触发对焦并短暂显示对焦环。
- 前摄预览镜像，输出文件按设备方向正确旋转，不额外强制镜像。
- 拍摄完成后在同一 Activity 切换至确认态。照片使用全屏图片预览；视频循环静音预览。
- 确认态返回或“重拍”删除临时文件并回到预览态；“使用”返回媒体结果。
- 预览态返回关闭相机并回到媒体选择器调用页面。

## [S4] 组件边界

### `MediaCameraActivity`

负责页面生命周期、权限反馈、手势、按钮状态、拍摄/确认状态切换和 Activity 结果。它不直接操作 Camera2 对象。

### `MediaCameraController`

负责相机线程、镜头枚举、预览 Surface、Camera2 session、JPEG `ImageReader`、`MediaRecorder`、闪光、缩放、对焦、方向计算和资源关闭。通过回调向 Activity 报告就绪、文件生成和错误。

### `MediaPickerSheet`

第一格保持静态相机图标，点击后通知调用方打开 `MediaCameraActivity`。弹窗本身不持有相机资源。

### 调用方

`ComposeFragment`、`ProfileQrCodeFragment` 和 `MLKitBarcodeScannerActivity` 使用统一 request code/result extra。Compose 接收图片或视频；二维码相关入口以 `allow_video=false` 启动，只接收图片。

## [S5] 相机状态机

控制器和页面共享单向状态：`OPENING -> PREVIEW -> CAPTURING/RECORDING -> REVIEW -> PREVIEW`，退出时进入 `CLOSING`。

- 非 `PREVIEW` 状态禁止切镜头、重复拍照和重复开始录像。
- `RECORDING` 状态的返回、暂停和超时都先停止录像，再进入确认或安全退出。
- 切镜头时先关闭旧 capture session、device、reader 和 recorder，再打开新镜头。
- 过期异步回调通过 generation token 丢弃，不能重新操作已关闭对象。

## [S6] 媒体输出

- 图片输出至 `cache/images/camera_<timestamp>.jpg`，由 `ImageReader` 获取 JPEG 字节并直接写入。
- 视频输出至 `cache/videos/camera_<timestamp>.mp4`，使用 H.264/AAC 和设备支持的合理分辨率。
- Activity 结果包含 `media_uri`、`media_is_video` 和 `media_mime_type`。
- URI 由现有 `TweakedFileProvider` 生成。
- 取消、重拍、失败和未确认退出时删除临时文件；确认后文件交由现有附件流程管理。

## [S7] 权限与错误处理

- 相机权限沿用调用方现有申请流程；Activity 缺少权限时提示并退出。
- 视频需要 `RECORD_AUDIO`。首次长按录像时若无权限则请求；拒绝后保留拍照能力并提示无法录音录像。
- 无前摄、无闪光或硬件不支持对应能力时隐藏或禁用按钮。
- 相机打开、session 创建、JPEG 写入、录像启动或停止失败时恢复到可退出状态，Toast 提示，不保留空文件。

## [S8] 生命周期与进程安全

- `onResume` 启动专用 `HandlerThread` 并打开相机；`onPause` 同步停止录像并关闭全部相机资源。
- 页面不依赖系统相机 Activity，因此不会触发该设备的调用方清理路径。
- 当前拍摄模式、镜头方向和待确认文件路径保存到实例状态；若 Activity 重建且文件存在，恢复确认态，否则恢复预览态。
- 相机打开期间不执行 TFLite 初始化或图片 NSFW 推理；媒体被调用方接收后沿用现有后台检测流程。

## [S9] 验证标准

- Debug 构建成功。
- 真机连续完成至少 5 次拍照和 3 次录像，应用进程 PID 不变化，日志中无 `camopt_killer` 对 ABDL Space 的 kill、无 Java 崩溃和 ANR。
- 前后摄切换、三种可用闪光模式、双指缩放和点按对焦工作正常。
- 照片方向正确；视频可播放、有声音、最长 60 秒自动停止。
- 重拍会删除旧文件；使用后 Compose 能添加附件；二维码入口能解码照片且不出现视频入口。
- 深色和浅色模式下控制按钮、确认按钮与系统栏均可见。

## [S10] 非目标

- 不实现滤镜、美颜、贴纸、裁剪、视频编辑和多段合并。
- 不复制 Telegram 的 `CameraController`、`PhotoViewer`、媒体模型或主题系统。
- 不在首版恢复相册第一格实时预览；第一格仅是静态相机入口。
