package org.joinmastodon.android.novel.app

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import me.grishka.appkit.FragmentStackActivity
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.fragments.ComposeFragment
import org.joinmastodon.android.fragments.settings.BabyVerificationFragment
import org.joinmastodon.android.novel.app.design.NovelIcons
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.joinmastodon.android.novel.app.editor.NovelCreationActivity
import org.joinmastodon.android.novel.app.editor.NovelReaderActivity
import org.joinmastodon.android.novel.app.editor.listLocalWorks
import org.joinmastodon.android.novel.app.models.Author
import org.joinmastodon.android.novel.app.models.Work
import org.joinmastodon.android.ui.compose.navigation.ViewBitmapBackdrop
import org.joinmastodon.android.ui.compose.navigation.liquid.IosLiquidGlassNavigationBar
import org.joinmastodon.android.ui.views.BackdropCaptureFrameLayout
import org.joinmastodon.android.ui.utils.LiquidGlassCompatibility
import org.joinmastodon.android.ui.compose.ui.LocalColorMode
import org.joinmastodon.android.ui.utils.UiUtils
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

internal val LocalNovelAccount = staticCompositionLocalOf { "" }
internal val LocalNovelBottomPadding = staticCompositionLocalOf { 0.dp }

/** Rebuilt public novel UI. Legacy private data and document compatibility stay in their own hosts. */
class NovelAppActivity : ComponentActivity(), NavigationEventDispatcherOwner {
    private var model: NovelAppViewModel? = null
    private var accountId: String? = null
    override val navigationEventDispatcher = NavigationEventDispatcher { goBack() }

    override fun onCreate(savedInstanceState: Bundle?) {
        val account = intent.getStringExtra(EXTRA_ACCOUNT)?.takeIf { it.isNotBlank() }
        val session = account?.let { AccountSessionManager.getInstance().tryGetAccount(it) }
        UiUtils.setUserPreferredTheme(this, session)
        super.onCreate(savedInstanceState)
        if (account == null || session == null) { finish(); return }
        accountId = account
        val vm = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return NovelAppViewModel(
                    { id -> NovelRepository(NovelAppApi(checkNotNull(AccountSessionManager.getInstance().tryGetAccount(id)))) },
                    PreferenceNovelSearchHistory(applicationContext),
                    { id -> id == accountId && AccountSessionManager.getInstance().tryGetAccount(id) != null },
                    { id -> listLocalWorks(applicationContext, id) },
                ) as T
            }
        })[NovelAppViewModel::class.java]
        model = vm
        val self = session.self
        vm.bindAccount(account, Author(self.id.toLongOrNull() ?: 0, self.username.orEmpty(), self.displayName.orEmpty(), self.avatar.orEmpty(), self.url.orEmpty()), intent.getStringExtra(EXTRA_WORK_ID)?.takeIf { it.isNotBlank() })
        enableEdgeToEdge(SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { UiUtils.isDarkTheme() }, SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { UiUtils.isDarkTheme() })
        setContent {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides this, LocalNovelAccount provides account) {
                NovelTheme(account) {
                    val state by vm.state.collectAsState()
                    BackHandler { goBack() }
                    NovelAppContent(state, vm, GlobalUserPreferences.isIosLiquidNavigationEnabled(), ::goBack,
                        onRead = { work, chapter -> startActivity(NovelReaderActivity.intent(this, account, work.id, chapter)) },
                        onCreate = { work, local, mode -> startActivity(NovelCreationActivity.intent(this, account, work, local, mode)) },
                        onVerify = { openAccountAction("verify") }, onPost = { openAccountAction("post") },
                        onShareWork = { shareWork(it) }, onShareAuthor = { shareText(listOf(it.author.name, "@${it.author.username}", it.author.url.takeIf { url -> url.startsWith("https://") }.orEmpty()).filter { part -> part.isNotBlank() }.joinToString("\n")) },
                        onMessage = { message -> message.workId?.let { id ->
                            if (message.category == "updates" && message.chapterId != null) startActivity(NovelReaderActivity.intent(this, account, id, message.chapterId))
                            else if (message.category == "system") { vm.navigate(NovelPage.Creator); vm.navigate(NovelPage.OwnerWorks) }
                            else vm.openWork(id)
                        } },
                    )
                }
            }
        }
        val chapter = intent.getStringExtra(EXTRA_CHAPTER_ID)?.takeIf { it.isNotBlank() }
        val work = intent.getStringExtra(EXTRA_WORK_ID)?.takeIf { it.isNotBlank() }
        if (savedInstanceState == null && chapter != null && work != null) startActivity(NovelReaderActivity.intent(this, account, work, chapter))
    }

    override fun onResume() {
        super.onResume()
        val id = accountId ?: return
        if (AccountSessionManager.getInstance().tryGetAccount(id) == null) { model?.unbindAccount(); finish() }
        else model?.refresh() // Includes fresh, account-scoped device manuscripts on return from the editor.
    }
    private fun goBack() { if (model?.back() != true) finish() }
    private fun openAccountAction(action: String) { accountId?.let { startActivity(NovelAccountActionActivity.intent(this, it, action)) } }
    private fun shareWork(work: Work) { shareText(listOf("《${work.title}》 · ${work.displayAuthorName}", work.description, "ABDL Space 小说").filter { it.isNotBlank() }.joinToString("\n\n")) }
    private fun shareText(text: String) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "分享")) }

    companion object {
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_WORK_ID = "work_id"
        const val EXTRA_CHAPTER_ID = "chapter_id"
        @JvmStatic @JvmOverloads fun intent(context: Context, accountId: String, workId: String? = null, chapterId: String? = null): Intent {
            require(accountId.isNotBlank())
            return Intent(context, NovelAppActivity::class.java).putExtra(EXTRA_ACCOUNT, accountId).putExtra(EXTRA_WORK_ID, workId).putExtra(EXTRA_CHAPTER_ID, chapterId)
        }
    }
}

