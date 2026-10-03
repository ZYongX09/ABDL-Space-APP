package org.joinmastodon.android.api.requests.nbw;

import android.net.Uri;

import com.google.gson.annotations.SerializedName;

import org.joinmastodon.android.api.MastodonAPIRequest;

public class RecommendNBWForum extends MastodonAPIRequest<RecommendNBWForum.Response>{
	public RecommendNBWForum(String content){
		super(HttpMethod.POST, "/auth/nbw/recommend-fid", Response.class);
		setRequestBody(new Request(content));
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}

	@Override
	public Uri getURL(){
		return new Uri.Builder()
				.scheme("https")
				.authority("api.abdl-space.top")
				.path("/api/auth/nbw/recommend-fid")
				.build();
	}

	private static class Request{
		public String content;

		private Request(String content){
			this.content=content;
		}
	}

	public static class Response{
		public int fid;
		@SerializedName("forum_name")
		public String forumName;
		public double confidence;
		public boolean fallback;
	}
}
