@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.joinmastodon.android.novel.app.editor

import org.joinmastodon.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import org.joinmastodon.android.novel.app.NovelActionLabel
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
                item { Surface(shape = RoundedCornerShape(22.dp), color = NovelTheme.colors.primaryContainer.copy(alpha = .55f), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { Icon(NovelIcons.FileUp, null, Modifier.size(46.dp), tint = NovelTheme.colors.primary); Text(stringResource(R.string.novel_en_import_formats_limit), fontSize = 11.sp, color = NovelTheme.colors.textSecondary); Button(onClick = { picker.launch(arrayOf("text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }, enabled = !state.busy) { Icon(NovelIcons.FolderOpen, null, Modifier.size(20.dp)); Text(stringResource(R.string.novel_en_choose_local_file)) }; Text(uri?.lastPathSegment ?: stringResource(R.string.novel_en_no_file_selected), fontSize = 11.sp, color = NovelTheme.colors.textSecondary) } } }
                item { Text(stringResource(R.string.novel_en_import_into), fontSize = 15.sp); Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { FilterChip(!existing, { existing = false }, label = { Text(stringResource(R.string.novel_en_create_work)) }); FilterChip(existing, { existing = true }, enabled = localWorks.isNotEmpty(), label = { Text(stringResource(R.string.novel_en_existing_work)) }) }; if (existing) localWorks.forEach { work -> Row(Modifier.fillMaxWidth().clickable { target = work.localId }, verticalAlignment = Alignment.CenterVertically) { RadioButton(target == work.localId, { target = work.localId }); Text(work.title, fontSize = 13.sp) } }; Text(stringResource(R.string.novel_en_import_append_only), fontSize = 11.sp, color = NovelTheme.colors.textSecondary) }
                item { Text(stringResource(R.string.novel_en_chapter_detection), fontFamily = NovelTypography.serif, fontSize = 21.sp); Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(stringResource(R.string.novel_en_auto_detect_chapters), fontSize = 14.sp); Text(stringResource(R.string.novel_en_chapter_detection_example), fontSize = 11.sp, color = NovelTheme.colors.textSecondary) }; Switch(auto, { auto = it }, enabled = !state.busy) } }
                item { Text(stringResource(R.string.novel_en_import_tips), fontSize = 15.sp); Text(stringResource(R.string.novel_en_import_tips_body), fontSize = 12.sp, color = NovelTheme.colors.textSecondary, modifier = Modifier.padding(vertical = 16.dp)) }
            } else {
                item { Surface(color = NovelTheme.colors.primaryContainer.copy(alpha = .5f), shape = RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth().padding(18.dp)) { Text(state.importName, fontSize = 17.sp); Text(stringResource(R.string.novel_en_import_summary, chapters.size, chapters.sumOf { it.content().length }), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)); Text(stringResource(R.string.novel_en_import_local_destination, if (existing) stringResource(R.string.novel_en_import_existing_destination) else stringResource(R.string.novel_en_import_new_destination)), fontSize = 11.sp, color = NovelTheme.colors.textSecondary) } } }
                if (state.page == "adjust") {
                    item { Text(stringResource(R.string.novel_en_chapter_adjust_instructions), color = NovelTheme.colors.primary, fontSize = 12.sp); Row { TextButton(onClick = { controller.adjust { it.reset() }; selected = emptySet() }) { Text(stringResource(R.string.novel_en_reset)) }; TextButton(onClick = { selected = if (selected.size == chapters.size) emptySet() else chapters.indices.toSet() }) { Text(stringResource(R.string.novel_en_select_all_toggle)) } } }
                } else item { Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.novel_en_chapter_detection), fontFamily = NovelTypography.serif, fontSize = 21.sp, modifier = Modifier.weight(1f)); TextButton(onClick = { controller.importPage("adjust") }) { Text(stringResource(R.string.novel_en_adjust_chapters)) } } }
                itemsIndexed(chapters, key = { i, _ -> i }) { index, chapter ->
                    Surface(shape = RoundedCornerShape(14.dp), color = if ((state.page == "adjust" && index in selected) || (state.page != "adjust" && index == preview)) NovelTheme.colors.primaryContainer else NovelTheme.colors.surfaceVariant.copy(alpha = .25f), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.page == "adjust") Checkbox(index in selected, { checked -> selected = if (checked) selected + index else selected - index }) else Icon(NovelIcons.FileText, null, Modifier.padding(8.dp).size(20.dp), tint = NovelTheme.colors.primary)
                            Column(Modifier.weight(1f).clickable { if (state.page == "adjust") rename = index else preview = index }) { Text(stringResource(R.string.novel_en_chapter_number_title, index + 1, chapter.title()), fontSize = 12.sp); Text(pluralStringResource(R.plurals.novel_en_character_count, chapter.content().length, chapter.content().length), color = NovelTheme.colors.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp)) }
                            if (state.page == "adjust") {
                                IconButton(onClick = { if (index > 0) { controller.adjust { it.move(index, index - 1) }; selected = emptySet() } }, enabled = index > 0, modifier = Modifier.size(32.dp)) { Icon(NovelIcons.ChevronUp, stringResource(R.string.novel_en_move_up), Modifier.size(18.dp)) }
                                IconButton(onClick = { if (index < chapters.lastIndex) { controller.adjust { it.move(index, index + 1) }; selected = emptySet() } }, enabled = index < chapters.lastIndex, modifier = Modifier.size(32.dp)) { Icon(NovelIcons.ChevronDown, stringResource(R.string.novel_en_move_down), Modifier.size(18.dp)) }
                                Icon(NovelIcons.GripVertical, stringResource(R.string.novel_en_drag_chapter), Modifier.size(30.dp).pointerInput(index, state.importRevision) { var distance = 0f; detectDragGesturesAfterLongPress(onDragStart = { distance = 0f }, onDrag = { change, delta -> change.consume(); distance += delta.y }, onDragEnd = { val next = (index + (distance / 70.dp.toPx()).toInt()).coerceIn(0, chapters.lastIndex); if (next != index) { controller.adjust { it.move(index, next) }; selected = emptySet() } }) }, tint = NovelTheme.colors.textSecondary)
                            }
                        }
                    }
                }
                if (state.page == "adjust") item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = selected.size >= 2, onClick = { controller.adjust { it.merge(selected.toList()) }; selected = emptySet() }) { Text(pluralStringResource(R.plurals.novel_en_merge_chapters, selected.size, selected.size)) }
                    TextButton(enabled = selected.size == 1, onClick = { rename = selected.single() }) { Text(stringResource(R.string.novel_en_rename)) }
                    TextButton(enabled = selected.size == 1, onClick = { split = selected.single() }) { Text(stringResource(R.string.novel_en_split_chapter)) }
                } }
                else {
                    item { Text(stringResource(R.string.novel_en_body_preview_readonly), fontFamily = NovelTypography.serif, fontSize = 21.sp) }
                    chapters.getOrNull(preview.coerceIn(0, chapters.lastIndex.coerceAtLeast(0)))?.let { chapter -> item { Surface(shape = RoundedCornerShape(18.dp), color = NovelTheme.colors.surfaceVariant.copy(alpha = .25f)) { Column(Modifier.padding(20.dp)) { Text(chapter.title(), fontFamily = NovelTypography.serif, fontSize = 18.sp); Text(chapter.content().take(4000), fontFamily = NovelTypography.serif, fontSize = 14.sp, lineHeight = 25.sp, modifier = Modifier.padding(vertical = 18.dp)); Text(stringResource(R.string.novel_en_preview_excerpt_notice), color = NovelTheme.colors.textSecondary, fontSize = 11.sp) } } } }
                    state.importResult?.warnings()?.forEach { warning -> item { Text(warning.userMessage(androidx.compose.ui.platform.LocalContext.current), fontSize = 11.sp, color = NovelTheme.colors.textSecondary) } }
                }
                item { Text(stringResource(R.string.novel_en_import_changes_local), color = NovelTheme.colors.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(vertical = 18.dp)) }
            }
        }
        Button(onClick = { when (state.page) { "import" -> uri?.let { controller.parse(it, auto) }; "adjust" -> controller.importPage("importPreview"); else -> confirm = true } }, enabled = !state.busy && (state.page != "import" || uri != null) && (!existing || target != null), modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp).heightIn(min = 48.dp)) { Text(when (state.page) { "import" -> stringResource(R.string.novel_en_parse_preview); "adjust" -> stringResource(R.string.novel_en_finish_adjusting); else -> stringResource(R.string.novel_en_confirm_import_drafts) }) }
    }
    rename?.let { index -> var name by remember(index) { mutableStateOf(chapters[index].title()) }; AlertDialog(onDismissRequest = { rename = null }, title = { Text(stringResource(R.string.novel_en_rename_chapter)) }, text = { OutlinedTextField(name, { if (it.length <= 40) name = it }, label = { Text(stringResource(R.string.novel_en_chapter_name)) }) }, confirmButton = { TextButton(onClick = { controller.adjust { it.rename(index, name) }; rename = null }) { Text(stringResource(R.string.novel_en_confirm)) } }, dismissButton = { TextButton(onClick = { rename = null }) { Text(stringResource(R.string.novel_en_cancel)) } }) }
    split?.let { index -> NovelSplitDialog(chapters[index].title(), chapters[index].content(), { split = null }) { offset, first, second -> controller.adjust { it.split(index, offset, first, second) }; split = null; selected = emptySet() } }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.novel_en_confirm_import)) }, text = { Text(stringResource(R.string.novel_en_import_confirmation, chapters.size, if (existing) stringResource(R.string.novel_en_selected_work_destination) else stringResource(R.string.novel_en_new_work_destination))) }, confirmButton = { TextButton(onClick = { confirm = false; controller.confirmImport(if (existing) target else null) }) { Text(stringResource(R.string.novel_en_confirm_local_save)) } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.novel_en_cancel)) } })
}

