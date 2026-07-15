# Telegram-like 私信 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `compose:subagent` (recommended) or `compose:execute` task-by-task. Do not start Android UI work until Tasks 1-5 pass their verification gates.

**Goal:** 在 ABDL Space Android 客户端实现接近 Telegram 体验的 1v1 文字私信，包括会话列表、气泡聊天、乐观发送、已读、草稿、WebSocket 实时和 JPush 离线通知，同时保证弱网重试、断线恢复和多账号切换不会丢消息、重复消息或串数据。

**Architecture:** D1 是消息与持久化事件的权威存储；消息、双方事件和 outbox 意图原子写入。每用户 `UserPresence` Durable Object 仅分发低延迟事件，Android 通过 `after_event_id` REST sync 恢复任何遗漏。客户端按 `account_id` 隔离，并以 `client_msg_id -> message_id -> event_id` 合并 REST、WS 和 JPush 更新。

**Tech Stack:** Android Java 17 + appkit Fragments + OkHttp/Gson + Otto + SQLite；后端 Hono + Cloudflare Workers + D1 + Durable Objects WebSocket Hibernation + JPush。

**Spec:** `docs/compose/specs/2026-07-15-telegram-like-dm-design.md`

**Repos:**

- Backend: `/home/ZYongX/projects/git/abdl-space/`
- Android: `/home/ZYongX/projects/moshidon-test/`

---

## Cross-cutting invariants

以下约束适用于所有任务，不能在后续实现中弱化：

1. `client_msg_id` 标识发送命令；同一发送者下唯一，重用但 receiver/content 不一致返回 409。
2. `message_id` 标识服务端消息；REST 回包、WS 事件和 JPush payload 必须使用同一个 ID。
3. `event_id` 是全局单调游标；单个用户流出现跳号是正常现象。客户端只要求递增和去重，不使用 `last+1` 判断缺口。
4. `read_up_to_id` 是已读 watermark；不得用无边界的通用 read 事件批量修改新消息。
5. Android 所有聊天数据和运行时实例以 `AccountSession.getID()` 作为 `account_id`。
6. WS 和 JPush 不是消息可靠性的来源；任何遗漏都必须能由 `/api/messages/sync` 恢复。
7. 新 Durable Object namespace 使用 `new_sqlite_classes`；心跳使用 WebSocket auto-response。
8. JPush Master Secret 只能保存在 Wrangler Secret；不得保留公开发送接口或源码凭据。

## File map

### Backend (`abdl-space`)

| File | Responsibility |
|---|---|
| `migrations/00xx_messages_reliability.sql` | `client_msg_id`、events、outbox、索引 |
| `schemas/schema.sql` | 同步完整 schema 文档 |
| `src/routes/messages.ts` | 幂等发送、历史、sync、read watermark、typing |
| `src/lib/message-events.ts` | 原子事件/outbox 写入与分发入口 |
| `src/lib/jpush.ts` | 仅内部可调用的 JPush helper |
| `src/durable-objects/UserPresence.ts` | 每用户 hibernatable WS 连接与广播 |
| `src/index.ts` / `src/api-worker.ts` | `/api/ws` 鉴权、DO 导出 |
| `wrangler.jsonc` | DO、Queue producer/consumer、Cron、observability、migration |
| `src/**/*.test.ts` | D1、DO、路由与并发测试 |

### Android (`moshidon-test`)

| File | Responsibility |
|---|---|
| `.../chat/model/*` | Conversation、ChatMessage、SendState、ChatEvent |
| `.../chat/api/*` | conversations/history/sync/send/read/typing |
| `.../chat/ChatStorage.java` | account-scoped SQLite 与 sync cursor |
| `.../chat/ChatController.java` | 顺序应用事件、会话/消息协调 |
| `.../chat/MessageSendHelper.java` | 乐观发送、幂等重试、三路回显合并 |
| `.../chat/ChatRealtimeClient.java` | 每账号 WS、同步边界缓冲、重连恢复 |
| `.../chat/ui/*` | 列表、气泡、输入、动画 |
| `JPushReceiver.java` / `MainActivity.java` | account-targeted 通知抑制与深链 |

