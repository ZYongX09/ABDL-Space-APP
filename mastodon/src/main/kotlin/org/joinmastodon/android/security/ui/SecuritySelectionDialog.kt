package org.joinmastodon.android.security.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SecuritySelectionDialog(
	title: String,
	items: List<String>,
	selected: Int,
	onDismiss: () -> Unit,
	onSelect: (Int) -> Unit,
) {
	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
	) {
		Column(
			modifier = Modifier.clip(RoundedCornerShape(24.dp))
				.background(MiuixTheme.colorScheme.surface)
				.verticalScroll(rememberScrollState())
				.padding(20.dp)
				.selectableGroup(),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium,
				color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 12.dp))
			items.forEachIndexed { index, label ->
				Row(
					modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
						.clip(RoundedCornerShape(12.dp))
						.selectable(selected = index == selected, role = Role.RadioButton,
							onClick = { onSelect(index) }).padding(horizontal = 12.dp, vertical = 12.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(16.dp),
				) {
					Text(if (index == selected) "●" else "○", color = MiuixTheme.colorScheme.primary,
						modifier = Modifier.size(24.dp), fontSize = 20.sp)
					Text(label, color = MiuixTheme.colorScheme.onSurface, fontSize = 17.sp)
				}
			}
		}
	}
}
