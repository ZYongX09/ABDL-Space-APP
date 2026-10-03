package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import android.net.Uri;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.requests.timelines.*;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.model.Token;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** Real timeline constructors/controller/Gson/postprocess on loopback HTTP, including API 26.
 * Only the ordinary test client's URL is rerouted; no controller or response methods are mocked.
 * This does not exercise TLS, the system trust store, ART rendering, or a real backend.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26, 28})
public class AppVersionReportingHttpTest {
    private MockWebServer server;
    private OkHttpClient originalClient, loopbackClient;
    private MastodonAPIController controller;
    private static final String HOST="offline.example.test";
    private static final String NOTICE_ID="app-update-required";
    private static final String NOTICE="""
        {
          "id":"app-update-required",
          "uri":"https://abdl-space.top/app#update-required",
          "url":"https://abdl-space.top/app",
          "created_at":"2026-10-03T00:00:00.000Z",
          "account":{
            "id":"-1", "username":"app-update", "acct":"app-update",
            "display_name":"App 更新提醒", "url":"https://abdl-space.top/app", "uri":"https://abdl-space.top/app",
            "avatar":"https://img.abdl-space.top/file/system/1781439303787_play_store_512.png",
            "avatar_static":"https://img.abdl-space.top/file/system/1781439303787_play_store_512.png",
            "header":"https://img.abdl-space.top/file/system/1781439303787_play_store_512.png",
            "header_static":"https://img.abdl-space.top/file/system/1781439303787_play_store_512.png",
            "note":"", "created_at":"2026-10-03T00:00:00.000Z",
            "emojis":[], "fields":[], "roles":[], "bot":true,
            "locked":false, "discoverable":true, "group":false,
            "followers_count":0, "following_count":0, "statuses_count":0,
            "last_status_at":null, "last_status_province":null, "badge":null, "baby_verification":null,
            "hide_collections":false, "noindex":false, "nbw_username":null, "verified":false,
            "source":{"note":"", "fields":[], "privacy":"public", "sensitive":false, "language":"zh"}
          },
          "content":"<p>当前 App 版本已停止支持，请更新到最新版本后继续使用。</p><p><a href='https://abdl-space.top/app' rel='nofollow noopener noreferrer' target='_blank'>下载最新版本 App</a></p>",
          "text":"当前 App 版本已停止支持，请更新到最新版本后继续使用。\\nhttps://abdl-space.top/app",
          "visibility":"public", "language":"zh", "sensitive":false, "mental_crisis":false, "spoiler_text":"",
          "media_attachments":[], "mentions":[], "tags":[], "emojis":[],
          "reblogs_count":0, "favourites_count":0, "replies_count":0, "bookmarks_count":0,
          "shares_count":0, "views_count":0, "heat":0, "favourited":false, "reblogged":false,
          "muted":false, "bookmarked":false, "pinned":false,
          "in_reply_to_id":null, "in_reply_to_account_id":null, "reblog":null,
          "application":{"name":"ABDL Space", "website":"https://abdl-space.top"},
          "geo_location":null, "card":null, "poll":null, "edited_at":null
        }
        """;

