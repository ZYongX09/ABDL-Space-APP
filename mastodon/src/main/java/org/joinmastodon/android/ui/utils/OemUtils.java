package org.joinmastodon.android.ui.utils;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.joinmastodon.android.MastodonApp;

import java.lang.reflect.Method;

public class OemUtils {
	private static final String TAG = "OemUtils";

	public enum Vendor {
		MIUI("小米"),
		HARMONY("华为"),
		HONOR("荣耀"),
		COLOROS("OPPO/一加/realme"),
		FUNTOUCHOS("vivo/iQOO"),
		ONEUI("三星"),
		OTHER("其他");

		public final String displayName;
		Vendor(String displayName) { this.displayName = displayName; }
	}

	private static Vendor cachedVendor;

	public static Vendor detectVendor() {
		if (cachedVendor != null) return cachedVendor;
		String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase() : "";
		String brand = Build.BRAND != null ? Build.BRAND.toLowerCase() : "";
		// MIUI: check system prop first, then manufacturer
		String miuiVer = getSystemProp("ro.miui.ui.version.name");
		if (!TextUtils.isEmpty(miuiVer) || "xiaomi".equals(manufacturer) || "xiaomi".equals(brand) || "redmi".equals(brand)) {
			cachedVendor = Vendor.MIUI;
		} else if ("huawei".equals(manufacturer) || "huawei".equals(brand)) {
			cachedVendor = Vendor.HARMONY;
		} else if ("honor".equals(manufacturer) || "honor".equals(brand)) {
			cachedVendor = Vendor.HONOR;
		} else if ("oppo".equals(manufacturer) || "oneplus".equals(manufacturer) || "realme".equals(manufacturer)
				|| "oppo".equals(brand) || "oneplus".equals(brand) || "realme".equals(brand)) {
			cachedVendor = Vendor.COLOROS;
		} else if ("vivo".equals(manufacturer) || "iqoo".equals(manufacturer) || "vivo".equals(brand) || "iqoo".equals(brand)) {
			cachedVendor = Vendor.FUNTOUCHOS;
		} else if (isOneUI()) {
			cachedVendor = Vendor.ONEUI;
		} else {
			cachedVendor = Vendor.OTHER;
		}
		return cachedVendor;
	}

	public static boolean isAutoStartGranted() {
		if (detectVendor() != Vendor.MIUI) return false;
		try {
			AppOpsManager mgr = (AppOpsManager) MastodonApp.context.getSystemService(Context.APP_OPS_SERVICE);
			Method m = AppOpsManager.class.getMethod("checkOpNoThrow", int.class, int.class, String.class);
			int result = (int) m.invoke(mgr, 10008, android.os.Process.myUid(), MastodonApp.context.getPackageName());
			return result == AppOpsManager.MODE_ALLOWED;
		} catch (Exception e) {
			Log.e(TAG, "isAutoStartGranted failed", e);
			return false;
		}
	}

	public static Intent getAutostartIntent(Context ctx) {
		Vendor v = detectVendor();
		try {
			switch (v) {
				case MIUI: {
					Intent i = new Intent("miui.intent.action.APP_PERM_EDITOR");
					i.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity");
					i.putExtra("extra_pkgname", ctx.getPackageName());
					return i;
				}
				case COLOROS: {
					Intent i = new Intent();
					i.setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity");
					return i;
				}
				case FUNTOUCHOS: {
					Intent i = new Intent();
					i.setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity");
					return i;
				}
				default:
					return getAppSettingsIntent(ctx);
			}
		} catch (Exception e) {
			Log.e(TAG, "getAutostartIntent failed", e);
			return getAppSettingsIntent(ctx);
		}
	}

	public static Intent getBatteryIntent(Context ctx) {
		try {
			return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
					Uri.parse("package:" + ctx.getPackageName()));
		} catch (Exception e) {
			return getAppSettingsIntent(ctx);
		}
	}

	public static Intent getAppSettingsIntent(Context ctx) {
		return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
				Uri.parse("package:" + ctx.getPackageName()));
	}

	private static boolean isOneUI() {
		try {
			java.lang.reflect.Field f = Build.VERSION.class.getDeclaredField("SEM_PLATFORM_INT");
			f.setAccessible(true);
			return (int) f.get(null) >= 100000;
		} catch (Exception e) {
			return false;
		}
	}

	private static String getSystemProp(String key) {
		try {
			Class<?> c = Class.forName("android.os.SystemProperties");
			return (String) c.getMethod("get", String.class).invoke(null, key);
		} catch (Exception e) {
			return null;
		}
	}
}
