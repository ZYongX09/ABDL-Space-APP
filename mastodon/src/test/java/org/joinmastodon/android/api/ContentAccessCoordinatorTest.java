package org.joinmastodon.android.api;

import android.net.Uri;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.ContentAccessCoordinator;
import org.joinmastodon.android.model.ContentAccess;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.model.verification.VerificationLink;
import org.joinmastodon.android.verification.VerificationSessionGuard;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Production owner checks, serial probe state and actual private SQLite leases, not source assertions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ContentAccessCoordinatorTest{
	private AccountSession session, previous;
	private Map<String,AccountSession> sessions;
	private String previousActive;
	private NBWEndpointFixtures endpoints;
	private ContentAccessCoordinator coordinator;
	private final List<ContentAccessCoordinator.Result> probes=new ArrayList<>();
	private final List<Integer> canceled=new ArrayList<>();
	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); me.grishka.appkit.utils.V.setApplicationContext(MastodonApp.context);
		endpoints=new NBWEndpointFixtures();
		var manager=AccountSessionManager.getInstance();
		sessions=(Map<String,AccountSession>)HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"sessions").get(manager);
		previousActive=manager.getLastActiveAccountID(); session=HomeTimelineSnapshotCacheTest.session("api.abdl-space.top","content-access-991");
		previous=sessions.put(session.getID(),session); HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"lastActiveAccountID").set(manager,session.getID());
		coordinator=new ContentAccessCoordinator(session,(id,result)->{ assertEquals(session.getID(),id); int index=probes.size(); probes.add(result); return ()->canceled.add(index); },Runnable::run);
		HomeTimelineSnapshotCacheTest.field(AccountSession.class,"contentAccessCoordinator").set(session,coordinator);
	}
	@After public void cleanup() throws Exception{
		HomeTimelineSnapshotCacheTest.drain(); session.deactivateNBW(); session.getCacheController().closeDatabase(); MastodonApp.context.deleteDatabase(session.getID()+".db");
		if(previous==null) sessions.remove(session.getID()); else sessions.put(session.getID(),previous);
		HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),previousActive); endpoints.close();
	}
	public static ContentAccess access(ContentAccess.State state){ ContentAccess access=new ContentAccess(); access.schemaVersion=1; access.state=state; access.actionPath=ContentAccess.ACTION_PATH; access.message="Generic server policy"; return access; }
	private void open(){ coordinator.refresh(); probes.get(probes.size()-1).success(access(ContentAccess.State.OPEN)); assertTrue(coordinator.isOpen()); }

	@Test public void unknownFailureAndRevalidationNeverAllowOfflineHistory() throws Exception{
		assertFalse(coordinator.isOpen()); assertNull(session.getCacheController().beginHomeTimelineSnapshot());
		coordinator.refresh(); assertTrue(coordinator.getSnapshot().loading); probes.get(0).failure();
		assertEquals(ContentAccess.State.UNAVAILABLE,coordinator.getSnapshot().state); assertFalse(coordinator.isOpen());
		open(); var old=coordinator.capture(); coordinator.refresh(); assertFalse(coordinator.isOpen()); assertFalse(old.canRead());
		probes.get(probes.size()-1).failure(); assertFalse(coordinator.isOpen());
	}
	@Test public void onlyNewestSerialGetOpensAndLateAllowCannotClearRequired(){
		coordinator.refresh(); var oldProbe=probes.get(0); coordinator.refresh(); assertTrue(canceled.contains(0));
		oldProbe.success(access(ContentAccess.State.OPEN)); assertFalse(coordinator.isOpen());
		probes.get(1).success(access(ContentAccess.State.OPEN)); assertTrue(coordinator.isOpen());
		var content=coordinator.capture(); coordinator.refresh();
		// The old content header is not part of the new epoch and cannot close the fresh pending probe.
		content.receiveHeader("v1:baby_verification_required"); probes.get(2).success(access(ContentAccess.State.OPEN)); assertTrue(coordinator.isOpen());
		var current=coordinator.capture(); current.receiveHeader("v1:baby_verification_required");
		probes.get(2).success(access(ContentAccess.State.OPEN)); assertEquals(ContentAccess.State.BABY_VERIFICATION_REQUIRED,coordinator.getSnapshot().state);
		current.receiveHeader("v1:open"); assertFalse(coordinator.isOpen());
		coordinator.refresh(); probes.get(3).success(access(ContentAccess.State.OPEN)); assertTrue(coordinator.isOpen());
	}
	@Test public void exactTokenObjectValueSessionAndAccountOwnEveryProbe() throws Exception{
		coordinator.refresh(); var owner=coordinator.capture(); var pending=probes.get(0);
		Token original=session.token; Token replacement=new Token(); replacement.accessToken=original.accessToken; session.token=replacement;
		pending.success(access(ContentAccess.State.OPEN)); assertFalse(coordinator.isOpen()); assertFalse(owner.isCurrent());
		coordinator.refresh(); session.token.accessToken="rotated"; probes.get(1).success(access(ContentAccess.State.OPEN)); assertFalse(coordinator.isOpen());
		coordinator.refresh(); var active=probes.get(2);
		HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),"different-account");
		active.success(access(ContentAccess.State.OPEN)); assertFalse(coordinator.isOpen());
		HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),session.getID());
		coordinator.deactivate(); active.success(access(ContentAccess.State.OPEN)); assertFalse(coordinator.isOpen());
		coordinator.refresh(); sessions.put(session.getID(),HomeTimelineSnapshotCacheTest.session(session.domain,session.self.id)); probes.get(3).success(access(ContentAccess.State.OPEN)); assertFalse(coordinator.isOpen()); sessions.put(session.getID(),session);
	}
	@Test public void malformedSchemaActionAndUnknownStateRemainClosed(){
		for(String json:List.of("{}","{\"schema_version\":2,\"state\":\"open\",\"action_path\":\"/settings/baby-verification\",\"message\":\"ok\"}","{\"schema_version\":1,\"state\":\"open\",\"action_path\":\"https://evil.test\",\"message\":\"ok\"}","{\"schema_version\":1,\"state\":\"future_allow\",\"action_path\":\"/settings/baby-verification\",\"message\":\"ok\"}")){
			coordinator.refresh(); probes.get(probes.size()-1).success(MastodonAPIController.gson.fromJson(json,ContentAccess.class)); assertFalse(coordinator.isOpen());
		}
	}
	@Test public void requirementPromptOnceAcrossRefreshCancelAndRecreationNotAStoredAllow(){
		open(); coordinator.capture().receiveHeader("v1:baby_verification_required"); assertTrue(coordinator.claimRequiredPrompt()); assertFalse(coordinator.claimRequiredPrompt());
		coordinator.refresh(); probes.get(probes.size()-1).success(access(ContentAccess.State.BABY_VERIFICATION_REQUIRED)); assertFalse(coordinator.claimRequiredPrompt());
		assertFalse(new ContentAccessCoordinator(session,(id,result)->()->{},Runnable::run).isOpen());
		open(); coordinator.capture().receiveHeader("v1:baby_verification_required"); assertTrue(coordinator.claimRequiredPrompt());
	}
	@Test public void cacheLeaseAndQueuedWriteRetiredOnRequiredThenFreshOpenCannotResurrect() throws Exception{
		open(); CacheController cache=session.getCacheController(); var lease=cache.beginHomeTimelineSnapshot(); assertNotNull(lease);
		cache.putHomeTimelineSnapshot(lease,List.of(HomeTimelineSnapshotCacheTest.post("p_visible")),"old-cursor"); HomeTimelineSnapshotCacheTest.drain();
		CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
		CacheController.databaseThread.postRunnable(()->{ entered.countDown(); try{ release.await(); }catch(InterruptedException error){ Thread.currentThread().interrupt(); } },0);
		assertTrue(entered.await(5,TimeUnit.SECONDS));
		try{
			cache.putHomeTimelineSnapshot(lease,List.of(HomeTimelineSnapshotCacheTest.post("p_late")),null);
			coordinator.capture().receiveHeader("v1:baby_verification_required"); assertFalse(cache.isHomeTimelineLeaseCurrent(lease)); assertNull(cache.beginHomeTimelineSnapshot());
			cache.putHomeTimelineSnapshot(lease,List.of(HomeTimelineSnapshotCacheTest.post("p_resurrect")),null);
		}finally{ release.countDown(); }
		HomeTimelineSnapshotCacheTest.drain(); open();
		AtomicReference<org.joinmastodon.android.api.cache.HomeTimelineSnapshot> snapshot=new AtomicReference<>(); cache.getHomeTimelineSnapshot(cache.beginHomeTimelineSnapshot(),snapshot::set); HomeTimelineSnapshotCacheTest.drain(); assertNull(snapshot.get());
	}
	@Test public void verificationGuardRejectsSameValueNewCredentialAndMutatedValue(){
		VerificationSessionGuard guard=new VerificationSessionGuard(session.getID()); int generation=guard.nextGeneration(); assertTrue(guard.live(generation));
		Token replacement=new Token(); replacement.accessToken=session.token.accessToken; session.token=replacement; assertFalse(guard.live(generation));
		guard=new VerificationSessionGuard(session.getID()); generation=guard.nextGeneration(); session.token.accessToken="changed"; assertFalse(guard.live(generation));
	}
	@Test public void strictSettingsLinkAndOriginRouteBoundary(){
		assertTrue(VerificationLink.isSettingsLink(Uri.parse("https://abdl-space.top/settings/baby-verification")));
		for(String value:List.of("http://abdl-space.top/settings/baby-verification","https://api.abdl-space.top/settings/baby-verification","https://abdl-space.top:443/settings/baby-verification","https://abdl-space.top/settings/baby-verification/","https://abdl-space.top/settings/baby-verification?open=1","https://abdl-space.top/settings%2Fbaby-verification","https://abdl-space.top@evil.test/settings/baby-verification")) assertFalse(value,VerificationLink.isSettingsLink(Uri.parse(value)));
		assertFalse(ContentAccessCoordinator.isProtectedRoute(Uri.parse("https://foreign.example/api/v1/statuses")));
		assertFalse(ContentAccessCoordinator.isProtectedRoute(Uri.parse("https://api.abdl-space.top/api/v1/baby-verification/verify/abcdefghijklmnop")));
		assertTrue(ContentAccessCoordinator.isProtectedRoute(Uri.parse("https://api.abdl-space.top/api/v1/statuses/p_1")));
	}
}
