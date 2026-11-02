package org.joinmastodon.android.model;

import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.api.RequiredField;

/** Server-owned account preference and current capability. No local opt-in defaults. */
public class TranslationPreferences extends BaseModel{
	@RequiredField public String readingLanguage;
	@RequiredField public String revision;
	public boolean autoTranslateEnabled;
	public boolean enabled;
	public boolean providerConfigured;
	public boolean autoTranslateAllowed;
	public Quota quota;

	@Override public void postprocess() throws ObjectValidationException{
		super.postprocess();
		if(readingLanguage.isBlank() || revision.isBlank()) throw new ObjectValidationException("Invalid translation preferences");
	}

	public static class Quota{
		public long limit, used, reserved, resetAt;
		public String day;
	}
}
