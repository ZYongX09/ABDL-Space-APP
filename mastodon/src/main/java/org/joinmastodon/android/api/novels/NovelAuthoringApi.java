package org.joinmastodon.android.api.novels;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.AccountSession;

import java.io.IOException;
import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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

	public Call newStructureCall(String workId){
		return callFactory.newCall(authorizedRequest(baseUrl+"/works/"+encode(workId)+"/structure").get().build());
	}

	public Call newCreateVolumeCall(String workId, TitleRequest input, String idempotencyKey){
		return jsonCall("POST", "/works/"+encode(workId)+"/volumes", input, idempotencyKey);
	}

	public Call newCreateChapterCall(String workId, String volumeId, TitleRequest input, String idempotencyKey){
		return jsonCall("POST", "/works/"+encode(workId)+"/volumes/"+encode(volumeId)+"/chapters", input, idempotencyKey);
	}

	public Call newRenameVolumeCall(String workId, String volumeId, TitleRequest input){
		return jsonCall("PATCH", "/works/"+encode(workId)+"/volumes/"+encode(volumeId), input, null);
	}

	public Call newRenameChapterCall(String workId, String volumeId, String chapterId, TitleRequest input){
		return jsonCall("PATCH", "/works/"+encode(workId)+"/volumes/"+encode(volumeId)+"/chapters/"+encode(chapterId), input, null);
	}

	public Call newDeleteVolumeCall(String workId, String volumeId){
		return callFactory.newCall(authorizedRequest(baseUrl+"/works/"+encode(workId)+"/volumes/"+encode(volumeId)).delete().build());
	}

	public Call newDeleteChapterCall(String workId, String volumeId, String chapterId){
		return callFactory.newCall(authorizedRequest(baseUrl+"/works/"+encode(workId)+"/volumes/"+encode(volumeId)+"/chapters/"+encode(chapterId)).delete().build());
	}

	public Call newCreateRevisionCall(String chapterId, RevisionBodyRequest input, String idempotencyKey){
		return jsonCall("POST", "/chapters/"+encode(chapterId)+"/revisions", input, idempotencyKey);
	}

	public Call newDraftCall(String revisionId, DraftRequest input, String idempotencyKey){
		return jsonCall("PUT", "/revisions/"+encode(revisionId)+"/draft", input, idempotencyKey);
	}

	public RevisionDto executeDraft(Call call) throws IOException{
		try(Response response=call.execute()){
			if(response.priorResponse()!=null) throw new IOException("Redirects are not allowed");
			ResponseBody body=response.body();
			String json=body==null ? "" : body.string();
			try{
				if(response.code()==409){
					ConflictEnvelope conflict=json.isEmpty() ? null : GSON.fromJson(json, ConflictEnvelope.class);
					if(conflict!=null && "revision_conflict".equals(conflict.code) && isValidRevision(conflict.serverRevision))
						throw new DraftConflictException(conflict.serverRevision);
					throw new ApiException(response.code(), conflict==null ? null : conflict.code);
				}
				if(!response.isSuccessful()){
					ErrorEnvelope error=json.isEmpty() ? null : GSON.fromJson(json, ErrorEnvelope.class);
					throw new ApiException(response.code(), error==null ? null : error.code);
				}
				if(json.isEmpty()) throw new IOException("Empty response body");
				RevisionDto revision=GSON.fromJson(json, RevisionDto.class);
				if(!isValidRevision(revision)) throw new IOException("Invalid revision response");
				return revision;
			}catch(JsonParseException | IllegalStateException e){ throw new IOException("Invalid JSON response", e); }
		}
	}

	private static boolean isValidRevision(RevisionDto revision){
		return revision!=null && revision.id!=null && revision.chapterId!=null && revision.body!=null && revision.status!=null && revision.version>=1;
	}

	private Call jsonCall(String method, String path, Object input, String idempotencyKey){
		Request.Builder builder=authorizedRequest(baseUrl+path);
		if(idempotencyKey!=null) builder.header("Idempotency-Key", idempotencyKey);
		RequestBody body=RequestBody.create(JSON, GSON.toJson(input));
		builder.method(method, body);
		return callFactory.newCall(builder.build());
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

	public static class TitleRequest{
		public final String title;
		public TitleRequest(String title){ this.title=title; }
	}

	public static class StructureDto{
		public WorkDto work;
		public List<VolumeDto> volumes;
	}

	public static class VolumeDto{
		public String id;
		public String title;
		public long sortOrder;
		public long createdAt;
		public long updatedAt;
		public List<ChapterDto> chapters;
	}

	public static class ChapterDto{
		public String id;
		public String volumeId;
		public String title;
		public long sortOrder;
		public long createdAt;
		public long updatedAt;
	}

	public static class DeleteDto{
		public String id;
		public boolean deleted;
	}

	public static class RevisionDto{
		public String id;
		public String chapterId;
		public String body;
		public String status;
		public long version;
		public long createdAt;
		public long updatedAt;
	}

	public static class RevisionBodyRequest{
		public final String body;
		public RevisionBodyRequest(String body){ this.body=body; }
	}

	public static class DraftRequest{
		public final String body;
		@SerializedName("base_version") public final long baseVersion;
		public DraftRequest(String body, long baseVersion){ this.body=body; this.baseVersion=baseVersion; }
	}

	private static class ConflictEnvelope extends ErrorEnvelope{
		@SerializedName("server_revision") RevisionDto serverRevision;
	}

	private static class ErrorEnvelope{
		String code;
	}

	private static String encode(String value){
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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

	public static class DraftConflictException extends IOException{
		public final RevisionDto serverRevision;
		DraftConflictException(RevisionDto serverRevision){ super("Novel draft conflict"); this.serverRevision=serverRevision; }
	}
}
