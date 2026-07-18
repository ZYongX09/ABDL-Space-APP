# Compose NewBabyWorld Binding Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Require a verified NewBabyWorld binding before App publishing, explain federation in a composer card, and make AI forum fallback explicit to the user.

**Architecture:** The backend recommendation response gains a backward-compatible `fallback` flag. `ComposeFragment` refreshes the current account from the server, gates the publish action on verified binding state, renders a theme-aware banner using the settings banner visual language, and pauses AI-mode publishing for explicit fallback confirmation.

**Tech Stack:** Java 17 Android/AppKit, XML resources, Hono/Cloudflare Workers, D1 account data.

---

### Task 1: Mark AI recommendation fallback responses

**Files:**
- Modify: `/home/ZYongX/projects/git/abdl-space/src/routes/nbw.ts:576-637`
- Test: `/home/ZYongX/projects/git/abdl-space/src/routes/nbw.test.ts`

- [ ] **Step 1: Add focused tests for explicit fallback state**

Assert that missing API key and non-OK/invalid AI results return `fid: 27` with `fallback: true`, while a valid supported AI result returns `fallback: false`.

- [ ] **Step 2: Run the focused tests and confirm failure**

Run the repository test command scoped to `src/routes/nbw.test.ts`; expect assertions for `fallback` to fail.

- [ ] **Step 3: Add the compatibility field**

Keep `fid`, `forum_name`, and `confidence` unchanged. Add `fallback: true` to every default-share response and `fallback: false` to validated AI output.

- [ ] **Step 4: Run focused backend tests**

Expected: all recommendation fallback tests pass.

- [ ] **Step 5: Deploy and verify the production Worker**

Deploy with Wrangler and verify an authenticated recommendation response contains boolean `fallback` without removing existing fields.

### Task 2: Add the composer federation card

**Files:**
- Create: `mastodon/src/main/res/layout/include_compose_newbabyworld_card.xml`
- Modify: `mastodon/src/main/res/layout/fragment_compose.xml`
- Modify: `mastodon/src/main/res/values/strings.xml`
- Modify: `mastodon/src/main/res/values-zh-rCN/strings.xml`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/ComposeFragment.java`

- [ ] **Step 1: Add source assertions for required card states**

Assert that the compose layout contains a top card and that `ComposeFragment` renders unbound, bound, checking, and check-failed states without user-visible `NBW` text.

- [ ] **Step 2: Create the card layout**

Reuse `bg_settings_banner`, Material 3 text appearances, `ic_nbw_logo`, and a text button. Place it above `btn_visibility` inside the compose scroll content.

- [ ] **Step 3: Implement account-scoped binding refresh**

Call `GetOwnAccount` when the view is created and on resume after launching binding. Update `AccountSessionManager` on success. Treat `nbwUsername == null || blank` as unbound; treat request error as unverified.

- [ ] **Step 4: Implement card actions**

Unbound action opens `NBWPostRegisterActivity` with `show_back=true`. Bound action closes the card for the current process using a static account-ID set. Failed-check action retries. Unbound state always overrides prior close state.

- [ ] **Step 5: Verify card source assertions**

Expected: all layout/state/text assertions pass.

### Task 3: Gate publishing and surface AI results

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/api/requests/nbw/RecommendNBWForum.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/ComposeFragment.java`
- Modify: `mastodon/src/main/res/values/strings.xml`
- Modify: `mastodon/src/main/res/values-zh-rCN/strings.xml`

- [ ] **Step 1: Add behavior source assertions**

Assert publish enablement requires a verified nonblank bound username, AI success updates the button to `AI 推荐 · <版块>`, and AI failure opens a default-share confirmation rather than publishing directly.

- [ ] **Step 2: Extend the Android response model**

Add `boolean fallback` to `RecommendNBWForum.Response`.

- [ ] **Step 3: Gate toolbar publishing**

Include `bindingState == BOUND` in `updatePublishButtonState()`. Recheck binding in the publish click path so stale UI state cannot bypass the gate.

- [ ] **Step 4: Display successful AI classification**

For supported `fid` with `fallback == false`, store the resolved forum name, update `btn_visibility` to `AI 推荐 · <版块>`, and continue publishing with that `nbw_fid`.

- [ ] **Step 5: Confirm fallback publishing**

For request errors, `fallback == true`, or invalid `fid`, show an M3 dialog explaining AI recognition failed. Positive action publishes to 分享 (`fid=27`); negative action returns to editing without sending.

- [ ] **Step 6: Verify behavior assertions**

Expected: all binding and AI-flow assertions pass.

### Task 4: Build, review, and commit

**Files:**
- Verify all Android files from Tasks 2-3.

- [ ] **Step 1: Build the Android debug APK**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Check diffs and user-visible wording**

Run `git diff --check`; inspect all modified Android resources and confirm visible strings say “宝宝新天地” rather than “NBW/nbw”.

- [ ] **Step 3: Commit successful builds**

Commit backend and Android changes separately using focused Chinese conventional commit messages. Do not include unrelated worktree changes.
