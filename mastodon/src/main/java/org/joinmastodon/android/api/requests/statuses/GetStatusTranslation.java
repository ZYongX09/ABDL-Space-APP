package org.joinmastodon.android.api.requests.statuses;

import org.joinmastodon.android.api.requests.translation.TranslationRequest;
import org.joinmastodon.android.model.Translation;

/** Read-only poll; never resubmits AI work. */
public class GetStatusTranslation extends TranslationRequest<Translation>{
	public GetStatusTranslation(String id, String language){
		super(HttpMethod.GET, "/statuses/"+android.net.Uri.encode(id)+"/translation", Translation.class);
		addQueryParameter("lang", language);
	}
}
