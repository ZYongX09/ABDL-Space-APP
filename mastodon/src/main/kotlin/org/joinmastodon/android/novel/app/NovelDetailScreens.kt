@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.joinmastodon.android.novel.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.novel.app.design.NovelIcons
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.joinmastodon.android.novel.app.models.AuthorPage
import org.joinmastodon.android.novel.app.models.Work

@Composable internal fun NovelDetailScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit, onRead: (Work, String?) -> Unit, onShare: (Work) -> Unit) {
    val work = state.work.value
    var more by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            item {
                NovelHeader("作品详情", onBack) {
                    if (work != null) NovelIconButton(NovelIcons.Upload, "分享作品", { onShare(work) })
                    Box { NovelIconButton(NovelIcons.MoreHorizontal, "作品更多操作", { more = true }, enabled = work != null); DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text("举报作品") }, onClick = { more = false; report = true })
                        DropdownMenuItem(text = { Text("刷新作品") }, onClick = { more = false; vm.refresh() })
                    } }
                }
            }
            if (work != null) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        NovelBookCover(work, width = 98.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            NovelHeading(work.title, size = 27)
                            Text("${work.displayAuthorName} 著", color = NovelTheme.colors.primary, fontSize = 13.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { NovelTag(work.typeLabel); NovelTag(work.statusLabel) }
                            NovelCompactValue(work.wordCount, "字", fontSize = 14, suffix = " 字")
                            Text("${work.chapterCount} 章", color = NovelTheme.colors.textSecondary, fontSize = 12.sp)
                            Text("最近更新 ${work.updatedAt.novelDate()}", color = NovelTheme.colors.textSecondary, fontSize = 10.sp)
                        }
                    }
                    NovelRule()
                    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
                        NovelMetric(work.reads, "次阅读", Modifier.weight(1f))
                        NovelMetric(work.collections, "人收藏", Modifier.weight(1f))
                        NovelMetric(work.comments, "条评论", Modifier.weight(1f).clickable { vm.navigate(NovelPage.Comments(work.id)) })
                    }
                    NovelRule()
                    Row(Modifier.fillMaxWidth().clickable { vm.navigate(NovelPage.AuthorProfile(work.author.id)) }.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NovelAvatar(work.author, 42.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("上传者 · ${work.author.name}", color = NovelTheme.colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            val author = state.author.value?.takeIf { it.author.id == work.author.id }
                            Text(author?.let { "${it.stats.works} 部作品 · ${it.stats.followers.novelNumber()} 位关注者" } ?: "查看上传者主页", color = NovelTheme.colors.textSecondary, fontSize = 10.sp)
                        }
                        state.author.value?.takeIf { it.author.id == work.author.id && it.author.id != state.self?.id }?.let { author ->
                            NovelPill(if (author.following) "已关注" else "+ 关注", { vm.follow(author) }, enabled = "follow:${author.author.id}" !in state.pending, primary = false)
                        }
                    }
                    NovelRule()
                    if (work.statements.isNotEmpty()) {
                        val light = NovelTheme.colors.background.luminance() > .5f
                        Column(Modifier.fillMaxWidth().padding(top = 24.dp).background(if (light) Color(0xFFF2EDE2) else NovelTheme.colors.surfaceVariant, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("作者声明", color = if (light) Color(0xFF795F3D) else NovelTheme.colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            work.statements.forEach { Text("·  $it", color = if (light) Color(0xFF795F3D) else NovelTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 21.sp) }
                        }
                    }
                    NovelSection("简介")
                    Text(work.description.ifBlank { "作者暂未填写简介" }, color = NovelTheme.colors.textSecondary, fontSize = 14.sp, lineHeight = 26.sp)
                    if (work.source.isNotBlank() || work.authorization.isNotBlank()) {
                        NovelSection("来源与授权")
                        if (work.source.isNotBlank()) Text(work.source, color = NovelTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 21.sp)
                        if (work.authorization.isNotBlank()) Text(work.authorization, color = NovelTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 21.sp)
                    }
                    NovelSection("目录", "全部 ${work.chapterCount} 章  ›", { vm.navigate(NovelPage.Chapters(work.id)) })
                }
                val preview = listOfNotNull(work.chapters.firstOrNull(), work.chapters.lastOrNull()).distinctBy { it.id }
                items(preview, key = { "chapter:${it.id}" }) { chapter ->
                    Row(Modifier.fillMaxWidth().clickable { onRead(work, chapter.id) }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(chapter.title, color = NovelTheme.colors.text, fontSize = 13.sp)
                            Text("${chapter.wordCount.novelNumber()} 字", color = NovelTheme.colors.textSecondary, fontSize = 10.sp)
                        }
                        Icon(NovelIcons.ChevronRight, "阅读章节", tint = NovelTheme.colors.textSecondary, modifier = Modifier.size(20.dp))
                    }
                }
                item {
                    if (work.chapters.isEmpty()) Text("当前还没有可阅读章节", color = NovelTheme.colors.textSecondary, fontSize = 12.sp)
                    NovelSection("读者评论", "全部 ${work.comments} 条  ›", { vm.navigate(NovelPage.Comments(work.id)) })
                    state.comments.value?.items?.firstOrNull()?.let { comment ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(comment.author.name, color = NovelTheme.colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(comment.createdAt.novelDate(), color = NovelTheme.colors.textSecondary, fontSize = 10.sp)
                        }
                        Text(comment.body, color = NovelTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 10.dp, bottom = 12.dp))
                    }
                    if (state.comments.value?.items?.isEmpty() == true) Text("还没有评论，来聊聊这个故事", color = NovelTheme.colors.textSecondary, fontSize = 12.sp)
                    NovelLink("写评论", { vm.navigate(NovelPage.Comments(work.id)) }, Modifier.padding(bottom = 16.dp))
                }
            }
            item { NovelLoadState(state.work, false, "", vm::refresh) }
        }
        if (work != null) {
            NovelRule()
            BoxWithConstraints(Modifier.fillMaxWidth().background(NovelTheme.colors.background)) {
                val compactActions = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.3f
                Row(Modifier.fillMaxWidth().testTag("novel-detail-actions").padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (compactActions) {
                        IconToggleButton(checked = work.inShelf, onCheckedChange = { vm.collect(work) },
                            enabled = "shelf:${work.id}" !in state.pending,
                            modifier = Modifier.size(48.dp).testTag("novel-detail-shelf-action")
                                .background(NovelTheme.colors.primaryContainer, RoundedCornerShape(24.dp))
                                .semantics { contentDescription = if (work.inShelf) "移出书架" else "加入书架" }) {
                            Icon(if (work.inShelf) NovelIcons.BookmarkCheck else NovelIcons.BookmarkPlus, null, tint = NovelTheme.colors.primary, modifier = Modifier.size(22.dp))
                        }
                    } else {
                        NovelPill(if (work.inShelf) "已在书架" else "加入书架", { vm.collect(work) }, Modifier.weight(1f), primary = false, enabled = "shelf:${work.id}" !in state.pending, icon = NovelIcons.Bookmark)
                    }
                    NovelPill(if (work.progress == null) "开始阅读" else "继续阅读", { onRead(work, null) },
                        Modifier.weight(if (compactActions) 1f else 1.5f).testTag("novel-detail-read-action"), enabled = work.chapters.isNotEmpty(), icon = NovelIcons.BookOpen)
                }
            }
        }
    }
    if (report && work != null) NovelReportDialog({ report = false }, { reason -> report = false; vm.report(work.id, null, reason) })
}

