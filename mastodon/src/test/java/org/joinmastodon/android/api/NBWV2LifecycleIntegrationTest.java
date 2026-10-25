package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.api.session.AccountLocalPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.NBWForumOptionsRepository;
import org.joinmastodon.android.fragments.BaseStatusListFragment;
import org.joinmastodon.android.fragments.settings.NBWPostRegisterActivity;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.InstanceV1;
import org.joinmastodon.android.model.NBWCapabilities;
import org.joinmastodon.android.model.NBWForumOptions;
import org.joinmastodon.android.model.NBWSelection;
import org.joinmastodon.android.model.SearchResult;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

/** Real guards, queued UI delivery and controller cancellation; only HTTP transport is captured. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class NBWV2LifecycleIntegrationTest{
	private AccountSession session, previousSession;
	private Instance previousInstance;
	private String previousActive;
	private AccountLocalPreferences.ColorPreference previousColor;
	private Map<String, AccountSession> sessions;
	private Map<String, Instance> instances;
	private FakeTransport transport;
	private NBWForumOptionsRepository repository;
	private CapturingController controller;
	private final List<ActivityController<NBWPostRegisterActivity>> activities=new ArrayList<>();

	private static final class Pending<T> implements NBWForumOptionsRepository.Cancelable{
		final NBWForumOptionsRepository.Result<T> result;
		Pending(NBWForumOptionsRepository.Result<T> result){ this.result=result; }
		@Override public void cancel(){}
	}
	private static final class FakeTransport implements NBWForumOptionsRepository.Transport{
		final List<Pending<NBWCapabilities>> capabilities=new ArrayList<>();
		final List<Pending<NBWForumOptions>> options=new ArrayList<>();
		@Override public NBWForumOptionsRepository.Cancelable capabilities(NBWForumOptionsRepository.Result<NBWCapabilities> result){
			Pending<NBWCapabilities> pending=new Pending<>(result); capabilities.add(pending); return pending;
		}
		@Override public NBWForumOptionsRepository.Cancelable options(NBWForumOptionsRepository.Result<NBWForumOptions> result){
			Pending<NBWForumOptions> pending=new Pending<>(result); options.add(pending); return pending;
		}
		@Override public NBWForumOptionsRepository.Cancelable consent(boolean accepted, NBWForumOptionsRepository.Result<Object> result){ return new Pending<>(result); }
	}
	private static final class CapturingController extends MastodonAPIController{
		final List<MastodonAPIRequest<?>> requests=new ArrayList<>();
		CapturingController(AccountSession session){ super(session); }
		@Override public <T> void submitRequest(MastodonAPIRequest<T> request){ requests.add(request); }
	}
	public static final class ListProbe extends BaseStatusListFragment<Status>{
		void pin(String account){ accountID=account; }
		void append(List<Status> items){ data.addAll(items); onAppendItems(items); }
		void prepend(List<Status> items){ prependItems(items, false); }
		List<Status> rows(){ return new ArrayList<>(data); }
		@Override protected List<StatusDisplayItem> buildDisplayItems(Status status){
			return List.of(new StatusDisplayItem(status.id, this, MastodonApp.context){
				@Override public Type getType(){ return Type.SECTION_HEADER; }
			});
		}
		@Override protected void addAccountToKnown(Status status){}
		@Override protected Status asStatus(Status status){ return status; }
		@Override protected void loadRelationships(Set<String> ids){}
		@Override protected void doLoadData(int offset, int count){}
		@Override public void onItemClick(String id){}
		@Override public android.net.Uri getWebUri(android.net.Uri.Builder base){ return base.build(); }
	}

	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication();
		previousColor=GlobalUserPreferences.color;
		GlobalUserPreferences.color=AccountLocalPreferences.ColorPreference.PURPLE;
		me.grishka.appkit.utils.V.setApplicationContext(MastodonApp.context);
		var ctor=AccountSession.class.getDeclaredConstructor(); ctor.setAccessible(true); session=ctor.newInstance();
		session.domain="nbw-lifecycle.offline.example.test"; session.self=new Account(); session.self.id="83";
		AccountSessionManager manager=AccountSessionManager.getInstance();
		sessions=(Map<String, AccountSession>)field(AccountSessionManager.class, "sessions").get(manager);
		instances=(Map<String, Instance>)field(AccountSessionManager.class, "instances").get(manager);
		previousSession=sessions.put(session.getID(), session);
		previousActive=(String)field(AccountSessionManager.class, "lastActiveAccountID").get(manager);
		field(AccountSessionManager.class, "lastActiveAccountID").set(manager, session.getID());
		InstanceV1 instance=new InstanceV1(); instance.uri=instance.normalizedUri=session.domain; instance.version="4.3.0";
		previousInstance=instances.put(session.domain, instance);
		transport=new FakeTransport();
		repository=new NBWForumOptionsRepository(transport, ()->0L, new NBWForumOptionsRepository.SelectionStore(){
			@Override public NBWSelection load(){ return NBWSelection.ai(); }
			@Override public void save(NBWSelection selection){}
		}, ()->{}, Runnable::run);
		field(AccountSession.class, "nbwForumOptionsRepository").set(session, repository);
		controller=new CapturingController(session);
		field(AccountSession.class, "apiController").set(session, controller);
		ShadowToast.reset();
	}
	@After public void cleanup() throws Exception{
		for(ActivityController<NBWPostRegisterActivity> activity:activities) activity.close();
		repository.deactivate(); idle();
		if(previousSession==null) sessions.remove(session.getID()); else sessions.put(session.getID(), previousSession);
		if(previousInstance==null) instances.remove(session.domain); else instances.put(session.domain, previousInstance);
		field(AccountSessionManager.class, "lastActiveAccountID").set(AccountSessionManager.getInstance(), previousActive);
		GlobalUserPreferences.color=previousColor;
	}
	private void ready(){
		repository.activate();
		transport.capabilities.get(0).result.success(NBWV2RepositoryTest.capabilities("full"));
		transport.options.get(0).result.success(NBWV2RepositoryTest.options());
	}
	private void readOnly(){
		repository.refreshNow();
		transport.capabilities.get(transport.capabilities.size()-1).result.success(NBWV2RepositoryTest.capabilities("read_only"));
	}
	private static void idle(){ org.robolectric.shadows.ShadowLooper.idleMainLooper(); }
	private static Status status(String id){
		Status status=Status.ofFake(id, "body", Instant.EPOCH);
		status.uri="https://offline.example.test/statuses/"+id;
		status.account=new Account(); status.account.id="83";
		status.account.username=status.account.acct=status.account.displayName="offline";
		status.account.url="https://offline.example.test/@offline";
		return status;
	}
	private MastodonAPIRequest<Object> remoteRequest(int[] callbacks){
		return new MastodonAPIRequest<Object>(MastodonAPIRequest.HttpMethod.GET, "/statuses/nbw_123", Object.class){}
				.setCallback(new Callback<>(){
					@Override public void onSuccess(Object result){ callbacks[0]++; }
					@Override public void onError(ErrorResponse error){ callbacks[1]++; }
				}).exec(session.getID());
	}

	@Test public void immutableLocalThreadRootsAndRepliesRemainRenderableWhenMetadataIsUnavailable(){
		ListProbe probe=new ListProbe(); probe.pin(session.getID());
		Status root=status("p_1"), reply=status("c_2"), ancestor=status("p_0");
		List<Status> immutable=Collections.singletonList(root);
		probe.append(immutable); probe.append(Collections.singletonList(reply)); probe.prepend(Collections.singletonList(ancestor));
		assertEquals(List.of(ancestor, root, reply), probe.rows());
		assertEquals(3, probe.getDisplayItems().size());
		assertEquals(List.of(root), immutable);
	}
	@Test public void hiddenRemoteRowsAreRemovedFromBackingDataAndDisplayWithoutMutatingCaller(){
		ListProbe probe=new ListProbe(); probe.pin(session.getID());
		Status local=status("p_1"), remote=status("nbw_123");
		List<Status> immutable=List.of(local, remote);
		probe.append(immutable);
		assertEquals(List.of(local), probe.rows()); assertEquals(1, probe.getDisplayItems().size());
		assertEquals("p_1", probe.getDisplayItems().get(0).parentID);
		probe.prepend(Collections.singletonList(remote));
		assertEquals(List.of(local), probe.rows()); assertEquals(2, immutable.size());
	}
	@Test public void queuedSuccessAndErrorRecheckGenerationOnTheUiThread() throws Exception{
		for(boolean error:new boolean[]{false, true}){
			if(transport.capabilities.isEmpty()) ready();
			else{ repository.activate(); transport.capabilities.get(transport.capabilities.size()-1).result.success(NBWV2RepositoryTest.capabilities("full")); }
			int[] callbacks={0, 0}, canceled={0};
			MastodonAPIRequest<Object> request=remoteRequest(callbacks);
			request.setNBWCancellationCleanup(()->{ assertSame(Looper.getMainLooper(), Looper.myLooper()); canceled[0]++; });
			Thread network=new Thread(()->{
				if(error) request.onError(new MastodonErrorResponse("offline", 503, null)); else request.onSuccess(new Object());
			});
			network.start(); network.join();
			assertEquals(0, callbacks[0]+callbacks[1]);
			repository.deactivate(); idle();
			assertEquals(0, callbacks[0]+callbacks[1]); assertEquals(1, canceled[0]);
			request.cancel(); idle(); assertEquals(1, canceled[0]);
		}
	}
	@Test public void sameGenerationRemoteCallbacksDeliverExactlyOnceAndReleaseRegistration(){
		ready(); int[] callbacks={0, 0}; MastodonAPIRequest<Object> request=remoteRequest(callbacks);
		request.onSuccess(new Object()); request.onSuccess(new Object()); idle();
		assertEquals(1, callbacks[0]); assertEquals(0, callbacks[1]);
		repository.deactivate(); idle();
		assertFalse(request.canceled);
	}
	@Test public void canceledLikeAndBookmarkClearPendingAndRollbackWithoutClaimingRemoteSuccess() throws Exception{
		ready(); StatusInteractionController interactions=new StatusInteractionController(session.getID());
		Status like=status("nbw_123"), bookmark=status("nbw_124"); like.favouritesCount=7; bookmark.bookmarksCount=3;
		int[] success={0};
		interactions.setFavorited(like, true, result->success[0]++, MastodonApp.context);
		interactions.setBookmarked(bookmark, true, MastodonApp.context);
		assertTrue(like.favourited); assertTrue(bookmark.bookmarked);
		readOnly();
		assertTrue("Cleanup is queued, never executed on the invalidation thread", like.favourited);
		idle();
		assertFalse(like.favourited); assertEquals(7, like.favouritesCount);
		assertFalse(bookmark.bookmarked); assertEquals(3, bookmark.bookmarksCount);
		assertTrue(((Map<?, ?>)field(StatusInteractionController.class, "runningFavoriteRequests").get(interactions)).isEmpty());
		assertTrue(((Map<?, ?>)field(StatusInteractionController.class, "runningBookmarkRequests").get(interactions)).isEmpty());
		assertEquals(0, success[0]); assertNull(ShadowToast.getTextOfLatestToast());
		repository.refreshNow(); transport.capabilities.get(transport.capabilities.size()-1).result.success(NBWV2RepositoryTest.capabilities("full"));
		int sent=controller.requests.size();
		interactions.setFavorited(like, true, result->success[0]++, MastodonApp.context);
		assertEquals(sent+1, controller.requests.size()); assertTrue(like.favourited);
	}
	@Test public void logoutBeforeQueuedErrorOnlyCleansOldPendingState() throws Exception{
		ready(); StatusInteractionController interactions=new StatusInteractionController(session.getID());
		Status like=status("nbw_123"); like.favouritesCount=9; int[] callbacks={0};
		interactions.setFavorited(like, true, result->callbacks[0]++, MastodonApp.context);
		MastodonAPIRequest<?> request=controller.requests.get(0);
		request.onError(new MastodonErrorResponse("old account", 401, null));
		sessions.remove(session.getID()); repository.deactivate(); idle();
		assertFalse(like.favourited); assertEquals(9, like.favouritesCount); assertEquals(0, callbacks[0]);
		assertNull(ShadowToast.getTextOfLatestToast());
		assertTrue(((Map<?, ?>)field(StatusInteractionController.class, "runningFavoriteRequests").get(interactions)).isEmpty());
		sessions.put(session.getID(), session);
	}
	@Test public void postRegistrationDoneAndBackReturnHomeButSettingsOnlyCloses(){
		for(boolean back:new boolean[]{false, true}){
			NBWPostRegisterActivity page=openConnectionPage(false);
			if(back) page.onBackPressed(); else doneButton(page.findViewById(android.R.id.content)).performClick();
			Intent intent=org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowActivity>extract(page).getNextStartedActivity();
			assertNotNull(intent); assertEquals(MainActivity.class.getName(), intent.getComponent().getClassName()); assertTrue(page.isFinishing());
		}
		NBWPostRegisterActivity settings=openConnectionPage(true);
		doneButton(settings.findViewById(android.R.id.content)).performClick();
		assertNull(org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowActivity>extract(settings).getNextStartedActivity()); assertTrue(settings.isFinishing());
	}
	@Test public void disabledRecentSearchCachePurgesWrappedRemoteIdsButPreservesLocalMetadata() throws Exception{
		CacheController cache=session.getCacheController();
		Status local=status("p_1"), comment=status("c_2"); local.nbwTid=123L; comment.nbwTid=123L;
		Account author=status("nbw_123").account; author.id="nbw_83";
		cache.putRecentSearch(new SearchResult(author)); cache.putRecentSearch(new SearchResult(status("nbw_123")));
		cache.putRecentSearch(new SearchResult(local)); cache.putRecentSearch(new SearchResult(comment));
		cache.clearNBWRemoteCache();
		CountDownLatch drained=new CountDownLatch(1); CacheController.databaseThread.postRunnable(drained::countDown, 0);
		assertTrue(drained.await(5, TimeUnit.SECONDS));
		AtomicReference<List<SearchResult>> results=new AtomicReference<>(); cache.getRecentSearches(results::set);
		CountDownLatch read=new CountDownLatch(1); CacheController.databaseThread.postRunnable(read::countDown, 0);
		assertTrue(read.await(5, TimeUnit.SECONDS)); idle();
		assertNotNull(results.get()); assertEquals(2, results.get().size());
		assertTrue(results.get().stream().allMatch(result->result.status!=null && !result.status.id.startsWith("nbw_")));
		cache.closeDatabase(); MastodonApp.context.deleteDatabase(session.getID()+".db");
	}
	private NBWPostRegisterActivity openConnectionPage(boolean settings){
		Intent intent=new Intent(MastodonApp.context, NBWPostRegisterActivity.class).putExtra("account", session.getID()).putExtra("show_back", settings);
		ActivityController<NBWPostRegisterActivity> lifecycle=Robolectric.buildActivity(NBWPostRegisterActivity.class, intent);
		activities.add(lifecycle); lifecycle.setup(); return lifecycle.get();
	}
	private static Button doneButton(View view){
		if(view instanceof Button button && MastodonApp.context.getString(R.string.done).contentEquals(button.getText())) return button;
		if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++){
			Button button=doneButton(group.getChildAt(i)); if(button!=null) return button;
		}
		return null;
	}
	private static Field field(Class<?> type, String name) throws Exception{ Field value=type.getDeclaredField(name); value.setAccessible(true); return value; }
}
