package org.joinmastodon.android.sponsors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.model.sponsors.SponsorModels.Plan;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class SponsorPurchaseSafety{
	private SponsorPurchaseSafety(){}
	/** A refresh is not consent. Changed config always requires new reading and another click. */
	public static boolean canOpen(String readFingerprint, String verifiedFingerprint, boolean readComplete){
		return readComplete && readFingerprint!=null && readFingerprint.equals(verifiedFingerprint);
	}
	/** Fixed official checkout origin; no arbitrary schemes, nested redirect parameters or changed SKU. */
	public static boolean valid(Plan plan){
		try{
			if(plan==null || !plan.enabled || plan.purchaseUrl==null) return false;
			URI uri=new URI(plan.purchaseUrl);
			if(!"https".equals(uri.getScheme()) || !"ifdian.net".equalsIgnoreCase(uri.getHost()) || uri.getUserInfo()!=null
					|| uri.getPort()!=-1 && uri.getPort()!=443 || uri.getFragment()!=null || !"/order/create".equals(uri.getPath())) return false;
			Map<String, String> query=new HashMap<>();
			for(String pair:uri.getRawQuery().split("&")){
				String[] parts=pair.split("=", 2);
				if(parts.length!=2) return false;
				String key=URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name());
				if(query.put(key, URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()))!=null) return false;
			}
			if(!query.keySet().equals(java.util.Set.of("product_type", "plan_id", "sku", "viokrz_ex"))
					|| !"1".equals(query.get("product_type")) || !"0".equals(query.get("viokrz_ex"))
					|| plan.afdianPlanId==null || !plan.afdianPlanId.equals(query.get("plan_id"))) return false;
			JsonArray sku=JsonParser.parseString(query.get("sku")).getAsJsonArray();
			if(sku.size()!=1) return false;
			JsonObject item=sku.get(0).getAsJsonObject();
			return item.keySet().equals(java.util.Set.of("sku_id", "count")) && item.get("count").isJsonPrimitive()
					&& item.getAsJsonPrimitive("count").isNumber() && "1".equals(item.get("count").toString())
					&& item.get("sku_id").isJsonPrimitive() && item.getAsJsonPrimitive("sku_id").isString()
					&& plan.afdianSkuId!=null && plan.afdianSkuId.equals(item.get("sku_id").getAsString());
		}catch(Exception invalid){ return false; }
	}
}
