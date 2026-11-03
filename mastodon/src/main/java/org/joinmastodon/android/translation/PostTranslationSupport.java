package org.joinmastodon.android.translation;

import android.content.Context;
import android.text.TextUtils;

import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.Translation;
import org.joinmastodon.android.ui.displayitems.FriendCardStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.NovelStatusDisplayItem;
import org.joinmastodon.android.ui.text.HtmlParser;
import org.joinmastodon.android.ui.utils.AppLanguageManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/** Pure presentation/source identity helpers. Never starts requests from a display-item builder. */
public final class PostTranslationSupport{
	private PostTranslationSupport(){}

	public static String normalizeLanguage(String tag){
		if(tag==null) return "";
		Locale locale=Locale.forLanguageTag(tag.replace('_', '-'));
		String language=locale.getLanguage();
		if(language.isEmpty() || "und".equals(language)) return "";
		// en and en-US are the same reading target and quota tier; preserve other BCP47 scripts.
		return "en".equals(language) ? "en" : locale.toLanguageTag();
	}
	public static String target(Context context){ return normalizeLanguage(AppLanguageManager.getLanguageTag(context)); }

	public static String cleanBody(Status status){
		Status content=status.getContentStatus();
		if(content.albumUpdate!=null || FriendCardStatusDisplayItem.replacesBody(FriendCardStatusDisplayItem.presentation(content.friendCard))) return "";
		return NovelStatusDisplayItem.contentWithoutFallback(FriendCardStatusDisplayItem.contentWithoutFallback(content.content, content.friendCard), content.novel);
	}
	public static boolean eligible(Status status, String target){
		Status content=status.getContentStatus();
		return !"app-update-required".equals(content.id) && !content.preview && content.advertisement==null && content.isEligibleForTranslation(target)
				&& (!TextUtils.isEmpty(HtmlParser.strip(cleanBody(content))) || !TextUtils.isEmpty(content.spoilerText));
	}

	/** Local edit/cache fence, deliberately NOT the server's authoritative source_hash. Never sent. */
	public static String sourceVersion(Status status){
		Status content=status.getContentStatus();
		try{
			MessageDigest digest=MessageDigest.getInstance("SHA-256");
			for(String value:new String[]{content.id, content.content, content.spoilerText, content.language,
					String.valueOf(content.editedAt), String.valueOf(content.visibility), cleanBody(content)}){
				byte[] bytes=Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
				digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
			}
			StringBuilder hex=new StringBuilder();
			for(byte b:digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
			return hex.toString();
		}catch(NoSuchAlgorithmException impossible){ throw new AssertionError(impossible); }
	}

	public static void validateCached(Status status, String accountID, String target){
		Status content=status.getContentStatus();
		if(!Objects.equals(content.translationAccountID, accountID) || !Objects.equals(content.translationTargetLanguage, target)
				|| !Objects.equals(content.translationSourceVersion, sourceVersion(content))
				|| content.translation!=null && android.os.SystemClock.elapsedRealtime()-content.translationValidatedAt>=PostTranslationCoordinator.CACHE_TTL_MS){
			content.translation=null; content.translationState=Status.TranslationState.HIDDEN;
			content.translationSourceVersion=null; content.translationSourceHash=null;
			content.translationAccountID=null; content.translationTargetLanguage=null;
		}
	}
	public static void identify(Status status, String accountID, String target, String version){
		status.translationAccountID=accountID; status.translationTargetLanguage=target; status.translationSourceVersion=version;
	}
	public static void apply(Status status, String accountID, String target, String version, Translation result){
		identify(status, accountID, target, version);
		status.translationSourceHash=result.sourceHash;
		status.translationValidatedAt=android.os.SystemClock.elapsedRealtime();
		status.translation=result; status.translationState=Status.TranslationState.SHOWN;
	}

	/** Translate ALT only in a disposable display projection, never in the original attachment. */
	public static Attachment displayedAttachment(Status status, Attachment original){
		if(status.translationState!=Status.TranslationState.SHOWN || status.translation==null) return original;
		String description=status.translation.attachmentDescription(original.id, original.description);
		if(Objects.equals(description, original.description)) return original;
		Attachment display=(Attachment)original.clone(); display.description=description; return display;
	}
}