---

## Phase 0: Security and protocol gate

### Task 1: 修复 JPush 安全与账号注册语义

**Covers:** S5.1, S6.7, S10

**Files:**

- Modify: backend `src/routes/jpush.ts`
- Create: backend `src/lib/jpush.ts`
- Modify: backend types/config and JPush registration migration if needed
- Modify later in Task 11: Android `JPushReceiver.java`

- [ ] **Step 1: 立即轮换已暴露的 JPush Master Secret**

在 JPush 控制台轮换；用交互式命令写入 Worker Secret，不把值写入 shell history、源码或文档：

```bash
npx wrangler secret put JPUSH_APP_KEY
npx wrangler secret put JPUSH_MASTER_SECRET
```

- [ ] **Step 2: 删除或严格封闭 `POST /api/jpush/send`**

消息路由只能调用 `src/lib/jpush.ts` 的内部函数。不得通过公开 HTTP 自调用。

- [ ] **Step 3: 明确设备注册策略**

第一版采用“一台设备 regId 只绑定当前活跃账号”：注册时事务内先删除该 `reg_id` 的其他用户绑定，再插入/更新当前用户；登出时注销。`account_id` 由服务端使用受信任的 `INSTANCE_DOMAIN + "_" + user.sub` 生成，禁止接受客户端任意声明。JPush payload 必须包含：

```json
{"type":"message","account_id":"abdl-space.top_123","peer_id":"456","message_id":"789"}
```

- [ ] **Step 4: 测试并提交 backend**

测试未鉴权用户无法触发任意推送、源码/config 无 secret、regId 换号后旧账号不再收到通知。

---

### Task 2: D1 reliability schema

**Covers:** S5.3, S7

**Files:**

- Create: backend `migrations/00xx_messages_reliability.sql`（按现有编号顺延）
- Modify: backend `schemas/schema.sql`

- [ ] **Step 1: 增加消息幂等字段和唯一索引**

```sql
ALTER TABLE messages ADD COLUMN client_msg_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_client_msg
ON messages(sender_id, client_msg_id)
WHERE client_msg_id IS NOT NULL;
```

- [ ] **Step 2: 增加持久化事件和 outbox**

```sql
CREATE TABLE message_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  message_id INTEGER,
  peer_id INTEGER NOT NULL,
  read_up_to_id INTEGER,
  payload TEXT NOT NULL,
  created_at INTEGER NOT NULL DEFAULT (unixepoch())
);
CREATE INDEX idx_message_events_sync ON message_events(user_id, id);
CREATE UNIQUE INDEX idx_message_event_new
ON message_events(user_id, event_type, message_id)
WHERE event_type = 'message.new';
CREATE UNIQUE INDEX idx_message_event_read
ON message_events(user_id, event_type, peer_id, read_up_to_id)
WHERE event_type = 'message.read';

CREATE TABLE message_outbox (
  event_id INTEGER PRIMARY KEY,
  dispatched_at INTEGER,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at INTEGER NOT NULL DEFAULT (unixepoch()),
  FOREIGN KEY(event_id) REFERENCES message_events(id)
);
CREATE INDEX idx_message_outbox_pending
ON message_outbox(dispatched_at, next_attempt_at);
```

- [ ] **Step 3: 本地验证 migration，再执行 remote**

先备份/确认目标数据库，再执行 remote migration。执行后查询表、列和索引，不能只相信命令 exit code。

- [ ] **Step 4: 提交 backend**

---

### Task 3: 原子消息命令、历史分页和 read watermark

**Covers:** S5.1, S6.3, S7

**Files:**

- Modify: backend `src/routes/messages.ts`
- Create/Modify: backend `src/lib/message-events.ts`
- Test: backend messages route tests

- [ ] **Step 1: 使用 Workers runtime 测试环境**

加入 `@cloudflare/vitest-pool-workers`，修改测试脚本以发现所有相关测试；不得只在固定 Node 测试文件列表中新增一个不会执行的文件。

