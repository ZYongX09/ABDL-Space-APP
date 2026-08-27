# 私人小说云书架 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户私密上传、解析、下载和跨设备同步 TXT/EPUB/粘贴文本，并在改造后的 FlowReader 中离线阅读。

**Architecture:** 后端 D1 保存私有元数据与同步游标，对象存储保存原文件；Android 使用 authorize/PUT/complete 直传和账号隔离 Room 镜像。

**Tech Stack:** Hono、Cloudflare Workers、D1、Tencent COS、OkHttp、Room、Compose、WorkManager

---

### Task 1: 建立私人书籍与同步 schema

**Covers:** [S6, S10, S11, S13]

**Files:**
- Create: `../git/abdl-space/migrations/0050_novel_private_library.sql`
- Create: `../git/abdl-space/src/lib/novel-private.test.ts`

- [ ] **Step 1: 写失败 schema 测试**

用内存 SQLite 执行 migration，断言存在 `private_books`、`novel_sync_items`，主键显式 `NOT NULL`，`private_books(owner_id, content_hash)` 有索引，sync item 唯一键为 `(owner_id, item_type, item_id)`。

- [ ] **Step 2: 运行确认失败**

Run in backend: `node --experimental-strip-types --test src/lib/novel-private.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现 migration**

`private_books` 保存 id、owner_id、title、author、format、object_key、content_hash、declared_size、verified_size、parse_status、created_at、updated_at、deleted_at；`novel_sync_items` 保存 book_id、item_type、item_id、payload_json、updated_at、deleted_at。外键全部限制 owner 范围。

- [ ] **Step 4: 验证并提交**

Run: `npm test && npm run lint`
Expected: PASS。

`git add migrations/0050_novel_private_library.sql src/lib/novel-private.test.ts package.json && git commit -m "feat(novels): add private library schema"`

### Task 2: 实现私有文件 authorize/complete

**Covers:** [S6, S13, S14]

**Files:**
- Create: `../git/abdl-space/src/routes/novel-private.ts`
- Create: `../git/abdl-space/src/routes/novel-private.test.ts`
- Modify: `../git/abdl-space/src/index.ts`
- Modify: `../git/abdl-space/src/types/index.ts`

- [ ] **Step 1: 写路由失败测试**

覆盖未认证401、非 TXT/EPUB 400、超限413、客户端 object key 400、其他 owner complete 404、HEAD大小/类型不符422、成功 complete 200、重复 complete 幂等200，以及返回值不含永久公开 URL。

- [ ] **Step 2: 运行确认失败**

Run: `node --experimental-strip-types --test src/routes/novel-private.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现协议**

注册 `/api/v1/novels/private`。authorize生成 `novels/private/<owner>/<uuid>.<ext>`，签名 PUT并禁止覆盖；complete使用服务端记录 key 做 HEAD，校验 owner、大小和 MIME后更新 verified字段。下载端点仅为 owner签发短时 GET，不返回 bucket公开地址。

- [ ] **Step 4: 验证并提交**

Run: `npm test && npm run lint && npx wrangler deploy --dry-run`
Expected: PASS。

`git add src/routes/novel-private.ts src/routes/novel-private.test.ts src/index.ts src/types/index.ts package.json && git commit -m "feat(novels): add private book upload protocol"`

### Task 3: 实现列表、删除、粘贴与同步 API

**Covers:** [S6, S10, S14]

**Files:**
- Modify: `../git/abdl-space/src/routes/novel-private.ts`
- Modify: `../git/abdl-space/src/routes/novel-private.test.ts`

- [ ] **Step 1: 写行为测试**

覆盖 cursor分页、跨账号 detail 404、粘贴文本规范化为 UTF-8、删除写 tombstone、增量同步只返回 cursor之后数据、旧设备上传不能复活较新的 tombstone。

- [ ] **Step 2: 运行确认失败**

