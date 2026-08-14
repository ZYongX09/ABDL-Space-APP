package org.joinmastodon.android.api.novels;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.AccountSession;

import java.io.IOException;
import java.util.List;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class NovelAuthoringApi{
	private static final String API_BASE_URL="https://api.abdl-space.top/api/v1/novels/authoring";
	private static final MediaType JSON=MediaType.parse("application/json; charset=utf-8");
	private static final Gson GSON=new GsonBuilder().disableHtmlEscaping().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();

	private final String baseUrl;
	private final String token;
	private final Call.Factory callFactory;

	public NovelAuthoringApi(AccountSession session){
		this(API_BASE_URL, session.token.accessToken, MastodonAPIController.getHttpClient(), false);
	}

	public NovelAuthoringApi(String baseUrl, String token, Call.Factory callFactory, boolean allowHttpForTests){
		if(!allowHttpForTests && !baseUrl.startsWith("https://"))
			throw new IllegalArgumentException("Novel authoring API must use HTTPS");
		this.baseUrl=baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl;
		this.token=token;
		this.callFactory=callFactory instanceof OkHttpClient ? ((OkHttpClient)callFactory).newBuilder().cache(null).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build() : callFactory;
	}

	public Call newEligibilityCall(){
		return callFactory.newCall(authorizedRequest(baseUrl+"/eligibility").get().build());
	}

	public Call newWorksCall(){
		return callFactory.newCall(authorizedRequest(baseUrl+"/works").get().build());
	}

	public Call newCreateWorkCall(CreateWorkRequest input, String idempotencyKey){
		Request request=authorizedRequest(baseUrl+"/works")
				.header("Idempotency-Key", idempotencyKey)
				.post(RequestBody.create(JSON, GSON.toJson(input)))
				.build();
		return callFactory.newCall(request);
	}

	public <T> T executeJson(Call call, Class<T> type) throws IOException{
		try(Response response=call.execute()){
			if(response.priorResponse()!=null) throw new IOException("Redirects are not allowed");
			ResponseBody body=response.body();
			if(body==null) throw new IOException("Empty response body");
			try{
				if(!response.isSuccessful()){
					ErrorEnvelope error=GSON.fromJson(body.charStream(), ErrorEnvelope.class);
					throw new ApiException(response.code(), error==null ? null : error.code);
				}
				T parsed=GSON.fromJson(body.charStream(), type);
				if(parsed==null) throw new IOException("Invalid response body");
				return parsed;
			}catch(JsonParseException | IllegalStateException e){
				throw new IOException("Invalid JSON response", e);
			}
		}
	}

	private Request.Builder authorizedRequest(String url){
		return new Request.Builder().url(url).header("Authorization", "Bearer "+token).header("Accept", "application/json");
	}

	public static class EligibilityDto{
		public boolean eligible;
		public boolean accountAgeEligible;
		public boolean postEligible;
		public List<String> reasons;
	}

	public static class WorkListDto{
		public List<WorkDto> items;
	}

	public static class WorkDto{
		public String id;
		public String title;
		public String description;
		public String category;
		public String status;
		public long createdAt;
		public long updatedAt;
	}

	public static class CreateWorkRequest{
		public final String title;
		public final String description;
		public final String category;

		public CreateWorkRequest(String title, String description, String category){
			this.title=title;
			this.description=description;
			this.category=category;
		}
	}

	private static class ErrorEnvelope{
		String code;
	}

	public static class ApiException extends IOException{
		public final int status;
		public final String code;

		ApiException(int status, String code){
			super("Novel authoring request failed: HTTP "+status+(code==null ? "" : " ("+code+")"));
			this.status=status;
			this.code=code;
		}
	}
}
