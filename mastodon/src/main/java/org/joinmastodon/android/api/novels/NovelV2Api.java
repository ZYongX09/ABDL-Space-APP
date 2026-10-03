package org.joinmastodon.android.api.novels;

import com.google.gson.Gson;

import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.AccountSession;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Client for the local-first authoring v2 protocol and the release square. */
public class NovelV2Api{
	private static final String BASE="https://api.abdl-space.top/api/v1/novels";
	private static final MediaType JSON=MediaType.parse("application/json; charset=utf-8");
	private static final Gson GSON=new Gson();
	private final String baseUrl, token;
	private final Call.Factory callFactory;

	public NovelV2Api(AccountSession session){ this(BASE, session.token.accessToken, MastodonAPIController.getHttpClient(), false); }
	public NovelV2Api(String baseUrl, String token, Call.Factory callFactory, boolean allowHttpForTests){
		if(!allowHttpForTests && !baseUrl.startsWith("https://")) throw new IllegalArgumentException("Novel v2 API must use HTTPS");
		this.baseUrl=baseUrl.endsWith("/")?baseUrl.substring(0, baseUrl.length()-1):baseUrl;
		this.token=token;
		this.callFactory=callFactory instanceof OkHttpClient client ? client.newBuilder().cache(null).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build() : callFactory;
	}

	public SyncStartResponse start(SyncStartRequest input, String operation) throws IOException{
		return executeJson(post("/authoring/v2/sync/start", GSON.toJson(input), operation), new int[]{200, 201}, SyncStartResponse.class);
	}
	public PartResponse putPart(String syncId, String part, PartRequest input, String operation) throws IOException{
		return executeJson(put("/authoring/v2/sync/"+path(syncId)+"/parts/"+path(part), GSON.toJson(input), operation), 200, PartResponse.class);
	}
	public FinalizeResponse finalizeSync(String syncId, String clientWorkId, String operation) throws IOException{
		return executeJson(post("/authoring/v2/sync/"+path(syncId)+"/finalize", GSON.toJson(new ClientWorkRequest(clientWorkId)), operation), 200, FinalizeResponse.class);
	}
	public ReleaseResponse publishRelease(String clientWorkId, long manifestVersion, String operation) throws IOException{
		return executeJson(post("/authoring/v2/works/"+path(clientWorkId)+"/releases", GSON.toJson(new ReleaseRequest(manifestVersion)), operation), new int[]{200, 201}, ReleaseResponse.class);
	}
	public SquareListResponse squareWorks(String cursor, String queryText, int limit) throws IOException{
		StringBuilder query=new StringBuilder("?limit=").append(limit);
		if(cursor!=null && !cursor.isBlank()) query.append("&cursor=").append(url(cursor));
		if(queryText!=null && !queryText.isBlank()) query.append("&query=").append(url(queryText));
		return executeJson(get("/square/works"+query), 200, SquareListResponse.class);
	}
	public SquareWorkResponse squareWork(String workId) throws IOException{ return executeJson(get("/square/works/"+path(workId)), 200, SquareWorkResponse.class); }
	public SquareChapterResponse squareChapter(String workId, String chapterId, String releaseId) throws IOException{
		return executeJson(get("/square/works/"+path(workId)+"/chapters/"+path(chapterId)+"?release_id="+url(releaseId)), 200, SquareChapterResponse.class);
	}

