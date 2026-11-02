package org.joinmastodon.android.novel.app

import org.joinmastodon.android.R
import androidx.compose.ui.res.stringResource

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

private val StoreTypes = listOf("all" to R.string.novel_en_featured, "original" to R.string.novel_en_type_original, "repost" to R.string.novel_en_type_repost_short, "ai" to R.string.novel_en_type_ai_creation)
private val SearchTypes = listOf("all" to R.string.novel_en_all, "original" to R.string.novel_en_type_original, "repost" to R.string.novel_en_type_repost_short, "ai" to R.string.novel_en_type_ai_creation)
private val Sorts = listOf("featured" to R.string.novel_en_sort_featured, "updated" to R.string.novel_en_sort_updated, "popular" to R.string.novel_en_sort_reads, "collections" to R.string.novel_en_sort_collections)

@Composable internal fun NovelStoreScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = LocalNovelBottomPadding.current + 4.dp)) {
        item { NovelHeader(stringResource(R.string.novel_en_novels), onBack, stringResource(R.string.novel_en_store_subtitle)) { NovelIconButton(NovelIcons.Bell, stringResource(R.string.novel_en_novel_messages), { vm.navigate(NovelPage.Messages) }) } }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp).height(52.dp).background(NovelTheme.colors.surfaceVariant.copy(alpha = .45f), RoundedCornerShape(28.dp)).clickable { vm.navigate(NovelPage.Search) }.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(NovelIcons.Search, null, Modifier.size(21.dp), tint = NovelTheme.colors.textSecondary)
                Text(stringResource(R.string.novel_en_search_hint), color = NovelTheme.colors.textSecondary, fontSize = 14.sp)
            }
            NovelTabs(StoreTypes.map { (id, label) -> id to stringResource(label) }, state.storeType, vm::setStoreType, underline = true)
            Spacer(Modifier.height(10.dp))
        }
        items(state.works.value?.items.orEmpty(), key = { it.id }) { work -> NovelWorkRow(work, { vm.openWork(work.id) }) }
        item { NovelLoadState(state.works, state.works.value?.items.isNullOrEmpty(), stringResource(R.string.novel_en_no_public_works), vm::refresh) }
        item {
            if (state.works.value?.nextCursor != null) NovelLink(stringResource(R.string.novel_en_more_works), { vm.loadMoreWorks() }, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                NovelLink(stringResource(R.string.novel_en_all_books_link), { vm.navigate(NovelPage.AllWorks) })
                NovelLink(stringResource(R.string.novel_en_refresh), vm::refresh)
            }
        }
    }
}

@Composable internal fun NovelAllWorksScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
        item { NovelHeader(stringResource(R.string.novel_en_all_books), onBack) }
        item { NovelSortMenu(state.sort, vm::setAllSort) }
        items(state.allWorks.value?.items.orEmpty(), key = { it.id }) { NovelWorkRow(it, { vm.openWork(it.id) }) }
        item { NovelLoadState(state.allWorks, state.allWorks.value?.items.isNullOrEmpty(), stringResource(R.string.novel_en_no_public_works), vm::refresh) }
        item { if (state.allWorks.value?.nextCursor != null) NovelLink(stringResource(R.string.novel_en_load_more), { vm.loadMoreWorks(all = true) }, Modifier.fillMaxWidth()) }
    }
}

@Composable internal fun NovelSearchScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovelSearchField(state.query, vm::setQuery, vm::searchNow, Modifier.weight(1f))
                NovelLink(stringResource(R.string.novel_en_cancel), onBack)
            }
        }
        if (state.query.isBlank()) {
            item {
                NovelSection(stringResource(R.string.novel_en_recent_searches), if (state.history.isEmpty()) null else stringResource(R.string.novel_en_clear), vm::clearHistory)
                if (state.history.isEmpty()) Text(stringResource(R.string.novel_en_no_search_history), color = NovelTheme.colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.history.forEach { query ->
                        Row(Modifier.background(NovelTheme.colors.surfaceVariant.copy(alpha = .45f), RoundedCornerShape(24.dp)).clickable { vm.setQuery(query); vm.searchNow() }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(NovelIcons.Clock, null, Modifier.size(16.dp), tint = NovelTheme.colors.textSecondary)
                            Text(query, color = NovelTheme.colors.text, fontSize = 12.sp)
                        }
                    }
                }
                NovelSection(stringResource(R.string.novel_en_trending_searches), stringResource(R.string.novel_en_past_week), {})
            }
            items(state.hot.value.orEmpty().withIndex().toList(), key = { it.value.id }) { (index, work) ->
                Column {
                    Row(Modifier.fillMaxWidth().clickable { vm.openWork(work.id, fromSearch = true) }.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("%02d".format(index + 1), color = NovelTheme.colors.primary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(work.title, color = NovelTheme.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("${work.displayAuthorName(androidx.compose.ui.platform.LocalContext.current)} · ${stringResource(work.typeLabelRes)}", color = NovelTheme.colors.textSecondary, fontSize = 11.sp)
                        }
                        Icon(NovelIcons.ChevronRight, stringResource(R.string.novel_en_open_work), tint = NovelTheme.colors.textSecondary, modifier = Modifier.size(19.dp))
                    }
                    NovelRule()
                }
            }
            item { NovelLoadState(state.hot, state.hot.value.isNullOrEmpty(), stringResource(R.string.novel_en_no_trending_searches), vm::refresh) }
            item { Text(stringResource(R.string.novel_en_search_history_private), color = NovelTheme.colors.textSecondary, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp)) }
        } else {
            item {
                NovelTabs(SearchTypes.map { (id, label) -> id to stringResource(label) }, state.searchType, vm::setSearchType)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.novel_en_search_results, state.query, state.search.value?.items?.size ?: 0), color = NovelTheme.colors.textSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    NovelSortMenu(state.searchSort, vm::setSearchSort)
                }
            }
            items(state.search.value?.items.orEmpty(), key = { it.id }) { NovelWorkRow(it, { vm.openWork(it.id, fromSearch = true) }) }
            item { NovelLoadState(state.search, state.search.value?.items.isNullOrEmpty(), stringResource(R.string.novel_en_no_matching_works), vm::searchNow) }
            item {
                if (state.search.value?.nextCursor != null) NovelLink(stringResource(R.string.novel_en_load_more), { vm.loadMoreWorks(search = true) }, Modifier.fillMaxWidth())
                else if (!state.search.loading && state.search.value?.items?.isNotEmpty() == true) Text(stringResource(R.string.novel_en_all_results_shown), color = NovelTheme.colors.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(vertical = 30.dp))
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
                Box(Modifier.weight(1f)) { if (query.isBlank()) Text(stringResource(R.string.novel_en_search_hint), color = c.textSecondary, fontSize = 13.sp); inner() }
                if (query.isNotEmpty()) NovelIconButton(NovelIcons.X, stringResource(R.string.novel_en_clear_search), { onChange("") }) else Spacer(Modifier.width(8.dp))
            }
        })
}

@Composable internal fun NovelSortMenu(selected: String, onSort: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        NovelLink(stringResource(Sorts.firstOrNull { it.first == selected }?.second ?: R.string.novel_en_sort_featured) + " ﹀", { expanded = true })
        DropdownMenu(expanded, { expanded = false }) { Sorts.forEach { (id, title) -> DropdownMenuItem(text = { Text(stringResource(title)) }, onClick = { expanded = false; onSort(id) }) } }
    }
}
