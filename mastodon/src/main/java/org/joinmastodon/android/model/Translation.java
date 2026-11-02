package org.joinmastodon.android.model;

import org.joinmastodon.android.api.ObjectValidationException;

/** A 202 is an in-flight result, never translated content. */
public class Translation extends BaseModel{
	public String content;
	public String detectedSourceLanguage;
	public String provider;
	public String spoilerText;
	public MediaAttachment[] mediaAttachments;
	public PollTranslation poll;
	public String translationStatus;
	public Integer retryAfter;
	public String sourceHash;
	public String targetLanguage;
	public boolean cached;
	public TranslationPreferences.Quota quota;

	public boolean isReady(){ return translationStatus==null || "ready".equals(translationStatus); }
	public boolean isPending(){ return "pending".equals(translationStatus); }
	public boolean isUnknown(){ return "unknown".equals(translationStatus); }

	@Override public void postprocess() throws ObjectValidationException{
		super.postprocess();
		if(isReady()){
			if(content==null || detectedSourceLanguage==null || provider==null)
				throw new ObjectValidationException("Incomplete ready translation");
		}else if(!isPending() && !isUnknown()){
			throw new ObjectValidationException("Unsupported translation state");
		}else if(sourceHash==null || sourceHash.isBlank() || targetLanguage==null || targetLanguage.isBlank()){
			throw new ObjectValidationException("Incomplete pending translation identity");
		}
		if(retryAfter!=null && retryAfter<0) throw new ObjectValidationException("Invalid translation retry interval");
	}

	public String pollOptionTitle(String pollID, int index, String fallback){
		if(poll==null || poll.options==null || index<0 || index>=poll.options.length
				|| poll.id!=null && !poll.id.equals(pollID) || poll.options[index]==null || poll.options[index].title==null) return fallback;
		return poll.options[index].title;
	}

	public String attachmentDescription(String id, String fallback){
		if(mediaAttachments!=null) for(MediaAttachment attachment:mediaAttachments)
			if(attachment!=null && id!=null && id.equals(attachment.id) && attachment.description!=null) return attachment.description;
		return fallback;
	}

	public static class MediaAttachment{ public String id; public String description; }
	public static class PollTranslation{ public String id; public PollOption[] options; }
	public static class PollOption{ public String title; }
}
