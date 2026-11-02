package org.joinmastodon.android.ui.utils;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.utils.V;

/** Real framework contexts/lifecycles; no network, app startup, process restarts or mock locale APIs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26, 28, 32}, application=Application.class, qualifiers="en-rUS-w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class AppLanguageManagerTest{
	private Application application;
	private final List<ActivityController<?>> controllers=new ArrayList<>();

	@Before public void setup(){
		application=RuntimeEnvironment.getApplication();
		MastodonApp.context=application;
		V.setApplicationContext(application);
		application.getSharedPreferences("app_language", Context.MODE_PRIVATE).edit().clear().commit();
		if(Build.VERSION.SDK_INT>=33) application.getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.getEmptyLocaleList());
		AppLanguageManager.install(application);
		DraftHost.snapshots=0;
	}

	@After public void cleanup(){
		for(ActivityController<?> controller:controllers) controller.close();
		application.getSharedPreferences("app_language", Context.MODE_PRIVATE).edit().clear().commit();
	}

	private <T extends Activity> ActivityController<T> open(Class<T> type){
		ActivityController<T> controller=Robolectric.buildActivity(type).setup();
		controllers.add(controller);
		return controller;
	}

	@Test public void effectiveLocaleComesFromThePassedResourcesNotPostingOrGlobalLocale(){
		Locale previous=Locale.getDefault();
		try{
			Locale.setDefault(Locale.JAPANESE);
			Configuration configuration=new Configuration(application.getResources().getConfiguration());
			configuration.setLocales(LocaleList.forLanguageTags("en-US"));
			Context english=application.createConfigurationContext(configuration);
			configuration.setLocales(LocaleList.forLanguageTags("zh-CN"));
			Context chinese=application.createConfigurationContext(configuration);
			assertEquals("en-US", AppLanguageManager.getLanguageTag(english));
			assertEquals(Locale.forLanguageTag("zh-CN"), AppLanguageManager.getEffectiveLocale(chinese));
			assertEquals("App language", english.getString(R.string.app_language_settings));
			assertEquals("应用语言", chinese.getString(R.string.app_language_settings));
		}finally{ Locale.setDefault(previous); }
	}

	@Test public void lifecycleAppliesLegacyOverrideToNativeAppKitAndComponentHosts(){
		application.getSharedPreferences("app_language", Context.MODE_PRIVATE).edit().putString("language_tag", "zh-CN").commit();
		for(Class<? extends Activity> type:List.of(DraftHost.class, AppKitHost.class, ComponentHost.class)){
			Activity activity=open(type).get();
			assertEquals(type.getSimpleName(), "zh-CN", AppLanguageManager.getLanguageTag(activity));
			assertEquals("应用语言", activity.getString(R.string.app_language_settings));
			assertFalse(activity.isFinishing());
		}
	}

	@Test public void localeOverrideDoesNotResetAccessibilityFontScale(){
		application.getSharedPreferences("app_language", Context.MODE_PRIVATE).edit().putString("language_tag", "zh-CN").commit();
		Configuration configuration=new Configuration(application.getResources().getConfiguration());
		configuration.fontScale=1.8f;
		Context wrapped=AppLanguageManager.wrap(application.createConfigurationContext(configuration));
		assertEquals(1.8f, wrapped.getResources().getConfiguration().fontScale, 0.001f);
		assertEquals("zh-CN", AppLanguageManager.getLanguageTag(wrapped));
	}

	@Test public void busyEditorVetoesBeforePreferenceWriteOrRecreation(){
		DraftHost host=open(DraftHost.class).get();
		host.editor.setText("unsaved local draft"); host.busy=true;
		assertFalse(AppLanguageManager.setLanguage(host, "zh-CN"));
		assertEquals("", AppLanguageManager.getSelectedLanguage(host));
		assertEquals("unsaved local draft", host.editor.getText().toString());
		assertEquals(0, DraftHost.snapshots);
		assertFalse(host.isFinishing());
		assertFalse(host.isChangingConfigurations());
	}

	@Test public void nestedNativeFragmentCanVetoWithoutLosingItsInput(){
		DraftHost host=open(DraftHost.class).get();
		VetoFragment parent=new VetoFragment(); parent.block=false;
		host.getFragmentManager().beginTransaction().add(parent, "parent").commit();
		host.getFragmentManager().executePendingTransactions();
		VetoFragment child=new VetoFragment();
		parent.getChildFragmentManager().beginTransaction().add(child, "child").commit();
		parent.getChildFragmentManager().executePendingTransactions();
		assertFalse(AppLanguageManager.setLanguage(host, "en"));
		assertTrue(child.asked);
		assertEquals("", AppLanguageManager.getSelectedLanguage(host));
	}

	@Test public void successfulSwitchSnapshotsThenRealActivityRecreationRestoresDraft(){
		ActivityController<DraftHost> controller=open(DraftHost.class);
		DraftHost original=controller.get(); original.editor.setText("draft retained through zh/en change");
		assertTrue(AppLanguageManager.setLanguage(original, "zh-CN"));
		assertTrue(DraftHost.snapshots>0);
		// Drive the actual framework save/destroy/create lifecycle, not a hand-built replacement view.
		controller.recreate();
		DraftHost recreated=controller.get();
		assertNotSame(original, recreated);
		assertEquals("draft retained through zh/en change", recreated.editor.getText().toString());
		assertEquals("zh-CN", AppLanguageManager.getLanguageTag(recreated));
		assertEquals("应用语言", recreated.label.getText().toString());
		assertTrue(AppLanguageManager.setLanguage(recreated, "en"));
		controller.recreate();
		assertEquals("draft retained through zh/en change", controller.get().editor.getText().toString());
		assertEquals("en", AppLanguageManager.getSelectedLanguage(controller.get()));
		assertEquals("App language", controller.get().label.getText().toString());
	}

	@Test public void followSystemClearsLegacyOverrideAndLeavesDataIntact(){
		ActivityController<DraftHost> controller=open(DraftHost.class);
		application.getSharedPreferences("unrelated_draft", Context.MODE_PRIVATE).edit().putString("text", "keep me").commit();
		assertTrue(AppLanguageManager.setLanguage(controller.get(), "zh-CN"));
		controller.recreate();
		assertTrue(AppLanguageManager.setLanguage(controller.get(), ""));
		controller.recreate();
		assertEquals("", AppLanguageManager.getSelectedLanguage(controller.get()));
		assertEquals("keep me", application.getSharedPreferences("unrelated_draft", Context.MODE_PRIVATE).getString("text", null));
		assertEquals(android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0), AppLanguageManager.getEffectiveLocale(controller.get()));
	}

	@Test public void onlyStatelessSettingsScreensOptOutOfRetention() throws Exception{
		java.nio.file.Path root=java.nio.file.Path.of(System.getProperty("user.dir"));
		if(!java.nio.file.Files.exists(root.resolve("src/main"))) root=root.resolve("mastodon");
		String main=java.nio.file.Files.readString(root.resolve("src/main/java/org/joinmastodon/android/fragments/settings/SettingsMainFragment.java"));
		String account=java.nio.file.Files.readString(root.resolve("src/main/java/org/joinmastodon/android/fragments/settings/SettingsAccountFragment.java"));
		String base=java.nio.file.Files.readString(root.resolve("src/main/java/org/joinmastodon/android/fragments/settings/BaseSettingsFragment.java"));
		assertTrue(main.contains("setRetainInstance(false)"));
		assertTrue(account.contains("setRetainInstance(false)"));
		assertTrue("Do not discard draft-bearing settings fragments globally", base.contains("setRetainInstance(true)"));
		assertTrue(account.contains("SettingsTranslationFragment.class"));
		assertTrue(main.contains("AppLanguageManager.setLanguage"));
	}

	@Test @Config(sdk=33) public void android13SystemPickerIsAuthoritativeOverLegacyPreference(){
		LocaleManager manager=application.getSystemService(LocaleManager.class);
		assertNotNull(manager);
		application.getSharedPreferences("app_language", Context.MODE_PRIVATE).edit().putString("language_tag", "en").commit();
		manager.setApplicationLocales(LocaleList.forLanguageTags("zh-CN"));
		assertEquals("zh-CN", AppLanguageManager.getSelectedLanguage(application));
		manager.setApplicationLocales(LocaleList.getEmptyLocaleList());
		assertEquals("", AppLanguageManager.getSelectedLanguage(application));
		DraftHost host=open(DraftHost.class).get(); host.busy=true;
		assertFalse(AppLanguageManager.setLanguage(host, "zh-CN"));
		assertTrue(manager.getApplicationLocales().isEmpty());
		host.busy=false;
		assertTrue(AppLanguageManager.setLanguage(host, "en"));
		assertEquals("en", manager.getApplicationLocales().toLanguageTags());
	}

	public static class DraftHost extends Activity implements AppLanguageManager.DraftOwner{
		static int snapshots;
		EditText editor;
		TextView label;
		boolean busy;
		@Override protected void attachBaseContext(Context base){ super.attachBaseContext(AppLanguageManager.wrap(base)); }
		@Override protected void onCreate(Bundle state){
			setTheme(R.style.Theme_Mastodon_Light); super.onCreate(state);
			android.widget.LinearLayout root=new android.widget.LinearLayout(this); root.setOrientation(1);
			editor=new EditText(this); editor.setId(android.R.id.input);
			if(state!=null) editor.setText(state.getString("draft", ""));
			label=new TextView(this); label.setText(R.string.app_language_settings);
			root.addView(editor); root.addView(label); setContentView(root);
		}
		@Override protected void onSaveInstanceState(Bundle state){ state.putString("draft", editor.getText().toString()); super.onSaveInstanceState(state); }
		@Override public boolean prepareForAppLanguageChange(){ if(busy) return false; snapshots++; return true; }
	}

	public static class AppKitHost extends FragmentStackActivity{
		@Override protected void onCreate(Bundle state){ setTheme(R.style.Theme_Mastodon_Light); super.onCreate(state); }
	}
	public static class ComponentHost extends ComponentActivity{
		@Override protected void onCreate(Bundle state){ setTheme(R.style.Theme_Mastodon_Light); super.onCreate(state); TextView text=new TextView(this); text.setText(R.string.app_language_settings); setContentView(text); }
	}
	public static class VetoFragment extends Fragment implements AppLanguageManager.DraftOwner{
		boolean block=true, asked;
		@Override public boolean prepareForAppLanguageChange(){ asked=true; return !block; }
	}
}
