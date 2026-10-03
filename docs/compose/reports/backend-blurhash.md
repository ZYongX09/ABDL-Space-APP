---
feature: backend-blurhash
status: delivered
specs: []
plans:
  - docs/compose/plans/2026-07-20-backend-blurhash.md
branch: develop
commits: d87f8570..d87f8570
---

# 后端 BlurHash — Final Report

## What Was Built

生产 Worker 现在会在 `/api/v1/media` 接收静态图片时生成 BlurHash。Android 将上传响应中的 BlurHash 随 `media_attributes` 发送到创建或编辑帖子接口，后端持久化到 `post_images.blurhash`，后续 Status 列表和详情响应通过 `media_attachments[].blurhash` 返回。

没有 BlurHash 的旧媒体、视频、非图片、超过 10 MiB 的文件及解码失败图片继续返回 `null`，Android 使用通用图片占位图，不影响上传和发帖。

## Architecture

`src/lib/blurhash.ts` 使用 `@cf-wasm/photon` 解码图片并将最长边缩至 32 像素，再由 `blurhash` 以 4×3 分量编码。Photon 对象在 `finally` 中显式释放，异常统一降级为 `null`。

媒体上传响应由 `src/mastodon/routes.ts` 返回生成值。创建/编辑帖子时只接收经过 `isBlurhashValid()` 验证且不超过 200 字符的值，并写入 D1。`src/mastodon/converter.ts` 将数据库值映射到 Mastodon 媒体对象。Android 的 `CreateStatus.MediaAttribute` 负责传递上传响应中的值。

### Design Decisions

- 选择 Worker 内同步生成，因为客户端可以在上传响应后立即持有 BlurHash，不需要异步轮询。
- 生成失败不阻塞上传，因为 BlurHash 是视觉增强而不是媒体完整性的必要条件。
- 保留 D1 持久化而不是只返回一次上传结果，使时间线和详情页之后仍能取得 BlurHash。
- 对输入设置 10 MiB 门禁，避免 Photon 在 Worker 中解码超大文件造成不必要的内存压力。

## Usage

Android 上传图片后会收到：

```json
{
  "blurhash": "L00000fQfQfQfQfQfQfQfQfQfQfQ"
}
```

创建帖子时通过 `media_attributes[].blurhash` 回传。对应 Status 响应的 `media_attachments[].blurhash` 返回同一值。没有可用值时字段为 `null`。

## Verification

- 后端测试 27/27 通过，覆盖静态 PNG、非图片、无效图片和持久化值校验。
- `wrangler deploy --dry-run` 成功打包 Photon WASM；gzip 后约 866 KiB。
- Android `testDebugUnitTest` 成功但为 `NO-SOURCE`，`assembleDebug` 成功。
- 生产 D1 已新增 `post_images.blurhash`，部署版本为 `27ec839a-e830-4d8d-856e-36f5fdc40910`。
- 生产 Status 路径通过临时写入有效 BlurHash 后回查，确认 API 返回该值；测试数据随后恢复为 `NULL`。
- 因没有可安全使用的生产 OAuth 测试凭据，未直接调用受认证的生产上传接口；生成器测试、Worker 打包和生产部署均已完成。

## Journey Log

- [pivot] Photon 的 `/workerd` 显式入口无法在 Node 测试器中加载，改用包条件导出后 Node 选择 Node 实现、Wrangler 选择 Workerd 实现。
- [lesson] `post_images` 的基准 schema 必须同步包含 `is_nsfw`、`alt_text` 和 `blurhash`，否则新建数据库会与路由 SQL 不一致。
- [lesson] 编辑帖子重建媒体记录时必须同时恢复图片级敏感标记和 BlurHash。

## Source Materials

| File | Role | Notes |
|------|------|-------|
| `docs/compose/plans/2026-07-20-backend-blurhash.md` | Implementation plan | Complete |
| `src/lib/blurhash.ts` | Worker image decoding and encoding | Backend source of truth |
| `src/mastodon/routes.ts` | Upload and persistence flow | Backend source of truth |
| `mastodon/src/main/java/org/joinmastodon/android/ui/viewcontrollers/ComposeMediaViewController.java` | Android metadata propagation | Client source of truth |
