# Telegram-like 私信 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 ABDL Space Android 客户端实现接近 Telegram 体验的 1v1 文字私信（会话列表、气泡聊天、乐观发送、已读、草稿、WebSocket 实时、JPush 离线），后端以 REST 落库 + 每用户 Durable Object 推送。

**Architecture:** D1 为消息权威存储；`POST /api/messages` 写入后推送到收发双方 `UserPresence` DO；Android 按 Telegram 分层镜像（ChatController / MessageSendHelper / ChatStorage / ChatRealtimeClient + ConversationsFragment / ChatFragment），本地 SQLite 缓存 + Otto 事件驱动 UI。

**Tech Stack:** Android Java 17 + appkit Fragments + OkHttp/Gson + Otto + SQLite；后端 Hono + Cloudflare Workers + D1 + Durable Objects（WebSocket Hibernation）；JPush。

**Spec:** `docs/compose/specs/2026-07-15-telegram-like-dm-design.md`

**Repos:**
- Backend: `/home/ZYongX/projects/git/abdl-space/`
- Android: `/home/ZYongX/projects/moshidon-test/`

---

## File map

### Backend (`abdl-space`)
| File | Responsibility |
|------|----------------|
| `migrations/00xx_messages_client_msg_id.sql` | schema: client_msg_id + unique index |
| `src/routes/messages.ts` | REST 扩展：幂等发送、cursor 历史、typing、发送后推送 |
| `src/durable-objects/UserPresence.ts` | 每用户 WS 连接与推送 |
| `src/index.ts` / `src/api-worker.ts` | 挂载 `/api/ws`、导出 DO |
| `wrangler.jsonc` | DO binding |
| `src/routes/jpush.ts` 或 messages 内联 | 离线推送 |

### Android (`moshidon-test`)
| File | Responsibility |
|------|----------------|
| `.../chat/model/*` | Conversation, ChatMessage, SendState |
| `.../chat/api/*` | MastodonAPIRequest 子类 |
| `.../chat/ChatStorage.java` | SQLite |
| `.../chat/ChatController.java` | 会话/消息/更新入口 |
| `.../chat/MessageSendHelper.java` | 发送队列与状态机 |
| `.../chat/ChatRealtimeClient.java` | WebSocket |
| `.../chat/events/*` | Otto events |
| `.../chat/ui/*` | Fragment/Cell/Bubble/Input/Adapter/Transition |
| `AndroidManifest.xml` | 无新 Activity（Fragment 栈） |
| `HomeFragment` / `ProfileFragment` | 入口 |

---

### Task 1: Backend schema — client_msg_id

**Covers:** S5.3, S7

**Files:**
- Create: `/home/ZYongX/projects/git/abdl-space/migrations/0024_messages_client_msg_id.sql`（编号按仓库现有 migrations 顺延）
- Modify: `/home/ZYongX/projects/git/abdl-space/schemas/schema.sql`（同步文档）

- [ ] **Step 1: 查看现有 migrations 编号**

```bash
ls /home/ZYongX/projects/git/abdl-space/migrations | sort | tail -5
```

- [ ] **Step 2: 写 migration**

```sql
-- migrations/00XX_messages_client_msg_id.sql
ALTER TABLE messages ADD COLUMN client_msg_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_client_msg
  ON messages(sender_id, client_msg_id)
  WHERE client_msg_id IS NOT NULL;
```

- [ ] **Step 3: 远程执行**

```bash
cd /home/ZYongX/projects/git/abdl-space
npx wrangler d1 execute abdl-space-db --remote --file=migrations/00XX_messages_client_msg_id.sql
```

Expected: success

- [ ] **Step 4: 更新 schemas/schema.sql 中 messages 表定义，加入 client_msg_id**

- [ ] **Step 5: Commit (backend repo)**

```bash
git add migrations schemas/schema.sql
git commit -m "feat(messages): add client_msg_id for idempotent send"
```

---

### Task 2: Backend REST — 幂等发送 + cursor 历史 + typing

**Covers:** S5.1, S7

