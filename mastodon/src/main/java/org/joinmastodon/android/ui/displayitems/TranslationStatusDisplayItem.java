package org.joinmastodon.android.ui.displayitems;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.utils.AppLanguageManager;

import java.util.Locale;

import me.grishka.appkit.utils.V;

/** Compact 12sp translation module; its action keeps a full 48dp touch target. */
public class TranslationStatusDisplayItem extends StatusDisplayItem{
	public final Status contentStatus;
	public final Status visibilityStatus;
	public final String accountID;
	public TranslationStatusDisplayItem(String parentID, Callbacks callbacks, Context context, Status contentStatus, Status visibilityStatus, String accountID){
		super(parentID, callbacks, context);
		this.contentStatus=contentStatus; this.visibilityStatus=visibilityStatus; this.accountID=accountID;
		status=contentStatus;
	}
	@Override public Type getType(){ return Type.TRANSLATION; }
	public static class Holder extends StatusDisplayItem.Holder<TranslationStatusDisplayItem>{
		private final LinearLayout row;
		private final TextView label, action;
		private final ImageView icon;
		public Holder(Context context, ViewGroup parent){
			super(new FrameLayout(context));
			row=new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL); row.setOrientation(LinearLayout.HORIZONTAL);
			((FrameLayout)itemView).addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			icon=new ImageView(context); icon.setId(R.id.post_translation_icon); icon.setImageResource(R.drawable.ic_abdl_icon);
			icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); row.addView(icon, new LinearLayout.LayoutParams(V.dp(16), V.dp(16)));
			label=new TextView(context); label.setId(R.id.post_translation_label); label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
			// Let the source-language label grow at narrow widths/large font scales; never clip "from xx".
			label.setPaddingRelative(V.dp(6), 0, 0, 0);
			row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
			action=new TextView(context); action.setId(R.id.post_translation_action); action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
			action.setGravity(Gravity.CENTER_VERTICAL); action.setPaddingRelative(V.dp(8), 0, V.dp(8), 0);
			action.setMinHeight(V.dp(48)); action.setMinWidth(V.dp(48)); action.setFocusable(true);
			action.setBackgroundResource(android.R.drawable.list_selector_background);
			row.addView(action, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			action.setOnClickListener(v->item.callbacks.togglePostTranslation(item.contentStatus, item.parentID));
		}
		@Override public void onBind(TranslationStatusDisplayItem item){
			Context context=itemView.getContext();
			itemView.setPaddingRelative(V.dp(item.fullWidth ? 16 : 64), 0, V.dp(8), 0);
			// Resolve explicit day/night resources independently of any global primary palette.
			boolean dark=org.joinmastodon.android.ui.utils.UiUtils.isDarkTheme();
			int blue=context.getColor(dark ? R.color.post_translation_action_blue_dark : R.color.post_translation_action_blue);
			int gray=context.getColor(dark ? R.color.post_translation_label_gray_dark : R.color.post_translation_label_gray);
			action.setTextColor(blue); label.setTextColor(gray); icon.setImageTintList(ColorStateList.valueOf(gray));
			boolean shown=item.contentStatus.translationState==Status.TranslationState.SHOWN && item.contentStatus.translation!=null;
			boolean loading=item.contentStatus.translationState==Status.TranslationState.LOADING;
			label.setVisibility(shown ? View.VISIBLE : View.GONE);
			LinearLayout.LayoutParams actionParams=(LinearLayout.LayoutParams)action.getLayoutParams();
			actionParams.width=shown ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
			actionParams.weight=shown ? 0 : 1;
			action.setLayoutParams(actionParams);
			if(shown){
				String language=Locale.forLanguageTag(item.contentStatus.translation.detectedSourceLanguage).getDisplayLanguage(AppLanguageManager.getEffectiveLocale(context));
				label.setText(context.getString(R.string.post_translation_from, language));
			}
			action.setText(shown ? R.string.post_translation_show_original : loading ? R.string.post_translation_loading : R.string.post_translation_translate);
			action.setEnabled(item.callbacks.supportsPostTranslation());
		}
	}
}