/** Private native FragmentStack host for the real verification and compose flows. */
class NovelAccountActionActivity : FragmentStackActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val account = intent.getStringExtra("account")?.takeIf { it.isNotBlank() }
        val session = account?.let { AccountSessionManager.getInstance().tryGetAccount(it) }
        UiUtils.setUserPreferredTheme(this, session)
        super.onCreate(savedInstanceState)
        if (account == null || session == null) { finish(); return }
        if (savedInstanceState != null) return
        val args = Bundle().apply { putString("account", account) }
        val fragment = when (intent.getStringExtra("action")) {
            "verify" -> BabyVerificationFragment().apply { arguments = args }
            "post" -> ComposeFragment().apply { arguments = args }
            else -> { finish(); return }
        }
        showFragment(fragment)
    }
    companion object {
        internal fun intent(context: Context, accountId: String, action: String): Intent {
            require(accountId.isNotBlank() && action in setOf("verify", "post"))
            return Intent(context, NovelAccountActionActivity::class.java).putExtra("account", accountId).putExtra("action", action)
        }
    }
}

@Composable internal fun NovelAppContent(
    state: NovelAppState, vm: NovelAppViewModel, liquid: Boolean, onBack: () -> Unit,
    onRead: (Work, String?) -> Unit, onCreate: (String?, String?, String) -> Unit,
    onVerify: () -> Unit, onPost: () -> Unit,
    onShareWork: (Work) -> Unit, onShareAuthor: (org.joinmastodon.android.novel.app.models.AuthorPage) -> Unit,
    onMessage: (org.joinmastodon.android.novel.app.models.Message) -> Unit,
) {
    val roots = listOf(NovelPage.Store, NovelPage.Shelf, NovelPage.Creator)
    val root = state.page in roots
    val backdrop = remember { ViewBitmapBackdrop() }
    val density = LocalDensity.current
    var navigationHeight by remember { mutableIntStateOf(0) }
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // The outer host owns top/horizontal insets. The navigation owns its bottom inset once;
    // page lists reserve the measured overlay, not an additional fixed 96dp plus system bars.
    Box(Modifier.fillMaxSize().background(NovelTheme.colors.background)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        NovelCapturedPage(backdrop, liquid && root, if (root) navigationHeight + with(density) { 40.dp.roundToPx() } else 0,
            Modifier.fillMaxSize().then(if (!root) Modifier.navigationBarsPadding().imePadding() else Modifier)) {
            CompositionLocalProvider(LocalNovelBottomPadding provides if (root) with(density) { navigationHeight.toDp() } else 0.dp) {
                key(state.page) {
                    when (val page = state.page) {
                        NovelPage.Store -> NovelStoreScreen(state, vm, onBack)
                        NovelPage.Shelf -> NovelShelfScreen(state, vm, onBack, onRead)
                        NovelPage.Creator -> NovelCreatorScreen(state, vm, onBack, onCreate, onVerify, onPost)
                        NovelPage.Search -> NovelSearchScreen(state, vm, onBack)
                        NovelPage.AllWorks -> NovelAllWorksScreen(state, vm, onBack)
                        is NovelPage.Detail -> NovelDetailScreen(state, vm, onBack, onRead, onShareWork)
                        is NovelPage.Chapters -> NovelChaptersScreen(state, vm, onBack, onRead)
                        is NovelPage.AuthorProfile -> NovelAuthorScreen(state, vm, onBack, onShareAuthor)
                        NovelPage.Messages -> NovelMessagesScreen(state, vm, onBack, onMessage)
                        is NovelPage.Comments -> NovelCommentsScreen(state, vm, page.workId, onBack)
                        NovelPage.Statistics -> NovelStatisticsScreen(state, vm, onBack)
                        NovelPage.OwnerWorks -> NovelOwnerWorksScreen(state, vm, onBack, onCreate)
                    }
                }
            }
        }
        if (root) Box(Modifier.align(Alignment.BottomCenter).testTag("novel-navigation")
            .onSizeChanged { navigationHeight = it.height }.padding(bottom = inset)) {
            NovelBottomNavigation(roots.indexOf(state.page), liquid, backdrop) { vm.navigate(roots[it]) }
        }
        val notice = state.actionError ?: state.notice
        if (notice != null) Surface(color = if (state.actionError != null) NovelTheme.colors.surface else NovelTheme.colors.primaryContainer,
            shape = CircleShape, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)) {
            Row(Modifier.padding(start = 18.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(notice, color = if (state.actionError != null) NovelTheme.colors.error else NovelTheme.colors.text, fontSize = 12.sp, modifier = Modifier.weight(1f, fill = false).padding(vertical = 12.dp))
                NovelIconButton(NovelIcons.X, "关闭提示", vm::dismissNotice)
            }
        }
    }
}