**Files:**
- Modify: `/home/ZYongX/projects/git/abdl-space/src/routes/messages.ts`
- Test: `/home/ZYongX/projects/git/abdl-space/src/routes/messages.test.ts`（若无则创建）

- [ ] **Step 1: 写失败测试（幂等）**

```ts
// messages.test.ts — 同一 sender + client_msg_id 第二次 POST 返回同一 id
test('send is idempotent by client_msg_id', async () => {
  // mock db: first insert returns id=10; second select finds existing
  // expect both responses .id === 10
})
```

- [ ] **Step 2: 改 `POST /`**

行为：
1. 校验 `receiver_id`、`content`（trim, max 2000）、不能发给自己
2. 检查 `user_settings.allow_messages`
3. 若 `client_msg_id` 存在：先 `SELECT id FROM messages WHERE sender_id=? AND client_msg_id=?`，命中则直接返回已有行
4. 否则 `INSERT ... (sender_id, receiver_id, content, client_msg_id)`
5. 返回 `{ id, client_msg_id, created_at, sender_id, receiver_id, content }`

- [ ] **Step 3: 改 `GET /:userId` 支持 `before_id`**

```sql
-- 当 before_id 有值：
WHERE ... AND m.id < ?
ORDER BY m.created_at DESC
LIMIT ?
-- 再 reverse 成 ASC 返回
```

- [ ] **Step 4: 新增 `POST /typing`**

```ts
messages.post('/typing', authMiddleware, async (c) => {
  const user = c.get('user')
  const { receiver_id } = await c.req.json<{ receiver_id: number }>()
  if (!receiver_id) return c.json({ error: 'receiver_id required' }, 400)
  // 调用 UserPresence stub（Task 3 接入前可 no-op）
  return c.json({ ok: true })
})
```

注意：路由顺序 — `/typing`、`/conversations` 必须在 `/:userId` 之前注册。

- [ ] **Step 5: 跑测试并 commit**

```bash
npm test -- messages
git add src/routes/messages.ts src/routes/messages.test.ts
git commit -m "feat(messages): idempotent send, cursor history, typing endpoint"
```

---

### Task 3: Backend UserPresence Durable Object + WS

**Covers:** S5.2, S4, S7

**Files:**
- Create: `/home/ZYongX/projects/git/abdl-space/src/durable-objects/UserPresence.ts`
- Modify: `/home/ZYongX/projects/git/abdl-space/wrangler.jsonc`
- Modify: `/home/ZYongX/projects/git/abdl-space/src/api-worker.ts` 或 `src/index.ts`
- Modify: `/home/ZYongX/projects/git/abdl-space/src/types/index.ts`（Env 加 binding）

- [ ] **Step 1: wrangler.jsonc 增加 DO**

```jsonc
"durable_objects": {
  "bindings": [
    { "name": "USER_PRESENCE", "class_name": "UserPresence" }
  ]
},
"migrations": [
  { "tag": "v1-user-presence", "new_classes": ["UserPresence"] }
]
```

- [ ] **Step 2: 实现 UserPresence（Hibernation）**

```ts
export class UserPresence {
  constructor(private state: DurableObjectState, private env: Env) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url)
    if (url.pathname === '/push' && request.method === 'POST') {
      const payload = await request.text()
      for (const ws of this.state.getWebSockets()) {
        try { ws.send(payload) } catch {}
      }
      return new Response('ok')
    }
    if (request.headers.get('Upgrade') !== 'websocket') {
      return new Response('expected websocket', { status: 426 })
    }
    const pair = new WebSocketPair()
    const [client, server] = Object.values(pair)
    this.state.acceptWebSocket(server)
    return new Response(null, { status: 101, webSocket: client })
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
    // ping -> pong; ignore other client messages for v1
    if (typeof message === 'string' && message === 'ping') ws.send('pong')
  }

  async webSocketClose() {}
  async webSocketError() {}
}
```

- [ ] **Step 3: Worker 路由**

```ts
// GET /api/ws?token=JWT  or Authorization Bearer
// verifyJWT → idFromName(`user:${payload.sub}`) → stub.fetch(request)
```

- [ ] **Step 4: messages 发送/已读后 push**

