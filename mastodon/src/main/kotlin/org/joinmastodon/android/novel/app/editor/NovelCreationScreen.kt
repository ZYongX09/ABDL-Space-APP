@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.joinmastodon.android.novel.app.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.joinmastodon.android.novel.app.design.NovelIcons
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.joinmastodon.android.novel.app.design.NovelTypography
import org.joinmastodon.noveleditor.EditorSession

@Composable
internal fun NovelCreationScreen(state: NovelEditorState, controller: NovelEditorController, onBack: () -> Unit, onDelete: () -> Unit, onPreview: (String, String?) -> Unit) {
    var more by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var submit by remember { mutableStateOf(false) }
    var conflictConfirm by remember { mutableStateOf(false) }
    var submitSelected by remember { mutableStateOf(setOf<String>()) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(controller::exportTo) }
    val title = when (state.page) { "metadata" -> if (state.workId == null) "新建作品" else "作品资料"; "editor" -> "小说编辑"; "import" -> "上传文稿"; "importPreview" -> "解析预览"; "adjust" -> "调整章节"; else -> "作品管理" }
    Scaffold(
        containerColor = NovelTheme.colors.background,
        topBar = { Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onBack, enabled = !state.busy) { Icon(NovelIcons.ArrowLeft, "返回") }
            Text(title, fontFamily = NovelTypography.serif, fontSize = 25.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
            if (state.workId != null) IconButton(onClick = { more = true }) { Icon(NovelIcons.MoreHorizontal, "更多操作") }
        } },
        bottomBar = {
            when (state.page) {
                "editor" -> Column(Modifier.fillMaxWidth().background(NovelTheme.colors.background).navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.saving) "正在保存本机…" else if (state.dirty) "有未保存修改 · 每 10 秒本地保存" else "已保存到本机", fontSize = 11.sp, color = NovelTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { controller.preview(onPreview) }, enabled = !state.busy) { Icon(NovelIcons.Eye, null, Modifier.size(17.dp)); Text(" 预览") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(onClick = controller::saveExplicit, enabled = !state.busy, modifier = Modifier.weight(1f).height(48.dp)) { Text("保存草稿") }
                        Button(onClick = { submitSelected = setOfNotNull(state.chapterId); submit = true }, enabled = !state.busy, modifier = Modifier.weight(1.6f).height(48.dp)) { Text("提交章节审核"); Icon(NovelIcons.ArrowRight, null, Modifier.padding(start = 8.dp).size(18.dp)) }
                    }
                }
                "manage" -> Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { submitSelected = emptySet(); submit = true }, enabled = !state.busy, modifier = Modifier.weight(1f).height(48.dp)) { Text("批量提交") }
                    Button(onClick = controller::newChapter, enabled = !state.busy, modifier = Modifier.weight(1.4f).height(48.dp)) { Icon(NovelIcons.Plus, null); Text(" 新建章节") }
                }
                "metadata" -> Button(onClick = controller::storeMetadata, enabled = !state.busy, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp).height(48.dp)) { Text(if (state.workId == null) "写第一章  →" else "保存本地资料") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { error -> Surface(color = NovelTheme.colors.error.copy(alpha = .09f), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(error, color = NovelTheme.colors.error); TextButton(onClick = controller::clearError) { Text("知道了") } } } }
            state.notice?.let { Text(it, color = NovelTheme.colors.primary, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable { controller.clearError() }.padding(16.dp)) }
            state.conflict?.let { message -> Surface(color = NovelTheme.colors.error.copy(alpha = .09f), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(message, color = NovelTheme.colors.error)
                Row { TextButton(onClick = controller::refreshOwner, enabled = !state.busy) { Text("刷新审核状态") }; TextButton(onClick = { conflictConfirm = true }, enabled = !state.busy) { Text("保留本机并重新选章") } }
            } } }
            when (state.page) {
                "loading" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                "metadata" -> NovelMetadataForm(state.metadata, controller::metadata, state.busy, controller)
                "editor" -> ManuscriptScreen(state, controller)
                "manage" -> WorkManagement(state, controller, onPreview, { confirmation = "chapter:$it" })
                else -> NovelImportScreen(state, controller)
            }
        }
    }
    if (more) ModalBottomSheet(onDismissRequest = { more = false }, containerColor = NovelTheme.colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
            Text("作品操作", fontFamily = NovelTypography.serif, fontSize = 23.sp)
            Text(state.directory?.structure?.work()?.title().orEmpty(), color = NovelTheme.colors.textSecondary, modifier = Modifier.padding(vertical = 10.dp))
            listOf("metadata" to "编辑作品资料", "refresh" to "刷新审核状态", "complete" to "设为完结", "export" to "导出文稿", "import" to "导入文稿为新章节", "retry" to "重试上次提交", "cancelSubmission" to "放弃本次未完成提交", "withdraw" to "下架作品", "delete" to "删除作品").forEach { (key, text) ->
                TextButton(onClick = { more = false; when (key) { "metadata" -> controller.showMetadata(); "refresh" -> controller.refreshOwner(); "import" -> controller.showImport(); else -> confirmation = key } }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(text, color = if (key in setOf("withdraw", "delete")) NovelTheme.colors.error else NovelTheme.colors.text, modifier = Modifier.weight(1f)); Icon(NovelIcons.ChevronRight, null) }
                HorizontalDivider(color = NovelTheme.colors.outline)
            }
            TextButton(onClick = { more = false }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("取消") }
        }
    }
    confirmation?.let { action ->
        val text = when { action.startsWith("chapter:") -> "仅将此本地章节移到回收状态，不会删除公开章节。确定继续？"; action == "complete" -> "保存本地完结标记。公开信息需在明确提交后通过人工审核才会更新。"; action == "export" -> "将导出全部本地章节（含未发布草稿）到你选择的位置，可能包含私密文稿。确定导出？"; action == "withdraw" -> "将停止公开展示此作品；本地文稿与私人书库保留。确定下架？"; action == "retry" -> "仅重发上次已冻结的所选章节快照，不包含后来修改；不会自动提交其他章节。确定重试？"; action == "cancelSubmission" -> "明确放弃本次上传或待审核投稿；审核员已接手时不可取消。所有本机文稿保留，不会上传新修改。确定放弃？"; else -> "服务端作品将删除；本机移到回收状态，私人书库不受影响。此公开操作不可恢复，确定删除？" }
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("请再次确认") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { confirmation = null; when { action.startsWith("chapter:") -> controller.deleteChapter(action.substringAfter(':')); action == "export" -> export.launch("${state.metadata.title.ifBlank { "文稿" }}.txt"); action == "retry" -> controller.submit(emptyList(), true); action == "cancelSubmission" -> controller.cancelSubmission(); else -> controller.workAction(action, onDelete) } }) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
    }
    if (conflictConfirm && state.conflict != null) AlertDialog(onDismissRequest = { conflictConfirm = false }, title = { Text("保留本机稿并重新选章") }, text = { Text("远端状态将再次核对。本机正文与资料不会被远端覆盖；后续仅你再次明确选择的章节会作为当前公开版本的修改提交。此次确认不上传任何文稿。") }, confirmButton = { TextButton(onClick = { conflictConfirm = false; submitSelected = emptySet(); controller.keepLocalAfterConflict() }, enabled = !state.busy) { Text("保留本机，重新选章") } }, dismissButton = { TextButton(onClick = { conflictConfirm = false }) { Text("取消，继续本机编辑") } })
    if (submit) AlertDialog(onDismissRequest = { submit = false }, title = { Text("提交所选章节审核") }, text = {
        Column {
            Text("只上传勾选章节及当前作品资料；其他本地草稿不会上传，已发布版本不变。", fontSize = 12.sp)
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                itemsIndexed(state.directory?.structure?.chapters().orEmpty(), key = { _, c -> c.id() }) { _, chapter ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { submitSelected = if (chapter.id() in submitSelected) submitSelected - chapter.id() else submitSelected + chapter.id() }) { Checkbox(chapter.id() in submitSelected, { selected -> submitSelected = if (selected) submitSelected + chapter.id() else submitSelected - chapter.id() }); Text(chapter.title(), modifier = Modifier.weight(1f)) }
                }
            }
        }
    }, confirmButton = { TextButton(enabled = submitSelected.isNotEmpty(), onClick = { submit = false; controller.submit(state.directory?.structure?.chapters().orEmpty().filter { it.id() in submitSelected }.map { it.id() }) }) { Text("确认提交 ${submitSelected.size} 章") } }, dismissButton = { TextButton(onClick = { submit = false }) { Text("取消") } })
}

