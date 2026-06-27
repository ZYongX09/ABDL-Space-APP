package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import org.joinmastodon.android.E;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountLocalPreferences;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.StatusDisplaySettingsChangedEvent;
import org.joinmastodon.android.model.viewmodel.CheckableListItem;
import org.joinmastodon.android.model.viewmodel.ListItem;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import me.grishka.appkit.FragmentStackActivity;

public class SettingsDisplayFragment extends BaseSettingsFragment<Void>{
	private ImageView themeTransitionWindowView;
	private ListItem<Void> themeItem;
	private CheckableListItem<Void> showCWsItem, hideSensitiveMediaItem, interactionCountsItem, emojiInNamesItem, dynamicColorsItem;

	// MOSHIDON:
	private ListItem<Void> colorItem;
	private CheckableListItem<Void> trueBlackModeItem;
	private AccountLocalPreferences lp;

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		setTitle(R.string.settings_display);

		// MOSHIDON:
		AccountSession s=AccountSessionManager.get(accountID);
		lp=s.getLocalPreferences();

		List<ListItem<Void>> items=new ArrayList<>();
		items.add(themeItem=new ListItem<>(R.string.settings_theme, getAppearanceValue(), R.drawable.ic_dark_mode_24px, this::onAppearanceClick));

		// MOSHIDON: we use out own theme variations
//		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
//			items.add(dynamicColorsItem=new CheckableListItem<>(R.string.settings_use_dynamic_colors, 0, CheckableListItem.Style.SWITCH, GlobalUserPreferences.useDynamicColors, R.drawable.ic_palette_24px, item->{
//				toggleCheckableItem(item);
//				setUseDynamicColors(item.checked);
//			}));
//			dynamicColorsItem.checkedChangeListener=this::setUseDynamicColors;
//		}

		// MOSHIDON:
		items.add(colorItem=new ListItem<>(getString(R.string.sk_settings_color_palette), getColorPaletteValue(), R.drawable.ic_fluent_color_24_regular, this::onColorClick));
		items.add(trueBlackModeItem=new CheckableListItem<>(R.string.sk_settings_true_black, R.string.mo_setting_true_black_summary, CheckableListItem.Style.SWITCH, GlobalUserPreferences.trueBlackTheme, R.drawable.ic_fluent_dark_theme_24_regular, i->onTrueBlackModeClick(), true));