- [ ] **Step 2: 原子实现 `POST /api/messages`**

验证 receiver、trim 后非空 content、最大 2000 字符、不能发给自己，并完整执行 `allow_messages` / `allow_messages_from`。

在一个 D1 `batch()` 中使用可独立预编译、依靠唯一约束收敛的条件 SQL：

1. `INSERT INTO messages (...) VALUES (...) ON CONFLICT DO NOTHING`。使用无目标 conflict，避免 partial unique index 与 conflict target 不匹配。
2. 两条 `INSERT INTO message_events (...) SELECT ... FROM messages WHERE sender_id=? AND client_msg_id=? ON CONFLICT DO NOTHING`，分别写 sender/receiver 事件；事件唯一索引确保重复 batch 不生成第二个事件。
3. `INSERT INTO message_outbox(event_id) SELECT id FROM message_events WHERE ... ON CONFLICT DO NOTHING`。
4. batch 完成后查询确定的完整 message row 和双方 event IDs。

冲突命中后验证 receiver/content；不一致返回 409。重复或并发请求返回同一 message/event，不产生第二条消息或事件。测试必须使用真实 D1 runtime 并发执行，而不是只 mock SQL。

- [ ] **Step 3: 修正历史分页**

```sql
WHERE (...) AND m.id < ?
ORDER BY m.id DESC
LIMIT ?
```

响应前 reverse 为升序。会话列表最新消息排序使用 `created_at DESC, id DESC`。

- [ ] **Step 4: 实现带 watermark 的 read**

`POST /api/messages/:userId/read` body 为 `read_up_to_id`。只更新该 peer 发来的 `id <= read_up_to_id` 消息，并为双方写持久化 `message.read` event。

- [ ] **Step 5: 测试**

覆盖并发相同 `client_msg_id`、冲突 payload、权限、分页边界、同时间戳、read 与新发送并发、D1 batch 失败不留下半状态。

---

### Task 4: 持久化增量同步 API

**Covers:** S6.4, S7

**Files:**

- Modify: backend `src/routes/messages.ts`
- Test: backend sync tests

- [ ] **Step 1: 实现 `GET /api/messages/sync`**

参数：`after_event_id`、`through_event_id`、`limit`。只返回当前账号满足 `id > after AND id <= through` 的事件，按 `id ASC`。第一次请求未传 `through_event_id` 时，服务端查询当前用户 `MAX(id)` 作为固定同步边界，并在后续分页原样返回：

```json
{
  "events": [],
  "next_event_id": 1002,
  "sync_boundary": 1050,
  "has_more": false
}
```

- [ ] **Step 2: 定义保留与 bootstrap 行为**

首版保留全部 message events。新安装从 `after_event_id=0` 同步全部事件；消息正文由事件 payload 建立缓存，打开会话时再用 history 校正。不得先记录当前全局最大 ID 而跳过历史事件。

- [ ] **Step 3: 测试账号隔离、分页连续性和断线补偿**

---

### Task 5: UserPresence Durable Object、WS 和 outbox dispatcher

**Covers:** S4, S5.2, S7

**Files:**

- Create: backend `src/durable-objects/UserPresence.ts`
- Modify: backend `wrangler.jsonc`, Worker entry and generated Env types
- Modify/Create: backend outbox dispatcher
- Test: DO + dispatcher tests

- [ ] **Step 1: 配置 SQLite-backed DO**

```jsonc
"durable_objects": {
  "bindings": [{ "name": "USER_PRESENCE", "class_name": "UserPresence" }]
},
"migrations": [
  { "tag": "v1-user-presence", "new_sqlite_classes": ["UserPresence"] }
]
```

运行 `npx wrangler types`，不要手写漂移的 binding 类型。

- [ ] **Step 2: 实现 hibernation**

`UserPresence extends DurableObject<Env>`；构造器配置：

```ts
this.ctx.setWebSocketAutoResponse(
  new WebSocketRequestResponsePair('ping', 'pong')
)
```

