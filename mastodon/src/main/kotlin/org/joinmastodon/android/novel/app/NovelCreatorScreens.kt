package org.joinmastodon.android.novel.app

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
        item { NovelHeader("创作者中心", onBack) { NovelIconButton(if (creator?.active == true) NovelIcons.Settings2 else NovelIcons.Info, "创作与发布须知", { rules = true }) } }
        if (creator != null) {
            if (creator.active) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp).background(NovelTheme.colors.primaryContainer.copy(alpha = .45f), RoundedCornerShape(26.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        CreatorIdentity(state, true)
                        Row(Modifier.fillMaxWidth()) {
                            NovelMetric(creator.stats.works, "已发布作品", Modifier.weight(1f))
                            NovelMetric(creator.stats.followers, "关注者", Modifier.weight(1f))
                            NovelMetric(creator.stats.words, "累计创作字数", Modifier.weight(1.3f))
                        }
                        NovelRule()
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NovelPill("新建作品", { onCreate(null, null, "create") }, icon = NovelIcons.Plus)
                            NovelPill("上传文稿", { onCreate(null, null, "import") }, primary = false, icon = NovelIcons.Upload)
                        }
                    }
                    NovelSection("作品数据", "全部数据  ›", { vm.navigate(NovelPage.Statistics) })
                    CreatorStats(creator.stats)
                    NovelSection("我的作品", "全部作品  ›", { vm.navigate(NovelPage.OwnerWorks) })
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
                    NovelSection("开通条件")
                    QualificationRow(NovelIcons.BadgeCheck, "完成“宝宝认证”", creator.babyVerified,
                        "仅用于社群身份核验，认证信息不公开。", if (!creator.babyVerified) "去认证" else null, onVerify)
                    QualificationRow(NovelIcons.Clock, "注册时间满 72 小时", creator.accountAgeEligible,
                        if (creator.registeredAt.isBlank()) "由服务器核验账号注册时间。" else "注册于 ${creator.registeredAt}", null, {})
                    QualificationRow(NovelIcons.Heart, "至少 ${creator.requiredPosts} 条帖子点赞数达到 ${creator.requiredLikes} 个", creator.qualifyingPosts >= creator.requiredPosts,
                        "每条帖子需至少 ${creator.requiredLikes} 个赞，当前 ${creator.qualifyingPosts} 条已达标。", if (creator.qualifyingPosts < creator.requiredPosts) "去发帖" else null, onPost)
                    Spacer(Modifier.height(22.dp))
                    NovelPill(if ("apply" in state.pending) "正在开通…" else "开通创作者身份", vm::applyCreator, Modifier.fillMaxWidth(),
                        enabled = creator.eligible && creator.completedConditions == 3 && !state.creator.loading && "apply" !in state.pending, icon = NovelIcons.LockKeyhole)
                    NovelLink("已完成？重新检查资格", vm::refresh, Modifier.fillMaxWidth().padding(top = 12.dp))
                    Text("所有条件满足后可主动开通；未开通不影响阅读与收藏。", color = NovelTheme.colors.textSecondary, fontSize = 11.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 18.dp))
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
            NovelHeading(state.self?.name ?: "当前账号", size = 24)
            Text(if (active) "已开通创作者" else state.self?.let { "@${it.username} · 18+ 社群用户" } ?: "18+ 社群用户", color = NovelTheme.colors.textSecondary, fontSize = 12.sp)
        }
        if (!active) NovelTag("待开通")
    }
}

