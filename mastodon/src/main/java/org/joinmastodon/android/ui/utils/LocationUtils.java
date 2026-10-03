package org.joinmastodon.android.ui.utils;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.joinmastodon.android.api.requests.geo.GetIPProvince;
import org.joinmastodon.android.api.session.AccountSessionManager;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 位置信息工具 — 使用系统 LocationManager 获取定位、天地图逆地理编码解析省/市/区。
 * 不依赖 Google Play Services 或 Android Geocoder（适配无 GMS 设备）。
 * 无定位权限时通过百度 IP 定位 API 直连获取省/市（纯前端，不经后端中转）。
 * 位置缓存在 SharedPreferences 中供发帖使用。
 */
public class LocationUtils{
	private static final String TAG="LocationUtils";
	private static final String TIANDITU_KEY="791612dca05be9d878cc91b2f5ea7428";
	private static final String PREFS="location_cache";
	private static final String KEY_PROVINCE="province";
	private static final String KEY_CITY="city";
	private static final String KEY_DISTRICT="district";
	private static final String KEY_LAT="lat";
	private static final String KEY_LNG="lng";
	private static final String KEY_LAST_UPDATE="last_update";
	private static final long STALE_MS=24*60*60*1000L; // 24 小时过期

	private static final ExecutorService geocodeExecutor=Executors.newSingleThreadExecutor();
	private static final OkHttpClient httpClient=new OkHttpClient();
	private static final Gson gson=new Gson();

	public record ResolvedLocation(String province, @Nullable String city, @Nullable String district){}

	public static boolean hasLocationPermission(Context context){
		return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
				|| ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
	}

