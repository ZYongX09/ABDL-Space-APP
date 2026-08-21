package org.joinmastodon.android.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.api.novels.PublicNovelStoreApi
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun NovelStoreScreen(state: NovelStoreState, onRefresh: () -> Unit, onLoadNextPage: () -> Unit, onOpenWork: (String) -> Unit, onOpenChapter: (PublicNovelStoreApi.WorkDto, PublicNovelStoreApi.ChapterDto) -> Unit, onBackFromWork: () -> Unit, onDismissError: () -> Unit) {
	val work = state.selectedWork
	if (work != null) {
		PublicWorkScreen(work, state.loading, onOpenChapter, onBackFromWork)
		state.error?.let { error -> NovelStoreError(error, onDismissError) }
		return
	}
	LazyColumn(
		modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		item {
			Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text("公开书城", fontSize = 27.sp, fontWeight = FontWeight.Bold)
					Text("仅展示已审核发布的作品", fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
				}
				TextButton(if (state.loading) "加载中" else "刷新", onClick = onRefresh, enabled = !state.loading)
			}
		}
		if (state.loading && state.works.isEmpty()) item { StoreLoading() }
		if (!state.loading && state.works.isEmpty()) item {
			Card(Modifier.fillMaxWidth()) {
				Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
					Text("书城正在等待第一部公开作品", fontSize = 18.sp, fontWeight = FontWeight.Medium)
					Spacer(Modifier.height(7.dp))
					Text("作品通过审核并发布后会出现在这里", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
				}
			}
		}
		items(state.works, key = { it.id }) { publicWork -> PublicWorkRow(publicWork) { onOpenWork(publicWork.id) } }
		if (state.nextCursor != null) item { TextButton(if (state.loading) "加载中" else "加载更多", onClick = onLoadNextPage, enabled = !state.loading) }
		item { Spacer(Modifier.height(28.dp)) }
	}
	state.error?.let { error -> NovelStoreError(error, onDismissError) }
}

@Composable
private fun PublicWorkScreen(work: PublicNovelStoreApi.WorkDto, loading: Boolean, onOpenChapter: (PublicNovelStoreApi.WorkDto, PublicNovelStoreApi.ChapterDto) -> Unit, onBack: () -> Unit) {
	LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		item {
			Column(Modifier.padding(top = 10.dp)) {
				TextButton("返回书城", onClick = onBack)
				Text(work.title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
				work.author?.username?.takeIf { it.isNotBlank() }?.let { Text(it, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 15.sp) }
				work.description.takeIf { !it.isNullOrBlank() }?.let { Text(it, Modifier.padding(top = 8.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
			}
		}
		if (loading) item { StoreLoading() }
		work.volumes.orEmpty().forEach { volume ->
			item(key = "volume:${volume.id}") { Text(volume.title, Modifier.padding(top = 8.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
			items(volume.chapters.orEmpty(), key = { it.id }) { chapter ->
				Card(Modifier.fillMaxWidth().clickable { onOpenChapter(work, chapter) }) {
					Column(Modifier.padding(14.dp)) {
						Text(chapter.title, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
						chapter.contentHint?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(top = 5.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
						chapter.rating?.takeIf { it.isNotBlank() }?.let { Text(ratingLabel(it), Modifier.padding(top = 7.dp), fontSize = 12.sp, color = MiuixTheme.colorScheme.primary) }
					}
				}
			}
		}
	}
}

@Composable
private fun PublicWorkRow(work: PublicNovelStoreApi.WorkDto, onClick: () -> Unit) {
	val shape = RoundedCornerShape(18.dp)
	Row(
		Modifier.fillMaxWidth().clip(shape).background(MiuixTheme.colorScheme.surface).border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f), shape).clickable(onClick = onClick).padding(14.dp),
		verticalAlignment = Alignment.Top,
	) {
		Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
			Text("书", color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
		}
		Spacer(Modifier.width(12.dp))
		Column(Modifier.weight(1f)) {
			Text(work.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
			work.author?.username?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
			work.description.takeIf { !it.isNullOrBlank() }?.let { Text(it, Modifier.padding(top = 5.dp), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
			Text("${work.publishedChapterCount} 章已发布", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
		}
	}
}

@Composable
private fun StoreLoading() {
	Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun NovelStoreError(error: String, onDismiss: () -> Unit) {
	Card(Modifier.fillMaxWidth().padding(18.dp)) {
		Column(Modifier.padding(14.dp)) {
			Text("书城加载失败", fontWeight = FontWeight.SemiBold)
			Text(error, Modifier.padding(top = 5.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton("知道了", onClick = onDismiss) }
		}
	}
}

private fun ratingLabel(rating: String): String = when (rating) {
	"all_ages" -> "全年龄"
	"suggest_12" -> "建议 12+"
	"suggest_15" -> "建议 15+"
	"suggest_18" -> "建议 18+"
	else -> "内容评级"
}
