package org.joinmastodon.android.friendcards;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.sponsors.SponsorContrast;
import org.joinmastodon.android.ui.utils.UiUtils;

/** UI projection only: availability comes exclusively from the server's public reference. */
public final class PublicFriendCardUi{
	private PublicFriendCardUi(){}

	public static boolean hasReference(Account account){
		// Native user IDs only. Synthetic/admin/NBW identities must never acquire this marker.
		return account!=null && account.id!=null && account.id.matches("[1-9][0-9]*")
				&& !account.suspended && account.publicFriendCard!=null && account.publicFriendCard.isSupported();
	}

	public static boolean hasHeaderReference(Account account, Status status){
		return hasReference(account) && status!=null && status.advertisement==null && !status.isRemote
				&& status.account!=null && account.id.equals(status.account.id);
	}

	/** The same baby-blue palette and contrast correction used for sponsor usernames, not their color. */
	public static int markerColor(boolean dark, int background){
		return SponsorContrast.ensure(dark ? 0xFFB9D9F0 : 0xFF4E7394, background);
	}

	private static int tint(View view){
		View current=view;
		while(current!=null){
			Drawable drawable=current.getBackground();
			if(drawable instanceof ColorDrawable color && Color.alpha(color.getColor())==255)
				return markerColor(UiUtils.isDarkTheme(), color.getColor());
			ViewParent parent=current.getParent();
			current=parent instanceof View ? (View) parent : null;
		}
		return markerColor(UiUtils.isDarkTheme(), UiUtils.getThemeColor(view.getContext(), R.attr.colorM3Surface));
	}

	public static void bindMarker(ImageView icon, Account account, Status status){
		clearIcon(icon);
		if(!hasHeaderReference(account, status)) return;
		icon.setImageResource(R.drawable.ic_friend_card);
		icon.setImageTintList(ColorStateList.valueOf(tint(icon)));
		icon.setContentDescription(icon.getContext().getString(R.string.friend_card_public_marker_description));
		icon.setVisibility(View.VISIBLE);
	}

	private static void clearIcon(ImageView icon){
		icon.setOnClickListener(null);
		icon.setClickable(false);
		icon.setFocusable(false);
		icon.setTag(null);
		icon.setContentDescription(null);
		icon.setImageTintList(null);
		icon.setImageDrawable(null);
		icon.setVisibility(View.GONE);
	}

	public static void clearMini(View row){
		row.setOnClickListener(null);
		row.setClickable(false);
		row.setFocusable(false);
		row.setEnabled(false);
		row.setTag(null);
		row.setContentDescription(null);
		row.setVisibility(View.GONE);
		clearIcon(row.findViewById(R.id.profile_friend_card_icon));
	}

	public static void bindMini(View row, Account account, boolean unavailable, View.OnClickListener listener){
		clearMini(row);
		if(unavailable || !hasReference(account)) return;
		ImageView icon=row.findViewById(R.id.profile_friend_card_icon);
		icon.setImageResource(R.drawable.ic_friend_card);
		icon.setImageTintList(ColorStateList.valueOf(tint(row)));
		icon.setVisibility(View.VISIBLE);
		row.setContentDescription(row.getContext().getString(R.string.friend_card_profile_view));
		row.setEnabled(true);
		row.setOnClickListener(listener);
		row.setFocusable(true);
		row.setVisibility(View.VISIBLE);
	}
}