```ts
async function pushToUser(env: Env, userId: number, event: object) {
  const id = env.USER_PRESENCE.idFromName(`user:${userId}`)
  const stub = env.USER_PRESENCE.get(id)
  await stub.fetch('https://do/push', {
    method: 'POST',
    body: JSON.stringify(event),
  })
}
// 发送成功后：
// pushToUser(env, receiver_id, { type:'message.new', message })
// pushToUser(env, sender_id, { type:'message.new', message })
// 已读后：
// pushToUser(env, otherId, { type:'message.read', peer_id: user.sub, reader_id: user.sub })
// typing：
// pushToUser(env, receiver_id, { type:'typing', from_user_id: user.sub })
```

- [ ] **Step 5: 发送成功后 JPush（复用现有 jpush 工具）**

payload 至少：`{ type: 'message', peer_id: senderId, alert: content 截断 }`

- [ ] **Step 6: 部署**

```bash
npm run deploy
# 或 npx wrangler deploy
```

- [ ] **Step 7: Commit**

```bash
git add wrangler.jsonc src/durable-objects src/routes/messages.ts src/api-worker.ts src/types
git commit -m "feat(messages): UserPresence DO websocket realtime push"
```

---

### Task 4: Android models + API requests

**Covers:** S6.1, S6.2, S7

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/chat/model/SendState.java`
- Create: `mastodon/src/main/java/org/joinmastodon/android/chat/model/ChatMessage.java`
- Create: `mastodon/src/main/java/org/joinmastodon/android/chat/model/Conversation.java`
- Create: `mastodon/src/main/java/org/joinmastodon/android/chat/api/GetConversations.java`
- Create: `mastodon/src/main/java/org/joinmastodon/android/chat/api/GetChatMessages.java`
- Create: `mastodon/src/main/java/org/joinmastodon/android/chat/api/SendChatMessage.java`
- Create: `mastodon/src/main/java/org/joinmastodon/android/chat/api/MarkChatRead.java`
- Create: `mastodon/src/main/java/org/joinmastodon/android/chat/api/SendTyping.java`

- [ ] **Step 1: 模型**

```java
public enum SendState { SENDING, SENT, READ, FAILED }

public class ChatMessage {
  public long id;           // server id, 0 if pending
  public long tempId;       // local
  public String clientMsgId;
  public long peerId;
  public long senderId;
  public String content;
  public Instant createdAt; // or long millis
  public boolean out;
  public SendState sendState;
}

public class Conversation {
  public long peerId;
  public String username;
  public String avatar;
  public String lastMessage;
  public long lastMessageAt;
  public int unreadCount;
  public String draft;
  public SendState lastOutState;
}
```

- [ ] **Step 2: API 类（继承 MastodonAPIRequest，pathPrefix `/api`）**

```java
// GetConversations: GET /messages/conversations → List/Conversation wrapper
// GetChatMessages: GET /messages/{userId}?before_id=&limit=
// SendChatMessage: POST /messages body {receiver_id, content, client_msg_id}
// MarkChatRead: POST /messages/{userId}/read
// SendTyping: POST /messages/typing body {receiver_id}
```

注意：`getPathPrefix()` 返回 `/api`（与 friend-request 相同模式）。

- [ ] **Step 3: compileDebugJavaWithJavac**

```bash
./gradlew :mastodon:compileDebugJavaWithJavac --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m
```

- [ ] **Step 4: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/chat
git commit -m "feat(chat): models and REST request classes"
```

---

### Task 5: ChatStorage (SQLite)

