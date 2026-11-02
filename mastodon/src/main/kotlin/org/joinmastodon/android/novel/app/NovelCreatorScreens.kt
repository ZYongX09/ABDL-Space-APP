package org.joinmastodon.android.novel.app

import org.joinmastodon.android.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.novel.app.design.NovelIcons
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.joinmastodon.android.novel.app.design.NovelTypography
import org.joinmastodon.android.novel.app.editor.LocalNovelSummary
import org.joinmastodon.android.novel.app.models.Creator
import org.joinmastodon.android.novel.app.models.Work

/** Server metadata and device manuscripts deliberately remain two separate inventories. */
internal data class NovelCreatorInventory(val server: List<Work>, val local: List<LocalNovelSummary>) {
    val published: List<Work> get() = server.filter { it.status == "published" }
    val reviewing: List<Work> get() = server.filter { it.status == "pending" || it.chapters.any { c -> c.status in setOf("pending", "reviewing") } }
}

@Composable internal fun NovelCreatorScreen(
    state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit,
    onCreate: (workId: String?, localWorkId: String?, mode: String) -> Unit,
    onVerify: () -> Unit, onPost: () -> Unit,
) {
    val creator = state.creator.value
    var rules by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = LocalNovelBottomPadding.current + 4.dp)) {
        item { NovelHeader(stringResource(R.string.novel_en_creator_center), onBack) { NovelIconButton(if (creator?.active == true) NovelIcons.Settings2 else NovelIcons.Info, stringResource(R.string.novel_en_publishing_guidelines), { rules = true }) } }
        if (creator != null) {
            if (creator.active) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp).background(NovelTheme.colors.primaryContainer.copy(alpha = .45f), RoundedCornerShape(26.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        CreatorIdentity(state, true)
                        Row(Modifier.fillMaxWidth()) {
                            NovelMetric(creator.stats.works, stringResource(R.string.novel_en_published_works), Modifier.weight(1f))
                            NovelMetric(creator.stats.followers, stringResource(R.string.novel_en_followers), Modifier.weight(1f))
                            NovelMetric(creator.stats.words, stringResource(R.string.novel_en_total_written), Modifier.weight(1.3f))
                        }
                        NovelRule()
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NovelPill(stringResource(R.string.novel_en_create_work), { onCreate(null, null, "create") }, icon = NovelIcons.Plus)
                            NovelPill(stringResource(R.string.novel_en_import_manuscript), { onCreate(null, null, "import") }, primary = false, icon = NovelIcons.Upload)
                        }
                    }
                    NovelSection(stringResource(R.string.novel_en_work_statistics), stringResource(R.string.novel_en_all_statistics_link), { vm.navigate(NovelPage.Statistics) })
                    CreatorStats(creator.stats)
                    NovelSection(stringResource(R.string.novel_en_my_works), stringResource(R.string.novel_en_all_works_link), { vm.navigate(NovelPage.OwnerWorks) })
                }
                creatorInventory(state, vm, NovelCreatorInventory(creator.works, state.localWorks.value.orEmpty()), onCreate)
                item {
                    NovelLoadState(state.localWorks, false, "", vm::refresh)
                    CreatorRulesLink { rules = true }
                }
            } else {
                item {
                    CreatorIdentity(state, false)
                    Spacer(Modifier.height(22.dp))
                    QualificationCard(creator)
                    NovelSection(stringResource(R.string.novel_en_requirements))
                    QualificationRow(NovelIcons.BadgeCheck, stringResource(R.string.novel_en_baby_verification_requirement), creator.babyVerified,
                        stringResource(R.string.novel_en_verification_privacy), if (!creator.babyVerified) stringResource(R.string.novel_en_verify) else null, onVerify)
                    QualificationRow(NovelIcons.Clock, stringResource(R.string.novel_en_account_age_requirement), creator.accountAgeEligible,
                        if (creator.registeredAt.isBlank()) stringResource(R.string.novel_en_registration_server_check) else stringResource(R.string.novel_en_registered_at, creator.registeredAt.novelRegistrationDate()), null, {})
                    QualificationRow(NovelIcons.Heart, stringResource(R.string.novel_en_qualifying_posts_requirement, creator.requiredPosts, creator.requiredLikes), creator.qualifyingPosts >= creator.requiredPosts,
                        stringResource(R.string.novel_en_qualifying_posts_progress, creator.requiredLikes, creator.qualifyingPosts), if (creator.qualifyingPosts < creator.requiredPosts) stringResource(R.string.novel_en_write_post) else null, onPost)
                    Spacer(Modifier.height(22.dp))
                    NovelPill(if ("apply" in state.pending) stringResource(R.string.novel_en_activating) else stringResource(R.string.novel_en_activate_creator), vm::applyCreator, Modifier.fillMaxWidth(),
                        enabled = creator.eligible && creator.completedConditions == 3 && !state.creator.loading && "apply" !in state.pending, icon = NovelIcons.LockKeyhole)
                    NovelLink(stringResource(R.string.novel_en_recheck_eligibility), vm::refresh, Modifier.fillMaxWidth().padding(top = 12.dp))
                    Text(stringResource(R.string.novel_en_activation_explanation), color = NovelTheme.colors.textSecondary, fontSize = 11.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 18.dp))
                }
            }
        }
        item { NovelLoadState(state.creator, false, "", vm::refresh) }
    }
    if (rules) CreatorRulesDialog { rules = false }
}