@Composable internal fun NovelChaptersScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit, onRead: (Work, String?) -> Unit) {
    val work = state.work.value
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp)) {
        item { NovelHeader("全部目录", onBack, work?.title) }
        items(work?.chapters.orEmpty(), key = { it.id }) { chapter ->
            Column {
                Row(Modifier.fillMaxWidth().clickable { work?.let { onRead(it, chapter.id) } }.padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(chapter.title, color = NovelTheme.colors.text, fontSize = 14.sp)
                        Text("${chapter.wordCount.novelNumber()} 字${if (chapter.id == work?.progress?.chapterId) " · 上次读到" else ""}", color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
                    }
                    Icon(NovelIcons.ChevronRight, "打开章节", tint = NovelTheme.colors.textSecondary, modifier = Modifier.size(19.dp))
                }
                NovelRule()
            }
        }
        item { NovelLoadState(state.work, work?.chapters.isNullOrEmpty(), "暂无公开章节", vm::refresh) }
    }
}

@Composable internal fun NovelAuthorScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit, onShare: (AuthorPage) -> Unit) {
    val author = state.author.value
    var newestFirst by remember { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
        item { NovelHeader("作者主页", onBack) { if (author != null) NovelIconButton(NovelIcons.Upload, "分享作者", { onShare(author) }) } }
        if (author != null) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    NovelAvatar(author.author, 64.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NovelHeading(author.author.name, size = 28)
                        Text("上传者账号 · @${author.author.username}", color = NovelTheme.colors.textSecondary, fontSize = 12.sp)
                    }
                    if (author.author.id != state.self?.id) NovelPill(if (author.following) "已关注" else "+ 关注", { vm.follow(author) }, enabled = "follow:${author.author.id}" !in state.pending)
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 24.dp)) {
                    NovelMetric(author.stats.works, "公开作品", Modifier.weight(1f)); NovelMetric(author.stats.followers, "关注者", Modifier.weight(1f)); NovelMetric(author.stats.reads, "累计阅读", Modifier.weight(1f))
                }
                NovelRule()
                NovelSection("作品 · ${author.items.size}", if (newestFirst) "最近更新 ↓" else "最早更新 ↑", { newestFirst = !newestFirst })
            }
            items(if (newestFirst) author.items.sortedByDescending { it.updatedAt } else author.items.sortedBy { it.updatedAt }, key = { it.id }) { work -> NovelWorkRow(work, { vm.openWork(work.id) }) { Text("${work.statusLabel} · ${work.wordCount.novelNumber()}字 · ${work.chapterCount}章", color = NovelTheme.colors.primary, fontSize = 10.sp) } }
            item { NovelSection("近期更新") }
            items(author.items.sortedByDescending { it.updatedAt }.take(10), key = { "update:${it.id}" }) { work ->
                Column(Modifier.fillMaxWidth().clickable { vm.openWork(work.id) }.padding(start = 12.dp, top = 10.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(work.updatedAt.novelDate(), color = NovelTheme.colors.textSecondary, fontSize = 10.sp)
                    Text("《${work.title}》 · ${work.statusLabel}", color = NovelTheme.colors.text, fontSize = 13.sp)
                    Text("当前 ${work.chapterCount} 章", color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
                }
            }
        }
        item { NovelLoadState(state.author, author?.items.isNullOrEmpty(), "暂无公开作品", vm::refresh) }
    }
}
