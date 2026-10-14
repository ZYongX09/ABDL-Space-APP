package org.joinmastodon.android.ui.map;

import android.content.Context;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.joinmastodon.android.BuildConfig;

public final class MapProviderRegistry{
	private MapProviderRegistry(){}

	public static List<String> available(){
		return available(BuildConfig.BAIDU_MAP_CONFIGURED,BuildConfig.AMAP_CONFIGURED,Build.SUPPORTED_ABIS);
	}
	public static List<String> available(boolean baiduConfigured,boolean amapConfigured,String[] abis){
		List<String> result=new ArrayList<>();
		boolean arm=false;
		for(String abi:abis) if("arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi)) arm=true;
		if(baiduConfigured) result.add("baidu");
		if(amapConfigured && arm) result.add("amap");
		return result;
	}

	public static String next(List<String> available,String preferred,Set<String> attempted){
		if(available.contains(preferred) && !attempted.contains(preferred)) return preferred;
		for(String id:available) if(!attempted.contains(id)) return id;
		return null;
	}

	public static MapProvider create(Context context,String id){
		return switch(id){
			case "baidu" -> new BaiduMapProvider(context);
			case "amap" -> new AMapProvider(context);
			default -> throw new IllegalArgumentException("Unknown map provider");
		};
	}
}
