@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package org.joinmastodon.android.ui.compose.friendcards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.api.requests.friendcards.FriendCardInput
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses
import org.joinmastodon.android.friendcards.FriendCardDraft
import org.joinmastodon.android.friendcards.FriendCardRules
import org.joinmastodon.android.model.FriendCard
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    val editing = !gated && draft.initialized && !preview && pending == null
    val stage = draft.stage.coerceIn(0, 2)
    val games = options?.games.orEmpty()
    // Keep the list and field compositions in place across routine controller revisions.
    val listState = rememberLazyListState()
    LaunchedEffect(stage, preview) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        if (editing) Box(Modifier.padding(horizontal = 16.dp)) {
            EditorHeader(stage, FriendCardCompletion(input, !draft.coverUrl.isNullOrBlank()), enabled, onStage)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (error != null) item(key = "error") { FriendCardNotice(error, "重试 / 保存草稿", onRetry) }
            if (eligibilityError != null) item(key = "eligibility-error") { FriendCardNotice(eligibilityError, "重试读取资格", onRetry) }
            if (pending != null) item(key = "pending") {
                FriendCardPanel(if (pending.rejected) "服务端已明确拒绝" else "保留中的发布操作") {
                    Text(if (pending.rejected) "此操作未被接受。可返回修改后重新确认发布。" else "此操作的输入快照已保存。为防重复帖子，确认结果前不能修改、丢弃或换一个操作发布。离开或进程结束后仍可重试原操作。")
                    FriendCardActions(*(if (pending.rejected) arrayOf("返回修改" to onUnlock) else arrayOf("使用原操作重试" to onPublish)), enabled = !busy)
                }
            }
            if (gated) {
                item(key = "eligibility") {
                    FriendCardPanel("仅创建需要宝宝认证") {
                        Text(if (checking) "正在读取当前有效宝宝认证状态…" else when (options?.verificationState) {
                            "not_started" -> "创建扩列卡需要先通过宝宝认证。认证通过后即可填写并发布。"
                            "pending" -> "宝宝认证正在审核，通过后即可创建扩列卡。"
                            "rejected" -> "宝宝认证尚未通过，请查看原因并按现有流程重新申请。"
                            "revoked" -> "当前宝宝认证已撤销或失效，请查看状态并按现有流程重新认证。"
                            else -> "无法确认创建资格，请重试。不会猜测为未认证，也不会绕过服务端校验。"
                        })
                        Text("浏览扩列与详情不需要此认证；不新增年龄门槛。当前草稿会保留，返回后重新检查资格。")
                        FriendCardActions((if (options?.verificationState == "pending") "查看认证进度" else "去宝宝认证") to onVerification, "重试状态" to onRetry, enabled = !checking)
                    }
                }
                return@LazyColumn
            }
            if (!draft.initialized) {
                item(key = "loading") { FriendCardNotice("正在读取原卡，读取成功前不覆盖草稿。", "重试", onRetry) }
                return@LazyColumn
            }
            if (preview || pending != null) {
                // Always render the public projection, and use the immutable operation when pending.
                val shown = if (pending != null) pending.input() else input
                val publicCard = FriendCardRules.publicPreview(shown, draft.diapers, draft.coverUrl)
                item(key = "public-preview") {
                    FriendCardPanel("公开卡片预览") {
                        FriendCardPortrait(
                            card = publicCard,
                            modifier = Modifier.clip(RoundedCornerShape(24.dp)),
                            games = games.associate { it.id to it.name },
                            onDiaper = onDiaperOpen,
                        )
                    }
                }
                item(key = "legacy-preview") {
                    FriendCardPanel("旧版 App / 网页文字预览") { Text(FriendCardRules.legacyText(publicCard, games)) }
                }
                item(key = "publication-notice") {
                    FriendCardPanel("发布说明") {
                        Text(if (draft.cardId == null) "创建成功后，会自动发布一条带扩列标记的真实帖子。旧版App和网页会以文字展示你选择公开的信息。以后编辑会更新原帖，不会重复发帖。" else "保存会更新原卡与原发布帖，不会顶帖、重发或创建第二条帖子。")
                        if (draft.cardId == null && draft.expectedCurrentId != null) Text("创建新卡成功后，原当前卡才会归档；失败不会先撤掉旧卡。")
                        Text("仅自己可见字段不会进入预览与公开帖子。隐私变更无法抹除他人已经保存的截图。")
                        if (pending == null) {
                            FriendCardPrimaryButton(if (draft.cardId == null) "创建并发布" else "保存并更新原帖", onPublish, enabled = !busy && !coverBusy)
                            FriendCardActions("返回修改" to onBackToEdit, enabled = !busy && !coverBusy)
                        }
                        if (busy) Text("正在发送已持久保存的操作…")
                    }
                }
                return@LazyColumn
            }
            item(key = "privacy-note") {
                EditorHint("选填资料可留空，学校默认仅自己可见。")
            }
            if (stage == 0) {
                item(key = "cover") {
                    FriendCardPanel {
                        if (draft.coverUrl != null) {
                            FriendCardCover(draft.coverUrl, input.title.ifBlank { "你的宣言" }, input.coverFocusX, input.coverFocusY, 2.2f)
                            EditorHint("帖子横幅 2.2:1 / 详情封面 3:2 使用同一完整图片与焦点")
                            FriendCardCover(draft.coverUrl, input.title.ifBlank { "你的宣言" }, input.coverFocusX, input.coverFocusY, 1.5f)
                            EditorHint("焦点横向 ${"%.0f".format(input.coverFocusX * 100)}% · 纵向 ${"%.0f".format(input.coverFocusY * 100)}%")
                            FriendCardActions(
                                "左移" to { onEdit { it.coverFocusX = (it.coverFocusX - .1f).coerceAtLeast(0f) } },
                                "右移" to { onEdit { it.coverFocusX = (it.coverFocusX + .1f).coerceAtMost(1f) } },
                                "上移" to { onEdit { it.coverFocusY = (it.coverFocusY - .1f).coerceAtLeast(0f) } },
                                "下移" to { onEdit { it.coverFocusY = (it.coverFocusY + .1f).coerceAtMost(1f) } },
                                enabled = enabled && !coverBusy,
                            )
                            FriendCardActions("选择图片" to onCover, "移除封面" to onRemoveCover, enabled = enabled && !coverBusy)
                        } else {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 88.dp).clip(RoundedCornerShape(16.dp))
                                    .clickable(enabled = enabled && !coverBusy, role = Role.Button, onClick = onCover)
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Image, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(32.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("上传扩列封面", fontSize = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    Text("选择图片 · 最大 10 MiB", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                                }
                            }
                        }
                        if (coverBusy) EditorHint("压缩并上传封面中…")
                    }
                }
                item(key = "declaration") {
                    FriendCardPanel {
                        FriendCardField("宣言（必填）", input.title, 40, enabled, "用一句话介绍你", multiline = false) { value -> onEdit { it.title = value } }
                    }
                }
                item(key = "about-me") {
                    FriendCardPanel("关于我 · 可选资料") {
                        EditorHint("直接填写右侧内容；点按可见性图标切换公开范围。")
                        Column {
                            EditorField("gender", input.gender, 24, input, enabled, onEdit) { target, text -> target.gender = text }
                            EditorDivider()
                            EditorField("age", input.age, 12, input, enabled, onEdit, hint = "自愿填写展示文本，不是准入条件") { target, text -> target.age = text }
                            EditorDivider()
                            EditorField("zodiac", input.zodiac, 12, input, enabled, onEdit) { target, text -> target.zodiac = text }
                            EditorDivider()
                            EditorField("region", input.region, 60, input, enabled, onEdit, hint = "自愿填写省市，不读取地图定位") { target, text -> target.region = text }
                            EditorDivider()
                            EditorField("school", input.school, 60, input, enabled, onEdit) { target, text -> target.school = text }
                            EditorDivider()
                            EditorField("mbti", input.mbti, 16, input, enabled, onEdit) { target, text -> target.mbti = text }
                            EditorDivider()
                            EditorField("interaction_habits", input.interactionHabits, 120, input, enabled, onEdit, multiline = true, hint = "例如：慢回复但认真回；熟悉后可语音；周末一起玩游戏。示例不会保存。") { target, text -> target.interactionHabits = text }
                        }
                    }
                }
            }
            if (stage == 1) {
                item(key = "interests") {
                    FriendCardPanel("我喜欢 · ${input.interests.size}/12") {
                        if (input.interests.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            input.interests.forEach { value -> FriendCardChip("$value ×", true, enabled, { onToggleInterest(value) }) }
                        }
                        if (options?.interestPresets?.isNotEmpty() == true) {
                            EditorHint("点选兴趣，找到更多共同话题")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                options.interestPresets.forEach { value -> FriendCardChip(value, value in input.interests, enabled, { onToggleInterest(value) }) }
                            }
                        }
                        FriendCardTagInput("自定义兴趣", enabled, onInterest)
                        FriendCardPrivacy("interests", "interests" in input.publicFields, enabled) { onEdit { togglePublic(it, "interests") } }
                    }
                }
                item(key = "games") {
                    FriendCardPanel("我在玩 · ${input.games.size}/8") {
                        if (input.games.isNotEmpty()) {
                            EditorGameGrid(
                                input.games.map { it to FriendCardRules.gameName(it, games) },
                                selected = { true }, removable = true, enabled = enabled, onToggle = onToggleGame,
                            )
                        }
                        if (games.isNotEmpty()) {
                            EditorHint("选择你在玩的游戏，也可以填写其他游戏")
                            EditorGameGrid(
                                games.map { it.id to it.name },
                                selected = { id -> input.games.any { FriendCardRules.gameKey(it, games) == id } },
                                removable = false, enabled = enabled, onToggle = onToggleGame,
                            )
                        }
                        if (options == null) FriendCardNotice("游戏预设读取失败，当前自填内容仍保留。", "重试选项", onRetry)
                        FriendCardTagInput("其他游戏", enabled, onGame)
                        FriendCardPrivacy("games", "games" in input.publicFields, enabled) { onEdit { togglePublic(it, "games") } }
                    }
                }
                item(key = "products") {
                    FriendCardPanel("我穿过 · ${input.diaperIds.size}/8") {
                        if (input.diaperIds.isEmpty()) EditorHint("从真实商品库中选择你穿过的款式，可留空。")
                        draft.diapers.filter { it.id in input.diaperIds }.forEach { item ->
                            FriendCardProduct(item, { onDiaperOpen(item) }, if (enabled) ({ onDiaperRemove(item.id) }) else null)
                        }
                        FriendCardPrimaryButton("选择库内纸尿裤", onDiapers, enabled = enabled, outlined = true)
                        FriendCardPrivacy("diapers", "diapers" in input.publicFields, enabled) { onEdit { togglePublic(it, "diapers") } }
                    }
                }
            }
            if (stage == 2) {
                item(key = "looking-for") {
                    FriendCardPanel {
                        EditorField("looking_for", input.lookingFor, 300, input, enabled, onEdit, multiline = true, hint = "你想认识怎样的朋友？（必填）") { target, text -> target.lookingFor = text }
                    }
                }
                item(key = "boundaries") {
                    FriendCardPanel {
                        EditorField("boundaries", input.boundaries, 200, input, enabled, onEdit, multiline = true, hint = "自愿说明边界与不接受的互动") { target, text -> target.boundaries = text }
                    }
                }
            }
            item(key = "draft-notice") {
                EditorHint("草稿按当前账号持久保存，不会发布。输入内容与账号个人资料相互独立。")
            }
        }
        if (editing) EditorBottomBar(stage, enabled, coverBusy, onStage, onPreview, onSave)
    }
}