	private Call.Factory calls(){ return callFactory; }
	private Request.Builder authorized(String url){ return new Request.Builder().url(url).header("Authorization", "Bearer "+token).header("Accept", "application/json"); }
	private Request get(String path){ return authorized(baseUrl+path).get().build(); }
	private Request post(String path, String json, String operation){ return authorized(baseUrl+path).header("Idempotency-Key", operation).post(RequestBody.create(JSON, json)).build(); }
	private Request put(String path, String json, String operation){ return authorized(baseUrl+path).header("Idempotency-Key", operation).put(RequestBody.create(JSON, json)).build(); }
	private <T> T executeJson(Request request, int expected, Class<T> type) throws IOException{ return executeJson(request,new int[]{expected},type); }
	private <T> T executeJson(Request request, int[] expected, Class<T> type) throws IOException{
		Call call=calls().newCall(request);
		try(Response response=call.execute()){
			if(response.priorResponse()!=null) throw new IOException("Redirects are not allowed");
			ResponseBody body=response.body();
			String text=body==null ? "" : body.string();
			boolean accepted=false; for(int code:expected) if(response.code()==code){accepted=true;break;}
			if(!accepted){
				ApiError error=text.isBlank() ? null : safe(text);
				throw new ApiException(response.code(), error==null ? null : error.code, error==null ? null : error.error);
			}
			if(text.isBlank()) throw new IOException("Empty response body");
			T parsed=GSON.fromJson(text, type);
			if(parsed==null) throw new IOException("Invalid response");
			return parsed;
		}catch(com.google.gson.JsonParseException error){ throw new IOException("Invalid JSON response", error); }
	}
	private ApiError safe(String text){ try{ return GSON.fromJson(text, ApiError.class); }catch(RuntimeException ignored){ return null; } }
	private static String path(String value){ try{ return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }catch(java.io.UnsupportedEncodingException e){ throw new AssertionError(e); } }
	private static String url(String value){ return path(value); }

	public static class ClientWorkRequest{ public final String client_work_id; public ClientWorkRequest(String id){ this.client_work_id=id; } }
	public static class SyncStartRequest{
		public final String client_work_id, title, description, category, declared_rating, content_warning;
		public SyncStartRequest(String id, String title, String description, String category, String rating, String warning){ this.client_work_id=id; this.title=title; this.description=description; this.category=category; this.declared_rating=rating; this.content_warning=warning; }
	}
	public static class VolumeInput{
		public final String client_volume_id, title; public final long sort_order;
		public VolumeInput(String id, String title, long sort){ this.client_volume_id=id; this.title=title; this.sort_order=sort; }
	}
	public static class ChapterInput{
		public final String client_chapter_id, client_volume_id, title, body, body_sha256; public final long sort_order, generation;
		public ChapterInput(String id, String volume, String title, long sort, long generation, String body, String hash){ this.client_chapter_id=id; this.client_volume_id=volume; this.title=title; this.sort_order=sort; this.generation=generation; this.body=body; this.body_sha256=hash; }
	}
	public static class PartRequest{
		public final String client_work_id; public final List<VolumeInput> volumes; public final List<ChapterInput> chapters;
		public PartRequest(String work, List<VolumeInput> volumes, List<ChapterInput> chapters){ this.client_work_id=work; this.volumes=volumes; this.chapters=chapters; }
	}
	public static class ReleaseRequest{ public final long manifest_version; public ReleaseRequest(long version){ this.manifest_version=version; } }

	public static class SyncStartResponse{ public String sync_id, client_work_id; }
	public static class PartResponse{ public String sync_id, part; public int accepted_volumes, accepted_chapters; }
	public static class FinalizeResponse{ public String sync_id, client_work_id; public int manifest_version, volumes, chapters; }
	public static class ReleaseResponse{ public String work_id, client_work_id, release_id, status; public int release_version, manifest_version; }
	public static class SquareListResponse{ public List<SquareWorkResponse> items; public String next_cursor; }
	public static class SquareWorkResponse{
		public String id, release_id, title, description, category, declared_rating, content_warning, next_cursor;
		public long published_at; public int release_version, published_chapter_count; public SquareAuthor author;
		public List<SquareVolume> volumes;
	}
	public static class SquareAuthor{ public long id; public String username, avatar, url; }
	public static class SquareVolume{ public String id, title; public long sort_order; public List<SquareChapter> chapters; }
	public static class SquareChapter{ public String id, volume_id, title, body, body_sha256, release_id; public long sort_order, generation; }
	public static class SquareChapterResponse{ public String id, title, body, body_sha256, release_id; public long generation; }
	public static class ApiError{ public String error, code; }
	public static class ApiException extends IOException{
		public final int status; public final String code, message2;
		ApiException(int status, String code, String message){ super("Novel v2 request failed: HTTP "+status+(code==null ? "" : " ("+code+")")); this.status=status; this.code=code; this.message2=message; }
	}
	public static String newOperationId(){ return UUID.randomUUID().toString(); }
}