升级时 `ctx.acceptWebSocket(server)`，并 `serializeAttachment({ accountId, deviceId, connectedAt })`。`accountId` 由 Worker 根据已验证用户和 `INSTANCE_DOMAIN` 生成后通过内部 header 转发，DO 不信任客户端 account header。广播使用 RPC 方法优先；若仓库兼容性要求 fetch，则 `/push` 只能由 binding 到达，不暴露公网路由。

连接 accept 后，DO 查询该用户当前 `MAX(message_events.id)` 并发送 `sync.ready(sync_boundary)`。accept 在查询之前：查询期间产生的事件会同时进入 boundary 和 WS 缓冲，之后产生的事件由 WS 送达，因此客户端按 ID 去重后无竞态窗口。

- [ ] **Step 3: WS 路由复用完整鉴权**

验证 GET + Upgrade header，复用 REST 的 JWT 和 OAuth access token 解析。鉴权失败在调用 DO 前返回。

- [ ] **Step 4: 实现 Queue-backed outbox 重试**

固定采用 Cloudflare Queue `MESSAGE_OUTBOX_QUEUE`，同一 Worker 同时配置 producer 和 consumer。HTTP 命令提交 D1 后使用 `c.executionCtx.waitUntil(env.MESSAGE_OUTBOX_QUEUE.send({ eventId }))` 即时入队；Queue consumer 查询 outbox/event，推送成功后设置 `dispatched_at`，失败抛出并由 Queue 指数退避重试。另配置每分钟 Cron，scheduled handler 扫描 `dispatched_at IS NULL AND next_attempt_at <= unixepoch()` 并重新入队，覆盖 D1 commit 后、Queue send 前 Worker 终止的窗口。

`api-worker.ts` 的默认导出必须同时实现 `fetch`、`queue`、`scheduled`，不能只转发 Hono fetch。`wrangler.jsonc` 明确配置 producer、consumer、dead-letter queue 与 Cron；运行 `wrangler types` 后使用生成的 binding 类型。

对 `message.new`：双方 WS 广播；receiver JPush。对 `message.read`：相关双方 WS 广播，不发 JPush。typing 不落库，直接 DO 推送。

- [ ] **Step 5: 测试并部署**

覆盖双设备、hibernation 后 attachment、auto-response、无效 OAuth/JWT、广播失败重试、部署/断线后 sync。部署前先备份当前状态；部署后验证真实 WS upgrade。

---

## Phase 1: Android reliable core

### Task 6: Account-scoped models and REST API

**Covers:** S6.1, S6.2, S7

**Files:** `.../chat/model/*`, `.../chat/api/*`

- [ ] 模型包含 `accountId`（存储/运行时字段）、`id`、`tempId`、`clientMsgId`、`eventId`、`readUpToId` 和 `SendState`。
- [ ] API 类实现 conversations、history(`before_id`)、sync(`after_event_id`)、send、read(`read_up_to_id`) 和 typing。
- [ ] API path prefix 返回 `/api`，沿用 friend-request 模式。
- [ ] JSON 数字边界和空 URL 字段按项目模型规则处理。
- [ ] 运行 Java compile；成功后按项目规则提交。

---

### Task 7: Account-scoped ChatStorage

**Covers:** S6.5

**Files:** `.../chat/ChatStorage.java` and tests

- [ ] 使用独立 `local_id INTEGER PRIMARY KEY AUTOINCREMENT`，不要以 `temp_id` 作为主键。
- [ ] `chat_conversations` 主键 `(account_id, peer_id)`。
- [ ] `chat_messages` 对非空/非零 server ID 约束 `(account_id, server_id)`，对 client ID 约束 `(account_id, client_msg_id)`。
- [ ] `chat_drafts` 主键 `(account_id, peer_id)`。
- [ ] `chat_sync_state` 主键 `account_id`，保存 `last_event_id`。
- [ ] `applyEventsAndAdvanceCursor()` 在一个 SQLite transaction 中顺序应用事件并推进 cursor；崩溃不能出现 cursor 超前。
- [ ] 测试两个本地账号共享相同 peer/server IDs 时不串消息、草稿或未读。
- [ ] trim 每会话最近 500 条时不得删除 SENDING/FAILED 消息。

