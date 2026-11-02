@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.joinmastodon.android.fragments.friendcards

import org.joinmastodon.android.R
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.grishka.appkit.Nav
import org.joinmastodon.android.api.requests.diapers.GetDiaperList
import org.joinmastodon.android.friendcards.FriendCardDiaperSelection
import org.joinmastodon.android.friendcards.FriendCardRules
import org.joinmastodon.android.model.Diaper
import org.joinmastodon.android.model.FriendCard
import org.joinmastodon.android.ui.compose.friendcards.*
import top.yukonga.miuix.kmp.basic.Text
import java.util.UUID

class FriendCardDiaperSelectFragment : FriendCardBaseFragment() {
    private lateinit var selection: FriendCardDiaperSelection
    private val main = Handler(Looper.getMainLooper())
    private val searchTask = Runnable { if (shown && accountValid()) load() }
    private var scrollIndex = 0
    private var scrollOffset = 0
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); setTitle(R.string.friend_cards_diaper_title)
        val ids = state?.getIntegerArrayList("selection") ?: arguments?.getIntegerArrayList("selected_ids") ?: arrayListOf()
        val draftId = state?.getString("draft_id") ?: arguments?.getString("draft_id") ?: UUID.randomUUID().toString()
        selection = FriendCardDiaperSelection(account, draftId, ids)
        val known = state?.getString("diaper_json") ?: arguments?.getString("diaper_json")
        try { FriendCardRules.gson.fromJson(known, Array<FriendCard.Diaper>::class.java)?.forEach { selection.known[it.id] = it } } catch (_: Exception) { /* IDs remain selected; server product names are loaded separately. */ }
        selection.search(state?.getString("query").orEmpty())
        scrollIndex = state?.getInt("scroll_index") ?: 0; scrollOffset = state?.getInt("scroll_offset") ?: 0
    }
    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putIntegerArrayList("selection", ArrayList(selection.ids)); state.putString("draft_id", selection.draftId)
        state.putString("query", selection.query); state.putString("diaper_json", FriendCardRules.gson.toJson(selection.known.values.toList()))
        state.putInt("scroll_index", scrollIndex); state.putInt("scroll_offset", scrollOffset)
    }
    override fun refresh() { if (selection.results.isEmpty()) load(); changed() }
    override fun onHidden() { main.removeCallbacks(searchTask); selection.interrupt(); super.onHidden() }
    override fun onDestroyView() { main.removeCallbacks(searchTask); selection.interrupt(); super.onDestroyView() }
    private fun search(query: String) {
        selection.search(query); scrollIndex = 0; scrollOffset = 0; changed()
        main.removeCallbacks(searchTask); main.postDelayed(searchTask, 300)
    }
    private fun load() {
        val ticket = selection.begin() ?: return
        changed()
        execute(GetDiaperList(ticket.page, 20, ticket.query.trim(), null, null), { result ->
            try {
                val raw = result["diapers"] as? List<*> ?: throw IllegalStateException(getString(R.string.fc_products_incomplete))
                val items = raw.map { FriendCardRules.gson.fromJson(FriendCardRules.gson.toJson(it), Diaper::class.java) }.map { value ->
                    if (value.id <= 0) throw IllegalStateException(getString(R.string.fc_product_id_invalid))
                    FriendCard.Diaper().apply { id = value.id; name = listOfNotNull(value.brand, value.model).filter { it.isNotBlank() }.joinToString(" "); imageUrl = value.images?.firstOrNull(); available = true }
                }
                val total = ((result["pagination"] as? Map<*, *>)?.get("total") as? Number)?.toInt() ?: throw IllegalStateException(getString(R.string.fc_products_pagination_incomplete))
                selection.accept(ticket, items, total)
            } catch (error: Exception) { selection.fail(ticket, error.message ?: getString(R.string.fc_products_failed)) }
            changed()
        }, { selection.fail(ticket, message(it)); changed() })
    }
    private fun toggle(id: Int) { selection.toggle(id)?.let { toast(it) }; changed() }
    private fun complete() {
        if (!accountValid()) return
        val result = args().apply {
            putString("draft_id", selection.draftId)
            putIntegerArrayList("diaper_ids", ArrayList(selection.ids))
            putString("diaper_json", FriendCardRules.gson.toJson(selection.ids.mapNotNull { selection.known[it] }))
        }
        setResult(true, result); Nav.finish(this)
    }
    private data class ScreenState(
        val query: String, val ids: List<Int>, val known: Map<Int, FriendCard.Diaper>,
        val results: List<FriendCard.Diaper>, val loading: Boolean, val error: String?,
        val hasMore: Boolean,
    )
    @Composable override fun Screen(revision: Int) {
        val state = remember(revision) {
            ScreenState(selection.query, selection.ids, selection.known.toMap(), selection.results.toList(),
                selection.loading, selection.error, selection.hasMore)
        }
        val list = rememberLazyListState(scrollIndex, scrollOffset)
        var displayedQuery by remember { mutableStateOf(state.query) }
        LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.collect { (index, offset) -> scrollIndex = index; scrollOffset = offset } }
        LaunchedEffect(state.query) { if (displayedQuery != state.query) { list.scrollToItem(0); displayedQuery = state.query } }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FriendCardField(getString(R.string.fc_product_search), state.query, 100, hint = getString(R.string.fc_product_search_hint), onChange = { search(it) })
            if (state.ids.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.ids.forEach { id -> FriendCardChip("${state.known[id]?.name?.takeIf { it.isNotBlank() } ?: getString(R.string.fc_product_fallback, id)} ×", selected = true, onClick = { toggle(id) }) }
            }
            LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.results, key = { it.id }) { item ->
                    FriendCardPanel {
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { toggle(item.id) }.padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (item.id in state.ids) getString(R.string.fc_product_selected_toggle) else getString(R.string.fc_tap_select))
                            Text(if (item.id in state.ids) "✓" else "○")
                        }
                        // Product body has an explicit detail affordance; choosing never navigates away.
                        FriendCardProduct(item, { toggle(item.id) })
                        FriendCardActions(getString(R.string.fc_product_details) to { diaper(item) }, (if (item.id in state.ids) getString(R.string.fc_deselect) else getString(R.string.fc_select_product)) to { toggle(item.id) })
                    }
                }
                if (state.loading) item { FriendCardNotice(getString(R.string.fc_loading_products), null, null) }
                state.error?.let { error -> item { FriendCardNotice(error, getString(R.string.fc_retry), { load() }) } }
                if (!state.loading && state.results.isEmpty() && state.error == null) item { FriendCardNotice(getString(R.string.fc_products_no_matches), null, null) }
                if (!state.loading && state.hasMore && state.error == null && state.results.isNotEmpty()) item { FriendCardActions(getString(R.string.fc_more_products) to { load() }) }
            }
            FriendCardActions(getString(R.string.fc_cancel) to { finish() }, getString(R.string.fc_selection_done, state.ids.size) to { complete() })
            Text(getString(R.string.fc_product_selection_help))
        }
    }
}
