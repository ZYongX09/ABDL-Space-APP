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
- 无 message 删除接口（第一版可先本地隐藏 + 后续补）
- 发送后无推送到对端

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
│ 发送成功后 ──► UserPresenceDO(receiver) + DO(sender) push   │
│ /api/ws  ──► upgrade ──► UserPresenceDO(userId)             │
└─────────────────────────────────────────────────────────────┘
```

原则：

1. **落库权威在 D1**（与 Telegram 的 server message id 类似）
2. **DO 只做在线连接与推送**，不做消息主存储
3. **Android 本地缓存**保证秒开与离线可读
4. **事件总线驱动 UI**，避免 Fragment 直接耦合网络

## [S5] Backend design

### [S5.1] REST（扩展现有 messages）

保持兼容移动端，并增加 Android 需要的字段：

1. `GET /api/messages/conversations`
   - 返回：`user_id, username, avatar, last_message, last_message_at, unread_count`
2. `GET /api/messages/:userId?before_id=&limit=`
   - 历史分页改为 **cursor（before_id）** 优先，兼容 page
3. `POST /api/messages`
   - body：`receiver_id, content, client_msg_id`
   - 返回：`id, client_msg_id, created_at, sender_id, receiver_id`
   - 幂等：同 `sender_id + client_msg_id` 不重复插入
4. `POST /api/messages/:userId/read`
   - 标记来自该用户的未读为已读
   - 成功后通过 DO 推 `message.read` 给对方
5. `POST /api/messages/typing`（新增）
   - body：`receiver_id`
   - 不落库，直接 DO 推 `typing`
6. `GET /api/users/:id/can-message`（若缺失则补齐）

发送后副作用：

- 写 D1
- 推 JPush（对方离线时）
- 推 WS：`message.new` 到双方 UserPresence DO

### [S5.2] Durable Object：UserPresence

- 一个用户一个 DO：`idFromName("user:" + userId)`
- 使用 **WebSocket Hibernation API**（`acceptWebSocket` / `webSocketMessage` / `serializeAttachment`）
- 连接鉴权：Worker 升级前校验 JWT，把 `userId` 作为 tag 附加
- 事件：
  - `message.new`
  - `message.read`
  - `typing`
  - `ping/pong`（客户端心跳；DO 可用 auto-response 降本）

### [S5.3] Schema 增量

```sql
ALTER TABLE messages ADD COLUMN client_msg_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_client_msg
  ON messages(sender_id, client_msg_id) WHERE client_msg_id IS NOT NULL;
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

`Conversation`：

- `peerId, username, avatar`
- `lastMessage, lastMessageAt`
- `unreadCount`
- `draft`
- `lastOutState`（用于列表勾）

### [S6.3] 发送状态机（对齐 SendMessagesHelper）

1. UI 点发送 → `MessageSendHelper.sendText`
2. 生成 `tempId/clientMsgId`，`sendState=SENDING`，写入 Storage + 通知 UI（乐观插入）
3. 触发 `TextSendEnterTransition`（输入文字飞到气泡位置）
4. REST `POST /api/messages`
5. 成功：映射 `tempId → serverId`，`sendState=SENT`，更新会话预览
6. 失败：`sendState=FAILED`，点击重试走同一 helper
7. 收到对方 `message.read`：对应消息/会话 `SENT → READ`（✓✓）

### [S6.4] 实时更新路径（对齐 processUpdate）

`ChatRealtimeClient` 收到 WS 事件 → `ChatController.applyUpdate`：

- `message.new`：若非自己 temp 回显则插入；更新会话列表顺序/预览/未读
- `message.read`：更新自己发出消息的勾
- `typing`：聊天顶栏短暂显示“对方正在输入…”

App 回前台/WS 重连：拉会话列表 + 当前会话增量历史，避免丢消息。

### [S6.5] 本地缓存（对齐 MessagesStorage 子集）

SQLite 表：

- `chat_conversations`
- `chat_messages`
- `chat_drafts`

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
- 左滑：标已读、删除会话（第一版删除=本地隐藏 + 可选服务端后续）
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
- 长按消息：复制、删除（失败消息额外“重试”）
- 顶栏：头像 + 名称；typing 时副标题切换

### [S6.7] 入口位置

- 个人主页：发私信按钮 → `ChatFragment(peerId)`
- “我的”页或通知相关入口：会话列表
- JPush payload 带 `type=message&peer_id=` → 打开对应聊天

## [S7] API contracts（客户端视角）

### WS 消息示例

```json
{"type":"message.new","message":{"id":123,"sender_id":1,"receiver_id":2,"content":"hi","created_at":"...","client_msg_id":"..."}}
{"type":"message.read","peer_id":2,"reader_id":2,"read_at":"..."}
{"type":"typing","from_user_id":2}
```

### REST 发送

```http
POST /api/messages
{"receiver_id":2,"content":"hi","client_msg_id":"uuid"}
```

```json
{"id":123,"client_msg_id":"uuid","created_at":"...","sender_id":1,"receiver_id":2}
```

## [S8] Phased delivery

### Phase 1 — 基础可聊（优先）

- 后端：`client_msg_id`、发送后 JPush、基础 REST 修正
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
2. DO 本地：`wrangler dev` 下双连接互推
3. Android 仪器/手工：
   - 双账号互发
   - 杀进程后草稿恢复
   - 断网发送失败 → 重试成功
   - 进入会话未读清零
   - 后台收 JPush 点进聊天
4. 体验对照清单：对照 Telegram 截图/真机，逐项勾 UI 差异

## [S10] Risks

| 风险 | 缓解 |
|---|---|
| “完全一致”期望无限膨胀 | 用 Non-goals + 分阶段；每期对照清单验收 |
| DO 成本/复杂度 | 仅用户级 presence；Hibernation；无连接不常驻 |
| 与现有 Fragment 栈冲突 | 继续 appkit Fragment 栈，不引入 TG BaseFragment |
| 消息乱序/重复 | `client_msg_id` 幂等 + 本地 temp 映射 |
| GPL 合规 | 不复制 TG 源文件，只参考行为与结构 |

## [S11] Decision log

- 不做 Telegram 整包复制，采用逻辑镜像重写
- 实时：Cloudflare Durable Objects WebSocket Hibernation
- 架构：REST 落库 + 每用户 DO 推送（方案 A）
- 第一版：1v1 文字 + 列表/气泡/状态/草稿/已读/实时/推送
- 明确延后：语音/贴纸/反应/群聊/E2EE
