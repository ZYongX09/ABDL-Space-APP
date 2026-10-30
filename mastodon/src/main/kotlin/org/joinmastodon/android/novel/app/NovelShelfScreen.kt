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
            NovelHeader("书架", onBack, "收藏喜欢的故事，留住阅读的片刻") {
                NovelIconButton(NovelIcons.Search, "搜索小说", { vm.navigate(NovelPage.Search) })
                Box { NovelIconButton(NovelIcons.MoreHorizontal, "书架更多", { menu = true }); DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("刷新书架") }, onClick = { menu = false; vm.refresh() })
                } }
            }
            Spacer(Modifier.height(12.dp))
            shelf?.continueWork?.let { work ->
                Column(Modifier.fillMaxWidth().background(NovelTheme.colors.primaryContainer.copy(alpha = .72f), RoundedCornerShape(26.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("继续阅读", color = NovelTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(work.progress?.updatedAt?.novelDate().orEmpty(), color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(17.dp), verticalAlignment = Alignment.CenterVertically) {
                        NovelBookCover(work, width = 78.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            NovelHeading(work.title, size = 23)
                            Text("${work.displayAuthorName} / ${work.typeLabel}", color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
                            Text(work.chapters.firstOrNull { it.id == work.progress?.chapterId }?.title ?: "继续上次的章节", color = NovelTheme.colors.text, fontSize = 12.sp)
                            LinearProgressIndicator(progress = { ((work.progress?.percent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = NovelTheme.colors.primary, trackColor = NovelTheme.colors.outline)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("已读 ${(work.progress?.percent ?: 0.0).toInt()}% · ${work.statusLabel}", color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
                        NovelPill("接着读", { onRead(work, null) }, icon = NovelIcons.BookOpen)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { NovelTabs(listOf("all" to "全部 ${shelf?.items?.size ?: 0}", "updates" to "有更新", "completed" to "已读完"), state.shelfFilter, vm::setShelfFilter) }
                NovelLink(if (managing) "完成" else "管理", { managing = !managing })
            }
        }
        items(state.shelfItems.chunked(3), key = { it.first().id }) { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { work ->
                    Column(Modifier.weight(1f).clickable { vm.openWork(work.id) }, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        NovelBookCover(work, Modifier.fillMaxWidth(), width = 110.dp)
                        Text(work.title, color = NovelTheme.colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                        Text(when { work.progress?.completed == true -> "已读完"; work.progress != null -> "已读 ${work.progress.percent.toInt()}%"; else -> "尚未开始" }, color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
                        if (work.hasUpdate) Text("有新版本", color = NovelTheme.colors.primary, fontSize = 10.sp)
                        if (managing) NovelPill("移除", { vm.collect(work.copy(inShelf = true)) }, enabled = "shelf:${work.id}" !in state.pending, primary = false, icon = NovelIcons.Trash)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item { NovelLoadState(state.shelf, state.shelfItems.isEmpty(), if (state.shelfFilter == "all") "书架还是空的" else "这个分类下暂无作品", vm::refresh) }
    }
}