Run: `node --experimental-strip-types --test src/routes/novel-private.test.ts`
Expected: FAIL新增用例。

- [ ] **Step 3: 实现最小路由**

增加 `GET /books`、`GET/DELETE /books/:id`、`POST /paste`、`GET /sync?cursor=`、`PUT /sync/items/:id`。所有 owner来自认证会话；payload类型仅允许 progress/bookmark/note，删除使用 `deleted_at`。

- [ ] **Step 4: 验证并提交**

Run: `npm test && npm run lint`
Expected: PASS。

`git add src/routes/novel-private.ts src/routes/novel-private.test.ts && git commit -m "feat(novels): add private library sync APIs"`

### Task 4: Android上传、下载与导入

**Covers:** [S5, S6, S11, S14]

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/api/novels/PrivateNovelApi.java`
- Create: `mastodon/src/main/java/org/joinmastodon/android/api/novels/PrivateBookUpload.java`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/novel/importer/NovelImportCoordinator.kt`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/novel/download/NovelDownloadWorker.kt`
- Test: `mastodon/src/test/java/org/joinmastodon/android/api/novels/PrivateBookUploadTest.java`

- [ ] **Step 1: 写上传状态机失败测试**

断言 authorize→PUT→complete顺序、取消时停止当前 Call、失败不写 complete、下载先写 `.part` 且校验成功后原子重命名。

- [ ] **Step 2: 运行确认失败**

Run: `gradle :mastodon:testDebugUnitTest --tests org.joinmastodon.android.api.novels.PrivateBookUploadTest`
Expected: FAIL。

- [ ] **Step 3: 实现网络链路**

复用 `MastodonAPIController`共享 OkHttpClient和当前账号 token；SAF只获取持久 URI读取权限，不申请广泛存储权限。TXT/EPUB导入先复制到 App私有临时文件、计算SHA-256，再授权上传。下载 Worker以 accountId作为唯一工作名的一部分。

- [ ] **Step 4: 验证并提交**

Run: `gradle :mastodon:testDebugUnitTest --tests org.joinmastodon.android.api.novels.PrivateBookUploadTest && gradle :mastodon:compileDebugKotlin`
Expected: PASS。

`git add mastodon/src/main/java/org/joinmastodon/android/api/novels mastodon/src/main/kotlin/org/joinmastodon/android/novel && git commit -m "feat(novels): add private book transfer pipeline"`

### Task 5: 书架 UI 与云同步

**Covers:** [S4, S5, S10, S11]

**Files:**
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/novel/library/NovelLibraryViewModel.kt`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/novel/library/NovelLibraryScreen.kt`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/novel/sync/NovelSyncEngine.kt`
- Test: `mastodon/src/test/kotlin/org/joinmastodon/android/novel/sync/NovelSyncEngineTest.kt`

- [ ] **Step 1: 写同步失败测试**

覆盖进度节流、书签/笔记LWW、tombstone、账号切换停止旧同步、冲突不跨账号写入。

- [ ] **Step 2: 运行确认失败**

Run: `gradle :mastodon:testDebugUnitTest --tests org.joinmastodon.android.novel.sync.NovelSyncEngineTest`
Expected: FAIL。

- [ ] **Step 3: 实现 UI 与同步**

书架页展示公开收藏/私人书籍、上传/粘贴、下载和同步状态；ViewModel只观察当前账号数据库。阅读位置在稳定后2秒节流上传，书签笔记立即排队，网络恢复后按 cursor拉取再推送本地变更。

- [ ] **Step 4: 验证并提交**

Run: `gradle :mastodon:testDebugUnitTest --tests org.joinmastodon.android.novel.sync.NovelSyncEngineTest && gradle :mastodon:assembleDebug`
Expected: PASS。

`git add mastodon/src/main/kotlin/org/joinmastodon/android/novel && git commit -m "feat(novels): add cloud library and sync UI"`
