package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.requests.statuses.CreateStatus;
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
import java.net.InetAddress;
import java.net.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Real ordinary CreateStatus/controller/Gson/model validation over loopback HTTP only.
 * Only the ordinary client's destination is rerouted; callbacks and responses are not mocked.
 * Credentials are synthetic and in memory. This does not test Compose gates, NBW replies,
 * TLS, or a real backend. API 26 covers every branch; API 35 repeats the full success contract.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=26)
public class CreateStatusHttpTest {
    private static final String HOST="offline.example.test";
    private static final String OFFLINE_TOKEN="offline-create-status-token";
    private static final String IDEMPOTENCY_KEY="9ecc69d3-ff09-4698-9de9-07813bdbb28f";
    private static final String COMMENT="离线评论 & HTTP";
    private static final String CREATED_ID="c_789";
    private static final String STATUS_JSON="""
        {
          "id":"c_789",
          "uri":"https://offline.example.test/statuses/c_789",
          "url":"https://offline.example.test/statuses/c_789",
          "created_at":"2026-10-03T00:00:00.000Z",
          "account":{
            "id":"42", "username":"offline-user", "acct":"offline-user",
            "display_name":"Offline User", "url":"https://offline.example.test/@offline-user",
            "avatar":"https://offline.example.test/avatar.png",
            "avatar_static":"https://offline.example.test/avatar.png",
            "header":"https://offline.example.test/header.png",
            "header_static":"https://offline.example.test/header.png",
            "note":"", "created_at":"2026-10-03T00:00:00.000Z",
            "emojis":[], "fields":[], "roles":[], "bot":false, "locked":false,
            "discoverable":true, "followers_count":0, "following_count":0, "statuses_count":1,
            "source":{"note":"", "fields":[], "privacy":"public", "sensitive":false, "language":"zh"}
          },
          "content":"<p>离线评论 &amp; HTTP</p>", "text":"离线评论 & HTTP",
          "visibility":"public", "language":"zh", "sensitive":false,
          "mental_crisis":false, "spoiler_text":"",
          "media_attachments":[], "mentions":[], "tags":[], "emojis":[],
          "reblogs_count":0, "favourites_count":0, "replies_count":0, "quotes_count":0,
          "bookmarks_count":0, "shares_count":0, "views_count":0, "heat":0,
          "favourited":false, "reblogged":false, "muted":false, "bookmarked":false, "pinned":false,
          "in_reply_to_id":"%s", "in_reply_to_account_id":"42", "reblog":null,
          "application":{"name":"ABDL Space", "website":"https://offline.example.test"},
          "geo_location":null, "card":null, "poll":null, "edited_at":null
        }
        """;

    private MockWebServer server;
    private OkHttpClient originalClient, loopbackClient;
    private MastodonAPIController controller;

