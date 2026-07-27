package org.joinmastodon.android.ui.compose.diapers

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import top.yukonga.miuix.kmp.basic.TabRowWithContour

class DiaperBrandTabRowView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
	private var tabs by mutableStateOf(emptyList<String>())
	private var selectedIndex by mutableIntStateOf(0)
	private var onTabSelected: ((Int) -> Unit)? = null

	init {
		addView(ComposeView(context).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
			setContent {
				MiuixAppTheme {
					if(tabs.isNotEmpty()) {
						TabRowWithContour(
							tabs=tabs,
							selectedTabIndex=selectedIndex,
							onTabSelected={ index ->
								if(index!=selectedIndex) {
									selectedIndex=index
									onTabSelected?.invoke(index)
								}
							},
							modifier=Modifier
								.fillMaxWidth()
								.padding(horizontal=16.dp, vertical=8.dp),
							listState=rememberLazyListState(),
						)
					}
				}
			}
		}, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
	}

	fun setTabs(tabs: List<String>, selectedIndex: Int) {
		this.tabs=tabs.toList()
		this.selectedIndex=selectedIndex.coerceIn(0, (tabs.size-1).coerceAtLeast(0))
	}

	fun setOnTabSelectedListener(listener: OnTabSelectedListener?) {
		onTabSelected=listener?.let { callback -> { index -> callback.onTabSelected(index) } }
	}

	fun interface OnTabSelectedListener {
		fun onTabSelected(index: Int)
	}
}