@Composable
private fun EditorHeader(stage: Int, completion: Int, enabled: Boolean, onStage: (Int) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val stages = listOf("关于我", "喜欢与经历", "我想找")
    Column(
        Modifier.fillMaxWidth().background(colors.background).padding(top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("完成度", color = colors.onSurfaceVariantSummary, fontSize = 13.sp)
            Box(
                Modifier.weight(1f).height(6.dp).clip(CircleShape).background(colors.surfaceVariant)
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(completion.toFloat(), 0f..100f) },
            ) {
                if (completion > 0) Box(
                    Modifier.fillMaxWidth(completion / 100f).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Color(0xFF249EFF), Color(0xFFA16BFA)))),
                )
            }
            Text("$completion%", color = colors.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Text("${stage + 1} / 3 · ${stages[stage]}", color = colors.onSurfaceVariantSummary, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            stages.forEachIndexed { index, label ->
                Box(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(CircleShape)
                        .background(if (stage == index) colors.primaryContainer else colors.surface)
                        .clickable(enabled = enabled, role = Role.Tab, onClick = { onStage(index) })
                        .padding(horizontal = 6.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 13.sp, fontWeight = if (stage == index) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (stage == index) colors.onPrimaryContainer else colors.onSurfaceVariantSummary)
                }
            }
        }
    }
}

