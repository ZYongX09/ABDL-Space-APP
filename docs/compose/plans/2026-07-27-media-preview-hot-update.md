# Media Preview Hot Update Implementation Plan

> [!NOTE]
> This document may not reflect the current implementation.
> See the final report for up-to-date state:
> [Final Report](../reports/media-preview-hot-update.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过 Worker 懒生成并缓存 720px WebP 缩略图，让旧帖和新帖无需网页前端更新即可获得真实 `preview_url`，并让 Android 列表优先加载该预览图。

**Architecture:** Worker 为受信任媒体 URL 生成确定性的签名式预览路径，首次请求时拉取源图、Photon 缩放并写入 Cache API，失败时安全回退原图。Mastodon 转换器和上传响应统一返回该路径；Android 列表仅改变 IMAGE 请求选址，查看器继续使用原图。

**Tech Stack:** Cloudflare Workers、Hono、Cache API、@cf-wasm/photon、Node test runner、Android Java、AppKit ImageCache、Gradle/JUnit。

---

### Task 1: Worker 缩略图 URL 与安全网关

**Files:**
- Create: `/home/ZYongX/projects/git/abdl-space/src/lib/media-preview.ts`
- Create: `/home/ZYongX/projects/git/abdl-space/src/mastodon/media-preview.test.ts`
- Modify: `/home/ZYongX/projects/git/abdl-space/src/mastodon/routes.ts`

- [ ] 写失败测试，覆盖受信任 HTTPS 主机生成确定性 URL、未知主机回退原 URL、预览路径解析、最长边 720px 缩放以及无放大。
- [ ] 运行 `npm test -- src/mastodon/media-preview.test.ts`，确认因 API 缺失失败。
- [ ] 实现 `buildMediaPreviewUrl()`、`parseMediaPreviewSource()`、`resizeMediaPreview()`；源图限制 10 MiB、校验最终响应 URL 主机和图片 MIME，输出 WebP。
- [ ] 新增公开 GET/HEAD 预览路由，使用 `caches.default` 缓存成功响应，设置 `Cache-Control: public, max-age=2592000, immutable`；任何抓取、解码或缓存失败返回 302 原图 URL。
- [ ] 运行测试和 `npm run typecheck`。

### Task 2: 所有 Mastodon 媒体响应接入 preview_url

**Files:**
- Modify: `/home/ZYongX/projects/git/abdl-space/src/mastodon/converter.ts`
- Modify: `/home/ZYongX/projects/git/abdl-space/src/mastodon/routes.ts`
- Test: `/home/ZYongX/projects/git/abdl-space/src/mastodon/media-preview.test.ts`

- [ ] 写失败测试，验证旧帖子转换和 `/api/v1/media` 上传响应对受信任图片返回预览网关 URL，第三方历史图片保持原 URL。
- [ ] 让 `toMediaAttachment()` 和上传/更新响应统一调用 `buildMediaPreviewUrl()`。
- [ ] 运行完整 `npm test` 与类型检查。
- [ ] 精确提交后端相关文件并部署 Worker。
- [ ] 从生产公开时间线取样，验证 `preview_url != url`；冷请求验证 WebP、最长边<=720、体积下降，暖请求验证缓存和耗时。

### Task 3: Android 列表使用 previewUrl

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/ui/displayitems/MediaGridStatusDisplayItem.java`
- Create: `mastodon/src/test/java/org/joinmastodon/android/ui/displayitems/MediaGridStatusDisplayItemTest.java`

- [ ] 写失败测试，验证 IMAGE 优先非空 `previewUrl`，null/空串回退 `url`，VIDEO/GIFV 仍使用 `previewUrl`。
- [ ] 提取最小包级静态 URL 选择方法，并在列表图片请求中使用；不修改 PhotoViewer、保存或下载原图逻辑。
- [ ] 运行聚焦单测、Java 编译和 fresh `assembleDebug`。
- [ ] 精确提交 Android 相关文件。

### Task 4: 端到端验证

**Files:**
- No code changes expected.

- [ ] 使用生产时间线响应确认 Android 可获得新的 `preview_url`。
- [ ] 对同一图片测原图和预览图的字节数、像素、冷暖 TTFB/总时间。
- [ ] 安装最新 APK，清除 App 图片缓存后滚动媒体时间线，确认列表请求预览 URL，打开图片查看器仍请求原图。
- [ ] 记录限制：旧 Android 版本仍使用原图；后端热更新不依赖网页前端。
