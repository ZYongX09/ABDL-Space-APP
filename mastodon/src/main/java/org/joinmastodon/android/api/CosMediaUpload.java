package org.joinmastodon.android.api;

import com.google.gson.JsonObject;

import org.joinmastodon.android.model.Attachment;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class CosMediaUpload{
	private CosMediaUpload(){}
	private static final MediaType JSON=MediaType.get("application/json; charset=utf-8");

	public static boolean isValidAuthorization(CosUploadAuthorization authorization){
		return authorization!=null
				&& authorization.uploadId!=null
				&& authorization.uploadUrl!=null
				&& authorization.publicUrl!=null
				&& authorization.requiredHeaders!=null
				&& authorization.requiredHeaders.containsKey("Authorization")
				&& authorization.requiredHeaders.containsKey("Content-Type")
				&& "true".equals(authorization.requiredHeaders.get("x-cos-forbid-overwrite"));
	}

	public static Map<String, String> requiredHeaders(CosUploadAuthorization authorization){
		if(!isValidAuthorization(authorization))
			throw new IllegalArgumentException("Invalid COS upload authorization");
		return new LinkedHashMap<>(authorization.requiredHeaders);
	}

	public static CosUploadAuthorization authorize(OkHttpClient client, String domain, String token, String purpose, RequestBody body, int width, int height, CallListener listener) throws IOException{
		JsonObject json=new JsonObject();
		json.addProperty("purpose", purpose);
		json.addProperty("mimeType", body.contentType().toString());
		json.addProperty("declaredSize", body.contentLength());
		json.addProperty("width", width);
		json.addProperty("height", height);
		Request request=new Request.Builder()
				.url("https://"+domain+"/api/v1/uploads/authorize")
				.header("Authorization", "Bearer "+token)
				.post(RequestBody.create(JSON, MastodonAPIController.gsonWithoutDeserializer.toJson(json)))
				.build();
		try(Response response=execute(client, request, listener)){
			CosUploadAuthorization authorization=parse(response, CosUploadAuthorization.class);
			if(!isValidAuthorization(authorization))
				throw new IOException("Invalid COS authorization response");
			return authorization;
		}
	}

	public static void put(OkHttpClient client, CosUploadAuthorization authorization, RequestBody body, CallListener listener) throws IOException{
		Request.Builder builder=new Request.Builder().url(authorization.uploadUrl).put(body);
		for(Map.Entry<String, String> header:requiredHeaders(authorization).entrySet())
			builder.header(header.getKey(), header.getValue());
		try(Response response=execute(client, builder.build(), listener)){
			if(!response.isSuccessful())
				throw new IOException("COS PUT failed: "+response.code());
		}
	}

	public static Attachment complete(OkHttpClient client, String domain, String token, String uploadId, String previewUploadId, CallListener listener) throws IOException{
		JsonObject json=new JsonObject();
		if(previewUploadId!=null)
			json.addProperty("preview_upload_id", previewUploadId);
		Request request=new Request.Builder()
				.url("https://"+domain+"/api/v1/uploads/"+uploadId+"/complete")
				.header("Authorization", "Bearer "+token)
				.post(RequestBody.create(JSON, MastodonAPIController.gsonWithoutDeserializer.toJson(json)))
				.build();
		try(Response response=execute(client, request, listener)){
			return parse(response, Attachment.class);
		}
	}

	private static Response execute(OkHttpClient client, Request request, CallListener listener) throws IOException{
		Call call=client.newCall(request);
		listener.onCall(call);
		Response response=call.execute();
		if(!response.isSuccessful()){
			String message=response.message();
			try(ResponseBody body=response.body()){
				if(body!=null){
					JsonObject error=MastodonAPIController.gsonWithoutDeserializer.fromJson(body.charStream(), JsonObject.class);
					if(error!=null && error.has("error"))
						message=error.get("error").getAsString();
				}
			}
			response.close();
			throw new IOException(message+" (HTTP "+response.code()+")");
		}
		return response;
	}

	private static <T> T parse(Response response, Class<T> type) throws IOException{
		try(ResponseBody body=response.body()){
			if(body==null)
				throw new IOException("Empty server response");
			T result=MastodonAPIController.gson.fromJson(body.charStream(), type);
			if(result==null)
				throw new IOException("Invalid server response");
			return result;
		}
	}

	public interface CallListener{
		void onCall(Call call);
	}

	public static class Progress{
		private final long originalTotal;
		private final long previewTotal;
		private long originalTransferred;
		private long previewTransferred;
		private int lastPercent;

		public Progress(long originalTotal, long previewTotal){
			if(originalTotal<=0 || previewTotal<0)
				throw new IllegalArgumentException("Invalid upload size");
			this.originalTotal=originalTotal;
			this.previewTotal=previewTotal;
		}

		public int updateOriginal(long transferred){
			originalTransferred=clamp(transferred, originalTotal);
			return percent();
		}

		public int updatePreview(long transferred){
			previewTransferred=clamp(transferred, previewTotal);
			return percent();
		}

		private int percent(){
			long total=originalTotal+previewTotal;
			int current=(int)Math.min(100, Math.round((originalTransferred+previewTransferred)*100d/total));
			lastPercent=Math.max(lastPercent, current);
			return lastPercent;
		}

		private static long clamp(long value, long maximum){
			return Math.max(0, Math.min(value, maximum));
		}
	}
}
