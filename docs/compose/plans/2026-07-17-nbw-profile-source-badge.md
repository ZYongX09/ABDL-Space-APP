# NBW Profile Source Badge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the follow action on NBW user profiles with a dashed source badge containing the NBW logo and “此用户来自宝宝新天地”.

**Architecture:** Reuse the profile action container and existing `bg_handle_help` dashed drawable. Detect NBW accounts from the established `nbw_<uid>` account ID convention in `ProfileFragment`, and make its action-state renderer choose either the source badge or the existing relationship action.

**Tech Stack:** Java 17, Android XML layouts/resources, AppKit fragments.

---

### Task 1: Add and render the NBW source badge

**Files:**
- Modify: `mastodon/src/main/res/layout/fragment_profile.xml`
- Modify: `mastodon/src/main/res/values/strings.xml`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/ProfileFragment.java`

- [ ] **Step 1: Record the current failing behavior**

Inspect `ProfileFragment.updateRelationship()` and confirm it has no `nbw_` branch and can show `profile_action_btn` for every non-self account.

- [ ] **Step 2: Add the source badge layout and string**

Add a default-hidden horizontal view inside `profile_action_btn_wrap`. Give it `@drawable/bg_handle_help`, `@drawable/ic_nbw_logo`, and `@string/nbw_profile_source` with value `此用户来自宝宝新天地`.

- [ ] **Step 3: Add minimal NBW display logic**

Bind the source badge in `onCreateContentView()`. Add an `isNBWAccount()` check for `profileAccount.id.startsWith("nbw_")`; when true, hide the relationship button/progress and show the badge. Keep ordinary and self-profile behavior unchanged, including after asynchronous relationship callbacks.

- [ ] **Step 4: Verify resources and Java compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m`

Expected: `BUILD SUCCESSFUL` with Android resource linking and Java compilation passing.

- [ ] **Step 5: Check the focused diff and commit**

Run `git diff --check`, review only the three listed files, then commit with `feat(profile): 标记宝宝新天地用户来源`.
