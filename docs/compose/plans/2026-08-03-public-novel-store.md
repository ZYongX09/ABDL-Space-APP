# 公开书城与读者互动 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提供公开小说发现、搜索、作品详情、收藏、点赞、评分、章节评论和规则推荐。

**Architecture:** 公开读取只暴露已发布 revision；互动写入使用唯一约束和幂等接口，推荐由后端可解释规则计算。

**Tech Stack:** Hono、D1、Compose、Miuix、Paging-style cursor API

---

### Task 1: 建立互动 schema

**Covers:** [S12, S13]

**Files:**
- Create: `../git/abdl-space/migrations/0052_novel_interactions.sql`
- Create: `../git/abdl-space/src/lib/novel-interactions.test.ts`

- [ ] **Step 1: 写唯一性和外键测试**

断言收藏/点赞/评分分别以 `(user_id, novel_id)` 唯一；评论关联已发布章节；举报保留 reporter、target和状态；聚合表不能由客户端直接写入。

- [ ] **Step 2: 运行确认失败并实现 migration**

Run: `node --experimental-strip-types --test src/lib/novel-interactions.test.ts`
Expected before implementation: FAIL；实现后 PASS。

- [ ] **Step 3: 全量验证并提交**

Run: `npm test && npm run lint`
Expected: PASS。

`git add migrations/0052_novel_interactions.sql src/lib/novel-interactions.test.ts package.json && git commit -m "feat(novels): add reader interaction schema"`

### Task 2: 公开作品与章节读取 API

**Covers:** [S7, S9, S14]

**Files:**
- Create: `../git/abdl-space/src/routes/novels.ts`
- Create: `../git/abdl-space/src/routes/novels.test.ts`
- Modify: `../git/abdl-space/src/index.ts`

- [ ] **Step 1: 写公开边界测试**

覆盖仅published作品可见、章节只返回current_revision、评级和内容提示始终返回、私人书和审核快照不可访问、cursor稳定分页、搜索不返回下架作品。

- [ ] **Step 2: 运行确认失败**

Run: `node --experimental-strip-types --test src/routes/novels.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现 list/search/detail/volumes/chapters/content**

所有列表查询显式过滤 published和未下架状态；章节正文响应带 revision ID、ETag和更新时间，供离线缓存判断版本。

- [ ] **Step 4: 验证并提交**

Run: `npm test && npm run lint`
Expected: PASS。

`git add src/routes/novels.ts src/routes/novels.test.ts src/index.ts && git commit -m "feat(novels): expose published catalog APIs"`

### Task 3: 收藏、点赞、评分、评论和举报 API

**Covers:** [S12, S13, S14]

**Files:**
- Modify: `../git/abdl-space/src/routes/novels.ts`
- Modify: `../git/abdl-space/src/routes/novels.test.ts`

- [ ] **Step 1: 写幂等和权限测试**

重复收藏/点赞保持单行；取消重复调用成功；评分1-5且覆盖旧值；评论作者只能删自己的评论，管理员可下架；举报不能伪造reporter；私人book ID全部404。

- [ ] **Step 2: 运行确认失败并实现**

Run: `node --experimental-strip-types --test src/routes/novels.test.ts`
Expected before implementation: FAIL；实现后 PASS。

- [ ] **Step 3: 全量验证并提交**

Run: `npm test && npm run lint`
Expected: PASS。

`git add src/routes/novels.ts src/routes/novels.test.ts && git commit -m "feat(novels): add catalog interactions"`

### Task 4: 可解释规则推荐

**Covers:** [S12]

**Files:**
- Create: `../git/abdl-space/src/lib/novel-ranking.ts`
- Create: `../git/abdl-space/src/lib/novel-ranking.test.ts`
- Modify: `../git/abdl-space/src/routes/novels.ts`

- [ ] **Step 1: 写排序测试**

固定时钟，断言下架/审核中被过滤；近期更新、收藏、点赞、评分、完成度分别有上限；单项异常值不能永久垄断榜首；相同分数按稳定ID排序。

- [ ] **Step 2: 运行确认失败并实现纯函数**

Run: `node --experimental-strip-types --test src/lib/novel-ranking.test.ts`
Expected before implementation: FAIL；实现后 PASS。

- [ ] **Step 3: 验证并提交**

Run: `npm test && npm run lint`
Expected: PASS。

`git add src/lib/novel-ranking.ts src/lib/novel-ranking.test.ts src/routes/novels.ts && git commit -m "feat(novels): add explainable recommendations"`

### Task 5: Android书城、详情和互动 UI

**Covers:** [S4, S5, S12]

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/api/novels/NovelCatalogApi.java`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/novel/catalog/NovelCatalogScreen.kt`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/novel/catalog/NovelDetailScreen.kt`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/novel/comments/ChapterCommentsScreen.kt`
- Test: `mastodon/src/test/kotlin/org/joinmastodon/android/novel/catalog/NovelCatalogReducerTest.kt`

- [ ] **Step 1: 写 UI状态测试**

覆盖分页去重、搜索切换取消旧请求、收藏/点赞乐观更新失败回滚、评分覆盖、评论分页、评级与内容提示始终显示。

- [ ] **Step 2: 运行确认失败**

Run: `gradle :mastodon:testDebugUnitTest --tests org.joinmastodon.android.novel.catalog.NovelCatalogReducerTest`
Expected: FAIL。

- [ ] **Step 3: 实现 Miuix页面**

推荐页使用Miuix书卡和筛选，详情页显示封面、简介、评级、提示和目录。章节评论从Reader controls打开独立页面，不覆盖正文。

- [ ] **Step 4: 验证并提交**

Run: `gradle :mastodon:testDebugUnitTest --tests org.joinmastodon.android.novel.catalog.NovelCatalogReducerTest && gradle :mastodon:assembleDebug`
Expected: PASS。

`git add mastodon/src/main/java/org/joinmastodon/android/api/novels mastodon/src/main/kotlin/org/joinmastodon/android/novel && git commit -m "feat(novels): add public catalog and interactions"`
