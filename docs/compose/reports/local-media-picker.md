---
feature: local-media-picker
status: delivered
specs: []
plans:
  - .mimocode/plans/1782657636580-quiet-rocket.md
branch: develop
commits: 7fab5fb2..7fab5fb2
---

# 可复用本地媒体选择器 — 最终报告

## What Was Built

发帖页的添加媒体按钮现在打开自定义的两级本地媒体选择器：先选择相册，再在相册中选择图片或视频。选择器支持多选、选择序号、最大选择数量，并将 `content://` Uri 按选择顺序返回给发帖页。

实现没有引入 Telegram 源码、第三方图片库或 Telegram 的 UI/发送依赖。现有 `ComposeMediaViewController` 继续负责 MIME 类型、文件大小、NSFW 检测、附件预览、上传队列、排序和草稿恢复。

## Architecture

`MediaStoreLoader` 使用 Android MediaStore 分别查询图片和视频，按 `BUCKET_ID` 分组，并生成“全部媒体”相册。`MediaItem` 保存本地媒体的 Uri、类型、尺寸、大小、修改时间和视频时长；`MediaAlbum` 保存相册及其媒体列表。

`MediaAlbumPickerFragment` 展示相册网格并进入 `MediaPickerFragment`。后者使用 GridLayoutManager 展示媒体网格，维护 Uri key、选中对象和选择顺序。确认后通过 AppKit 的 `setResult`/`Nav.finish` 返回 `MediaPickerResult.KEY_URIS`。

### Design Decisions

- 使用 `content://` Uri 而不是绝对文件路径，因为它适配 Android 分区存储，也能直接进入现有上传接口。
- 选择器只负责“找媒体和返回 Uri”，上传、内容检查和业务限制仍由发帖媒体控制器统一处理，方便未来头像、纸尿裤图片等页面复用。
- Android 13+ 按图片和视频权限分别查询；部分授权时仍可显示已授权类型的媒体。

## Usage

调用方创建 `MediaPickerConfig`，设置 `maxCount`、`allowImages` 和 `allowVideos`，将 `config.toBundle()` 传给 `MediaAlbumPickerFragment`。通过 `Nav.goForResult` 接收结果，并从 `MediaPickerResult.KEY_URIS` 读取 `ArrayList<Uri>`。

发帖页使用当前剩余附件数量作为 `maxCount`，接收结果后逐个调用 `ComposeMediaViewController.addMediaAttachment(uri, null)`。

## Verification

- `./gradlew :mastodon:assembleDebug --no-daemon`：通过。
- `./gradlew :mastodon:testDebugUnitTest --no-daemon`：通过；当前项目没有 debug 单元测试源码，因此 Gradle 报告 `NO-SOURCE`。
- `git diff --check`：通过。
- 未进行真机/模拟器交互验证；Android 12、Android 13+ 权限分支、深色模式、旋转恢复和实际媒体上传仍需要设备测试。

## Journey Log

- [pivot] 没有直接搬运 Telegram 的 `MediaController` 和自定义 Cell，而是将 MediaStore 查询和选择流程拆成项目自己的 Java 类，避免依赖冲突。
- [lesson] AppKit 的页面结果应使用 `setResult`、`Nav.finish` 和 `onFragmentResult`，不能假设 Android Activity 的 result API。
- [lesson] Android 13+ 图片和视频权限需要独立判断，不能因为其中一种未授权就隐藏另一种已授权媒体。

## Source Materials

| File | Role | Notes |
|------|------|-------|
| `.mimocode/plans/1782657636580-quiet-rocket.md` | Implementation plan | Approved plan for the feature |
| `mastodon/src/main/java/org/joinmastodon/android/ui/media/MediaStoreLoader.java` | MediaStore loader | Queries and groups local images/videos |
| `mastodon/src/main/java/org/joinmastodon/android/fragments/media/MediaAlbumPickerFragment.java` | Album picker | First level of the picker |
| `mastodon/src/main/java/org/joinmastodon/android/fragments/media/MediaPickerFragment.java` | Media picker | Grid, multi-select and result delivery |
| `mastodon/src/main/java/org/joinmastodon/android/fragments/ComposeFragment.java` | Compose integration | Opens picker and consumes Uri results |
