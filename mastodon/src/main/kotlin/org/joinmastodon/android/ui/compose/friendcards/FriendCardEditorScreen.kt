@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package org.joinmastodon.android.ui.compose.friendcards

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import org.joinmastodon.android.R
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
    val context = LocalContext.current
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
            if (error != null) item(key = "error") { FriendCardNotice(error, stringResource(R.string.fc_retry_save_draft), onRetry) }
            if (eligibilityError != null) item(key = "eligibility-error") { FriendCardNotice(eligibilityError, stringResource(R.string.fc_retry_eligibility), onRetry) }
            if (pending != null) item(key = "pending") {
                FriendCardPanel(if (pending.rejected) stringResource(R.string.fc_operation_rejected_title) else stringResource(R.string.fc_operation_pending_title)) {
                    Text(if (pending.rejected) stringResource(R.string.fc_operation_rejected_help) else stringResource(R.string.fc_operation_pending_help))
                    FriendCardActions(*(if (pending.rejected) arrayOf(stringResource(R.string.fc_back_to_edit) to onUnlock) else arrayOf(stringResource(R.string.fc_retry_operation) to onPublish)), enabled = !busy)
                }
            }
            if (gated) {
                item(key = "eligibility") {
                    FriendCardPanel(stringResource(R.string.fc_verification_title)) {
                        Text(if (checking) stringResource(R.string.fc_verification_checking) else when (options?.verificationState) {
                            "not_started" -> stringResource(R.string.fc_verification_not_started)
                            "pending" -> stringResource(R.string.fc_verification_pending)
                            "rejected" -> stringResource(R.string.fc_verification_rejected)
                            "revoked" -> stringResource(R.string.fc_verification_revoked)
                            else -> stringResource(R.string.fc_verification_unknown)
                        })
                        Text(stringResource(R.string.fc_verification_scope))
                        FriendCardActions((if (options?.verificationState == "pending") stringResource(R.string.fc_verification_progress) else stringResource(R.string.fc_verification_open)) to onVerification, stringResource(R.string.fc_retry_status) to onRetry, enabled = !checking)
                    }
                }
                return@LazyColumn
            }
            if (!draft.initialized) {
                item(key = "loading") { FriendCardNotice(stringResource(R.string.fc_loading_original), stringResource(R.string.fc_retry), onRetry) }
                return@LazyColumn
            }
            if (preview || pending != null) {
                // Always render the public projection, and use the immutable operation when pending.
                val shown = if (pending != null) pending.input() else input
                val publicCard = FriendCardRules.publicPreview(shown, draft.diapers, draft.coverUrl)
                item(key = "public-preview") {
                    FriendCardPanel(stringResource(R.string.fc_preview_public)) {
                        FriendCardPortrait(
                            card = publicCard,
                            modifier = Modifier.clip(RoundedCornerShape(24.dp)),
                            games = games.associate { it.id to it.name },
                            onDiaper = onDiaperOpen,
                        )
                    }
                }
                item(key = "legacy-preview") {
                    FriendCardPanel(stringResource(R.string.fc_preview_legacy)) { Text(FriendCardRules.legacyText(publicCard, games, context)) }
                }
                item(key = "publication-notice") {
                    FriendCardPanel(stringResource(R.string.fc_publication_title)) {
                        Text(if (draft.cardId == null) stringResource(R.string.fc_publication_create_help) else stringResource(R.string.fc_publication_edit_help))
                        if (draft.cardId == null && draft.expectedCurrentId != null) Text(stringResource(R.string.fc_publication_replace_help))
                        Text(stringResource(R.string.fc_publication_privacy_help))
                        if (pending == null) {
                            FriendCardPrimaryButton(if (draft.cardId == null) stringResource(R.string.fc_create_publish) else stringResource(R.string.fc_save_update_post), onPublish, enabled = !busy && !coverBusy)
                            FriendCardActions(stringResource(R.string.fc_back_to_edit) to onBackToEdit, enabled = !busy && !coverBusy)
                        }
                        if (busy) Text(stringResource(R.string.fc_sending_operation))
                    }
                }
                return@LazyColumn
            }
            item(key = "privacy-note") {
                EditorHint(stringResource(R.string.fc_optional_privacy_hint))
            }
            if (stage == 0) {
                item(key = "cover") {
                    FriendCardPanel {
                        if (draft.coverUrl != null) {
                            FriendCardCover(draft.coverUrl, input.title.ifBlank { stringResource(R.string.fc_your_statement) }, input.coverFocusX, input.coverFocusY, 2.2f)
                            EditorHint(stringResource(R.string.fc_cover_ratios))
                            FriendCardCover(draft.coverUrl, input.title.ifBlank { stringResource(R.string.fc_your_statement) }, input.coverFocusX, input.coverFocusY, 1.5f)
                            EditorHint(stringResource(R.string.fc_cover_focus, kotlin.math.round(input.coverFocusX * 100).toInt(), kotlin.math.round(input.coverFocusY * 100).toInt()))
                            FriendCardActions(
                                stringResource(R.string.fc_cover_left) to { onEdit { it.coverFocusX = (it.coverFocusX - .1f).coerceAtLeast(0f) } },
                                stringResource(R.string.fc_cover_right) to { onEdit { it.coverFocusX = (it.coverFocusX + .1f).coerceAtMost(1f) } },
                                stringResource(R.string.fc_cover_up) to { onEdit { it.coverFocusY = (it.coverFocusY - .1f).coerceAtLeast(0f) } },
                                stringResource(R.string.fc_cover_down) to { onEdit { it.coverFocusY = (it.coverFocusY + .1f).coerceAtMost(1f) } },
                                enabled = enabled && !coverBusy,
                            )
                            FriendCardActions(stringResource(R.string.fc_choose_image) to onCover, stringResource(R.string.fc_remove_cover) to onRemoveCover, enabled = enabled && !coverBusy)
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
                                    Text(stringResource(R.string.fc_upload_cover), fontSize = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    Text(stringResource(R.string.fc_cover_choose_limit), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
                                }
                            }
                        }
                        if (coverBusy) EditorHint(stringResource(R.string.fc_cover_uploading))
                    }
                }
                item(key = "declaration") {
                    FriendCardPanel {
                        FriendCardField(stringResource(R.string.fc_statement_required_label), input.title, 40, enabled, stringResource(R.string.fc_statement_hint), multiline = false) { value -> onEdit { it.title = value } }
                    }
                }
                item(key = "about-me") {
                    FriendCardPanel(stringResource(R.string.fc_about_optional)) {
                        EditorHint(stringResource(R.string.fc_field_visibility_hint))
                        Column {
                            EditorField("gender", input.gender, 24, input, enabled, onEdit) { target, text -> target.gender = text }
                            EditorDivider()
                            EditorField("age", input.age, 12, input, enabled, onEdit, hint = stringResource(R.string.fc_age_hint)) { target, text -> target.age = text }
                            EditorDivider()
                            EditorField("zodiac", input.zodiac, 12, input, enabled, onEdit) { target, text -> target.zodiac = text }
                            EditorDivider()
                            EditorField("region", input.region, 60, input, enabled, onEdit, hint = stringResource(R.string.fc_region_hint)) { target, text -> target.region = text }
                            EditorDivider()
                            EditorField("school", input.school, 60, input, enabled, onEdit) { target, text -> target.school = text }
                            EditorDivider()
                            EditorField("mbti", input.mbti, 16, input, enabled, onEdit) { target, text -> target.mbti = text }
                            EditorDivider()
                            EditorField("interaction_habits", input.interactionHabits, 120, input, enabled, onEdit, multiline = true, hint = stringResource(R.string.fc_habits_hint)) { target, text -> target.interactionHabits = text }
                            val socialSupported = options?.capabilities?.socialAccounts == true
                            if (socialSupported || !input.socialAccounts.isNullOrEmpty()) {
                                EditorDivider()
                                Spacer(Modifier.height(20.dp))
                                if (!socialSupported) Text(stringResource(R.string.fc_social_unsupported_readonly), fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(bottom = 12.dp))
                                FriendCardSocialEditor(
                                    accounts = input.socialAccounts.orEmpty(),
                                    enabled = enabled && socialSupported,
                                    onChange = { accounts -> if (enabled && socialSupported) onEdit { target -> target.socialAccounts = accounts.toList() } },
                                )
                            }
                        }
                    }
                }
            }
            if (stage == 1) {
                item(key = "interests") {
                    FriendCardPanel(stringResource(R.string.fc_interests_count, input.interests.size)) {
                        if (input.interests.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            input.interests.forEach { value -> FriendCardChip("$value ×", true, enabled, { onToggleInterest(value) }) }
                        }
                        if (options?.interestPresets?.isNotEmpty() == true) {
                            EditorHint(stringResource(R.string.fc_interests_hint))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                options.interestPresets.forEach { value -> FriendCardChip(value, value in input.interests, enabled, { onToggleInterest(value) }) }
                            }
                        }
                        FriendCardTagInput(stringResource(R.string.fc_custom_interest), enabled, onInterest)
                        FriendCardPrivacy("interests", "interests" in input.publicFields, enabled) { onEdit { togglePublic(it, "interests") } }
                    }
                }
                item(key = "games") {
                    FriendCardPanel(stringResource(R.string.fc_games_count, input.games.size)) {
                        if (input.games.isNotEmpty()) {
                            EditorGameGrid(
                                input.games.map { it to FriendCardRules.gameName(it, games, context) },
                                selected = { true }, removable = true, enabled = enabled, onToggle = onToggleGame,
                            )
                        }
                        if (games.isNotEmpty()) {
                            EditorHint(stringResource(R.string.fc_games_hint))
                            EditorGameGrid(
                                games.map { it.id to FriendCardRules.gameName(it.id, games, context) },
                                selected = { id -> input.games.any { FriendCardRules.gameKey(it, games) == id } },
                                removable = false, enabled = enabled, onToggle = onToggleGame,
                            )
                        }
                        if (options == null) FriendCardNotice(stringResource(R.string.fc_games_options_failed), stringResource(R.string.fc_retry_options), onRetry)
                        FriendCardTagInput(stringResource(R.string.fc_other_game), enabled, onGame)
                        FriendCardPrivacy("games", "games" in input.publicFields, enabled) { onEdit { togglePublic(it, "games") } }
                    }
                }
                item(key = "products") {
                    FriendCardPanel(stringResource(R.string.fc_products_count, input.diaperIds.size)) {
                        if (input.diaperIds.isEmpty()) EditorHint(stringResource(R.string.fc_products_hint))
                        draft.diapers.filter { it.id in input.diaperIds }.forEach { item ->
                            FriendCardProduct(item, { onDiaperOpen(item) }, if (enabled) ({ onDiaperRemove(item.id) }) else null)
                        }
                        FriendCardPrimaryButton(stringResource(R.string.fc_choose_catalog_diapers), onDiapers, enabled = enabled, outlined = true)
                        FriendCardPrivacy("diapers", "diapers" in input.publicFields, enabled) { onEdit { togglePublic(it, "diapers") } }
                    }
                }
            }
            if (stage == 2) {
                item(key = "looking-for") {
                    FriendCardPanel {
                        EditorField("looking_for", input.lookingFor, 300, input, enabled, onEdit, multiline = true, hint = stringResource(R.string.fc_looking_for_hint)) { target, text -> target.lookingFor = text }
                    }
                }
                item(key = "boundaries") {
                    FriendCardPanel {
                        EditorField("boundaries", input.boundaries, 200, input, enabled, onEdit, multiline = true, hint = stringResource(R.string.fc_boundaries_hint)) { target, text -> target.boundaries = text }
                    }
                }
            }
            item(key = "draft-notice") {
                EditorHint(stringResource(R.string.fc_draft_hint))
            }
        }
        if (editing) EditorBottomBar(stage, enabled, coverBusy, onStage, onPreview)
    }
}