@Composable private fun CreatorIdentity(state: NovelAppState, active: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        state.self?.let { NovelAvatar(it, 52.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NovelHeading(state.self?.name ?: stringResource(R.string.novel_en_current_account), size = 24)
            Text(if (active) stringResource(R.string.novel_en_creator_active) else state.self?.let { stringResource(R.string.novel_en_community_user_handle, it.username) } ?: stringResource(R.string.novel_en_community_user), color = NovelTheme.colors.textSecondary, fontSize = 12.sp)
        }
        if (!active) NovelTag(stringResource(R.string.novel_en_not_activated))
    }
}

@Composable private fun QualificationCard(creator: Creator) {
    val c = NovelTheme.colors
    Column(Modifier.fillMaxWidth().background(c.primaryContainer.copy(alpha = .8f), RoundedCornerShape(26.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.novel_en_creator_eligibility), color = c.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Icon(NovelIcons.LockKeyhole, null, Modifier.size(22.dp), tint = c.primary)
        }
        NovelHeading(stringResource(R.string.novel_en_story_starts_here), size = 25)
        Text(stringResource(R.string.novel_en_eligibility_description), color = c.textSecondary, fontSize = 13.sp, lineHeight = 24.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { repeat(3) { i -> Box(Modifier.weight(1f).height(3.dp).background(if (i < creator.completedConditions) c.primary else c.primary.copy(alpha = .15f), CircleShape)) } }
        Text(stringResource(R.string.novel_en_requirements_progress, creator.completedConditions), color = c.primary, fontSize = 12.sp)
    }
}

@Composable private fun QualificationRow(icon: ImageVector, title: String, complete: Boolean, description: String, action: String?, onAction: () -> Unit) {
    val c = NovelTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(34.dp).background(c.primaryContainer.copy(alpha = .6f), CircleShape), contentAlignment = Alignment.Center) { Icon(if (complete) NovelIcons.Check else icon, null, Modifier.size(21.dp), tint = if (complete) c.primary else c.textSecondary) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, color = c.text, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp)
            Text(if (complete) stringResource(R.string.novel_en_complete) else stringResource(R.string.novel_en_incomplete), color = if (complete) c.primary else c.textSecondary, fontSize = 11.sp)
            Text(description, color = c.textSecondary, fontSize = 11.sp, lineHeight = 20.sp)
        }
        if (action != null) NovelPill(action, onAction, primary = false)
    }
    NovelRule()
}

