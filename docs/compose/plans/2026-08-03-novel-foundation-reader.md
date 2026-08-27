# 小说基础架构与阅读内核 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 FlowReader 的阅读与 Room 书库能力纳入 ABDL Space，建立账号隔离的本地小说基础，并从首页右上菜单进入可运行的小说壳页面。

**Architecture:** 新建 `:reader-core` Android library，保留并改造 FlowReader 的 domain/data/reader 能力，删除独立 App 导航。`mastodon` 只负责账号、网络与小说业务 UI；Room 所有云数据以 `accountId` 隔离。

**Tech Stack:** Android 13+、Kotlin 2.4.10、Compose、Miuix 0.9.3、Room 2.6.1、KAPT、JUnit

---

### Task 1: 提升全 App 最低系统版本

**Covers:** [S1, S2]

**Files:**
- Modify: `mastodon/build.gradle`
- Modify: `mastodon/src/main/AndroidManifest.xml`
- Test: `mastodon/src/test/kotlin/org/joinmastodon/android/MinSdkContractTest.kt`

- [ ] **Step 1: 写失败契约测试**

新增源码契约测试，读取 `mastodon/build.gradle` 并断言存在 `minSdk 33`，同时断言 Manifest 不再声明 `READ_EXTERNAL_STORAGE` 和 `WRITE_EXTERNAL_STORAGE`。

- [ ] **Step 2: 运行测试确认失败**

Run: `gradle :mastodon:testDebugUnitTest --tests org.joinmastodon.android.MinSdkContractTest`
Expected: FAIL，当前仍为 `minSdk 24` 且保留旧存储权限。

- [ ] **Step 3: 最小修改平台配置**

将 `defaultConfig.minSdk` 改为 `33`；删除仅服务 API 32及以下的两项外部存储权限。保留 SAF 文件选择，不新增广泛文件权限。

- [ ] **Step 4: 验证**

Run: `gradle :mastodon:testDebugUnitTest --tests org.joinmastodon.android.MinSdkContractTest && gradle :mastodon:processDebugMainManifest`
Expected: PASS。

- [ ] **Step 5: 提交**

`git add mastodon/build.gradle mastodon/src/main/AndroidManifest.xml mastodon/src/test/kotlin/org/joinmastodon/android/MinSdkContractTest.kt && git commit -m "build: require Android 13 or newer"`

### Task 2: 创建 reader-core 模块并保留上游许可证

**Covers:** [S2, S3]

**Files:**
- Modify: `settings.gradle`
- Modify: `build.gradle`
- Create: `reader-core/build.gradle`
- Create: `reader-core/consumer-rules.pro`
- Create: `reader-core/LICENSE.flowreader`
- Create: `reader-core/UPSTREAM.md`
- Modify: `mastodon/build.gradle`

- [ ] **Step 1: 写模块装配失败测试**

在 `mastodon/src/test/kotlin/org/joinmastodon/android/reader/ReaderModuleContractTest.kt` 断言 `Class.forName("org.joinmastodon.reader.data.NovelDatabase")` 可加载。

- [ ] **Step 2: 运行确认失败**

Run: `gradle :mastodon:testDebugUnitTest --tests org.joinmastodon.android.reader.ReaderModuleContractTest`
Expected: FAIL，模块和类不存在。

- [ ] **Step 3: 建立模块**

`reader-core/build.gradle` 使用 `com.android.library`、`org.jetbrains.kotlin.android`、`org.jetbrains.kotlin.kapt`、`org.jetbrains.kotlin.plugin.compose`；设置 namespace `org.joinmastodon.reader`、`minSdk 33`、Java/Kotlin 17，并加入 Compose、Room runtime/ktx、`kapt "androidx.room:room-compiler:2.6.1"`、jsoup和 EPUB解析所需依赖。`settings.gradle` 加入 `include ':reader-core'`，`mastodon` 加入 `implementation project(':reader-core')`。

- [ ] **Step 4: 固化来源**

复制 FlowReader GPL-3.0许可证至 `LICENSE.flowreader`；`UPSTREAM.md` 写明仓库 URL、导入 commit、保留/移除模块和后续人工移植策略。

- [ ] **Step 5: 验证并提交**

Run: `gradle :reader-core:assembleDebug :mastodon:testDebugUnitTest --tests org.joinmastodon.android.reader.ReaderModuleContractTest`
Expected: PASS。

`git add settings.gradle build.gradle reader-core mastodon/build.gradle mastodon/src/test/kotlin/org/joinmastodon/android/reader/ReaderModuleContractTest.kt && git commit -m "feat(reader): add FlowReader-based core module"`

### Task 3: 迁移并改造 Room 本地书库

**Covers:** [S3, S10, S11]

