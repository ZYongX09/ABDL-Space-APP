# Profile Notifications Entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the bottom notification tab and expose notification navigation and unread badges through the profile tab and owner-profile toolbar.

**Architecture:** Keep `HomeFragment` as the single unread-count source. Move its bottom badge to the profile tab, propagate the same formatted count into `ProfileFragment`, and open `NotificationsListFragment` as a standalone AppKit page from an owner-only toolbar action.

**Tech Stack:** Java, Android Views, AppKit fragments, Mastodon notification marker events.

---

### Task 1: Remove Notification Bottom Tab

**Covers:** [S1]

**Files:**
- Modify: `mastodon/src/main/res/layout/tab_bar.xml`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/HomeFragment.java`

- [ ] Remove `tab_notifications` from the layout and place `notifications_badge` inside `tab_profile`.
- [ ] Remove the resident notifications child fragment and all save/restore, tab mapping, loading, and inset references.
- [ ] Keep unread-count loading and marker event handling in `HomeFragment`.

### Task 2: Add Owner Profile Notification Action

**Covers:** [S2]

**Files:**
- Create: `mastodon/src/main/res/layout/action_profile_notifications.xml`
- Modify: `mastodon/src/main/res/menu/profile_own.xml`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/ProfileFragment.java`

- [ ] Add a notification menu item immediately before share.
- [ ] Use a 48dp custom action view with a notification icon and numeric badge.
- [ ] Add a setter that stores and renders unread badge text across menu recreation.
- [ ] Navigate to a standalone `NotificationsListFragment` with the current account argument.

### Task 3: Synchronize And Verify

**Covers:** [S3]

- [ ] Send every formatted unread value from `HomeFragment` to both badges.
- [ ] Clear both badges from the existing `NotificationsMarkerUpdatedEvent` path.
- [ ] Run `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug --no-daemon` and commit only related files.

## Self-Review

- Other-user profile notification-follow action remains unchanged.
- Existing unread APIs and marker behavior remain the single source of truth.
- Removed tab IDs are eliminated from HomeFragment restoration and mapping paths.
