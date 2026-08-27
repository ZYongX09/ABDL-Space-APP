# 小说平台加固与发布 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成恶意文件防护、迁移、性能、许可证、全链路验证和生产发布门禁，使私人书架与公开创作可同时安全开放。

**Architecture:** 在既有纵向切片之上增加文件沙箱、迁移验证、账号隔离审查、真实云端烟测和 Release真机验收，不引入新产品功能。

**Tech Stack:** Android Release、Room migration tests、Cloudflare Workers、D1、COS、MiMo、Gradle、Wrangler

---

### Task 1: 加固 TXT/EPUB 解析

**Covers:** [S13, S15]

**Files:**
- Modify: `reader-core/src/main/kotlin/org/joinmastodon/reader/parser/TxtBookParser.kt`
- Modify: `reader-core/src/main/kotlin/org/joinmastodon/reader/parser/EpubBookParser.kt`
- Test: `reader-core/src/test/kotlin/org/joinmastodon/reader/parser/MaliciousBookTest.kt`

- [ ] **Step 1: 写恶意样本测试**

覆盖ZIP路径穿越、超高压缩比、文件数超限、解压总体积超限、DOCTYPE/外部实体、外部图片URL、超长TXT行和控制字符；全部返回受控错误且不写出沙箱目录。

- [ ] **Step 2: 运行确认失败并实现限制**

Run: `gradle :reader-core:testDebugUnitTest --tests org.joinmastodon.reader.parser.MaliciousBookTest`
Expected before implementation: FAIL；实现后 PASS。

- [ ] **Step 3: 提交**

`git add reader-core && git commit -m "fix(reader): harden imported book parsing"`

### Task 2: Room migration 与账号切换验证

**Covers:** [S3, S10, S15]

**Files:**
- Modify: `reader-core/src/main/kotlin/org/joinmastodon/reader/data/NovelDatabase.kt`
- Test: `reader-core/src/androidTest/kotlin/org/joinmastodon/reader/data/NovelMigrationTest.kt`
- Test: `mastodon/src/test/kotlin/org/joinmastodon/android/novel/NovelAccountIsolationTest.kt`

- [ ] **Step 1: 写 migration与切换测试**

从每个已发布schema版本迁移到最新；验证书、章节、进度、书签、笔记数量不变。模拟A→B切换，断言A的Flow取消、Worker取消、内存状态清空，B只打开自己的数据库。

- [ ] **Step 2: 运行确认失败并修复**

Run: `gradle :reader-core:connectedDebugAndroidTest :mastodon:testDebugUnitTest --tests org.joinmastodon.android.novel.NovelAccountIsolationTest`
Expected before implementation: FAIL；实现后 PASS。

- [ ] **Step 3: 提交**

`git add reader-core mastodon && git commit -m "test(novels): verify migrations and account isolation"`

### Task 3: 后端集成与生产前安全测试

**Covers:** [S6, S7, S8, S9, S10, S12, S13, S14, S15]

**Files:**
- Create: `../git/abdl-space/src/routes/novels-integration.test.ts`
- Modify: `../git/abdl-space/package.json`

- [ ] **Step 1: 写完整集成测试**

串联注册资格→创建作品→草稿→审核→发布→公开读取→收藏评分评论；并串联私有authorize→complete→同步→删除。加入跨账号、MiMo失败、HEAD不符、重复幂等键和发布竞态。

- [ ] **Step 2: 运行并修复所有失败**

Run: `npm test && npm run lint && npx wrangler deploy --dry-run`
Expected: 全部 PASS，dry-run无缺失binding。

- [ ] **Step 3: 提交**

`git add src/routes/novels-integration.test.ts package.json && git commit -m "test(novels): cover end-to-end backend flows"`

### Task 4: 性能、baseline profile 与许可证

**Covers:** [S2, S4, S11, S15]

**Files:**
- Modify: `mastodon/src/main/baseline-prof.txt`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/settings/OpenSourceLicensesFragment.java`
- Create: `docs/licenses/flowreader.md`

- [ ] **Step 1: 增加许可证展示测试**

源码契约断言开放源代码许可页包含 FlowReader、GPL-3.0和上游URL；baseline profile覆盖 `NovelActivity`、reader parser、ReaderScreen和小说首页。

- [ ] **Step 2: 运行测试并构建非debuggable变体**

Run: `gradle :mastodon:testDebugUnitTest :mastodon:assembleScreenshotsUiTest --max-workers=1`
Expected: PASS。

- [ ] **Step 3: 使用真机基准**

清缓存后分别打开100章TXT和大型EPUB，记录首开时间、翻页FrameTimeline、内存峰值和10分钟温升；正文滚动/翻页不得出现持续重组或实时blur。

- [ ] **Step 4: 提交**

`git add mastodon/src/main/baseline-prof.txt mastodon/src/main/java/org/joinmastodon/android/fragments/settings/OpenSourceLicensesFragment.java docs/licenses/flowreader.md && git commit -m "perf(novels): profile reader and document FlowReader"`

### Task 5: 生产迁移与端到端验收

**Covers:** [S15, S16]

**Files:**
- Verify only: `../git/abdl-space/migrations/0050_novel_private_library.sql`
- Verify only: `../git/abdl-space/migrations/0051_novel_authoring.sql`
- Verify only: `../git/abdl-space/migrations/0052_novel_interactions.sql`

- [ ] **Step 1: 建立生产 D1 导出备份**

使用Wrangler导出生产库到带UTC时间戳的本地文件，并记录SHA-256。由于已有migration账本异常，禁止直接批量执行全部pending migrations。

- [ ] **Step 2: 单独应用并核验0050-0052**

逐个执行已审查SQL；每次查询 `sqlite_master`、外键、索引和空表计数。失败时停止部署，不继续下一份migration。

- [ ] **Step 3: 配置Secret与真实烟测**

交互式设置 `MIMO_API_KEY`；用小TXT和EPUB完成真实PUT/HEAD/GET/delete；使用测试作者完成MiMo安全、拒绝和故障三条路径。日志不得输出正文、token或签名URL。

- [ ] **Step 4: 部署后端并验证认证边界**

Run in backend: `npm test && npm run lint && npx wrangler deploy`
Expected: 部署成功；未认证私有/创作写接口401，跨账号404，公开published读取200。

- [ ] **Step 5: 构建 Android Release并真机验收**

Run: `gradle :mastodon:assembleRelease --no-daemon --max-workers=1`
Expected: BUILD SUCCESSFUL、签名有效。

真机依次验证：TXT/EPUB/粘贴上传、取消重试、离线整书、进度/书签/笔记双设备同步、A/B账号隔离、断网创作、冲突保留、MiMo评级/拒绝/申诉、revision原子更新、收藏/评分/点赞/评论、浅色/深色和长时间阅读。

- [ ] **Step 6: 提交构建产生的必要源码变更**

按项目规则，成功构建后提交本阶段所有已验证源码；不提交APK、密钥、生产备份或 `local.properties`。
