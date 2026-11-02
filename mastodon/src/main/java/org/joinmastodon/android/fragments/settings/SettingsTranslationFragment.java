package org.joinmastodon.android.fragments.settings;

import android.os.Bundle;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.TranslationPreferences;
import org.joinmastodon.android.model.viewmodel.CheckableListItem;
import org.joinmastodon.android.model.viewmodel.ListItem;
import org.joinmastodon.android.translation.PostTranslationCoordinator;
import org.joinmastodon.android.ui.utils.AppLanguageManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Account-owned opt-in; target follows app language, never the compose language. */
public class SettingsTranslationFragment extends BaseSettingsFragment<Void>{
	private PostTranslationCoordinator coordinator;
	private PostTranslationCoordinator.Cancelable subscription;

	@Override public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		setTitle(R.string.post_translation_settings);
		onDataLoaded(new ArrayList<>());
	}
	@Override protected void doLoadData(int offset, int count){}
	@Override public void onResume(){
		super.onResume();
		coordinator=PostTranslationCoordinator.get(getActivity(), accountID);
		if(coordinator!=null){
			if(subscription!=null) subscription.cancel();
			subscription=coordinator.observePreferences(this::render);
			coordinator.refreshPreferences(true);
		}
		render();
	}
	@Override public void onPause(){
		if(subscription!=null){ subscription.cancel(); subscription=null; }
		super.onPause();
	}
	@Override public void onDestroyView(){
		if(subscription!=null){ subscription.cancel(); subscription=null; }
		super.onDestroyView();
	}
	private void render(){
		if(getActivity()==null) return;
		TranslationPreferences preferences=coordinator==null ? null : coordinator.preferences();
		boolean busy=coordinator!=null && coordinator.preferencesBusy();
		List<ListItem<Void>> items=new ArrayList<>();
		Locale locale=AppLanguageManager.getEffectiveLocale(getActivity());
		ListItem<Void> language=new ListItem<>(getString(R.string.post_translation_reading_language),
				getString(R.string.post_translation_reading_description, locale.getDisplayName(locale)), R.drawable.ic_language_24px, null);
		language.isEnabled=false; items.add(language);
		CheckableListItem<Void> automatic=new CheckableListItem<>(getString(R.string.post_translation_auto),
				getString(preferences!=null && (!preferences.enabled || !preferences.providerConfigured || !preferences.autoTranslateAllowed)
						? R.string.post_translation_auto_unavailable : R.string.post_translation_auto_description),
				CheckableListItem.Style.SWITCH, preferences!=null && preferences.autoTranslateEnabled,
				item->{ if(coordinator!=null && !coordinator.preferencesBusy()) coordinator.setAutomatic(!item.checked); });
		automatic.isEnabled=preferences!=null && !busy; items.add(automatic);
		if(preferences!=null && preferences.quota!=null){
			var quota=preferences.quota;
			ListItem<Void> allowance=new ListItem<>(getString(R.string.post_translation_quota),
					getString(R.string.post_translation_quota_value, quota.used, quota.reserved, quota.limit), 0, null);
			allowance.isEnabled=false; items.add(allowance);
		}
		ListItem<Void> reload=new ListItem<>(getString(R.string.post_translation_reload),
				getString(busy ? R.string.post_translation_preferences_loading : preferences==null
						? R.string.post_translation_preferences_failed : R.string.post_translation_auto_description),
				R.drawable.ic_info_24px, item->{ if(coordinator!=null) coordinator.refreshPreferences(true); });
		reload.isEnabled=!busy; items.add(reload);
		data.clear(); data.addAll(items);
		if(itemsAdapter!=null) itemsAdapter.notifyDataSetChanged();
	}
}