---

### Task 8: ChatController and MessageSendHelper

**Covers:** S6.3, S6.4

**Files:** `.../chat/ChatController.java`, `MessageSendHelper.java`, events

- [ ] Controller 实例按 `accountId` 管理，退出账号时关闭并清理运行时引用。
- [ ] 启动/回前台执行固定边界 sync：首次响应取得 `sync_boundary`，后续分页始终携带该边界，事件按 `event_id` 顺序事务应用。
- [ ] event ID 小于等于本地 cursor 时忽略；全局 event ID 合法跳号不得触发 gap。socket 断开、App 回前台或 `sync.required` 才触发新一轮边界 sync。
- [ ] 乐观发送先持久化 UUID `clientMsgId` 和 SENDING 消息，再通知 UI。
- [ ] REST ack 或 sender WS echo 都按 `clientMsgId` 映射同一 local row；之后按 server ID 去重。
- [ ] 重试复用原 `clientMsgId`；进程重启后的 SENDING 恢复为可重试，不生成新命令 ID。
- [ ] `message.read` 只更新 `serverId <= readUpToId` 的出站消息。
- [ ] 测试 REST-first、WS-first、重复 WS、失败重试、进程恢复和 read race。

---

### Task 9: ChatRealtimeClient

**Covers:** S5.2, S6.4

**Files:** `.../chat/ChatRealtimeClient.java`

- [ ] 每个激活账号独立持有 URL、token、cursor 和 lifecycle；切号不得复用旧 socket callback。
- [ ] Header 使用当前 `AccountSession` access token，URL 从 API host 配置派生。
- [ ] 前台连接、后台断开依赖 JPush；指数退避 1s 到 30s并加 jitter。
- [ ] 每 25s 文本 `ping` 由 DO auto-response 处理。
- [ ] 连接建立后等待 `sync.ready(boundary)`，立刻缓冲 WS 持久化事件；REST sync 到 boundary 后按 event ID 合并缓冲并进入 LIVE。socket 断开后重新执行该流程。
- [ ] 401/403 停止无限重连并进入现有登录过期流程。

---

## Phase 2: Telegram-like UI

### Task 10: Conversations list UI

**Covers:** S6.6, S6.7

- [ ] `ConversationCell`: 72dp 行、54dp 头像、名称/时间、预览/未读角标、最后出站状态。
- [ ] `ConversationsFragment`: 本地秒开、网络刷新、搜索过滤、空态、事件局部更新。
- [ ] 自己资料页提供消息入口；他人资料页仅在 can-message 允许时显示发私信。
- [ ] 左滑仅提供标已读；没有服务端 hide 协议前不提供删除。
- [ ] 明暗主题和长文本布局验证；assembleDebug 成功后提交。

---

### Task 11: Bubble, input, ChatFragment and send transition

**Covers:** S6.3, S6.6

- [ ] `MessageBubbleView`: 左右气泡、first/middle/last/single 圆角、时间和状态，不使用负 letter-spacing。
- [ ] Adapter: 日期分隔、稳定 ID、状态局部刷新、列表反向/贴底行为稳定。
- [ ] Input: 1-5 行、400ms 草稿 debounce、3s typing throttle、WindowInsets 安全区。
- [ ] ChatFragment: 初始贴底、距底 80dp 内自动滚动，否则显示新消息按钮；onResume 发送当前最大入站 ID 作为 read watermark。
- [ ] 发送动画 overlay 从输入文字坐标飞入目标气泡；动画结束不改变列表稳定尺寸。
- [ ] 长按提供复制，失败消息提供重试；本地删除在服务端语义落地前不提供。
- [ ] 对照固定 Telegram Android commit/截图记录会话、键盘、发送中、失败、明暗主题差异。

---

### Task 12: JPush foreground suppression and account-targeted deep link

**Covers:** S5.1, S6.7

**Files:** `JPushReceiver.java`, `MainActivity.java`, session/logout integration

