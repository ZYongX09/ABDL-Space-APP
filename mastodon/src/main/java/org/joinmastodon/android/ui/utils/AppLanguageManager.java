package org.joinmastodon.android.ui.utils;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** App UI language, deliberately independent of an account's default posting language. */
public final class AppLanguageManager{
	public static final String SYSTEM="";
	public static final String CHINESE="zh-CN";
	public static final String ENGLISH="en";
	private static final String PREFERENCES="app_language";
	private static final String LANGUAGE="language_tag";
	private static final String MIGRATED="android_13_migrated";
	private static final Map<Activity, String> activities=new WeakHashMap<>();

	/** Editors snapshot their drafts before a language change can trigger recreation. */
	public interface DraftOwner{
		boolean prepareForAppLanguageChange();
	}

	private AppLanguageManager(){}

	/** The effective Resources locale, also used as the reading/translation target. */
	public static Locale getEffectiveLocale(Context context){
		LocaleList locales=context.getResources().getConfiguration().getLocales();
		return locales.isEmpty() ? Locale.ENGLISH : locales.get(0);
	}

	/** BCP 47; callers may normalize en/en-US for their own wire contract. */
	public static String getLanguageTag(Context context){
		return getEffectiveLocale(context).toLanguageTag();
	}

	/** Empty means follow system. Android 13's system picker remains authoritative. */
	public static String getSelectedLanguage(Context context){
		if(Build.VERSION.SDK_INT>=33){
			LocaleManager manager=context.getSystemService(LocaleManager.class);
			LocaleList locales=manager==null ? LocaleList.getEmptyLocaleList() : manager.getApplicationLocales();
			return locales.isEmpty() ? SYSTEM : locales.get(0).toLanguageTag();
		}
		return preferences(context).getString(LANGUAGE, SYSTEM);
	}

	/** A context hook for the Application and the existing AppKit host, not a new Activity base. */
	public static Context wrap(Context context){
		if(Build.VERSION.SDK_INT>=33 || getSelectedLanguage(context).isEmpty()) return context;
		Configuration configuration=new Configuration(context.getResources().getConfiguration());
		configuration.setLocales(requestedLocales(context));
		return context.createConfigurationContext(configuration);
	}

	public static void install(Application application){
		if(Build.VERSION.SDK_INT>=33){
			SharedPreferences preferences=preferences(application);
			if(!preferences.getBoolean(MIGRATED, false)){
				String previous=preferences.getString(LANGUAGE, SYSTEM);
				LocaleManager manager=application.getSystemService(LocaleManager.class);
				if(manager!=null && preferences.edit().putBoolean(MIGRATED, true).commit()
						&& manager.getApplicationLocales().isEmpty() && !previous.isEmpty()){
					manager.setApplicationLocales(LocaleList.forLanguageTags(previous));
				}
			}
		}else{
			applyResources(application);
		}
		application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
			@Override public void onActivityPreCreated(Activity activity, Bundle state){
				// API 29+: before even Activity.onCreate/fragment restoration. Only override locales.
				if(Build.VERSION.SDK_INT<33 && !getSelectedLanguage(activity).isEmpty()){
					Configuration override=new Configuration();
					override.fontScale=0; // Undefined: do not override the user's accessibility font scale.
					override.setLocales(requestedLocales(activity));
					try{
						activity.applyOverrideConfiguration(override);
					}catch(IllegalStateException resourcesAlreadyAccessed){
						applyResources(activity);
					}
				}
			}
			@Override public void onActivityCreated(Activity activity, Bundle state){
				// API 26-28: runs from Activity.super.onCreate, before native/Compose content inflation.
				if(Build.VERSION.SDK_INT<33) applyResources(activity);
				activities.put(activity, getLanguageTag(activity));
			}
			@Override public void onActivityResumed(Activity activity){
				if(Build.VERSION.SDK_INT>=33) return; // Framework owns system-picker recreation.
				String previous=activities.get(activity);
				applyResources(activity);
				if(previous!=null && !previous.equals(getLanguageTag(activity)) && prepareDrafts(activity)){
					activities.put(activity, getLanguageTag(activity));
					activity.recreate();
				}
			}
			@Override public void onActivityDestroyed(Activity activity){ activities.remove(activity); }
			@Override public void onActivityStarted(Activity activity){}
			@Override public void onActivityPaused(Activity activity){}
			@Override public void onActivityStopped(Activity activity){}
			@Override public void onActivitySaveInstanceState(Activity activity, Bundle state){}
		});
	}

	/** Called before date formatter refresh when Android dispatches a system configuration change. */
	public static void onConfigurationChanged(Context context){
		if(Build.VERSION.SDK_INT<33) applyResources(context);
	}

	/**
	 * Must be called on the main thread, after user confirmation. No process restart, finish or data
	 * clearing. False leaves the current language untouched (busy editor or preference write failure).
	 */
	public static boolean setLanguage(Activity caller, String languageTag){
		if(!SYSTEM.equals(languageTag) && !CHINESE.equals(languageTag) && !ENGLISH.equals(languageTag))
			throw new IllegalArgumentException("Unsupported app language");
		if(languageTag.equals(getSelectedLanguage(caller))) return true;
		ArrayList<Activity> current=new ArrayList<>(activities.keySet());
		if(!current.contains(caller)) current.add(caller);
		for(Activity activity:current){
			if(!activity.isFinishing() && !activity.isDestroyed()
					&& (activity.getFragmentManager().isStateSaved() || !prepareDrafts(activity))) return false;
		}
		if(Build.VERSION.SDK_INT>=33){
			LocaleManager manager=caller.getSystemService(LocaleManager.class);
			if(manager==null) return false;
			manager.setApplicationLocales(LocaleList.forLanguageTags(languageTag));
		}else{
			if(!preferences(caller).edit().putString(LANGUAGE, languageTag).commit()) return false;
			applyResources(caller.getApplication());
			UiUtils.updateLocalizedDateFormatters(caller.getApplication());
			for(Activity activity:current){
				if(!activity.isFinishing() && !activity.isDestroyed()){
					activities.put(activity, requestedLocales(activity).get(0).toLanguageTag());
					activity.recreate(); // Normal Android state saving preserves the fragment/view state.
				}
			}
		}
		return true;
	}

	private static boolean prepareDrafts(Activity activity){
		if(activity instanceof DraftOwner owner && !owner.prepareForAppLanguageChange()) return false;
		return prepareDrafts(activity.getFragmentManager());
	}

	private static boolean prepareDrafts(FragmentManager manager){
		for(Fragment fragment:manager.getFragments()){
			if(fragment==null || !fragment.isAdded()) continue;
			if(fragment instanceof DraftOwner owner && !owner.prepareForAppLanguageChange()) return false;
			if(!prepareDrafts(fragment.getChildFragmentManager())) return false;
		}
		return true;
	}

	private static SharedPreferences preferences(Context context){
		return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
	}

	private static LocaleList requestedLocales(Context context){
		String language=getSelectedLanguage(context);
		LocaleList locales=language.isEmpty() ? Resources.getSystem().getConfiguration().getLocales() : LocaleList.forLanguageTags(language);
		return locales.isEmpty() ? new LocaleList(Locale.ENGLISH) : locales;
	}

	@SuppressWarnings("deprecation")
	private static void applyResources(Context context){
		Resources resources=context.getResources();
		LocaleList locales=requestedLocales(context);
		if(!resources.getConfiguration().getLocales().equals(locales)){
			Configuration configuration=new Configuration(resources.getConfiguration());
			configuration.setLocales(locales);
			resources.updateConfiguration(configuration, resources.getDisplayMetrics());
		}
	}
}
