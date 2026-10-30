package org.joinmastodon.android.novel.app.editor

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.ui.platform.AbstractComposeView
import com.squareup.otto.Subscribe
import kotlinx.coroutines.cancel
import org.joinmastodon.android.E
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.ui.compose.navigation.MorphingGlassContainer
import org.joinmastodon.android.ui.compose.navigation.WithIsolatedGlassShaderCache
import org.joinmastodon.android.ui.compose.ui.LocalColorMode
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import org.joinmastodon.android.events.AccountLoggedOutEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.app.NovelAppApi
import org.joinmastodon.android.novel.app.design.NovelIcons
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.joinmastodon.android.ui.utils.UiUtils
import org.joinmastodon.reader.domain.ReaderPalette
import org.joinmastodon.reader.domain.ReaderPosition
import org.joinmastodon.reader.ui.ReaderScreen

/** Reader-core host with one lazily fetched chapter and toolbar-scoped host actions. */
class NovelReaderActivity : ComponentActivity(), NavigationEventDispatcherOwner {
    private var controller: NovelReaderController? = null
    private var accountId: String? = null
    private var eventsRegistered = false
    override val navigationEventDispatcher = NavigationEventDispatcher { if (sessionActive()) controller?.leave(::finish) ?: finish() else closeExpiredSession() }
    override fun onCreate(savedInstanceState: Bundle?) {
        val account = intent.getStringExtra("account")
        val session = account?.let { AccountSessionManager.getInstance().tryGetAccount(it) }
        UiUtils.setUserPreferredTheme(this, session)
        super.onCreate(savedInstanceState)
        if (session == null || account == null || listOfNotNull(intent.getStringExtra("work_id"), intent.getStringExtra("local_work_id"), intent.getStringExtra("private_book_id")).size != 1) { closeExpiredSession(); return }
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return NovelReaderController(application, NovelEditorRepository(application, account, NovelAppApi(session)), intent.getStringExtra("work_id"), intent.getStringExtra("local_work_id"), intent.getStringExtra("private_book_id")) as T
            }
        })[NovelReaderController::class.java]
        controller = model
        accountId = account
        if (!sessionActive()) { closeExpiredSession(); return }
        E.register(this); eventsRegistered = true
        if (model.state.value.book == null) model.start(intent.getStringExtra("local_chapter_id") ?: intent.getStringExtra("chapter_id"))
        enableEdgeToEdge(SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        setContent { CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides this) { NovelTheme(account) {
            val state by model.state.collectAsState()
            BackHandler { model.leave(::finish) }
            ReaderHostScreen(state, model) { model.leave(::finish) }
        } } }
    }
    override fun onResume() { super.onResume(); if (!sessionActive()) closeExpiredSession() }
    override fun onPause() {
        if (sessionActive()) controller?.savePosition() else closeExpiredSession()
        super.onPause()
    }
    @Subscribe fun onAccountLoggedOut(event: AccountLoggedOutEvent) {
        if (event.id == accountId) runOnUiThread { closeExpiredSession() }
    }
    private fun sessionActive(): Boolean = controller?.repository?.let { it.accountId == accountId && (it.api as? NovelAppApi)?.isSessionActive() == true } == true
    private fun closeExpiredSession() {
        // Drop in-memory private/public text as well as jobs, without writing a logged-out position.
        controller?.viewModelScope?.cancel()
        controller = null
        viewModelStore.clear()
        findViewById<ViewGroup>(android.R.id.content)?.let { content ->
            for (i in 0 until content.childCount) (content.getChildAt(i) as? AbstractComposeView)?.disposeComposition()
            content.removeAllViews()
        }
        finish()
    }
    override fun onDestroy() {
        if (eventsRegistered) { E.unregister(this); eventsRegistered = false }
        super.onDestroy()
    }
    companion object {
        @JvmStatic @JvmOverloads fun intent(context: Context, accountId: String, workId: String, chapterId: String? = null) = Intent(context, NovelReaderActivity::class.java).putExtra("account", accountId).putExtra("work_id", workId).putExtra("chapter_id", chapterId)
        @JvmStatic @JvmOverloads fun localIntent(context: Context, accountId: String, localWorkId: String, localChapterId: String? = null) = Intent(context, NovelReaderActivity::class.java).putExtra("account", accountId).putExtra("local_work_id", localWorkId).putExtra("local_chapter_id", localChapterId)
        @JvmStatic fun privateIntent(context: Context, accountId: String, privateBookId: String) = Intent(context, NovelReaderActivity::class.java).putExtra("account", accountId).putExtra("private_book_id", privateBookId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderHostScreen(state: ReaderHostState, controller: NovelReaderController, back: () -> Unit) {
    var directory by remember { mutableStateOf(false) }; var annotations by remember { mutableStateOf(false) }; var note by remember { mutableStateOf(false) }
    val liquid = GlobalUserPreferences.isIosLiquidNavigationEnabled()
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize()) {
        val book = state.book; val chapter = state.chapter
        if (book != null && chapter != null && !state.loading && state.error == null) {
            // A composition key resets only the UI page state, not the stable persisted work identity.
            key(book.id, chapter.id) {
                ReaderScreen(book = book, chapters = listOf(chapter), initialPosition = ReaderPosition(0, state.page),
                    onPositionChanged = controller::position, onBookmark = { controller.annotation() }, onNote = { note = true },
                    onPreviousChapter = if (state.index > 0) ({ controller.open(state.index - 1) }) else null,
                    onNextChapter = if (state.index < state.headers.lastIndex) ({ controller.open(state.index + 1) }) else null,
                    externalChapterIndex = state.index, externalChapterCount = state.headers.size,
                    initialPalette = if (UiUtils.isDarkTheme()) ReaderPalette.NIGHT else ReaderPalette.PAPER, onBack = back,
                    toolbarActions = {
                        ReaderHostToolbarActions(liquid, backdrop, onAnnotations = { annotations = true }, onDirectory = { directory = true })
                    },
                    contentBackdropModifier = if (liquid && isRuntimeShaderSupported()) Modifier.layerBackdrop(backdrop) else Modifier)
            }
        } else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.loading) CircularProgressIndicator() else { Text(state.error ?: "暂无正文"); Button(onClick = { controller.start() }, modifier = Modifier.padding(top = 20.dp)) { Text(if (state.changed) "重新加载当前版本" else "重试") } }
            TextButton(onClick = back) { Text("返回") }
        }
        state.notice?.let { notice -> Surface(color = NovelTheme.colors.primaryContainer, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).clickable { controller.clearNotice() }) { Text(notice, fontSize = 12.sp, modifier = Modifier.padding(12.dp)) } }
    }
    if (directory || annotations) ModalBottomSheet(onDismissRequest = { directory = false; annotations = false }) {
        Text(if (directory) "章节目录" else "书签与笔记（本机）", modifier = Modifier.padding(20.dp), fontSize = 21.sp)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
            if (directory) itemsIndexed(state.headers, key = { _, h -> h.id }) { i, header -> ListItem(headlineContent = { Text("${i + 1} · ${header.title}") }, modifier = Modifier.clickable { directory = false; controller.open(i) }) }
            else {
                if (state.annotations.isEmpty()) item { Text("暂无书签与笔记", modifier = Modifier.padding(20.dp)) }
                itemsIndexed(state.annotations, key = { _, item -> item.id }) { _, item -> ListItem(headlineContent = { Text(item.note ?: "书签") }, supportingContent = { Text("${state.headers.firstOrNull { it.id == item.chapterId }?.title ?: "章节已移除"} · 第 ${item.page + 1} 页") }, modifier = Modifier.clickable { val index = state.headers.indexOfFirst { it.id == item.chapterId }; if (index >= 0) { annotations = false; controller.open(index, item.page) } }) }
            }
        }
    }
    if (note) { var body by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = { note = false }, title = { Text("添加阅读笔记") }, text = { OutlinedTextField(body, { body = it.take(2000) }, label = { Text("笔记（绑定当前章节与页码）") }) }, confirmButton = { TextButton(enabled = body.isNotBlank(), onClick = { controller.annotation(body); note = false }) { Text("保存") } }, dismissButton = { TextButton(onClick = { note = false }) { Text("取消") } }) }
}