- [ ] 解析 `type/account_id/peer_id/message_id`，字段缺失或 account 不存在时安全降级到会话列表。
- [ ] 点击通知先选择 payload 对应 AccountSession，再 push `ConversationsFragment -> ChatFragment`。
- [ ] 当前账号前台且正在查看同一会话时，按 `message_id` 抑制/取消系统通知；不能影响其他账号或其他会话。
- [ ] 登录注册当前 regId，切号重新绑定，登出注销；验证服务端“一 regId 一活跃账号”约束。
- [ ] 冷启动、后台、前台、切号和重复 payload 真机验证。

---

## Phase 3: End-to-end acceptance

### Task 13: Verification, rollout and final commits

- [ ] 后端单测/Workers runtime 测试全部通过。
- [ ] 后端 typecheck、lint、`wrangler deploy --dry-run` 通过。
- [ ] 双账号并发发送相同/不同 `client_msg_id` 正确。
- [ ] REST ack + sender WS echo 只显示一条消息。
- [ ] 写 D1 后模拟 WS/JPush 失败，重连 sync 仍补齐。
- [ ] read 与新消息并发时，只把 watermark 以内消息标为双勾。
- [ ] 两个 Android 账号对相同 peer 不串缓存、草稿、未读或深链。
- [ ] 杀进程后草稿和失败/SENDING 消息可恢复。
- [ ] 部署导致 socket 断开后自动重连并补齐事件。
- [ ] 后台 JPush 点击进入正确账号和会话；前台无重复通知。
- [ ] 明暗主题、窄屏、键盘展开、长用户名和 2000 字正文无重叠。
- [ ] 使用项目规定命令 assembleDebug；每次成功构建后立即提交。
- [ ] 用户完成 release APK 真机测试前，不合并到 main、不打 tag。

---

## Commit boundaries

每个任务形成独立、可验证 commit。禁止使用 `git commit -am` 遗漏新文件；先检查 `git status` 和 staged diff。后端和 Android 是两个仓库，分别提交。涉及 remote migration、deploy、push 或删除前按项目规则备份。

建议提交主题：

1. `fix(jpush): secure internal notification delivery`
2. `feat(messages): add reliable event and outbox schema`
3. `feat(messages): atomic send and read watermarks`
4. `feat(messages): add cursor-based event sync`
5. `feat(messages): add hibernating realtime delivery`
6. `feat(chat): add account-scoped models and APIs`
7. `feat(chat): add account-scoped local storage`
8. `feat(chat): add reliable message state controller`
9. `feat(chat): add account-scoped realtime client`
10. `feat(chat): add conversations list`
11. `feat(chat): add Telegram-like chat UI`
12. `feat(chat): route message notifications by account`

## Spec coverage

| Spec | Tasks |
|---|---|
| S1-S3 context/reference | plan context, Tasks 10-11 behavior comparison |
| S4 architecture | Tasks 2-5, 7-9 |
| S5 backend | Tasks 1-5 |
| S6 Android | Tasks 6-12 |
| S7 contracts | Tasks 2-9, 12 |
| S8 phases | Phase 0-3 |
| S9 testing | Every task gate + Task 13 |
| S10 risks | Invariants + Tasks 1-9, 12 |
| S11 decisions | Cross-cutting invariants and phase gates |

## Definition of ready

只有以下条件满足后才能开始执行 Task 1：

- JPush secret 轮换窗口和后端部署权限可用。
- migration 编号、当前 schema 和 `allow_messages_from` 真实语义已从后端仓库核对。
- Queue、dead-letter queue、Cron 扫尾和本 Worker consumer 均可在目标 Cloudflare 账号创建，并在 `wrangler.jsonc` 中有可测试配置。
- Telegram UX 参考 commit 与验收截图目录已固定。

## Definition of done

计划完成不等于 UI 能发送一条消息。必须通过 Task 13 的弱网、并发、切号、进程恢复、部署断线、通知路由和明暗主题验收，且用户完成 release APK 真机测试，才可进入发布流程。
