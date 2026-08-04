package org.joinmastodon.android.novel

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.compose.component.BackNavigationIcon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun NovelHomeScreen(onBack: () -> Unit) {
	val tabs = listOf(
		R.string.novel_recommend to R.string.novel_recommend_empty,
		R.string.novel_bookshelf to R.string.novel_bookshelf_empty,
		R.string.novel_creation to R.string.novel_creation_empty,
	)
	var selectedTab by remember { mutableIntStateOf(0) }

	Scaffold(
		containerColor = MiuixTheme.colorScheme.background,
		topBar = {
			Column {
				SmallTopAppBar(
					title = stringResource(R.string.novel),
					navigationIcon = { BackNavigationIcon(onClick = onBack) },
				)
				TabRow(
					tabs = tabs.map { stringResource(it.first) },
					selectedTabIndex = selectedTab,
					onTabSelected = { selectedTab = it },
				)
			}
		},
	) { padding ->
		Box(
			modifier = Modifier.fillMaxSize().padding(padding),
			contentAlignment = Alignment.Center,
		) {
			Column(
				modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.Center,
			) {
				Image(
					painter = painterResource(R.drawable.ic_fluent_book_48_regular),
					contentDescription = null,
					modifier = Modifier.size(64.dp),
					colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
				)
				Spacer(Modifier.height(20.dp))
				Text(
					text = stringResource(tabs[selectedTab].second),
					color = MiuixTheme.colorScheme.onSurface,
					fontSize = 18.sp,
					fontWeight = FontWeight.Medium,
				)
			}
		}
	}
}
