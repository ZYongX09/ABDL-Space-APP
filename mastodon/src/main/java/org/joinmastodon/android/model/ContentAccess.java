package org.joinmastodon.android.model;

import com.google.gson.annotations.SerializedName;

import org.joinmastodon.android.api.ObjectValidationException;

/** Server policy only; neither a certificate result nor a cached grant. */
public final class ContentAccess extends BaseModel{
	public static final String ACTION_PATH="/settings/baby-verification";
	public int schemaVersion;
	public State state;
	public String actionPath, message;

	public enum State{
		@SerializedName("open") OPEN,
		@SerializedName("baby_verification_required") BABY_VERIFICATION_REQUIRED,
		@SerializedName("unavailable") UNAVAILABLE
	}

	@Override public void postprocess() throws ObjectValidationException{
		if(schemaVersion!=1 || state==null || !ACTION_PATH.equals(actionPath) || message==null || message.length()>8192)
			throw new ObjectValidationException("Invalid content access response");
	}
}