/** Composed only by ReaderControls' action slot, so hiding tools also removes their hit targets. */
@Composable
internal fun ReaderHostToolbarActions(liquid: Boolean, backdrop: Backdrop, onAnnotations: () -> Unit, onDirectory: () -> Unit) {
    val dark = NovelTheme.colors.background.luminance() < .5f
    CompositionLocalProvider(LocalColorMode provides if (dark) 2 else 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            ReaderHostToolbarButton(NovelIcons.Bookmark, "书签与笔记", liquid, backdrop, onAnnotations)
            ReaderHostToolbarButton(NovelIcons.List, "章节目录", liquid, backdrop, onDirectory)
        }
    }
}

@Composable
private fun ReaderHostToolbarButton(icon: ImageVector, label: String, liquid: Boolean, backdrop: Backdrop, onClick: () -> Unit) {
    if (liquid) WithIsolatedGlassShaderCache {
        MorphingGlassContainer(
            expanded = false, closedWidth = 48.dp, closedHeight = 48.dp,
            expandedWidth = 48.dp, expandedHeight = 48.dp, backdrop = backdrop,
            anchorFractionX = 1f, selectionItemCount = 0, enableDragSelection = false,
            shouldExpandFromClosed = { _, _ -> false }, onExpansionRequested = {}, onClick = onClick,
            modifier = Modifier.semantics { contentDescription = label; role = Role.Button },
            closedContent = { Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = NovelTheme.colors.text) } },
            expandedContent = {},
        )
    } else FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Icon(icon, label) }
}
