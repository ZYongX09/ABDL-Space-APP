# 发帖心理危机提示实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在发帖页检测心理危机相关词汇，并以红色顶部提示卡优先于宝宝新天地绑定卡显示。

**Architecture:** 复用发帖页现有顶部卡片布局，在 `fragment_compose.xml` 增加危机提示卡。`ComposeFragment` 的正文 `TextWatcher` 只做本地关键词匹配，首次触发后显示卡片并隐藏 NBW 卡；本次页面关闭后不再自动触发。帮助按钮暂不接实际动作。

**Tech Stack:** Java 17、Android Views、现有 `ComposeFragment`、XML drawable/color resources。

---

### Task 1: 添加红色危机提示卡布局和资源

**Covers:** S1, S2

**Files:**
- Modify: `mastodon/src/main/res/layout/fragment_compose.xml`
- Modify: `mastodon/src/main/res/values/strings.xml`
- Modify: `mastodon/src/main/res/values-zh-rCN/strings.xml`
- Create: `mastodon/src/main/res/drawable/bg_compose_crisis_card.xml`
- Create: `mastodon/src/main/res/drawable/bg_compose_crisis_button.xml`
- Create: `mastodon/src/main/res/drawable/ic_fluent_warning_24_filled.xml`

- [ ] **Step 1: 添加中文文案资源**

在 `values-zh-rCN/strings.xml` 添加：

```xml
<string name="compose_crisis_title">你不孤单，我们都在</string>
<string name="compose_crisis_message">这个世界虽然不完美，但总有人守护着你。\n你可以点击以下按钮获得帮助</string>
<string name="compose_crisis_close">关闭</string>
<string name="compose_crisis_help">我需要帮助</string>
```

在默认 `values/strings.xml` 添加等义英文 fallback 文案，保证资源完整。

- [ ] **Step 2: 添加红色卡片、按钮和图标资源**

卡片 drawable 使用红色描边和主题感知的浅红/深红背景；按钮 drawable 使用红色实心背景和白色文字，图标使用 Material 风格红色警告图标。

- [ ] **Step 3: 在发帖页顶部插入卡片**

在 `fragment_compose.xml` 中将危机卡放在 `newbabyworld_card` 之前，初始 `visibility="gone"`，包含：

```xml
<ImageView android:id="@+id/compose_crisis_icon" ... />
<TextView android:id="@+id/compose_crisis_title" ... />
<TextView android:id="@+id/compose_crisis_message" ... />
<Button android:id="@+id/compose_crisis_close" ... />
<Button android:id="@+id/compose_crisis_help" ... />
```

两个按钮采用水平排列，帮助按钮为红色主按钮，关闭按钮为红色边框/文字按钮。

- [ ] **Step 4: 运行资源编译检查**

运行：

```bash
./gradlew :mastodon:compileDebugJavaWithJavac
```

预期：`BUILD SUCCESSFUL`。

### Task 2: 实现关键词检测和卡片优先级

**Covers:** S3, S4

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/ComposeFragment.java:180-210, 318-321, 590-650`

- [ ] **Step 1: 添加检测状态和关键词集合**

增加字段：

```java
private View crisisCard;
private boolean crisisWarningDismissed;
private static final String[] CRISIS_KEYWORDS={
    "自杀", "自残", "死亡", "抑郁", "双向", "双相", "ADHD", "PTSD",
    "童年创伤", "不想活", "活不下去", "轻生", "绝望", "伤害自己"
};
```

增加本地方法：

```java
private boolean containsCrisisKeyword(String text){
    if(TextUtils.isEmpty(text))
        return false;
    String normalized=text.toLowerCase(Locale.ROOT);
    for(String keyword:CRISIS_KEYWORDS){
        if(normalized.contains(keyword.toLowerCase(Locale.ROOT)))
            return true;
    }
    return false;
}
```

- [ ] **Step 2: 绑定控件和按钮行为**

在 `onCreateView` 绑定危机卡和两个按钮：

```java
crisisCard=view.findViewById(R.id.compose_crisis_card);
view.findViewById(R.id.compose_crisis_close).setOnClickListener(v->dismissCrisisWarning());
view.findViewById(R.id.compose_crisis_help).setOnClickListener(v->dismissCrisisWarning());
```

`dismissCrisisWarning()` 设置 `crisisWarningDismissed=true`、隐藏危机卡，然后调用 `updateNewBabyWorldCard()` 恢复 NBW 卡原有显示状态。

- [ ] **Step 3: 接入正文 TextWatcher 并处理优先级**

在现有 `mainEditText` TextWatcher 的 `afterTextChanged` 中调用：

```java
private void updateCrisisWarning(String text){
    if(crisisCard==null || crisisWarningDismissed)
        return;
    boolean show=containsCrisisKeyword(text.toString());
    crisisCard.setVisibility(show ? View.VISIBLE : View.GONE);
    if(show)
        newBabyWorldCard.setVisibility(View.GONE);
    else
        updateNewBabyWorldCard();
}
```

初始化已有草稿时也调用一次 `updateCrisisWarning(mainEditText.getText())`，保证恢复草稿同样检测。

- [ ] **Step 4: 编译验证状态逻辑**

运行：

```bash
./gradlew :mastodon:compileDebugJavaWithJavac
```

预期：`BUILD SUCCESSFUL`。

### Task 3: 构建和 UI 回归验证

**Covers:** S1, S2, S3, S4

**Files:**
- No additional files.

- [ ] **Step 1: 运行 Debug 单元测试任务**

运行：

```bash
./gradlew :mastodon:testDebugUnitTest
```

预期：`BUILD SUCCESSFUL`；若项目无测试源码，记录 `NO-SOURCE`。

- [ ] **Step 2: 运行完整 Debug 构建**

运行：

```bash
./gradlew :mastodon:assembleDebug
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 3: 真机/模拟器检查交互**

验证以下场景：

```text
普通正文：危机卡隐藏，NBW 卡按原逻辑显示。
输入“自杀”：顶部出现红色危机卡，NBW 卡隐藏。
输入“ADHD”或“PTSD”：大小写变化均能触发。
点击“关闭”：危机卡隐藏，当前发帖页后续不再自动触发，NBW 卡恢复。
点击“我需要帮助”：当前暂不执行帮助功能，并保持提示不再自动触发。
重新打开新的发帖页：检测状态重新开始。
```

- [ ] **Step 4: 检查差异并提交**

运行：

```bash
git diff --check
git status --short
```

成功构建后按项目规则提交：

```bash
git add mastodon/src/main/java/org/joinmastodon/android/fragments/ComposeFragment.java mastodon/src/main/res/layout/fragment_compose.xml mastodon/src/main/res/values/strings.xml mastodon/src/main/res/values-zh-rCN/strings.xml mastodon/src/main/res/drawable/bg_compose_crisis_card.xml mastodon/src/main/res/drawable/bg_compose_crisis_button.xml mastodon/src/main/res/drawable/ic_fluent_warning_24_filled.xml
git commit -m "feat(ui): 增加发帖心理危机提示"
```