@Composable private fun QualificationCard(creator: Creator) {
    val c = NovelTheme.colors
    Column(Modifier.fillMaxWidth().background(c.primaryContainer.copy(alpha = .8f), RoundedCornerShape(26.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("创作者资格", color = c.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Icon(NovelIcons.LockKeyhole, null, Modifier.size(22.dp), tint = c.primary)
        }
        NovelHeading("让故事，从这里开始。", size = 25)
        Text("完成以下全部条件，即可开通创作者身份，上传作品并管理章节。", color = c.textSecondary, fontSize = 13.sp, lineHeight = 24.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { repeat(3) { i -> Box(Modifier.weight(1f).height(3.dp).background(if (i < creator.completedConditions) c.primary else c.primary.copy(alpha = .15f), CircleShape)) } }
        Text("已完成 ${creator.completedConditions} / 3 项", color = c.primary, fontSize = 12.sp)
    }
}

@Composable private fun QualificationRow(icon: ImageVector, title: String, complete: Boolean, description: String, action: String?, onAction: () -> Unit) {
    val c = NovelTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(34.dp).background(c.primaryContainer.copy(alpha = .6f), CircleShape), contentAlignment = Alignment.Center) { Icon(if (complete) NovelIcons.Check else icon, null, Modifier.size(21.dp), tint = if (complete) c.primary else c.textSecondary) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, color = c.text, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp)
            Text(if (complete) "已完成" else "未完成", color = if (complete) c.primary else c.textSecondary, fontSize = 11.sp)
            Text(description, color = c.textSecondary, fontSize = 11.sp, lineHeight = 20.sp)
        }
        if (action != null) NovelPill(action, onAction, primary = false)
    }
    NovelRule()
}

@Composable private fun CreatorStats(stats: org.joinmastodon.android.novel.app.models.Stats) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
        NovelMetric(stats.reads, "阅读", Modifier.weight(1f), stats.readDelta)
        NovelMetric(stats.collections, "收藏", Modifier.weight(1f), stats.collectionDelta)
        NovelMetric(stats.comments, "评论", Modifier.weight(1f), stats.commentDelta)
    }
}

private fun LazyListScope.creatorInventory(state: NovelAppState, vm: NovelAppViewModel, inventory: NovelCreatorInventory, onCreate: (String?, String?, String) -> Unit, all: Boolean = false) {
    item {
        NovelTabs(listOf("published" to "已发布 ${inventory.published.size}", "local" to "本地稿 ${inventory.local.size}", "pending" to "审核中 ${inventory.reviewing.size}") + if (all) listOf("all" to "全部") else emptyList(), state.creatorFilter, vm::setCreatorFilter)
    }
    if (state.creatorFilter == "local") {
        items(inventory.local.sortedByDescending { it.updatedAt }, key = { "local:${it.localId}" }) { local -> LocalWorkRow(local) { onCreate(local.serverId, local.localId, "edit") } }
        item {
            NovelLoadState(state.localWorks, inventory.local.isEmpty(), "本机还没有创作稿", vm::refresh)
            Text("本地稿仅保存在当前账号的设备内，不会自动上传。已关联的作品可继续本机编辑。", color = NovelTheme.colors.textSecondary, fontSize = 11.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 16.dp))
        }
    } else {
        val works = when (state.creatorFilter) { "pending" -> inventory.reviewing; "all" -> inventory.server; else -> inventory.published }
        items(works.sortedByDescending { it.updatedAt }, key = { it.id }) { work -> OwnerWorkRow(work, vm, onCreate, "owner:${work.id}" in state.pending) }
        item { if (works.isEmpty()) NovelLoadState(NovelLoad(value = works), true, if (state.creatorFilter == "pending") "暂无审核中的作品" else "暂无作品", vm::refresh) }
    }
}

@Composable private fun LocalWorkRow(local: LocalNovelSummary, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(70.dp, 98.dp).background(NovelTheme.colors.primaryContainer, RoundedCornerShape(3.dp)).padding(9.dp)) {
            Text(local.title.ifBlank { "未命名作品" }, fontFamily = NovelTypography.serif, color = NovelTheme.colors.text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NovelHeading(local.title.ifBlank { "未命名作品" }, size = 19)
            NovelTag(if (local.serverId == null) "本地草稿" else "本机编辑稿")
            Text("${local.chapterCount} 章 · ${local.wordCount.novelNumber()} 字", color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
            Text("保存于 ${(local.updatedAt / 1000).novelDate()}", color = NovelTheme.colors.textSecondary, fontSize = 10.sp)
        }
        NovelIconButton(NovelIcons.Pencil, "继续编辑本地稿", onEdit)
    }
    NovelRule()
}

