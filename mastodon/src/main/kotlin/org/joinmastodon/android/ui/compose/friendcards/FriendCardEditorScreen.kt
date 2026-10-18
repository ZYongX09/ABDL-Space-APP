@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.joinmastodon.android.ui.compose.friendcards

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.api.requests.friendcards.FriendCardInput
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses
import org.joinmastodon.android.friendcards.FriendCardDraft
import org.joinmastodon.android.friendcards.FriendCardRules
import org.joinmastodon.android.model.FriendCard
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun FriendCardEditorScreen(
    draft: FriendCardDraft,
    options: FriendCardResponses.Options?,
    checking: Boolean,
    eligibilityError: String?,
    error: String?,
    preview: Boolean,
    busy: Boolean,
    coverBusy: Boolean,
    onRetry: () -> Unit,
    onVerification: () -> Unit,
    onStage: (Int) -> Unit,
    onEdit: ((FriendCardInput) -> Unit) -> Unit,
    onPreview: () -> Unit,
    onBackToEdit: () -> Unit,
    onPublish: () -> Unit,
    onUnlock: () -> Unit,
    onSave: () -> Unit,
    onCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onInterest: (String) -> String?,
    onToggleInterest: (String) -> Unit,
    onGame: (String) -> String?,
    onToggleGame: (String) -> Unit,
    onDiapers: () -> Unit,
    onDiaperOpen: (FriendCard.Diaper) -> Unit,
    onDiaperRemove: (Int) -> Unit,
) {
    val input = draft.input
    val pending = draft.pending
    val enabled = pending == null && !busy
    val gated = draft.cardId == null && pending == null && (options?.canCreate != true || options.verificationState != "active")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (error != null) item { FriendCardNotice(error, "重试 / 保存草稿", onRetry) }
        if (eligibilityError != null) item { FriendCardNotice(eligibilityError, "重试读取资格", onRetry) }
        if (pending != null) item {
            FriendCardPanel(if (pending.rejected) "服务端已明确拒绝" else "保留中的发布操作") {
                Text(if (pending.rejected) "此操作未被接受。可返回修改后重新确认发布。" else "此操作的输入快照已保存。为防重复帖子，确认结果前不能修改、丢弃或换一个操作发布。离开或进程结束后仍可重试原操作。")
                FriendCardActions(*(if (pending.rejected) arrayOf("返回修改" to onUnlock) else arrayOf("使用原操作重试" to onPublish)), enabled = !busy)
            }
        }
        if (gated) {
            item { FriendCardPanel("仅创建需要宝宝认证") {
                Text(if (checking) "正在读取当前有效宝宝认证状态…" else when (options?.verificationState) {
                    "not_started" -> "创建扩列卡需要先通过宝宝认证。认证通过后即可填写并发布。"
                    "pending" -> "宝宝认证正在审核，通过后即可创建扩列卡。"
                    "rejected" -> "宝宝认证尚未通过，请查看原因并按现有流程重新申请。"
                    "revoked" -> "当前宝宝认证已撤销或失效，请查看状态并按现有流程重新认证。"
                    else -> "无法确认创建资格，请重试。不会猜测为未认证，也不会绕过服务端校验。"
                })
                Text("浏览扩列与详情不需要此认证；不新增年龄门槛。当前草稿会保留，返回后重新检查资格。")
                FriendCardActions((if (options?.verificationState == "pending") "查看认证进度" else "去宝宝认证") to onVerification, "重试状态" to onRetry, enabled = !checking)
            } }
            return@LazyColumn
        }
        if (!draft.initialized) { item { FriendCardNotice("正在读取原卡，读取成功前不覆盖草稿。", "重试", onRetry) }; return@LazyColumn }
        if (preview || pending != null) {
            val shown = if (pending != null) pending.input() else input
            val publicCard = FriendCardRules.publicPreview(shown, draft.diapers, draft.coverUrl)
            item { FriendCardPanel("公开卡片预览") {
                FriendCardCover(publicCard.coverUrl, publicCard.title, publicCard.coverFocusX, publicCard.coverFocusY, 1.5f)
                FriendCardContents(publicCard, games = options?.games.orEmpty().associate { it.id to it.name }, onDiaper = onDiaperOpen)
            } }
            item { FriendCardPanel("旧版 App / 网页文字预览") { Text(FriendCardRules.legacyText(publicCard, options?.games.orEmpty())) } }
            item { FriendCardPanel("发布说明") {
                Text(if (draft.cardId == null) "创建成功后，会自动发布一条带扩列标记的真实帖子。旧版App和网页会以文字展示你选择公开的信息。以后编辑会更新原帖，不会重复发帖。" else "保存会更新原卡与原发布帖，不会顶帖、重发或创建第二条帖子。")
                if (draft.cardId == null && draft.expectedCurrentId != null) Text("创建新卡成功后，原当前卡才会归档；失败不会先撤掉旧卡。")
                Text("仅自己可见字段不会进入预览与公开帖子。隐私变更无法抹除他人已经保存的截图。")
                if (pending == null) FriendCardActions("返回修改" to onBackToEdit, (if (draft.cardId == null) "创建并发布" else "保存并更新原帖") to onPublish, enabled = !busy && !coverBusy)
                if (busy) Text("正在发送已持久保存的操作…")
            } }
            return@LazyColumn
        }
        item { FriendCardPanel("${draft.stage + 1} / 3 · ${listOf("关于我", "喜欢与经历", "我想找")[draft.stage.coerceIn(0, 2)]}") {
            FriendCardActions("关于我" to { onStage(0) }, "喜欢与经历" to { onStage(1) }, "我想找" to { onStage(2) }, enabled = enabled)
            Text("字段单独设置公开范围；学校默认仅自己可见。所有可选字段可留空。")
        } }
        if (draft.stage == 0) {
            item { FriendCardPanel("封面 · 可选") {
                FriendCardCover(draft.coverUrl, input.title.ifBlank { "你的宣言" }, input.coverFocusX, input.coverFocusY, 2.2f)
                if (draft.coverUrl != null) {
                    Text("帖子横幅 2.2:1 / 详情封面 3:2 使用同一完整图片与焦点")
                    FriendCardCover(draft.coverUrl, input.title.ifBlank { "你的宣言" }, input.coverFocusX, input.coverFocusY, 1.5f)
                    Text("焦点横向 ${"%.0f".format(input.coverFocusX * 100)}% · 纵向 ${"%.0f".format(input.coverFocusY * 100)}%")
                    FriendCardActions("左移" to { onEdit { it.coverFocusX = (it.coverFocusX - .1f).coerceAtLeast(0f) } }, "右移" to { onEdit { it.coverFocusX = (it.coverFocusX + .1f).coerceAtMost(1f) } }, "上移" to { onEdit { it.coverFocusY = (it.coverFocusY - .1f).coerceAtLeast(0f) } }, "下移" to { onEdit { it.coverFocusY = (it.coverFocusY + .1f).coerceAtMost(1f) } }, enabled = enabled && !coverBusy)
                }
                FriendCardActions("选择图片" to onCover, "移除封面" to onRemoveCover, enabled = enabled && !coverBusy)
                Text(if (coverBusy) "压缩并上传封面中…" else "最大10 MiB。压缩、纠正方向并移除EXIF；完成上传后才保存generic资源ID，不接受任意URL。")
            } }
            item { FriendCardPanel {
                FriendCardField("宣言（必填）", input.title, 40, enabled, hint = "用一句话介绍你", onChange = { value -> onEdit { it.title = value } })
                EditorField("gender", input.gender, 24, input, enabled, onEdit) { target, text -> target.gender = text }
                EditorField("age", input.age, 12, input, enabled, onEdit, hint = "自愿填写展示文本，不是准入条件") { target, text -> target.age = text }
                EditorField("zodiac", input.zodiac, 12, input, enabled, onEdit) { target, text -> target.zodiac = text }
                EditorField("region", input.region, 60, input, enabled, onEdit, hint = "自愿填写省市，不读取地图定位") { target, text -> target.region = text }
                EditorField("school", input.school, 60, input, enabled, onEdit) { target, text -> target.school = text }
                EditorField("mbti", input.mbti, 16, input, enabled, onEdit) { target, text -> target.mbti = text }
                EditorField("interaction_habits", input.interactionHabits, 120, input, enabled, onEdit, multiline = true, hint = "例如：慢回复但认真回；熟悉后可语音；周末一起玩游戏。示例不会保存。") { target, text -> target.interactionHabits = text }
            } }
        }
        if (draft.stage == 1) {
            item { FriendCardPanel("我喜欢 · ${input.interests.size}/12") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { input.interests.forEach { value -> FriendCardChip("$value ×", true, enabled, { onToggleInterest(value) }) } }
                if (options?.interestPresets?.isNotEmpty() == true) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { options.interestPresets.forEach { value -> FriendCardChip(value, value in input.interests, enabled, { onToggleInterest(value) }) } }
                FriendCardTagInput("自定义兴趣", enabled, onInterest)
                FriendCardPrivacy("interests", "interests" in input.publicFields, enabled) { onEdit { togglePublic(it, "interests") } }
            } }
            item { FriendCardPanel("我在玩 · ${input.games.size}/8") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { input.games.forEach { value -> FriendCardChip("${FriendCardRules.gameName(value, options?.games.orEmpty())} ×", true, enabled, { onToggleGame(value) }) } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { options?.games.orEmpty().forEach { game -> FriendCardChip(game.name, input.games.any { FriendCardRules.gameKey(it, options?.games.orEmpty()) == game.id }, enabled, { onToggleGame(game.id) }) } }
                if (options == null) FriendCardNotice("游戏预设读取失败，当前自填内容仍保留。", "重试选项", onRetry)
                FriendCardTagInput("其他游戏", enabled, onGame)
                FriendCardPrivacy("games", "games" in input.publicFields, enabled) { onEdit { togglePublic(it, "games") } }
            } }
            item { FriendCardPanel("我穿过 · ${input.diaperIds.size}/8") {
                draft.diapers.filter { it.id in input.diaperIds }.forEach { item -> FriendCardProduct(item, { onDiaperOpen(item) }, if (enabled) ({ onDiaperRemove(item.id) }) else null) }
                FriendCardActions("选择库内纸尿裤" to onDiapers, enabled = enabled)
                FriendCardPrivacy("diapers", "diapers" in input.publicFields, enabled) { onEdit { togglePublic(it, "diapers") } }
            } }
        }
        if (draft.stage == 2) item { FriendCardPanel {
            EditorField("looking_for", input.lookingFor, 300, input, enabled, onEdit, multiline = true, hint = "你想认识怎样的朋友？（必填）") { target, text -> target.lookingFor = text }
            EditorField("boundaries", input.boundaries, 200, input, enabled, onEdit, multiline = true, hint = "自愿说明边界与不接受的互动") { target, text -> target.boundaries = text }
        } }
        item { FriendCardPanel {
            if (draft.stage < 2) FriendCardActions("下一阶段" to { onStage(draft.stage + 1) }, enabled = enabled)
            FriendCardActions("保存本机草稿" to onSave, "发布前预览" to onPreview, enabled = enabled && !coverBusy)
            Text("草稿按当前账号持久保存，不会发布。输入内容与账号个人资料相互独立。")
        } }
    }
}

private fun togglePublic(input: FriendCardInput, key: String) { input.publicFields = if (key in input.publicFields) input.publicFields - key else input.publicFields + key }

@Composable
private fun EditorField(key: String, value: String, limit: Int, input: FriendCardInput, enabled: Boolean, edit: ((FriendCardInput) -> Unit) -> Unit, multiline: Boolean = false, hint: String = "可留空", change: (FriendCardInput, String) -> Unit) {
    FriendCardField(FriendCardRules.fields[key] ?: key, value, limit, enabled, hint, multiline) { text -> edit { change(it, text) } }
    FriendCardPrivacy(key, key in input.publicFields, enabled) { edit { togglePublic(it, key) } }
}