	/**
	 * 尝试获取一次位置并解析省市区，结果缓存到 SharedPreferences。
	 * 异步执行，完成后回调 onResolved（可能传 null 如果获取失败）。
	 */
	@SuppressLint("MissingPermission")
	public static void fetchAndResolve(@NonNull Context context, @Nullable OnLocationResolved callback){
		android.util.Log.e(TAG, "fetchAndResolve called, hasPermission="+hasLocationPermission(context));
		if(!hasLocationPermission(context)){
			if(callback!=null) callback.onResolved(null);
			return;
		}
		LocationManager lm=(LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
		if(lm==null){
			android.util.Log.e(TAG, "LocationManager is null");
			if(callback!=null) callback.onResolved(null);
			return;
		}
		// Try last known location from all providers
		Location best=null;
		for(String provider : lm.getProviders(true)){
			Location loc=lm.getLastKnownLocation(provider);
			android.util.Log.e(TAG, "provider="+provider+" lastKnown="+
					(loc==null ? "null" : loc.getLatitude()+","+loc.getLongitude()));
			if(loc!=null && (best==null || loc.getAccuracy()<best.getAccuracy()))
				best=loc;
		}
		if(best!=null){
			android.util.Log.e(TAG, "Using last known location: "+best.getLatitude()+","+best.getLongitude());
			resolveAndCache(context, best, callback);
			return;
		}
		// Request a fresh location from the best available provider
		String provider=lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER
				: lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : null;
		if(provider==null){
			android.util.Log.e(TAG, "No enabled location provider");
			if(callback!=null) callback.onResolved(null);
			return;
		}
		android.util.Log.e(TAG, "Requesting single update from "+provider);
		lm.requestSingleUpdate(provider, location->resolveAndCache(context, location, callback), Looper.getMainLooper());
	}

	private static void resolveAndCache(@NonNull Context context, @NonNull Location location, @Nullable OnLocationResolved callback){
		geocodeExecutor.execute(()->{
			ResolvedLocation resolved=reverseGeocodeTianditu(location.getLatitude(), location.getLongitude());
			if(resolved!=null){
				cacheLocation(context, resolved, location);
			}
			if(callback!=null){
				new android.os.Handler(Looper.getMainLooper()).post(()->callback.onResolved(resolved));
			}
		});
	}

	/** 天地图逆地理编码 API — 不依赖 GMS/Geocoder
	 *  文档: http://lbs.tianditu.gov.cn/server/geocoding.html
	 *  响应: { status: "0", result: { formatted_address, addressComponent: { province, city, county, ... } } }
	 *  注意: 文档表格未列 province/county，但实际返回包含这两个字段
	 */
	@Nullable
	private static ResolvedLocation reverseGeocodeTianditu(double lat, double lng){
		try{
			String postStr="{\"lon\":"+lng+",\"lat\":"+lat+",\"ver\":1}";
			String url="https://api.tianditu.gov.cn/geocoder?postStr="+java.net.URLEncoder.encode(postStr, "UTF-8")
					+"&type=geocode&tk="+TIANDITU_KEY;
			Request request=new Request.Builder().url(url).get().build();
			Response response=httpClient.newCall(request).execute();
			if(!response.isSuccessful() || response.body()==null){
				Log.w(TAG, "Tianditu geocode HTTP "+response.code());
				return null;
			}
			String body=response.body().string();
			JsonObject root=gson.fromJson(body, JsonObject.class);
			if(root==null) return null;
			// 校验 status: "0"=正确, "1"=错误, "404"=出错
			String status=root.has("status") && !root.get("status").isJsonNull()
					? root.get("status").getAsString() : "1";
			if(!"0".equals(status)){
				Log.w(TAG, "Tianditu geocode status="+status+" msg="+(root.has("msg") ? root.get("msg").getAsString() : ""));
				return null;
			}
			if(!root.has("result")) return null;
			JsonObject result=root.getAsJsonObject("result");
			if(!result.has("addressComponent")) return null;
			JsonObject comp=result.getAsJsonObject("addressComponent");
			String province=comp.has("province") && !comp.get("province").isJsonNull()
					? comp.get("province").getAsString() : null;
			String city=comp.has("city") && !comp.get("city").isJsonNull()
					? comp.get("city").getAsString() : null;
			String district=comp.has("county") && !comp.get("county").isJsonNull()
					? comp.get("county").getAsString() : null;
			if(TextUtils.isEmpty(province)){
				// 兜底：从 formatted_address 提取省份
				String formatted=result.has("formatted_address") && !result.get("formatted_address").isJsonNull()
						? result.get("formatted_address").getAsString() : null;
				if(formatted!=null) province=extractProvince(formatted);
			}
			if(TextUtils.isEmpty(province)) return null;
			// 天地图对直辖市返回 province="北京市", city="北京市" — 去重
			if(city!=null && city.equals(province)) city=province;
			return new ResolvedLocation(province, city, district);
		}catch(Exception e){
			Log.w(TAG, "Tianditu reverse geocode failed", e);
			return null;
		}
	}

	/** 从 formatted_address 开头提取省份名（如"北京市西城区..." → "北京市"） */
	@Nullable
	private static String extractProvince(String address){
		if(TextUtils.isEmpty(address)) return null;
		String[] suffixes={"省", "市", "自治区"};
		// 优先匹配已知省级行政区
		for(String p : new String[]{"北京市","天津市","上海市","重庆市","河北省","山西省","辽宁省","吉林省","黑龙江省","江苏省","浙江省","安徽省","福建省","江西省","山东省","河南省","湖北省","湖南省","广东省","海南省","四川省","贵州省","云南省","陕西省","甘肃省","青海省","内蒙古自治区","广西壮族自治区","西藏自治区","宁夏回族自治区","新疆维吾尔自治区","香港特别行政区","澳门特别行政区","台湾省"}){
			if(address.startsWith(p)) return p;
		}
		// 兜底：截取到第一个省/自治区后缀
		for(String suffix : suffixes){
			int idx=address.indexOf(suffix);
			if(idx>=0 && idx<15) return address.substring(0, idx+suffix.length());
		}
		return null;
	}

	private static void cacheLocation(@NonNull Context context, @NonNull ResolvedLocation loc, @NonNull Location raw){
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
				.putString(KEY_PROVINCE, loc.province())
				.putString(KEY_CITY, loc.city())
				.putString(KEY_DISTRICT, loc.district())
				.putFloat(KEY_LAT, (float) raw.getLatitude())
				.putFloat(KEY_LNG, (float) raw.getLongitude())
				.putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
				.apply();
	}

	/** 读缓存的位置（6 小时内有效） */
	@Nullable
	public static ResolvedLocation getCachedLocation(@NonNull Context context){
		SharedPreferences prefs=context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		long lastUpdate=prefs.getLong(KEY_LAST_UPDATE, 0);
		if(System.currentTimeMillis()-lastUpdate>STALE_MS) return null;
		String province=prefs.getString(KEY_PROVINCE, null);
		if(TextUtils.isEmpty(province)) return null;
		return new ResolvedLocation(
				province,
				prefs.getString(KEY_CITY, null),
				prefs.getString(KEY_DISTRICT, null)
		);
	}

	/** 强制获取缓存（忽略过期）— 用于发帖时降级 */
	@Nullable
	public static ResolvedLocation getCachedLocationForce(@NonNull Context context){
		SharedPreferences prefs=context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		String province=prefs.getString(KEY_PROVINCE, null);
		if(TextUtils.isEmpty(province)) return null;
		return new ResolvedLocation(
				province,
				prefs.getString(KEY_CITY, null),
				prefs.getString(KEY_DISTRICT, null)
		);
	}

	/** 获取缓存的省份名（用于时间线标签，可为空） */
	@Nullable
	public static String getCachedProvince(@NonNull Context context){
		ResolvedLocation loc=getCachedLocationForce(context);
		return loc==null ? null : loc.province();
	}

	/**
	 * 无定位权限时，通过后端 IP 属地接口获取省/市。
	 * 后端走百度地图普通IP定位（SN 校验），避免泄露 AK/SK。
	 * 异步执行，完成后回调 onResolved（可能传 null）。
	 */
	public static void fetchProvinceFromIP(@NonNull Context context, @Nullable OnProvinceResolved callback){
		try{
			AccountSessionManager mgr=AccountSessionManager.getInstance();
			if(mgr==null || mgr.getLastActiveAccount()==null){
				android.util.Log.e(TAG, "fetchProvinceFromIP: no active account");
				if(callback!=null) callback.onResolved(null);
				return;
			}
			String accountID=mgr.getLastActiveAccount().getID();
			android.util.Log.e(TAG, "fetchProvinceFromIP: exec with accountID="+accountID);
			new GetIPProvince()
					.setCallback(new me.grishka.appkit.api.Callback<>(){
						@Override
					public void onSuccess(GetIPProvince.Response result){
						android.util.Log.e(TAG, "fetchProvinceFromIP onSuccess: province="+
								(result==null ? "null" : result.province)+" city="+
								(result==null ? "null" : result.city));
						if(result!=null && result.province!=null){
							android.content.SharedPreferences prefs=context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
							// IP 属地不覆盖 GPS 精确定位（已有 district 说明 GPS 成功过）
							if(prefs.getString(KEY_DISTRICT, null)==null){
								prefs.edit()
										.putString(KEY_PROVINCE, result.province)
										.putString(KEY_CITY, result.city)
										.putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
										.apply();
							}
						}
						if(callback!=null) callback.onResolved(result!=null ? result.province : null);
					}

						@Override
						public void onError(me.grishka.appkit.api.ErrorResponse error){
							android.util.Log.e(TAG, "fetchProvinceFromIP onError: "+error.getClass().getSimpleName()+" "+error);
							if(callback!=null) callback.onResolved(null);
						}
					})
					.exec(accountID);
		}catch(Exception e){
			android.util.Log.e(TAG, "fetchProvinceFromIP failed", e);
			if(callback!=null) callback.onResolved(null);
		}
	}

	public interface OnProvinceResolved{
		void onResolved(@Nullable String province);
	}

	public interface OnLocationResolved{
		void onResolved(@Nullable ResolvedLocation location);
	}
}
