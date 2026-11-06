package org.joinmastodon.android.sponsors;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import org.joinmastodon.android.model.BaseModel;
import org.joinmastodon.android.model.sponsors.SponsorModels.*;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels.*;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded strict wire reader, followed by the unchanged shared model validators. No API globals. */
final class SandboxProtocol{
	static final String PLAN="sandbox-cent";
	static final int VERSION=1, BODY_LIMIT=98304, PAIR_LIMIT=4096;
	static final Gson JSON=new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
	static final class Pair{
		int schemaVersion;
		String environment, origin, runId, userId, accessToken;
		long expiresAt;
	}
	private SandboxProtocol(){}
	static String requireOrigin(String value){
		if(value==null || !value.matches("https://[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.trycloudflare\\.com"))
			throw new IllegalArgumentException("Invalid sandbox origin");
		return value;
	}
	static boolean validRun(String value){
		try{ return value!=null && UUID.fromString(value).toString().equals(value); }
		catch(IllegalArgumentException invalid){ return false; }
	}
	static boolean validCode(String code){ return code!=null && code.matches("[A-Za-z0-9_-]{16,32}"); }
	// Server TTL is one hour; tolerate at most 120s of phone clock lag. Never rewrite expiry.
	static void validatePair(Pair value, String origin, long now) throws IOException{
		if(value==null || value.schemaVersion!=1 || !"sandbox".equals(value.environment) || !origin.equals(value.origin)
				|| !validRun(value.runId) || !"101".equals(value.userId) || value.accessToken==null
				|| !value.accessToken.matches("[A-Za-z0-9_-]{43}") || value.expiresAt<=now || value.expiresAt>now+3720)
			throw invalid();
	}
	static Pair pair(byte[] bytes, String origin, long now) throws IOException{
		JsonObject object=object(bytes, PAIR_LIMIT);
		if(!object.keySet().equals(Set.of("schema_version", "environment", "origin", "run_id", "user_id", "access_token", "expires_at"))) throw invalid();
		// Pair has private/package fields, so explicitly enforce the exact primitive types here.
		for(String key:Set.of("environment", "origin", "run_id", "user_id", "access_token")) check(object.get(key), String.class);
		check(object.get("schema_version"), int.class); check(object.get("expires_at"), long.class);
		Pair pair=JSON.fromJson(object, Pair.class); validatePair(pair, origin, now); return pair;
	}
	static <T extends BaseModel> T model(byte[] bytes, Class<T> type, boolean create) throws IOException{
		JsonObject object=object(bytes, BODY_LIMIT);
		check(object, type);
		T model=JSON.fromJson(object, type);
		model.postprocess();
		if(model instanceof Catalog catalog){
			if(catalog.plans.size()!=1) throw invalid();
			plan(catalog.plans.get(0));
		}else if(model instanceof Me me){
			membership(me);
		}else if(model instanceof Options options){
			plan(options.plan);
			if(!Boolean.FALSE.equals(options.channel("afdian").available)) throw invalid();
			if(options.activeOrder!=null) order(options.activeOrder, false);
		}else if(model instanceof CurrentOrder current){
			if(current.order!=null) order(current.order, false);
		}else if(model instanceof Order order){
			order(order, create);
		}else throw invalid();
		return model;
	}
	private static void plan(Plan plan) throws IOException{
		if(!SponsorPaymentModels.validPlan(plan) || !PLAN.equals(plan.id) || plan.version!=VERSION || plan.priceMinor!=1
				|| !"day".equals(plan.durationUnit) || plan.durationCount!=1
				|| plan.name==null || !(plan.name.contains("沙箱") || plan.name.toLowerCase(java.util.Locale.ROOT).contains("sandbox"))
				|| nonempty(plan.purchaseUrl) || nonempty(plan.afdianPlanId) || nonempty(plan.afdianSkuId)) throw invalid();
	}
	private static void membership(Me me) throws IOException{
		if(me.sponsor.permanent) throw invalid();
	}
	private static void order(Order order, boolean create) throws IOException{
		if(!PLAN.equals(order.planId) || order.planVersion!=VERSION || order.amountMinor!=1 || !"CNY".equals(order.currency)
				|| !create && order.orderStr!=null) { order.orderStr=null; throw invalid(); }
		if(order.me!=null) membership(order.me);
	}
	private static boolean nonempty(String value){ return value!=null && !value.isEmpty(); }
	static String error(byte[] bytes){
		try{
			JsonElement code=object(bytes, PAIR_LIMIT).get("code");
			if(code!=null && code.isJsonPrimitive() && code.getAsJsonPrimitive().isString() && code.getAsString().matches("[a-z][a-z0-9_]{0,79}")) return code.getAsString();
		}catch(Exception ignored){}
		return "request_failed";
	}
	static JsonObject object(byte[] bytes, int limit) throws IOException{
		if(bytes.length>limit) throw invalid();
		String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		try(JsonReader reader=new JsonReader(new StringReader(text))){
			reader.setLenient(false);
			JsonElement value=read(reader, 0);
			if(!value.isJsonObject() || reader.peek()!=JsonToken.END_DOCUMENT) throw invalid();
			return value.getAsJsonObject();
		}catch(IllegalStateException | NumberFormatException invalid){ throw invalid(); }
	}
	private static JsonElement read(JsonReader reader, int depth) throws IOException{
		if(depth>24) throw invalid();
		return switch(reader.peek()){
			case BEGIN_OBJECT -> {
				JsonObject object=new JsonObject(); reader.beginObject();
				while(reader.hasNext()){
					String name=reader.nextName();
					if(object.has(name) || object.size()>=128) throw invalid();
					object.add(name, read(reader, depth+1));
				}
				reader.endObject(); yield object;
			}
			case BEGIN_ARRAY -> {
				JsonArray array=new JsonArray(); reader.beginArray();
				while(reader.hasNext()){ if(array.size()>=200) throw invalid(); array.add(read(reader, depth+1)); }
				reader.endArray(); yield array;
			}
			case STRING -> new JsonPrimitive(reader.nextString());
			case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
			case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
			case NUMBER -> {
				String text=reader.nextString();
				if(!text.matches("-?(0|[1-9][0-9]*)")) throw invalid();
				long value=Long.parseLong(text);
				if(value < -SponsorPaymentModels.MAX_SAFE_INTEGER || value>SponsorPaymentModels.MAX_SAFE_INTEGER) throw invalid();
				yield new JsonPrimitive(value);
			}
			default -> throw invalid();
		};
	}
	/** Gson normally coerces strings/numbers/booleans and accepts missing primitives; forbid that. */
	private static void check(JsonElement value, Type type) throws IOException{
		if(value==null || value.isJsonNull()){
			if(type instanceof Class<?> clazz && clazz.isPrimitive()) throw invalid();
			return;
		}
		if(type instanceof ParameterizedType list){
			if(!value.isJsonArray()) throw invalid();
			for(JsonElement item:value.getAsJsonArray()) check(item, list.getActualTypeArguments()[0]);
			return;
		}
		if(!(type instanceof Class<?> clazz)) throw invalid();
		if(clazz==String.class){ if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(); return; }
		if(clazz==boolean.class || clazz==Boolean.class){ if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw invalid(); return; }
		if(clazz==int.class || clazz==long.class || clazz==Long.class || clazz==Integer.class){
			if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid();
			long number=value.getAsLong();
			if((clazz==int.class || clazz==Integer.class) && (number<Integer.MIN_VALUE || number>Integer.MAX_VALUE)) throw invalid();
			return;
		}
		if(!value.isJsonObject()) throw invalid();
		JsonObject object=value.getAsJsonObject(); Map<String, Field> fields=new HashMap<>();
		for(Field field:clazz.getFields()) if(!Modifier.isStatic(field.getModifiers())) fields.put(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES.translateName(field), field);
		for(String name:object.keySet()){
			Field field=fields.get(name); if(field==null) throw invalid();
			check(object.get(name), field.getGenericType());
		}
		for(var entry:fields.entrySet()) if(entry.getValue().getType().isPrimitive() && !object.has(entry.getKey())) throw invalid();
	}
	private static IOException invalid(){ return new IOException("Invalid sandbox response"); }
}
