package org.joinmastodon.android.ui.compose.friendcards

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.UUID
import kotlinx.coroutines.delay
import org.joinmastodon.android.R
import org.joinmastodon.android.model.FriendCard
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val SocialAccountLimit = 5
private const val SocialNameLimit = 20
private const val SocialValueLimit = 120

/** Display names only: never infer profile URLs, search for users, or fetch link metadata. */
fun friendCardSocialPlatformName(entry: FriendCard.SocialAccount): String = when (entry.platform) {
    "qq" -> "QQ"
    "wechat" -> "微信"
    else -> entry.name?.takeIf { it.isNotBlank() } ?: "其他 / 自定义"
}

private fun copySocialAccount(entry: FriendCard.SocialAccount, change: FriendCard.SocialAccount.() -> Unit) =
    FriendCard.SocialAccount().apply {
        id = entry.id
        platform = entry.platform
        account = entry.account
        name = entry.name
        isPublic = entry.isPublic
        change()
    }

/** Controlled editor. Existing DTOs are never mutated; only explicit additions receive a new ID. */
@Composable
fun FriendCardSocialEditor(
    accounts: List<FriendCard.SocialAccount>,
    enabled: Boolean,
    onChange: (List<FriendCard.SocialAccount>) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val latestAccounts by rememberUpdatedState(accounts)
    val latestChange by rememberUpdatedState(onChange)
    var showPlatforms by remember { mutableStateOf(false) }
    var platformTarget by remember { mutableStateOf<FriendCard.SocialAccount?>(null) }
    LaunchedEffect(enabled) { if (!enabled) showPlatforms = false }

    fun sameRow(left: FriendCard.SocialAccount, right: FriendCard.SocialAccount): Boolean =
        if (!right.id.isNullOrBlank()) left.id == right.id else left === right

    fun update(entry: FriendCard.SocialAccount, change: FriendCard.SocialAccount.() -> Unit) {
        if (enabled) latestChange(latestAccounts.map { if (sameRow(it, entry)) copySocialAccount(it, change) else it })
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("社交账号", modifier = Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Text("选填 · ${accounts.size}/$SocialAccountLimit", fontSize = 12.sp, color = if (accounts.size > SocialAccountLimit) colors.error else colors.onSurfaceVariantSummary)
        }
        Text("选择平台，再填写账号或主页链接。默认公开，可逐项设为仅自己。", fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariantSummary)
        accounts.forEachIndexed { index, entry ->
            // Never key on account text, privacy, platform, list revision, or DTO object identity.
            key(entry.id?.takeIf { it.isNotBlank() } ?: "legacy-social-$index") {
                val platformName = friendCardSocialPlatformName(entry)
                val custom = entry.platform != "qq" && entry.platform != "wechat"
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.width(108.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                                .background(colors.surfaceVariant.copy(alpha = .65f))
                                .clickable(enabled = enabled, role = Role.Button) { platformTarget = entry; showPlatforms = true }
                                .semantics { contentDescription = "选择第${index + 1}项社交平台，当前$platformName" }
                                .padding(horizontal = 9.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            SocialPlatformIcon(entry.platform, Modifier.size(22.dp), colors.onSurfaceVariantSummary)
                            Text(if (custom) "自定义" else platformName, modifier = Modifier.weight(1f), fontSize = 13.sp, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Outlined.ExpandMore, null, modifier = Modifier.size(14.dp), tint = colors.onSurfaceVariantSummary)
                        }
                        SocialTextInput(
                            value = entry.account.orEmpty(), label = "第${index + 1}项${platformName}账号",
                            hint = when (entry.platform) { "qq" -> "填写 QQ 账号"; "wechat" -> "填写微信号"; else -> "账号或主页链接" },
                            enabled = enabled, limit = SocialValueLimit, modifier = Modifier.weight(1f),
                        ) { text -> update(entry) { account = text } }
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = enabled, role = Role.Button) {
                                    if (enabled) latestChange(latestAccounts.filterNot { sameRow(it, entry) })
                                }.semantics { contentDescription = "移除第${index + 1}项${platformName}账号" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.Close, null, modifier = Modifier.size(20.dp), tint = colors.onSurfaceVariantSummary.copy(alpha = if (enabled) 1f else .45f))
                        }
                    }
                    if (custom) SocialTextInput(
                        value = entry.name.orEmpty(), label = "第${index + 1}项平台名称", hint = "平台名称（必填，最多20字）",
                        enabled = enabled, limit = SocialNameLimit, modifier = Modifier.fillMaxWidth().padding(end = 50.dp),
                    ) { text -> update(entry) { name = text } }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                            .toggleable(value = entry.isPublic, enabled = enabled, role = Role.Switch, onValueChange = { visible -> update(entry) { isPublic = visible } })
                            .semantics { contentDescription = "第${index + 1}项${platformName}账号可见范围" }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(if (entry.isPublic) "公开 · 将展示在扩列卡" else "仅自己 · 不对外展示", modifier = Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp,
                            color = if (entry.isPublic) colors.primary else colors.onSurfaceVariantSummary)
                        Icon(if (entry.isPublic) Icons.Outlined.Visibility else Icons.Outlined.Lock, null, modifier = Modifier.padding(horizontal = 12.dp).size(18.dp),
                            tint = if (entry.isPublic) colors.primary else colors.onSurfaceVariantSummary)
                    }
                }
            }
        }
        FriendCardPrimaryButton(
            text = if (accounts.size >= SocialAccountLimit) "已达上限（最多5个账号）" else "+  添加社交账号",
            onClick = { platformTarget = null; showPlatforms = true },
            enabled = enabled && accounts.size < SocialAccountLimit,
            outlined = true,
        )
    }
    if (showPlatforms && enabled) SocialPlatformSheet(
        selected = platformTarget?.platform,
        onDismiss = { showPlatforms = false },
        onSelect = { platform ->
            if (enabled) {
                val target = platformTarget
                if (target == null) {
                    if (latestAccounts.size < SocialAccountLimit) latestChange(latestAccounts + FriendCard.SocialAccount().apply {
                        id = UUID.randomUUID().toString()
                        this.platform = platform
                        account = ""
                        name = ""
                        isPublic = true
                    })
                } else update(target) { this.platform = platform }
            }
            showPlatforms = false
        },
    )
}

