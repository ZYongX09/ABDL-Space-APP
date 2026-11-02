@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.joinmastodon.android.novel.app.editor

import org.joinmastodon.android.R
import androidx.compose.ui.res.stringResource

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import org.joinmastodon.android.novel.app.NovelActionLabel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.novel.app.design.NovelIcons
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.joinmastodon.android.novel.app.design.NovelTypography

@Composable
internal fun NovelMetadataForm(data: NovelMetadata, change: (NovelMetadata) -> Unit, busy: Boolean, controller: NovelEditorController) {
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(controller::chooseCover) }
    var coverConfirmation by remember { mutableStateOf(false) }
    var rules by remember { mutableStateOf(false) }
    val chooseCoverDescription = stringResource(R.string.novel_en_choose_change_cover)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            NovelEditorCover(data, Modifier.width(90.dp).height(120.dp).semantics { contentDescription = chooseCoverDescription }.clickable(enabled = !busy) { coverPicker.launch(arrayOf("image/jpeg", "image/png")) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(NovelIcons.ImagePlus, null, Modifier.size(30.dp)); Text(if (data.localCover == null && data.coverUrl == null) stringResource(R.string.novel_en_choose_cover) else stringResource(R.string.novel_en_choose_again), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(stringResource(R.string.novel_en_cover_requirements), fontSize = 11.sp, color = NovelTheme.colors.textSecondary); Text(if (data.coverPending) stringResource(R.string.novel_en_cover_pending_preview) else if (data.coverUrl != null) stringResource(R.string.novel_en_cover_uploaded) else stringResource(R.string.novel_en_cover_default_notice), fontSize = 10.sp, color = NovelTheme.colors.textSecondary) }
        }
        if (data.localCover != null || data.coverUrl != null) Row {
            TextButton(onClick = { coverConfirmation = true }, enabled = data.localCover != null && data.coverPending && !busy) { Text(stringResource(R.string.novel_en_explicit_cover_upload)) }
            TextButton(onClick = controller::removeCover, enabled = !busy) { Text(stringResource(R.string.novel_en_remove_cover)) }
        }
        MetadataField(stringResource(R.string.novel_en_work_title_label), data.title, 30, busy, { change(data.copy(title = it)) }, true, stringResource(R.string.novel_en_work_title_hint))
        MetadataField(stringResource(R.string.novel_en_work_description_label), data.description, 500, busy, { change(data.copy(description = it)) }, false, stringResource(R.string.novel_en_work_description_hint))
        Text(stringResource(R.string.novel_en_work_type_label), fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("original" to stringResource(R.string.novel_en_type_original), "repost" to stringResource(R.string.novel_en_type_repost), "ai" to stringResource(R.string.novel_en_type_ai)).forEach { (value, label) -> FilterChip(selected = data.type == value, onClick = { change(data.copy(type = value)) }, enabled = !busy, label = { Text(label, fontSize = 12.sp) }) } }
        Text(stringResource(R.string.novel_en_type_disclosure_notice), fontSize = 10.sp, color = NovelTheme.colors.textSecondary)
        if (data.type == "repost") {
            MetadataField(stringResource(R.string.novel_en_original_author_label), data.originalAuthor, 120, busy, { change(data.copy(originalAuthor = it)) }, true, stringResource(R.string.novel_en_original_author_hint))
            MetadataField(stringResource(R.string.novel_en_source_label), data.source, 2000, busy, { change(data.copy(source = it)) })
            MetadataField(stringResource(R.string.novel_en_permission_label), data.authorization, 2000, busy, { change(data.copy(authorization = it)) })
        } else if (data.type == "ai") MetadataField(stringResource(R.string.novel_en_ai_source_label), data.source, 2000, busy, { change(data.copy(source = it)) })
        var tags by remember(data.tags) { mutableStateOf(data.tags.joinToString("，")) }
        OutlinedTextField(tags, { value -> if (value.length <= 95) { tags = value; change(data.copy(tags = value.split('，', ',').map { it.trim() }.filter { it.isNotEmpty() })) } }, label = { Text(stringResource(R.string.novel_en_tags_label)) }, enabled = !busy, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.novel_en_author_statements), fontSize = 15.sp)
        Text(stringResource(R.string.novel_en_statements_explanation), fontSize = 10.sp, color = NovelTheme.colors.textSecondary)
        Column(Modifier.fillMaxWidth().background(NovelTheme.colors.surfaceVariant.copy(alpha = .4f), RoundedCornerShape(14.dp)).padding(8.dp)) {
            listOf("内容不适宜未成年人阅读", "原创内容禁止转载", "含有引人不适内容", "虚构内容，请勿当真").forEach { text ->
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(text in data.statements, { selected -> change(data.copy(statements = if (selected) data.statements + text else data.statements - text)) }, enabled = !busy); Text(stringResource(statementLabel(text)), fontSize = 12.sp) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(data.accepted, { change(data.copy(accepted = it)) }, enabled = !busy); Text(stringResource(R.string.novel_en_rights_confirmation), fontSize = 11.sp) }
        TextButton(onClick = { rules = true }) { Text(stringResource(R.string.novel_en_read_publishing_rules), fontSize = 12.sp) }
        Text(stringResource(R.string.novel_en_metadata_local_only), fontSize = 11.sp, color = NovelTheme.colors.textSecondary)
    }
    if (coverConfirmation) AlertDialog(onDismissRequest = { coverConfirmation = false }, title = { Text(stringResource(R.string.novel_en_upload_cover)) }, text = { Text(stringResource(R.string.novel_en_cover_upload_confirmation)) }, confirmButton = { TextButton(onClick = { coverConfirmation = false; controller.uploadCover() }) { Text(stringResource(R.string.novel_en_confirm_upload)) } }, dismissButton = { TextButton(onClick = { coverConfirmation = false }) { Text(stringResource(R.string.novel_en_cancel)) } })
    if (rules) AlertDialog(onDismissRequest = { rules = false }, title = { Text(stringResource(R.string.novel_en_publishing_guidelines), fontFamily = NovelTypography.serif) }, text = { Text(stringResource(R.string.novel_en_metadata_rules_body)) }, confirmButton = { TextButton(onClick = { rules = false }) { Text(stringResource(R.string.novel_en_read_acknowledged)) } })
}

@Composable
private fun MetadataField(label: String, value: String, max: Int, busy: Boolean, change: (String) -> Unit, singleLine: Boolean = false, placeholder: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text("${value.length} / $max", color = NovelTheme.colors.textSecondary, fontSize = 10.sp) }
        OutlinedTextField(value, { if (it.length <= max) change(it) }, enabled = !busy, singleLine = singleLine, minLines = if (singleLine) 1 else 3, placeholder = { Text(placeholder, fontSize = 12.sp) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
    }
}

/** Canonical statement values are existing wire/user data, not translated storage. */
private fun statementLabel(value: String): Int = when (value) {
    "内容不适宜未成年人阅读" -> R.string.novel_en_statement_adult
    "原创内容禁止转载" -> R.string.novel_en_statement_no_repost
    "含有引人不适内容" -> R.string.novel_en_statement_sensitive
    "虚构内容，请勿当真" -> R.string.novel_en_statement_fiction
    else -> error("Unknown statement template")
}