/** A sibling of the navigation: native ImageViews and Compose text are captured, never the glass. */
@Composable internal fun NovelCapturedPage(backdrop: ViewBitmapBackdrop, enabled: Boolean, captureHeight: Int,
    modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val parent = rememberCompositionContext()
    val latestContent by rememberUpdatedState(content)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var captureView by remember { mutableStateOf<BackdropCaptureFrameLayout?>(null) }
    AndroidView(modifier = modifier, factory = { context ->
        BackdropCaptureFrameLayout(context).apply {
            addView(ComposeView(context).apply {
                consumeWindowInsets = false // Insets have already been applied by the outer Compose host.
                setParentCompositionContext(parent)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { latestContent() }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            captureView = this
        }
    })
    DisposableEffect(captureView, lifecycle, backdrop, enabled, captureHeight) {
        val binding = captureView?.let { NovelBackdropCaptureBinding(it, backdrop, lifecycle) }
        binding?.update(enabled, captureHeight)
        onDispose { binding?.dispose() }
    }
}

/** Window-relative strip coordinates stay valid across resize, pause/resume and view reattachment. */
internal class NovelBackdropCaptureBinding(
    private val view: BackdropCaptureFrameLayout,
    private val backdrop: ViewBitmapBackdrop,
    private val lifecycle: Lifecycle,
) {
    private var enabled = false
    private var height = 0
    private var listening = false
    private var disposed = false
    private val location = IntArray(2)
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = refresh()
        override fun onViewDetachedFromWindow(v: View) = stop()
    }
    private val lifecycleObserver = LifecycleEventObserver { _, _ -> refresh() }
    private val captureListener = BackdropCaptureFrameLayout.CaptureListener { _, bottom ->
        if (listening && canCapture()) {
            updateOrigin(bottom?.height ?: height.coerceAtMost(view.height))
            backdrop.update(bottom)
        }
    }
    init {
        view.addOnLayoutChangeListener(layoutListener)
        view.addOnAttachStateChangeListener(attachListener)
        lifecycle.addObserver(lifecycleObserver)
    }
    fun update(enabled: Boolean, height: Int) {
        this.enabled = enabled
        this.height = height.coerceAtLeast(0)
        refresh()
    }
    private fun canCapture() = !disposed && enabled && height > 0 && view.isAttachedToWindow &&
        view.isHardwareAccelerated && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && LiquidGlassCompatibility.isSupported()
    private fun refresh() {
        if (!canCapture()) { stop(); return }
        view.setCaptureHeights(0, height)
        updateOrigin(height.coerceAtMost(view.height))
        if (!listening) {
            listening = true
            view.setCaptureListener(captureListener)
        }
    }
    private fun updateOrigin(stripHeight: Int) {
        view.getLocationInWindow(location)
        backdrop.updateOriginInWindow(Offset(location[0].toFloat(), (location[1] + view.height - stripHeight).toFloat()))
    }
    private fun stop() {
        listening = false
        view.setCaptureListener(null)
        view.setCaptureHeights(0, 0)
        backdrop.update(null)
    }
    fun dispose() {
        if (disposed) return
        disposed = true
        lifecycle.removeObserver(lifecycleObserver)
        view.removeOnLayoutChangeListener(layoutListener)
        view.removeOnAttachStateChangeListener(attachListener)
        stop()
    }
}

@Composable internal fun NovelBottomNavigation(selected: Int, liquid: Boolean, backdrop: top.yukonga.miuix.kmp.blur.Backdrop, onSelect: (Int) -> Unit) {
    val items = listOf(NavigationItem("书城", NovelIcons.Compass), NavigationItem("书架", NovelIcons.Library), NavigationItem("创作", NovelIcons.PenLine))
    val c = NovelTheme.colors
    if (liquid) {
        val dark = c.background.luminance() < .5f
        val controller = remember(c, dark) {
            ThemeController(colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
                lightColors = lightColorScheme(primary = c.primary, onPrimary = c.onPrimary, surfaceContainer = c.surface, onSurface = c.text),
                darkColors = darkColorScheme(primary = c.primary, onPrimary = c.onPrimary, surfaceContainer = c.surface, onSurface = c.text))
        }
        CompositionLocalProvider(LocalColorMode provides if (dark) 2 else 1) {
            MiuixTheme(controller = controller) { IosLiquidGlassNavigationBar(items, selected, onSelect, backdrop, isRuntimeShaderSupported(), bottomPaddingOverride = 12.dp) }
        }
    } else {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Surface(color = c.surface, shape = CircleShape, shadowElevation = 3.dp,
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("novel-navigation-pill")) {
                Row(Modifier.selectableGroup().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items.forEachIndexed { index, item ->
                        Column(Modifier.weight(1f).background(if (selected == index) c.primaryContainer else c.surface, CircleShape)
                            .selectable(selected == index, role = Role.Tab, onClick = { onSelect(index) })
                            .heightIn(min = 48.dp).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                            Icon(item.icon, null, Modifier.size(20.dp), tint = if (selected == index) c.primary else c.textSecondary)
                            // AnnotatedString retains the canonical measured MultiParagraph for semantics,
                            // avoiding the simple-string path rebuilding it with different width constraints.
                            Text(AnnotatedString(item.label), color = if (selected == index) c.primary else c.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