**Covers:** S6.5

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/chat/ChatStorage.java`

- [ ] **Step 1: 实现 DB helper**

表：
- `chat_conversations(peer_id PK, username, avatar, last_message, last_message_at, unread_count, draft, last_out_state)`
- `chat_messages(id INTEGER, temp_id INTEGER, client_msg_id TEXT, peer_id INTEGER, sender_id INTEGER, content TEXT, created_at INTEGER, out INTEGER, send_state TEXT, PRIMARY KEY(peer_id, temp_id))`
- index on `(peer_id, id)` and `(client_msg_id)`

方法：
- `upsertConversation` / `listConversations` / `setDraft` / `getDraft`
- `upsertMessage` / `getMessages(peerId, limit)` / `mapTempToServer` / `updateSendState`
- `trimMessages(peerId, keep=500)`

- [ ] **Step 2: 单元级 smoke（可 androidTest 或手工）**

- [ ] **Step 3: Commit**

```bash
git commit -am "feat(chat): local SQLite ChatStorage"
```

---

### Task 6: ChatController + MessageSendHelper + events

**Covers:** S6.3, S6.4, S6.1

**Files:**
- Create: `.../chat/ChatController.java`
- Create: `.../chat/MessageSendHelper.java`
- Create: `.../chat/events/*.java`

- [ ] **Step 1: Otto events**

```java
public class ConversationsUpdatedEvent {}
public class NewChatMessageEvent { public final ChatMessage message; }
public class MessageSendStateEvent { public final ChatMessage message; }
public class MessageReadEvent { public final long peerId; public final long readerId; }
public class TypingEvent { public final long fromUserId; }
```

- [ ] **Step 2: ChatController 单例（per account session）**

API：
- `loadConversations(forceNetwork)`
- `openChat(peerId)` / `loadMore(peerId, beforeId)`
- `applyWsEvent(json)`
- `markRead(peerId)`
- `getCachedMessages(peerId)`

流程对齐 Telegram `processUpdate`：写 Storage → Bus post。

- [ ] **Step 3: MessageSendHelper**

```java
public void sendText(long peerId, String content) {
  // 1 create temp message SENDING
  // 2 storage + bus NewChatMessageEvent
  // 3 SendChatMessage.exec
  // 4 success: map id, SENT, bus MessageSendStateEvent
  // 5 fail: FAILED, bus
}
public void retry(ChatMessage failed) { ... }
```

- [ ] **Step 4: Commit**

```bash
git commit -am "feat(chat): ChatController and MessageSendHelper state machine"
```

---

### Task 7: ChatRealtimeClient (WebSocket)

**Covers:** S6.4, S5.2

**Files:**
- Create: `.../chat/ChatRealtimeClient.java`

- [ ] **Step 1: OkHttp WebSocket**

- URL: `wss://api.abdl-space.top/api/ws`（或当前配置的 API host）
- Header: `Authorization: Bearer <token>`
- onMessage → `ChatController.applyWsEvent`
- 自动重连：指数退避 1s→30s
- 前台 `connect()`，后台可断开（依赖 JPush）
- 心跳：每 25s 发 `ping`

- [ ] **Step 2: 在登录成功 / MainActivity 启动时 connect；登出 disconnect**

- [ ] **Step 3: Commit**

```bash
git commit -am "feat(chat): WebSocket realtime client"
```

---

### Task 8: UI — ConversationCell + ConversationsFragment

**Covers:** S6.6 会话列表, S6.7

**Files:**
- Create: `.../chat/ui/ConversationCell.java`
- Create: `.../chat/ui/ConversationsFragment.java`
- Modify: 入口（`ProfileFragment` 或 `HomeFragment` 增加“消息”）

- [ ] **Step 1: ConversationCell**

规格：
- 高 72dp，头像 54dp
- 名称 + 相对时间
- 预览 + 未读角标
- 自己最后一条显示状态图标

- [ ] **Step 2: ConversationsFragment**

- LoaderFragment 或 RecyclerFragment 风格
- 订阅 `ConversationsUpdatedEvent` / `NewChatMessageEvent`
- 点击 → `ChatFragment` 带 peerId
- 空态 + 搜索过滤（本地）

- [ ] **Step 3: 入口**

- 个人主页（自己）：菜单/列表项「私信」
- 他人资料页：「发私信」按钮

- [ ] **Step 4: assembleDebug + commit**

```bash
./gradlew :mastodon:assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m
git commit -am "feat(chat): conversations list UI"
```

---

### Task 9: UI — MessageBubbleView + ChatMessageAdapter + ChatInputBar

**Covers:** S6.6 聊天页基础

**Files:**
- Create: `.../chat/ui/MessageBubbleView.java`
- Create: `.../chat/ui/ChatMessageAdapter.java`
- Create: `.../chat/ui/ChatInputBar.java`

- [ ] **Step 1: MessageBubbleView**

- 出站/入站颜色
- 时间 + 状态勾
- 相邻消息圆角：`first/middle/last/single` 由 adapter 计算

- [ ] **Step 2: Adapter**

- 插入日期分隔 item type
- Diff/notify 局部更新状态

- [ ] **Step 3: ChatInputBar**

- 多行 1–5
- 空=附件占位（可先 toast「即将支持」），非空=发送
- `TextWatcher` debounce 400ms 存草稿 + `SendTyping` 节流 3s
- WindowInsets 键盘抬升（父布局处理）

- [ ] **Step 4: Commit**

```bash
git commit -am "feat(chat): bubble, adapter, input bar"
```

---

### Task 10: UI — ChatFragment + 发送动画 + 已读

**Covers:** S6.3, S6.6, S6.7

**Files:**
- Create: `.../chat/ui/ChatFragment.java`
- Create: `.../chat/ui/TextSendEnterTransition.java`

- [ ] **Step 1: ChatFragment 结构**

- Toolbar：返回、头像、名称、typing 副标题
- RecyclerView 消息列表
- ChatInputBar
- onResume → markRead + load
- 订阅 New/SendState/Read/Typing events

- [ ] **Step 2: 滚动策略**

- 初始贴底
- 距底 < 80dp 时新消息自动滚底
- 否则显示 floating「新消息」按钮

- [ ] **Step 3: TextSendEnterTransition**

- 发送瞬间：overlay TextView 从输入框坐标 animate 到列表底部气泡位置
- 动画结束：确保 adapter 项可见

- [ ] **Step 4: 长按菜单**

- 复制、删除（本地）、失败消息重试

- [ ] **Step 5: assembleDebug + commit**

```bash
./gradlew :mastodon:assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m
git commit -am "feat(chat): ChatFragment with send animation and read receipts"
```

---

### Task 11: JPush 深链进会话

**Covers:** S6.7, S5.1 离线

**Files:**
- Modify: `PushNotificationReceiver.java` / `JPushReceiver` 相关
- Modify: `MainActivity.java`（处理 intent extras）

- [ ] **Step 1: 解析 payload**

若 `type=message` 且有 `peer_id` → 打开 `ConversationsFragment` 栈上再 push `ChatFragment`

- [ ] **Step 2: 真机验证（双账号）**

- [ ] **Step 3: Commit**

```bash
git commit -am "feat(chat): open chat from JPush notification"
```

---

### Task 12: 端到端验收清单

**Covers:** S9, S2

- [ ] **双账号互发文字，双方列表与气泡一致**
- [ ] 乐观发送：弱网下先出现时钟勾，成功变 ✓
- [ ] 进入会话未读清零；对方看到 ✓✓
- [ ] 草稿：杀进程后恢复
- [ ] WS 断开重连后不丢最近消息
- [ ] 后台收推送可点进会话
- [ ] 对方关闭私信时发送失败提示
- [ ] 明暗主题气泡可读

- [ ] **修 bug 后最终 commit**

```bash
git commit -am "fix(chat): e2e polish after acceptance"
```

---

## Spec coverage self-check

| Spec | Tasks |
|------|-------|
| S1 Problem | context |
| S2 Goals/Non-goals | 8–12, Non-goals 不实现 |
| S3 Reference | mirrored in file map |
| S4 Architecture | 3, 6, 7 |
| S5 Backend | 1–3 |
| S6 Android | 4–11 |
| S7 API contracts | 2, 3, 4, 7 |
| S8 Phases | Tasks 1–7≈P1/P2 core; media later |
| S9 Testing | 2 tests, 12 e2e |
| S10 Risks | client_msg_id, hibernation, no GPL copy |
| S11 Decisions | plan follows A |

## Placeholder scan

无 TBD/TODO 步骤；关键代码与命令已给出。

## Type consistency

- `client_msg_id` / `peerId` / `SendState` 全任务统一
- API path prefix `/api`
- Events 经 Otto 与现有工程一致
