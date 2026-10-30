package org.joinmastodon.android.novel.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.novel.app.design.NovelIcons
import org.joinmastodon.android.novel.app.design.NovelTheme

private val StoreTypes = listOf("all" to "精选", "original" to "原创", "repost" to "搬运", "ai" to "AI创作")
private val SearchTypes = listOf("all" to "全部", "original" to "原创", "repost" to "搬运", "ai" to "AI创作")
private val Sorts = listOf("featured" to "综合排序", "updated" to "最近更新", "popular" to "最多阅读", "collections" to "最多收藏")

@Composable internal fun NovelStoreScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = LocalNovelBottomPadding.current + 4.dp)) {
        item { NovelHeader("小说", onBack, "18+ ABDL 社群 · 用户上传小说") { NovelIconButton(NovelIcons.Bell, "小说消息", { vm.navigate(NovelPage.Messages) }) } }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp).height(52.dp).background(NovelTheme.colors.surfaceVariant.copy(alpha = .45f), RoundedCornerShape(28.dp)).clickable { vm.navigate(NovelPage.Search) }.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(NovelIcons.Search, null, Modifier.size(21.dp), tint = NovelTheme.colors.textSecondary)
                Text("搜索作品、作者、标签", color = NovelTheme.colors.textSecondary, fontSize = 14.sp)
            }
            NovelTabs(StoreTypes, state.storeType, vm::setStoreType, underline = true)
            Spacer(Modifier.height(10.dp))
        }
        items(state.works.value?.items.orEmpty(), key = { it.id }) { work -> NovelWorkRow(work, { vm.openWork(work.id) }) }
        item { NovelLoadState(state.works, state.works.value?.items.isNullOrEmpty(), "暂无公开作品", vm::refresh) }
        item {
            if (state.works.value?.nextCursor != null) NovelLink("加载更多作品", { vm.loadMoreWorks() }, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                NovelLink("全部图书  ›", { vm.navigate(NovelPage.AllWorks) })
                NovelLink("刷新", vm::refresh)
            }
        }
    }
}

@Composable internal fun NovelAllWorksScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
        item { NovelHeader("全部图书", onBack) }
        item { NovelSortMenu(state.sort, vm::setAllSort) }
        items(state.allWorks.value?.items.orEmpty(), key = { it.id }) { NovelWorkRow(it, { vm.openWork(it.id) }) }
        item { NovelLoadState(state.allWorks, state.allWorks.value?.items.isNullOrEmpty(), "暂无公开作品", vm::refresh) }
        item { if (state.allWorks.value?.nextCursor != null) NovelLink("加载更多", { vm.loadMoreWorks(all = true) }, Modifier.fillMaxWidth()) }
    }
}

@Composable internal fun NovelSearchScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovelSearchField(state.query, vm::setQuery, vm::searchNow, Modifier.weight(1f))
                NovelLink("取消", onBack)
            }
        }
        if (state.query.isBlank()) {
            item {
                NovelSection("最近搜索", if (state.history.isEmpty()) null else "清空", vm::clearHistory)
                if (state.history.isEmpty()) Text("还没有搜索记录", color = NovelTheme.colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.history.forEach { query ->
                        Row(Modifier.background(NovelTheme.colors.surfaceVariant.copy(alpha = .45f), RoundedCornerShape(24.dp)).clickable { vm.setQuery(query); vm.searchNow() }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(NovelIcons.Clock, null, Modifier.size(16.dp), tint = NovelTheme.colors.textSecondary)
                            Text(query, color = NovelTheme.colors.text, fontSize = 12.sp)
                        }
                    }
                }
                NovelSection("大家都在搜", "近 7 天", {})
            }
            items(state.hot.value.orEmpty().withIndex().toList(), key = { it.value.id }) { (index, work) ->
                Column {
                    Row(Modifier.fillMaxWidth().clickable { vm.openWork(work.id, fromSearch = true) }.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("%02d".format(index + 1), color = NovelTheme.colors.primary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(work.title, color = NovelTheme.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("${work.displayAuthorName} · ${work.typeLabel}", color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
                        }
                        Icon(NovelIcons.ChevronRight, "打开作品", tint = NovelTheme.colors.textSecondary, modifier = Modifier.size(19.dp))
                    }
                    NovelRule()
                }
            }
            item { NovelLoadState(state.hot, state.hot.value.isNullOrEmpty(), "近七天暂无热门搜索", vm::refresh) }
            item { Text("搜索历史仅自己可见", color = NovelTheme.colors.textSecondary, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp)) }
        } else {
            item {
                NovelTabs(SearchTypes, state.searchType, vm::setSearchType)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("“${state.query}” 的搜索结果 · ${state.search.value?.items?.size ?: 0} 部已加载", color = NovelTheme.colors.textSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    NovelSortMenu(state.searchSort, vm::setSearchSort)
                }
            }
            items(state.search.value?.items.orEmpty(), key = { it.id }) { NovelWorkRow(it, { vm.openWork(it.id, fromSearch = true) }) }
            item { NovelLoadState(state.search, state.search.value?.items.isNullOrEmpty(), "没有找到匹配的作品", vm::searchNow) }
            item {
                if (state.search.value?.nextCursor != null) NovelLink("加载更多", { vm.loadMoreWorks(search = true) }, Modifier.fillMaxWidth())
                else if (!state.search.loading && state.search.value?.items?.isNotEmpty() == true) Text("已展示全部结果，试试其他关键词", color = NovelTheme.colors.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(vertical = 30.dp))
            }
        }
    }
}

@Composable private fun NovelSearchField(query: String, onChange: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier) {
    val c = NovelTheme.colors
    BasicTextField(query, onChange, modifier.heightIn(min = 50.dp).background(c.surfaceVariant.copy(alpha = .4f), RoundedCornerShape(28.dp)).border(1.dp, c.primary.copy(alpha = .35f), RoundedCornerShape(28.dp)),
        textStyle = TextStyle(color = c.text, fontSize = 14.sp), cursorBrush = SolidColor(c.primary), singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        decorationBox = { inner ->
            Row(Modifier.padding(start = 16.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(NovelIcons.Search, null, Modifier.size(20.dp), tint = c.primary)
                Box(Modifier.weight(1f)) { if (query.isBlank()) Text("搜索作品、作者、标签", color = c.textSecondary, fontSize = 13.sp); inner() }
                if (query.isNotEmpty()) NovelIconButton(NovelIcons.X, "清除搜索词", { onChange("") }) else Spacer(Modifier.width(8.dp))
            }
        })
}

@Composable internal fun NovelSortMenu(selected: String, onSort: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        NovelLink((Sorts.firstOrNull { it.first == selected }?.second ?: "综合排序") + " ﹀", { expanded = true })
        DropdownMenu(expanded, { expanded = false }) { Sorts.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSort(id) }) } }
    }
}
