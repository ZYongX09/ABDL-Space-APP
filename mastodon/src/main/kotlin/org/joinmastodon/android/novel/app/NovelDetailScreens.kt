@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.joinmastodon.android.novel.app

import org.joinmastodon.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

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
                NovelHeader(stringResource(R.string.novel_en_work_details), onBack) {
                    if (work != null) NovelIconButton(NovelIcons.Upload, stringResource(R.string.novel_en_share_work), { onShare(work) })
                    Box { NovelIconButton(NovelIcons.MoreHorizontal, stringResource(R.string.novel_en_work_more_actions), { more = true }, enabled = work != null); DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.novel_en_report_work)) }, onClick = { more = false; report = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.novel_en_refresh_work)) }, onClick = { more = false; vm.refresh() })
                    } }
                }
            }
            if (work != null) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        NovelBookCover(work, width = 98.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            NovelHeading(work.title, size = 27)
                            Text(stringResource(R.string.novel_en_by_author, work.displayAuthorName(androidx.compose.ui.platform.LocalContext.current)), color = NovelTheme.colors.primary, fontSize = 13.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { NovelTag(stringResource(work.typeLabelRes)); NovelTag(stringResource(work.statusLabelRes)) }
                            NovelCompactValue(work.wordCount, stringResource(R.string.novel_en_characters), fontSize = 14, suffix = stringResource(R.string.novel_en_characters_suffix))
                            Text(pluralStringResource(R.plurals.novel_en_chapter_count, work.chapterCount, work.chapterCount), color = NovelTheme.colors.textSecondary, fontSize = 12.sp)
                            Text(stringResource(R.string.novel_en_last_updated, work.updatedAt.novelDate()), color = NovelTheme.colors.textSecondary, fontSize = 10.sp)
                        }
                    }
                    NovelRule()
                    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
                        NovelMetric(work.reads, stringResource(R.string.novel_en_read_count_label), Modifier.weight(1f))
                        NovelMetric(work.collections, stringResource(R.string.novel_en_collection_count_label), Modifier.weight(1f))
                        NovelMetric(work.comments, stringResource(R.string.novel_en_comment_count_label), Modifier.weight(1f).clickable { vm.navigate(NovelPage.Comments(work.id)) })
                    }
                    NovelRule()
                    Row(Modifier.fillMaxWidth().clickable { vm.navigate(NovelPage.AuthorProfile(work.author.id)) }.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NovelAvatar(work.author, 42.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.novel_en_uploader_byline, work.author.name), color = NovelTheme.colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            val author = state.author.value?.takeIf { it.author.id == work.author.id }
                            Text(author?.let { stringResource(R.string.novel_en_author_counts, it.stats.works, it.stats.followers.novelNumber()) } ?: stringResource(R.string.novel_en_view_uploader_profile), color = NovelTheme.colors.textSecondary, fontSize = 10.sp)
                        }
                        state.author.value?.takeIf { it.author.id == work.author.id && it.author.id != state.self?.id }?.let { author ->
                            NovelPill(if (author.following) stringResource(R.string.novel_en_following) else stringResource(R.string.novel_en_follow), { vm.follow(author) }, enabled = "follow:${author.author.id}" !in state.pending, primary = false)
                        }
                    }
                    NovelRule()
                    if (work.statements.isNotEmpty()) {
                        val light = NovelTheme.colors.background.luminance() > .5f
                        Column(Modifier.fillMaxWidth().padding(top = 24.dp).background(if (light) Color(0xFFF2EDE2) else NovelTheme.colors.surfaceVariant, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.novel_en_author_statements), color = if (light) Color(0xFF795F3D) else NovelTheme.colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            work.statements.forEach { Text("·  $it", color = if (light) Color(0xFF795F3D) else NovelTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 21.sp) }
                        }
                    }
                    NovelSection(stringResource(R.string.novel_en_description))
                    Text(work.description.ifBlank { stringResource(R.string.novel_en_no_description) }, color = NovelTheme.colors.textSecondary, fontSize = 14.sp, lineHeight = 26.sp)
                    if (work.source.isNotBlank() || work.authorization.isNotBlank()) {
                        NovelSection(stringResource(R.string.novel_en_source_permission))
                        if (work.source.isNotBlank()) Text(work.source, color = NovelTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 21.sp)
                        if (work.authorization.isNotBlank()) Text(work.authorization, color = NovelTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 21.sp)
                    }
                    NovelSection(stringResource(R.string.novel_en_contents), pluralStringResource(R.plurals.novel_en_all_chapters_link, work.chapterCount, work.chapterCount), { vm.navigate(NovelPage.Chapters(work.id)) })
                }
                val preview = listOfNotNull(work.chapters.firstOrNull(), work.chapters.lastOrNull()).distinctBy { it.id }
                items(preview, key = { "chapter:${it.id}" }) { chapter ->
                    Row(Modifier.fillMaxWidth().clickable { onRead(work, chapter.id) }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(chapter.title, color = NovelTheme.colors.text, fontSize = 13.sp)
                            Text(pluralStringResource(R.plurals.novel_en_character_count, chapter.wordCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), chapter.wordCount.novelNumber()), color = NovelTheme.colors.textSecondary, fontSize = 10.sp)
                        }
                        Icon(NovelIcons.ChevronRight, stringResource(R.string.novel_en_read_chapter), tint = NovelTheme.colors.textSecondary, modifier = Modifier.size(20.dp))
                    }
                }
                item {
                    if (work.chapters.isEmpty()) Text(stringResource(R.string.novel_en_no_readable_chapters), color = NovelTheme.colors.textSecondary, fontSize = 12.sp)
                    NovelSection(stringResource(R.string.novel_en_reader_comments), pluralStringResource(R.plurals.novel_en_all_comments_link, work.comments.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), work.comments), { vm.navigate(NovelPage.Comments(work.id)) })
                    state.comments.value?.items?.firstOrNull()?.let { comment ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(comment.author.name, color = NovelTheme.colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(comment.createdAt.novelDate(), color = NovelTheme.colors.textSecondary, fontSize = 10.sp)
                        }
                        Text(comment.body, color = NovelTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 10.dp, bottom = 12.dp))
                    }
                    if (state.comments.value?.items?.isEmpty() == true) Text(stringResource(R.string.novel_en_first_comment_invitation), color = NovelTheme.colors.textSecondary, fontSize = 12.sp)
                    NovelLink(stringResource(R.string.novel_en_write_comment), { vm.navigate(NovelPage.Comments(work.id)) }, Modifier.padding(bottom = 16.dp))
                }
            }
            item { NovelLoadState(state.work, false, "", vm::refresh) }
        }
        if (work != null) {
            NovelRule()
            BoxWithConstraints(Modifier.fillMaxWidth().background(NovelTheme.colors.background)) {
                val shelfDescription = stringResource(if (work.inShelf) R.string.novel_en_remove_from_shelf else R.string.novel_en_add_to_shelf)
                val compactActions = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.3f
                Row(Modifier.fillMaxWidth().testTag("novel-detail-actions").padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (compactActions) {
                        IconToggleButton(checked = work.inShelf, onCheckedChange = { vm.collect(work) },
                            enabled = "shelf:${work.id}" !in state.pending,
                            modifier = Modifier.size(48.dp).testTag("novel-detail-shelf-action")
                                .background(NovelTheme.colors.primaryContainer, RoundedCornerShape(24.dp))
                                .semantics { contentDescription = shelfDescription }) {
                            Icon(if (work.inShelf) NovelIcons.BookmarkCheck else NovelIcons.BookmarkPlus, null, tint = NovelTheme.colors.primary, modifier = Modifier.size(22.dp))
                        }
                    } else {
                        NovelPill(if (work.inShelf) stringResource(R.string.novel_en_on_shelf) else stringResource(R.string.novel_en_add_to_shelf), { vm.collect(work) }, Modifier.weight(1f), primary = false, enabled = "shelf:${work.id}" !in state.pending, icon = NovelIcons.Bookmark)
                    }
                    NovelPill(if (work.progress == null) stringResource(R.string.novel_en_start_reading) else stringResource(R.string.novel_en_continue_reading), { onRead(work, null) },
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
        item { NovelHeader(stringResource(R.string.novel_en_all_chapters), onBack, work?.title) }
        items(work?.chapters.orEmpty(), key = { it.id }) { chapter ->
            Column {
                Row(Modifier.fillMaxWidth().clickable { work?.let { onRead(it, chapter.id) } }.padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(chapter.title, color = NovelTheme.colors.text, fontSize = 14.sp)
                        Text(stringResource(R.string.novel_en_chapter_resume_summary, chapter.wordCount.novelNumber(), if (chapter.id == work?.progress?.chapterId) stringResource(R.string.novel_en_last_read_suffix) else ""), color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
                    }
                    Icon(NovelIcons.ChevronRight, stringResource(R.string.novel_en_open_chapter), tint = NovelTheme.colors.textSecondary, modifier = Modifier.size(19.dp))
                }
                NovelRule()
            }
        }
        item { NovelLoadState(state.work, work?.chapters.isNullOrEmpty(), stringResource(R.string.novel_en_no_public_chapters), vm::refresh) }
    }
}

@Composable internal fun NovelAuthorScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit, onShare: (AuthorPage) -> Unit) {
    val author = state.author.value
    var newestFirst by remember { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
        item { NovelHeader(stringResource(R.string.novel_en_author_profile), onBack) { if (author != null) NovelIconButton(NovelIcons.Upload, stringResource(R.string.novel_en_share_author), { onShare(author) }) } }
        if (author != null) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    NovelAvatar(author.author, 64.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NovelHeading(author.author.name, size = 28)
                        Text(stringResource(R.string.novel_en_uploader_account, author.author.username), color = NovelTheme.colors.textSecondary, fontSize = 12.sp)
                    }
                    if (author.author.id != state.self?.id) NovelPill(if (author.following) stringResource(R.string.novel_en_following) else stringResource(R.string.novel_en_follow), { vm.follow(author) }, enabled = "follow:${author.author.id}" !in state.pending)
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 24.dp)) {
                    NovelMetric(author.stats.works, stringResource(R.string.novel_en_public_works), Modifier.weight(1f)); NovelMetric(author.stats.followers, stringResource(R.string.novel_en_followers), Modifier.weight(1f)); NovelMetric(author.stats.reads, stringResource(R.string.novel_en_total_reads), Modifier.weight(1f))
                }
                NovelRule()
                NovelSection(stringResource(R.string.novel_en_works_heading, author.items.size), if (newestFirst) stringResource(R.string.novel_en_newest_first) else stringResource(R.string.novel_en_oldest_first), { newestFirst = !newestFirst })
            }
            items(if (newestFirst) author.items.sortedByDescending { it.updatedAt } else author.items.sortedBy { it.updatedAt }, key = { it.id }) { work -> NovelWorkRow(work, { vm.openWork(work.id) }) { Text(stringResource(R.string.novel_en_public_work_summary, stringResource(work.statusLabelRes), work.wordCount.novelNumber(), work.chapterCount), color = NovelTheme.colors.primary, fontSize = 10.sp) } }
            item { NovelSection(stringResource(R.string.novel_en_recent_updates)) }
            items(author.items.sortedByDescending { it.updatedAt }.take(10), key = { "update:${it.id}" }) { work ->
                Column(Modifier.fillMaxWidth().clickable { vm.openWork(work.id) }.padding(start = 12.dp, top = 10.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(work.updatedAt.novelDate(), color = NovelTheme.colors.textSecondary, fontSize = 10.sp)
                    Text("《${work.title}》 · ${stringResource(work.statusLabelRes)}", color = NovelTheme.colors.text, fontSize = 13.sp)
                    Text(pluralStringResource(R.plurals.novel_en_current_chapters, work.chapterCount, work.chapterCount), color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
                }
            }
        }
        item { NovelLoadState(state.author, author?.items.isNullOrEmpty(), stringResource(R.string.novel_en_no_public_works), vm::refresh) }
    }
}
