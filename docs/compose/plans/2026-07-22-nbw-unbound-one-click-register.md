# NBW Unbound One-Click Register Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the NBW OAuth login “not bound” dead end with a choice page that can either return to ABDL login or one-click prefill ABDL registration from NBW data and bind the NBW account during registration.

**Architecture:** Backend `mobile-callback` should return a signed short-lived `nbw_bind` JWT for unbound NBW accounts instead of a raw NBW OAuth access token, then expose a token-protected user-info endpoint that calls NBW S2S `get_user_info`. Android reuses `NBWNotBoundActivity` as the choice page, loads NBW user-info for one-click registration, passes prefill/bind extras to `RegisterInfoFragment`, and `RegisterInfoFragment` skips `NBWPostRegisterActivity` when registration was launched from NBW OAuth.

**Tech Stack:** Cloudflare Workers Hono TypeScript backend, D1, NBW S2S API, Android Java/appkit, OkHttp/Gson, XML layouts.

---

### Task 1: Backend NBW Bind Token and User Info

**Covers:** backend callback, secure token, user-info proxy

**Files:**
- Modify: `/home/ZYongX/projects/git/abdl-space/src/routes/nbw.ts`
- Modify: `/home/ZYongX/projects/git/abdl-space/src/routes/nbw.test.ts`

- [ ] Add tests proving `mobile-callback` unbound flow returns a signed `nbw_bind` token rather than a raw NBW OAuth access token.
- [ ] Add tests for verifying the signed token and rejecting invalid/expired tokens.
- [ ] Add `POST /api/auth/nbw/user-info` with body `{ nbw_token }`, verify token, call `nbwS2SRequest(env, 'get_user_info', { query: uid })`, and return safe registration prefill fields: `uid`, `username`, `email`, `avatar`.
- [ ] Run `node --experimental-strip-types --test src/routes/nbw.test.ts` and `npm test`.

### Task 2: Android Unbound Choice Page

**Covers:** NBWNotBoundActivity UI and navigation

**Files:**
- Modify: `/home/ZYongX/projects/moshidon-test/mastodon/src/main/java/org/joinmastodon/android/fragments/settings/NBWNotBoundActivity.java`
- Modify: `/home/ZYongX/projects/moshidon-test/mastodon/src/main/res/layout/activity_nbw_not_bound.xml`

- [ ] Rebuild the layout using the old bind-guide three-icon row: ABDL icon, link icon, NBW icon.
- [ ] Add two actions: “一键注册新 ABDL Space 账号” and “登录已有 ABDL Space 账号”.
- [ ] For existing login, navigate to `MainActivity`/`LoginEmailFragment` state by clearing back stack as the current login entry expects.
- [ ] For one-click register, call the backend user-info endpoint with `nbw_token`; on success launch `RegisterInfoFragment` with `email`, `prefill_username`, `nbw_token`, `nbw_uid`, `nbw_username`, and `from_nbw_oauth_register=true`.
- [ ] Show a Toast after navigation: “已帮你自动填写部分来自宝宝新天地的账号信息，请完善密码，也可以修改预填信息”.

### Task 3: Android Registration Prefill and Bound Completion

**Covers:** RegisterInfoFragment prefilling and post-register navigation

**Files:**
- Modify: `/home/ZYongX/projects/moshidon-test/mastodon/src/main/java/org/joinmastodon/android/fragments/auth/RegisterInfoFragment.java`

- [ ] Read prefill extras and set `usernameEdit`/email display from NBW user info.
- [ ] Include `nbw_token` in `/api/auth/register` request body when `from_nbw_oauth_register=true`.
- [ ] On success from NBW OAuth registration, call `addAccount`, show Toast “宝宝新天地账号已绑定”, and go directly to `MainActivity` home; do not open `NBWPostRegisterActivity`.
- [ ] Preserve ordinary email registration behavior: ordinary registration still opens `NBWPostRegisterActivity`.

### Task 4: Verification

**Covers:** build and runtime confidence

**Files:**
- Backend and Android files above

- [ ] Run backend tests: `npm test` in `/home/ZYongX/projects/git/abdl-space`.
- [ ] Deploy backend API Worker: `npm run deploy:api` after tests pass.
- [ ] Compile Android: `./gradlew :mastodon:compileDebugJavaWithJavac --no-daemon` in `/home/ZYongX/projects/moshidon-test`.
- [ ] Report changed files and any residual manual verification needed for the OAuth browser callback.
