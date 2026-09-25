package org.joinmastodon.android.ui.sheets;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.sponsors.SponsorUi;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.utils.V;
import me.grishka.appkit.views.BottomSheet;

/** Scrollable at large font scales; constructor and dismiss-before-action contract stay stable. */
public class SponsorNoticeSheet extends BottomSheet{
	public SponsorNoticeSheet(Context context, String title, String body, String action, Runnable continueAction, Runnable centerAction){
		super(context);
		ScrollView scroll=new ScrollView(context);
		scroll.setBackgroundResource(R.drawable.bg_bottom_sheet); scroll.setClipToOutline(true);
		LinearLayout content=SponsorUi.column(context);
		content.setPadding(V.dp(24), V.dp(12), V.dp(24), V.dp(24));
		View handle=new View(context);
		handle.setBackground(SponsorUi.rounded(UiUtils.getThemeColor(context, R.attr.colorM3Outline), 2));
		handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		LinearLayout.LayoutParams handleParams=new LinearLayout.LayoutParams(V.dp(36), V.dp(4));
		handleParams.gravity=Gravity.CENTER_HORIZONTAL; handleParams.bottomMargin=V.dp(24);
		content.addView(handle, handleParams);
		ImageView icon=new ImageView(context);
		icon.setImageResource(R.drawable.ic_volunteer_activism_24px);
		icon.setColorFilter(UiUtils.getThemeColor(context, R.attr.colorM3Primary));
		icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		LinearLayout.LayoutParams iconParams=new LinearLayout.LayoutParams(V.dp(40), V.dp(40)); iconParams.bottomMargin=V.dp(12);
		content.addView(icon, iconParams);
		SponsorUi.headline(content, title);
		SponsorUi.text(SponsorUi.card(content), body, false);
		if(continueAction!=null) SponsorUi.button(content, action, ()->{ dismiss(); continueAction.run(); });
		if(centerAction!=null) SponsorUi.button(content, context.getString(R.string.sponsor_ui_notice_center), ()->{ dismiss(); centerAction.run(); });
		SponsorUi.secondary(content, context.getString(R.string.sponsor_ui_cancel), this::dismiss);
		scroll.addView(content); setContentView(scroll);
		setNavigationBarBackground(new ColorDrawable(SponsorUi.cardColor(context)), !UiUtils.isDarkTheme());
	}
}
