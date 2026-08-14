package org.joinmastodon.android.novel.author

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import org.joinmastodon.android.api.novels.NovelAuthoringApi
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AuthoringScreen(state: AuthoringState, viewModel: AuthoringViewModel) {
	var createVisible by rememberSaveable { mutableStateOf(false) }
	BackHandler(enabled = state.selectedWorkId != null) { viewModel.closeWork() }
	LaunchedEffect(state.createdWorkId) {
		if (state.createdWorkId != null) {
			createVisible = false
			viewModel.consumeCreatedWork()
		}
	}
	if (state.selectedWorkId != null) {
		WorkStructureScreen(state, viewModel)
		state.error?.let { ErrorDialog(it, viewModel::dismissError) { viewModel.loadStructure() } }
		return
	}
	LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		item {
			Spacer(Modifier.height(6.dp))
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text("创作中心", fontSize = 22.sp, fontWeight = FontWeight.Bold)
					Text("作品草稿仅自己可见", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
				}
				if (state.eligibility?.eligible == true) TextButton("新建作品", onClick = { createVisible = true })
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
				item { Text("我的作品", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
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
	val shape = RoundedCornerShape(18.dp)
	Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface, shape).border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.07f), shape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Text(if (eligibility.eligible) "已获得创作资格" else "创作资格", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
		EligibilityRow("注册已满 72 小时", eligibility.accountAgeEligible)
		EligibilityRow("至少发布 1 条当前存在的帖子", eligibility.postEligible)
	}
}

@Composable
private fun EligibilityRow(label: String, met: Boolean) {
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
		Text(label, fontSize = 14.sp)
		Text(if (met) "已满足" else "未满足", color = if (met) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error, fontSize = 13.sp, fontWeight = FontWeight.Medium)
	}
}

@Composable
private fun WorkCard(work: NovelAuthoringApi.WorkDto, onClick: () -> Unit) {
	val shape = RoundedCornerShape(16.dp)
	Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface, shape).border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f), shape).clickable(onClick = onClick).padding(15.dp)) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Text(work.title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Text("草稿", Modifier.clip(RoundedCornerShape(8.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 4.dp), color = MiuixTheme.colorScheme.primary, fontSize = 12.sp)
		}
		if (work.description.isNotBlank()) Text(work.description, Modifier.padding(top = 8.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
		Text("${categoryName(work.category)} · 更新于 ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(work.updatedAt * 1000))}", Modifier.padding(top = 10.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
	}
}

@Composable
private fun WorkStructureScreen(state: AuthoringState, viewModel: AuthoringViewModel) {
	var titleDialog by rememberSaveable { mutableStateOf<String?>(null) }
	var targetVolumeId by rememberSaveable { mutableStateOf<String?>(null) }
	var targetChapterId by rememberSaveable { mutableStateOf<String?>(null) }
	var initialTitle by rememberSaveable { mutableStateOf("") }
	var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
	val structure = state.structure
	LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
		item {
			Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
				Text("返回", Modifier.clickable { viewModel.closeWork() }.padding(start = 0.dp, top = 10.dp, end = 14.dp, bottom = 10.dp), color = MiuixTheme.colorScheme.primary)
				Column(Modifier.weight(1f)) {
					Text(structure?.work?.title ?: "作品目录", fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
					Text("作品目录 · 正文编辑将在下一阶段开放", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
				}
				TextButton("新建分卷", enabled = !state.structureOperating, onClick = { titleDialog = "新建分卷"; targetVolumeId = null; targetChapterId = null; initialTitle = "" })
			}
		}
		if (state.structureLoading && structure == null) item { Row(Modifier.fillMaxWidth().padding(vertical = 50.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
		if (!state.structureLoading && structure?.volumes.orEmpty().isEmpty()) item {
			Column(Modifier.fillMaxWidth().padding(vertical = 46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
				Text("还没有分卷", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
				Text("先建立目录，再进入正文编辑阶段", Modifier.padding(top = 8.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
			}
		}
		structure?.volumes.orEmpty().forEach { volume ->
			item(key = volume.id) {
				VolumeCard(volume, state.structureOperating,
					onAddChapter = { titleDialog = "新建章节"; targetVolumeId = volume.id; targetChapterId = null; initialTitle = "" },
					onRename = { titleDialog = "修改分卷名称"; targetVolumeId = volume.id; targetChapterId = null; initialTitle = volume.title },
					onDelete = { deleteTarget = "volume:${volume.id}" },
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
private fun VolumeCard(volume: NovelAuthoringApi.VolumeDto, operating: Boolean, onAddChapter: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onRenameChapter: (NovelAuthoringApi.ChapterDto) -> Unit, onDeleteChapter: (NovelAuthoringApi.ChapterDto) -> Unit) {
	val shape = RoundedCornerShape(16.dp)
	Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface, shape).border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f), shape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Text(volume.title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
			Text("改名", Modifier.clickable(enabled = !operating, onClick = onRename).padding(8.dp), color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
			Text("删除", Modifier.clickable(enabled = !operating, onClick = onDelete).padding(8.dp), color = MiuixTheme.colorScheme.error, fontSize = 13.sp)
		}
		volume.chapters.orEmpty().forEach { chapter ->
			Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
				Text(chapter.title, Modifier.weight(1f).padding(vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
				Text("改名", Modifier.clickable(enabled = !operating) { onRenameChapter(chapter) }.padding(8.dp), color = MiuixTheme.colorScheme.primary, fontSize = 12.sp)
				Text("删除", Modifier.clickable(enabled = !operating) { onDeleteChapter(chapter) }.padding(8.dp), color = MiuixTheme.colorScheme.error, fontSize = 12.sp)
			}
		}
		TextButton("新建章节", enabled = !operating, onClick = onAddChapter)
	}
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
