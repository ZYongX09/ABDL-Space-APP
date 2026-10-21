package org.joinmastodon.android.friendcards;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewpager2.widget.ViewPager2;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.accounts.GetAccountByID;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.ProfileFragment;
import org.joinmastodon.android.fragments.friendcards.FriendCardDetailFragment;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Relationship;
import org.joinmastodon.android.ui.tabs.TabLayout;
import org.joinmastodon.android.ui.tabs.TabLayoutMediator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.fragments.LoaderFragment;
import me.grishka.appkit.utils.V;

/** Exercises production mini binding/click/onShown and account refresh failures without a server. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class,
		shadows={PublicFriendCardProfileTest.LocalRequests.class,PublicFriendCardProfileTest.LocalNavigation.class,
				PublicFriendCardProfileTest.UnmountedLoader.class})
public class PublicFriendCardProfileTest{
	@Implements(MastodonAPIRequest.class) public static class LocalRequests{
		@RealObject MastodonAPIRequest<?> request;
		static final List<MastodonAPIRequest<?>> sent=new ArrayList<>();
		static final List<String> accounts=new ArrayList<>();
		@Implementation public MastodonAPIRequest<?> exec(String account){ sent.add(request); accounts.add(account); return request; }
		@Implementation public void cancel(){} // Deliberately permit late callbacks to exercise generation checks.
	}
	@Implements(Nav.class) public static class LocalNavigation{
		static Class<? extends Fragment> destination;
		static Bundle args;
		@Implementation public static void go(Activity activity,Class<? extends Fragment> target,Bundle arguments){
			assertNotNull(activity); destination=target; args=new Bundle(arguments);
		}
	}
	// No toolbar/list lifecycle is needed for a mini click. The ProfileFragment implementation still runs.
	@Implements(LoaderFragment.class) public static class UnmountedLoader{
		@Implementation protected void onShown(){}
	}
	public static class RefreshProbe extends ProfileFragment{
		int loads;
		@Override protected void doLoadData(){ loads++; }
		void shown(){ super.onShown(); }
	}

	private ActivityController<Activity> lifecycle;
	private Activity activity;
	private Fragment host;
	private Map<String,AccountSession> sessions;
	private String selected,previousActive;

	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		lifecycle=Robolectric.buildActivity(Activity.class); activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.setup();
		host=new Fragment(); activity.getFragmentManager().beginTransaction().add(host,"profile-host").commit(); activity.getFragmentManager().executePendingTransactions();
		var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true); AccountSession session=constructor.newInstance();
		session.domain="public-friend-card-offline.example.test"; session.self=new Account(); session.self.id="7"; selected=session.getID();
		var manager=AccountSessionManager.getInstance(); sessions=(Map<String,AccountSession>)field(AccountSessionManager.class,"sessions").get(manager);
		previousActive=manager.getLastActiveAccountID(); sessions.put(selected,session); field(AccountSessionManager.class,"lastActiveAccountID").set(manager,selected);
		LocalRequests.sent.clear(); LocalRequests.accounts.clear(); LocalNavigation.destination=null; LocalNavigation.args=null;
	}
	@After public void cleanup() throws Exception{
		sessions.remove(selected); field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),previousActive); lifecycle.close();
	}
	private View prepare(ProfileFragment profile) throws Exception{
		field(Fragment.class,"mHost").set(profile,field(Fragment.class,"mHost").get(host));
		Bundle args=new Bundle(); args.putString("account",selected); args.putString("profileAccountID","42"); profile.setArguments(args);
		field(ProfileFragment.class,"accountID").set(profile,selected); field(ProfileFragment.class,"profileAccountID").set(profile,"42");
		field(ProfileFragment.class,"account").set(profile,PublicFriendCardUiTest.user());
		View content=LayoutInflater.from(activity).inflate(R.layout.fragment_profile,null,false);
		View mini=content.findViewById(R.id.profile_friend_card_mini); field(ProfileFragment.class,"publicFriendCardMini").set(profile,mini);
		bind(profile); return mini;
	}

	@Test public void miniRoutesSelectedAccountAndLatestReferenceThenRefreshesOnceOnReturn() throws Exception{
		RefreshProbe profile=new RefreshProbe(); View mini=prepare(profile);
		assertEquals(View.VISIBLE,mini.getVisibility());
		Account updated=PublicFriendCardUiTest.user(); updated.publicFriendCard.id="a1b2c3d4-e5f6-4789-8abc-0123456789ab";
		field(ProfileFragment.class,"account").set(profile,updated); // No cached card ID captured in the listener.
		assertTrue(mini.performClick()); assertEquals(FriendCardDetailFragment.class,LocalNavigation.destination);
		assertEquals(selected,LocalNavigation.args.getString("account")); assertEquals(updated.publicFriendCard.id,LocalNavigation.args.getString("card_id"));
		assertEquals(2,LocalNavigation.args.size()); assertEquals(View.GONE,mini.getVisibility()); assertFalse(mini.hasOnClickListeners());
		assertEquals(0,profile.loads); profile.shown(); assertEquals(1,profile.loads); profile.shown(); assertEquals(1,profile.loads);
		assertTrue(field(ProfileFragment.class,"publicFriendCardRefreshing").getBoolean(profile));
		assertEquals(View.GONE,mini.getVisibility()); assertNull(((ImageView)mini.findViewById(R.id.profile_friend_card_icon)).getDrawable());
		// A paused/deleted response has no public reference. The normal account binder must keep it gone.
		updated.publicFriendCard=null; field(ProfileFragment.class,"publicFriendCardRefreshing").setBoolean(profile,false); bind(profile);
		assertEquals(View.GONE,mini.getVisibility());
	}

	@Test public void unknownAndEditedReferencesAndChangedSelectedAccountCannotNavigate() throws Exception{
		RefreshProbe profile=new RefreshProbe(); View mini=prepare(profile);
		field(ProfileFragment.class,"isInEditMode").setBoolean(profile,true); bind(profile);
		assertEquals(View.GONE,mini.getVisibility()); assertFalse(mini.hasOnClickListeners()); mini.performClick(); assertNull(LocalNavigation.destination);
		field(ProfileFragment.class,"isInEditMode").setBoolean(profile,false); bind(profile);
		field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),"other-session");
		mini.performClick(); assertNull(LocalNavigation.destination); assertEquals(0,profile.loads);
		field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),selected);
		Account current=(Account)field(ProfileFragment.class,"account").get(profile); current.publicFriendCard=null;
		mini.performClick(); assertNull(LocalNavigation.destination); // Click guard also checks fresh DTO before a later rebind.
		bind(profile); assertEquals(View.GONE,mini.getVisibility()); assertFalse(mini.hasOnClickListeners());
	}

	@Test public void returnReusesGetAccountByIdAndRefreshFailureNeverRestoresStaleMini() throws Exception{
		ProfileFragment profile=new ProfileFragment(); View mini=prepare(profile);
		field(ProfileFragment.class,"publicFriendCardRefreshing").setBoolean(profile,true); bind(profile);
		invoke(profile,"doLoadData");
		assertEquals(1,LocalRequests.sent.size()); assertTrue(LocalRequests.sent.get(0) instanceof GetAccountByID);
		assertEquals("/accounts/42",field(MastodonAPIRequest.class,"path").get(LocalRequests.sent.get(0)));
		assertEquals(List.of(selected),LocalRequests.accounts);
		Callback<Object> first=callback(LocalRequests.sent.get(0));
		invoke(profile,"doLoadData");
		first.onError(new MastodonErrorResponse("late failure",404,null));
		assertNotNull(((Account)field(ProfileFragment.class,"account").get(profile)).publicFriendCard);
		callback(LocalRequests.sent.get(1)).onError(new MastodonErrorResponse("offline",503,null));
		assertNull(((Account)field(ProfileFragment.class,"account").get(profile)).publicFriendCard);
		assertFalse(field(ProfileFragment.class,"publicFriendCardRefreshing").getBoolean(profile));
		assertEquals(View.GONE,mini.getVisibility()); assertFalse(mini.hasOnClickListeners()); assertNull(mini.getContentDescription());
		assertEquals(2,LocalRequests.sent.size()); // No extra card, token extraction or per-header request.
	}

	@Test public void initialDataLoadedSelectsTimelineButRecheckPreservesAlbumAndAboutTabs() throws Exception{
		ProfileFragment profile=new ProfileFragment(); View mini=prepare(profile);
		ViewPager2 pager=mini.getRootView().findViewById(R.id.pager);
		TabLayout tabs=mini.getRootView().findViewById(R.id.tabbar);
		pager.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>(){
			@Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent,int type){
				FrameLayout page=new FrameLayout(parent.getContext());
				page.setLayoutParams(new RecyclerView.LayoutParams(-1,-1));
				return new RecyclerView.ViewHolder(page){};
			}
			@Override public void onBindViewHolder(RecyclerView.ViewHolder holder,int position){}
			@Override public int getItemCount(){ return 3; }
		});
		field(ProfileFragment.class,"pager").set(profile,pager); field(ProfileFragment.class,"tabbar").set(profile,tabs);
		TabLayoutMediator mediator=new TabLayoutMediator(tabs,pager,(tab,position)->tab.setText("Page "+position)); mediator.attach();
		try{
			assertFalse(profile.loaded); assertEquals(0,pager.getCurrentItem());
			profile.dataLoaded(); pager.getViewTreeObserver().dispatchOnPreDraw();
			assertTrue(profile.loaded); assertEquals(1,pager.getCurrentItem()); assertEquals(1,tabs.getSelectedTabPosition());
			for(int page:new int[]{0,2}){
				pager.setCurrentItem(page,false); tabs.selectTab(tabs.getTabAt(page));
				profile.dataLoaded(); pager.getViewTreeObserver().dispatchOnPreDraw();
				assertEquals(page,pager.getCurrentItem()); assertEquals(page,tabs.getSelectedTabPosition());
			}
			assertTrue(LocalRequests.sent.isEmpty());
		}finally{ mediator.detach(); }
	}

	@Test public void recheckFailureClearsOverlappingPullRefreshAndAllowsAnotherRefresh() throws Exception{
		ProfileFragment profile=new ProfileFragment(); View mini=prepare(profile); View content=mini.getRootView();
		SwipeRefreshLayout refresh=content.findViewById(R.id.refresh_layout);
		field(ProfileFragment.class,"refreshLayout").set(profile,refresh);
		field(ProfileFragment.class,"noteEdit").set(profile,(EditText)content.findViewById(R.id.note_edit));
		field(ProfileFragment.class,"relationship").set(profile,new Relationship()); profile.loaded=true;
		assertTrue(mini.performClick()); invoke(profile,"onShown");
		MastodonAPIRequest<?> recheck=LocalRequests.sent.get(0); assertTrue(recheck instanceof GetAccountByID);
		refresh.setRefreshing(true); profile.onRefresh();
		MastodonAPIRequest<?> pull=LocalRequests.sent.get(LocalRequests.sent.size()-1); assertTrue(pull instanceof GetAccountByID); assertNotSame(recheck,pull);
		assertTrue(field(ProfileFragment.class,"refreshing").getBoolean(profile)); assertTrue(refresh.isRefreshing());
		callback(recheck).onError(new MastodonErrorResponse("stale",503,null));
		assertTrue(field(ProfileFragment.class,"refreshing").getBoolean(profile)); assertTrue(refresh.isRefreshing());
		assertTrue(field(ProfileFragment.class,"publicFriendCardRefreshing").getBoolean(profile));
		assertNotNull(((Account)field(ProfileFragment.class,"account").get(profile)).publicFriendCard);
		callback(pull).onError(new MastodonErrorResponse("offline",503,null));
		assertFalse(field(ProfileFragment.class,"refreshing").getBoolean(profile)); assertFalse(refresh.isRefreshing());
		assertFalse(field(ProfileFragment.class,"publicFriendCardRefreshing").getBoolean(profile));
		assertNull(((Account)field(ProfileFragment.class,"account").get(profile)).publicFriendCard);
		assertEquals(View.GONE,mini.getVisibility()); assertFalse(mini.hasOnClickListeners());
		long requests=LocalRequests.sent.stream().filter(GetAccountByID.class::isInstance).count();
		refresh.setRefreshing(true); profile.onRefresh();
		assertEquals(requests+1,LocalRequests.sent.stream().filter(GetAccountByID.class::isInstance).count());
		assertTrue(field(ProfileFragment.class,"refreshing").getBoolean(profile)); assertTrue(refresh.isRefreshing());
	}

	private static void bind(ProfileFragment profile) throws Exception{ invoke(profile,"bindPublicFriendCardMini"); }
	private static void invoke(ProfileFragment profile,String name) throws Exception{ Method method=ProfileFragment.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(profile); }
	@SuppressWarnings("unchecked") private static Callback<Object> callback(MastodonAPIRequest<?> request) throws Exception{ return (Callback<Object>)field(APIRequest.class,"callback").get(request); }
	private static Field field(Class<?> type,String name) throws Exception{ Field field=type.getDeclaredField(name); field.setAccessible(true); return field; }
}
