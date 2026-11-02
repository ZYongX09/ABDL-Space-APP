@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.joinmastodon.android.novel.app.editor

import org.joinmastodon.android.novel.app.novelMessage

import org.joinmastodon.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import org.joinmastodon.android.novel.app.NovelActionLabel
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
    val defaultExportName = stringResource(R.string.novel_en_default_export_name)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(controller::exportTo) }
    var bodyFocused by remember(state.page, state.chapterId, state.editToken) { mutableStateOf(false) }
    var manuscriptView by remember(state.page, state.chapterId, state.editToken) { mutableStateOf<NovelManuscriptView?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val compact = state.page == "editor" && (bodyFocused || WindowInsets.ime.getBottom(LocalDensity.current) > 0)
    val title = when (state.page) { "metadata" -> if (state.workId == null) stringResource(R.string.novel_en_create_work) else stringResource(R.string.novel_en_work_metadata); "editor" -> stringResource(R.string.novel_en_novel_editor); "import" -> stringResource(R.string.novel_en_import_manuscript); "importPreview" -> stringResource(R.string.novel_en_parse_preview_title); "adjust" -> stringResource(R.string.novel_en_adjust_chapters); else -> stringResource(R.string.novel_en_work_management) }
    Scaffold(
        containerColor = NovelTheme.colors.background,
        modifier = Modifier.imePadding(),
        topBar = { Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = if (compact) 8.dp else 20.dp, vertical = if (compact) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onBack, enabled = !state.busy) { Icon(NovelIcons.ArrowLeft, stringResource(R.string.novel_en_back)) }
            NovelActionLabel(if (compact) state.frame?.title().orEmpty() else title, Modifier.weight(1f).padding(start = 12.dp), size = if (compact) 16 else 25, minimum = 12)
            if (compact) TextButton(onClick = { keyboard?.hide(); focusManager.clearFocus(force = true); manuscriptView?.clearBodyFocus(); bodyFocused = false }) { Text(stringResource(R.string.novel_en_done), maxLines = 1) }
            if (state.workId != null) IconButton(onClick = { more = true }) { Icon(NovelIcons.MoreHorizontal, stringResource(R.string.novel_en_more_actions)) }
        } },
        bottomBar = {
            when (state.page) {
                "editor" -> if (compact) {
                    Row(Modifier.fillMaxWidth().background(NovelTheme.colors.background).navigationBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = controller::undo, enabled = state.canUndo && !state.busy) { Icon(NovelIcons.Undo2, stringResource(R.string.novel_en_undo)) }
                        IconButton(onClick = controller::redo, enabled = state.canRedo && !state.busy) { Icon(NovelIcons.Redo2, stringResource(R.string.novel_en_redo)) }
                        IconButton(onClick = { controller.insert("\n\n　　") }, enabled = !state.busy) { Icon(NovelIcons.Pilcrow, stringResource(R.string.novel_en_insert_paragraph)) }
                        if (state.saving || state.dirty) Text(if (state.saving) stringResource(R.string.novel_en_saving) else stringResource(R.string.novel_en_autosave_short), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = NovelTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                        else Spacer(Modifier.weight(1f))
                        IconButton(onClick = controller::saveExplicit, enabled = !state.busy) { Icon(NovelIcons.CheckCheck, stringResource(R.string.novel_en_save_draft)) }
                    }
                } else Column(Modifier.fillMaxWidth().background(NovelTheme.colors.background).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (state.saving || state.dirty) Text(if (state.saving) stringResource(R.string.novel_en_saving_on_device) else stringResource(R.string.novel_en_unsaved_autosave), fontSize = 11.sp, color = NovelTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                        else Spacer(Modifier.weight(1f))
                        TextButton(onClick = { controller.preview(onPreview) }, enabled = !state.busy) { Icon(NovelIcons.Eye, null, Modifier.size(17.dp)); Text(stringResource(R.string.novel_en_preview_action)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(onClick = controller::saveExplicit, enabled = !state.busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { NovelActionLabel(stringResource(R.string.novel_en_save_draft)) }
                        Button(onClick = { submitSelected = setOfNotNull(state.chapterId); submit = true }, enabled = !state.busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), modifier = Modifier.weight(1.6f).heightIn(min = 48.dp)) { NovelActionLabel(stringResource(R.string.novel_en_submit_review)) }
                    }
                }
                "manage" -> Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { submitSelected = emptySet(); submit = true }, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { NovelActionLabel(stringResource(R.string.novel_en_batch_submit)) }
                    Button(onClick = controller::newChapter, enabled = !state.busy, modifier = Modifier.weight(1.4f).heightIn(min = 48.dp)) { Icon(NovelIcons.Plus, null); NovelActionLabel(stringResource(R.string.novel_en_new_chapter_action), Modifier.weight(1f, fill = false)) }
                }
                "metadata" -> Button(onClick = controller::storeMetadata, enabled = !state.busy, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp).heightIn(min = 48.dp)) { Text(if (state.workId == null) stringResource(R.string.novel_en_write_first_chapter) else stringResource(R.string.novel_en_save_local_metadata)) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { error -> Surface(color = NovelTheme.colors.error.copy(alpha = .09f), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(novelMessage(error), color = NovelTheme.colors.error); TextButton(onClick = controller::clearError) { Text(stringResource(R.string.novel_en_acknowledge)) } } } }
            state.notice?.let { Text(novelMessage(it), color = NovelTheme.colors.primary, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable { controller.clearError() }.padding(16.dp)) }
            state.conflict?.let { message -> Surface(color = NovelTheme.colors.error.copy(alpha = .09f), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(novelMessage(message), color = NovelTheme.colors.error)
                FlowRow { TextButton(onClick = controller::refreshOwner, enabled = !state.busy) { Text(stringResource(R.string.novel_en_refresh_review)) }; TextButton(onClick = { conflictConfirm = true }, enabled = !state.busy) { Text(stringResource(R.string.novel_en_keep_local_reselect_action)) } }
            } } }
            when (state.page) {
                "loading" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                "metadata" -> NovelMetadataForm(state.metadata, controller::metadata, state.busy, controller)
                "editor" -> ManuscriptScreen(state, controller, compact, { bodyFocused = it }, { manuscriptView = it })
                "manage" -> WorkManagement(state, controller, onPreview, { confirmation = "chapter:$it" })
                else -> NovelImportScreen(state, controller)
            }
        }
    }
    if (more) ModalBottomSheet(onDismissRequest = { more = false }, containerColor = NovelTheme.colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.novel_en_work_actions), fontFamily = NovelTypography.serif, fontSize = 23.sp)
            Text(state.directory?.structure?.work()?.title().orEmpty(), color = NovelTheme.colors.textSecondary, modifier = Modifier.padding(vertical = 10.dp))
            listOf("metadata" to stringResource(R.string.novel_en_edit_work_metadata), "refresh" to stringResource(R.string.novel_en_refresh_review), "complete" to stringResource(R.string.novel_en_mark_complete), "export" to stringResource(R.string.novel_en_export_manuscript), "import" to stringResource(R.string.novel_en_import_as_chapters), "retry" to stringResource(R.string.novel_en_retry_submission), "cancelSubmission" to stringResource(R.string.novel_en_abandon_submission), "withdraw" to stringResource(R.string.novel_en_unpublish_work), "delete" to stringResource(R.string.novel_en_delete_work)).forEach { (key, text) ->
                TextButton(onClick = { more = false; when (key) { "metadata" -> controller.showMetadata(); "refresh" -> controller.refreshOwner(); "import" -> controller.showImport(); else -> confirmation = key } }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(text, color = if (key in setOf("withdraw", "delete")) NovelTheme.colors.error else NovelTheme.colors.text, modifier = Modifier.weight(1f)); Icon(NovelIcons.ChevronRight, null) }
                HorizontalDivider(color = NovelTheme.colors.outline)
            }
            TextButton(onClick = { more = false }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text(stringResource(R.string.novel_en_cancel)) }
        }
    }
    confirmation?.let { action ->
        val text = when { action.startsWith("chapter:") -> stringResource(R.string.novel_en_delete_local_chapter_confirmation); action == "complete" -> stringResource(R.string.novel_en_mark_complete_confirmation); action == "export" -> stringResource(R.string.novel_en_export_confirmation); action == "withdraw" -> stringResource(R.string.novel_en_unpublish_confirmation); action == "retry" -> stringResource(R.string.novel_en_retry_submission_confirmation); action == "cancelSubmission" -> stringResource(R.string.novel_en_abandon_submission_confirmation); else -> stringResource(R.string.novel_en_delete_work_confirmation) }
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(stringResource(R.string.novel_en_confirm_again)) }, text = { Text(text) }, confirmButton = { TextButton(onClick = { confirmation = null; when { action.startsWith("chapter:") -> controller.deleteChapter(action.substringAfter(':')); action == "export" -> export.launch("${state.metadata.title.ifBlank { defaultExportName }}.txt"); action == "retry" -> controller.submit(emptyList(), true); action == "cancelSubmission" -> controller.cancelSubmission(); else -> controller.workAction(action, onDelete) } }) { Text(stringResource(R.string.novel_en_confirm)) } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.novel_en_cancel)) } })
    }
    if (conflictConfirm && state.conflict != null) AlertDialog(onDismissRequest = { conflictConfirm = false }, title = { Text(stringResource(R.string.novel_en_keep_local_reselect)) }, text = { Text(stringResource(R.string.novel_en_keep_local_explanation)) }, confirmButton = { TextButton(onClick = { conflictConfirm = false; submitSelected = emptySet(); controller.keepLocalAfterConflict() }, enabled = !state.busy) { Text(stringResource(R.string.novel_en_keep_local_confirm)) } }, dismissButton = { TextButton(onClick = { conflictConfirm = false }) { Text(stringResource(R.string.novel_en_cancel_keep_editing)) } })
    if (submit) AlertDialog(onDismissRequest = { submit = false }, title = { Text(stringResource(R.string.novel_en_submit_selected_title)) }, text = {
        Column {
            Text(stringResource(R.string.novel_en_submit_selected_explanation), fontSize = 12.sp)
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                itemsIndexed(state.directory?.structure?.chapters().orEmpty(), key = { _, c -> c.id() }) { _, chapter ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { submitSelected = if (chapter.id() in submitSelected) submitSelected - chapter.id() else submitSelected + chapter.id() }) { Checkbox(chapter.id() in submitSelected, { selected -> submitSelected = if (selected) submitSelected + chapter.id() else submitSelected - chapter.id() }); Text(chapter.title(), modifier = Modifier.weight(1f)) }
                }
            }
        }
    }, confirmButton = { TextButton(enabled = submitSelected.isNotEmpty(), onClick = { submit = false; controller.submit(state.directory?.structure?.chapters().orEmpty().filter { it.id() in submitSelected }.map { it.id() }) }) { Text(pluralStringResource(R.plurals.novel_en_confirm_submit_count, submitSelected.size, submitSelected.size)) } }, dismissButton = { TextButton(onClick = { submit = false }) { Text(stringResource(R.string.novel_en_cancel)) } })
}