    private static Field field(Class<?> type, String name) throws Exception {
        Field field=type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Before public void setup() throws Exception {
        server=new MockWebServer();
        server.start(InetAddress.getByName("127.0.0.1"), 0);
        MastodonApp.context=RuntimeEnvironment.getApplication();
        me.grishka.appkit.utils.V.setApplicationContext(MastodonApp.context);
        Field clientField=field(MastodonAPIController.class, "httpClient");
        originalClient=(OkHttpClient)clientField.get(null);
        loopbackClient=new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(chain->{
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
        try {
            if(originalClient!=null) field(MastodonAPIController.class, "httpClient").set(null, originalClient);
        } finally {
            if(loopbackClient!=null) {
                loopbackClient.dispatcher().cancelAll();
                loopbackClient.connectionPool().evictAll();
                loopbackClient.dispatcher().executorService().shutdown();
            }
            if(server!=null) server.shutdown();
        }
    }

    private static final class Outcome implements Callback<Status> {
        final AtomicInteger successCalls=new AtomicInteger();
        final AtomicInteger errorCalls=new AtomicInteger();
        volatile Status status;
        volatile ErrorResponse error;

        @Override public void onSuccess(Status result) {
            status=result;
            successCalls.incrementAndGet();
        }

        @Override public void onError(ErrorResponse failure) {
            error=failure;
            errorCalls.incrementAndGet();
        }
    }

    private Outcome submit(String replyTo, MockResponse response) throws Exception {
        CreateStatus.Request body=new CreateStatus.Request();
        body.status=COMMENT;
        body.inReplyToId=replyTo;
        body.visibility=StatusPrivacy.PUBLIC;
        body.language="zh";
        body.spoilerText="";
        MastodonAPIRequest<Status> request=new CreateStatus(body, IDEMPOTENCY_KEY);
        // Keep the constructor's Idempotency-Key while testing mandatory native header precedence.
        request.headers.put("user-agent", "Mozilla/5.0 caller");
        request.headers.put("USER-AGENT", "OtherNative/999");
        request.headers.put("x-app-version-code", "-1");
        request.headers.put("X-APP-VERSION-CODE", "999999");
        request.headers.put("X-Caller-Header", "preserved");
        field(MastodonAPIRequest.class, "domain").set(request, HOST);
        Token token=new Token();
        token.accessToken=OFFLINE_TOKEN;
        request.token=token;
        // Fail before submitting if this ordinary request ever selects an un-rerouted client.
        assertFalse(request.isSensitiveRequest());
        assertFalse(request.requiresSystemTrust());
        Outcome outcome=new Outcome();
        request.setCallback(outcome);
        server.enqueue(response.setHeader("Content-Type", "application/json"));
        controller.submitRequest(request);

        RecordedRequest recorded=server.takeRequest(15, TimeUnit.SECONDS);
        assertNotNull("Controller must send an actual HTTP request", recorded);
        assertEquals("POST", recorded.getMethod());
        assertEquals("/api/v1/statuses", recorded.getPath());
        assertEquals("application/json", recorded.getHeader("Content-Type"));
        assertEquals(List.of("Bearer "+OFFLINE_TOKEN), recorded.getHeaders().values("Authorization"));
        assertEquals(List.of("MastodonAndroid/"+BuildConfig.VERSION_NAME), recorded.getHeaders().values("User-Agent"));
        assertEquals(List.of(String.valueOf(BuildConfig.VERSION_CODE)), recorded.getHeaders().values("X-App-Version-Code"));
        assertEquals(List.of(IDEMPOTENCY_KEY), recorded.getHeaders().values("Idempotency-Key"));
        assertEquals("preserved", recorded.getHeader("X-Caller-Header"));
        JsonObject sent=JsonParser.parseString(recorded.getBody().readUtf8()).getAsJsonObject();
        assertTrue("Reply IDs must stay JSON strings", sent.getAsJsonPrimitive("in_reply_to_id").isString());
        assertEquals(replyTo, sent.get("in_reply_to_id").getAsString());
        assertEquals(COMMENT, sent.get("status").getAsString());
        assertEquals("public", sent.get("visibility").getAsString());
        assertEquals("zh", sent.get("language").getAsString());
        assertEquals("", sent.get("spoiler_text").getAsString());
        assertFalse(sent.get("sensitive").getAsBoolean());
        assertFalse(sent.get("mental_crisis").getAsBoolean());
        assertFalse("Ordinary replies must omit nbw_fid, not send null", sent.has("nbw_fid"));
        for(String key:sent.keySet()) assertFalse("Ordinary replies must omit geo fields: "+key, key.startsWith("geo"));

        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(outcome.successCalls.get()+outcome.errorCalls.get()==0 && System.nanoTime()<deadline) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(10);
        }
        ShadowLooper.idleMainLooper();
        assertEquals("Exactly one response callback must complete", 1, outcome.successCalls.get()+outcome.errorCalls.get());
        assertEquals("A reply must issue exactly one request", 1, server.getRequestCount());
        return outcome;
    }

    private void assertReplySuccess(String replyTo) throws Exception {
        Outcome outcome=submit(replyTo, new MockResponse().setResponseCode(201).setBody(STATUS_JSON.formatted(replyTo)));
        assertEquals(1, outcome.successCalls.get());
        assertEquals(0, outcome.errorCalls.get());
        assertNull(outcome.error);
        Status status=outcome.status;
        assertNotNull("Success must return a single validated Status", status);
        assertEquals(CREATED_ID, status.id);
        assertEquals(replyTo, status.inReplyToId);
        assertEquals("42", status.inReplyToAccountId);
        assertEquals("42", status.getAccountID());
        assertEquals(Instant.parse("2026-10-03T00:00:00Z"), status.createdAt);
        assertEquals(StatusPrivacy.PUBLIC, status.visibility);
        assertEquals(COMMENT, status.text);
        assertEquals("<p>离线评论 &amp; HTTP</p>", status.content);
        assertTrue(status.mediaAttachments.isEmpty());
        assertTrue(status.mentions.isEmpty());
        assertTrue(status.tags.isEmpty());
        assertTrue(status.emojis.isEmpty());
        // These values are populated by the real Status and Account postprocess methods.
        assertTrue(status.revealedSpoilers.contains(Status.SpoilerType.CONTENT_WARNING));
        assertNotNull(status.reactions);
        assertEquals("offline-user@"+HOST, status.account.fqn);
    }

    private static MastodonErrorResponse assertFailure(Outcome outcome, int httpStatus) {
        assertEquals("Invalid/error responses must never report success", 0, outcome.successCalls.get());
        assertEquals(1, outcome.errorCalls.get());
        assertNull(outcome.status);
        assertTrue("Callback must retain a Mastodon HTTP error", outcome.error instanceof MastodonErrorResponse);
        MastodonErrorResponse error=(MastodonErrorResponse)outcome.error;
        assertEquals(httpStatus, error.httpStatus);
        assertNotNull(error.error);
        assertFalse(error.error.isEmpty());
        return error;
    }

    private void assertHttpError(int httpStatus, String text) throws Exception {
        JsonObject response=new JsonObject();
        response.addProperty("error", text);
        MastodonErrorResponse error=assertFailure(submit("p_123", new MockResponse()
            .setResponseCode(httpStatus).setBody(response.toString())), httpStatus);
        assertEquals(text, error.error);
        assertNull(error.underlyingException);
    }

    @Test @Config(sdk={26, 35})
    public void postReplyPreservesStringIdWireContractAndParsesSingleStatus() throws Exception {
        assertReplySuccess("p_123");
    }

    @Test public void commentReplyPreservesStringIdWireContractAndParsesSingleStatus() throws Exception {
        assertReplySuccess("c_456");
    }

    @Test public void unauthorizedReplyPreservesHttpStatusAndServerText() throws Exception {
        assertHttpError(401, "Authentication required: offline session expired");
    }

    @Test public void forbiddenReplyPreservesHttpStatusAndServerText() throws Exception {
        assertHttpError(403, "评论权限不足");
    }

    @Test public void unprocessableReplyPreservesHttpStatusTextAndDetails() throws Exception {
        MastodonErrorResponse error=assertFailure(submit("c_456", new MockResponse().setResponseCode(422).setBody("""
            {"error":"评论校验失败", "details":{"in_reply_to_id":[{"error":"invalid", "description":"父评论不存在"}]}}
            """)), 422);
        assertEquals("评论校验失败", error.error);
        assertNull(error.underlyingException);
        assertTrue(error instanceof MastodonDetailedErrorResponse);
        List<MastodonDetailedErrorResponse.FieldError> details=((MastodonDetailedErrorResponse)error).detailedErrors.get("in_reply_to_id");
        assertEquals(1, details.size());
        assertEquals("invalid", details.get(0).error);
        assertEquals("父评论不存在", details.get(0).description);
    }

    @Test public void malformedSuccessfulJsonNeverReportsSuccess() throws Exception {
        MastodonErrorResponse error=assertFailure(submit("p_123", new MockResponse().setBody("{\"id\":")), 200);
        assertTrue(error.underlyingException instanceof JsonSyntaxException);
    }

    @Test public void incompleteSuccessfulStatusIsRejectedByRealValidation() throws Exception {
        JsonObject response=JsonParser.parseString(STATUS_JSON.formatted("c_456")).getAsJsonObject();
        response.remove("id");
        MastodonErrorResponse error=assertFailure(submit("c_456", new MockResponse().setBody(response.toString())), 200);
        assertTrue(error.underlyingException instanceof ObjectValidationException);
        assertTrue(error.error.contains("Required field 'id'"));
    }

    @Test public void nullSuccessfulStatusNeverReportsSuccess() throws Exception {
        MastodonErrorResponse error=assertFailure(submit("p_123", new MockResponse().setBody("null")), 200);
        assertTrue(error.underlyingException instanceof ObjectValidationException);
        assertEquals("Server response is empty", error.error);
    }
}
