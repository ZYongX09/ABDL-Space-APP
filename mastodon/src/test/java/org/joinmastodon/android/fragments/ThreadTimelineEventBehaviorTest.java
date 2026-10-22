package org.joinmastodon.android.fragments;

import static org.junit.Assert.*;

import android.app.Fragment;
import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.E;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.accounts.GetAccountStatuses;
import org.joinmastodon.android.api.requests.statuses.GetStatusContext;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.StatusCountersUpdatedEvent;
import org.joinmastodon.android.events.StatusCreatedEvent;
import org.joinmastodon.android.events.StatusDeletedEvent;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Filter;
import org.joinmastodon.android.model.FilterAction;
import org.joinmastodon.android.model.FilterContext;
import org.joinmastodon.android.model.FilterResult;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.InstanceV1;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusContext;
import org.joinmastodon.android.ui.displayitems.FooterStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.parceler.Parcels;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.utils.V;

/** Real event listeners and Thread lifecycle/rendering. Only transport/navigation are captured. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="en",
		shadows={ThreadTimelineEventBehaviorTest.LocalRequests.class, ThreadTimelineEventBehaviorTest.LocalNavigation.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class ThreadTimelineEventBehaviorTest{
	@Implements(MastodonAPIRequest.class) public static class LocalRequests{
		@RealObject MastodonAPIRequest<?> request;
		static final List<MastodonAPIRequest<?>> sent=new ArrayList<>();
		@Implementation public MastodonAPIRequest<?> exec(String account){ sent.add(request); return request; }
	}
	@Implements(Nav.class) public static class LocalNavigation{
		static final List<Fragment> finished=new ArrayList<>();
		@Implementation public static void finish(Fragment fragment){ finished.add(fragment); }
	}

	// Retain production Home/Account event handling and storage; omit only unrelated timeline UI/IO.
	public static class HomeProbe extends HomeTimelineFragment{
		@Override protected void prependItems(List<Status> statuses, boolean notify){ data.addAll(0, statuses); }
		void preload(Status status){ preloadedData.add(status); }
		List<String> ids(){ return data.stream().map(s->s.id).collect(Collectors.toList()); }
	}
	public static class AccountProbe extends AccountTimelineFragment{
		@Override protected void prependItems(List<Status> statuses, boolean notify){ data.addAll(0, statuses); }
		void preload(Status status){ preloadedData.add(status); }
		List<String> ids(){ return data.stream().map(s->s.id).collect(Collectors.toList()); }
	}

	private AccountSession session, previousSession;
	private Instance previousInstance;
	private Map<String, AccountSession> sessions;
	private Map<String, Instance> instances;
	private ActivityController<FragmentStackActivity> lifecycle;
	private ThreadFragment thread;
	private final List<Object> extraListeners=new ArrayList<>();
	private boolean previousShowCounts;

	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		ShadowChoreographer.setPaused(true);
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		org.joinmastodon.android.ui.utils.UiUtils.updateLocalizedDateFormatters(MastodonApp.context);
		var ctor=AccountSession.class.getDeclaredConstructor(); ctor.setAccessible(true); session=ctor.newInstance();
		session.domain="thread-ui.offline.example.test";
		session.self=new Account(); session.self.id="42";
		session.self.username=session.self.acct="offline"; session.self.displayName="Offline"; session.self.emojis=List.of();
		session.self.avatar=session.self.avatarStatic="android.resource://"+MastodonApp.context.getPackageName()+"/"+R.drawable.ic_nbw;
		AccountSessionManager manager=AccountSessionManager.getInstance();
		sessions=(Map<String, AccountSession>)field(AccountSessionManager.class, "sessions").get(manager);
		instances=(Map<String, Instance>)field(AccountSessionManager.class, "instances").get(manager);
		previousSession=sessions.put(session.getID(), session);
		InstanceV1 instance=new InstanceV1(); instance.uri=instance.normalizedUri=session.domain; instance.version="4.3.0";
		previousInstance=instances.put(session.domain, instance);
		session.getLocalPreferences().serverSideFiltersSupported=false;
		previousShowCounts=GlobalUserPreferences.showInteractionCounts; GlobalUserPreferences.showInteractionCounts=true;
		LocalRequests.sent.clear(); LocalNavigation.finished.clear();
	}
	@After public void cleanup(){
		for(Object listener:extraListeners) E.unregister(listener);
		if(lifecycle!=null) lifecycle.close();
		if(previousSession==null) sessions.remove(session.getID()); else sessions.put(session.getID(), previousSession);
		if(previousInstance==null) instances.remove(session.domain); else instances.put(session.domain, previousInstance);
		GlobalUserPreferences.showInteractionCounts=previousShowCounts;
		LocalRequests.sent.clear(); LocalNavigation.finished.clear(); ShadowChoreographer.setPaused(false);
	}

	@Test public void homeEventListenerRejectsEveryReplyAndDuplicateIncludingPreloadedIds(){
		HomeProbe home=new HomeProbe(); home.accountID=session.getID(); register(home.eventListener);
		for(String parent:List.of("p_1", "c_2", "nbw_123", "nbw_123_456")){
			Status reply=status("c_"+parent, parent); reply.inReplyToAccountId=session.self.id;
			created(reply); reply.inReplyToAccountId=null; created(reply);
		}
		assertTrue(home.ids().isEmpty());
		created(status("p_new", null)); created(status("p_new", null));
		home.preload(status("p_preloaded", null)); created(status("p_preloaded", null));
		E.post(new StatusCreatedEvent(status("p_other_account", null), "another-account")); idle();
		assertEquals(List.of("p_new"), home.ids());
	}

	@Test public void accountRootEventsRejectSelfRepliesAndMissingParentAccountsAndDuplicates() throws Exception{
		AccountProbe account=accountProbe(GetAccountStatuses.Filter.DEFAULT);
		for(String parent:List.of("p_1", "c_2", "nbw_123", "nbw_123_456")){
			Status reply=status("c_"+parent, parent); reply.inReplyToAccountId=session.self.id;
			created(reply); reply.inReplyToAccountId=null; created(reply);
		}
		assertTrue(account.ids().isEmpty());
		created(status("p_new", null)); created(status("p_new", null));
		account.preload(status("p_preloaded", null)); created(status("p_preloaded", null));
		assertEquals(List.of("p_new"), account.ids());
	}

	@Test public void accountRepliesTabStillAcceptsAReplyExactlyOnce() throws Exception{
		AccountProbe account=accountProbe(GetAccountStatuses.Filter.INCLUDE_REPLIES);
		created(status("c_new", "nbw_123_456")); created(status("c_new", "nbw_123_456"));
		assertEquals(List.of("c_new"), account.ids());
	}

	@Test public void eventBeforeHttpDeduplicatesContextWithoutLosingExistingReplies() throws Exception{
		launchThread(); GetStatusContext request=requestContext(false);
		created(status("c_local", "p_root"));
		success(request, context(List.of(status("p_root", null)), List.of(status("c_local", "p_root"),
				status("c_local", "p_root"), status("c_remote", "p_root"), status("p_root", null))));
		assertEquals(List.of("p_root", "c_local", "c_remote"), threadIDs());
		assertUniqueDisplayRows();
	}

	@Test public void httpBeforeEventDoesNotDuplicateButStillAcceptsNewReplies() throws Exception{
		launchThread();
		success(requestContext(false), context(List.of(), List.of(status("c_remote", "p_root"))));
		created(status("c_remote", "p_root")); created(status("c_local", "c_remote")); created(status("c_local", "c_remote"));
		assertEquals(List.of("p_root", "c_remote", "c_local"), threadIDs()); assertUniqueDisplayRows();
	}

	@Test public void staleRefreshRetainsPendingReplyUntilAcknowledgedAndDeletionDoesNotResurrectIt() throws Exception{
		launchThread(); success(requestContext(false), context(List.of(), List.of(status("c_existing", "p_root"))));
		GetStatusContext refresh=requestContext(true);
		created(status("c_pending", "c_existing"));
		success(refresh, context(List.of(), List.of(status("c_existing", "p_root"), status("c_existing", "p_root"))));
		assertEquals(List.of("p_root", "c_existing", "c_pending"), threadIDs()); assertUniqueDisplayRows();
		E.post(new StatusDeletedEvent("c_pending", session.getID())); idle();
		success(requestContext(true), context(List.of(), List.of(status("c_existing", "p_root"))));
		assertEquals(List.of("p_root", "c_existing"), threadIDs());
	}

	@Test public void deletingMainThroughEventClosesThreadCancelsHttpAndRejectsLateRefreshOrReply() throws Exception{
		launchThread(); GetStatusContext pending=requestContext(false);
		E.post(new StatusDeletedEvent("p_root", "another-account")); idle(); assertTrue(LocalNavigation.finished.isEmpty());
		E.post(new StatusDeletedEvent("p_root", session.getID())); idle();
		assertEquals(List.of(thread), LocalNavigation.finished);
		assertTrue(field(MastodonAPIRequest.class, "canceled").getBoolean(pending));
		assertEquals(View.GONE, thread.getView().findViewById(R.id.reply_button_wrapper).getVisibility());
		int requests=LocalRequests.sent.size(); thread.doLoadData(0, 20); assertEquals(requests, LocalRequests.sent.size());
		// Deliver the genuine callback even after cancellation to exercise the late-response guard.
		success(pending, context(List.of(), List.of(status("c_late", "p_root")))); created(status("c_event_late", "p_root"));
		assertTrue(threadIDs().isEmpty());
	}

	@Test public void realListEventListenerUpdatesVisibleAndPreloadedContentNotReblogShells() throws Exception{
		launchThread();
		Status visible=status("boost_visible", null); visible.favouritesCount=91; visible.bookmarksCount=92;
		visible.reblog=status("nbw_123", "p_root");
		success(requestContext(false), context(List.of(), List.of(visible)));
		Status preloaded=status("boost_preloaded", null); preloaded.favouritesCount=81; preloaded.bookmarksCount=82;
		preloaded.reblog=status("nbw_123", "p_root"); statuses(thread, "preloadedData").add(preloaded);
		RecyclerView list=(RecyclerView)field(thread.getClass(), "list").get(thread);
		list.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 480, 2400); idle();
		Status authoritative=status("nbw_123", null); authoritative.favouritesCount=42; authoritative.favourited=true;
		authoritative.bookmarksCount=12; authoritative.bookmarked=true;
		E.post(new StatusCountersUpdatedEvent(authoritative, StatusCountersUpdatedEvent.CounterType.FAVORITES));
		E.post(new StatusCountersUpdatedEvent(authoritative, StatusCountersUpdatedEvent.CounterType.BOOKMARKS)); idle();
		for(Status content:List.of(visible.reblog, preloaded.reblog)){
			assertEquals(42, content.favouritesCount); assertTrue(content.favourited);
			assertEquals(12, content.bookmarksCount); assertTrue(content.bookmarked);
		}
		assertEquals(91, visible.favouritesCount); assertEquals(92, visible.bookmarksCount);
		assertEquals(81, preloaded.favouritesCount); assertEquals(82, preloaded.bookmarksCount);
		boolean rebound=false;
		for(int i=0;i<list.getChildCount();i++){
			RecyclerView.ViewHolder holder=list.getChildViewHolder(list.getChildAt(i));
			if(holder instanceof FooterStatusDisplayItem.Holder footer && footer.getItem().status==visible.reblog){
				assertTrue(footer.itemView.findViewById(R.id.favorite_btn).isSelected());
				assertTrue(footer.itemView.findViewById(R.id.bookmark_btn).isSelected()); rebound=true;
			}
		}
		assertTrue("The genuine visible footer must be rebound by EventListener", rebound);
	}

	@Test public void threadContextHonorsModernFilterContextInsteadOfHidingHomeOnlyMatches() throws Exception{
		launchThread(); session.getLocalPreferences().serverSideFiltersSupported=true;
		Status homeHidden=status("c_home", "p_root"); homeHidden.filtered=List.of(hiddenIn(FilterContext.HOME));
		Status threadHidden=status("c_thread", "p_root"); threadHidden.filtered=List.of(hiddenIn(FilterContext.THREAD));
		success(requestContext(false), context(List.of(), List.of(homeHidden, threadHidden)));
		assertEquals(List.of("p_root", "c_home"), threadIDs());
		Status hiddenEvent=status("c_event_hidden", "p_root"); hiddenEvent.filtered=List.of(hiddenIn(FilterContext.THREAD));
		created(hiddenEvent); assertEquals(List.of("p_root", "c_home"), threadIDs());
	}

	private AccountProbe accountProbe(GetAccountStatuses.Filter filter) throws Exception{
		AccountProbe account=new AccountProbe(); account.accountID=session.getID();
		field(AccountTimelineFragment.class, "user").set(account, session.self);
		field(AccountTimelineFragment.class, "filter").set(account, filter); register(account.eventListener); return account;
	}
	private void register(Object listener){ E.register(listener); extraListeners.add(listener); }
	private void created(Status status){ E.post(new StatusCreatedEvent(status, session.getID())); idle(); }
	private void launchThread(){
		lifecycle=Robolectric.buildActivity(FragmentStackActivity.class);
		FragmentStackActivity activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.create();
		thread=new ThreadFragment(); Bundle args=new Bundle(); args.putString("account", session.getID());
		args.putParcelable("status", Parcels.wrap(status("p_root", null))); args.putBoolean("noAutoLoad", true); thread.setArguments(args);
		activity.showFragment(thread); lifecycle.start().resume().visible(); idle(); assertNotNull(thread.getView());
	}
	private GetStatusContext requestContext(boolean refreshing) throws Exception{
		field(thread.getClass(), "refreshing").setBoolean(thread, refreshing);
		thread.doLoadData(0, 20);
		return (GetStatusContext)LocalRequests.sent.stream().filter(GetStatusContext.class::isInstance).reduce((a,b)->b).orElseThrow();
	}
	private Status status(String id, String parent){
		Status status=Status.ofFake(id, "<p>Offline body</p>", Instant.parse("2026-10-22T00:00:00Z"));
		status.account=session.self; status.uri=status.url="https://"+session.domain+"/statuses/"+id;
		status.inReplyToId=parent; status.favouritesCount=7; status.bookmarksCount=3; return status;
	}
	private static StatusContext context(List<Status> ancestors, List<Status> descendants){
		StatusContext context=new StatusContext(); context.ancestors=ancestors; context.descendants=descendants; return context;
	}
	private static FilterResult hiddenIn(FilterContext context){
		Filter filter=new Filter(); filter.id="hide_"+context; filter.title="Offline filter";
		filter.context=EnumSet.of(context); filter.filterAction=FilterAction.HIDE;
		FilterResult result=new FilterResult(); result.filter=filter; return result;
	}
	private List<String> threadIDs() throws Exception{ return statuses(thread, "data").stream().map(s->s.id).collect(Collectors.toList()); }
	private void assertUniqueDisplayRows(){
		List<String> keys=thread.displayItems.stream().map(s->s.parentID+":"+s.index+":"+s.getType()).collect(Collectors.toList());
		assertEquals(keys.size(), keys.stream().distinct().count());
	}
	@SuppressWarnings("unchecked") private static List<Status> statuses(Object object, String name) throws Exception{ return (List<Status>)field(object.getClass(), name).get(object); }
	@SuppressWarnings("unchecked") private static void success(MastodonAPIRequest<?> request, Object result) throws Exception{
		((Callback<Object>)field(APIRequest.class, "callback").get(request)).onSuccess(result); idle();
	}
	private static Field field(Class<?> type, String name) throws NoSuchFieldException{
		for(Class<?> current=type;current!=null;current=current.getSuperclass()){
			try{ Field field=current.getDeclaredField(name); field.setAccessible(true); return field; }catch(NoSuchFieldException ignored){}
		}
		throw new NoSuchFieldException(name);
	}
	private static void idle(){ ShadowLooper.idleMainLooper(); }
}
