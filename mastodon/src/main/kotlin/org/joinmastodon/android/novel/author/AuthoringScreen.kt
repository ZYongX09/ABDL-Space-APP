package org.joinmastodon.android.novel.author

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import java.text.DateFormat
import java.util.Date
import org.joinmastodon.android.api.novels.NovelAuthoringApi
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.AddCircle
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.FolderFill
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Ok

@Composable
fun AuthoringScreen(state: AuthoringState, viewModel: AuthoringViewModel, onOpenChapter: (String, NovelAuthoringApi.ChapterDto) -> Unit) {
	var createVisible by rememberSaveable { mutableStateOf(false) }
	BackHandler(enabled = state.selectedWorkId != null) { viewModel.closeWork() }
	LaunchedEffect(state.createdWorkId) {
		if (state.createdWorkId != null) {
			createVisible = false
			viewModel.consumeCreatedWork()
		}
	}
	if (state.selectedWorkId != null) {
		WorkStructureScreen(state, viewModel, onOpenChapter)
		state.error?.let { ErrorDialog(it, viewModel::dismissError) { viewModel.loadStructure() } }
		return
	}
	LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
		item {
			Spacer(Modifier.height(10.dp))
			BoxWithConstraints(Modifier.fillMaxWidth()) {
				if (maxWidth < 360.dp) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
					HeaderTitle("创作中心", "作品草稿仅自己可见")
					if (state.eligibility?.eligible == true) PrimaryAction("新建作品", MiuixIcons.AddCircle) { createVisible = true }
				} else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
					Column(Modifier.weight(1f)) { HeaderTitle("创作中心", "作品草稿仅自己可见") }
					if (state.eligibility?.eligible == true) PrimaryAction("新建作品", MiuixIcons.AddCircle) { createVisible = true }
				}
			}
		}
		if (state.loading) {
			item { Row(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
		} else {
			state.eligibility?.let { item { EligibilityCard(it) } }
			if (state.works.isEmpty()) {
				item {
					Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
						Text(if (state.eligibility?.eligible == true) "还没有作品" else "满足全部条件后即可创建作品", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
						Spacer(Modifier.height(8.dp))
						Text("当前只创建私有草稿，不会立即公开", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
					}
				}
			} else {
				item { Text("我的作品", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
				items(state.works, key = { it.id }) { WorkCard(it) { viewModel.openWork(it.id) } }
			}
		}
		item { Spacer(Modifier.height(24.dp)) }
	}
	if (createVisible) CreateWorkDialog(state.creating, { if (!state.creating) createVisible = false }, viewModel::createWork)
	state.error?.let { message ->
		ErrorDialog(message, viewModel::dismissError) { viewModel.refresh() }
	}
}

@Composable
private fun EligibilityCard(eligibility: NovelAuthoringApi.EligibilityDto) {
	val shape = RoundedCornerShape(22.dp)
	Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainer, shape).border(1.dp, MiuixTheme.colorScheme.primary.copy(alpha = 0.18f), shape).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			IconTile(MiuixIcons.Ok, 46.dp)
			Text(if (eligibility.eligible) "已获得创作资格" else "创作资格", Modifier.padding(start = 13.dp), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
		}
		EligibilityRow("注册已满 72 小时", eligibility.accountAgeEligible)
		DividerLine()
		EligibilityRow("至少发布 1 条当前存在的帖子", eligibility.postEligible)
	}
}

@Composable
private fun EligibilityRow(label: String, met: Boolean) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Icon(MiuixIcons.Ok, null, Modifier.size(22.dp), if (met) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
		Text(label, Modifier.weight(1f).padding(start = 12.dp), fontSize = 15.sp)
		Text(if (met) "已满足" else "未满足", color = if (met) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error, fontSize = 14.sp, fontWeight = FontWeight.Medium)
	}
}

