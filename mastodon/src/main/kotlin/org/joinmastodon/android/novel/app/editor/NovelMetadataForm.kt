package org.joinmastodon.android.novel.app.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            NovelEditorCover(data, Modifier.width(90.dp).height(120.dp).semantics { contentDescription = "选择或更换封面" }.clickable(enabled = !busy) { coverPicker.launch(arrayOf("image/jpeg", "image/png")) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(NovelIcons.ImagePlus, null, Modifier.size(30.dp)); Text(if (data.localCover == null && data.coverUrl == null) "选择封面" else "重新选择", fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("选填 · JPG / PNG，建议 3:4\n不超过 5 MB", fontSize = 11.sp, color = NovelTheme.colors.textSecondary); Text(if (data.coverPending) "本地预览 · 待明确上传" else if (data.coverUrl != null) "封面已上传" else "若不上传，将使用默认文字封面", fontSize = 10.sp, color = NovelTheme.colors.textSecondary) }
        }
        if (data.localCover != null || data.coverUrl != null) Row {
            TextButton(onClick = { coverConfirmation = true }, enabled = data.localCover != null && data.coverPending && !busy) { Text("明确上传封面") }
            TextButton(onClick = controller::removeCover, enabled = !busy) { Text("移除封面") }
        }
        MetadataField("作品名称 *", data.title, 30, busy, { change(data.copy(title = it)) }, true, "给你的故事起一个名字")
        MetadataField("作品简介 *", data.description, 500, busy, { change(data.copy(description = it)) }, false, "介绍故事的背景、人物与看点，让读者更了解你的作品。")
        Text("作品类型 *", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("original" to "原创", "repost" to "授权转载", "ai" to "AI辅助").forEach { (value, label) -> FilterChip(selected = data.type == value, onClick = { change(data.copy(type = value)) }, enabled = !busy, label = { Text(label, fontSize = 12.sp) }) } }
        Text("选择转载需填写来源及授权；AI 辅助将明确标识。", fontSize = 10.sp, color = NovelTheme.colors.textSecondary)
        if (data.type == "repost") {
            MetadataField("原作者名 *", data.originalAuthor, 120, busy, { change(data.copy(originalAuthor = it)) }, true, "填写作品原作者的名字")
            MetadataField("来源 *", data.source, 2000, busy, { change(data.copy(source = it)) })
            MetadataField("授权说明 *", data.authorization, 2000, busy, { change(data.copy(authorization = it)) })
        } else if (data.type == "ai") MetadataField("AI 辅助说明 / 来源", data.source, 2000, busy, { change(data.copy(source = it)) })
        var tags by remember(data.tags) { mutableStateOf(data.tags.joinToString("，")) }
        OutlinedTextField(tags, { value -> if (value.length <= 95) { tags = value; change(data.copy(tags = value.split('，', ',').map { it.trim() }.filter { it.isNotEmpty() })) } }, label = { Text("作品标签（选填，最多 3 个，逗号分隔）") }, enabled = !busy, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        Text("作者声明", fontSize = 15.sp)
        Text("请按作品实际情况逐项勾选，声明将展示在作品详情页。", fontSize = 10.sp, color = NovelTheme.colors.textSecondary)
        Column(Modifier.fillMaxWidth().background(NovelTheme.colors.surfaceVariant.copy(alpha = .4f), RoundedCornerShape(14.dp)).padding(8.dp)) {
            listOf("内容不适宜未成年人阅读", "原创内容禁止转载", "含有引人不适内容", "虚构内容，请勿当真").forEach { text ->
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(text in data.statements, { selected -> change(data.copy(statements = if (selected) data.statements + text else data.statements - text)) }, enabled = !busy); Text(text, fontSize = 12.sp) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(data.accepted, { change(data.copy(accepted = it)) }, enabled = !busy); Text("我确认作品内无违规内容，我没有侵犯版权，并尊重他人隐私。", fontSize = 11.sp) }
        TextButton(onClick = { rules = true }) { Text("阅读《社群创作与发布规范》", fontSize = 12.sp) }
        Text("此步骤仅创建/修改本地草稿。不会自动上传正文或更改公开作品资料。", fontSize = 11.sp, color = NovelTheme.colors.textSecondary)
    }
    if (coverConfirmation) AlertDialog(onDismissRequest = { coverConfirmation = false }, title = { Text("上传封面") }, text = { Text("只上传已选择的封面图片，不上传文稿。图片将由现有附件上传服务处理。是否继续？") }, confirmButton = { TextButton(onClick = { coverConfirmation = false; controller.uploadCover() }) { Text("确认上传") } }, dismissButton = { TextButton(onClick = { coverConfirmation = false }) { Text("取消") } })
    if (rules) AlertDialog(onDismissRequest = { rules = false }, title = { Text("创作与发布须知", fontFamily = NovelTypography.serif) }, text = { Text("仅提交有权发布的内容；转载必须标注来源和授权，AI 辅助需明确标识。不得发布违法、侵权、侵犯他人隐私的内容。请如实选择读者年龄和内容提示。所有公开内容必须经过人工审核；本地草稿不会自动上传。") }, confirmButton = { TextButton(onClick = { rules = false }) { Text("已阅读") } })
}

@Composable
private fun MetadataField(label: String, value: String, max: Int, busy: Boolean, change: (String) -> Unit, singleLine: Boolean = false, placeholder: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, fontSize = 14.sp); Text("${value.length} / $max", color = NovelTheme.colors.textSecondary, fontSize = 10.sp) }
        OutlinedTextField(value, { if (it.length <= max) change(it) }, enabled = !busy, singleLine = singleLine, minLines = if (singleLine) 1 else 3, placeholder = { Text(placeholder, fontSize = 12.sp) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
    }
}