@Composable
private fun ManuscriptScreen(state: NovelEditorState, controller: NovelEditorController) {
    val frame = state.frame ?: return
    var directory by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(true) }
    var font by remember { mutableFloatStateOf(14f) }
    val chapters = state.directory?.structure?.chapters().orEmpty()
    val index = chapters.indexOfFirst { it.id() == state.chapterId }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = NovelTheme.colors.primaryContainer, modifier = Modifier.fillMaxWidth().clickable { directory = true }) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(NovelIcons.List, null, tint = NovelTheme.colors.primary); Text("第 ${index + 1} 章 · ${frame.title()}", color = NovelTheme.colors.primary, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp)); Text("目录", fontSize = 12.sp); Icon(NovelIcons.ChevronDown, null, Modifier.size(18.dp)) } }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("第 ${index + 1} 章 · 本地草稿", color = NovelTheme.colors.primary, fontSize = 12.sp); Text("${frame.title().length} / 40", color = NovelTheme.colors.textSecondary, fontSize = 11.sp) }
        TextField(value = frame.title(), onValueChange = { if (it.length <= 40) controller.frame(requireNotNull(state.chapterId), state.editToken, EditorSession.Frame(it, frame.body(), frame.start(), frame.end(), frame.scroll())) }, enabled = !state.busy, singleLine = true, textStyle = LocalTextStyle.current.copy(fontFamily = NovelTypography.serif, fontSize = 25.sp), modifier = Modifier.fillMaxWidth(), colors = TextFieldDefaults.colors(focusedContainerColor = NovelTheme.colors.background, unfocusedContainerColor = NovelTheme.colors.background))
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("正文编辑", fontSize = 12.sp); Text("正文模式 · 自动换行", color = NovelTheme.colors.textSecondary, fontSize = 10.sp) }
        val colors = NovelTheme.colors
        key(state.chapterId, state.editToken) {
            // A new chapter gets a new InputConnection; a queued old IME event keeps its old callback token.
            AndroidView(factory = { NovelManuscriptView(it) }, modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, colors.outline, RoundedCornerShape(14.dp)), update = { it.bind(requireNotNull(state.chapterId), frame, !state.busy, colors.text.toArgb(), colors.textSecondary.toArgb(), colors.primary.toArgb(), lines, font) { controller.frame(requireNotNull(state.chapterId), state.editToken, it) } })
        }
        val offset = frame.start().coerceIn(0, frame.body().length)
        val row = frame.body().take(offset).count { it == '\n' } + 1
        val col = offset - frame.body().lastIndexOf('\n', offset - 1)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("行 $row，列 $col", color = colors.primary, fontSize = 10.sp); Text("正文 ${font.toInt()}sp · ${if (lines) "行号已开启" else "行号已关闭"}", color = colors.textSecondary, fontSize = 10.sp) }
        Surface(shape = RoundedCornerShape(18.dp), color = colors.surfaceVariant, modifier = Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = controller::undo, enabled = state.canUndo && !state.busy) { Icon(NovelIcons.Undo2, "撤销") }
            IconButton(onClick = controller::redo, enabled = state.canRedo && !state.busy) { Icon(NovelIcons.Redo2, "重做") }
            IconButton(onClick = { controller.insert("\n\n　　") }, enabled = !state.busy) { Icon(NovelIcons.Pilcrow, "新段落并缩进") }
            IconButton(onClick = { controller.insert("\n\n———\n\n") }, enabled = !state.busy) { Icon(NovelIcons.Minus, "插入分隔线") }
            IconButton(onClick = { tools = true }) { Icon(NovelIcons.Settings, "编辑设置") }
        } }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("本章 ${frame.body().length} 字 · 预计阅读 ${(frame.body().length / 400 + 1)} 分钟", color = colors.textSecondary, fontSize = 10.sp); Text("全书 ${state.directory?.structure?.work()?.characterCount() ?: 0} 字", color = colors.textSecondary, fontSize = 10.sp) }
    }
    if (directory) ModalBottomSheet(onDismissRequest = { directory = false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp)) { itemsIndexed(chapters, key = { _, c -> c.id() }) { i, c -> ListItem(headlineContent = { Text("${i + 1} · ${c.title()}") }, modifier = Modifier.clickable { directory = false; controller.openChapter(c.id()) }) } }
    }
    if (tools) AlertDialog(onDismissRequest = { tools = false }, title = { Text("编辑设置") }, text = { Column { Row(verticalAlignment = Alignment.CenterVertically) { Text("显示行号", modifier = Modifier.weight(1f)); Switch(lines, { lines = it }) }; Text("字号 ${font.toInt()} sp"); Slider(font, { font = it }, valueRange = 12f..24f, steps = 11) } }, confirmButton = { TextButton(onClick = { tools = false }) { Text("完成") } })
}