@Composable private fun CreatorStats(stats: org.joinmastodon.android.novel.app.models.Stats) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
        NovelMetric(stats.reads, stringResource(R.string.novel_en_reads), Modifier.weight(1f), stats.readDelta)
        NovelMetric(stats.collections, stringResource(R.string.novel_en_collections), Modifier.weight(1f), stats.collectionDelta)
        NovelMetric(stats.comments, stringResource(R.string.novel_en_comments), Modifier.weight(1f), stats.commentDelta)
    }
}

private fun LazyListScope.creatorInventory(state: NovelAppState, vm: NovelAppViewModel, inventory: NovelCreatorInventory, onCreate: (String?, String?, String) -> Unit, all: Boolean = false) {
    item {
        NovelTabs(listOf("published" to stringResource(R.string.novel_en_published_count, inventory.published.size), "local" to stringResource(R.string.novel_en_local_count, inventory.local.size), "pending" to stringResource(R.string.novel_en_pending_count, inventory.reviewing.size)) + if (all) listOf("all" to stringResource(R.string.novel_en_all)) else emptyList(), state.creatorFilter, vm::setCreatorFilter)
    }
    if (state.creatorFilter == "local") {
        items(inventory.local.sortedByDescending { it.updatedAt }, key = { "local:${it.localId}" }) { local -> LocalWorkRow(local) { onCreate(local.serverId, local.localId, "edit") } }
        item {
            NovelLoadState(state.localWorks, inventory.local.isEmpty(), stringResource(R.string.novel_en_no_local_manuscripts), vm::refresh)
            Text(stringResource(R.string.novel_en_local_manuscript_privacy), color = NovelTheme.colors.textSecondary, fontSize = 11.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 16.dp))
        }
    } else {
        val works = when (state.creatorFilter) { "pending" -> inventory.reviewing; "all" -> inventory.server; else -> inventory.published }
        items(works.sortedByDescending { it.updatedAt }, key = { it.id }) { work -> OwnerWorkRow(work, vm, onCreate, "owner:${work.id}" in state.pending) }
        item { if (works.isEmpty()) NovelLoadState(NovelLoad(value = works), true, if (state.creatorFilter == "pending") stringResource(R.string.novel_en_no_pending_works) else stringResource(R.string.novel_en_no_works), vm::refresh) }
    }
}

@Composable private fun LocalWorkRow(local: LocalNovelSummary, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(70.dp, 98.dp).background(NovelTheme.colors.primaryContainer, RoundedCornerShape(3.dp)).padding(9.dp)) {
            Text(local.title.ifBlank { stringResource(R.string.novel_en_untitled_work) }, fontFamily = NovelTypography.serif, color = NovelTheme.colors.text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NovelHeading(local.title.ifBlank { stringResource(R.string.novel_en_untitled_work) }, size = 19)
            NovelTag(if (local.serverId == null) stringResource(R.string.novel_en_local_draft) else stringResource(R.string.novel_en_local_edit))
            Text(stringResource(R.string.novel_en_chapters_characters, local.chapterCount, local.wordCount.novelNumber()), color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
            Text(stringResource(R.string.novel_en_saved_at, (local.updatedAt / 1000).novelDate()), color = NovelTheme.colors.textSecondary, fontSize = 10.sp)
        }
        NovelIconButton(NovelIcons.Pencil, stringResource(R.string.novel_en_continue_local_edit), onEdit)
    }
    NovelRule()
}

