package org.joinmastodon.android.sponsors;

import android.content.res.ColorStateList;
import android.widget.Button;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

/** Server-provided color previews, corrected against the actual themed card surface. */
public final class SponsorColors{
	private SponsorColors(){}
	public static void preview(Button button, String light, String dark){
		Integer parsed=SponsorContrast.parse(UiUtils.isDarkTheme() ? dark : light);
		int fallback=UiUtils.getThemeColor(button.getContext(), R.attr.colorM3OnSurface);
		int color=SponsorContrast.ensure(parsed==null ? fallback : parsed, SponsorUi.cardColor(button.getContext()));
		button.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_selected}, new int[]{-android.R.attr.state_enabled}, new int[]{}},
				new int[]{color, UiUtils.getThemeColor(button.getContext(), R.attr.colorM3OnSurfaceVariant), color}));
	}
}
