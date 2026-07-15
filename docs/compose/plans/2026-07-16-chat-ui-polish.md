# Chat UI Polish Implementation Plan

> [!NOTE]
> This document may not reflect the current implementation.
> See the final report for up-to-date state:
> [Final Report](../reports/chat-ui-polish.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有私信列表和聊天页优化成清晰、现代、完整适配明暗主题的聊天界面，同时保持消息数据与交互逻辑不变。

**Architecture:** 继续使用现有纯 Java Fragment、RecyclerView 和 XML 资源。会话列表改为真正加载 `fragment_conversations.xml` 并绑定空状态和头像；聊天页在现有布局上补齐用户信息、安全区、气泡层级和输入区视觉，不引入新依赖或新功能。

**Tech Stack:** Java 17、Android Views/XML、RecyclerView、AppKit `ViewImageLoader`、Material 主题属性

---

### Task 1: 完善会话列表页面

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/chat/ui/ConversationsFragment.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/chat/ui/ConversationCell.java`
- Modify: `mastodon/src/main/res/layout/fragment_conversations.xml`
- Modify: `mastodon/src/main/res/layout/item_conversation.xml`
- Create: `mastodon/src/main/res/drawable/bg_chat_avatar.xml`

- [ ] **Step 1: 将 Fragment 接入现有 XML**

让 `onCreateView()` inflate `fragment_conversations.xml`，绑定 `swipe_refresh`、`recycler` 与 `empty_state`，加载完成后按 `data.isEmpty()` 切换列表和空状态。

- [ ] **Step 2: 加载圆形头像并重置复用状态**

在 `ConversationCell.bind()` 中使用 `ViewImageLoader.loadWithoutAnimation()` 和 `UrlImageLoaderRequest` 加载 `conversation.avatar`；无地址时显示 `image_placeholder`，避免 RecyclerView 复用旧头像。

- [ ] **Step 3: 调整列表信息层级**

头像使用 52dp 圆形背景/裁切，名称 16sp 中粗，摘要 14sp，时间 12sp；未读会话名称和摘要强调，未读徽标保持紧凑，整行高度约 76dp。

- [ ] **Step 4: 编译资源与 Java**

Run: `./gradlew :mastodon:compileDebugJavaWithJavac --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m`

Expected: `BUILD SUCCESSFUL`

### Task 2: 完善聊天页面视觉与信息

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/chat/ui/ChatFragment.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/chat/ui/MessageBubbleView.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/chat/ui/ChatMessageAdapter.java`
- Modify: `mastodon/src/main/res/layout/fragment_chat.xml`
- Modify: `mastodon/src/main/res/layout/item_chat_message.xml`
- Modify: `mastodon/src/main/res/drawable/bg_bubble_in.xml`
- Modify: `mastodon/src/main/res/drawable/bg_bubble_out.xml`
- Create: `mastodon/src/main/res/drawable/bg_chat_input.xml`
- Create: `mastodon/src/main/res/drawable/bg_chat_date.xml`

- [ ] **Step 1: 补齐标题栏用户信息**

把返回按钮、圆形头像、用户名和状态文案写入 `fragment_chat.xml`；`ChatFragment` 从参数读取 `peer_avatar`，使用 `ViewImageLoader` 加载头像，并保留现有返回行为。

- [ ] **Step 2: 优化消息区域**

RecyclerView 使用舒适的上下留白与轻微分层背景；气泡最大宽度限制在父容器约 78%，入站和出站使用不同圆角背景，正文、时间和发送状态保持清晰对比。

- [ ] **Step 3: 修正日期分隔与消息顺序表现**

日期分隔改成主题色胶囊标签；适配器继续按日期插入分隔项，但只在相邻日期变化时显示，不改消息持久化和分页协议。

- [ ] **Step 4: 优化输入栏**

输入框使用圆角容器，发送按钮使用主题主色圆形背景；保留多行输入、草稿、输入状态和键盘弹出后滚到底部的现有行为。

- [ ] **Step 5: 编译资源与 Java**

Run: `./gradlew :mastodon:compileDebugJavaWithJavac --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m`

Expected: `BUILD SUCCESSFUL`

### Task 3: 完整验证并提交

**Files:**
- Verify: all files modified in Tasks 1-2

- [ ] **Step 1: 检查改动范围**

Run: `git diff --check && git status --short`

Expected: 无空白错误，只有聊天 UI 和本计划相关改动。

- [ ] **Step 2: 构建 Debug APK**

Run: `./gradlew :mastodon:assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m`

Expected: `BUILD SUCCESSFUL`，APK 位于 `mastodon/build/outputs/apk/debug/`。

- [ ] **Step 3: 提交成功构建的改动**

Run: `git add docs/compose/plans/2026-07-16-chat-ui-polish.md mastodon/src/main/java/org/joinmastodon/android/chat/ui mastodon/src/main/res/layout/fragment_chat.xml mastodon/src/main/res/layout/fragment_conversations.xml mastodon/src/main/res/layout/item_chat_message.xml mastodon/src/main/res/layout/item_conversation.xml mastodon/src/main/res/drawable/bg_chat_avatar.xml mastodon/src/main/res/drawable/bg_chat_input.xml mastodon/src/main/res/drawable/bg_chat_date.xml mastodon/src/main/res/drawable/bg_bubble_in.xml mastodon/src/main/res/drawable/bg_bubble_out.xml && git commit -m "feat(chat): 优化会话列表与聊天页面 UI"`

Expected: commit 成功，随后 `git status --short` 无本次任务遗留改动。
