# 心理危机词库扩充 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 扩充发帖页心理危机高置信度词库，覆盖“想死”等常见明确表达，同时避免宽泛情绪词误报。

**Architecture:** 保持现有 `ComposeFragment.containsCrisisKeyword()` 本地包含匹配机制不变，仅扩充 `CRISIS_KEYWORDS` 常量。词库只包含明确的轻生、自伤、求死表达，不引入“累了”“难受”等低置信度词。

**Tech Stack:** Java 17、Android Views、Gradle 8.5。

---

### Task 1: 扩充高置信度危机词库

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/ComposeFragment.java:196`

- [ ] **Step 1: 扩充关键词常量**

将以下表达加入 `CRISIS_KEYWORDS`：

```text
想死、去死、求死、寻死、不想活了、不想再活、活着没意思、活着没有意义、
结束生命、结束自己的生命、了结自己、离开这个世界、永远消失、
割腕、割脉、跳楼、跳河、吞药、自我伤害、伤害我自己
```

- [ ] **Step 2: 运行编译和测试任务**

运行：

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:compileDebugJavaWithJavac :mastodon:testDebugUnitTest --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m
```

预期：`BUILD SUCCESSFUL`；若无测试源码则记录 `NO-SOURCE`。

- [ ] **Step 3: 运行完整 Debug 构建**

运行：

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 4: 检查并提交**

运行：

```bash
git diff --check
git add mastodon/src/main/java/org/joinmastodon/android/fragments/ComposeFragment.java docs/compose/plans/2026-07-20-mental-crisis-keywords.md
git commit -m "feat(ui): 扩充心理危机提示词库"
```
