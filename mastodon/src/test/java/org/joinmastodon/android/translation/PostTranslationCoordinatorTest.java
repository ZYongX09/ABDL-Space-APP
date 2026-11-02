package org.joinmastodon.android.translation;

import android.app.Application;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.requests.translation.TranslationRequest.TranslationError;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.Translation;
import org.joinmastodon.android.model.TranslationPreferences;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

/** Production state machine with deterministic time and cancellable, deliberately late callbacks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class PostTranslationCoordinatorTest{
	private World world;
	private Wire wire;
	private Clock clock;
	private PostTranslationCoordinator coordinator;
	private static final String ACCOUNT="offline.example.test_42";

	private final class World implements PostTranslationCoordinator.Environment{
		boolean current=true, sponsor=true;
		String language="en";
		@Override public boolean current(){ return current; }
		@Override public String target(){ return language; }
		@Override public boolean sponsorActive(){ return sponsor; }
		@Override public long now(){ return clock.now; }
	}
	private static final class Task{
		long at, order; Runnable action; boolean canceled;
	}
	private static final class Clock implements PostTranslationCoordinator.Scheduler{
		long now, serial;
		final PriorityQueue<Task> pending=new PriorityQueue<>(Comparator.comparingLong((Task task)->task.at).thenComparingLong(task->task.order));
		@Override public PostTranslationCoordinator.Cancelable later(Runnable action, long delay){
			Task task=new Task(); task.at=now+delay; task.order=serial++; task.action=action; pending.add(task); return ()->task.canceled=true;
		}
		void drain(){ advance(0); }
		void advance(long duration){
			long until=now+duration;
			while(!pending.isEmpty() && pending.peek().at<=until){ Task task=pending.remove(); now=task.at; if(!task.canceled) task.action.run(); }
			now=until;
		}
	}
	private static class Call<T> implements PostTranslationCoordinator.Cancelable{
		final Callback<T> callback; boolean canceled;
		Call(Callback<T> callback){ this.callback=callback; }
		@Override public void cancel(){ canceled=true; }
		void success(T result){ callback.onSuccess(result); }
		void error(int status){ callback.onError(new TranslationError(null, status)); }
	}
	private static final class TranslationCall extends Call<Translation>{
		final PostTranslationCoordinator.Key key; final boolean automatic;
		TranslationCall(PostTranslationCoordinator.Key key, boolean automatic, Callback<Translation> callback){ super(callback); this.key=key; this.automatic=automatic; }
	}
	private static final class Save extends Call<TranslationPreferences>{
		final String language, revision; final boolean automatic;
		Save(String language, boolean automatic, String revision, Callback<TranslationPreferences> callback){ super(callback); this.language=language; this.automatic=automatic; this.revision=revision; }
	}
	private static final class Wire implements PostTranslationCoordinator.Transport{
		final List<TranslationCall> posts=new ArrayList<>(), gets=new ArrayList<>();
		final List<Call<TranslationPreferences>> preferences=new ArrayList<>(); final List<Save> saves=new ArrayList<>();
		@Override public PostTranslationCoordinator.Cancelable translate(PostTranslationCoordinator.Key key, boolean automatic, Callback<Translation> callback){ TranslationCall call=new TranslationCall(key, automatic, callback); posts.add(call); return call; }
		@Override public PostTranslationCoordinator.Cancelable query(PostTranslationCoordinator.Key key, Callback<Translation> callback){ TranslationCall call=new TranslationCall(key, false, callback); gets.add(call); return call; }
		@Override public PostTranslationCoordinator.Cancelable preferences(Callback<TranslationPreferences> callback){ Call<TranslationPreferences> call=new Call<>(callback); preferences.add(call); return call; }
		@Override public PostTranslationCoordinator.Cancelable savePreferences(String language, boolean automatic, String revision, Callback<TranslationPreferences> callback){ Save save=new Save(language, automatic, revision, callback); saves.add(save); return save; }
	}
	private final class Host implements PostTranslationCoordinator.Listener{
		final Status status; boolean present=true, visible=true; int changes; final List<ErrorResponse> errors=new ArrayList<>();
		Host(Status status){ this.status=status; }
		@Override public boolean current(PostTranslationCoordinator.Key key){ return world.current && present && status.id.equals(key.resourceID()) && PostTranslationSupport.normalizeLanguage(world.language).equals(key.target()) && PostTranslationSupport.sourceVersion(status).equals(key.sourceVersion()); }
		@Override public boolean visible(){ return visible; }
		@Override public void changed(){ changes++; }
		@Override public void error(ErrorResponse error){ errors.add(error); }
	}
	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		clock=new Clock(); world=new World(); wire=new Wire(); coordinator=new PostTranslationCoordinator(ACCOUNT, world, wire, clock);
	}
	private Status status(String id){ Status result=Status.ofFake(id, "<p>原文</p>", Instant.parse("2026-11-01T00:00:00Z")); result.language="zh"; return result; }
	private Translation ready(){ Translation result=new Translation(); result.content="<p>Translated body</p>"; result.detectedSourceLanguage="zh"; result.provider="ABDL Space AI"; result.sourceHash="server-source-1"; result.targetLanguage="en"; return result; }
	private Translation pending(String state){ Translation result=new Translation(); result.translationStatus=state; result.sourceHash="server-source-1"; result.targetLanguage="en"; result.retryAfter=3; return result; }
	private TranslationPreferences prefs(String language, boolean enabled, String revision){ TranslationPreferences result=new TranslationPreferences(); result.readingLanguage=language; result.autoTranslateEnabled=enabled; result.autoTranslateAllowed=result.enabled=result.providerConfigured=true; result.revision=revision; return result; }
	private void automaticReady(){ coordinator.refreshPreferences(true); wire.preferences.get(wire.preferences.size()-1).success(prefs("en", true, "r1")); clock.drain(); assertTrue(coordinator.canAutomaticallyTranslate()); }
	private PostTranslationCoordinator.Visit visit(){ return new PostTranslationCoordinator.Visit(); }

	@Test public void twoViewsSingleFlightAndShowOriginalSuppressesOnlyThisVisit(){
		automaticReady(); Status one=status("100"), two=status("100"); Host first=new Host(one), second=new Host(two); var firstVisit=visit(); var secondVisit=visit();
		coordinator.toggle(one, firstVisit, first); coordinator.toggle(two, secondVisit, second);
		assertEquals(1, wire.posts.size()); wire.posts.get(0).success(ready()); clock.drain();
		assertEquals(Status.TranslationState.SHOWN, one.translationState); assertEquals(Status.TranslationState.SHOWN, two.translationState);
		coordinator.toggle(one, firstVisit, first); assertEquals(Status.TranslationState.HIDDEN, one.translationState); assertEquals(Status.TranslationState.SHOWN, two.translationState);
		coordinator.automaticallyTranslate(one, firstVisit, first); assertEquals(Status.TranslationState.HIDDEN, one.translationState);
		coordinator.toggle(one, firstVisit, first); assertEquals(Status.TranslationState.SHOWN, one.translationState); assertEquals(1, wire.posts.size());
	}
	@Test public void pendingUsesBoundedGetPollingAndNeverRepeatsPost(){
		Status status=status("101"); Host host=new Host(status); var visit=visit(); coordinator.toggle(status, visit, host); wire.posts.get(0).success(pending("pending")); clock.drain();
		for(int i=0;i<6;i++){ clock.advance(3000); assertEquals(i+1, wire.gets.size()); wire.gets.get(i).success(pending("pending")); clock.drain(); }
		clock.advance(2000); assertEquals(Status.TranslationState.HIDDEN, status.translationState); assertEquals(1, wire.posts.size()); assertEquals(6, wire.gets.size()); assertEquals(1, host.errors.size());
		clock.advance(60000); assertEquals(6, wire.gets.size()); coordinator.toggle(status, visit, host); assertEquals(7, wire.gets.size()); assertEquals(1, wire.posts.size());
	}
	@Test public void unknownDoesNotPollAndEvenQuery404NeverResubmitsAi(){
		Status status=status("102"); Host host=new Host(status); var visit=visit(); coordinator.toggle(status, visit, host); wire.posts.get(0).success(pending("unknown")); clock.drain();
		clock.advance(60000); assertTrue(wire.gets.isEmpty()); assertEquals(Status.TranslationState.HIDDEN, status.translationState);
		coordinator.toggle(status, visit, host); wire.gets.get(0).error(404); clock.drain(); coordinator.toggle(status, visit, host);
		assertEquals(2, wire.gets.size()); assertEquals(1, wire.posts.size());
	}
	@Test public void canceledPostIsQueryOnlyButGet404AllowsNextManualPost(){
		Status status=status("103"); Host host=new Host(status); var visit=visit(); coordinator.toggle(status, visit, host); coordinator.suspend(visit); assertTrue(wire.posts.get(0).canceled);
		coordinator.resume(visit); coordinator.toggle(status, visit, host); assertEquals(1, wire.gets.size()); wire.gets.get(0).error(404); clock.drain();
		assertEquals(1, wire.posts.size()); coordinator.toggle(status, visit, host); assertEquals(2, wire.posts.size());
	}
	@Test public void cacheExpiresAfterSixtySecondsAndRechecksServerWithoutExtendingTtlOnToggle(){
		Status status=status("104"); Host host=new Host(status); var visit=visit(); coordinator.toggle(status, visit, host); wire.posts.get(0).success(ready()); clock.drain();
		clock.advance(59000); coordinator.toggle(status, visit, host); coordinator.toggle(status, visit, host); assertEquals(1, wire.posts.size()); assertEquals(0, wire.gets.size());
		clock.advance(1001); coordinator.toggle(status, visit, host); coordinator.toggle(status, visit, host); assertEquals(1, wire.gets.size()); assertEquals(1, wire.posts.size());
	}
	@Test public void accountLocaleEditAndDetachedResourceFenceLateResults(){
		for(int mode=0;mode<4;mode++){
			setup(); Status status=status("105"); Host host=new Host(status); var visit=visit(); coordinator.toggle(status, visit, host);
			switch(mode){ case 0 -> world.current=false; case 1 -> world.language="fr"; case 2 -> status.content="<p>Edited after request</p>"; case 3 -> host.present=false; }
			wire.posts.get(0).success(ready()); clock.drain(); assertNull("fence "+mode, status.translation); assertNotEquals(Status.TranslationState.SHOWN, status.translationState);
		}
	}
	@Test public void pendingHashChangeOrCachedResultWithoutSourceVersionIsRejected(){
		Status status=status("106"); Host host=new Host(status); coordinator.toggle(status, visit(), host); wire.posts.get(0).success(pending("pending")); clock.drain(); clock.advance(3000);
		Translation edited=ready(); edited.sourceHash="different-current-version"; wire.gets.get(0).success(edited); clock.drain(); assertNull(status.translation); assertEquals(1, host.errors.size());
		Status other=status("107"); coordinator.toggle(other, visit(), new Host(other)); Translation broken=ready(); broken.cached=true; broken.sourceHash=null; wire.posts.get(1).success(broken); clock.drain(); assertNull(other.translation);
	}
	@Test public void automaticQueueIsBoundedVisibleAndPrunedAtSponsorExpiry(){
		automaticReady(); var visit=visit(); List<Status> statuses=new ArrayList<>(); List<Host> hosts=new ArrayList<>();
		for(int i=0;i<80;i++){ Status status=status("auto-"+i); Host host=new Host(status); statuses.add(status); hosts.add(host); coordinator.automaticallyTranslate(status, visit, host); }
		assertEquals(PostTranslationCoordinator.MAX_ACTIVE, wire.posts.size()); assertTrue(wire.posts.stream().allMatch(call->call.automatic));
		assertEquals(PostTranslationCoordinator.MAX_ACTIVE+PostTranslationCoordinator.MAX_QUEUED, statuses.stream().filter(status->status.translationState==Status.TranslationState.LOADING).count());
		hosts.get(0).visible=false; coordinator.prune(); assertTrue(wire.posts.get(0).canceled); assertEquals(3, wire.posts.size());
		world.sponsor=false; coordinator.prune(); for(Status status:statuses) assertNotEquals(Status.TranslationState.LOADING, status.translationState);
		for(TranslationCall call:wire.posts){ call.success(ready()); } clock.drain(); for(Status status:statuses) assertNull(status.translation);
	}
	@Test public void autoOptInAndCapabilityAreBothRequiredAndUnavailableBackendFailsClosed(){
		Status status=status("108"); Host host=new Host(status); var visit=visit(); coordinator.automaticallyTranslate(status, visit, host); assertTrue(wire.posts.isEmpty());
		coordinator.refreshPreferences(true); wire.preferences.get(0).success(prefs("en", false, "r1")); clock.drain(); coordinator.automaticallyTranslate(status, visit, host); assertTrue(wire.posts.isEmpty());
		coordinator.refreshPreferences(true); wire.preferences.get(1).error(503); clock.drain(); assertNull(coordinator.preferences()); assertFalse(coordinator.canAutomaticallyTranslate());
		coordinator.toggle(status, visit, host); assertEquals(1, wire.posts.size()); assertFalse(wire.posts.get(0).automatic); wire.posts.get(0).error(503); clock.drain(); assertEquals(Status.TranslationState.HIDDEN, status.translationState);
		coordinator.toggle(status, visit, host); assertEquals(2, wire.posts.size());
	}
	@Test public void languageSyncPreservesServerOptInUsesFreshCasAndDoesNotPutPerBind(){
		coordinator.refreshPreferences(false); wire.preferences.get(0).success(prefs("zh", true, "r1")); clock.drain();
		assertEquals(1, wire.saves.size()); assertTrue(wire.saves.get(0).automatic); assertEquals("en", wire.saves.get(0).language); assertEquals("r1", wire.saves.get(0).revision);
		wire.saves.get(0).error(409); clock.drain(); assertEquals(2, wire.preferences.size());
		wire.preferences.get(1).success(prefs("zh", false, "r2")); clock.drain(); assertEquals(2, wire.saves.size()); assertFalse(wire.saves.get(1).automatic); assertEquals("r2", wire.saves.get(1).revision);
		wire.saves.get(1).success(prefs("en", false, "r3")); clock.drain();
		for(int i=0;i<100;i++){ coordinator.refreshPreferences(false); coordinator.automaticallyTranslate(status("hidden"), visit(), new Host(status("hidden"))); }
		assertEquals(2, wire.saves.size()); assertEquals(2, wire.preferences.size());
		coordinator.setAutomatic(true); assertEquals("r3", wire.saves.get(2).revision); wire.saves.get(2).success(prefs("en", true, "r4")); clock.drain(); assertTrue(coordinator.preferences().autoTranslateEnabled);
	}
	@Test public void queryTargetAndReblogIdentityNeverUseParentIdOrPostingLanguage(){
		Status content=status("real-content"), wrapper=status("reblog-wrapper"); wrapper.reblog=content; Host host=new Host(content); coordinator.toggle(wrapper, visit(), host);
		assertEquals("real-content", wire.posts.get(0).key.resourceID()); assertEquals("en", wire.posts.get(0).key.target()); assertEquals(ACCOUNT, wire.posts.get(0).key.accountID());
		assertEquals("en", PostTranslationSupport.normalizeLanguage("en-US")); assertEquals("en", PostTranslationSupport.normalizeLanguage("EN_gb"));
	}
}