@Composable
private fun WorkCard(work: NovelAuthoringApi.WorkDto, onClick: () -> Unit) {
	val shape = RoundedCornerShape(22.dp)
	Row(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainer, shape).border(1.dp, MiuixTheme.colorScheme.primary.copy(alpha = 0.15f), shape).clickable(onClick = onClick).padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
		IconTile(MiuixIcons.Notes, 58.dp)
		Column(Modifier.weight(1f).padding(start = 15.dp)) {
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
				Text(work.title, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Text("草稿", Modifier.clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp), color = MiuixTheme.colorScheme.primary, fontSize = 12.sp)
			}
			if (work.description.isNotBlank()) Text(work.description, Modifier.padding(top = 5.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Text("${categoryName(work.category)} · 更新于 ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(work.updatedAt * 1000))}", Modifier.padding(top = 10.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
		}
	}
}

@Composable
private fun WorkStructureScreen(state: AuthoringState, viewModel: AuthoringViewModel, onOpenChapter: (String, NovelAuthoringApi.ChapterDto) -> Unit) {
	var titleDialog by rememberSaveable { mutableStateOf<String?>(null) }
	var targetVolumeId by rememberSaveable { mutableStateOf<String?>(null) }
	var targetChapterId by rememberSaveable { mutableStateOf<String?>(null) }
	var initialTitle by rememberSaveable { mutableStateOf("") }
	var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
	val structure = state.structure
	LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
		item {
			BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
				val createVolume = { titleDialog = "新建分卷"; targetVolumeId = null; targetChapterId = null; initialTitle = "" }
				if (maxWidth < 380.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
					Text("‹ 返回", Modifier.defaultMinSize(minHeight = 48.dp).clickable { viewModel.closeWork() }.padding(vertical = 12.dp), color = MiuixTheme.colorScheme.primary, fontSize = 16.sp)
					HeaderTitle(structure?.work?.title ?: "作品目录", "作品目录 · 点击章节进入独立编辑页")
					PrimaryAction("新建分卷", MiuixIcons.Add, enabled = !state.structureOperating, onClick = createVolume)
				} else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
					Text("‹ 返回", Modifier.defaultMinSize(minHeight = 48.dp).clickable { viewModel.closeWork() }.padding(end = 16.dp, top = 12.dp, bottom = 12.dp), color = MiuixTheme.colorScheme.primary, fontSize = 16.sp)
					Column(Modifier.weight(1f)) { HeaderTitle(structure?.work?.title ?: "作品目录", "作品目录 · 点击章节进入独立编辑页") }
					PrimaryAction("新建分卷", MiuixIcons.Add, enabled = !state.structureOperating, onClick = createVolume)
				}
			}
		}
		if (state.structureLoading && structure == null) item { Row(Modifier.fillMaxWidth().padding(vertical = 50.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
		if (!state.structureLoading && structure?.volumes.orEmpty().isEmpty()) item {
			Column(Modifier.fillMaxWidth().padding(vertical = 46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
				Text("还没有分卷", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
				Text("先建立分卷和章节，再进入独立正文编辑页", Modifier.padding(top = 8.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
			}
		}
		structure?.volumes.orEmpty().forEach { volume ->
			item(key = volume.id) {
				VolumeCard(volume, state.structureOperating,
					onAddChapter = { titleDialog = "新建章节"; targetVolumeId = volume.id; targetChapterId = null; initialTitle = "" },
					onRename = { titleDialog = "修改分卷名称"; targetVolumeId = volume.id; targetChapterId = null; initialTitle = volume.title },
					onDelete = { deleteTarget = "volume:${volume.id}" },
					onOpenChapter = { chapter -> onOpenChapter(requireNotNull(state.selectedWorkId), chapter) },
					onRenameChapter = { chapter -> titleDialog = "修改章节名称"; targetVolumeId = volume.id; targetChapterId = chapter.id; initialTitle = chapter.title },
					onDeleteChapter = { chapter -> deleteTarget = "chapter:${volume.id}:${chapter.id}" },
				)
			}
		}
		item { Spacer(Modifier.height(24.dp)) }
	}
	titleDialog?.let { dialogTitle ->
		TitleDialog(dialogTitle, initialTitle, state.structureOperating, { if (!state.structureOperating) titleDialog = null }) { title ->
			when {
				targetChapterId != null -> viewModel.renameChapter(requireNotNull(targetVolumeId), requireNotNull(targetChapterId), title)
				targetVolumeId != null && dialogTitle == "新建章节" -> viewModel.createChapter(requireNotNull(targetVolumeId), title)
				targetVolumeId != null -> viewModel.renameVolume(requireNotNull(targetVolumeId), title)
				else -> viewModel.createVolume(title)
			}
			titleDialog = null
		}
	}
	deleteTarget?.let { target ->
		OverlayDialog(show = true, title = "确认删除？", summary = if (target.startsWith("volume:")) "仅空分卷可以删除" else "章节目录将被移除，当前尚无正文", onDismissRequest = { deleteTarget = null }) {
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
				TextButton("取消", onClick = { deleteTarget = null })
				TextButton("删除", enabled = !state.structureOperating, onClick = {
					val parts = target.split(':')
					if (parts[0] == "volume") viewModel.deleteVolume(parts[1]) else viewModel.deleteChapter(parts[1], parts[2])
					deleteTarget = null
				})
			}
		}
	}
}

@Composable
private fun VolumeCard(volume: NovelAuthoringApi.VolumeDto, operating: Boolean, onAddChapter: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onOpenChapter: (NovelAuthoringApi.ChapterDto) -> Unit, onRenameChapter: (NovelAuthoringApi.ChapterDto) -> Unit, onDeleteChapter: (NovelAuthoringApi.ChapterDto) -> Unit) {
	val shape = RoundedCornerShape(22.dp)
	Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainer, shape).border(1.dp, MiuixTheme.colorScheme.primary.copy(alpha = 0.16f), shape).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Icon(MiuixIcons.FolderFill, null, Modifier.size(28.dp), MiuixTheme.colorScheme.primary)
			Text(volume.title, Modifier.weight(1f).padding(start = 12.dp), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
			InlineAction("改名", MiuixTheme.colorScheme.primary, operating, onRename)
			InlineAction("删除", MiuixTheme.colorScheme.error, operating, onDelete)
		}
		DividerLine()
		volume.chapters.orEmpty().forEach { chapter ->
			Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
				Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
					Icon(MiuixIcons.Notes, null, Modifier.size(19.dp), MiuixTheme.colorScheme.onSurfaceVariantSummary)
				}
				Text(chapter.title, Modifier.weight(1f).clickable(enabled = !operating) { onOpenChapter(chapter) }.padding(start = 12.dp, top = 14.dp, bottom = 14.dp), fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
				InlineAction("改名", MiuixTheme.colorScheme.primary, operating) { onRenameChapter(chapter) }
				InlineAction("删除", MiuixTheme.colorScheme.error, operating) { onDeleteChapter(chapter) }
			}
		}
		Row(Modifier.defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, MiuixTheme.colorScheme.primary.copy(alpha = 0.65f), RoundedCornerShape(14.dp)).clickable(enabled = !operating, onClick = onAddChapter).padding(horizontal = 15.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
			Icon(MiuixIcons.Add, null, Modifier.size(20.dp), MiuixTheme.colorScheme.primary)
			Text("新建章节", Modifier.padding(start = 8.dp), color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
		}
	}
}

@Composable
fun NovelChapterEditorScreen(state: AuthoringState, viewModel: AuthoringViewModel, onClose: () -> Unit) {
	val chapter = requireNotNull(state.editingChapter)
	val clipboard = LocalClipboardManager.current
	Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 20.dp)) {
		Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
			Text("‹", Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clickable(onClick = onClose).padding(end = 18.dp, top = 5.dp, bottom = 5.dp), color = MiuixTheme.colorScheme.onSurface, fontSize = 36.sp, fontWeight = FontWeight.Light)
			Column(Modifier.weight(1f)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(chapter.title, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
				}
				Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
					Icon(if (state.editorSyncState == "clean") MiuixIcons.Ok else MiuixIcons.CloudFill, null, Modifier.size(15.dp), editorStatusColor(state.editorSyncState))
					Text(editorStatus(state.editorSyncState), Modifier.padding(start = 6.dp), fontSize = 12.sp, color = editorStatusColor(state.editorSyncState))
				}
			}
		}
		if (state.editorLoading) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator() }
		else BasicTextField(
			value = state.editorContent,
			onValueChange = viewModel::saveChapterContent,
			modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 18.dp).semantics { contentDescription = "章节正文" },
			enabled = state.editorConflict == null && !state.editorResolving,
			textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 20.sp, lineHeight = 32.sp),
			cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
			decorationBox = { inner ->
				Box {
					if (state.editorContent.isEmpty()) Text("开始写作…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 20.sp)
					inner()
				}
			},
		)
		DividerLine()
		Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
			Icon(MiuixIcons.CloudFill, null, Modifier.size(25.dp), editorStatusColor(state.editorSyncState))
			Column(Modifier.weight(1f).padding(start = 11.dp)) {
				Text(editorFooterTitle(state.editorSyncState), fontSize = 14.sp, fontWeight = FontWeight.Medium)
				Text(editorFooterSummary(state.editorSyncState), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
			}
			Text("${state.editorContent.length} 字", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
		}
	}
	state.editorConflict?.let { conflict ->
		OverlayDialog(show = true, title = "发现云端冲突", summary = "另一台设备已修改此章节。不会自动覆盖任何版本。", onDismissRequest = {}) {
			Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
				Text("本地版本", fontWeight = FontWeight.SemiBold)
				Text(conflict.localContent.take(240), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 5, overflow = TextOverflow.Ellipsis)
				Text("云端版本", fontWeight = FontWeight.SemiBold)
				Text(conflict.serverContent.take(240), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 5, overflow = TextOverflow.Ellipsis)
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
					TextButton("复制文本", enabled = !state.editorResolving, onClick = { clipboard.setText(AnnotatedString(conflict.localContent)) })
					TextButton("采用云端", enabled = !state.editorResolving, onClick = viewModel::useServerConflict)
					TextButton("保留本地副本", enabled = !state.editorResolving, onClick = viewModel::keepLocalConflict)
				}
			}
		}
	}
	state.error?.let { ErrorDialog(it, viewModel::dismissError) { viewModel.refreshEditorConflict() } }
}

