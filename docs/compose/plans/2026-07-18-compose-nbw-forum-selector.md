# Compose NBW Forum Selector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the App composer visibility control with the web client's NBW forum selector and publish every new post as public.

**Architecture:** `ComposeFragment` owns the selected forum and renders the existing `btn_visibility` as a forum menu. A small authenticated request class calls the existing recommendation endpoint when AI mode is selected, while `CreateStatus.Request` carries the resolved `nbw_fid`; visibility is always assigned `PUBLIC` before submission.

**Tech Stack:** Java 17, Android XML/AppKit, existing Mastodon API request stack.

---

### Task 1: Add NBW forum request data

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/api/requests/nbw/RecommendNBWForum.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/api/requests/statuses/CreateStatus.java`

- [ ] **Step 1: Add a request-field regression check**

Run a source assertion that fails until `CreateStatus.Request` contains serialized `nbwFid` and `RecommendNBWForum` targets `/auth/nbw/recommend-fid` with `/api` prefix.

- [ ] **Step 2: Implement the minimal request classes**

Add `@SerializedName("nbw_fid") public Integer nbwFid` to `CreateStatus.Request`. Add `RecommendNBWForum` as an authenticated `POST` request with `{ content }`, returning `{ fid, forumName, confidence }` and overriding `getPathPrefix()` to `/api`.

- [ ] **Step 3: Re-run the request-field assertion**

Expected: both source assertions pass.

### Task 2: Replace visibility with forum selection

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/ComposeFragment.java`
- Modify: `mastodon/src/main/res/values/strings.xml`
- Modify: `mastodon/src/main/res/values-zh-rCN/strings.xml`

- [ ] **Step 1: Add a behavior regression check**

Run a source assertion that fails until `onVisibilityClick()` exposes AI/28/27/26/3 forums, `actuallyPublish()` fixes visibility to `PUBLIC`, and the request receives `nbwFid`.

- [ ] **Step 2: Implement forum state and menu**

Store selected forum as `0` for AI recommendation by default. Replace visibility menu handling with `ExtendedPopupMenu` items for AI 推荐、自拍、分享、小说/漫画、交友. Update the existing two-text animation to show the selected forum and use an appropriate existing icon.

- [ ] **Step 3: Resolve AI recommendation before publishing**

When selected forum is `0`, call `RecommendNBWForum` with trimmed post text before entering the existing publish pipeline. Accept only `28`, `27`, `26`, or `3`; on request error or invalid response use `27`. Manual choices skip the request.

- [ ] **Step 4: Force public visibility and submit the forum**

Set `req.visibility=StatusPrivacy.PUBLIC` and `req.nbwFid=resolvedNBWFid`. Stop applying account posting visibility preferences and do not restore visibility from replies or saved state.

- [ ] **Step 5: Re-run the behavior assertion**

Expected: all forum and request assertions pass.

### Task 3: Verify and commit

**Files:**
- Verify all files from Tasks 1-2.

- [ ] **Step 1: Build the debug APK**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Check the focused diff**

Run `git diff --check` and confirm only the planned Java/resource files plus this plan are included.

- [ ] **Step 3: Commit after successful build**

Commit with `feat(compose): 发帖改用宝宝新天地版块选择`.
