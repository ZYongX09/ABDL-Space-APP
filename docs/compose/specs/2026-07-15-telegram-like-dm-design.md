# Telegram-like 私信（逻辑镜像）设计

## [S1] Problem

ABDL Space Android 客户端需要 1v1 私信，用户体验目标是**尽可能与 Telegram 一致**（会话列表、气泡聊天、发送状态、入场动画、草稿、已读回执、实时到达），但：

- 后端不是 MTProto，而是 Cloudflare Workers + D1 + 现有 REST 私信 API
- 不能整包复制 Telegram 源码（MTProto/native 绑定、体量 10 万+ 行、与 Moshodon 架构冲突、GPL 许可约束）
- 现有移动端（`abdl-space-mobile`）已有基础私信 UI/API 消费方式，可复用接口语义

因此采用 **Telegram 逻辑镜像重写**：分层、状态机、交互、动画对齐 Telegram；网络与存储换成 ABDL 自定义实现。

## [S2] Goals / Non-goals

### Goals（第一版必须）

1. 会话列表：头像、名称、时间、预览、未读角标、自己最后一条状态勾
2. 聊天页：左右气泡、相邻圆角合并、时间与发送状态、反向列表贴底
3. 发送：乐观发送 + tempId → serverId 映射 + 失败重试
4. 已读：进入会话标已读；对方已读后 ✓→✓✓
5. 草稿：按会话本地保存/恢复
6. 实时：Cloudflare Durable Object（每用户）+ WebSocket Hibernation
7. 离线：JPush 通知点击进入对应会话
8. UI/动画：对标 Telegram 的关键交互（发送文字飞入、列表插入、键盘适配、右滑返回）
9. 多账号隔离：缓存、草稿、WebSocket、推送和深链全部绑定明确的 `account_id`
10. 可靠恢复：WebSocket 仅负责低延迟提示；断线后必须通过持久化事件游标补齐
11. 安全：JPush 凭据只存在于 Wrangler Secret，推送发送能力不得暴露为公开接口

### Non-goals（第一版明确不做）

- 语音录制波纹、视频消息、贴纸/GIF、反应、话题、频道、群聊
- E2EE / 秘密聊天 / MTProto / 代理
- 归档文件夹、Stories、通话
- 完整移植 `ChatActivity`/`ChatMessageCell` 源文件

## [S3] Reference systems

### Telegram（`~/projects/Telegram`）

| Telegram 组件 | 角色 | 本项目镜像 |
|---|---|---|
| `DialogsActivity` + `DialogCell` | 会话列表与行渲染 | `ConversationsFragment` + `ConversationCell` |
| `ChatActivity` + `ChatActivityAdapter` | 聊天页与消息列表 | `ChatFragment` + `ChatMessageAdapter` |
| `ChatActivityEnterView` | 输入栏/发送按钮/附件切换 | `ChatInputBar` |
| `ChatMessageCell` | 气泡绘制、时间、勾 | `MessageBubbleView` |
| `TextMessageEnterTransition` | 发送文字入场动画 | `TextSendEnterTransition` |
| `MessagesController` | 会话/消息/已读/更新入口 | `ChatController` |
| `SendMessagesHelper` | 发送队列与状态 | `MessageSendHelper` |
| `MessagesStorage` | 本地 SQLite 缓存 | `ChatStorage` |
| `NotificationCenter` | 事件分发 | Otto 事件（现有） |
| `ConnectionsManager` + TLRPC | 网络协议 | REST + WebSocket（ABDL） |

### 移动端（`~/projects/abdl-space-mobile`）

- `MessagesPage.jsx`：会话列表 + 聊天视图 + 新会话
- `messagesAPI`：`/api/messages/conversations`、`/api/messages/:userId`、`POST /api/messages`、`POST /api/messages/:userId/read`

### 后端（`~/projects/git/abdl-space`）

现有：

- 表 `messages(sender_id, receiver_id, content, read, created_at)`
- 表 `user_settings(allow_messages, allow_messages_from)`
- REST：会话列表 / 历史 / 发送 / 已读

缺口：

