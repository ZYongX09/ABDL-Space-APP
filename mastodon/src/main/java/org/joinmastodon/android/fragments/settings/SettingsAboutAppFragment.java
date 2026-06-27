package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.SplashFragment;
import org.joinmastodon.android.model.viewmodel.ListItem;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.Snackbar;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.ToNumberPolicy;

import me.grishka.appkit.Nav;
import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.disklrucache.DiskLruCache;
import me.grishka.appkit.utils.MergeRecyclerAdapter;
import me.grishka.appkit.utils.SingleViewRecyclerAdapter;
import me.grishka.appkit.utils.V;

public class SettingsAboutAppFragment extends BaseSettingsFragment<Void>{
	private ListItem<Void> mediaCacheItem;
	private ListItem<Void> clearRecentEmojisItem, exportItem, importItem;
	private static final int IMPORT_RESULT=314;
	private static final int EXPORT_RESULT=271;
	private static final String TAG="SettingsAboutAppFragment";
	private int eggTapCount=0;
	private Toast eggToast;

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		setTitle(getString(R.string.about_app, getString(R.string.app_name)));
		AccountSession s=AccountSessionManager.get(accountID);

		Consumer<ListItem<Void>> openSourceClick = i -> Nav.go(getActivity(), OpenSourceLicensesFragment.class, null);

		onDataLoaded(List.of(
				// === 支持我们 ===
				new ListItem<>(R.string.support_us, 0, this::openSupportUs),
				// === 法律协议 ===
				new ListItem<>(R.string.settings_tos, 0, (Consumer<ListItem<Void>>) i->UiUtils.launchWebBrowser(getActivity(), "https://abdl-space.top/terms")),
				new ListItem<>(R.string.settings_privacy_policy, 0, (Consumer<ListItem<Void>>) i->UiUtils.launchWebBrowser(getActivity(), "https://abdl-space.top/privacy"), 0, false),
				new ListItem<>(R.string.settings_cookie_policy, 0, (Consumer<ListItem<Void>>) i->UiUtils.launchWebBrowser(getActivity(), "https://abdl-space.top/cookies"), 0, true),
				// === 链接 ===
				new ListItem<>(R.string.settings_about_website, 0, (Consumer<ListItem<Void>>) i->UiUtils.launchWebBrowser(getActivity(), "https://abdl-space.top")),
				new ListItem<>(R.string.settings_github, 0, (Consumer<ListItem<Void>>) i->UiUtils.launchWebBrowser(getActivity(), "https://github.com/ZYongX09/ABDL-Space-V2")),
				new ListItem<>(R.string.settings_about_blog, 0, (Consumer<ListItem<Void>>) i->UiUtils.launchWebBrowser(getActivity(), "https://zhx-blog.top"), 0, true),
				// === 数据管理 ===
				exportItem=new ListItem<>(R.string.export_settings_title, R.string.export_settings_summary, R.drawable.ic_fluent_arrow_export_24_filled, this::onExportClick, 0, false),
				importItem=new ListItem<>(R.string.import_settings_title, R.string.import_settings_summary, R.drawable.ic_fluent_arrow_import_24_filled, this::onImportClick, 0, false),
				mediaCacheItem=new ListItem<>(R.string.settings_clear_cache, 0, this::onClearMediaCacheClick, 0, true),
				// === 关于 ===
				new ListItem<>(R.string.open_source_licenses, 0, openSourceClick)
		));

