package org.joinmastodon.android.ui.displayitems;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Fragment;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.PopupMenu;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.statuses.GetStatusSourceText;
import org.joinmastodon.android.fragments.ComposeFragment;
import org.joinmastodon.android.fragments.ProfileFragment;
import org.joinmastodon.android.fragments.friendcards.FriendCardCreateFragment;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.FriendCardAttachment;
import org.joinmastodon.android.model.Status;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.utils.V;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class,
		shadows={FriendCardEntryRoutingTest.LocalNavigation.class, FriendCardEntryRoutingTest.LocalRequests.class})
public class FriendCardEntryRoutingTest{
	@Implements(Nav.class) public static class LocalNavigation{
		static final List<Class<? extends Fragment>> destinations=new ArrayList<>();
		static final List<Bundle> arguments=new ArrayList<>();
		@Implementation public static void go(Activity activity, Class<? extends Fragment> destination, Bundle args){
			assertNotNull(activity); destinations.add(destination); arguments.add(new Bundle(args));
		}
	}
	@Implements(MastodonAPIRequest.class) public static class LocalRequests{
		@RealObject MastodonAPIRequest<?> request;
		static final List<MastodonAPIRequest<?>> sent=new ArrayList<>();
		static final List<String> accounts=new ArrayList<>();
		@Implementation public MastodonAPIRequest<?> exec(String account){ sent.add(request); accounts.add(account); return request; }
	}
	private ActivityController<Activity> controller;
	private Activity activity;

	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		controller=Robolectric.buildActivity(Activity.class); activity=controller.get();
		activity.setTheme(R.style.Theme_Mastodon_Light); controller.setup();
		LocalNavigation.destinations.clear(); LocalNavigation.arguments.clear(); LocalRequests.sent.clear(); LocalRequests.accounts.clear();
	}
	@After public void cleanup(){ controller.close(); }

	private Status status(String mode){
		Status status=Status.ofFake("p_101", "<p>Body</p>", Instant.parse("2026-10-18T12:30:00Z"));
		Account account=new Account(); account.id="42"; account.username=account.acct="owner"; account.displayName="Owner"; account.emojis=List.of(); status.account=account;
		FriendCardAttachment attachment=new FriendCardAttachment(); attachment.schemaVersion=1; attachment.id="card-1";
		attachment.mode=mode; attachment.available=true; attachment.title="Card title"; status.friendCard=attachment; return status;
	}
	private PopupMenu headerMenu(Status status) throws Exception{
		HeaderStatusDisplayItem item=new HeaderStatusDisplayItem(status.id, status.account, status.createdAt,
				new StatusDisplayItem.NoOpCallbacks(activity), activity, "selected-account", status, null);
		HeaderStatusDisplayItem.Holder holder=new HeaderStatusDisplayItem.Holder(activity,new FrameLayout(activity)); holder.bind(item);
		return (PopupMenu)field(HeaderStatusDisplayItem.Holder.class,"optionsMenu").get(holder);
	}

	@Test public void publicationEditGoesDirectlyToCardEditorWithAccountAndId() throws Exception{
		for(boolean available:List.of(true,false)){
			Status status=status("publication"); status.friendCard.available=available;
			assertTrue(headerMenu(status).getMenu().performIdentifierAction(R.id.edit,0));
		}
		assertEquals(List.of(FriendCardCreateFragment.class,FriendCardCreateFragment.class),LocalNavigation.destinations);
		for(Bundle args:LocalNavigation.arguments){
			assertEquals("selected-account",args.getString("account")); assertEquals("card-1",args.getString("card_id")); assertFalse(args.containsKey("editStatus"));
		}
		assertTrue("Publication is not edited as projected ordinary text",LocalRequests.sent.isEmpty());
	}

	@Test public void ordinaryLinkEditLoadsPureSourceAndPassesReferenceForComposeEcho() throws Exception{
		assertTrue(headerMenu(status("link")).getMenu().performIdentifierAction(R.id.edit,0));
		assertTrue(LocalNavigation.destinations.isEmpty()); assertEquals(1,LocalRequests.sent.size());
		MastodonAPIRequest<?> request=LocalRequests.sent.get(0); assertTrue(request instanceof GetStatusSourceText);
		assertEquals(List.of("selected-account"),LocalRequests.accounts);
		GetStatusSourceText.Response source=new GetStatusSourceText.Response(); source.id="p_101"; source.text="Original & plain"; source.spoilerText="CW"; source.friendCardId="card-1";
		callback(request).onSuccess(source);
		assertEquals(List.of(ComposeFragment.class),LocalNavigation.destinations);
		Bundle args=LocalNavigation.arguments.get(0); assertEquals(source.text,args.getString("sourceText"));
		assertEquals(source.spoilerText,args.getString("sourceSpoiler")); assertEquals("card-1",args.getString("sourceFriendCardId"));
	}

	@Test public void ownProfileMenuNoLongerContainsMyCardsEntryOrHandler() throws Exception{
		Fragment probe=new Fragment(); activity.getFragmentManager().beginTransaction().add(probe,"probe").commit(); activity.getFragmentManager().executePendingTransactions();
		ProfileFragment profile=new ProfileFragment(); field(Fragment.class,"mHost").set(profile,field(Fragment.class,"mHost").get(probe));
		field(ProfileFragment.class,"accountID").set(profile,"selected-account"); field(ProfileFragment.class,"isOwnProfile").setBoolean(profile,true);
		PopupMenu menu=new PopupMenu(activity,new View(activity)); menu.inflate(R.menu.profile_own);
		assertNull(menu.getMenu().findItem(R.id.my_friend_cards));
		profile.onOptionsItemSelected(menu.getMenu().add(0, R.id.my_friend_cards, 0, "Removed cards"));
		assertTrue(LocalNavigation.destinations.isEmpty());
	}

	@Test public void firstPartyForumAndCanonicalLinksResolveSameTypedPostOnly(){
		for(String url:List.of("https://abdl-space.top/forum/123", "https://abdl-space.top/forum/123/?share=1", "https://api.abdl-space.top/api/v1/statuses/123", "https://abdl-space.top/api/v1/statuses/p_123"))
			assertEquals(url,"p_123",MainActivity.statusIDFromSharedURL(Uri.parse(url)));
		for(String url:List.of("https://evil.example/forum/123", "https://abdl-space.top.evil.example/forum/123", "https://evil@abdl-space.top/forum/123", "https://abdl-space.top/forum/%31%32%33", "https://abdl-space.top/forum/p_123", "https://abdl-space.top/forum/123/extra", "https://abdl-space.top/api/v1/statuses/c_123", "https://abdl-space.top/@user/p_123", "https://abdl-space.top:9443/forum/123", "javascript:alert(1)"))
			assertNull(url,MainActivity.statusIDFromSharedURL(Uri.parse(url)));
	}

	@SuppressWarnings("unchecked") private static Callback<Object> callback(MastodonAPIRequest<?> request) throws Exception{ return (Callback<Object>)field(APIRequest.class,"callback").get(request); }
	private static Field field(Class<?> type,String name) throws Exception{ Field field=type.getDeclaredField(name); field.setAccessible(true); return field; }
}