private fun editorStatus(state: String) = when (state) {
	"pending" -> "已保存到本机，等待同步"
	"conflict" -> "存在冲突，自动同步已暂停"
	"clean" -> "已同步"
	else -> "仅保存在本机"
}

private fun editorFooterTitle(state: String) = when (state) {
	"conflict" -> "同步冲突"
	"clean" -> "自动保存"
	else -> "正在保存"
}

private fun editorFooterSummary(state: String) = when (state) {
	"conflict" -> "自动同步已暂停，请处理冲突"
	"clean" -> "云草稿已同步"
	else -> "本地草稿已保存"
}

@Composable
private fun editorStatusColor(state: String) = when (state) {
	"conflict" -> MiuixTheme.colorScheme.error
	"clean" -> MiuixTheme.colorScheme.primary
	else -> MiuixTheme.colorScheme.primary
}

@Composable
private fun HeaderTitle(title: String, summary: String) {
	Column {
		Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
		Text(summary, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
	}
}

@Composable
private fun PrimaryAction(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
	val shape = RoundedCornerShape(18.dp)
	val start = if (enabled) MiuixTheme.colorScheme.primary.copy(alpha = 0.72f) else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
	val end = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
	Row(
		Modifier.clip(shape).background(Brush.linearGradient(listOf(start, end))).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(icon, null, Modifier.size(22.dp), MiuixTheme.colorScheme.onPrimary)
		Text(label, Modifier.padding(start = 8.dp), color = MiuixTheme.colorScheme.onPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
	}
}

@Composable
private fun IconTile(icon: ImageVector, size: androidx.compose.ui.unit.Dp) {
	Box(
		Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(Brush.linearGradient(listOf(MiuixTheme.colorScheme.primary.copy(alpha = 0.30f), MiuixTheme.colorScheme.primary.copy(alpha = 0.08f)))).border(1.dp, MiuixTheme.colorScheme.primary.copy(alpha = 0.30f), RoundedCornerShape(size / 3)),
		contentAlignment = Alignment.Center,
	) {
		Icon(icon, null, Modifier.size(size * 0.48f), MiuixTheme.colorScheme.primary)
	}
}

@Composable
private fun DividerLine() {
	Box(Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.07f)))
}