/** The row's saveable TextFieldValue survives ordinary DTO copies and keeps IME composition. */
@Composable
private fun SocialTextInput(
    value: String,
    label: String,
    hint: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    limit: Int? = null,
    onDone: (() -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(value)) }
    LaunchedEffect(value) {
        if (value != field.text) field = TextFieldValue(
            text = value,
            selection = TextRange(field.selection.start.coerceIn(0, value.length), field.selection.end.coerceIn(0, value.length)),
        )
    }
    val count = value.codePointCount(0, value.length)
    val invalid = value.any { Character.isISOControl(it) || it == '\u2028' || it == '\u2029' }
    val tooLong = limit != null && count > limit
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicTextField(
            value = field,
            onValueChange = { next ->
                field = next
                if (enabled && next.text != value) onChange(next.text)
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = if (onDone != null) KeyboardActions(onDone = { onDone() }) else KeyboardActions.Default,
            textStyle = TextStyle(color = colors.onSurface, fontSize = 14.sp),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .background(colors.surface.copy(alpha = .7f))
                .border(1.dp, if (tooLong || invalid) colors.error else colors.dividerLine, RoundedCornerShape(12.dp))
                .semantics { contentDescription = label }.padding(horizontal = 10.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (field.text.isEmpty()) Text(hint, color = colors.onSurfaceVariantSummary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        // Do not truncate or normalize an active composing value; the publish rules validate it.
        if (tooLong || invalid) Text(if (invalid) "请勿输入换行或控制字符" else "$count/$limit 字", fontSize = 11.sp, color = colors.error)
    }
}

private data class SocialPlatform(val id: String, val name: String, val hint: String, val searchTerms: String)
private val SocialPlatforms = listOf(
    SocialPlatform("qq", "QQ", "填写 QQ 账号", "qq QQ 腾讯"),
    SocialPlatform("wechat", "微信", "填写微信号", "微信 wechat weixin"),
    SocialPlatform("custom", "其他 / 自定义", "填写平台名称和账号或主页链接", "其他 自定义 custom other"),
)

@Composable
private fun SocialPlatformSheet(selected: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    var search by rememberSaveable { mutableStateOf("") }
    val matches = SocialPlatforms.filter { it.searchTerms.contains(search.trim(), ignoreCase = true) }
    // A separate native window is required here: the editor lives inside a clipped lazy item,
    // and no Miuix overlay host is guaranteed above that item.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true,
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val hideKeyboard = { keyboard?.hide(); focus.clearFocus() }
        val dismiss = { hideKeyboard(); onDismiss() }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Box(Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Button, onClick = dismiss,
            ).semantics { contentDescription = "关闭社交平台选择遮罩" })
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(colors.surface)
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(colors.dividerLine))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("选择社交平台", modifier = Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = dismiss)
                        .semantics { contentDescription = "关闭社交平台选择" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Close, null, tint = colors.onSurfaceVariantSummary, modifier = Modifier.size(22.dp))
                    }
                }
                Text("预设平台使用官方图标", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                SocialTextInput(search, "搜索社交平台", "搜索平台", true, onDone = hideKeyboard, onChange = { search = it })
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (matches.isEmpty()) Text("未找到预设平台，可选择其他 / 自定义。", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                    (if (matches.isEmpty()) listOf(SocialPlatforms.last()) else matches).forEach { platform ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (platform.id == selected) colors.primaryContainer.copy(alpha = .5f) else colors.surfaceVariant.copy(alpha = .35f))
                                .selectable(selected = platform.id == selected, role = Role.RadioButton, onClick = { hideKeyboard(); onSelect(platform.id) })
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SocialPlatformIcon(platform.id, Modifier.size(36.dp), colors.onSurfaceVariantSummary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(platform.name, fontSize = 17.sp, color = colors.onSurface)
                                Text(platform.hint, fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariantSummary)
                            }
                            Icon(if (platform.id == selected) Icons.Outlined.Check else Icons.Outlined.ChevronRight, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Text("自定义平台使用通用链接图标。仅公开的账号会展示在扩列卡中。", fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariantSummary)
            }
        }
    }
}

