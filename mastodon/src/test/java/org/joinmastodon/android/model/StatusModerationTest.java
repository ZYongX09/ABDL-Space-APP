package org.joinmastodon.android.model;

import static org.junit.Assert.*;

import android.content.Context;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.ui.displayitems.ModerationNoticeStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.grishka.appkit.utils.V;

/** Strict optional moderation projection + safe row/inline projection. No HTTP. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="en-rUS-w393dp-h852dp-mdpi")
public class StatusModerationTest{
	private static final String REASON="含违规内容";
	private static final String NOTICE=StatusModeration.PREFIX+REASON+StatusModeration.SUFFIX;
	private Gson gson;
	private Context context;

	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication();
		V.setApplicationContext(MastodonApp.context);
		context=MastodonApp.context;
		gson=new GsonBuilder()
				.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
				.registerTypeAdapter(StatusModeration.class, new StatusModeration.Deserializer())
				.create();
	}

	@Test public void validModerationMatchesExactServerNotice(){
		StatusModeration moderation=parseModeration(validJson(REASON, NOTICE));
		assertNotNull(moderation);
		assertTrue(moderation.isSupported());
		assertEquals("blocked", moderation.state);
		assertEquals(REASON, moderation.reason);
		assertEquals(NOTICE, moderation.notice);
		assertEquals(NOTICE, StatusModeration.noticeFor(REASON));

		Status status=gson.fromJson("{\"moderation\":"+validJson(REASON, NOTICE)+"}", Status.class);
		assertNotNull(status);
		assertNotNull(status.moderation);
		assertEquals(NOTICE, status.moderation.notice);

		Status cached=new Status();
		cached.id="cached-1";
		cached.moderation=moderation;
		Status roundTripped=gson.fromJson(gson.toJson(cached), Status.class);
		assertNotNull("Cached JSON must retain the moderation projection", roundTripped.moderation);
		assertEquals(NOTICE, roundTripped.moderation.notice);
	}

	@Test public void invalidOrMalformedModerationReturnsNullWithoutDroppingStatus(){
		String[] invalid={
				null,
				"null",
				"[]",
				"\"blocked\"",
				"{}",
				json(2, "blocked", REASON, NOTICE),
				json(1, "pending", REASON, NOTICE),
				json(1, "blocked", "", NOTICE),
				json(1, "blocked", REASON+"\u0000", NOTICE),
				json(1, "blocked", "x".repeat(501), NOTICE),
				json(1, "blocked", REASON, NOTICE+" "),
				json(1, "blocked", REASON, "错误提示"),
				"{\"schema_version\":1,\"state\":\"blocked\",\"reason\":123,\"notice\":"+quote(NOTICE)+"}",
				"{\"schema_version\":1,\"state\":\"blocked\",\"reason\":"+quote(REASON)+"}",
		};
		for(String value:invalid){
			String json="{\"id\":\"status-1\",\"moderation\":"+value+",\"reblog\":null}";
			Status status=gson.fromJson(json, Status.class);
			assertNotNull("A bad optional moderation field must not discard the status: "+value, status);
			assertNull("Invalid moderation must be ignored: "+value, status.moderation);
		}
	}

	@Test public void getStrippedTextReturnsNoticeAndNeverOldBody(){
		Status status=Status.ofFake("blocked-1", "<p>原始正文</p>", Instant.parse("2026-11-15T00:00:00Z"));
		status.moderation=parseModeration(validJson(REASON, NOTICE));
		setField(status, "strippedText", "旧正文缓存");
		assertEquals(NOTICE, status.getStrippedText());
		assertEquals(NOTICE, status.getStrippedText());
		assertFalse(status.getStrippedText().contains("原始正文"));
		assertFalse(status.getStrippedText().contains("旧正文缓存"));
	}

	@Test public void getStrippedTextUsesExactNoticeStoredAsPlainContent(){
		Status status=Status.ofFake("blocked-2", NOTICE, Instant.parse("2026-11-15T00:00:00Z"));
		assertNull(status.moderation);
		assertTrue(StatusModerationSupport.isBlocked(status));
		assertEquals(NOTICE, status.getStrippedText());
	}

	@Test public void getStrippedTextFindsBlockedNestedReblog(){
		Status outer=Status.ofFake("outer", "<p>转发的旧正文</p>", Instant.parse("2026-11-15T00:00:00Z"));
		Status inner=Status.ofFake("inner", "<p>原始正文</p>", Instant.parse("2026-11-15T00:00:00Z"));
		inner.moderation=parseModeration(validJson(REASON, NOTICE));
		outer.reblog=inner;
		assertEquals(NOTICE, outer.getStrippedText());
	}

	@Test public void buildItemsForBlockedStatusKeepsHeaderAndFooterOnly(){
		Status status=blockedStatus("blocked-row");
		status.spoilerText="警告";
		status.mediaAttachments=new ArrayList<>(List.of(attachment()));
		status.poll=new Poll();
		status.card=new Card();
		assertFalse(status.isEligibleForTranslation("en"));
		List<StatusDisplayItem> items=StatusDisplayItem.buildItems(context, status, "account-1", status, Map.of(), StatusDisplayItem.FLAG_FULL_WIDTH);
		List<StatusDisplayItem.Type> types=new ArrayList<>();
		for(StatusDisplayItem item:items)
			types.add(item.getType());
		assertEquals(List.of(StatusDisplayItem.Type.HEADER, StatusDisplayItem.Type.MODERATION_NOTICE, StatusDisplayItem.Type.FOOTER), types);
		ModerationNoticeStatusDisplayItem notice=(ModerationNoticeStatusDisplayItem)items.get(1);
		assertEquals(NOTICE, notice.notice);
		assertEquals(status, notice.status);
		Set<StatusDisplayItem.Type> forbidden=new HashSet<>(List.of(
				StatusDisplayItem.Type.TEXT, StatusDisplayItem.Type.TRANSLATION, StatusDisplayItem.Type.MEDIA_GRID,
				StatusDisplayItem.Type.AUDIO, StatusDisplayItem.Type.POLL_OPTION, StatusDisplayItem.Type.POLL_FOOTER,
				StatusDisplayItem.Type.CARD_LARGE, StatusDisplayItem.Type.CARD_COMPACT, StatusDisplayItem.Type.SPOILER,
				StatusDisplayItem.Type.FILTER_SPOILER));
		for(StatusDisplayItem.Type type:types)
			assertFalse("Blocked row must not render "+type, forbidden.contains(type));
	}

	private Status blockedStatus(String id){
		Status status=Status.ofFake(id, "<p>原始正文</p>", Instant.parse("2026-11-15T00:00:00Z"));
		status.content="<p>原始正文</p>";
		status.language="zh";
		status.visibility=StatusPrivacy.PUBLIC;
		status.account=new Account();
		status.account.id="42";
		status.account.username=status.account.acct="owner";
		status.account.displayName="Owner";
		status.account.emojis=List.of();
		status.moderation=parseModeration(validJson(REASON, NOTICE));
		return status;
	}

	private Attachment attachment(){
		Attachment attachment=new Attachment();
		attachment.id="attachment-1";
		attachment.type=Attachment.Type.IMAGE;
		attachment.url="https://example.invalid/media.png";
		return attachment;
	}

	private StatusModeration parseModeration(String json){
		return gson.fromJson(json, StatusModeration.class);
	}

	private static String validJson(String reason, String notice){
		return json(1, "blocked", reason, notice);
	}

	private static String json(int version, String state, String reason, String notice){
		JsonObject object=new JsonObject();
		object.addProperty("schema_version", version);
		object.addProperty("state", state);
		object.addProperty("reason", reason);
		object.addProperty("notice", notice);
		return object.toString();
	}

	private static String quote(String value){
		return new Gson().toJson(value);
	}

	private static void setField(Object target, String name, Object value){
		try{
			Field field=target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		}catch(ReflectiveOperationException error){
			throw new AssertionError(error);
		}
	}
}