@Composable
private fun WorkManagement(state: NovelEditorState, controller: NovelEditorController, onPreview: (String, String?) -> Unit, onDeleteChapter: (String) -> Unit) {
    val directory = state.directory ?: return
    var filter by remember { mutableStateOf("all") }; var reverse by remember { mutableStateOf(false) }
    val chapters = directory.structure.chapters()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Surface(color = NovelTheme.colors.primaryContainer, shape = RoundedCornerShape(6.dp), modifier = Modifier.width(86.dp).height(118.dp)) { Box(contentAlignment = Alignment.Center) { Text(directory.metadata.title, fontFamily = NovelTypography.serif, fontSize = 15.sp, modifier = Modifier.padding(10.dp)) } }
            Column { Text(directory.metadata.title, fontFamily = NovelTypography.serif, fontSize = 25.sp); Text("${when (directory.metadata.type) { "repost" -> "授权转载"; "ai" -> "AI辅助"; else -> "原创" }} · ${if (directory.metadata.completed) "已完结" else "连载中"}", color = NovelTheme.colors.primary, modifier = Modifier.padding(vertical = 8.dp)); Text("${chapters.size} 章 · ${directory.structure.work().characterCount()} 字", color = NovelTheme.colors.textSecondary, fontSize = 12.sp); Row { TextButton(onClick = controller::showMetadata) { Text("编辑资料") }; TextButton(onClick = { controller.preview(onPreview) }) { Text("本地预览") } } }
        } }
        directory.server?.let { server -> item { HorizontalDivider(); Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) { listOf("reads" to "累计阅读", "collections" to "收藏", "comments" to "评论").forEach { (key, label) -> Column { Text(label, color = NovelTheme.colors.textSecondary, fontSize = 11.sp); Text(server.optLong(key).toString(), fontSize = 25.sp) } } }; HorizontalDivider() } }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("章节管理", fontFamily = NovelTypography.serif, fontSize = 21.sp, modifier = Modifier.weight(1f)); TextButton(onClick = { reverse = !reverse }) { Icon(NovelIcons.ArrowDownUp, null, Modifier.size(16.dp)); Text(if (reverse) "正序" else "倒序") } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("all" to "全部", "published" to "已发布", "draft" to "草稿", "pending" to "审核中").forEach { (value, label) -> FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(label, fontSize = 11.sp) }) } } }
        val filtered = (if (reverse) chapters.reversed() else chapters).filter { c -> val status = directory.remote[c.id()]?.status() ?: "draft"; filter == "all" || status == filter || (filter == "draft" && status == "rejected") }
        itemsIndexed(filtered, key = { _, c -> c.id() }) { _, chapter ->
            val index = chapters.indexOf(chapter); val remote = directory.remote[chapter.id()]
            Column {
                Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { controller.openChapter(chapter.id()) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", fontSize = 20.sp, color = NovelTheme.colors.textSecondary, modifier = Modifier.width(32.dp))
                    Column(Modifier.weight(1f)) { Text(chapter.title(), fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Text("${when (remote?.status()) { "published" -> "已发布"; "pending", "reviewing" -> "审核中"; "rejected" -> "已驳回"; else -> "草稿" }} · ${chapter.contentLength()} 字", color = NovelTheme.colors.textSecondary, fontSize = 11.sp); remote?.reason()?.takeIf { it.isNotBlank() }?.let { Text(it, color = NovelTheme.colors.error, fontSize = 11.sp) } }
                    IconButton(onClick = { if (index > 0) { val ids = chapters.map { it.id() }.toMutableList(); java.util.Collections.swap(ids, index, index - 1); controller.reorder(ids) } }, enabled = index > 0 && !state.busy) { Icon(NovelIcons.ChevronUp, "上移章节", Modifier.size(18.dp)) }
                    IconButton(onClick = { onDeleteChapter(chapter.id()) }, enabled = !state.busy) { Icon(NovelIcons.Trash, "移除本地章节", Modifier.size(18.dp)) }
                    Icon(NovelIcons.ChevronRight, "编辑章节", tint = NovelTheme.colors.primary, modifier = Modifier.size(20.dp))
                }
                HorizontalDivider(color = NovelTheme.colors.outline)
            }
        }
        item { Text("本地修改与审核版本互不覆盖。只有明确选择并提交的章节会上传。", fontSize = 11.sp, color = NovelTheme.colors.textSecondary, modifier = Modifier.padding(vertical = 18.dp)) }
    }
}
