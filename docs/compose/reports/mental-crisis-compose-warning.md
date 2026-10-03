---
feature: mental-crisis-compose-warning
status: delivered
specs: []
plans:
  - docs/compose/plans/2026-07-20-mental-crisis-compose-warning.md
branch: develop
commits: a7481314..a7481314
---

# 发帖心理危机提示 — Final Report

## What Was Built

发帖页现在会在正文中检测心理危机相关词汇。首次检测到匹配内容时，页面顶部显示红色心理危机提示卡，标题为“你不孤单，我们都在”，并显示指定的关怀文案及“关闭”“我需要帮助”两个按钮。

心理危机提示卡优先级高于宝宝新天地绑定卡：危机卡显示时，绑定卡隐藏；关闭危机提示后恢复绑定卡原有状态。本次发帖页关闭提示后不再自动重复显示，帮助按钮暂不连接实际帮助功能。

## Architecture

关键词检测位于 `ComposeFragment` 的正文 `TextWatcher` 中，仅在客户端内存中处理文本，不上传、不持久化、不记录用户输入。检测支持中文关键词和大小写不敏感的 `ADHD`、`PTSD`。

新增 `include_compose_crisis_card.xml` 及红色卡片、按钮和警告图标资源。卡片直接嵌入 `fragment_compose.xml`，放在宝宝新天地卡片之前，并使用主题错误色适配明暗主题。

### Design Decisions

- 复用顶部卡片布局而不是 `AlertDialog`，因为提示需要位于发帖页面顶部并和现有绑定卡保持一致。
- 关闭状态只保存在当前 `ComposeFragment` 实例中，避免跨页面打扰用户，也不保存敏感输入内容。
- 除用户指定词汇外，增加了“自杀”“自残”相关的常见表达，如“不想活”“活不下去”“轻生”“绝望”“伤害自己”，提升明显危机表达的覆盖率。

## Usage

在发帖正文输入以下任一词汇即可触发提示：

```text
自杀、自残、死亡、抑郁、双向、双相、ADHD、PTSD、童年创伤、不想活、活不下去、轻生、绝望、伤害自己
```

点击“关闭”或“我需要帮助”会关闭本次页面的危机提示；“我需要帮助”当前不执行外部操作。

## Verification

- `:mastodon:compileDebugJavaWithJavac` 成功。
- `:mastodon:testDebugUnitTest` 成功，但项目没有 Debug 单元测试源码，任务结果为 `NO-SOURCE`。
- `:mastodon:assembleDebug` 成功。
- `git diff --check` 通过。
- 资源覆盖了默认语言和简体中文；完整真机交互仍需在连接设备后验证。

## Journey Log

- [lesson] 关键词检测放在正文 TextWatcher 中，可以覆盖草稿初始化后的正文内容，同时不需要把用户输入发送到任何服务端。
- [lesson] 心理危机卡必须在 NBW 卡片前，并由统一的卡片更新逻辑恢复 NBW 卡状态，避免两个提示同时显示。

## Source Materials

| File | Role | Notes |
|------|------|-------|
| `docs/compose/plans/2026-07-20-mental-crisis-compose-warning.md` | Implementation plan | Completed |
| `mastodon/src/main/java/org/joinmastodon/android/fragments/ComposeFragment.java` | Detection and priority logic | Runtime source of truth |
| `mastodon/src/main/res/layout/include_compose_crisis_card.xml` | Crisis card layout | Red themed card and buttons |
