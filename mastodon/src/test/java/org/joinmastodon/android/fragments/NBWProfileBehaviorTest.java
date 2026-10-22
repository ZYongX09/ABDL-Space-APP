package org.joinmastodon.android.fragments;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Fragment;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.accounts.GetAccountStatuses;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.tabs.TabLayout;
import org.joinmastodon.android.ui.tabs.TabLayoutMediator;
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
import org.robolectric.annotation.RealObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.utils.V;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, shadows=NBWProfileBehaviorTest.LocalRequests.class)
public class NBWProfileBehaviorTest{
	@Implements(MastodonAPIRequest.class) public static class LocalRequests{
		@RealObject MastodonAPIRequest<?> request;
		static final List<MastodonAPIRequest<?>> sent=new ArrayList<>();
		static final List<String> sessions=new ArrayList<>();
		@Implementation public MastodonAPIRequest<?> exec(String account){ sent.add(request); sessions.add(account); return request; }
		@Implementation public void cancel(){}
	}
	public static class TimelineProbe extends AccountTimelineFragment{
		boolean more;
		@Override protected void onDataLoaded(List<Status> statuses,boolean hasMore){ more=hasMore; }
		void page(int offset){ doLoadData(offset,20); }
	}
	private ActivityController<Activity> lifecycle;
	private Activity activity;
	private Fragment host;
	private String selected;
	private Map<String,AccountSession> sessions;

	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		lifecycle=Robolectric.buildActivity(Activity.class); activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.setup();
		host=new Fragment(); activity.getFragmentManager().beginTransaction().add(host,"host").commit(); activity.getFragmentManager().executePendingTransactions();
		var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true); AccountSession session=constructor.newInstance();
		session.domain="nbw-profile.example.test"; session.self=new Account(); session.self.id="7"; selected=session.getID();
		sessions=(Map<String,AccountSession>)field(AccountSessionManager.class,"sessions").get(AccountSessionManager.getInstance()); sessions.put(selected,session);
		LocalRequests.sent.clear(); LocalRequests.sessions.clear();
	}
	@After public void cleanup(){ sessions.remove(selected); lifecycle.close(); }
	private Account author(String id) throws Exception{ Account a=new Account(); a.id=id; a.username=a.acct=a.displayName="Author"; a.url="https://www.newbabyworld.top/?4"; a.postprocess(); return a; }
	private void mount(Fragment fragment) throws Exception{ field(Fragment.class,"mHost").set(fragment,field(Fragment.class,"mHost").get(host)); }

	@Test @SuppressWarnings({"rawtypes","unchecked"}) public void nbwPagerHidesAlbumsAndUsesTimelineContainerWhileLocalProfileStaysUnchanged() throws Exception{
		for(String id:new String[]{"nbw_123","42"}){
			ProfileFragment profile=new ProfileFragment(); mount(profile); Account author=author(id); author.nbwUsername="bound";
			Bundle args=new Bundle(); args.putString("account",selected); args.putString("profileAccountID",id); profile.setArguments(args);
			field(ProfileFragment.class,"accountID").set(profile,selected); field(ProfileFragment.class,"account").set(profile,author);
			View root=profile.onCreateContentView(LayoutInflater.from(activity),null,null);
			profile.dataLoaded();
			ViewPager2 pager=root.findViewById(R.id.pager); TabLayout tabs=root.findViewById(R.id.tabbar);
			TabLayoutMediator mediator=(TabLayoutMediator)field(ProfileFragment.class,"tabLayoutMediator").get(profile); mediator.attach();
			try{
				pager.getViewTreeObserver().dispatchOnPreDraw(); boolean nbw=id.startsWith("nbw_");
				assertEquals(nbw ? 2 : 3,tabs.getTabCount()); assertEquals(nbw ? 0 : 1,pager.getCurrentItem());
				assertEquals(activity.getString(R.string.profile_timeline),tabs.getTabAt(nbw ? 0 : 1).getText().toString());
				if(nbw) assertNull(field(ProfileFragment.class,"featuredFragment").get(profile));
				else assertNotNull(field(ProfileFragment.class,"featuredFragment").get(profile));
				AccountTimelineFragment timeline=(AccountTimelineFragment)field(ProfileFragment.class,"timelineFragment").get(profile);
				assertSame(timeline,invoke(profile,"getFragmentForPage",new Class[]{int.class},nbw ? 0 : 1));
				Account target=Parcels.unwrap(timeline.getArguments().getParcelable("profileAccount")); assertEquals(id,target.id);
				// Bind the real adapter/container without starting the child timeline/network lifecycle.
				mount(timeline); field(Fragment.class,"mAdded").setBoolean(timeline,true);
				RecyclerView.Adapter adapter=pager.getAdapter(); RecyclerView.ViewHolder holder=adapter.createViewHolder(new FrameLayout(activity),nbw ? 0 : 1);
				adapter.bindViewHolder(holder,nbw ? 0 : 1);
				assertEquals(R.id.profile_timeline,((FrameLayout)holder.itemView).getChildAt(0).getId());
				field(Fragment.class,"mAdded").setBoolean(timeline,false);
			}finally{ mediator.detach(); }
		}
	}

	@Test public void filteredNbwUidRequestPreservesOpaqueRelativeNextCursorEvenOnEmptyPage() throws Exception{
		TimelineProbe timeline=new TimelineProbe(); mount(timeline);
		field(AccountTimelineFragment.class,"user").set(timeline,author("nbw_123"));
		field(BaseStatusListFragment.class,"accountID").set(timeline,selected);
		field(AccountTimelineFragment.class,"filter").set(timeline,GetAccountStatuses.Filter.MEDIA);
		timeline.page(0); GetAccountStatuses first=(GetAccountStatuses)LocalRequests.sent.stream().filter(GetAccountStatuses.class::isInstance).findFirst().orElseThrow();
		field(MastodonAPIRequest.class,"domain").set(first,"api.abdl-space.top");
		assertEquals("/api/v1/accounts/nbw_123/statuses",first.getURL().getPath()); assertEquals("true",first.getURL().getQueryParameter("only_media"));
		String cursor="nbwa_dWlkOjEyMy9jdXJzb3I=";
		Response response=new Response.Builder().request(new Request.Builder().url(first.getURL().toString()).build()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
				.header("Link","</api/v1/accounts/nbw_123/statuses?limit=20&max_id="+Uri.encode(cursor)+">; rel=\"next\"").build();
		first.validateAndPostprocessResponse(new ArrayList<>(),response); callback(first).onSuccess(new ArrayList<Status>());
		assertTrue("Empty filtered pages with next Link remain pageable",timeline.more);
		timeline.page(0); GetAccountStatuses second=(GetAccountStatuses)LocalRequests.sent.stream().filter(GetAccountStatuses.class::isInstance).reduce((a,b)->b).orElseThrow(); field(MastodonAPIRequest.class,"domain").set(second,"api.abdl-space.top");
		assertEquals(cursor,second.getURL().getQueryParameter("max_id"));
		assertNotSame(first,second);
		for(int i=0;i<LocalRequests.sent.size();i++) if(LocalRequests.sent.get(i) instanceof GetAccountStatuses) assertEquals(selected,LocalRequests.sessions.get(i));
		second.validateAndPostprocessResponse(new ArrayList<>(),new Response.Builder().request(new Request.Builder().url(second.getURL().toString()).build()).protocol(Protocol.HTTP_1_1).code(200).message("OK").build());
		callback(second).onSuccess(new ArrayList<Status>()); assertFalse(timeline.more);
	}

	@Test public void ordinaryAndPinnedQueriesRemainExistingFirstPartyContract() throws Exception{
		for(GetAccountStatuses.Filter filter:List.of(GetAccountStatuses.Filter.DEFAULT,GetAccountStatuses.Filter.INCLUDE_REPLIES,GetAccountStatuses.Filter.PINNED)){
			GetAccountStatuses request=new GetAccountStatuses("nbw_123",null,null,20,filter,null); field(MastodonAPIRequest.class,"domain").set(request,"api.abdl-space.top");
			assertEquals("/api/v1/accounts/nbw_123/statuses",request.getURL().getPath());
			assertEquals(filter==GetAccountStatuses.Filter.DEFAULT ? "true" : null,request.getURL().getQueryParameter("exclude_replies"));
			assertEquals(filter==GetAccountStatuses.Filter.PINNED ? "true" : null,request.getURL().getQueryParameter("pinned"));
		}
	}
	@SuppressWarnings("unchecked") private static Callback<Object> callback(MastodonAPIRequest<?> request) throws Exception{ return (Callback<Object>)field(APIRequest.class,"callback").get(request); }
	private static Field field(Class<?> type,String name) throws Exception{ Field f=type.getDeclaredField(name); f.setAccessible(true); return f; }
	private static Object invoke(Object target,String name,Class<?>[] types,Object... args) throws Exception{ Method m=ProfileFragment.class.getDeclaredMethod(name,types); m.setAccessible(true); return m.invoke(target,args); }
}