@Composable
private fun NovelSplitDialog(title: String, body: String, dismiss: () -> Unit, confirm: (Int, String, String) -> Unit) {
    val boundaries = remember(body) { ImportReview.paragraphBoundaries(body) }
    var boundary by remember { mutableIntStateOf(boundaries.firstOrNull() ?: 0) }
    val secondTitle = stringResource(R.string.novel_en_split_second_title, title.take(30))
    var first by remember { mutableStateOf(title) }; var second by remember { mutableStateOf(secondTitle) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.novel_en_split_chapter), fontFamily = NovelTypography.serif) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.novel_en_split_instructions), fontSize = 12.sp)
            LazyColumn(Modifier.heightIn(max = 250.dp)) {
                itemsIndexed(boundaries) { i, offset -> Column {
                    val start = if (i == 0) 0 else boundaries[i - 1]
                    Text(body.substring(start, offset).take(500), fontFamily = NovelTypography.serif, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth().background(if (boundary == offset) NovelTheme.colors.primaryContainer else NovelTheme.colors.background).clickable { boundary = offset }, verticalAlignment = Alignment.CenterVertically) { RadioButton(boundary == offset, { boundary = offset }); Text(stringResource(R.string.novel_en_split_after_paragraph, i + 1), fontSize = 11.sp) }
                } }
                item { Text(body.substring(boundaries.lastOrNull() ?: 0).take(500), fontFamily = NovelTypography.serif, fontSize = 12.sp) }
            }
            if (boundaries.isEmpty()) Text(stringResource(R.string.novel_en_no_split_boundary), color = NovelTheme.colors.error)
            OutlinedTextField(first, { first = it.take(40) }, label = { Text(stringResource(R.string.novel_en_split_first_length, boundary)) }, singleLine = true)
            OutlinedTextField(second, { second = it.take(40) }, label = { Text(stringResource(R.string.novel_en_split_second_length, body.length - boundary)) }, singleLine = true)
        }
    }, confirmButton = { TextButton(enabled = boundaries.isNotEmpty() && first.isNotBlank() && second.isNotBlank(), onClick = { confirm(boundary, first, second) }) { Text(stringResource(R.string.novel_en_confirm_split)) } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.novel_en_cancel)) } })
}
