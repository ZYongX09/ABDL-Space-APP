# AGENTS.md — ABDL Space Android App (Moshidon Fork)

## What is this
Mastodon Android client fork called "ABDL Space". Java only (no Kotlin, no Compose). Single module `:mastodon`. Fragment-based navigation with XML layouts. Package: `top.abdl_space.app`, namespace: `org.joinmastodon.android`.

## Branch model (gitflow simplified)
- **develop** — all feature work, bug fixes, daily commits
- **main** — stable releases only; merges ONLY from `develop` or `hotfix/*`
- **hotfix/*** — urgent bug fixes branched from current `main`, merged back to `main` (仅在用户明确要求时使用)
- Every merge to `main` gets a tag. Official APK is built from tagged commits.
- **CRITICAL**: hotfix branches MUST be merged into BOTH `main` AND `develop` to keep develop up to date.

## Release workflow
1. 在 `mastodon/build.gradle` 中 `versionName` 和 `versionCode` 加 1
2. 构建 release APK：`assembleRelease`
3. 用户测试通过后，合并 develop → main
4. 打 tag（如 `v1.1.0`）
5. 上传 APK 到后端：`POST /api/v1/version/upload`（multipart/form-data, admin only）

## Build & test
- **Debug**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleDebug --no-daemon`
- **Release**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :mastodon:assembleRelease --no-daemon`
- Gradle 8.5 requires JDK 17 (not 21+)
- Release APK: `mastodon/build/outputs/apk/release/abdl-space-app-release.apk`
- Debug APK: `mastodon/build/outputs/apk/debug/abdl-space-app-debug.apk`
- Signing: `mastodon/release.keystore`, alias `abdl-space`, password `ZhX&ZYongX_0311`
- Filter logcat: `adb logcat -s top.abdl_space.app.debug`

## Hard rules
- User communicates in Chinese — always reply in Chinese
- When user reports a bug, reproduce it yourself before proposing fixes
- Branding: all user-visible "Mastodon" → "ABDL Space" (exception: QR scan page)
- User is 18+ only — no minors allowed on the platform
- Never push to `main` directly; only merge from `develop` or `hotfix/*`

## Architecture key points
- **Theme**: Default is BLUE. `GlobalUserPreferences.color` defaults to `BLUE`. `Theme_Mastodon_Dark` has hardcoded purple `#563ACC` — must override with `accentColor` from Activity's theme when custom colors needed.
- **QR page**: Uses `Theme_Mastodon_Dark` wrapper for `colorM3OnPrimary` visibility. Override accent colors (code_container bgTint, domain textColor, particles, corners) with hardcoded blue values (`QR_BG_COLOR = #CEE5FF`, `QR_DOT_COLOR = #004A76`).
- **Announcements**: Disabled by Moshidon fork (`// MOSHIDON: for now announcements are broken`). Backend API works; re-enable in `HomeTabFragment.java`.
- **Link preview cards**: Backend `linkpreview.ts` generates cards. `fetchOgMetadata` uses real browser UA. bilibili may fail in Cloudflare Workers due to anti-bot.
- **formatContent in converter.ts**: URL→link conversion uses negative lookbehind `(?<!href=")` to prevent double-nesting.
- **Notification badge**: `markAsRead(true)` in `onHidden()` auto-marks all as read when user leaves notifications tab.
- **LAN login**: UDP broadcast on port 9527. `LanDiscoveryService` runs as foreground service. `sShownSessionId` prevents duplicate dialogs.
- **Proguard**: `-keep class org.joinmastodon.android.R$drawable { *; }` prevents R8 from stripping XML-only referenced drawables (splash_screen_bg).

## Related projects
- **Backend**: `/home/ZYongX/projects/git/abdl-space/` — Hono + CF Workers + D1
- **Frontend**: `/home/ZYongX/projects/abdl-space-v2/` — Vite + React
- **Mobile web**: `/home/ZYongX/projects/abdl-space-mobile/` — separate React project