**Files:**
- Create: `reader-core/src/main/kotlin/org/joinmastodon/reader/data/NovelDatabase.kt`
- Create: `reader-core/src/main/kotlin/org/joinmastodon/reader/data/NovelBookEntity.kt`
- Create: `reader-core/src/main/kotlin/org/joinmastodon/reader/data/NovelChapterEntity.kt`
- Create: `reader-core/src/main/kotlin/org/joinmastodon/reader/data/BookmarkEntity.kt`
- Create: `reader-core/src/main/kotlin/org/joinmastodon/reader/data/AnnotationEntity.kt`
- Create: `reader-core/src/main/kotlin/org/joinmastodon/reader/data/NovelBookDao.kt`
- Test: `reader-core/src/androidTest/kotlin/org/joinmastodon/reader/data/NovelDatabaseTest.kt`

- [ ] **Step 1: 写账号隔离失败测试**

插入 `account_a/book_1` 与 `account_b/book_1`，断言 `observeBooks("account_a")` 只返回 A；删除 A 不影响 B；所有复合唯一键均包含 `accountId`。

- [ ] **Step 2: 运行确认失败**

Run: `gradle :reader-core:connectedDebugAndroidTest`
Expected: FAIL，数据库尚不存在。

- [ ] **Step 3: 实现 schema**

以 FlowReader实体为基础，将本地自增主键改为稳定字符串 ID，并新增 `accountId`、`remoteId`、`sourceType`、`contentHash`、`localFilePath`、`downloadState`、`remoteUpdatedAt`、`deletedAt`。书签/笔记主键同样稳定且含账号索引。数据库名按账号生成 `novels_<sha256(accountId)>.db`，禁止把原始账号写入文件名。

- [ ] **Step 4: 验证并提交**

Run: `gradle :reader-core:connectedDebugAndroidTest :reader-core:assembleDebug`
Expected: PASS，Room schema 导出到 `reader-core/schemas/`。

`git add reader-core && git commit -m "feat(reader): add account-scoped Room library"`

### Task 4: 抽取 TXT/EPUB 解析与阅读界面

**Covers:** [S3, S4, S5]

**Files:**
- Create: `reader-core/src/main/kotlin/org/joinmastodon/reader/parser/BookParser.kt`
- Create: `reader-core/src/main/kotlin/org/joinmastodon/reader/parser/TxtBookParser.kt`
- Create: `reader-core/src/main/kotlin/org/joinmastodon/reader/parser/EpubBookParser.kt`
- Create: `reader-core/src/main/kotlin/org/joinmastodon/reader/ui/ReaderScreen.kt`
- Create: `reader-core/src/main/kotlin/org/joinmastodon/reader/ui/ReaderViewModel.kt`
- Test: `reader-core/src/test/kotlin/org/joinmastodon/reader/parser/BookParserTest.kt`

- [ ] **Step 1: 写解析失败测试**

测试 UTF-8/GB18030 TXT、EPUB目录顺序、章节标题和稳定锚点；断言同一内容重复解析产生相同 chapter/anchor ID。

- [ ] **Step 2: 运行确认失败**

Run: `gradle :reader-core:testDebugUnitTest --tests org.joinmastodon.reader.parser.BookParserTest`
Expected: FAIL。

- [ ] **Step 3: 迁移最小阅读核心**

从 FlowReader复制并改包名：domain model、TXT/EPUB parser、`ReaderContent`、`PagedReader`、`ReaderControls`、`ReaderSettingsSheet` 和文本映射。移除 Hilt、Navigation、完整 library screen、备份与独立 Application依赖；通过 `ReaderRepository` 接口读取章节和保存位置。

- [ ] **Step 4: 验证并提交**

Run: `gradle :reader-core:testDebugUnitTest :reader-core:assembleDebug && git diff --check -- reader-core`
Expected: PASS。

`git add reader-core && git commit -m "feat(reader): port TXT and EPUB reading core"`

### Task 5: 新增小说 Activity 与首页菜单入口

**Covers:** [S4, S5]

**Files:**
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/novel/NovelActivity.kt`
- Create: `mastodon/src/main/kotlin/org/joinmastodon/android/novel/ui/NovelHomeScreen.kt`
- Modify: `mastodon/src/main/AndroidManifest.xml`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java`
- Modify: `mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarView.kt`
- Test: `mastodon/src/test/kotlin/org/joinmastodon/android/novel/NovelEntryContractTest.kt`

- [ ] **Step 1: 写入口失败测试**

断言右侧根菜单包含稳定 ID `R.id.novels`，选择后构造带当前 `account` extra 的 `NovelActivity` Intent；Activity `exported=false`。

- [ ] **Step 2: 运行确认失败**

Run: `gradle :mastodon:testDebugUnitTest --tests org.joinmastodon.android.novel.NovelEntryContractTest`
Expected: FAIL。

- [ ] **Step 3: 实现最小页面**

仿 `ComposeAboutActivity` 设置主题和 edge-to-edge。`NovelHomeScreen` 只提供“推荐/书架/创作”三个 Miuix页签和空状态；入口回调由 `HomeFragment` 启动 Activity，并传当前账号 ID。

- [ ] **Step 4: 验证并提交**

Run: `gradle :mastodon:testDebugUnitTest --tests org.joinmastodon.android.novel.NovelEntryContractTest && gradle :mastodon:assembleDebug`
Expected: PASS。

`git add mastodon && git commit -m "feat(novels): add app entry and Miuix shell"`
