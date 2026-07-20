# 心理危机帖子标记与详情干预 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Android 发帖页的心理危机检测结果传给后端持久化，并在带标记帖子详情页每次进入时显示同款危机干预卡，同时标记生产帖子 581。

**Architecture:** Android 继续维护关键词词库并在创建状态请求中发送 `mental_crisis=true/false`。后端 D1 的 `posts.mental_crisis` 保存该布尔值，Mastodon Status 列表、详情和创建响应统一返回 `mental_crisis`。Android 抽取通用危机卡控制器，Compose 使用本地检测结果，ThreadFragment 使用后端标记；详情页每次新进入显示一次。

**Tech Stack:** Java 17 Android Views、Gson、Hono、Cloudflare Workers、D1 SQLite、Wrangler。

---

### Task 1: 后端数据库和 Status 协议

**Files:**
- Modify: `/home/ZYongX/projects/git/abdl-space/schemas/schema.sql`
- Create: `/home/ZYongX/projects/git/abdl-space/migrations/0040_posts_mental_crisis.sql`
- Modify: `/home/ZYongX/projects/git/abdl-space/src/mastodon/types.ts`
- Modify: `/home/ZYongX/projects/git/abdl-space/src/mastodon/converter.ts`

- [ ] **Step 1: 增加 D1 字段和生产迁移**

在 `posts` 表增加：

```sql
mental_crisis INTEGER NOT NULL DEFAULT 0
```

迁移文件内容为：

```sql
ALTER TABLE posts ADD COLUMN mental_crisis INTEGER NOT NULL DEFAULT 0;
```

- [ ] **Step 2: 增加 TypeScript 类型和转换字段**

`MastodonStatus` 增加：

```ts
mental_crisis: boolean
```

`toStatus()` 的输入类型增加 `mental_crisis?: boolean | number`，输出增加：

```ts
mental_crisis: !!post.mental_crisis
```

- [ ] **Step 3: 运行后端类型检查和相关测试**

运行：

```bash
npm run typecheck
npm test -- --runInBand
```

预期：类型检查和已有测试成功；若项目脚本不支持 `--runInBand`，使用 `npm test`。

### Task 2: 后端创建、编辑和所有 Status 返回路径

**Files:**
- Modify: `/home/ZYongX/projects/git/abdl-space/src/mastodon/routes.ts`
- Modify: `/home/ZYongX/projects/git/abdl-space/src/routes/posts.ts`
- Modify: `/home/ZYongX/projects/git/abdl-space/src/types/index.ts`

- [ ] **Step 1: 接收 Android 创建请求中的布尔值**

在 `POST /api/v1/statuses` body 类型增加：

```ts
mental_crisis?: boolean
```

解析为：

```ts
const mentalCrisis=body.mental_crisis ? 1 : 0;
```

并在 `INSERT INTO posts` 中写入 `mental_crisis`。

- [ ] **Step 2: 让创建和编辑接口返回/维护标记**

创建响应显式传递 `mental_crisis: !!post.mental_crisis`。编辑状态接口支持可选 `mental_crisis`，仅在字段存在时更新；原生 `/api/posts` 创建接口也接受可选 `mental_crisis`，否则使用默认 `0`。

- [ ] **Step 3: 覆盖所有 Mastodon Status 映射**

检索所有 `has_nsfw: !!post.has_nsfw`、`has_nsfw: !!r.has_nsfw` 和 `toStatus()` 调用，在帖子对象映射中传递 `mental_crisis`，包括单帖、首页/公开/ABDL 列表、搜索、收藏、书签、通知上下文、回复树和转发目标。

- [ ] **Step 4: 后端验证**

运行：

```bash
npm run typecheck
npm test -- --runInBand
```

并用本地 D1 迁移验证字段存在，检查 `mental_crisis=1` 的状态 JSON 返回布尔 `true`。

### Task 3: Android 发帖协议和通用危机卡

