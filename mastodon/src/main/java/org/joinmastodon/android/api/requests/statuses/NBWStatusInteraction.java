package org.joinmastodon.android.api.requests.statuses;

import com.google.gson.JsonObject;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.api.RequiredField;
import org.joinmastodon.android.model.BaseModel;
import org.joinmastodon.android.model.Status;

import java.io.IOException;

import me.grishka.appkit.api.ErrorResponse;

/** NBW actions return real interaction state, not a fabricated renderable Status. */
public class NBWStatusInteraction extends MastodonAPIRequest<NBWStatusInteraction.Result>{
	private final String id;
	private final boolean favorite;

	public NBWStatusInteraction(String id, boolean favorite, boolean enabled){
		super(HttpMethod.POST, "/statuses/"+id+"/"+(favorite ? enabled ? "favourite" : "unfavourite" : enabled ? "bookmark" : "unbookmark"), Result.class);
		if(!supportsID(id)) throw new IllegalArgumentException("Only NBW root thread interactions are supported");
		this.id=id;
		this.favorite=favorite;
		setRequestBody(new JsonObject());
	}

	public static boolean supportsID(String id){
		if(id==null || !id.matches("nbw_[1-9][0-9]*")) return false;
		try{ return Long.parseLong(id.substring(4))>0; }
		catch(NumberFormatException invalid){ return false; }
	}

	@Override public void validateAndPostprocessResponse(Result result, okhttp3.Response response) throws IOException{
		super.validateAndPostprocessResponse(result, response);
		if(!id.equals(result.id)) throw new ObjectValidationException("NBW interaction ID does not match the requested thread");
		if(favorite ? result.favourited==null : result.bookmarked==null)
			throw new ObjectValidationException("NBW interaction state is missing");
		if(result.favouritesCount!=null && result.favouritesCount<0 || result.bookmarksCount!=null && result.bookmarksCount<0)
			throw new ObjectValidationException("NBW interaction count is invalid");
	}

	@Override public ErrorResponse deserializeError(JsonObject body, int status){
		return MastodonErrorResponse.nbwBindingError(body, status);
	}

	public static class Result extends BaseModel{
		@RequiredField public String id;
		public Boolean favourited, bookmarked;
		public Long favouritesCount, bookmarksCount;

		public Status merge(Status original){
			Status merged=original.clone();
			if(favourited!=null){
				if(favouritesCount==null && original.favourited!=favourited) merged.favouritesCount=Math.max(0, original.favouritesCount+(favourited ? 1 : -1));
				merged.favourited=favourited;
			}
			if(bookmarked!=null){
				if(bookmarksCount==null && original.bookmarked!=bookmarked) merged.bookmarksCount=Math.max(0, original.bookmarksCount+(bookmarked ? 1 : -1));
				merged.bookmarked=bookmarked;
			}
			if(favouritesCount!=null) merged.favouritesCount=favouritesCount;
			if(bookmarksCount!=null) merged.bookmarksCount=bookmarksCount;
			return merged;
		}
	}
}
