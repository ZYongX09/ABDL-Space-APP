package org.joinmastodon.android.fragments.albums;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.Nav;
import me.grishka.appkit.utils.V;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Focused album report page behavior: toolbar entry, submit gating, server errors, draft, cancel. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class, shadows={AlbumReportUiBehaviorTest.RecordingNav.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AlbumReportUiBehaviorTest{
	private AccountSessionManager manager;
	private Map<String, AccountSession> sessions;
	private AccountSession session;
	private String accountId, previousActive;
	private OkHttpClient sensitiveClient;
	private Object originalInterceptors;
	private final List<ActivityController<FragmentStackActivity>> hosts=new CopyOnWriteArrayList<>();
	private final List<String> reportBodies=new CopyOnWriteArrayList<>();
	private final AtomicInteger reportCalls=new AtomicInteger();
	private volatile Mode mode=Mode.SUCCESS;
	private volatile boolean ownerAlbum;
	private volatile CountDownLatch releaseReport;
	private static final String ALBUM_ID="album";
	private static Field field(Class<?> type, String name) throws Exception{ Field field=type.getDeclaredField(name); field.setAccessible(true); return field; }

	private enum Mode{ SUCCESS, DUPLICATE, RATE_LIMITED, BLOCKED }

	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context); RecordingNav.reset();
		manager=AccountSessionManager.getInstance(); sessions=(Map<String, AccountSession>)field(AccountSessionManager.class, "sessions").get(manager); previousActive=manager.getLastActiveAccountID();
		var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true); session=constructor.newInstance();
		session.domain="album-report-offline.example.test"; session.self=new Account(); session.self.id="42"; session.token=new Token(); session.token.accessToken="report-fixture-token";
		accountId=session.getID(); sessions.put(accountId, session); field(AccountSessionManager.class, "lastActiveAccountID").set(manager, accountId);
		sensitiveClient=(OkHttpClient)field(MastodonAPIController.class, "sensitiveHttpClient").get(null); originalInterceptors=field(OkHttpClient.class, "interceptors").get(sensitiveClient);
		field(OkHttpClient.class, "interceptors").set(sensitiveClient, List.of((Interceptor)chain->respond(chain.request())));
		mode=Mode.SUCCESS; ownerAlbum=false; reportCalls.set(0); reportBodies.clear(); releaseReport=null;
	}
	@After public void cleanup() throws Exception{
		CountDownLatch release=releaseReport; if(release!=null) release.countDown();
		for(ActivityController<FragmentStackActivity> host:hosts) host.pause().stop().destroy();
		if(sensitiveClient!=null && originalInterceptors!=null) field(OkHttpClient.class, "interceptors").set(sensitiveClient, originalInterceptors);
		if(sessions!=null) sessions.remove(accountId); if(manager!=null) field(AccountSessionManager.class, "lastActiveAccountID").set(manager, previousActive);
	}

	private Response respond(Request request) throws IOException{
		if(!session.domain.equals(request.url().host())) throw new IOException("Unexpected outbound host: "+request.url().host());
		String path=request.url().encodedPath();
		if("POST".equals(request.method()) && path.endsWith("/albums/"+ALBUM_ID+"/report")){
			okio.Buffer buffer=new okio.Buffer(); request.body().writeTo(buffer); reportBodies.add(buffer.readUtf8()); reportCalls.incrementAndGet();
			CountDownLatch release=releaseReport;
			if(release!=null){ try{ if(!release.await(5, TimeUnit.SECONDS)) throw new IOException("Local report fixture timed out"); }catch(InterruptedException canceled){ Thread.currentThread().interrupt(); throw new IOException("Canceled"); } }
			return switch(mode){
				case SUCCESS -> json(request, 201, successBody(lastBody()));
				case DUPLICATE -> json(request, 409, "{\"error\":\"已有一个进行中的相册举报\",\"code\":\"duplicate_open\"}");
				case RATE_LIMITED -> json(request, 429, "{\"error\":\"举报提交过于频繁，请稍后再试\",\"code\":\"rate_limited\"}");
				case BLOCKED -> json(request, 201, successBody(lastBody()));
			};
		}
		if("GET".equals(request.method()) && path.endsWith("/albums/"+ALBUM_ID)) return json(request, 200, "{\"album\":"+albumJson()+"}");
		if("GET".equals(request.method()) && path.endsWith("/photos")) return json(request, 200, "{\"photos\":[],\"has_more\":false}");
		throw new IOException("Unexpected fixture route: "+request.method()+" "+path);
	}

	private String lastBody(){ return reportBodies.isEmpty() ? "{}" : reportBodies.get(reportBodies.size()-1); }

	private String successBody(String requestBody){
		com.google.gson.JsonObject body=MastodonAPIController.gson.fromJson(requestBody, com.google.gson.JsonObject.class);
		com.google.gson.JsonObject report=new com.google.gson.JsonObject();
		report.addProperty("id", "report1"); report.addProperty("album_id", ALBUM_ID); report.addProperty("reason", body.get("reason").getAsString());
		if(body.has("detail")) report.addProperty("detail", body.get("detail").getAsString());
		report.addProperty("created_at", 1791158401L); report.addProperty("status", "open");
		return "{\"report\":"+MastodonAPIController.gson.toJson(report)+"}";
	}

	private String albumJson(){
		return "{\"id\":\""+ALBUM_ID+"\",\"owner_id\":42,\"name\":\"宝宝相册\",\"visibility\":\"private\",\"is_default\":false,\"photo_count\":0,\"can_upload\":true,\"is_owner\":"+ownerAlbum+"}";
	}

	private static Response json(Request request, int code, String body){
		return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Local fixture").body(ResponseBody.create(MediaType.get("application/json"), body)).build();
	}

	private static void await(BooleanSupplier finished) throws Exception{
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
		while(!finished.getAsBoolean() && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(5); }
		ShadowLooper.idleMainLooper(); assertTrue("Offline operation must settle", finished.getAsBoolean());
	}

	private ActivityController<FragmentStackActivity> host(Fragment fragment){
		ActivityController<FragmentStackActivity> lifecycle=Robolectric.buildActivity(FragmentStackActivity.class);
		lifecycle.get().setTheme(R.style.Theme_Mastodon_Light); lifecycle.create().start().resume().visible();
		lifecycle.get().showFragment(fragment); lifecycle.get().getFragmentManager().executePendingTransactions();
		hosts.add(lifecycle); return lifecycle;
	}

	private AlbumReportFragment openReportPage(){
		AlbumReportFragment fragment=new AlbumReportFragment(); Bundle args=new Bundle(); args.putString("account", accountId); args.putString("albumId", ALBUM_ID); fragment.setArguments(args);
		host(fragment); return fragment;
	}

	private android.widget.Toolbar toolbar(Fragment fragment){ return fragment.getView().findViewById(R.id.toolbar); }

	private MenuItem reportItem(Fragment fragment){ return toolbar(fragment).getMenu().findItem(R.id.album_report); }

	@Test public void reportIconVisibleForVisitorOnlyAndTintedAsError() throws Exception{
		AlbumDetailFragment fragment=new AlbumDetailFragment(); Bundle args=new Bundle(); args.putString("account", accountId); args.putString("albumId", ALBUM_ID); fragment.setArguments(args);
		host(fragment);
		fragment.refresh();
		// The report action is gated on a settled page (busy/pageLoading); wait for the whole reload, not just fresh.
		await(()->{ try{
			return (boolean)field(AlbumDetailFragment.class, "fresh").get(fragment)
					&& !(boolean)field(AlbumDetailFragment.class, "pageLoading").get(fragment)
					&& !field(AlbumDetailFragment.class, "busy").getBoolean(fragment);
		}catch(Exception invalid){ throw new RuntimeException(invalid); } });
		MenuItem item=reportItem(fragment); assertNotNull("Menu item must exist", item);
		await(item::isVisible);
		assertEquals(UiUtils.getThemeColor(fragment.getActivity(), R.attr.colorM3Error), item.getIconTintList().getDefaultColor());
		assertEquals(fragment.getString(R.string.album_report), item.getContentDescription());
		// Tapping the visible entry opens the album report page, not the post report flow.
		assertTrue(fragment.onOptionsItemSelected(item));
		assertEquals(AlbumReportFragment.class, RecordingNav.destination);
		assertEquals(accountId, RecordingNav.arguments.getString("account")); assertEquals(ALBUM_ID, RecordingNav.arguments.getString("albumId"));
		// Owners cannot report their own album.
		((org.joinmastodon.android.model.albums.AlbumModels.Album)field(AlbumDetailFragment.class, "album").get(fragment)).isOwner=true;
		fragment.updateReportMenu(); ShadowLooper.idleMainLooper();
		assertFalse(item.isVisible());
		// A session that is no longer active hides the entry even for a visitor.
		((org.joinmastodon.android.model.albums.AlbumModels.Album)field(AlbumDetailFragment.class, "album").get(fragment)).isOwner=false;
		field(AccountSessionManager.class, "lastActiveAccountID").set(manager, "stale-session");
		fragment.updateReportMenu(); ShadowLooper.idleMainLooper();
		assertFalse(item.isVisible());
		field(AccountSessionManager.class, "lastActiveAccountID").set(manager, accountId);
	}

	@Test public void submitDisabledUntilReasonSelectedAndReasonIsSingleChoice() throws Exception{
		AlbumReportFragment fragment=openReportPage();
		Button submit=fragment.getView().findViewById(R.id.album_report_submit);
		assertFalse(submit.isEnabled());
		int rows=((android.view.ViewGroup)fragment.getView().findViewById(R.id.album_report_reasons)).getChildCount();
		assertEquals(fragment.getResources().getStringArray(R.array.album_report_reasons).length, rows);
		android.view.ViewGroup reasons=fragment.getView().findViewById(R.id.album_report_reasons);
		reasons.getChildAt(0).performClick(); ShadowLooper.idleMainLooper();
		assertTrue(submit.isEnabled());
		assertTrue(((RadioButton)reasons.getChildAt(0).findViewById(R.id.album_report_reason_radio)).isChecked());
		// Selecting another reason moves the single selection instead of accumulating.
		reasons.getChildAt(2).performClick(); ShadowLooper.idleMainLooper();
		assertTrue(((RadioButton)reasons.getChildAt(2).findViewById(R.id.album_report_reason_radio)).isChecked());
		assertFalse(((RadioButton)reasons.getChildAt(0).findViewById(R.id.album_report_reason_radio)).isChecked());
	}

	@Test public void draftReasonAndDetailSurviveViewRecreationWithoutPersistence() throws Exception{
		AlbumReportFragment fragment=openReportPage();
		android.view.ViewGroup reasons=fragment.getView().findViewById(R.id.album_report_reasons);
		reasons.getChildAt(1).performClick();
		((EditText)fragment.getView().findViewById(R.id.album_report_detail)).setText("草稿内容");
		Bundle saved=new Bundle(); fragment.onSaveInstanceState(saved);
		assertEquals(1, saved.getInt("reason", -2)); assertEquals("草稿内容", saved.getString("detail"));
		fragment.onDestroyView();
		View recreated=fragment.onCreateContentView(LayoutInflater.from(fragment.getActivity()), null, saved);
		android.view.ViewGroup restoredReasons=recreated.findViewById(R.id.album_report_reasons);
		assertTrue(((RadioButton)restoredReasons.getChildAt(1).findViewById(R.id.album_report_reason_radio)).isChecked());
		assertEquals("草稿内容", ((EditText)recreated.findViewById(R.id.album_report_detail)).getText().toString());
		assertTrue(((Button)recreated.findViewById(R.id.album_report_submit)).isEnabled());
	}

	@Test public void successSendsTrimmedDetailOperationIdThenToastAndFinish() throws Exception{
		AlbumReportFragment fragment=openReportPage();
		android.view.ViewGroup reasons=fragment.getView().findViewById(R.id.album_report_reasons);
		reasons.getChildAt(0).performClick(); // 色情低俗 -> nsfw
		((EditText)fragment.getView().findViewById(R.id.album_report_detail)).setText("  页面内容违规  ");
		mode=Mode.SUCCESS;
		fragment.getView().findViewById(R.id.album_report_submit).performClick();
		await(()->reportCalls.get()==1);
		com.google.gson.JsonObject body=MastodonAPIController.gson.fromJson(reportBodies.get(0), com.google.gson.JsonObject.class);
		assertEquals("nsfw", body.get("reason").getAsString()); assertEquals("页面内容违规", body.get("detail").getAsString());
		assertDoesNotThrow(()->UUID.fromString(body.get("operation_id").getAsString()));
		await(()->RecordingNav.finishCount>0);
		assertEquals(fragment.getString(R.string.album_report_submitted), ShadowToast.getTextOfLatestToast());
		assertTrue(fragment.finished);
	}

	@Test public void duplicateOpenShowsServerMessageAndKeepsDraftEditable() throws Exception{
		AlbumReportFragment fragment=openReportPage();
		((android.view.ViewGroup)fragment.getView().findViewById(R.id.album_report_reasons)).getChildAt(3).performClick();
		mode=Mode.DUPLICATE;
		fragment.getView().findViewById(R.id.album_report_submit).performClick();
		await(()->reportCalls.get()==1);
		await(()->ShadowToast.getLatestToast()!=null && reportCalls.get()==1);
		assertEquals("已有一个进行中的相册举报", ShadowToast.getTextOfLatestToast());
		assertFalse(fragment.finished); assertEquals(0, RecordingNav.finishCount);
		assertTrue(((Button)fragment.getView().findViewById(R.id.album_report_submit)).isEnabled());
	}

	@Test public void rateLimitedShowsServerMessageAndKeepsDraftEditable() throws Exception{
		AlbumReportFragment fragment=openReportPage();
		((android.view.ViewGroup)fragment.getView().findViewById(R.id.album_report_reasons)).getChildAt(4).performClick();
		mode=Mode.RATE_LIMITED;
		fragment.getView().findViewById(R.id.album_report_submit).performClick();
		await(()->reportCalls.get()==1);
		assertEquals("举报提交过于频繁，请稍后再试", ShadowToast.getTextOfLatestToast());
		assertFalse(fragment.finished); assertEquals(0, RecordingNav.finishCount);
		assertTrue(((Button)fragment.getView().findViewById(R.id.album_report_submit)).isEnabled());
	}

	@Test public void leavingWithoutSubmitAndDoubleTapSendNothingExtra() throws Exception{
		// Leaving the page before choosing a reason never touches the network.
		AlbumReportFragment canceled=openReportPage();
		canceled.onDestroyView();
		assertEquals(0, reportCalls.get());
		// A submit already in flight blocks duplicate sends even if the button is clicked again.
		AlbumReportFragment fragment=openReportPage();
		((android.view.ViewGroup)fragment.getView().findViewById(R.id.album_report_reasons)).getChildAt(0).performClick();
		mode=Mode.BLOCKED; releaseReport=new CountDownLatch(1);
		Button submit=fragment.getView().findViewById(R.id.album_report_submit);
		submit.performClick();
		await(()->reportCalls.get()==1);
		assertFalse("Submit must lock while a report is in flight", submit.isEnabled());
		submit.performClick(); ShadowLooper.idleMainLooper();
		assertEquals(1, reportCalls.get());
		releaseReport.countDown();
		await(()->RecordingNav.finishCount>0);
	}

	private static void assertDoesNotThrow(Runnable action){
		try{ action.run(); }catch(RuntimeException error){ fail("operation_id must be a valid UUID: "+error); }
	}

	@Implements(Nav.class) public static class RecordingNav{
		static Class<? extends Fragment> destination; static Bundle arguments; static int finishCount;
		static void reset(){ destination=null; arguments=null; finishCount=0; }
		@Implementation public static void go(Activity activity, Class<? extends Fragment> fragment, Bundle args){ destination=fragment; arguments=args==null ? null : new Bundle(args); }
		@Implementation public static void finish(Fragment fragment){ finishCount++; }
	}
}