/** Public detail only. Private rows do not compose any text or clipboard action. */
@Composable
fun FriendCardSocialDetails(accounts: List<FriendCard.SocialAccount>, foreground: Color = Color.White) {
    val visible = accounts.filter { it.isPublic && !it.account.isNullOrBlank() }
    if (visible.isEmpty()) return
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Public, null, tint = foreground.copy(alpha = .75f), modifier = Modifier.size(20.dp))
            Text("社交账号", fontSize = 14.sp, color = foreground.copy(alpha = .75f))
        }
        visible.forEachIndexed { index, entry ->
            key(entry.id?.takeIf { it.isNotBlank() } ?: "public-social-$index") {
                val name = friendCardSocialPlatformName(entry)
                var copyLabel by remember(entry.account) { mutableStateOf("复制") }
                LaunchedEffect(copyLabel) { if (copyLabel != "复制") { delay(2000); copyLabel = "复制" } }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(foreground.copy(alpha = .10f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SocialPlatformIcon(entry.platform, Modifier.size(36.dp), foreground.copy(alpha = .85f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(name, color = foreground, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(entry.account.orEmpty(), color = foreground.copy(alpha = .8f), fontSize = 13.sp, lineHeight = 19.sp)
                    }
                    Row(
                        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(foreground.copy(alpha = .12f))
                            .clickable(role = Role.Button) { copyLabel = if (copySocialText(context, name, entry.account.orEmpty())) "已复制" else "复制失败" }
                            .semantics { contentDescription = "复制${name}账号" }.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(16.dp), tint = foreground)
                        Text(copyLabel, color = foreground, fontSize = 12.sp)
                    }
                }
            }
        }
        Text("请复制账号后在对应平台添加好友。", color = foreground.copy(alpha = .65f), fontSize = 12.sp, lineHeight = 18.sp)
    }
}

private fun copySocialText(context: Context, name: String, account: String): Boolean = runCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return@runCatching false
    val clip = ClipData.newPlainText(name, account)
    // The stable literal also works on API 26–32; API 33+ suppresses contact previews.
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    clipboard.setPrimaryClip(clip)
    true
}.getOrDefault(false)

/** Brand bitmaps deliberately bypass Compose vector rendering and theme tinting. */
@Composable
private fun SocialPlatformIcon(platform: String?, modifier: Modifier, foreground: Color) {
    val resource = when (platform) { "qq" -> R.drawable.ic_social_qq; "wechat" -> R.drawable.ic_social_wechat; else -> null }
    if (resource == null) {
        Icon(Icons.Outlined.Link, null, modifier = modifier, tint = foreground)
    } else AndroidView(
        factory = { context -> ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        } },
        update = { image ->
            image.setImageResource(resource)
            image.imageTintList = null
            image.clearColorFilter()
        },
        modifier = modifier,
    )
}
