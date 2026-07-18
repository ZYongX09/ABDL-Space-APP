package org.joinmastodon.android.api.requests.nbw;

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