- 无 WebSocket / 无 Durable Objects
- 无 typing
- 无 client_msg_id（乐观发送去重）
- 无 message/conversation 删除或隐藏接口（第一版不展示删除入口，后续补服务端协议）
- 发送后无推送到对端
- 无全局用户事件游标，无法可靠恢复断线期间的消息和已读状态
- 已读只有布尔值，没有 `read_up_to_id` watermark
- JPush 发送接口当前无鉴权且凭据已进入源码，实施前必须轮换并移除

## [S4] Architecture overview

```
┌──────────────── Android App (moshidon-test) ────────────────┐
│ ConversationsFragment ──► ChatFragment                      │
│        │                      │                             │
│        ▼                      ▼                             │
│  ChatController ◄──── MessageSendHelper                     │
│        │                      │                             │
│        ▼                      ▼                             │
│  ChatStorage(SQLite)     MessagesApi (OkHttp/Gson)          │
│        │                      │                             │
│        └──────── ChatRealtimeClient (WS) ───────┐           │
└─────────────────────────────────────────────────┼───────────┘
                                                  │
                     REST                         │ WS
                      │                           ▼
┌──────── Cloudflare Worker (api.abdl-space.top) ─────────────┐
│ /api/messages/*  ──► D1 messages                            │
│ D1 message + outbox ──► dispatch ──► UserPresenceDO/JPush  │
│ /api/ws  ──► upgrade ──► UserPresenceDO(userId)             │
└─────────────────────────────────────────────────────────────┘
```

原则：

1. **落库权威在 D1**（与 Telegram 的 server message id 类似）
2. **DO 只做在线连接与推送**，不做消息主存储
3. **Android 本地缓存**保证秒开与离线可读
4. **事件总线驱动 UI**，避免 Fragment 直接耦合网络
5. **可靠性不依赖 WS/JPush**：持久化事件使用全局单调递增 `event_id`；对单个用户跳号是正常现象，重连以服务端同步边界补齐，绝不使用 `last+1` 判断缺口
6. **全链路去重**：命令用 `client_msg_id` 幂等；更新用 `event_id` 去重；消息用 `message_id` 合并
7. **多账号显式隔离**：Android 的任何聊天状态都以 `AccountSession.getID()` 为第一维

## [S5] Backend design

### [S5.1] REST（扩展现有 messages）

保持兼容移动端，并增加 Android 需要的字段：

1. `GET /api/messages/conversations`
   - 返回：`user_id, username, avatar, last_message, last_message_at, unread_count`
2. `GET /api/messages/:userId?before_id=&limit=`
   - 历史分页按 `id DESC` 使用 **cursor（before_id）**，避免过滤键与排序键不一致
3. `GET /api/messages/sync?after_event_id=&limit=`（新增）
   - 返回当前用户在游标之后的持久化事件，以及 `next_event_id`、`sync_boundary`、`has_more`
   - 事件包括 `message.new`、`message.read`、`conversation.hidden`
4. `POST /api/messages`
   - body：`receiver_id, content, client_msg_id`
   - 返回：完整消息和对应的 `event_id`
   - 以数据库唯一约束 + `INSERT ... ON CONFLICT DO NOTHING` 原子实现幂等；冲突后读取已有行
   - 重用 `client_msg_id` 时必须验证 receiver/content 一致，否则返回 409
5. `POST /api/messages/:userId/read`
   - body：`read_up_to_id`
   - 只标记来自该用户且 `id <= read_up_to_id` 的消息
   - 返回并广播持久化 `message.read` 事件，包含 `read_up_to_id`
6. `POST /api/messages/typing`（新增）
   - body：`receiver_id`
   - 不落库，直接 DO 推 `typing`
7. `GET /api/users/:id/can-message`（若缺失则补齐）
   - 同时执行 `allow_messages` 与 `allow_messages_from` 规则

发送后副作用：

- 单个 D1 `batch()` 条件写入 message、双方 `message_events` 和 outbox；`message.new` 事件以 `(user_id, event_type, message_id)` 唯一约束去重，并发重复命令不得生成重复事件
- Worker 在提交后把 outbox ID 投递到 Cloudflare Queue；Queue consumer 负责 WS/JPush、指数退避和完成标记
- Cron 每分钟扫描未完成 outbox 并重新投递，覆盖 Worker 在 D1 提交后、Queue send 前终止的窗口
- JPush 可始终发送以保证离线可达；前台客户端按 `message_id/account_id` 抑制重复通知

