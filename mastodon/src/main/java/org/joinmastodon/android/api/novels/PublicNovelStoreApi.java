package org.joinmastodon.android.api.novels;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

import org.joinmastodon.android.api.MastodonAPIController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Anonymous, read-only client for reviewed public novels. */
public class PublicNovelStoreApi{
	private static final String API_BASE_URL="https://api.abdl-space.top/api/v1/novels/store";
	private static final Gson GSON=new GsonBuilder().disableHtmlEscaping().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();

	private final String baseUrl;
	private final Call.Factory callFactory;

	public PublicNovelStoreApi(){
		this(API_BASE_URL, MastodonAPIController.getHttpClient(), false);
	}

	public PublicNovelStoreApi(String baseUrl, Call.Factory callFactory, boolean allowHttpForTests){
		if(!allowHttpForTests && !baseUrl.startsWith("https://"))
			throw new IllegalArgumentException("Public novel store API must use HTTPS");
		this.baseUrl=baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl;
		this.callFactory=callFactory instanceof OkHttpClient ? ((OkHttpClient)callFactory).newBuilder().cache(null).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build() : callFactory;
	}

	public Call newWorksCall(String cursor){
		return callFactory.newCall(request(baseUrl+"/works?limit=20"+(cursor==null ? "" : "&cursor="+encode(cursor))));
	}

	public Call newWorkCall(String workId){
		return callFactory.newCall(request(baseUrl+"/works/"+encode(workId)));
	}

	public Call newChapterCall(String workId, String chapterId, String revisionId){
		return callFactory.newCall(request(baseUrl+"/works/"+encode(workId)+"/chapters/"+encode(chapterId)+"?revision_id="+encode(revisionId)));
	}

	public <T> T executeJson(Call call, Class<T> type) throws IOException{
		try(Response response=call.execute()){
			if(response.priorResponse()!=null) throw new IOException("Redirects are not allowed");
			ResponseBody body=response.body();
			if(body==null) throw new IOException("Empty response body");
			try{
				if(!response.isSuccessful()) throw new ApiException(response.code());
				T parsed=GSON.fromJson(body.charStream(), type);
				if(parsed==null) throw new IOException("Invalid response body");
				return parsed;
			}catch(JsonParseException | IllegalStateException e){
				throw new IOException("Invalid JSON response", e);
			}
		}
	}

	private Request request(String url){
		return new Request.Builder().url(url).header("Accept", "application/json").get().build();
	}

	private static String encode(String value){
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	public static class ApiException extends IOException{
		public final int status;
		ApiException(int status){ super("HTTP "+status); this.status=status; }
	}

	public static class WorkListDto{ public List<WorkDto> items; @SerializedName("next_cursor") public String nextCursor; }
	public static class AuthorDto{ public String username; }
	public static class WorkDto{
		public String id;
		public String title;
		public String description;
		public String category;
		public AuthorDto author;
		@SerializedName("published_chapter_count") public int publishedChapterCount;
		@SerializedName("published_at") public long publishedAt;
		@SerializedName("created_at") public long createdAt;
		@SerializedName("updated_at") public long updatedAt;
		public List<VolumeDto> volumes;
	}
	public static class VolumeDto{
		public String id;
		public String title;
		@SerializedName("sort_order") public int sortOrder;
		public List<ChapterDto> chapters;
	}
	public static class ChapterDto{
		public String id;
		public String title;
		@SerializedName("sort_order") public int sortOrder;
		@SerializedName("published_revision_id") public String publishedRevisionId;
		public String rating;
		@SerializedName("content_hint") public String contentHint;
	}
	public static class PublishedChapterDto{
		@SerializedName("revision_id") public String revisionId;
		@SerializedName("chapter_id") public String chapterId;
		public String body;
		public String rating;
		@SerializedName("content_hint") public String contentHint;
	}
}