@Composable
private fun EditorHeader(stage: Int, completion: Int, enabled: Boolean, onStage: (Int) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val stages = listOf(stringResource(R.string.fc_about_me), stringResource(R.string.fc_interests_experiences), stringResource(R.string.fc_looking_for))
    Column(
        Modifier.fillMaxWidth().background(colors.background).padding(top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.fc_completion), color = colors.onSurfaceVariantSummary, fontSize = 13.sp)
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
        Text(stringResource(R.string.fc_stage_progress, stage + 1, stages[stage]), color = colors.onSurfaceVariantSummary, fontSize = 13.sp)
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
                        color = if (stage == index) colors.onPrimaryContainer else colors.onSurfaceVariantSummary, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun EditorBottomBar(stage: Int, enabled: Boolean, coverBusy: Boolean, onStage: (Int) -> Unit, onPreview: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background)) {
        EditorDivider()
        FriendCardPrimaryButton(
            text = stringResource(R.string.fc_next_stage),
            onClick = { if (stage < 2) onStage(stage + 1) else onPreview() },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 54.dp),
            enabled = enabled && (stage < 2 || !coverBusy),
        )
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
private fun EditorField(key: String, value: String, limit: Int, input: FriendCardInput, enabled: Boolean, edit: ((FriendCardInput) -> Unit) -> Unit, multiline: Boolean = false, hint: String = stringResource(R.string.fc_optional_hint), change: (FriendCardInput, String) -> Unit) {
    val label = stringResource(FriendCardRules.fieldLabel(key))
    if (multiline) {
        Column(Modifier.padding(vertical = if (key == "interaction_habits") 16.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FriendCardField(if (key == "looking_for") stringResource(R.string.fc_required_label, label) else label, value, limit, enabled, hint, multiline = true) { text -> edit { change(it, text) } }
            FriendCardPrivacy(key, key in input.publicFields, enabled) { edit { togglePublic(it, key) } }
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth.value / LocalDensity.current.fontScale < 300f) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.weight(1f), color = MiuixTheme.colorScheme.onSurface, fontSize = 15.sp)
                        EditorVisibility(key, key in input.publicFields, enabled) { edit { togglePublic(it, key) } }
                    }
                    EditorInlineInput(value, limit, enabled, hint, TextAlign.Start) { text -> edit { change(it, text) } }
                }
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.widthIn(max = 100.dp), color = MiuixTheme.colorScheme.onSurface, fontSize = 15.sp)
                    EditorVisibility(key, key in input.publicFields, enabled) { edit { togglePublic(it, key) } }
                    Box(Modifier.weight(1f)) {
                        EditorInlineInput(value, limit, enabled, hint, TextAlign.End) { text -> edit { change(it, text) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorInlineInput(value: String, limit: Int, enabled: Boolean, hint: String, alignment: TextAlign, change: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicTextField(
            value = value, onValueChange = change, enabled = enabled, singleLine = true,
            textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 16.sp, textAlign = alignment),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (alignment == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp, textAlign = alignment)
                    inner()
                }
            },
        )
        if (FriendCardRules.length(value) > limit) EditorCount(value, limit)
    }
}

/** Compact visual control; retain the complete existing privacy labels for accessibility. */
@Composable
private fun EditorVisibility(field: String, visible: Boolean, enabled: Boolean, onChange: () -> Unit) {
    val visibilityLabel = stringResource(R.string.fc_visibility_label, stringResource(FriendCardRules.fieldLabel(field)))
    val actionLabel = stringResource(if (visible) R.string.fc_public_toggle else R.string.fc_private_toggle)
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .toggleable(value = visible, enabled = enabled, role = Role.Checkbox, onValueChange = { onChange() })
            .semantics {
                this[SemanticsProperties.Text] = listOf(
                    AnnotatedString(visibilityLabel),
                    AnnotatedString(actionLabel),
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
        val columns = if (maxWidth.value / LocalDensity.current.fontScale < 300f) 1 else 2
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
                        Text(if (removable) stringResource(R.string.fc_added) else if (active) stringResource(R.string.fc_selected) else stringResource(R.string.fc_tap_add), fontSize = 11.sp, color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                        if (active) Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(16.dp))
                    }
                    Text(if (removable) "$name ×" else name, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold,
                        color = if (active) colors.onPrimaryContainer else colors.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
