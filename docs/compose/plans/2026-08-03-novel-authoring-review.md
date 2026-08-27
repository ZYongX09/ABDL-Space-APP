# 小说创作与 MiMo 审核 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 App 内完成作品、分卷、章节和草稿创作，并通过 MiMo结构化审核、评级、发布和人工申诉。

**Architecture:** D1保存作品结构、当前正文与 revision状态，对象存储保存不可变审核快照；客户端本地优先编辑并使用乐观版本检测冲突。

**Tech Stack:** Hono、D1、COS、MiMo API、Room、Compose、Miuix

---

### Task 1: 建立作品与 revision schema

**Covers:** [S7, S8, S9]

**Files:**
- Create: `../git/abdl-space/migrations/0051_novel_authoring.sql`
- Create: `../git/abdl-space/src/lib/novel-authoring.test.ts`

- [ ] **Step 1: 写 schema 测试**

断言 `novels`、`novel_volumes`、`novel_chapters`、`chapter_revisions`、`novel_review_events`、`novel_appeals` 存在；章节当前 revision外键可空；revision状态限制为 draft/review_pending/approved/rejected/published/superseded。

- [ ] **Step 2: 运行确认失败**

Run: `node --experimental-strip-types --test src/lib/novel-authoring.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现 migration并验证**

Run: `npm test && npm run lint`
Expected: PASS。

`git add migrations/0051_novel_authoring.sql src/lib/novel-authoring.test.ts package.json && git commit -m "feat(novels): add authoring schema"`

### Task 2: 实现作者资格与草稿 API

**Covers:** [S8, S14]

**Files:**
- Create: `../git/abdl-space/src/routes/novel-authoring.ts`
- Create: `../git/abdl-space/src/routes/novel-authoring.test.ts`
- Modify: `../git/abdl-space/src/index.ts`

- [ ] **Step 1: 写资格和冲突测试**

覆盖注册不足72小时403、无未删除帖子403、满足两项201、作者删除帖子后已有草稿仍可读、错误作者404、相同base_version更新成功、过期base_version返回409且同时返回server_revision。

- [ ] **Step 2: 运行确认失败**

Run: `node --experimental-strip-types --test src/routes/novel-authoring.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现路由**

提供作品、分卷、章节 CRUD 和 `PUT /revisions/:id/draft`。正文规范化换行、限制字数并只接受受控段落文本。owner只取认证会话，排序更新在 D1 batch内完成。

- [ ] **Step 4: 验证并提交**

Run: `npm test && npm run lint`
Expected: PASS。

`git add src/routes/novel-authoring.ts src/routes/novel-authoring.test.ts src/index.ts && git commit -m "feat(novels): add author drafts and eligibility"`

### Task 3: 实现 MiMo结构化审核

**Covers:** [S9, S13]

**Files:**
- Create: `../git/abdl-space/src/lib/mimo-novel-review.ts`
- Create: `../git/abdl-space/src/lib/mimo-novel-review.test.ts`
- Modify: `../git/abdl-space/src/types/index.ts`
- Modify: `../git/abdl-space/wrangler.jsonc`

- [ ] **Step 1: 写模型边界测试**

覆盖合法四级评级、非法JSON、未知评级、超时、HTTP失败、过长摘要、模型声称安全但风险类别为禁止内容。失败结果必须为 pending/error，绝不能 approved。

- [ ] **Step 2: 运行确认失败**

Run: `node --experimental-strip-types --test src/lib/mimo-novel-review.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现审核客户端**

定义 `NovelReviewResult { excessive_violation, risk_categories, confidence, rating, content_warnings, summary }`；严格手工校验对象、数组、枚举和长度。请求使用 `MIMO_API_KEY` secret，日志只写 revision ID、HTTP状态和安全错误码。

- [ ] **Step 4: 验证并提交**

Run: `npm test && npm run lint && npx wrangler deploy --dry-run`
Expected: PASS。

`git add src/lib/mimo-novel-review.ts src/lib/mimo-novel-review.test.ts src/types/index.ts wrangler.jsonc package.json && git commit -m "feat(novels): add structured MiMo review"`

### Task 4: 审核提交、原子发布与申诉

**Covers:** [S7, S9, S14]

**Files:**
- Modify: `../git/abdl-space/src/routes/novel-authoring.ts`
- Modify: `../git/abdl-space/src/routes/novel-authoring.test.ts`

- [ ] **Step 1: 写状态机测试**

提交时冻结snapshot；模型失败保持pending；过分违规则rejected；安全结果保存评级；编辑已发布章节时旧revision持续可读；新revision批准后单事务切换；申诉只进入human_pending且不能调用同一模型自动批准。

- [ ] **Step 2: 运行确认失败**

Run: `node --experimental-strip-types --test src/routes/novel-authoring.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现状态机并验证**

Run: `npm test && npm run lint`
Expected: PASS。

`git add src/routes/novel-authoring.ts src/routes/novel-authoring.test.ts && git commit -m "feat(novels): publish reviewed chapter revisions"`

### Task 5: Android创作中心与编辑器

**Covers:** [S4, S5, S8, S9]

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/api/novels/NovelAuthoringApi.java`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/novel/author/AuthoringViewModel.kt`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/novel/author/AuthoringScreen.kt`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/novel/author/ChapterEditorScreen.kt`
- Test: `mastodon/src/test/kotlin/org/joinmastodon/android/novel/author/AuthoringReducerTest.kt`

- [ ] **Step 1: 写编辑状态测试**

覆盖本地先保存、自动保存防抖、提交审核后正文只读、409保留local/server两个版本、拒绝展示评级原因与申诉入口。

- [ ] **Step 2: 运行确认失败**

Run: `gradle :mastodon:testDebugUnitTest --tests org.joinmastodon.android.novel.author.AuthoringReducerTest`
Expected: FAIL。

- [ ] **Step 3: 实现最小编辑体验**

使用纯文本段落编辑器和Miuix工具栏；Room revision保存 `baseVersion`、`dirty`、`syncState`。自动保存先本地事务，再延迟同步；冲突弹窗提供保留本地副本、采用云端、复制文本三项，不自动覆盖。

- [ ] **Step 4: 验证并提交**

Run: `gradle :mastodon:testDebugUnitTest --tests org.joinmastodon.android.novel.author.AuthoringReducerTest && gradle :mastodon:assembleDebug`
Expected: PASS。

`git add mastodon/src/main/java/org/joinmastodon/android/api/novels mastodon/src/main/kotlin/org/joinmastodon/android/novel/author && git commit -m "feat(novels): add in-app authoring and review UI"`
