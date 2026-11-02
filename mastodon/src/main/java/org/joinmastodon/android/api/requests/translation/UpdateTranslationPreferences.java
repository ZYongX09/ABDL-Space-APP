package org.joinmastodon.android.api.requests.translation;

import org.joinmastodon.android.model.TranslationPreferences;

import java.util.Map;

public class UpdateTranslationPreferences extends TranslationRequest<TranslationPreferences>{
	public UpdateTranslationPreferences(String readingLanguage, boolean automatic, String revision){
		super(HttpMethod.PUT, "/translation/preferences", TranslationPreferences.class);
		if(readingLanguage==null || readingLanguage.isBlank() || revision==null || revision.isBlank()) throw new IllegalArgumentException("Translation preference identity required");
		setRequestBody(Map.of("reading_language", readingLanguage, "auto_translate_enabled", automatic, "revision", revision));
	}
}