@Composable private fun OwnerWorkRow(work: Work, vm: NovelAppViewModel, onCreate: (String?, String?, String) -> Unit, busy: Boolean) {
    var menu by remember(work.id) { mutableStateOf(false) }
    var action by remember(work.id) { mutableStateOf<String?>(null) }
    NovelWorkRow(work, { onCreate(work.id, null, "edit") }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.novel_en_owner_work_summary, stringResource(work.typeLabelRes), stringResource(work.statusLabelRes), work.chapterCount), color = NovelTheme.colors.primary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Box {
                NovelIconButton(NovelIcons.MoreHorizontal, stringResource(R.string.novel_en_manage_work, work.title), { menu = true }, enabled = !busy)
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.novel_en_manage_chapters_review)) }, onClick = { menu = false; onCreate(work.id, null, "edit") })
                    if (work.status == "published") DropdownMenuItem(text = { Text(stringResource(R.string.novel_en_view_public_work)) }, onClick = { menu = false; vm.openWork(work.id) })
                    if (work.status == "published" && !work.completed) DropdownMenuItem(text = { Text(stringResource(R.string.novel_en_request_completion)) }, onClick = { menu = false; action = "complete" })
                    if (work.status in setOf("published", "pending")) DropdownMenuItem(text = { Text(stringResource(R.string.novel_en_withdraw_work)) }, onClick = { menu = false; action = "withdraw" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.novel_en_delete_server_work)) }, onClick = { menu = false; action = "delete" })
                }
            }
        }
    }
    action?.let { selected -> AlertDialog(onDismissRequest = { action = null }, title = { NovelHeading(when (selected) { "complete" -> stringResource(R.string.novel_en_request_completion_question); "withdraw" -> stringResource(R.string.novel_en_withdraw_work_question); else -> stringResource(R.string.novel_en_delete_server_work_question) }, size = 21) }, text = { Text(if (selected == "complete") stringResource(R.string.novel_en_completion_review_explanation) else stringResource(R.string.novel_en_withdraw_local_retained)) }, confirmButton = { TextButton({ action = null; vm.ownerAction(work.id, selected) }) { Text(stringResource(R.string.novel_en_confirm)) } }, dismissButton = { TextButton({ action = null }) { Text(stringResource(R.string.novel_en_cancel)) } }) }
}

@Composable internal fun NovelOwnerWorksScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit, onCreate: (String?, String?, String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
        item { NovelHeader(stringResource(R.string.novel_en_all_creations), onBack, stringResource(R.string.novel_en_inventories_separate)) { NovelIconButton(NovelIcons.Plus, stringResource(R.string.novel_en_create_work), { onCreate(null, null, "create") }) } }
        creatorInventory(state, vm, NovelCreatorInventory(state.ownerWorks.value.orEmpty(), state.localWorks.value.orEmpty()), onCreate, all = true)
        item { NovelLoadState(state.ownerWorks, false, "", vm::refresh) }
    }
}

@Composable internal fun NovelStatisticsScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
        item {
            NovelHeader(stringResource(R.string.novel_en_work_statistics), onBack, stringResource(R.string.novel_en_account_statistics))
            state.stats.value?.let { stats ->
                NovelSection(stringResource(R.string.novel_en_total_performance)); CreatorStats(stats)
                NovelRule(); NovelSection(stringResource(R.string.novel_en_writing_overview))
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp)) { NovelMetric(stats.works, stringResource(R.string.novel_en_work), Modifier.weight(1f)); NovelMetric(stats.followers, stringResource(R.string.novel_en_followers), Modifier.weight(1f)); NovelMetric(stats.words, stringResource(R.string.novel_en_written_characters), Modifier.weight(1f)) }
                Text(stringResource(R.string.novel_en_delta_availability), color = NovelTheme.colors.textSecondary, fontSize = 11.sp, lineHeight = 20.sp)
            }
            NovelLoadState(state.stats, false, "", vm::refresh)
        }
    }
}

@Composable private fun CreatorRulesLink(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(NovelIcons.ShieldCheck, null, Modifier.size(21.dp), tint = NovelTheme.colors.textSecondary)
        Text(stringResource(R.string.novel_en_safety_center), color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
        NovelLink(stringResource(R.string.novel_en_publishing_rules_link), onClick)
    }
}

@Composable private fun CreatorRulesDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { NovelHeading(stringResource(R.string.novel_en_publishing_guidelines), size = 21) }, text = { Text(stringResource(R.string.novel_en_publishing_rules_body), lineHeight = 25.sp) }, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.novel_en_got_it)) } })
}