### [S5.2] Durable Object：UserPresence

- 一个用户一个 DO：`idFromName("user:" + userId)`
- 使用 **WebSocket Hibernation API**（`acceptWebSocket` / `webSocketMessage` / `serializeAttachment`）
- 新 namespace 使用 Wrangler `new_sqlite_classes`
- 连接鉴权：Worker 升级前复用 REST 的完整 token 解析（JWT + OAuth access token），无效请求不进入 DO
- 每条连接通过 `serializeAttachment` 保存 `accountId/deviceId/connectedAt`，DO 唤醒后可恢复
- 使用 `setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"))`，避免心跳唤醒 DO
- 事件：
  - `message.new`
  - `message.read`
  - `typing`
  - `sync.required`（部署断线、事件缺口时要求客户端 REST 同步）

### [S5.3] Schema 增量

```sql
ALTER TABLE messages ADD COLUMN client_msg_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_client_msg
  ON messages(sender_id, client_msg_id) WHERE client_msg_id IS NOT NULL;

CREATE TABLE message_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  message_id INTEGER,
  peer_id INTEGER NOT NULL,
  read_up_to_id INTEGER,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_message_events_sync ON message_events(user_id, id);
CREATE UNIQUE INDEX idx_message_event_new
  ON message_events(user_id, event_type, message_id)
  WHERE event_type = 'message.new';

CREATE TABLE message_outbox (
  event_id INTEGER PRIMARY KEY,
  dispatched_at DATETIME,
  attempts INTEGER NOT NULL DEFAULT 0
);
```

可选（后续）：

- `deleted_by_sender` / `deleted_by_receiver`
- `message_type` / `media_url`（图片阶段）

## [S6] Android design（Telegram 逻辑镜像）

### [S6.1] 包结构

```
org.joinmastodon.android.chat/
  ChatController.java
  MessageSendHelper.java
  ChatStorage.java
  ChatRealtimeClient.java
  model/
    Conversation.java
    ChatMessage.java
    SendState.java
  api/
    GetConversations.java
    GetChatMessages.java
    SendChatMessage.java
    MarkChatRead.java
    SendTyping.java
  ui/
    ConversationsFragment.java
    ChatFragment.java
    ConversationCell.java
    MessageBubbleView.java
    ChatInputBar.java
    TextSendEnterTransition.java
    ChatMessageAdapter.java
  events/
    ConversationsUpdatedEvent.java
    NewChatMessageEvent.java
    MessageSendStateEvent.java
    MessageReadEvent.java
    TypingEvent.java
```

### [S6.2] 数据模型

`ChatMessage`：

- `id`（server long，未确认前为 0）
- `tempId`（本地 UUID/负 long）
- `peerId`（对方 userId）
- `senderId`
- `content`
- `createdAt`
- `out`（是否自己发送）
- `sendState`: `SENDING | SENT | READ | FAILED`
- `clientMsgId`
- `eventId`（事件记录 ID，仅去重/诊断）

`Conversation`：

- `peerId, username, avatar`
- `lastMessage, lastMessageAt`
- `unreadCount`
- `draft`
- `lastOutState`（用于列表勾）
- `readOutboxMaxId`（对方已读到的最大消息 ID）
- 所有模型在 Storage/Controller 中必须绑定 `accountId`

### [S6.3] 发送状态机（对齐 SendMessagesHelper）

1. UI 点发送 → `MessageSendHelper.sendText`
2. 生成 `tempId/clientMsgId`，`sendState=SENDING`，写入 Storage + 通知 UI（乐观插入）
3. 触发 `TextSendEnterTransition`（输入文字飞到气泡位置）
4. REST `POST /api/messages`
5. 成功：按 `clientMsgId` 映射 `tempId → serverId/eventId`，`sendState=SENT`，更新会话预览
6. 失败：`sendState=FAILED`，点击重试走同一 helper
7. 收到自己 WS 回显：以 `clientMsgId` 合并，不新建第二条消息
8. 收到对方 `message.read(read_up_to_id)`：仅 `id <= watermark` 的消息 `SENT → READ`（✓✓）

