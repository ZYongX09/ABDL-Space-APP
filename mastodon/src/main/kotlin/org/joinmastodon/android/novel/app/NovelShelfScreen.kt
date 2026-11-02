package org.joinmastodon.android.novel.app

import org.joinmastodon.android.R
import androidx.compose.ui.res.stringResource

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.novel.app.design.NovelIcons
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.joinmastodon.android.novel.app.models.Work

@Composable internal fun NovelShelfScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit, onRead: (Work, String?) -> Unit) {
    var managing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val shelf = state.shelf.value
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = LocalNovelBottomPadding.current + 4.dp)) {
        item {
            NovelHeader(stringResource(R.string.novel_en_shelf), onBack, stringResource(R.string.novel_en_shelf_subtitle)) {
                NovelIconButton(NovelIcons.Search, stringResource(R.string.novel_en_search_novels), { vm.navigate(NovelPage.Search) })
                Box { NovelIconButton(NovelIcons.MoreHorizontal, stringResource(R.string.novel_en_shelf_more), { menu = true }); DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.novel_en_refresh_shelf)) }, onClick = { menu = false; vm.refresh() })
                } }
            }
            Spacer(Modifier.height(12.dp))
            shelf?.continueWork?.let { work ->
                Column(Modifier.fillMaxWidth().background(NovelTheme.colors.primaryContainer.copy(alpha = .72f), RoundedCornerShape(26.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.novel_en_continue_reading), color = NovelTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(work.progress?.updatedAt?.novelDate().orEmpty(), color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(17.dp), verticalAlignment = Alignment.CenterVertically) {
                        NovelBookCover(work, width = 78.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            NovelHeading(work.title, size = 23)
                            Text("${work.displayAuthorName(androidx.compose.ui.platform.LocalContext.current)} / ${stringResource(work.typeLabelRes)}", color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
                            Text(work.chapters.firstOrNull { it.id == work.progress?.chapterId }?.title ?: stringResource(R.string.novel_en_resume_chapter), color = NovelTheme.colors.text, fontSize = 12.sp)
                            LinearProgressIndicator(progress = { ((work.progress?.percent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = NovelTheme.colors.primary, trackColor = NovelTheme.colors.outline)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.novel_en_reading_progress_status, (work.progress?.percent ?: 0.0).toInt(), stringResource(work.statusLabelRes)), color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
                        NovelPill(stringResource(R.string.novel_en_read_on), { onRead(work, null) }, icon = NovelIcons.BookOpen)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { NovelTabs(listOf("all" to stringResource(R.string.novel_en_all_count, shelf?.items?.size ?: 0), "updates" to stringResource(R.string.novel_en_with_updates), "completed" to stringResource(R.string.novel_en_finished_reading)), state.shelfFilter, vm::setShelfFilter) }
                NovelLink(if (managing) stringResource(R.string.novel_en_done) else stringResource(R.string.novel_en_manage), { managing = !managing })
            }
        }
        items(state.shelfItems.chunked(3), key = { it.first().id }) { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { work ->
                    Column(Modifier.weight(1f).clickable { vm.openWork(work.id) }, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        NovelBookCover(work, Modifier.fillMaxWidth(), width = 110.dp)
                        Text(work.title, color = NovelTheme.colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                        Text(when { work.progress?.completed == true -> stringResource(R.string.novel_en_finished_reading); work.progress != null -> stringResource(R.string.novel_en_reading_progress, work.progress.percent.toInt()); else -> stringResource(R.string.novel_en_not_started) }, color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
                        if (work.hasUpdate) Text(stringResource(R.string.novel_en_new_version), color = NovelTheme.colors.primary, fontSize = 10.sp)
                        if (managing) NovelPill(stringResource(R.string.novel_en_remove), { vm.collect(work.copy(inShelf = true)) }, enabled = "shelf:${work.id}" !in state.pending, primary = false, icon = NovelIcons.Trash)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item { NovelLoadState(state.shelf, state.shelfItems.isEmpty(), if (state.shelfFilter == "all") stringResource(R.string.novel_en_shelf_empty) else stringResource(R.string.novel_en_category_empty), vm::refresh) }
    }
}