		updateMediaCacheItem();
	}

	private void onEggTap(){
		eggTapCount++;
		// Cancel previous toast
		if(eggToast!=null) eggToast.cancel();
		// Vibrate
		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
			VibratorManager vm=getActivity().getSystemService(VibratorManager.class);
			if(vm!=null) vm.getDefaultVibrator().vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
		}else{
			Vibrator v=(Vibrator) getActivity().getSystemService(android.content.Context.VIBRATOR_SERVICE);
			if(v!=null) v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
		}
		// Toast countdown
		int remaining=7-eggTapCount;
		if(remaining>0){
			eggToast=Toast.makeText(getActivity(), "千万不要再点"+remaining+"次", Toast.LENGTH_SHORT);
			eggToast.show();
		}
		// On 7th tap, launch intro animation (bypass splash_shown check)
		if(eggTapCount>=7){
			eggTapCount=0;
			Intent intent=new Intent(getActivity(), org.joinmastodon.android.ui.SplashActivity.class);
			intent.putExtra("from_easter_egg", true);
			startActivity(intent);
		}
	}

	private void openSupportUs(ListItem<?> item){
		new M3AlertDialogBuilder(getActivity())
				.setTitle(R.string.support_us)
				.setMessage(R.string.support_us_message)
				.setPositiveButton("爱发电", (d, w) -> UiUtils.launchWebBrowser(getActivity(), "https://ifdian.net/order/create?user_id=399f44cc508c11f18b7752540025c377"))
				.setNeutralButton("了解更多", (d, w) -> UiUtils.launchWebBrowser(getActivity(), "https://ifdian.net/a/ZYongX"))
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	@Override
	protected void doLoadData(int offset, int count){}

	@Override
	protected RecyclerView.Adapter<?> getAdapter(){
		MergeRecyclerAdapter adapter=new MergeRecyclerAdapter();
		adapter.addAdapter(super.getAdapter());

		// Header: app icon + name + version
		View headerView=getActivity().getLayoutInflater().inflate(R.layout.item_about_header, null);
		ImageView aboutIcon=headerView.findViewById(R.id.about_icon);
		aboutIcon.setImageResource(R.drawable.ic_ntf_logo);
		aboutIcon.setOnClickListener(v->onEggTap());
		((TextView) headerView.findViewById(R.id.about_name)).setText(R.string.app_name);
		((TextView) headerView.findViewById(R.id.about_version)).setText(
				getString(R.string.settings_app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
		adapter.addAdapter(new SingleViewRecyclerAdapter(headerView));

		// Version info at bottom
		TextView versionInfo=new TextView(getActivity());
		versionInfo.setSingleLine();
		versionInfo.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, V.dp(32)));
		versionInfo.setTextAppearance(R.style.m3_label_medium);
		versionInfo.setTextColor(UiUtils.getThemeColor(getActivity(), R.attr.colorM3Outline));
		versionInfo.setGravity(Gravity.CENTER);
		versionInfo.setText(getString(R.string.settings_app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
		versionInfo.setOnClickListener(v->{
			getActivity().getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("", BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")"));
			if(Build.VERSION.SDK_INT<=Build.VERSION_CODES.S_V2){
				new Snackbar.Builder(getActivity())
						.setText(R.string.app_version_copied)
						.show();
			}
		});
		adapter.addAdapter(new SingleViewRecyclerAdapter(versionInfo));

		return adapter;
	}

	// === Export / Import (preserved exactly as-is) ===

	private void onExportClick(ListItem<?> item){
		Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
		intent.setType("application/json");
		intent.putExtra(Intent.EXTRA_TITLE,"abdl-space-exported-settings.json");
		startActivityForResult(intent, EXPORT_RESULT);
	}

	private void onImportClick(ListItem<?> item){
		new M3AlertDialogBuilder(getContext())
				.setTitle(R.string.import_settings_confirm)
				.setIcon(R.drawable.ic_fluent_warning_24_regular)
				.setMessage(R.string.import_settings_confirm_body)
				.setPositiveButton(R.string.ok, (dialogInterface, i) -> {
					Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
					intent.addCategory(Intent.CATEGORY_OPENABLE);
					intent.setType("application/json");
					startActivityForResult(intent, IMPORT_RESULT);
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data){
		if(requestCode==IMPORT_RESULT && resultCode==Activity.RESULT_OK){
			Uri uri=data.getData();
			if(uri==null){
				return;
			}
			try{
				InputStream inputStream=getContext().getContentResolver().openInputStream(uri);
				if(inputStream==null)
					return;
				BufferedReader reader=new BufferedReader(new InputStreamReader(inputStream));
				StringBuilder stringBuilder=new StringBuilder();
				String line;
				while((line=reader.readLine())!=null){
					stringBuilder.append(line);
				}
				inputStream.close();
				String jsonString=stringBuilder.toString();

				Gson gson=new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();

				if(jsonString.isEmpty()){
					throw new IOException();
				}

				JsonObject jsonObject=JsonParser.parseString(jsonString).getAsJsonObject();

				if(!(jsonObject.has("versionName") && jsonObject.has("versionCode") && jsonObject.has("GlobalUserPreferences"))){
					Toast.makeText(getContext(), getContext().getString(R.string.import_settings_failed), Toast.LENGTH_SHORT).show();
					return;
				}
				String versionName=jsonObject.get("versionName").getAsString();
				int versionCode=jsonObject.get("versionCode").getAsInt();
				Log.i(TAG, "onActivityResult: Reading exported settings ("+versionName+" "+versionCode+")");

				Map<String, ?> jsonGlobalPrefs=gson.fromJson(jsonObject.getAsJsonObject("GlobalUserPreferences"), Map.class);
				SharedPreferences.Editor globalPrefsEditor=GlobalUserPreferences.getPrefs().edit();
				for(String key : jsonGlobalPrefs.keySet()){
					Object value=jsonGlobalPrefs.get(key);
					if(value==null)
						continue;
					savePrefValue(globalPrefsEditor, key, value);
				}

				for(AccountSession accountSession : AccountSessionManager.getInstance().getLoggedInAccounts()){
					if(!jsonObject.has(accountSession.self.id))
						continue;
					Map<String, ?> prefs=gson.fromJson(jsonObject.getAsJsonObject(accountSession.self.id), Map.class);

					SharedPreferences.Editor prefEditor=accountSession.getRawLocalPreferences().edit();
					for(String key : prefs.keySet()){
						Object value=prefs.get(key);
						if(value==null)
							continue;
						savePrefValue(prefEditor, key, value);
					}
				}

				PackageManager packageManager=getContext().getPackageManager();
				Intent intent=packageManager.getLaunchIntentForPackage(getContext().getPackageName());
				ComponentName componentName=intent.getComponent();
				Intent mainIntent=Intent.makeRestartActivityTask(componentName);
				mainIntent.setPackage(getContext().getPackageName());
				getContext().startActivity(mainIntent);
				Runtime.getRuntime().exit(0);
			}catch(IOException e){
				Log.w(TAG, e);
				Toast.makeText(getContext(), getContext().getString(R.string.import_settings_failed), Toast.LENGTH_SHORT).show();
			}
		}

		if(requestCode==EXPORT_RESULT && resultCode==Activity.RESULT_OK){
			try{
				Gson gson=new Gson();
				JsonObject jsonObject=new JsonObject();
				jsonObject.addProperty("versionName", BuildConfig.VERSION_NAME);
				jsonObject.addProperty("versionCode", BuildConfig.VERSION_CODE);

				JsonElement je=gson.toJsonTree(GlobalUserPreferences.getPrefs().getAll());
				jsonObject.add("GlobalUserPreferences", je);

				for(AccountSession accountSession : AccountSessionManager.getInstance().getLoggedInAccounts()){
					Map<String, ?> prefs=accountSession.getRawLocalPreferences().getAll();
					JsonElement accountPrefs=gson.toJsonTree(prefs);
					jsonObject.add(accountSession.self.id, accountPrefs);
				}

				File file=new File(getContext().getCacheDir(), "abdl-space-exported-settings.json");
				FileWriter writer=new FileWriter(file);
				writer.write(jsonObject.toString());
				writer.flush();
				writer.close();

				InputStream is=new FileInputStream(file);
				OutputStream os=getContext().getContentResolver().openOutputStream(data.getData());

				byte[] buffer=new byte[1024];
				int length;
				while((length=is.read(buffer))>0){
					os.write(buffer, 0, length);
				}
			}catch(IOException e){
				Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_SHORT);
			}
		}
	}

	// === Clear Media Cache (preserved exactly as-is) ===

	private void savePrefValue(SharedPreferences.Editor editor, String key, Object value) {
		if(value.getClass().equals(Boolean.class))
			editor.putBoolean(key, (Boolean) value);
		else if(value.getClass().equals(Long.class))
			editor.putInt(key, ((Long) value).intValue());
		else if(value.getClass().equals(Double.class))
			editor.putFloat(key, ((Double) value).floatValue());
		else
			editor.putString(key, String.valueOf(value));
		editor.commit();
	}

	private void onClearMediaCacheClick(ListItem<?> item){
		MastodonAPIController.runInBackground(()->{
			Activity activity=getActivity();
			ImageCache.getInstance(getActivity()).clear();
			activity.runOnUiThread(()->{
				Toast.makeText(activity, R.string.media_cache_cleared, Toast.LENGTH_SHORT).show();
				updateMediaCacheItem();
			});
		});
	}

	private void updateMediaCacheItem(){
		DiskLruCache cache=ImageCache.getInstance(getActivity()).getDiskCache();
		long size=cache==null ? 0 : cache.size();
		mediaCacheItem.subtitle=UiUtils.formatFileSize(getActivity(), size, false);
		mediaCacheItem.isEnabled=size>0;
		rebindItem(mediaCacheItem);
	}
}