@Composable
private fun EditorBottomBar(stage: Int, enabled: Boolean, coverBusy: Boolean, onStage: (Int) -> Unit, onPreview: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background)) {
        EditorDivider()
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FriendCardPrimaryButton(
                text = if (stage < 2) "下一阶段" else "发布前预览",
                onClick = { if (stage < 2) onStage(stage + 1) else onPreview() },
                modifier = Modifier.heightIn(min = 54.dp),
                enabled = enabled && (stage < 2 || !coverBusy),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                EditorFooterAction("保存本机草稿", enabled && !coverBusy, Modifier.weight(1f), onSave)
                if (stage < 2) EditorFooterAction("发布前预览", enabled && !coverBusy, Modifier.weight(1f), onPreview)
            }
        }
    }
}

@Composable
private fun EditorFooterAction(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(8.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = if (enabled) 1f else .45f), textAlign = TextAlign.Center)
    }
}

@Composable
private fun EditorHint(value: String) {
    Text(value, fontSize = 12.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun EditorDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.dividerLine.copy(alpha = .6f)))
}

private fun togglePublic(input: FriendCardInput, key: String) { input.publicFields = if (key in input.publicFields) input.publicFields - key else input.publicFields + key }

@Composable
private fun EditorField(key: String, value: String, limit: Int, input: FriendCardInput, enabled: Boolean, edit: ((FriendCardInput) -> Unit) -> Unit, multiline: Boolean = false, hint: String = "可留空", change: (FriendCardInput, String) -> Unit) {
    val label = FriendCardRules.fields[key] ?: key
    if (multiline) {
        Column(Modifier.padding(vertical = if (key == "interaction_habits") 16.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FriendCardField(if (key == "looking_for") "$label（必填）" else label, value, limit, enabled, hint, multiline = true) { text -> edit { change(it, text) } }
            FriendCardPrivacy(key, key in input.publicFields, enabled) { edit { togglePublic(it, key) } }
        }
    } else {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MiuixTheme.colorScheme.onSurface, fontSize = 15.sp)
            EditorVisibility(key, key in input.publicFields, enabled) { edit { togglePublic(it, key) } }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                BasicTextField(
                    value = value, onValueChange = { text -> edit { change(it, text) } }, enabled = enabled, singleLine = true,
                    textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 16.sp, textAlign = TextAlign.End),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            if (value.isEmpty()) Text(hint, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp, textAlign = TextAlign.End)
                            inner()
                        }
                    },
                )
                if (FriendCardRules.length(value) > limit) EditorCount(value, limit)
            }
        }
    }
}

