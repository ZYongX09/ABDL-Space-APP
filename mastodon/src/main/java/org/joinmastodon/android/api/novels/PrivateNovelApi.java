package org.joinmastodon.android.api.novels;

import com.google.gson.annotations.SerializedName;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.AccountSession;

import java.io.IOException;
import java.util.Map;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class PrivateNovelApi{
	private static final String API_BASE_URL="https://api.abdl-space.top/api/v1/novels/private";
	private static final MediaType JSON=MediaType.parse("application/json; charset=utf-8");
	private static final Gson GSON=new GsonBuilder().disableHtmlEscaping().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();

	private final String baseUrl;
	private final String token;
	private final Call.Factory callFactory;
	private final boolean allowHttpTransfersForTests;

	public PrivateNovelApi(AccountSession session){
		this(API_BASE_URL, session.token.accessToken, noRedirectClient(MastodonAPIController.getHttpClient()), false);
	}

	public PrivateNovelApi(String baseUrl, String token, Call.Factory callFactory, boolean allowHttpForTests){
		this(baseUrl, token, callFactory, allowHttpForTests, allowHttpForTests);
	}

	public PrivateNovelApi(String baseUrl, String token, Call.Factory callFactory, boolean allowHttpApiForTests, boolean allowHttpTransfersForTests){
		if(!allowHttpApiForTests && !baseUrl.startsWith("https://"))
			throw new IllegalArgumentException("Private novel API must use HTTPS");
		this.baseUrl=stripTrailingSlash(baseUrl);
		this.token=token;
		this.callFactory=withoutRedirects(callFactory);
		this.allowHttpTransfersForTests=allowHttpTransfersForTests;
	}

	public Call.Factory getCallFactory(){
		return callFactory;
	}

	boolean isAllowedTransferUrl(String url){
		return url!=null && (url.startsWith("https://") || allowHttpTransfersForTests && url.startsWith("http://"));
	}

	public Call newAuthorizeCall(AuthorizeRequest request){
		return newJsonCall("/authorize", GSON.toJson(request));
	}

	public Call newCompleteCall(String bookId){
		return newJsonCall("/"+bookId+"/complete", "{}");
	}

	public Call newDownloadAuthorizeCall(String bookId){
		return newJsonCall("/"+bookId+"/download/authorize", "{}");
	}

	public Call newBookCall(String bookId){
		Request request=authorizedRequest(baseUrl+"/books/"+bookId).get().build();
		return callFactory.newCall(request);
	}

	public <T> T executeJson(Call call, Class<T> type) throws IOException{
		return executeJsonResponse(call, type).body;
	}

	public <T> ApiResponse<T> executeJsonResponse(Call call, Class<T> type) throws IOException{
		try(Response response=call.execute()){
			if(response.priorResponse()!=null)
				throw new IOException("Redirects are not allowed");
			ResponseBody body=response.body();
			if(body==null)
				throw new IOException("Empty response body");
			try{
				if(!response.isSuccessful()){
					ErrorEnvelope envelope=GSON.fromJson(body.charStream(), ErrorEnvelope.class);
					String code=envelope==null ? null : envelope.error!=null ? envelope.error.code : envelope.code;
					throw new ApiException(response.code(), code);
				}
				T parsed=GSON.fromJson(body.charStream(), type);
				if(parsed==null)
					throw new IOException("Invalid response body");
				return new ApiResponse<>(response.code(), parsed);
			}catch(JsonParseException | IllegalStateException e){
				throw new IOException("Invalid JSON response", e);
			}
		}
	}

	private Call newJsonCall(String path, String json){
		Request request=authorizedRequest(baseUrl+path)
				.post(RequestBody.create(JSON, json))
				.build();
		return callFactory.newCall(request);
	}

	private Request.Builder authorizedRequest(String url){
		return new Request.Builder()
				.url(url)
				.header("Authorization", "Bearer "+token)
				.header("Accept", "application/json");
	}

	private static Call.Factory noRedirectClient(OkHttpClient client){
		return client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
	}

	private static Call.Factory withoutRedirects(Call.Factory factory){
		return factory instanceof OkHttpClient ? noRedirectClient((OkHttpClient) factory) : factory;
	}

	private static String stripTrailingSlash(String value){
		return value.endsWith("/") ? value.substring(0, value.length()-1) : value;
	}

	public static class UploadMetadata{
		public final String title;
		public final String author;
		public final String format;
		@SerializedName("mime_type") public final String mimeType;

		public UploadMetadata(String title, String author, String format, String mimeType){
			this.title=title;
			this.author=author;
			this.format=format;
			this.mimeType=mimeType;
		}
	}

	public static class AuthorizeRequest extends UploadMetadata{
		@SerializedName("declared_size") public final long declaredSize;
		@SerializedName("content_hash") public final String contentHash;
		@SerializedName("content_md5") public final String contentMd5;

		public AuthorizeRequest(UploadMetadata metadata, long declaredSize, String contentHash, String contentMd5){
			super(metadata.title, metadata.author, metadata.format, metadata.mimeType);
			this.declaredSize=declaredSize;
			this.contentHash=contentHash;
			this.contentMd5=contentMd5;
		}
	}

	public static class UploadAuthorization{
		@SerializedName("upload_id") public String uploadId;
		@SerializedName("upload_url") public String uploadUrl;
		@SerializedName("required_headers") public Map<String, String> requiredHeaders;
		@SerializedName("expires_at") public long expiresAt;
		@SerializedName("already_uploaded") public boolean alreadyUploaded;
		@SerializedName("parse_status") public String parseStatus;
	}

	public static class CompleteResultDto{
		public String id;
		public String format;
		@SerializedName("verified_size") public Long verifiedSize;
		@SerializedName("parse_status") public String parseStatus;
	}

	public static class ApiResponse<T>{
		public final int status;
		public final T body;

		ApiResponse(int status, T body){
			this.status=status;
			this.body=body;
		}
	}

	public static class ApiException extends IOException{
		public final int status;
		public final String code;

		ApiException(int status, String code){
			super("HTTP "+status+(code==null ? "" : " ("+code+")"));
			this.status=status;
			this.code=code;
		}
	}

	private static class ErrorEnvelope{
		ErrorDto error;
		String code;
	}

	private static class ErrorDto{
		String code;
	}

	public static class DownloadAuthorization{
		@SerializedName("download_url") public String downloadUrl;
		@SerializedName("expires_at") public long expiresAt;
	}

	public static class BookDto{
		public String id;
		public String title;
		public String author;
		public String format;
		@SerializedName("content_hash") public String contentHash;
		@SerializedName("verified_size") public long verifiedSize;
		@SerializedName("parse_status") public String parseStatus;
	}
}