@Composable private fun OwnerWorkRow(work: Work, vm: NovelAppViewModel, onCreate: (String?, String?, String) -> Unit, busy: Boolean) {
    var menu by remember(work.id) { mutableStateOf(false) }
    var action by remember(work.id) { mutableStateOf<String?>(null) }
    NovelWorkRow(work, { onCreate(work.id, null, "edit") }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${work.typeLabel} · ${work.statusLabel} · ${work.chapterCount} 章", color = NovelTheme.colors.primary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Box {
                NovelIconButton(NovelIcons.MoreHorizontal, "管理《${work.title}》", { menu = true }, enabled = !busy)
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("管理章节与审核") }, onClick = { menu = false; onCreate(work.id, null, "edit") })
                    if (work.status == "published") DropdownMenuItem(text = { Text("查看公开作品") }, onClick = { menu = false; vm.openWork(work.id) })
                    if (work.status == "published" && !work.completed) DropdownMenuItem(text = { Text("申请标记完结") }, onClick = { menu = false; action = "complete" })
                    if (work.status in setOf("published", "pending")) DropdownMenuItem(text = { Text("撤下作品") }, onClick = { menu = false; action = "withdraw" })
                    DropdownMenuItem(text = { Text("删除服务端作品") }, onClick = { menu = false; action = "delete" })
                }
            }
        }
    }
    action?.let { selected -> AlertDialog(onDismissRequest = { action = null }, title = { NovelHeading(when (selected) { "complete" -> "申请标记完结？"; "withdraw" -> "撤下作品？"; else -> "删除服务端作品？" }, size = 21) }, text = { Text(if (selected == "complete") "完结信息将提交人工审核，通过前不会改变公开版本。" else "作品将不再公开。此操作不会删除本地稿或私人书库。") }, confirmButton = { TextButton({ action = null; vm.ownerAction(work.id, selected) }) { Text("确认") } }, dismissButton = { TextButton({ action = null }) { Text("取消") } }) }
}

@Composable internal fun NovelOwnerWorksScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit, onCreate: (String?, String?, String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
        item { NovelHeader("全部创作", onBack, "公开版本、审核快照与本地稿分别管理") { NovelIconButton(NovelIcons.Plus, "新建作品", { onCreate(null, null, "create") }) } }
        creatorInventory(state, vm, NovelCreatorInventory(state.ownerWorks.value.orEmpty(), state.localWorks.value.orEmpty()), onCreate, all = true)
        item { NovelLoadState(state.ownerWorks, false, "", vm::refresh) }
    }
}

@Composable internal fun NovelStatisticsScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
        item {
            NovelHeader("作品数据", onBack, "当前账号的创作统计")
            state.stats.value?.let { stats ->
                NovelSection("累计表现"); CreatorStats(stats)
                NovelRule(); NovelSection("创作概况")
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp)) { NovelMetric(stats.works, "作品", Modifier.weight(1f)); NovelMetric(stats.followers, "关注者", Modifier.weight(1f)); NovelMetric(stats.words, "创作字数", Modifier.weight(1f)) }
                Text("仅在服务器提供可比数据时显示较上周变化。", color = NovelTheme.colors.textSecondary, fontSize = 11.sp, lineHeight = 20.sp)
            }
            NovelLoadState(state.stats, false, "", vm::refresh)
        }
    }
}

@Composable private fun CreatorRulesLink(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(NovelIcons.ShieldCheck, null, Modifier.size(21.dp), tint = NovelTheme.colors.textSecondary)
        Text("ABDL Space 安全中心", color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
        NovelLink("发布规范  ›", onClick)
    }
}

@Composable private fun CreatorRulesDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { NovelHeading("创作与发布须知", size = 21) }, text = { Text("仅提交有权发布的内容；转载必须标明来源与授权，AI 辅助须明确标识。不得发布违法、侵权、侵犯隐私的内容。请如实填写年龄限制和内容提示。所有公开内容经过人工审核，本地稿不会自动上传；审核通过前不会替换已公开版本。", lineHeight = 25.sp) }, confirmButton = { TextButton(onDismiss) { Text("我知道了") } })
}