@Composable
private fun InlineAction(label: String, color: Color, disabled: Boolean, onClick: () -> Unit) {
	Text(label, Modifier.defaultMinSize(minHeight = 48.dp).clickable(enabled = !disabled, onClick = onClick).padding(horizontal = 9.dp, vertical = 14.dp), color = color, fontSize = 13.sp)
}

@Composable
private fun TitleDialog(title: String, initialValue: String, operating: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
	var value by rememberSaveable(title, initialValue) { mutableStateOf(initialValue) }
	OverlayDialog(show = true, title = title, onDismissRequest = onDismiss) {
		Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
			TextField(value, { if (it.length <= 120) value = it }, label = "名称")
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
				TextButton("取消", enabled = !operating, onClick = onDismiss)
				TextButton("确定", enabled = !operating && value.isNotBlank(), onClick = { onConfirm(value) })
			}
		}
	}
}

@Composable
private fun ErrorDialog(message: String, onDismiss: () -> Unit, onRetry: () -> Unit) {
	OverlayDialog(show = true, title = "创作中心暂不可用", summary = message, onDismissRequest = onDismiss) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
			TextButton("重试", onClick = { onDismiss(); onRetry() })
			TextButton("知道了", onClick = onDismiss)
		}
	}
}

@Composable
private fun CreateWorkDialog(creating: Boolean, onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
	var title by rememberSaveable { mutableStateOf("") }
	var description by rememberSaveable { mutableStateOf("") }
	var categoryIndex by rememberSaveable { mutableStateOf(0) }
	val categories = listOf("fiction", "fantasy", "romance", "science_fiction", "mystery", "history", "essay", "other")
	val category = categories[categoryIndex]
	OverlayDialog(show = true, title = "新建作品", summary = "当前只创建私有草稿，不会立即公开", onDismissRequest = onDismiss) {
		Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
			TextField(title, { if (it.length <= 120) title = it }, label = "作品标题")
			TextField(description, { if (it.length <= 2000) description = it }, label = "作品简介")
			Text("分类：${categoryName(category)}", Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !creating) { categoryIndex = (categoryIndex + 1) % categories.size }.padding(12.dp), color = MiuixTheme.colorScheme.primary)
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
				if (creating) CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
				TextButton("取消", enabled = !creating, onClick = onDismiss)
				TextButton(if (creating) "正在创建" else "创建草稿", enabled = !creating && title.isNotBlank(), onClick = { onCreate(title, description, category) })
			}
		}
	}
}

private fun categoryName(category: String) = when (category) {
	"fantasy" -> "奇幻"
	"romance" -> "言情"
	"science_fiction" -> "科幻"
	"mystery" -> "悬疑"
	"history" -> "历史"
	"essay" -> "随笔"
	"other" -> "其他"
	else -> "小说"
}
