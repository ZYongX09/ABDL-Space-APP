# Rating Flow And UI Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent duplicate diaper-rating entry, refresh detail exactly once after success, and visually polish the diaper and friend-universe screens without changing their established structure.

**Architecture:** Use the existing authenticated `/api/ratings/me/:id` endpoint and AppKit fragment results for rating correctness. Apply a shared spacing, card, theme-color, empty-state, and touch-target pass to the existing Java fragments and XML layouts rather than introducing a new design system.

**Tech Stack:** Java 17, Android Views, AppKit fragment stack, MastodonAPIRequest, XML resources.

---

### Task 1: Rating Entry And Refresh

**Covers:** [S1]

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/api/requests/diapers/GetMyDiaperRating.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/diapers/DiaperDetailFragment.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/diapers/DiaperRatingFragment.java`

- [ ] Add authenticated `GET /api/ratings/me/:diaperId` request.
- [ ] Gate rating navigation and show `你已经评价过这款纸尿裤` when `rating != null`.
- [ ] Open rating page with `Nav.goForResult`, set success result only after POST succeeds, and refresh detail in `onFragmentResult`.
- [ ] Build with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug --no-daemon` and commit.

### Task 2: Diaper Screen Polish

**Covers:** [S2]

**Files:**
- Modify existing diaper list/detail/ranking/rating Java and XML files under `mastodon/src/main/java/org/joinmastodon/android/fragments/diapers/` and `mastodon/src/main/res/`.

- [ ] Fix list empty-state container, request races, pagination retry, and excess bottom spacing.
- [ ] Improve card hierarchy, selected-chip contrast, 48dp touch targets, themed weak colors, and consistent 16dp spacing.
- [ ] Fix detail optional-section visibility, date safety, refresh resets, and bottom action spacing.
- [ ] Build and commit.

### Task 3: Friend Universe Polish

**Covers:** [S3]

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/FriendRequestListFragment.java`
- Modify: `mastodon/src/main/res/layout/item_friend_request.xml`
- Modify friend-request empty-state/card/tag resources.

- [ ] Place RecyclerView and empty state in one weighted content container.
- [ ] Reset recycled avatars, remove invalid `未知` metadata, and improve card information hierarchy.
- [ ] Use theme-aware card/tag colors, 48dp menu target, and stable FAB behavior.
- [ ] Build and commit.

### Task 4: Verification

**Covers:** [S4]

- [ ] Run a fresh full debug build.
- [ ] Review the final diff for unrelated files and verify only intended files were committed.

## Self-Review

- Every requested behavior is covered by Tasks 1-3.
- The plan preserves existing navigation and visual language.
- No backend change is required because the duplicate-check endpoint already exists.