@Composable
private fun ManuscriptScreen(state: NovelEditorState, controller: NovelEditorController, compact: Boolean, focusChanged: (Boolean) -> Unit, attach: (NovelManuscriptView) -> Unit) {
    val frame = state.frame ?: return
    var directory by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(true) }
    var font by remember { mutableFloatStateOf(14f) }
    val chapters = state.directory?.structure?.chapters().orEmpty()
    val index = chapters.indexOfFirst { it.id() == state.chapterId }
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val reduceAuxiliary = screenHeight in 1..700 && LocalDensity.current.fontScale >= 1.5f
    Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 8.dp else 20.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = NovelTheme.colors.primaryContainer, modifier = Modifier.fillMaxWidth().clickable { directory = true }) { Row(Modifier.padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(NovelIcons.List, null, tint = NovelTheme.colors.primary, modifier = Modifier.size(if (compact) 18.dp else 24.dp)); Text(stringResource(R.string.novel_en_chapter_number_title, index + 1, frame.title()), color = NovelTheme.colors.primary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp)); Text(stringResource(R.string.novel_en_contents), fontSize = 12.sp, maxLines = 1); Icon(NovelIcons.ChevronDown, null, Modifier.size(18.dp)) } }
        if (!compact) {
            if (!reduceAuxiliary) Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.novel_en_chapter_local_draft, index + 1), color = NovelTheme.colors.primary, fontSize = 12.sp); Text("${frame.title().length} / 40", color = NovelTheme.colors.textSecondary, fontSize = 11.sp) }
            TextField(value = frame.title(), onValueChange = { if (it.length <= 40) controller.frame(requireNotNull(state.chapterId), state.editToken, EditorSession.Frame(it, frame.body(), frame.start(), frame.end(), frame.scroll())) }, enabled = !state.busy, singleLine = true, textStyle = LocalTextStyle.current.copy(fontFamily = NovelTypography.serif, fontSize = 25.sp), modifier = Modifier.fillMaxWidth(), colors = TextFieldDefaults.colors(focusedContainerColor = NovelTheme.colors.background, unfocusedContainerColor = NovelTheme.colors.background))
        }
        val colors = NovelTheme.colors
        key(state.chapterId, state.editToken) {
            // A new chapter gets a new InputConnection; a queued old IME event keeps its old callback token.
            AndroidView(factory = { NovelManuscriptView(it).also(attach) }, modifier = Modifier.fillMaxWidth().weight(1f).testTag("novel-manuscript").border(1.dp, colors.outline, RoundedCornerShape(14.dp)), update = { view ->
                view.onBodyFocusChanged(focusChanged)
                view.bind(requireNotNull(state.chapterId), frame, !state.busy, colors.text.toArgb(), colors.textSecondary.toArgb(), colors.primary.toArgb(), lines, font) { controller.frame(requireNotNull(state.chapterId), state.editToken, it) }
            })
        }
        if (!compact) {
        val offset = frame.start().coerceIn(0, frame.body().length)
        val row = frame.body().take(offset).count { it == '\n' } + 1
        val col = offset - frame.body().lastIndexOf('\n', offset - 1)
        if (!reduceAuxiliary) Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.novel_en_cursor_position, row, col), color = colors.primary, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.novel_en_editor_font_lines, font.toInt(), if (lines) stringResource(R.string.novel_en_line_numbers_on) else stringResource(R.string.novel_en_line_numbers_off)), color = colors.textSecondary, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Surface(shape = RoundedCornerShape(18.dp), color = colors.surfaceVariant, modifier = Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = controller::undo, enabled = state.canUndo && !state.busy) { Icon(NovelIcons.Undo2, stringResource(R.string.novel_en_undo)) }
            IconButton(onClick = controller::redo, enabled = state.canRedo && !state.busy) { Icon(NovelIcons.Redo2, stringResource(R.string.novel_en_redo)) }
            IconButton(onClick = { controller.insert("\n\n　　") }, enabled = !state.busy) { Icon(NovelIcons.Pilcrow, stringResource(R.string.novel_en_insert_paragraph)) }
            IconButton(onClick = { controller.insert("\n\n———\n\n") }, enabled = !state.busy) { Icon(NovelIcons.Minus, stringResource(R.string.novel_en_insert_divider)) }
            IconButton(onClick = { tools = true }) { Icon(NovelIcons.Settings, stringResource(R.string.novel_en_editor_settings)) }
        } }
        if (!reduceAuxiliary) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.novel_en_chapter_reading_estimate, frame.body().length, (frame.body().length / 400 + 1)), color = colors.textSecondary, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.novel_en_book_character_count, state.directory?.structure?.work()?.characterCount() ?: 0), color = colors.textSecondary, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        }
    }
    if (directory) ModalBottomSheet(onDismissRequest = { directory = false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp)) { itemsIndexed(chapters, key = { _, c -> c.id() }) { i, c -> ListItem(headlineContent = { Text("${i + 1} · ${c.title()}") }, modifier = Modifier.clickable { directory = false; controller.openChapter(c.id()) }) } }
    }
    if (tools) AlertDialog(onDismissRequest = { tools = false }, title = { Text(stringResource(R.string.novel_en_editor_settings)) }, text = { Column { Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.novel_en_show_line_numbers), modifier = Modifier.weight(1f)); Switch(lines, { lines = it }) }; Text(stringResource(R.string.novel_en_font_size, font.toInt())); Slider(font, { font = it }, valueRange = 12f..24f, steps = 11) } }, confirmButton = { TextButton(onClick = { tools = false }) { Text(stringResource(R.string.novel_en_done)) } })
}

