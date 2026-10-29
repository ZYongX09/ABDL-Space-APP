package org.joinmastodon.android.novel.app.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.novel.app.design.NovelIcons
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.joinmastodon.android.novel.app.design.NovelTypography
import org.joinmastodon.noveleditor.ImportReview

@Composable
internal fun NovelImportScreen(state: NovelEditorState, controller: NovelEditorController) {
    var uri by remember { mutableStateOf<Uri?>(null) }; var auto by remember { mutableStateOf(true) }
    var existing by remember { mutableStateOf(state.workId != null) }
    var localWorks by remember { mutableStateOf(emptyList<LocalNovelSummary>()) }
    var target by remember { mutableStateOf(state.workId) }
    var selected by remember(state.importJob) { mutableStateOf(setOf<Int>()) }
    var preview by remember { mutableIntStateOf(0) }
    var rename by remember { mutableStateOf<Int?>(null) }
    var split by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri = it }
    val chapters = state.importResult?.chapters().orEmpty()
    LaunchedEffect(state.page) { if (state.page == "import") localWorks = listLocalWorks(controller.repository.context, controller.repository.accountId) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (state.page == "import") {
                item { Text("① 选择文稿    ——    ② 解析预览", color = NovelTheme.colors.primary, fontSize = 13.sp) }
                item { Surface(shape = RoundedCornerShape(22.dp), color = NovelTheme.colors.primaryContainer.copy(alpha = .55f), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { Icon(NovelIcons.FileUp, null, Modifier.size(46.dp), tint = NovelTheme.colors.primary); Text("导入你的大作", fontFamily = NovelTypography.serif, fontSize = 22.sp); Text("支持 TXT、DOCX · 单个文件不超过 10 MB", fontSize = 11.sp, color = NovelTheme.colors.textSecondary); Button(onClick = { picker.launch(arrayOf("text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }, enabled = !state.busy) { Icon(NovelIcons.FolderOpen, null, Modifier.size(20.dp)); Text(" 选择本地文件") }; Text(uri?.lastPathSegment ?: "尚未选择文件", fontSize = 11.sp, color = NovelTheme.colors.textSecondary) } } }
                item { Text("导入到", fontSize = 15.sp); Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { FilterChip(!existing, { existing = false }, label = { Text("新建作品") }); FilterChip(existing, { existing = true }, enabled = localWorks.isNotEmpty(), label = { Text("已有作品") }) }; if (existing) localWorks.forEach { work -> Row(Modifier.fillMaxWidth().clickable { target = work.localId }, verticalAlignment = Alignment.CenterVertically) { RadioButton(target == work.localId, { target = work.localId }); Text(work.title, fontSize = 13.sp) } }; Text("只追加本地新草稿章节，不会覆盖既有草稿或已发版本。", fontSize = 11.sp, color = NovelTheme.colors.textSecondary) }
                item { Text("章节识别", fontFamily = NovelTypography.serif, fontSize = 21.sp); Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("自动识别章节标题", fontSize = 14.sp); Text("例如：第一章、Chapter 1", fontSize = 11.sp, color = NovelTheme.colors.textSecondary) }; Switch(auto, { auto = it }, enabled = !state.busy) } }
                item { Text("导入前的小提示", fontSize = 15.sp); Text("· TXT 建议 UTF-8 编码，避免中文乱码。\n\n· 仅导入正文和章节标题，图片及复杂排版不保留。\n\n· 可预览、重排、合并、按段落拆分；二次确认后才写入本地作品。\n\n· 仅上传有权发布的文稿。导入默认私密，绝不自动发布。", fontSize = 12.sp, color = NovelTheme.colors.textSecondary, modifier = Modifier.padding(vertical = 16.dp)) }
            } else {
                item { Surface(color = NovelTheme.colors.primaryContainer.copy(alpha = .5f), shape = RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth().padding(18.dp)) { Text(state.importName, fontSize = 17.sp); Text("${chapters.size} 个章节 · ${chapters.sumOf { it.content().length }} 字正文", fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)); Text("仅本机解析，未上传。${if (existing) "导入到已有作品" else "导入到新建作品"}", fontSize = 11.sp, color = NovelTheme.colors.textSecondary) } } }
                if (state.page == "adjust") {
                    item { Text("长按右侧手柄上下拖动；也可用上下箭头。合并须选择相邻章节，重命名与拆分仅选一章。", color = NovelTheme.colors.primary, fontSize = 12.sp); Row { TextButton(onClick = { controller.adjust { it.reset() }; selected = emptySet() }) { Text("重置") }; TextButton(onClick = { selected = if (selected.size == chapters.size) emptySet() else chapters.indices.toSet() }) { Text("全选 / 取消") } } }
                } else item { Row(verticalAlignment = Alignment.CenterVertically) { Text("章节识别", fontFamily = NovelTypography.serif, fontSize = 21.sp, modifier = Modifier.weight(1f)); TextButton(onClick = { controller.importPage("adjust") }) { Text("调整章节") } } }
                itemsIndexed(chapters, key = { i, _ -> i }) { index, chapter ->
                    Surface(shape = RoundedCornerShape(14.dp), color = if ((state.page == "adjust" && index in selected) || (state.page != "adjust" && index == preview)) NovelTheme.colors.primaryContainer else NovelTheme.colors.surfaceVariant.copy(alpha = .25f), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.page == "adjust") Checkbox(index in selected, { checked -> selected = if (checked) selected + index else selected - index }) else Icon(NovelIcons.FileText, null, Modifier.padding(8.dp).size(20.dp), tint = NovelTheme.colors.primary)
                            Column(Modifier.weight(1f).clickable { if (state.page == "adjust") rename = index else preview = index }) { Text("第 ${index + 1} 章 · ${chapter.title()}", fontSize = 12.sp); Text("${chapter.content().length} 字", color = NovelTheme.colors.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp)) }
                            if (state.page == "adjust") {
                                IconButton(onClick = { if (index > 0) { controller.adjust { it.move(index, index - 1) }; selected = emptySet() } }, enabled = index > 0, modifier = Modifier.size(32.dp)) { Icon(NovelIcons.ChevronUp, "上移", Modifier.size(18.dp)) }
                                IconButton(onClick = { if (index < chapters.lastIndex) { controller.adjust { it.move(index, index + 1) }; selected = emptySet() } }, enabled = index < chapters.lastIndex, modifier = Modifier.size(32.dp)) { Icon(NovelIcons.ChevronDown, "下移", Modifier.size(18.dp)) }
                                Icon(NovelIcons.GripVertical, "长按拖动章节", Modifier.size(30.dp).pointerInput(index, state.importRevision) { var distance = 0f; detectDragGesturesAfterLongPress(onDragStart = { distance = 0f }, onDrag = { change, delta -> change.consume(); distance += delta.y }, onDragEnd = { val next = (index + (distance / 70.dp.toPx()).toInt()).coerceIn(0, chapters.lastIndex); if (next != index) { controller.adjust { it.move(index, next) }; selected = emptySet() } }) }, tint = NovelTheme.colors.textSecondary)
                            }
                        }
                    }
                }
                if (state.page == "adjust") item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = selected.size >= 2, onClick = { controller.adjust { it.merge(selected.toList()) }; selected = emptySet() }) { Text("合并 ${selected.size} 章") }
                    TextButton(enabled = selected.size == 1, onClick = { rename = selected.single() }) { Text("重命名") }
                    TextButton(enabled = selected.size == 1, onClick = { split = selected.single() }) { Text("拆分章节") }
                } }
                else {
                    item { Text("正文预览 · 只读", fontFamily = NovelTypography.serif, fontSize = 21.sp) }
                    chapters.getOrNull(preview.coerceIn(0, chapters.lastIndex.coerceAtLeast(0)))?.let { chapter -> item { Surface(shape = RoundedCornerShape(18.dp), color = NovelTheme.colors.surfaceVariant.copy(alpha = .25f)) { Column(Modifier.padding(20.dp)) { Text(chapter.title(), fontFamily = NovelTypography.serif, fontSize = 18.sp); Text(chapter.content().take(4000), fontFamily = NovelTypography.serif, fontSize = 14.sp, lineHeight = 25.sp, modifier = Modifier.padding(vertical = 18.dp)); Text("仅展示正文节选，确认保存后可完整阅读和编辑。", color = NovelTheme.colors.textSecondary, fontSize = 11.sp) } } } }
                    state.importResult?.warnings()?.forEach { warning -> item { Text(warning.message(), fontSize = 11.sp, color = NovelTheme.colors.textSecondary) } }
                }
                item { Text("只调整本次导入内容，不影响已发布作品。确认后仅保存为本地草稿。", color = NovelTheme.colors.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(vertical = 18.dp)) }
            }
        }
        Button(onClick = { when (state.page) { "import" -> uri?.let { controller.parse(it, auto) }; "adjust" -> controller.importPage("importPreview"); else -> confirm = true } }, enabled = !state.busy && (state.page != "import" || uri != null) && (!existing || target != null), modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp).height(48.dp)) { Text(when (state.page) { "import" -> "解析并预览  →"; "adjust" -> "完成调整"; else -> "确认导入为草稿" }) }
    }
    rename?.let { index -> var name by remember(index) { mutableStateOf(chapters[index].title()) }; AlertDialog(onDismissRequest = { rename = null }, title = { Text("重命名章节") }, text = { OutlinedTextField(name, { if (it.length <= 40) name = it }, label = { Text("章节名称") }) }, confirmButton = { TextButton(onClick = { controller.adjust { it.rename(index, name) }; rename = null }) { Text("确认") } }, dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } }) }
    split?.let { index -> NovelSplitDialog(chapters[index].title(), chapters[index].content(), { split = null }) { offset, first, second -> controller.adjust { it.split(index, offset, first, second) }; split = null; selected = emptySet() } }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("确认导入") }, text = { Text("将把 ${chapters.size} 章追加为${if (existing) "所选作品的" else "新作品的"}本地草稿。不会覆盖任何既有章节，不上传也不自动发布。是否继续？") }, confirmButton = { TextButton(onClick = { confirm = false; controller.confirmImport(if (existing) target else null) }) { Text("确认保存本地") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

@Composable
private fun NovelSplitDialog(title: String, body: String, dismiss: () -> Unit, confirm: (Int, String, String) -> Unit) {
    val boundaries = remember(body) { ImportReview.paragraphBoundaries(body) }
    var boundary by remember { mutableIntStateOf(boundaries.firstOrNull() ?: 0) }
    var first by remember { mutableStateOf(title) }; var second by remember { mutableStateOf("${title.take(30)}（下）") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("拆分章节", fontFamily = NovelTypography.serif) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("点击段落之间的空隙设置拆分点。总字数不变，原正文不会丢失。", fontSize = 12.sp)
            LazyColumn(Modifier.heightIn(max = 250.dp)) {
                itemsIndexed(boundaries) { i, offset -> Column {
                    val start = if (i == 0) 0 else boundaries[i - 1]
                    Text(body.substring(start, offset).take(500), fontFamily = NovelTypography.serif, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth().background(if (boundary == offset) NovelTheme.colors.primaryContainer else NovelTheme.colors.background).clickable { boundary = offset }, verticalAlignment = Alignment.CenterVertically) { RadioButton(boundary == offset, { boundary = offset }); Text("在第 ${i + 1} 段后拆分", fontSize = 11.sp) }
                } }
                item { Text(body.substring(boundaries.lastOrNull() ?: 0).take(500), fontFamily = NovelTypography.serif, fontSize = 12.sp) }
            }
            if (boundaries.isEmpty()) Text("没有可拆分的段落，请先在正文中添加换行。", color = NovelTheme.colors.error)
            OutlinedTextField(first, { first = it.take(40) }, label = { Text("前一章 · $boundary 字") }, singleLine = true)
            OutlinedTextField(second, { second = it.take(40) }, label = { Text("后一章 · ${body.length - boundary} 字") }, singleLine = true)
        }
    }, confirmButton = { TextButton(enabled = boundaries.isNotEmpty() && first.isNotBlank() && second.isNotBlank(), onClick = { confirm(boundary, first, second) }) { Text("确认拆分") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
