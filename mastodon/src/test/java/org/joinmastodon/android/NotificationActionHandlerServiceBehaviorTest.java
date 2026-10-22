package org.joinmastodon.android;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.squareup.otto.Subscribe;

import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.api.requests.statuses.CreateStatus;
import org.joinmastodon.android.api.requests.statuses.NBWStatusInteraction;
import org.joinmastodon.android.api.requests.statuses.SetStatusFavorited;
import org.joinmastodon.android.events.StatusCountersUpdatedEvent;
import org.joinmastodon.android.events.StatusCreatedEvent;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.parceler.Parcels;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;

/** Real Service commands, Android notifications and callbacks; only request execution is captured. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="en",
		shadows=NotificationActionHandlerServiceBehaviorTest.LocalRequests.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class NotificationActionHandlerServiceBehaviorTest{
	private static final String ACCOUNT="notification.offline.example.test_42", TAG="offline-mention", CHANNEL="offline-actions";
	@Implements(MastodonAPIRequest.class) public static class LocalRequests{
		@RealObject MastodonAPIRequest<?> request;
		static final List<MastodonAPIRequest<?>> sent=new ArrayList<>();
		static final List<String> accounts=new ArrayList<>();
		@Implementation public MastodonAPIRequest<?> exec(String account){ sent.add(request); accounts.add(account); return request; }
	}
	public static class Events{
		final List<StatusCountersUpdatedEvent> counters=new ArrayList<>();
		final List<StatusCreatedEvent> created=new ArrayList<>();
		@Subscribe public void onCounters(StatusCountersUpdatedEvent event){ counters.add(event); }
		@Subscribe public void onCreated(StatusCreatedEvent event){ created.add(event); }
	}
	private ServiceController<NotificationActionHandlerService> lifecycle;
	private NotificationActionHandlerService service;
	private NotificationManager notifications;
	private final Events events=new Events();

	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication(); me.grishka.appkit.utils.V.setApplicationContext(MastodonApp.context);
		lifecycle=Robolectric.buildService(NotificationActionHandlerService.class).create(); service=lifecycle.get();
		notifications=service.getSystemService(NotificationManager.class);
		notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Offline actions", NotificationManager.IMPORTANCE_DEFAULT));
		LocalRequests.sent.clear(); LocalRequests.accounts.clear(); E.register(events);
	}
	@After public void cleanup(){ E.unregister(events); notifications.cancelAll(); lifecycle.destroy(); LocalRequests.sent.clear(); LocalRequests.accounts.clear(); }

	@Test public void nbwNotificationLikeUsesValidatedLightweightIdAndFlagAndPublishesRealCounters() throws Exception{
		Status original=status("nbw_123", null); postNotification();
		Intent intent=command("favorite", original.id); intent.putExtra("status", Parcels.wrap(original));
		service.onStartCommand(intent, 0, 1);
		MastodonAPIRequest<?> request=last(); assertTrue(request instanceof NBWStatusInteraction);
		assertEquals("POST", request.getMethod()); assertEquals("/api/v1/statuses/nbw_123/favourite", request.getURL().getPath());
		assertEquals(List.of(ACCOUNT), LocalRequests.accounts); assertEquals(1, running());
		NBWStatusInteraction.Result result=MastodonAPIController.gson.fromJson("{\"id\":\"nbw_123\",\"favourited\":true,\"favourites_count\":42}", NBWStatusInteraction.Result.class);
		((NBWStatusInteraction)request).validateAndPostprocessResponse(result, response());
		success(request, result);
		assertEquals(0, running()); assertEquals(1, events.counters.size());
		assertEquals("nbw_123", events.counters.get(0).id); assertTrue(events.counters.get(0).favorited); assertEquals(42, events.counters.get(0).favorites);
		assertEquals(service.getString(R.string.button_favorited), active().actions[1].title);
		assertNull(active().actions[1].actionIntent);
	}

	@Test public void notificationSelectedRequestStillRejectsMismatchedIdOrMissingFlag() throws Exception{
		service.onStartCommand(command("favorite", "nbw_123"), 0, 1);
		assertTrue(last() instanceof NBWStatusInteraction);
		for(String json:List.of("{\"id\":\"nbw_124\",\"favourited\":true}", "{\"id\":\"nbw_123\"}")){
			NBWStatusInteraction.Result result=MastodonAPIController.gson.fromJson(json, NBWStatusInteraction.Result.class);
			try{ ((NBWStatusInteraction)last()).validateAndPostprocessResponse(result, response()); fail("Invalid lightweight result must fail validation"); }
			catch(ObjectValidationException expected){}
		}
		callback(last()).onError(new org.joinmastodon.android.api.MastodonErrorResponse("invalid response", 200, null));
		assertEquals(0, running()); assertTrue(events.counters.isEmpty());
	}

	@Test public void floorAndMalformedNbwIdsAreRejectedBeforeRequestOrNotificationMutation() throws Exception{
		postNotification();
		for(String id:List.of("nbw_123_456", "nbw_0", "nbw_001", "nbw_9223372036854775808")){
			service.onStartCommand(command("favorite", id), 0, 1);
			assertTrue(LocalRequests.sent.isEmpty()); assertEquals(0, running());
			assertEquals(service.getString(R.string.button_favorite), active().actions[1].title);
			assertNotNull(active().actions[1].actionIntent);
		}
		assertEquals(service.getString(R.string.nbw_reply_interaction_unsupported), ShadowToast.getTextOfLatestToast());
	}

	@Test public void nbwReplySnapshotIsRejectedEvenIfItsIdLooksLikeAThread() throws Exception{
		Intent intent=command("favorite", "nbw_123"); intent.putExtra("status", Parcels.wrap(status("nbw_123", "p_parent")));
		service.onStartCommand(intent, 0, 1); assertTrue(LocalRequests.sent.isEmpty()); assertEquals(0, running());
	}

	@Test public void legacyNbwIntentCanSucceedWithoutInventingStatusAndOrdinaryLikeKeepsFullStatusRequest() throws Exception{
		service.onStartCommand(command("favorite", "nbw_123"), 0, 1);
		NBWStatusInteraction.Result result=new NBWStatusInteraction.Result(); result.id="nbw_123"; result.favourited=true;
		success(last(), result); assertEquals(0, running()); assertTrue(events.counters.isEmpty());
		service.onStartCommand(command("favorite", "p_1"), 0, 2); assertTrue(last() instanceof SetStatusFavorited);
		Status local=status("p_1", null); local.favourited=true; success(last(), local);
		assertEquals(0, running()); assertEquals(1, events.counters.size()); assertEquals("p_1", events.counters.get(0).id);
	}

	@Test public void lightweightFailureRestoresNotificationActionAndNeverPublishesSuccess() throws Exception{
		postNotification(); PendingIntent previous=active().actions[1].actionIntent;
		service.onStartCommand(command("favorite", "nbw_123"), 0, 1);
		assertNull(active().actions[1].actionIntent);
		callback(last()).onError(new org.joinmastodon.android.api.MastodonErrorResponse("Permission denied", 403, null));
		assertEquals(0, running()); assertTrue(events.counters.isEmpty());
		assertEquals(previous, active().actions[1].actionIntent); assertEquals(service.getString(R.string.button_favorite), active().actions[1].title);
	}

	@Test public void quickReplyPreservesParentAndReportsFailedSyncButSyncedReplyNeedsNoNewModel() throws Exception{
		for(String state:List.of("failed", "unsupported", "synced")){
			ShadowToast.reset(); Intent intent=command("reply", "nbw_123_456");
			intent.putExtra("visibility", StatusPrivacy.PUBLIC.toString()); intent.putExtra("replyPrefix", "@offline ");
			RemoteInput input=new RemoteInput.Builder("replyText").build(); Bundle results=new Bundle(); results.putCharSequence("replyText", "reply body");
			RemoteInput.addResultsToIntent(new RemoteInput[]{input}, intent, results);
			service.onStartCommand(intent, 0, 1); assertTrue(last() instanceof CreateStatus);
			Buffer buffer=new Buffer(); last().getRequestBody().writeTo(buffer); JsonObject body=JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
			assertEquals("nbw_123_456", body.get("in_reply_to_id").getAsString()); assertEquals("@offline reply body", body.get("status").getAsString());
			assertFalse(body.has("nbw_fid")); assertFalse(body.has("geo_province"));
			Status result=status("c_"+state, "nbw_123_456"); result.nbwSync=new Status.NBWSync(); result.nbwSync.state=state;
			success(last(), result); assertEquals(0, running());
			assertEquals(ACCOUNT, events.created.get(events.created.size()-1).accountID);
			if("synced".equals(state)) assertNull(ShadowToast.getTextOfLatestToast());
			else assertEquals(service.getString(R.string.compose_nbw_reply_local_only), ShadowToast.getTextOfLatestToast());
		}
		assertEquals(3, events.created.size());
	}

	private Intent command(String action, String id){
		return new Intent(service, NotificationActionHandlerService.class).putExtra("action", action).putExtra("account", ACCOUNT).putExtra("post", id).putExtra("notificationTag", TAG);
	}
	private void postNotification(){
		PendingIntent action=PendingIntent.getService(service, 1, command("favorite", "nbw_123"), PendingIntent.FLAG_IMMUTABLE);
		Notification.Builder builder=new Notification.Builder(service, CHANNEL).setSmallIcon(R.drawable.ic_ntf_logo).setContentTitle("Offline mention");
		for(int i=0;i<3;i++) builder.addAction(new Notification.Action.Builder(R.drawable.ic_star_24px, service.getString(R.string.button_favorite), action).build());
		notifications.notify(TAG, PushNotificationReceiver.NOTIFICATION_ID, builder.build());
	}
	private Notification active(){ return notifications.getActiveNotifications()[0].getNotification(); }
	private static Status status(String id, String parent){
		Status result=Status.ofFake(id, "Unmodified body", Instant.EPOCH); result.uri="https://offline.example.test/statuses/"+id;
		result.account=new Account(); result.account.id="42"; result.inReplyToId=parent; result.favouritesCount=7; return result;
	}
	private static MastodonAPIRequest<?> last(){ return LocalRequests.sent.get(LocalRequests.sent.size()-1); }
	private int running() throws Exception{ return field(NotificationActionHandlerService.class, "runningRequestCount").getInt(service); }
	@SuppressWarnings("unchecked") private static Callback<Object> callback(MastodonAPIRequest<?> request) throws Exception{ return (Callback<Object>)field(APIRequest.class, "callback").get(request); }
	private static void success(MastodonAPIRequest<?> request, Object result) throws Exception{ callback(request).onSuccess(result); ShadowLooper.idleMainLooper(); }
	private static Field field(Class<?> type, String name) throws Exception{ Field field=type.getDeclaredField(name); field.setAccessible(true); return field; }
	private static Response response(){ return new Response.Builder().request(new Request.Builder().url("https://offline.example.test/api/v1/statuses/nbw_123/favourite").build()).protocol(Protocol.HTTP_1_1).code(200).message("OK").build(); }
}
