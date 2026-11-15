package org.joinmastodon.android.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Type;
import java.math.BigDecimal;

/** Optional public projection only. Never contains the administrative archive or asset URLs. */
public final class StatusModeration{
	public static final String PREFIX="根据互联网用户公众账号信息服务管理规定，此内容由于 ";
	public static final String SUFFIX=" 已被屏蔽";
	@SerializedName("schema_version") public final int schemaVersion=1;
	public final String state="blocked";
	public final String reason;
	public final String notice;

	private StatusModeration(String reason, String notice){ this.reason=reason; this.notice=notice; }

	public boolean isSupported(){
		return schemaVersion==1 && "blocked".equals(state) && validReason(reason) && noticeFor(reason).equals(notice);
	}
	public static String noticeFor(String reason){ return PREFIX+reason+SUFFIX; }
	public static boolean validReason(String reason){
		return reason!=null && !reason.isBlank() && reason.indexOf('\0')<0 && reason.codePointCount(0, reason.length())<=500;
	}

	/** Field adapter deliberately returns null, not a parse error that would discard a Status/list. */
	public static final class Deserializer implements JsonDeserializer<StatusModeration>{
		@Override public StatusModeration deserialize(JsonElement value, Type type, JsonDeserializationContext context){
			try{
				if(value==null || !value.isJsonObject()) return null;
				JsonObject object=value.getAsJsonObject();
				JsonElement version=object.get("schema_version");
				if(version==null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
						|| version.getAsBigDecimal().compareTo(BigDecimal.ONE)!=0) return null;
				for(String key:new String[]{"state", "reason", "notice"}){
					JsonElement field=object.get(key);
					if(field==null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) return null;
				}
				if(!"blocked".equals(object.get("state").getAsString())) return null;
				StatusModeration result=new StatusModeration(object.get("reason").getAsString(), object.get("notice").getAsString());
				return result.isSupported() ? result : null;
			}catch(RuntimeException malformedOptional){ return null; }
		}
	}
}