@Composable
private fun WorkManagement(state: NovelEditorState, controller: NovelEditorController, onPreview: (String, String?) -> Unit, onDeleteChapter: (String) -> Unit) {
    val directory = state.directory ?: return
    var filter by remember { mutableStateOf("all") }; var reverse by remember { mutableStateOf(false) }
    val chapters = directory.structure.chapters()
    val numberStyle = LocalTextStyle.current.copy(fontSize = 20.sp, fontFamily = FontFamily.Monospace)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val numberWidth = with(density) {
        // Measure actual labels, including scaled fonts; 32dp clips three digits on narrow screens.
        (chapters.indices.maxOfOrNull { measurer.measure("${it + 1}", numberStyle, maxLines = 1, softWrap = false).size.width } ?: 0).toDp() + 12.dp
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            NovelEditorCover(directory.metadata, Modifier.width(86.dp).height(118.dp))
            Column(Modifier.weight(1f)) { Text(directory.metadata.title, fontFamily = NovelTypography.serif, fontSize = 25.sp); Text(stringResource(R.string.novel_en_type_status, when (directory.metadata.type) { "repost" -> stringResource(R.string.novel_en_type_repost); "ai" -> stringResource(R.string.novel_en_type_ai); else -> stringResource(R.string.novel_en_type_original) }, if (directory.metadata.completed) stringResource(R.string.novel_en_status_completed) else stringResource(R.string.novel_en_status_serializing)), color = NovelTheme.colors.primary, modifier = Modifier.padding(vertical = 8.dp)); Text(stringResource(R.string.novel_en_chapters_characters, chapters.size, directory.structure.work().characterCount()), color = NovelTheme.colors.textSecondary, fontSize = 12.sp); Row { TextButton(onClick = controller::showMetadata) { Text(stringResource(R.string.novel_en_edit_metadata)) }; TextButton(onClick = { controller.preview(onPreview) }) { Text(stringResource(R.string.novel_en_local_preview)) } } }
        } }
        directory.server?.let { server -> item { HorizontalDivider(); Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) { listOf("reads" to stringResource(R.string.novel_en_total_reads), "collections" to stringResource(R.string.novel_en_collections), "comments" to stringResource(R.string.novel_en_comments)).forEach { (key, label) -> Column(Modifier.weight(1f)) { Text(label, color = NovelTheme.colors.textSecondary, fontSize = 11.sp); Text(server.optLong(key).toString(), fontSize = 25.sp) } } }; HorizontalDivider() } }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.novel_en_chapter_management), fontFamily = NovelTypography.serif, fontSize = 21.sp, modifier = Modifier.weight(1f)); TextButton(onClick = { reverse = !reverse }) { Icon(NovelIcons.ArrowDownUp, null, Modifier.size(16.dp)); Text(if (reverse) stringResource(R.string.novel_en_ascending) else stringResource(R.string.novel_en_descending)) } } }
        item { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("all" to stringResource(R.string.novel_en_all), "published" to stringResource(R.string.novel_en_published), "draft" to stringResource(R.string.novel_en_draft), "pending" to stringResource(R.string.novel_en_in_review)).forEach { (value, label) -> FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(label, fontSize = 11.sp) }) } } }
        val filtered = (if (reverse) chapters.reversed() else chapters).filter { c -> val status = directory.remote[c.id()]?.status() ?: "draft"; filter == "all" || status == filter || (filter == "draft" && status == "rejected") }
        itemsIndexed(filtered, key = { _, c -> c.id() }) { _, chapter ->
            val index = chapters.indexOf(chapter); val remote = directory.remote[chapter.id()]
            Column {
                Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { controller.openChapter(chapter.id()) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(AnnotatedString("${index + 1}"), style = numberStyle, maxLines = 1, softWrap = false, color = NovelTheme.colors.textSecondary, modifier = Modifier.width(numberWidth).testTag("chapter-number-${index + 1}"))
                    Column(Modifier.weight(1f)) { Text(chapter.title(), fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Text(stringResource(R.string.novel_en_chapter_status_length, when (remote?.status()) { "published" -> stringResource(R.string.novel_en_published); "pending", "reviewing" -> stringResource(R.string.novel_en_in_review); "rejected" -> stringResource(R.string.novel_en_chapter_rejected); else -> stringResource(R.string.novel_en_draft) }, chapter.contentLength()), color = NovelTheme.colors.textSecondary, fontSize = 11.sp); remote?.reason()?.takeIf { it.isNotBlank() }?.let { Text(it, color = NovelTheme.colors.error, fontSize = 11.sp) } }
                    IconButton(onClick = { if (index > 0) { val ids = chapters.map { it.id() }.toMutableList(); java.util.Collections.swap(ids, index, index - 1); controller.reorder(ids) } }, enabled = index > 0 && !state.busy) { Icon(NovelIcons.ChevronUp, stringResource(R.string.novel_en_move_chapter_up), Modifier.size(18.dp)) }
                    IconButton(onClick = { onDeleteChapter(chapter.id()) }, enabled = !state.busy) { Icon(NovelIcons.Trash, stringResource(R.string.novel_en_remove_local_chapter), Modifier.size(18.dp)) }
                    Icon(NovelIcons.ChevronRight, stringResource(R.string.novel_en_edit_chapter), tint = NovelTheme.colors.primary, modifier = Modifier.size(20.dp))
                }
                HorizontalDivider(color = NovelTheme.colors.outline)
            }
        }
        item { Text(stringResource(R.string.novel_en_local_review_separate), fontSize = 11.sp, color = NovelTheme.colors.textSecondary, modifier = Modifier.padding(vertical = 18.dp)) }
    }
}