    private static Field field(Class<?> type, String name) throws Exception {
        Field field=type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Before public void setup() throws Exception {
        server=new MockWebServer();
        server.start();
        MastodonApp.context=RuntimeEnvironment.getApplication();
        me.grishka.appkit.utils.V.setApplicationContext(MastodonApp.context);
        Field clientField=field(MastodonAPIController.class, "httpClient");
        originalClient=(OkHttpClient)clientField.get(null);
        loopbackClient=new OkHttpClient.Builder().addInterceptor(chain->{
            okhttp3.Request request=chain.request();
            if(!HOST.equals(request.url().host())) throw new IOException("Unexpected outbound host");
            String query=request.url().encodedQuery();
            return chain.proceed(request.newBuilder()
                .url(server.url(request.url().encodedPath()+(query==null ? "" : "?"+query))).build());
        }).build();
        clientField.set(null, loopbackClient);
        controller=new MastodonAPIController(null);
    }

    @After public void cleanup() throws Exception {
        field(MastodonAPIController.class, "httpClient").set(null, originalClient);
        server.shutdown();
        loopbackClient.connectionPool().evictAll();
        loopbackClient.dispatcher().executorService().shutdown();
    }

    private List<Status> submit(MastodonAPIRequest<List<Status>> request, MockResponse response,
                                boolean conflict, Token token, boolean expectError) throws Exception {
        field(MastodonAPIRequest.class, "domain").set(request, HOST);
        request.token=token;
        if(conflict) request.headers=Map.of(
            "user-agent", "Mozilla/5.0 caller", "USER-AGENT", "OtherNative/999",
            "x-app-version-code", "-1", "X-APP-VERSION-CODE", "999999",
            "X-Caller-Header", "preserved");
        AtomicBoolean done=new AtomicBoolean();
        AtomicReference<List<Status>> result=new AtomicReference<>();
        AtomicReference<ErrorResponse> error=new AtomicReference<>();
        request.setCallback(new Callback<>() {
            @Override public void onSuccess(List<Status> statuses) { result.set(statuses); done.set(true); }
            @Override public void onError(ErrorResponse failure) { error.set(failure); done.set(true); }
        });
        Uri expected=request.getURL();
        server.enqueue(response.setHeader("Content-Type", "application/json"));
        controller.submitRequest(request);
        RecordedRequest recorded=server.takeRequest(15, TimeUnit.SECONDS);
        assertNotNull("Controller must send request", recorded);
        assertEquals("GET", recorded.getMethod());
        assertEquals(expected.getEncodedPath(), recorded.getRequestUrl().encodedPath());
        assertEquals(expected.getEncodedQuery(), recorded.getRequestUrl().encodedQuery());
        assertEquals(List.of("MastodonAndroid/"+BuildConfig.VERSION_NAME), recorded.getHeaders().values("User-Agent"));
        assertEquals(List.of(String.valueOf(BuildConfig.VERSION_CODE)), recorded.getHeaders().values("X-App-Version-Code"));
        assertEquals(30, BuildConfig.VERSION_CODE);
        assertTrue(recorded.getHeader("User-Agent").matches("^MastodonAndroid/.*"));
        if(conflict) assertEquals("preserved", recorded.getHeader("X-Caller-Header"));
        AccountSession session=(AccountSession)field(MastodonAPIController.class, "session").get(controller);
        Token effectiveToken=session!=null ? session.token : token;
        if(effectiveToken!=null) assertEquals("Bearer "+effectiveToken.accessToken, recorded.getHeader("Authorization"));
        else assertNull(recorded.getHeader("Authorization"));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!done.get() && System.nanoTime()<deadline) { ShadowLooper.idleMainLooper(); Thread.sleep(10); }
        assertTrue("Response callback must complete", done.get());
        if(expectError) { assertNotNull(error.get()); assertNull(result.get()); }
        else { assertNull("Response must parse and validate: "+error.get(), error.get()); assertNotNull(result.get()); }
        return result.get();
    }

    private void verify(MastodonAPIRequest<List<Status>> request) throws Exception {
        Token token=new Token(); token.accessToken="offline-token";
        assertTrue(submit(request, new MockResponse().setBody("[]"), true, token, false).isEmpty());
    }

