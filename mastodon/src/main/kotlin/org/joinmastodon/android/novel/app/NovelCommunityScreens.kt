package org.joinmastodon.android.novel.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.novel.app.design.NovelIcons
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.joinmastodon.android.novel.app.models.Comment
import org.joinmastodon.android.novel.app.models.Message

@Composable internal fun NovelMessagesScreen(state: NovelAppState, vm: NovelAppViewModel, onBack: () -> Unit, onMessage: (Message) -> Unit) {
    val messages = state.messages.value
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
        item {
            NovelHeader("消息", onBack) { NovelLink("全部已读", { vm.markRead() }) }
            NovelTabs(listOf("all" to "全部 ${messages?.unread ?: 0}", "updates" to "作品更新", "interactions" to "互动", "system" to "系统"), state.messageCategory, vm::setMessageCategory)
            Spacer(Modifier.height(12.dp))
        }
        items(messages?.items.orEmpty(), key = { it.id }) { message ->
            Column(Modifier.fillMaxWidth().clickable { vm.markRead(message); onMessage(message) }.padding(vertical = 20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).background(NovelTheme.colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(when (message.category) { "updates" -> NovelIcons.BookOpen; "interactions" -> NovelIcons.MessageCircle; else -> NovelIcons.Bell }, null, Modifier.size(22.dp), tint = NovelTheme.colors.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(message.title, color = NovelTheme.colors.text, fontSize = 14.sp, fontWeight = if (message.read) FontWeight.Medium else FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(message.createdAt.novelDate(), color = NovelTheme.colors.textSecondary, fontSize = 10.sp)
                            if (!message.read) Box(Modifier.padding(start = 8.dp).size(5.dp).background(NovelTheme.colors.primary, CircleShape))
                        }
                        Text(message.body, color = NovelTheme.colors.textSecondary, fontSize = 13.sp, lineHeight = 23.sp)
                        if (message.workId != null) Row(Modifier.fillMaxWidth().background(NovelTheme.colors.surfaceVariant.copy(alpha = .4f), RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(NovelIcons.BookOpen, null, Modifier.size(18.dp), tint = NovelTheme.colors.primary)
                            Text(if (message.chapterId != null) "阅读更新章节" else "查看关联作品", color = NovelTheme.colors.primary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Icon(NovelIcons.ChevronRight, null, Modifier.size(18.dp), tint = NovelTheme.colors.textSecondary)
                        }
                    }
                }
                Spacer(Modifier.height(22.dp)); NovelRule()
            }
        }
        item { NovelLoadState(state.messages, messages?.items.isNullOrEmpty(), "暂无消息", vm::refresh) }
        item {
            if (messages?.nextCursor != null) NovelLink("加载更多消息", vm::loadMoreMessages, Modifier.fillMaxWidth())
            else if (messages?.items?.isNotEmpty() == true) Text("已显示全部消息", color = NovelTheme.colors.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(vertical = 24.dp))
        }
    }
}

@Composable internal fun NovelCommentsScreen(state: NovelAppState, vm: NovelAppViewModel, workId: String, onBack: () -> Unit) {
    var reporting by remember { mutableStateOf<Comment?>(null) }
    var deleting by remember { mutableStateOf<Comment?>(null) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            item { NovelHeader("读者评论", onBack, state.work.value?.title) }
            items(state.comments.value?.items.orEmpty(), key = { it.id }) { comment ->
                Column {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        NovelAvatar(comment.author, 35.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(comment.author.name, color = NovelTheme.colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(comment.createdAt.novelDate(), color = NovelTheme.colors.textSecondary, fontSize = 10.sp)
                        }
                        var menu by remember(comment.id) { mutableStateOf(false) }
                        Box { NovelIconButton(NovelIcons.MoreHorizontal, "评论操作", { menu = true }); DropdownMenu(menu, { menu = false }) {
                            if (comment.canDelete) DropdownMenuItem(text = { Text("删除评论") }, onClick = { menu = false; deleting = comment })
                            DropdownMenuItem(text = { Text("举报评论") }, onClick = { menu = false; reporting = comment })
                        } }
                    }
                    Text(comment.body, color = NovelTheme.colors.text, fontSize = 14.sp, lineHeight = 25.sp, modifier = Modifier.padding(start = 47.dp, top = 12.dp, bottom = 23.dp))
                    NovelRule()
                }
            }
            item { NovelLoadState(state.comments, state.comments.value?.items.isNullOrEmpty(), "还没有读者评论", vm::refresh) }
            item { if (state.comments.value?.nextCursor != null) NovelLink("加载更多评论", { vm.loadMoreComments(workId) }, Modifier.fillMaxWidth()) }
        }
        NovelRule()
        Row(Modifier.fillMaxWidth().background(NovelTheme.colors.background).padding(14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(state.commentDraft, vm::setCommentDraft, Modifier.weight(1f), placeholder = { Text("聊聊这个故事……", fontSize = 13.sp) }, maxLines = 5, shape = RoundedCornerShape(22.dp), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = NovelTheme.colors.text))
            NovelPill("发布", { vm.postComment(workId) }, enabled = state.commentDraft.isNotBlank() && "comment:$workId" !in state.pending)
        }
    }
    reporting?.let { comment -> NovelReportDialog({ reporting = null }, { reason -> reporting = null; vm.report(null, comment.id, reason) }) }
    deleting?.let { comment -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条评论？") }, text = { Text("删除后无法恢复。") }, confirmButton = { TextButton({ deleting = null; vm.deleteComment(comment) }) { Text("删除") } }, dismissButton = { TextButton({ deleting = null }) { Text("取消") } }) }
}

@Composable internal fun NovelReportDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { NovelHeading("举报内容", size = 21) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("请说明具体问题，举报将交由人工审核。", fontSize = 13.sp)
            OutlinedTextField(reason, { reason = it.take(1000) }, label = { Text("举报原因") }, minLines = 3, maxLines = 6)
        }
    }, confirmButton = { TextButton({ onSubmit(reason.trim()) }, enabled = reason.trim().isNotEmpty()) { Text("提交举报") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
