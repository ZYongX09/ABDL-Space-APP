package org.joinmastodon.android.api.requests.statuses;

import org.joinmastodon.android.api.requests.translation.TranslationRequest;
import org.joinmastodon.android.model.Translation;

import java.util.Map;

public class TranslateStatus extends TranslationRequest<Translation>{
	public TranslateStatus(String id, String lang){ this(id, lang, false); }
	public TranslateStatus(String id, String lang, boolean automatic){
		super(HttpMethod.POST, "/statuses/"+android.net.Uri.encode(id)+"/translate", Translation.class);
		setRequestBody(Map.of("lang", lang, "automatic", automatic));
	}
}
