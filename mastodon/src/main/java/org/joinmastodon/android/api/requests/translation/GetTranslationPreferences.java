package org.joinmastodon.android.api.requests.translation;

import org.joinmastodon.android.model.TranslationPreferences;

public class GetTranslationPreferences extends TranslationRequest<TranslationPreferences>{
	public GetTranslationPreferences(){ super(HttpMethod.GET, "/translation/preferences", TranslationPreferences.class); }
}