    @Test public void home() throws Exception { verify(new GetHomeTimeline("99", "1", 20, "2")); }
    @Test public void all() throws Exception { verify(new GetAllTimeline("99", 20)); }
    @Test public void publicLocal() throws Exception { verify(new GetPublicTimeline(true, false, "99", "1", 20, "2", "all")); }
    @Test public void publicRemote() throws Exception { verify(new GetPublicTimeline(false, true, "99", "1", 20, "2", null)); }
    @Test public void publicFederated() throws Exception { verify(new GetPublicTimeline(false, false, null, null, 20, null, null)); }
    @Test public void geo() throws Exception { verify(new GetGeoTimeline("浙江", "杭州", "西湖", "99", 20)); }
    @Test public void popular() throws Exception { verify(new GetPopularTimeline(20, 20)); }
    @Test public void nbw() throws Exception { verify(new GetNBWTimeline("99", 20, "3", "lastpost")); }
    @Test public void bubble() throws Exception { verify(new GetBubbleTimeline("99", 20, "all")); }
    @Test public void tag() throws Exception { verify(new GetHashtagTimeline("test", "99", "1", 20)); }
    @Test public void listWithReplyVisibility() throws Exception { verify(new GetListTimeline("7", "99", "1", 20, "2", "all")); }
    @Test public void listLegacyConstructor() throws Exception { verify(new GetListTimeline("7", "99", "1", 20, "2")); }
    @Test public void unauthenticatedRequestStillHasIdentity() throws Exception {
        submit(new GetPublicTimeline(true, false, null, null, 20, null, null), new MockResponse().setBody("[]"), false, null, false);
    }
    @Test public void sessionBearerTakesPrecedenceOverRequestToken() throws Exception {
        var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true);
        AccountSession session=constructor.newInstance(); session.token=new Token(); session.token.accessToken="session-token";
        controller=new MastodonAPIController(session);
        session.domain=HOST;
        session.self=new org.joinmastodon.android.model.Account(); session.self.id="42";
        Token fallback=new Token(); fallback.accessToken="request-fallback";
        submit(new GetHomeTimeline(null, null, 20, null), new MockResponse().setBody("[]"), true, fallback, false);
    }

    @Test public void syntheticNoticeParsesAcrossEveryTimelineAndKeepsFixedStringId() throws Exception {
        List<MastodonAPIRequest<List<Status>>> requests=List.of(
            new GetHomeTimeline(null, null, 20, null), new GetAllTimeline(null, 20),
            new GetPublicTimeline(false, false, null, null, 20, null, null),
            new GetGeoTimeline("浙江", null, null, null, 20), new GetPopularTimeline(0, 20),
            new GetNBWTimeline(null, 20, null, null), new GetBubbleTimeline(null, 20, null),
            new GetHashtagTimeline("test", null, null, 20),
            new GetListTimeline("7", null, null, 20, null, null), new GetListTimeline("7", null, null, 20, null));
        for(MastodonAPIRequest<List<Status>> request:requests) {
            List<Status> statuses=submit(request, new MockResponse().setBody("["+NOTICE+"]"), false, null, false);
            assertEquals(1, statuses.size());
            Status status=statuses.get(0);
            assertEquals(NOTICE_ID, status.getID());
            assertEquals("-1", status.getAccountID());
            // Legacy ProfileFragment's send-message action parses account IDs as long.
            assertEquals(-1L, Long.parseLong(status.account.id));
            assertEquals(StatusPrivacy.PUBLIC, status.visibility);
            assertTrue(status.getStrippedText().contains("当前 App 版本已停止支持"));
            assertEquals("https://abdl-space.top/app", status.url);
            assertTrue(status.mediaAttachments.isEmpty());
        }
    }
    @Test public void syntheticNoticeBuildsRealDisplayItemsAndStringIdComparator() throws Exception {
        Status status=submit(new GetAllTimeline(null, 20), new MockResponse().setBody("["+NOTICE+"]"), false, null, false).get(0);
        var items=org.joinmastodon.android.ui.displayitems.StatusDisplayItem.buildItems(
            RuntimeEnvironment.getApplication(), status, "offline.example.test_42", status, Map.of(status.account.id, status.account), 0);
        assertEquals(List.of(
            org.joinmastodon.android.ui.displayitems.StatusDisplayItem.Type.HEADER,
            org.joinmastodon.android.ui.displayitems.StatusDisplayItem.Type.TEXT,
            org.joinmastodon.android.ui.displayitems.StatusDisplayItem.Type.FOOTER),
            items.stream().map(item->item.getType()).collect(java.util.stream.Collectors.toList()));
        for(var item:items) assertEquals(NOTICE_ID, item.parentID);
        assertEquals(0, org.joinmastodon.android.utils.ObjectIdComparator.INSTANCE.compare(NOTICE_ID, NOTICE_ID));
        assertTrue(org.joinmastodon.android.utils.ObjectIdComparator.INSTANCE.compare(NOTICE_ID, "99")>0);
        java.util.Set<String> knownIds=new java.util.HashSet<>();
        assertTrue(knownIds.add(status.id)); assertFalse(knownIds.add(status.clone().id));
    }
    @Test public void allTimelineUsesArrayAndLinkCursorNotSyntheticId() throws Exception {
        GetAllTimeline request=new GetAllTimeline(null, 20);
        submit(request, new MockResponse().setBody("["+NOTICE+"]")
            .setHeader("Link", "<https://offline.example.test/api/v1/timelines/all?max_id=real-cursor&limit=20>; rel=\"next\""), false, null, false);
        assertEquals("real-cursor", request.getNextMaxID());
        GetAllTimeline onlyNotice=new GetAllTimeline(null, 20);
        submit(onlyNotice, new MockResponse().setBody("["+NOTICE+"]"), false, null, false);
        assertNull(onlyNotice.getNextMaxID());
    }
    @Test public void allTimelineRejectsObjectEnvelope() throws Exception {
        submit(new GetAllTimeline(null, 20), new MockResponse().setBody("{\"statuses\":["+NOTICE+"],\"next_max_id\":\"99\"}"), false, null, true);
    }
}