**Files:**
- Modify: `/home/ZYongX/projects/moshidon-test/mastodon/src/main/java/org/joinmastodon/android/model/Status.java`
- Modify: `/home/ZYongX/projects/moshidon-test/mastodon/src/main/java/org/joinmastodon/android/api/requests/statuses/CreateStatus.java`
- Create or modify: `/home/ZYongX/projects/moshidon-test/mastodon/src/main/java/org/joinmastodon/android/ui/views/CrisisWarningViewController.java`
- Modify: `/home/ZYongX/projects/moshidon-test/mastodon/src/main/java/org/joinmastodon/android/fragments/ComposeFragment.java`
- Modify: `/home/ZYongX/projects/moshidon-test/mastodon/src/main/res/layout/include_compose_crisis_card.xml`

- [ ] **Step 1: 增加客户端协议字段**

`Status` 增加：

```java
@SerializedName("mental_crisis")
public boolean mentalCrisis;
```

`CreateStatus.Request` 增加：

```java
@SerializedName("mental_crisis")
public boolean mentalCrisis;
```

在 Compose 创建请求处设置：

```java
req.mentalCrisis=containsCrisisKeyword(text);
```

- [ ] **Step 2: 抽取通用危机卡控制器**

控制器接收危机卡根 View，绑定关闭和帮助按钮，提供：

```java
void show()
void hide()
boolean isVisible()
```

按钮行为统一为隐藏当前卡片；控制器不负责关键词检测或后端请求。

- [ ] **Step 3: 让 Compose 使用控制器并保留 NBW 优先级**

将 Compose 的卡片按钮和显示/隐藏操作迁移到控制器，保留“本页关闭后不再自动触发”和危机卡显示时隐藏 NBW 卡的现有行为。

- [ ] **Step 4: Android 编译验证**

运行：

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:compileDebugJavaWithJavac --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m
```

预期：`BUILD SUCCESSFUL`。

### Task 4: 帖子详情页干预提示

**Files:**
- Modify: `/home/ZYongX/projects/moshidon-test/mastodon/src/main/res/layout/fragment_thread.xml`
- Modify: `/home/ZYongX/projects/moshidon-test/mastodon/src/main/java/org/joinmastodon/android/fragments/ThreadFragment.java`

- [ ] **Step 1: 在详情页加入同款危机卡**

复用通用危机卡布局，放在详情页帖子内容区域顶部，不插入 `StatusDisplayItem` 列表，避免影响回复索引和连线。

- [ ] **Step 2: 在 `onViewCreated()` 绑定后端标记**

使用主帖内容状态：

```java
Status status=mainStatus.getContentStatus();
if(status.mentalCrisis)
    crisisWarningController.show();
else
    crisisWarningController.hide();
```

每次新建/进入详情 Fragment 都重新绑定，因此每次进入显示一次；按钮关闭后只影响当前 Fragment 实例。

- [ ] **Step 3: 详情页编译验证**

运行 `:mastodon:compileDebugJavaWithJavac`，确认 ThreadFragment 和通用控制器无编译错误。

### Task 5: 生产迁移、部署和帖子 581 标记

**Files:**
- No additional source files.

- [ ] **Step 1: 备份并执行生产 D1 迁移**

在后端仓库执行：

```bash
npx wrangler d1 export abdl-space-db --remote --output /tmp/abdl-space-before-mental-crisis.sql
npx wrangler d1 execute abdl-space-db --remote --file migrations/0040_posts_mental_crisis.sql
```

- [ ] **Step 2: 部署 Worker 并验证 API**

```bash
npm run deploy
```

用生产 API 查询一个状态，确认 JSON 包含 `mental_crisis` 布尔字段。

- [ ] **Step 3: 标记帖子 581**

迁移和部署验证成功后执行：

```bash
npx wrangler d1 execute abdl-space-db --remote --command "UPDATE posts SET mental_crisis = 1 WHERE id = 581"
npx wrangler d1 execute abdl-space-db --remote --command "SELECT id, mental_crisis FROM posts WHERE id = 581"
```

预期查询结果为 `id=581, mental_crisis=1`，并通过帖子 API 验证 `mental_crisis=true`。

- [ ] **Step 4: Android 最终验证和提交**

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:testDebugUnitTest :mastodon:assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m
git diff --check
```

预期构建成功；单元测试若无源码记录 `NO-SOURCE`。成功构建后分别提交 Android 和后端改动。