### [S6.4] 实时更新路径（对齐 processUpdate）

`ChatRealtimeClient` 收到 WS 事件 → `ChatController.applyUpdate`：

- `message.new`：若非自己 temp 回显则插入；更新会话列表顺序/预览/未读
- `message.read`：按 `read_up_to_id` 更新自己发出消息的勾
- `typing`：聊天顶栏短暂显示“对方正在输入…”

每个账号持久化 `last_event_id`。WS 建连后服务端先发送 `sync.ready(sync_boundary)`，其中 boundary 是该用户建连时可见的最大事件 ID；客户端进入 SYNCING，缓冲其后到达的 WS 持久化事件，循环调用 `/api/messages/sync?after_event_id=&through_event_id=sync_boundary`，按 `event_id` 应用到边界，再合并缓冲事件并进入 LIVE。由于其他用户事件会形成合法跳号，客户端只要求事件 ID 单调增加并去重，不用 `last+1` 判断缺口；socket 断开、App 回前台或服务端 `sync.required` 时重新执行边界同步。typing 无 event_id，可直接丢弃或短暂显示。

### [S6.5] 本地缓存（对齐 MessagesStorage 子集）

SQLite 表：

- `chat_conversations`：主键 `(account_id, peer_id)`
- `chat_messages`：独立 `local_id` 主键；唯一 `(account_id, server_id)`（server_id 非空）和 `(account_id, client_msg_id)`（client_msg_id 非空）
- `chat_drafts`：主键 `(account_id, peer_id)`
- `chat_sync_state`：主键 `account_id`，保存 `last_event_id`

策略：

- 打开会话：先读本地，再网络刷新
- 发送/接收：先写本地，再通知 UI
- 限制每会话缓存最近 N 条（如 500），更早从网络分页

### [S6.6] UI/动画规格（体验对齐 Telegram）

#### 会话列表

- 行高约 72dp；头像 54dp 圆
- 第一行：名称（左）+ 相对时间（右）
- 第二行：预览（左，单行省略）+ 未读角标（右）
- 自己最后一条：预览前缀状态图标（时钟/✓/✓✓/！）
- 未读角标：圆角胶囊，品牌色
- 点击进入 `ChatFragment`
- 左滑：标已读；删除会话在服务端隐藏协议落地前不提供，避免刷新后复现
- 顶部：搜索（可先过滤本地会话）

#### 聊天页

- 反向 `LinearLayoutManager` 或正序 + 初始滚底
- 气泡：
  - 出站：品牌蓝底、深色字
  - 入站：surface 灰底
  - 时间 11–12sp 右下；状态勾与时间同一行
  - 同侧连续消息：仅末条显示尾巴，圆角按 first/middle/last 变化
- 日期分隔：跨天插入 date chip
- 新消息：
  - 在底部附近自动滚底
  - 上滑离开底部时显示“新消息”按钮，不强制打断阅读
- 输入栏：
  - 多行自动增高（1–5 行）
  - 空内容显示附件按钮，有内容切换发送按钮
  - 键盘 `WindowInsets` 抬升
  - 草稿 debounce 保存
- 发送动画：
  - 复制输入文字到 overlay
  - 动画位移/缩放至目标气泡区域
  - 结束后真正绑定气泡 view
- 长按消息：复制（失败消息额外“重试”）；第一版无服务端删除协议，不提供删除
- 顶栏：头像 + 名称；typing 时副标题切换

### [S6.7] 入口位置

- 个人主页：发私信按钮 → `ChatFragment(peerId)`
- “我的”页或通知相关入口：会话列表
- JPush payload 必须带 `type=message&account_id=&peer_id=&message_id=`；点击后先选择对应 AccountSession 再打开聊天
- `account_id` 必须由服务端按受信任的实例域名和已认证 `user_id` 生成（格式与 Android `AccountSession.getID()` 一致），禁止客户端任意声明；设备注册按 `(user_id, reg_id)` 管理，账号登出时注销该绑定

