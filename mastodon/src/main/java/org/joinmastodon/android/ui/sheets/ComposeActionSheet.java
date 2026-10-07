package org.joinmastodon.android.ui.sheets;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.util.function.IntConsumer;

import me.grishka.appkit.utils.V;
import me.grishka.appkit.views.BottomSheet;

/** The same three publishing destinations as the liquid home compose menu. */
public class ComposeActionSheet extends BottomSheet{
	private final LinearLayout cards;
	private final IntConsumer listener;
	private boolean actionDelivered;
	private boolean cardsInRow;

	public ComposeActionSheet(Context context, IntConsumer listener){
		super(context);
		this.listener=listener;
		View root=LayoutInflater.from(context).inflate(R.layout.compose_action_sheet, null);
		cards=root.findViewById(R.id.compose_action_cards);
		addCard(R.id.compose_post, R.string.compose_menu_post, R.string.compose_action_post_description,
				R.drawable.compose_action_notes, R.attr.colorM3PrimaryContainer, R.attr.colorM3OnPrimaryContainer);
		addCard(R.id.compose_friend_request, R.string.compose_menu_friend_request, R.string.compose_action_friend_description,
				R.drawable.compose_action_contacts, R.attr.colorM3SecondaryContainer, R.attr.colorM3OnSecondaryContainer);
		addCard(R.id.compose_album, R.string.baby_albums_compose_upload, R.string.compose_action_album_description,
				R.drawable.compose_action_image, R.attr.colorM3TertiaryContainer, R.attr.colorM3OnTertiaryContainer);
		root.findViewById(R.id.compose_action_cancel).setOnClickListener(v->dismiss());
		setContentView(root);
		// AppKit handles system-bar padding on the outer container, including the right inset.
		setNavigationBarBackground(new ColorDrawable(UiUtils.getThemeColor(context, R.attr.colorM3Surface)),
				!UiUtils.isDarkTheme());
		cards.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob)->updateCardLayout(r-l));
		updateCardLayout(0);
	}

	private void addCard(int id, int title, int description, int icon, int backgroundAttr, int foregroundAttr){
		Context context=getContext();
		LinearLayout card=(LinearLayout)LayoutInflater.from(context).inflate(R.layout.compose_action_card, cards, false);
		card.setId(id);
		int foreground=UiUtils.getThemeColor(context, foregroundAttr);
		GradientDrawable background=new GradientDrawable();
		background.setColor(UiUtils.getThemeColor(context, backgroundAttr));
		background.setCornerRadius(V.dp(24));
		GradientDrawable mask=new GradientDrawable();
		mask.setColor(foreground);
		mask.setCornerRadius(V.dp(24));
		card.setBackground(new RippleDrawable(ColorStateList.valueOf(
				UiUtils.getThemeColor(context, R.attr.colorM3OnSurface)&0x33ffffff), background, mask));
		TextView headline=card.findViewById(R.id.compose_action_card_title);
		headline.setText(title);
		headline.setTextColor(foreground);
		TextView subtitle=card.findViewById(R.id.compose_action_card_description);
		subtitle.setText(description);
		subtitle.setTextColor(foreground);
		ImageView image=card.findViewById(R.id.compose_action_card_icon);
		image.setImageResource(icon);
		image.setImageTintList(ColorStateList.valueOf(foreground));
		card.setContentDescription(context.getString(title)+". "+context.getString(description));
		card.setAccessibilityDelegate(new View.AccessibilityDelegate(){
			@Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info){
				super.onInitializeAccessibilityNodeInfo(host, info);
				info.setClassName(android.widget.Button.class.getName());
			}
		});
		card.setOnClickListener(v->selectAction(id));
		cards.addView(card);
	}

	private void selectAction(int id){
		if(actionDelivered || dismissed || !isShowing())
			return;
		actionDelivered=true;
		// Animated dismissal leaves the dialog attached until animation end. Remove it first,
		// so navigation never runs underneath the sheet and rapid/re-entrant taps cannot dispatch twice.
		dismissWithoutAnimation();
		listener.accept(id);
	}

	private void updateCardLayout(int width){
		float fontScale=getContext().getResources().getConfiguration().fontScale;
		boolean row=width>=V.dp(336) && fontScale<=1.15f;
		if(cards.getTag()!=null && cardsInRow==row)
			return;
		cards.setTag(Boolean.TRUE);
		cardsInRow=row;
		cards.setOrientation(row ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
		for(int i=0; i<cards.getChildCount(); i++){
			View card=cards.getChildAt(i);
			LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(row ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT, row ? 1 : 0);
			if(row)
				params.setMarginEnd(i<cards.getChildCount()-1 ? V.dp(10) : 0);
			else
				params.bottomMargin=i<cards.getChildCount()-1 ? V.dp(12) : 0;
			card.setMinimumHeight(V.dp(row ? 176 : 136));
			card.setLayoutParams(params);
		}
		cards.setGravity(Gravity.TOP);
	}

	@Override protected void onWindowInsetsUpdated(WindowInsets insets){
		super.onWindowInsetsUpdated(insets);
		if(android.os.Build.VERSION.SDK_INT>=28 && insets.getDisplayCutout()!=null){
			// Some devices report a larger landscape cutout than their system-bar side inset.
			View insetContainer=container;
			insetContainer.setPadding(Math.max(insets.getSystemWindowInsetLeft(), insets.getDisplayCutout().getSafeInsetLeft()),
					insetContainer.getPaddingTop(), Math.max(insets.getSystemWindowInsetRight(), insets.getDisplayCutout().getSafeInsetRight()),
					insetContainer.getPaddingBottom());
		}
	}
}
