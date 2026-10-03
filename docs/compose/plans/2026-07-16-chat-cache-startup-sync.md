# Chat Cache And Startup Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display locally cached chat history immediately, fetch only the latest page in the background, and refresh conversations when the app opens.

**Architecture:** Existing account-scoped `ChatStorage` remains the local source of truth. The chat screen renders its local 50-message window before its existing latest-page fetch merges refreshed rows into SQLite. Startup retains the WebSocket delta sync and explicitly refreshes the conversation summary list once.

**Tech Stack:** Java, Android SQLiteOpenHelper, AppKit API requests, Otto events.

---

### Task 1: Render Cache Before History Refresh

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/chat/ChatController.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/chat/ui/ChatFragment.java`

- [ ] Add `ChatController.getCachedMessages(peerId, limit)` forwarding to `ChatStorage.getMessages`.
- [ ] In `ChatFragment.loadMessages`, render cached rows and calculate `oldestMessageId` before starting the existing latest-page request.
- [ ] When the latest-page request succeeds, render the merged local SQLite window so cached optimistic rows are retained while server rows refresh.
- [ ] Compile with `./gradlew :mastodon:assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m`.

### Task 2: Refresh Conversation Summaries On Launch

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/MainActivity.java`

- [ ] Add a non-blocking startup refresh for the active account using `loadConversations(true, ...)`.
- [ ] Publish `ChatEvents.ConversationsUpdatedEvent` after a successful refresh so an already-created conversation tab updates.
- [ ] Invoke the refresh next to the existing first-launch WebSocket connection.
- [ ] Compile with `./gradlew :mastodon:assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m` and verify `git diff --check`.
- [ ] Commit the focused Android changes after the successful build.
