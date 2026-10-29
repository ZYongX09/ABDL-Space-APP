package org.joinmastodon.android.novel.app.design;

import org.joinmastodon.android.api.session.AccountLocalPreferences.ColorPreference;

/** Novel-only migration policy. Never rewrites global or account preferences. */
public final class NovelPalettePolicy{
	private NovelPalettePolicy(){}

	public static ColorPreference resolve(ColorPreference accountColor, ColorPreference globalColor,
			boolean globalExplicitlyChosen, boolean dark){
		if(accountColor!=null) return accountColor;
		ColorPreference inherited=globalColor==null ? ColorPreference.NORD : globalColor;
		return !dark && inherited==ColorPreference.NORD && !globalExplicitlyChosen ? ColorPreference.BLUE : inherited;
	}
}
