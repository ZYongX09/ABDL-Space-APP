# 后端 BlurHash 实现计划

> [!NOTE]
> This document may not reflect the current implementation.
> See the final report for up-to-date state:
> [Final Report](../reports/backend-blurhash.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Cloudflare Worker 上传静态图片时生成 BlurHash，并将其随帖子媒体持久化和返回给 Android。

**Architecture:** Worker 使用 Photon/WASM 将上传图片缩小为采样图，再用 BlurHash encoder 生成字符串；生成失败不阻塞上传，返回 `null`。Android 在 `media_attributes` 中携带每个媒体的 BlurHash，后端写入 `post_images.blurhash`，所有 Status 媒体映射读取该字段；视频和 GIFV 不生成 BlurHash。

**Tech Stack:** Hono、Cloudflare Workers、Photon/WASM、`blurhash`、D1 SQLite、Java/Gson Android 客户端、Wrangler。

---

### Task 1: Worker BlurHash 生成器

**Files:**
- Modify: `/home/ZYongX/projects/git/abdl-space/package.json`
- Create: `/home/ZYongX/projects/git/abdl-space/src/lib/blurhash.ts`
- Modify: `/home/ZYongX/projects/git/abdl-space/wrangler.jsonc`（仅在依赖需要 WASM 模块配置时修改）

- [ ] **Step 1: 添加并锁定依赖**

安装 Worker 兼容的 Photon/WASM 图片解码依赖和 `blurhash` encoder，确认最终 API 可在 Cloudflare Worker 模块环境中导入；锁定 package-lock 变更。

- [ ] **Step 2: 实现容错生成函数**

提供：

```ts
export async function generateBlurhash(file: File): Promise<string | null>
```

约束：

- 只接受 `image/*`，视频、GIFV 直接返回 `null`。
- 将图片缩放/采样到不超过 `32x32` 的小图。
- 使用固定 `4x3` BlurHash 网格和质量参数生成字符串。
- 解码、WASM、格式或尺寸失败时捕获异常并返回 `null`。
- 不记录图片内容、文件名或异常中的敏感数据。

- [ ] **Step 3: 为生成器添加可执行测试**

使用仓库已有 Node 测试框架，覆盖：无效/非图片输入返回 `null`、生成函数返回非空 BlurHash 字符串、失败不抛出到上传路由。

### Task 2: 上传媒体返回 BlurHash

**Files:**
- Modify: `/home/ZYongX/projects/git/abdl-space/src/mastodon/routes.ts`
- Modify: `/home/ZYongX/projects/git/abdl-space/src/routes/images.ts`（如果该上传 API 也作为 Android 媒体入口）

- [ ] **Step 1: 在上传图床前生成 BlurHash**

在 `POST /api/v1/media` 接收到 `File` 后调用 `generateBlurhash(file)`，然后继续现有图床转发。生成失败不得阻塞图床上传。

- [ ] **Step 2: 在媒体响应中返回 BlurHash**

图片响应返回：

```json
{
  "blurhash": "生成的字符串或 null"
}
```

视频响应保持 `blurhash: null`。

- [ ] **Step 3: 运行后端上传/生成测试**

运行：

```bash
npm test
npx wrangler deploy --dry-run
```

预期：测试成功，dry-run 能打包 Worker/WASM，不执行生产部署。

### Task 3: 持久化帖子媒体 BlurHash

**Files:**
- Modify: `/home/ZYongX/projects/git/abdl-space/schemas/schema.sql`
- Create: `/home/ZYongX/projects/git/abdl-space/migrations/0041_post_images_blurhash.sql`
- Modify: `/home/ZYongX/projects/git/abdl-space/src/mastodon/routes.ts`

- [ ] **Step 1: 增加 D1 字段和迁移**

在 `post_images` 表增加：

```sql
blurhash TEXT
```

迁移内容：

```sql
ALTER TABLE post_images ADD COLUMN blurhash TEXT;
```

- [ ] **Step 2: 接收并校验 media attribute BlurHash**

扩展 `media_attributes`：

```ts
{ id?: string; description?: string; blurhash?: string | null }
```

只接受有限长度的字符串，空值统一为 `null`，避免任意大字段进入 D1。

- [ ] **Step 3: 在创建/编辑帖子时写入 BlurHash**

`post_images` 插入语句增加 `blurhash` 列和值；编辑状态替换媒体时同样写入。

- [ ] **Step 4: 让所有媒体查询返回 BlurHash**

所有 `SELECT image_url, is_nsfw, alt_text FROM post_images` 查询增加 `blurhash`，并将图片映射结构传给 `toMediaAttachment()`。

### Task 4: Status 媒体映射和 Android 传递

**Files:**
- Modify: `/home/ZYongX/projects/git/abdl-space/src/mastodon/converter.ts`
- Modify: `/home/ZYongX/projects/git/abdl-space/src/mastodon/routes.ts`
- Modify: `/home/ZYongX/projects/moshidon-test/mastodon/src/main/java/org/joinmastodon/android/api/requests/statuses/CreateStatus.java`
- Modify: `/home/ZYongX/projects/moshidon-test/mastodon/src/main/java/org/joinmastodon/android/ui/viewcontrollers/ComposeMediaViewController.java`

- [ ] **Step 1: 扩展后端图片类型和转换函数**

`toMediaAttachment()` 接收 `blurhash?: string | null`，图片返回该值；没有值继续返回 `null`。所有调用点明确传递数据库字段。

- [ ] **Step 2: 扩展 Android 媒体属性**

`CreateStatus.Request.MediaAttribute` 增加：

```java
@SerializedName("blurhash")
public String blurhash;
```

构造媒体属性时从 `serverAttachment.blurhash` 传递值；不存在时传 `null`。

- [ ] **Step 3: 验证 Android BlurHash 解码链路**

确认返回的 `Attachment.blurhash` 经过 `postprocess()` 创建 `blurhashPlaceholder`，列表加载时显示真实 BlurHash；无值仍走通用占位图。

### Task 5: 迁移、部署和生产回归

**Files:**
- No additional source files.

- [ ] **Step 1: Android 构建验证**

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:testDebugUnitTest :mastodon:assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m
```

- [ ] **Step 2: 备份并执行生产 D1 迁移**

```bash
npx wrangler d1 export abdl-space-db --remote --output /tmp/abdl-space-before-post-image-blurhash.sql
npx wrangler d1 execute abdl-space-db --remote --file migrations/0041_post_images_blurhash.sql
```

- [ ] **Step 3: 部署并验证上传响应**

```bash
npm run deploy
```

上传一张测试静态图片，确认 `/api/v1/media` 返回非空 `blurhash`；上传视频/GIFV 确认返回 `null`。

- [ ] **Step 4: 验证帖子 Status 媒体字段**

创建带图片的测试帖子，确认列表和详情 API 的 `media_attachments[].blurhash` 与上传响应一致；检查旧媒体仍可返回 `null`。

- [ ] **Step 5: 检查并提交**

分别在 Android 和后端仓库执行 `git diff --check`，成功构建后提交；保留后端工作区中与本功能无关的已有修改，不纳入本次提交。