/** Compact visual control; retain the complete existing privacy labels for accessibility. */
@Composable
private fun EditorVisibility(field: String, visible: Boolean, enabled: Boolean, onChange: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .toggleable(value = visible, enabled = enabled, role = Role.Checkbox, onValueChange = { onChange() })
            .semantics {
                this[SemanticsProperties.Text] = listOf(
                    AnnotatedString("${FriendCardRules.fields[field]}可见范围"),
                    AnnotatedString(if (visible) "公开 · 点击设为仅自己" else "仅自己 · 点击设为公开"),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (visible) Icons.Outlined.Visibility else Icons.Outlined.Lock, contentDescription = null,
            tint = if (visible) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun EditorCount(value: String, limit: Int) {
    val count = FriendCardRules.length(value)
    Text("$count/$limit", fontSize = 11.sp, color = if (count > limit) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun EditorGameGrid(games: List<Pair<String, String>>, selected: (String) -> Boolean, removable: Boolean, enabled: Boolean, onToggle: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 260.dp) 1 else 2
        val tileWidth = (maxWidth - 10.dp * (columns - 1) - 1.dp) / columns
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            games.forEach { (id, name) ->
                val active = selected(id)
                Column(
                    Modifier.width(tileWidth).heightIn(min = 112.dp).clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(if (active) listOf(colors.primaryContainer, colors.surfaceVariant.copy(alpha = .55f)) else listOf(colors.surfaceVariant.copy(alpha = .55f), colors.surface)))
                        .border(1.dp, if (active) colors.primary.copy(alpha = .3f) else colors.dividerLine.copy(alpha = .6f), RoundedCornerShape(18.dp))
                        .clickable(enabled = enabled, role = Role.Button, onClick = { onToggle(id) }).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (removable) "已添加" else if (active) "已选择" else "点击添加", fontSize = 11.sp, color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                        if (active) Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(16.dp))
                    }
                    Text(if (removable) "$name ×" else name, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold,
                        color = if (active) colors.onPrimaryContainer else colors.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