		items.add(showCWsItem=new CheckableListItem<>(R.string.settings_show_cws, 0, CheckableListItem.Style.SWITCH, GlobalUserPreferences.showCWs, R.drawable.ic_warning_24px, this::toggleCheckableItem));
		items.add(hideSensitiveMediaItem=new CheckableListItem<>(R.string.settings_hide_sensitive_media, 0, CheckableListItem.Style.SWITCH, GlobalUserPreferences.hideSensitiveMedia, R.drawable.ic_no_adult_content_24px, this::toggleCheckableItem));
		items.add(interactionCountsItem=new CheckableListItem<>(R.string.settings_show_interaction_counts, 0, CheckableListItem.Style.SWITCH, GlobalUserPreferences.showInteractionCounts, R.drawable.ic_social_leaderboard_24px, this::toggleCheckableItem));
		items.add(emojiInNamesItem=new CheckableListItem<>(R.string.settings_show_emoji_in_names, 0, CheckableListItem.Style.SWITCH, GlobalUserPreferences.customEmojiInNames, R.drawable.ic_emoticon_24px, this::toggleCheckableItem));
		onDataLoaded(items);
	}

	@Override
	protected void doLoadData(int offset, int count){}

	@Override
	public void onAttach(Activity activity){
		super.onAttach(activity);
		if(themeTransitionWindowView!=null){
			// Activity has finished recreating. Remove the overlay.
			activity.getSystemService(WindowManager.class).removeView(themeTransitionWindowView);
			themeTransitionWindowView=null;
		}
	}

	@Override
	protected void onHidden(){
		super.onHidden();
		GlobalUserPreferences.showCWs=showCWsItem.checked;
		GlobalUserPreferences.hideSensitiveMedia=hideSensitiveMediaItem.checked;
		GlobalUserPreferences.showInteractionCounts=interactionCountsItem.checked;
		GlobalUserPreferences.customEmojiInNames=emojiInNamesItem.checked;
		GlobalUserPreferences.save();
		E.post(new StatusDisplaySettingsChangedEvent(accountID));
	}

	private int getAppearanceValue(){
		return switch(GlobalUserPreferences.theme){
			case AUTO -> R.string.theme_auto;
			case LIGHT -> R.string.theme_light;
			case DARK -> R.string.theme_dark;
		};
	}

	private void onAppearanceClick(ListItem<?> item_){
		int selected=switch(GlobalUserPreferences.theme){
			case LIGHT -> 0;
			case DARK -> 1;
			case AUTO -> 2;
		};
		new M3AlertDialogBuilder(getActivity())
				.setTitle(R.string.settings_theme)
				.setSingleChoiceItems((String[])IntStream.of(R.string.theme_light, R.string.theme_dark, R.string.theme_auto).mapToObj(this::getString).toArray(String[]::new),
						selected, (dlg, item)->{
							GlobalUserPreferences.ThemePreference pref=switch(item){
								case 0 -> GlobalUserPreferences.ThemePreference.LIGHT;
								case 1 -> GlobalUserPreferences.ThemePreference.DARK;
								case 2 -> GlobalUserPreferences.ThemePreference.AUTO;
								default -> throw new IllegalStateException("Unexpected value: "+item);
							};
							if(pref!=GlobalUserPreferences.theme){
								GlobalUserPreferences.ThemePreference prev=GlobalUserPreferences.theme;
								GlobalUserPreferences.theme=pref;
								GlobalUserPreferences.save();
								themeItem.subtitleRes=getAppearanceValue();
								rebindItem(themeItem);
								maybeApplyNewThemeRightNow(prev);
							}
							dlg.dismiss();
						})
				.show();
	}

	// MOSHIDON:
	private void onColorClick(ListItem<?> item_){
		boolean multiple=AccountSessionManager.getInstance().getLoggedInAccounts().size() > 1;
		int indexOffset=multiple ? 1 : 0;
		int selected=lp.color==null ? 0 : lp.color.ordinal() + indexOffset;
		int[] newSelected={selected};
		List<String> items=Arrays.stream(AccountLocalPreferences.ColorPreference.values()).map(AccountLocalPreferences.ColorPreference::getName).map(this::getString).collect(Collectors.toList());
		if(multiple)
			items.add(0, getString(R.string.sk_settings_color_palette_default, items.get(GlobalUserPreferences.color.ordinal())));

		Consumer<Boolean> save=(asDefault)->{
			boolean defaultSelected=multiple && newSelected[0]==0;
			AccountLocalPreferences.ColorPreference pref=defaultSelected ? null : AccountLocalPreferences.ColorPreference.values()[newSelected[0]-indexOffset];
			if(pref!=lp.color){
				AccountLocalPreferences.ColorPreference prev=lp.color;
				lp.color=asDefault ? null : pref;
				lp.save();
				if((asDefault || !multiple) && pref!=null){
					GlobalUserPreferences.color=pref;
					GlobalUserPreferences.save();
				}
				colorItem.subtitle=getColorPaletteValue();
				rebindItem(colorItem);
				if(prev==null && pref!=null) restartActivityToApplyNewTheme();
				else maybeApplyNewThemeRightNow(null, prev, null);
			}
		};

		AlertDialog.Builder alert=new M3AlertDialogBuilder(getActivity())
				.setTitle(R.string.sk_settings_color_palette)
				.setSingleChoiceItems(items.stream().toArray(String[]::new),
						selected, (dlg, item)->newSelected[0]=item)
				.setPositiveButton(R.string.ok, (dlg, item)->save.accept(false))
				.setNegativeButton(R.string.cancel, null);
		if(multiple) alert.setNeutralButton(R.string.sk_set_as_default, (dlg, item)->save.accept(true));
		alert.show();
	}

	private void setUseDynamicColors(boolean useDynamicColors){
		dynamicColorsItem.checked=useDynamicColors;
		GlobalUserPreferences.useDynamicColors=useDynamicColors;
		GlobalUserPreferences.save();
		restartActivityToApplyNewTheme();
	}

	// MOSHIDON:
	private String getColorPaletteValue(){
		AccountLocalPreferences.ColorPreference color=AccountSessionManager.get(accountID).getLocalPreferences().color;
		return color==null
				? getString(R.string.sk_settings_color_palette_default, getString(GlobalUserPreferences.color.getName()))
				: getString(color.getName());
	}

	// MOSHIDON: may the overlords forgive my overloads
	private void maybeApplyNewThemeRightNow(GlobalUserPreferences.ThemePreference prevTheme) {
		maybeApplyNewThemeRightNow(prevTheme,null, null);
	}

	// MOSHIDON:
	private void onTrueBlackModeClick(){
		toggleCheckableItem(trueBlackModeItem);
		boolean prev=GlobalUserPreferences.trueBlackTheme;
		GlobalUserPreferences.trueBlackTheme=trueBlackModeItem.checked;
		maybeApplyNewThemeRightNow(null, null, prev);
	}

	// MOSHIDON: more parameters because we need more things
	private void maybeApplyNewThemeRightNow(GlobalUserPreferences.ThemePreference prevTheme, AccountLocalPreferences.ColorPreference prevColor, Boolean prevTrueBlack){
		if(prevTheme==null) prevTheme=GlobalUserPreferences.theme;
		if(prevTrueBlack==null) prevTrueBlack=GlobalUserPreferences.trueBlackTheme;
		if(prevColor==null) prevColor=lp.getCurrentColor();

		boolean isCurrentDark=prevTheme==GlobalUserPreferences.ThemePreference.DARK ||
				(prevTheme==GlobalUserPreferences.ThemePreference.AUTO && Build.VERSION.SDK_INT>=30 && getResources().getConfiguration().isNightModeActive());
		boolean isNewDark=GlobalUserPreferences.theme==GlobalUserPreferences.ThemePreference.DARK ||
				(GlobalUserPreferences.theme==GlobalUserPreferences.ThemePreference.AUTO && Build.VERSION.SDK_INT>=30 && getResources().getConfiguration().isNightModeActive());

		// MOSHIDON
		boolean isNewBlack=GlobalUserPreferences.trueBlackTheme;
		if(isCurrentDark!=isNewDark || prevColor!=lp.getCurrentColor() || (isNewDark && prevTrueBlack!=isNewBlack)){
			restartActivityToApplyNewTheme();
		}
	}

	private void restartActivityToApplyNewTheme(){
		// Calling activity.recreate() causes a black screen for like half a second.
		// So, let's take a screenshot and overlay it on top to create the illusion of a smoother transition.
		// As a bonus, we can fade it out to make it even smoother.
		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.N && Build.VERSION.SDK_INT<Build.VERSION_CODES.S){
			View activityDecorView=getActivity().getWindow().getDecorView();
			Bitmap bitmap=Bitmap.createBitmap(activityDecorView.getWidth(), activityDecorView.getHeight(), Bitmap.Config.ARGB_8888);
			activityDecorView.draw(new Canvas(bitmap));
			themeTransitionWindowView=new ImageView(MastodonApp.context);
			themeTransitionWindowView.setImageBitmap(bitmap);
			WindowManager.LayoutParams lp=new WindowManager.LayoutParams(WindowManager.LayoutParams.TYPE_APPLICATION);
			lp.flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
					WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
			lp.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
			lp.systemUiVisibility|=(activityDecorView.getWindowSystemUiVisibility() & (View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
			lp.width=lp.height=WindowManager.LayoutParams.MATCH_PARENT;
			lp.token=getActivity().getWindow().getAttributes().token;
			lp.windowAnimations=R.style.window_fade_out;
			MastodonApp.context.getSystemService(WindowManager.class).addView(themeTransitionWindowView, lp);
		}
		getActivity().recreate();
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState){
		super.onViewCreated(view, savedInstanceState);
		((FragmentStackActivity)getActivity()).invalidateSystemBarColors(this);
	}
}
