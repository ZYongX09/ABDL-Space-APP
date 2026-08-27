# Badge Display System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add verified badge (圈内认证) display to the Android app — icon next to username in posts/profile, pill badge below profile bio, and click-to-explain popup.

**Architecture:** Backend stores badge definitions in `badges` table and user badge records in `user_badges` table. The existing `GET /api/users/:id/badges` endpoint returns badge data. Android app fetches badges via this API and renders: (1) a circle verified icon next to username in `StatusDisplayItem` and `ProfileFragment`, (2) a horizontal flow of pill-shaped badge labels below the profile bio, and (3) a `BottomSheet` explanation popup on badge click following the `DecentralizationExplainerSheet` pattern.

**Tech Stack:** Cloudflare Workers Hono backend, D1 SQLite, Android Java, appkit fragments, vector drawables.

---

### Task 1: Backend — Seed Badge Data + Update API Docs

**Files:**
- Modify: `/home/ZYongX/projects/git/abdl-space/schemas/account-system.sql`
- Create: `/home/ZYongX/projects/git/abdl-space/migrations/0042_badge_verified.sql`
- Modify: `/home/ZYongX/projects/git/abdl-space/API.md`

- [ ] **Step 1: Create migration to insert "宝宝认证" badge definition**

```sql
-- migrations/0042_badge_verified.sql
INSERT OR IGNORE INTO badges (key, name, icon, description, condition_type, condition_value)
VALUES ('verified', '圈内认证', 'verified', '用户身份经过平台验证，获得圈内认证徽章。', 'manual', 0);
```

- [ ] **Step 2: Update badges table schema comment in account-system.sql**

Change the comment from `-- 徽章定义（预留）` to `-- 徽章定义`.

- [ ] **Step 3: Update API.md** — add badge endpoint documentation under a new section:

```markdown
### GET /api/users/:id/badges — 获取用户徽章

返回指定用户已获得的徽章列表。

**响应：**
```json
{
  "user_id": 5,
  "badges": [
    {
      "key": "verified",
      "name": "圈内认证",
      "icon": "verified",
      "description": "用户身份经过平台验证，获得圈内认证徽章。",
      "unlocked_at": "2026-07-22T10:00:00Z",
      "displayed": true
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `key` | string | 徽章唯一标识 |
| `name` | string | 徽章显示名称 |
| `icon` | string | 徽章图标 key（App 端映射为本地 drawable） |
| `description` | string | 徽章详细说明 |
| `unlocked_at` | string | 解锁时间（ISO 8601） |
| `displayed` | boolean | 是否在个人主页展示 |
```

- [ ] **Step 4: Run migration against remote D1**

```bash
CF_ACCOUNT_ID=c5a9726ee4c59c70d9261881af33ca87 npx wrangler d1 execute abdl-space-db --remote --file=./migrations/0042_badge_verified.sql
```

- [ ] **Step 5: Verify badge was inserted**

```bash
CF_ACCOUNT_ID=c5a9726ee4c59c70d9261881af33ca87 npx wrangler d1 execute abdl-space-db --remote --command "SELECT key, name, icon FROM badges"
```

Expected: one row with `key=verified`, `name=圈内认证`, `icon=verified`.

- [ ] **Step 6: Run backend tests**

```bash
npm test
```

Expected: all 31 tests pass.

---

### Task 2: Android — Vector Drawables from SVG

**Files:**
- Create: `/home/ZYongX/projects/moshidon-test/mastodon/src/main/res/drawable/ic_badge_verified_circle.xml`
- Create: `/home/ZYongX/projects/moshidon-test/mastodon/src/main/res/drawable/ic_badge_verified_pill.xml`

- [ ] **Step 1: Convert `verified.svg` to `ic_badge_verified_circle.xml`**

The SVG is a shield+checkmark. Create a 24dp vector drawable (adapted to theme colors). For dark theme: use `?attr/textColorSecondary` or a fixed blue like `#5C9CE6`. For the circle badge next to username, use 16dp size.

- [ ] **Step 2: Convert `verified-2.svg` to `ic_badge_verified_pill.xml`**

The SVG is a pill/rounded-rectangle with checkmark + text. Create a vector drawable for the pill background shape (the checkmark icon part; the text "圈内认证" will be a separate `TextView` beside it in the layout).

- [ ] **Step 3: Create badge pill layout `item_profile_badge.xml`**

A horizontal LinearLayout or chip-style view: icon (ImageView) + name (TextView), with rounded background.

---

### Task 3: Android — Username Badge Icon (Posts + Profile)

**Files:**
- Modify: `layout/item_status_header.xml` (or wherever the username is in the post header)
- Modify: `layout/fragment_profile.xml` (username area)
- Modify: relevant display item Java files

- [ ] **Step 1: Add verified icon ImageView next to username in post header layout**

Size: 16dp, marginStart: 4dp, visibility: GONE by default.

- [ ] **Step 2: Add verified icon next to username in profile header layout**

Same icon, same sizing.

- [ ] **Step 3: In display item binding code, show/hide the icon based on `account.roles` or badge data**

When rendering a post or profile, if the account has `verified` badge, set icon visible.

---

### Task 4: Android — Profile Badge Pills

**Files:**
- Modify: `layout/fragment_profile.xml` (add badge area below bio/about)
- Modify: `ProfileFragment.java` (fetch badges, populate badge area)

- [ ] **Step 1: Add badge container in profile layout**

Below the about/bio section, add a `FlowLayout` or wrapped `LinearLayout` for badge pills. No special header — just spacing from bio.

- [ ] **Step 2: Fetch badges from API in `ProfileFragment`**

When account data loads, call `GET /api/users/:id/badges`. For each badge, inflate `item_profile_badge.xml` and add to the container.

- [ ] **Step 3: Badge click opens BottomSheet**

On badge pill click, open a new `BadgeExplainerSheet` (follows `DecentralizationExplainerSheet` pattern) showing badge name, icon, and full description.

---

### Task 5: Android — Badge Explainer BottomSheet

**Files:**
- Create: `layout/sheet_badge_explainer.xml`
- Create: `ui/sheets/BadgeExplainerSheet.java`

- [ ] **Step 1: Create layout `sheet_badge_explainer.xml`**

Following `sheet_decentralization_info` pattern: badge icon (large), badge name, description text, dismiss button.

- [ ] **Step 2: Create `BadgeExplainerSheet.java`**

Extends `BottomSheet`. Takes badge name, icon key, and description. Renders them in the sheet. Dismiss button closes.

- [ ] **Step 3: Wire badge pill click to open `BadgeExplainerSheet`**

In `ProfileFragment`, set click listener on each badge pill to open the sheet with that badge's data.

---

### Task 6: Verification

- [ ] **Step 1: Compile Android**

```bash
./gradlew :mastodon:compileDebugJavaWithJavac --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Deploy backend**

```bash
npm run deploy:api
```

- [ ] **Step 3: Manual verification checklist**

- Profile of a user with `user_badges` record shows verified icon next to username
- Profile shows badge pill below bio
- Clicking badge pill opens explanation sheet
- Profile of user without badge shows no icon and no pills
- Posts by verified user show icon next to username
