package org.joinmastodon.android.api.requests.albums;

import com.google.gson.JsonObject;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.sponsors.SponsorRequest;

import me.grishka.appkit.api.ErrorResponse;

/** Selected-session /api/v1/albums requests; sensitive, no cache and no redirects. */
public final class AlbumRequest<T> extends MastodonAPIRequest<T>{
	private AlbumRequest(HttpMethod method, String path, Class<T> type, Object body){
		super(method, albumPath(path), type);
		if(body!=null) setRequestBody(body);
		addHeader("Cache-Control", "no-store");
		setTimeout(30_000);
	}
	private static String albumPath(String path){
		if(path==null || (!path.isEmpty() && !path.startsWith("/")) || path.contains("?") || path.contains("#") || path.contains("..") || path.contains("://"))
			throw new IllegalArgumentException("Use an album-relative path and query() for parameters");
		return "/albums"+path;
	}
	public static <T> AlbumRequest<T> get(String path, Class<T> type){ return new AlbumRequest<>(HttpMethod.GET, path, type, null); }
	public static <T> AlbumRequest<T> post(String path, Class<T> type, Object body){ return new AlbumRequest<>(HttpMethod.POST, path, type, body); }
	public static <T> AlbumRequest<T> patch(String path, Class<T> type, Object body){ return new AlbumRequest<>(HttpMethod.PATCH, path, type, body); }
	public static <T> AlbumRequest<T> delete(String path, Class<T> type){ return new AlbumRequest<>(HttpMethod.DELETE, path, type, java.util.Map.of()); }
	public static <T> AlbumRequest<T> delete(String path, Class<T> type, Object body){ return new AlbumRequest<>(HttpMethod.DELETE, path, type, body); }
	public AlbumRequest<T> query(String key, Object value){ addQueryParameter(key, String.valueOf(value)); return this; }
	@Override public boolean isSensitiveRequest(){ return true; }
	@Override public ErrorResponse deserializeError(JsonObject body, int status){ return new AlbumError(body, status); }

	/** Sponsor notice/quota errors are compatible with SponsorNoticeSheet, without precharging. */
	public static final class AlbumError extends SponsorRequest.SponsorError{
		public AlbumError(JsonObject body, int status){ super(body, status); }
	}
}