## [S7] API contracts（客户端视角）

### WS 消息示例

```json
{"event_id":1001,"type":"message.new","message":{"id":123,"sender_id":1,"receiver_id":2,"content":"hi","created_at":"...","client_msg_id":"..."}}
{"event_id":1002,"type":"message.read","peer_id":2,"reader_id":2,"read_up_to_id":123,"read_at":"..."}
{"type":"typing","from_user_id":2}
```

### REST 发送

```http
POST /api/messages
{"receiver_id":2,"content":"hi","client_msg_id":"uuid"}
```

```json
{"event_id":1001,"message":{"id":123,"client_msg_id":"uuid","created_at":"...","sender_id":1,"receiver_id":2,"content":"hi"}}
```

## [S8] Phased delivery

### Phase 0 — 安全与可靠协议（阻断项）

- 轮换已暴露的 JPush Master Secret，迁移到 Wrangler Secret
- 移除公开无鉴权推送接口
- 落地 account scope、event cursor/sync boundary、read watermark、Queue-backed outbox 和幂等协议

### Phase 1 — 基础可聊（优先）

- 后端：`client_msg_id`、message events/outbox、sync、read watermark、发送后 JPush、基础 REST 修正
- Android：会话列表 + 聊天页 + 乐观发送 + 本地缓存 + 已读
- 无 WS 时可用前台短轮询兜底

### Phase 2 — 实时与 Telegram 手感

- UserPresence DO + WS Hibernation
- typing
- 发送入场动画、气泡合并圆角、失败重试完善

### Phase 3 — 媒体与增强

- 图片消息（复用现有 MediaPicker/Camera）
- 回复/转发
- 会话删除服务端同步

## [S9] Testing strategy

1. 后端单测：发送幂等、已读计数、权限（关闭私信）
2. Cloudflare Vitest pool：真实 D1 + DO，覆盖并发幂等、hibernation attachment、双连接互推
3. Android 仪器/手工：
   - 双账号互发
   - 杀进程后草稿恢复
   - 断网发送失败 → 重试成功
   - 进入会话未读清零
    - 后台收 JPush 点进聊天
    - 两个本地账号相同 peer id 不串缓存/草稿/推送
    - REST 成功 + WS 回显只保留一条消息
    - socket 断线/`sync.required` 后通过边界 sync 完整补齐
4. 体验对照清单：对照 Telegram 截图/真机，逐项勾 UI 差异

## [S10] Risks

| 风险 | 缓解 |
|---|---|
| “完全一致”期望无限膨胀 | 用 Non-goals + 分阶段；每期对照清单验收 |
| DO 成本/复杂度 | 仅用户级 presence；Hibernation；无连接不常驻 |
| 与现有 Fragment 栈冲突 | 继续 appkit Fragment 栈，不引入 TG BaseFragment |
| 消息乱序/重复 | `client_msg_id` 幂等 + 本地 temp 映射 |
| REST/WS/JPush 三路重复 | `client_msg_id → message_id → event_id` 分层去重 |
| 多账号串数据 | 所有本地表、Controller、WS、推送深链显式携带 `account_id` |
| 写消息成功但推送失败 | D1 message_events/outbox + REST sync，推送不是可靠性来源 |
| 凭据泄露/公开推送滥用 | 轮换 JPush secret；仅 Wrangler Secret；移除公开 send 接口 |
| GPL 合规 | 不复制 TG 源文件，只参考行为与结构 |

## [S11] Decision log

- 不做 Telegram 整包复制，采用逻辑镜像重写
- 实时：Cloudflare Durable Objects WebSocket Hibernation
- 架构：REST 落库 + 每用户 DO 推送（方案 A）
- 可靠性：D1 持久化事件游标 + outbox；WS/JPush 只负责低延迟与离线提醒
- 多账号：所有客户端聊天状态以 `AccountSession.getID()` 隔离
- 第一版：1v1 文字 + 列表/气泡/状态/草稿/已读/实时/推送
- 明确延后：语音/贴纸/反应/群聊/E2EE
