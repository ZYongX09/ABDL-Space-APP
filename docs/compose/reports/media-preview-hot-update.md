---
feature: media-preview-hot-update
status: delivered
specs: []
plans:
  - docs/compose/plans/2026-07-27-media-preview-hot-update.md
branch: develop + feat/media-preview-hot-update
commits: 2afda67..dd01d62, ad265def
---

# Media Preview Hot Update — Final Report

## What Was Built

生产 Worker 现在为受信任媒体源懒生成最长边 720px 的缩略图，并通过 `api.abdl-space.top` 返回确定性的 `preview_url`。旧帖子无需数据库迁移或网页前端更新，下一次 Mastodon API 响应即可获得缩略图地址。Android 状态列表优先加载 `previewUrl`，图片查看器、保存和下载继续使用原图。

不透明图片输出 JPEG quality 80；包含透明像素时输出无损 WebP。生成结果由 Cloudflare Cache API 缓存 30 天，转换失败时 302 回退原图，不阻断媒体显示。

## Architecture

`src/lib/media-preview.ts` 负责受信任域校验、URL-safe Base64 路径、文件头尺寸检查、Photon 缩放和自适应编码。当前只代理 `img.abdl-space.top` 与 `cloudflare-imgbed-790.pages.dev`，保持现有优选 IP、Worker 路由和 R2/图床转发结构不变。

`src/mastodon/routes.ts` 暴露 `/api/v1/media/preview/v3/:source`。GET 缓存未命中时拉取并转换源图；HEAD 缓存未命中时直接重定向原图。缓存键忽略 query，防止随机查询串重复转换。`src/mastodon/converter.ts` 和媒体上传/更新响应统一生成该 URL。

Android 的 `MediaGridImageUrl` 只负责列表选址：IMAGE 优先非空 preview，缺失时回退原图；VIDEO/GIFV 保持原 preview 行为。

### Design Decisions

- 选择 Cache API 懒生成，因为它不要求新增 R2 binding、历史迁移或网页发布，适合后端热更新。
- 预览源采用严格主机白名单，因为开放 URL 代理会产生 SSRF 风险。
- 在 Photon 解码前解析图片文件头并限制 12MP/8192px/10MiB，因为压缩字节限制不能防止图片解码炸弹。
- 采用版本化路径 `v3`，使编码或安全规则变化可以自然绕过旧边缘缓存。

## Usage

时间线附件同时返回原图和预览图：

```json
{
  "url": "https://cloudflare-imgbed-790.pages.dev/file/example.jpg",
  "preview_url": "https://api.abdl-space.top/api/v1/media/preview/v3/..."
}
```

列表使用 `preview_url`；用户打开查看器后仍加载 `url`。

## Verification

- Worker 隔离分支 30 项 Node 测试通过。
- `wrangler deploy --dry-run` 通过，生产版本 `bde26dbe-be21-4b25-be80-f480aca33e65` 已部署。
- 生产样本原图 360,124 B、1079×1922；预览图 51,407 B、404×720，字节下降约 85.7%。
- 暖请求返回 `CF-Cache-Status: HIT`、`Age` 和 30 天 immutable 缓存头。
- 带随机 query 的请求命中同一规范缓存；HEAD 冷请求不执行图片转换。
- Android URL 选择 3 项测试通过，fresh `assembleDebug` 成功。
- 真机安装验证未执行：验收时 ADB 设备离线。

## Journey Log

> Brief notes on what informed the final design. Not required reading.

- [pivot] 后端主工作区混有其他未提交功能，改用基于生产提交的独立 worktree 部署，避免把无关代码一起上线。
- [lesson] Photon `get_bytes_webp()` 输出无损 WebP，照片缩略图需按透明度选择 JPEG 或 WebP。
- [lesson] 限制压缩文件大小不能防止图片炸弹，必须在解码前读取文件头并限制像素尺寸。
- [pivot] Cache API 键改为忽略 query，避免匿名请求通过随机参数放大转换成本。

## Source Materials

| File | Role | Notes |
|------|------|-------|
| `docs/compose/plans/2026-07-27-media-preview-hot-update.md` | Implementation plan | Complete |
