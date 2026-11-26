package org.joinmastodon.android.ui.displayitems;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.NBWEndpointFixtures;
import org.joinmastodon.android.api.NBWV2RepositoryTest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.NBWForumOptionsRepository;
import org.joinmastodon.android.fragments.ComposeFragment;
import org.joinmastodon.android.fragments.ThreadFragment;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.InstanceV2;
import org.joinmastodon.android.model.NBWCapabilities;
import org.joinmastodon.android.model.NBWEndpoints;
import org.joinmastodon.android.model.NBWForumOptions;
import org.joinmastodon.android.model.NBWSelection;
import org.joinmastodon.android.model.Quote;
import org.joinmastodon.android.model.QuoteApproval;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.ui.utils.UiUtils;
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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.util.ReflectionHelpers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.Nav;
import me.grishka.appkit.utils.V;

/** Real layouts, holder bindings and chooser intents; HTTP and fragment starts are captured offline. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="en-w360dp-h720dp-mdpi",
		shadows={PostInlineActionsTest.LocalRequests.class, PostInlineActionsTest.LocalNavigation.class})
public class PostInlineActionsTest{
	private record Request(String account, String path){}

	@Implements(MastodonAPIRequest.class) public static class LocalRequests{
		@RealObject MastodonAPIRequest<?> request;
		static final List<Request> sent=new ArrayList<>();
		@Implementation public MastodonAPIRequest<?> exec(String account){
			sent.add(new Request(account, ReflectionHelpers.getField(request, "path")));
			return request;
		}
	}

	@Implements(Nav.class) public static class LocalNavigation{
		static Class<? extends Fragment> destination;
		static Bundle args;
		@Implementation public static void go(Activity activity, Class<? extends Fragment> target, Bundle arguments){
			destination=target;
			args=new Bundle(arguments);
		}
	}

	private AccountSession session;
	private InstanceV2 instance;
	private Map<String, AccountSession> sessions;
	private Map<String, Object> instances;
	private NBWEndpointFixtures endpoints;
	private NBWForumOptionsRepository forum;
	private boolean oldShowCounts;

	@Before public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication();
		V.setApplicationContext(MastodonApp.context);
		UiUtils.updateLocalizedDateFormatters(MastodonApp.context);
		oldShowCounts=GlobalUserPreferences.showInteractionCounts;
		GlobalUserPreferences.showInteractionCounts=true;
		LocalRequests.sent.clear();
		LocalNavigation.destination=null;
		LocalNavigation.args=null;
		endpoints=new NBWEndpointFixtures();
		var ctor=AccountSession.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		session=ctor.newInstance();
		session.domain="inline-actions.offline.example.test";
		session.self=author("42", "viewer");
		instance=new InstanceV2();
		instance.domain=instance.normalizedUri=session.domain;
		instance.version="4.4.0";
		instance.apiVersions=Map.of("mastodon", 7L);
		AccountSessionManager manager=AccountSessionManager.getInstance();
		sessions=ReflectionHelpers.getField(manager, "sessions");
		instances=ReflectionHelpers.getField(manager, "instances");
		sessions.put(session.getID(), session);
		instances.put(session.domain, instance);
	}

	@After public void cleanup() throws Exception{
		if(forum!=null) forum.deactivate();
		if(sessions!=null) sessions.remove(session.getID());
		if(instances!=null) instances.remove(session.domain);
		if(endpoints!=null) endpoints.close();
		GlobalUserPreferences.showInteractionCounts=oldShowCounts;
	}

	@Test public void cardRendersExactlyFourAccessibleActionsWithThemedShareAtPhoneWidth(){
		for(int theme:new int[]{R.style.Theme_Mastodon_Light, R.style.Theme_Mastodon_Dark}){
			try(ActivityController<Activity> controller=activity(theme)){
				Activity activity=controller.get();
				Status post=status("p_card", "Card body");
				post.reblogged=true;
				post.reblogsCount=87;
				post.favourited=true;
				post.bookmarked=true;
				ViewGroup row=(ViewGroup)footer(activity, post, 0).itemView;
				row.measure(View.MeasureSpec.makeMeasureSpec(V.dp(360), View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(V.dp(42), View.MeasureSpec.EXACTLY));
				row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
				List<Integer> actions=new ArrayList<>();
				int lastRight=row.getPaddingLeft();
				for(int i=0; i<row.getChildCount(); i++){
					View child=row.getChildAt(i);
					if(child.getVisibility()!=View.VISIBLE || !child.isClickable()) continue;
					actions.add(child.getId());
					assertTrue(child.isEnabled());
					assertTrue(child.getWidth()>0 && child.getLeft()>=lastRight);
					assertTrue(child.getRight()<=row.getWidth()-row.getPaddingRight());
					lastRight=child.getRight();
				}
				assertEquals(List.of(R.id.favorite_btn, R.id.reply_btn, R.id.bookmark_btn, R.id.share_btn), actions);
				assertTrue(row.findViewById(R.id.favorite_btn).isSelected());
				assertTrue(row.findViewById(R.id.bookmark_btn).isSelected());
				assertEquals(UiUtils.abbreviateNumber(post.favouritesCount), ((TextView)row.findViewById(R.id.favorite)).getText().toString());
				ImageView shareIcon=row.findViewById(R.id.share);
				assertEquals(V.dp(20), shareIcon.getDrawable().getIntrinsicWidth());
				assertEquals(V.dp(20), shareIcon.getDrawable().getIntrinsicHeight());
				assertEquals(UiUtils.getThemeColor(activity, R.attr.colorM3Outline), shareIcon.getImageTintList().getDefaultColor());
				View share=row.findViewById(R.id.share_btn);
				assertTrue(share.isFocusable());
				var node=share.createAccessibilityNodeInfo();
				assertEquals(Button.class.getName(), node.getClassName().toString());
				assertEquals(activity.getString(R.string.button_share), node.getContentDescription().toString());
				assertTrue(node.isClickable());
				assertEquals(87, post.reblogsCount);
				assertTrue(post.reblogged);
			}
		}
		assertTrue(LocalRequests.sent.isEmpty());
	}

	@Test public void cardClicksActOnDisplayedContentNotTheBoostWrapperOrEmbeddedQuote(){
		try(ActivityController<Activity> controller=activity(R.style.Theme_Mastodon_Light)){
			Activity activity=controller.get();
			Status wrapped=status("p_boost", "Boost wrapper"), content=status("p_content", "Displayed body");
			wrapped.reblog=content;
			content.quote=new Quote();
			content.quote.state=Quote.State.ACCEPTED;
			content.quote.quotedStatus=status("p_quoted", "Embedded quote");
			View row=footer(activity, wrapped, 0).itemView;
			assertEquals(content.url, share(activity, row).getStringExtra(Intent.EXTRA_TEXT));
			assertTrue(row.findViewById(R.id.reply_btn).performClick());
			assertEquals(ThreadFragment.class, LocalNavigation.destination);
			assertEquals(session.getID(), LocalNavigation.args.getString("account"));
			Status opened=Parcels.unwrap(LocalNavigation.args.getParcelable("status"));
			assertEquals(content.id, opened.id);
			assertTrue(row.findViewById(R.id.favorite_btn).performClick());
			assertTrue(row.findViewById(R.id.bookmark_btn).performClick());
			assertEquals(List.of(new Request(session.getID(), "/statuses/p_content/favourite"),
					new Request(session.getID(), "/statuses/p_content/bookmark")), LocalRequests.sent);
			assertTrue(content.favourited && content.bookmarked);
			assertFalse(wrapped.favourited || wrapped.bookmarked);
		}
	}

	@Test public void nbwFallbackKeepsReplyReadOnlyAndShareUsesExistingEndpointResolution() throws Exception{
		endpoints.publish("backup", "endpoint-b", "read_only");
		forum=new NBWForumOptionsRepository(new NBWForumOptionsRepository.Transport(){
			@Override public NBWForumOptionsRepository.Cancelable capabilities(NBWForumOptionsRepository.Result<NBWCapabilities> result){
				result.success(NBWV2RepositoryTest.capabilities("read_only")); return ()->{};
			}
			@Override public NBWForumOptionsRepository.Cancelable options(NBWForumOptionsRepository.Result<NBWForumOptions> result){
				result.success(NBWV2RepositoryTest.options()); return ()->{};
			}
			@Override public NBWForumOptionsRepository.Cancelable consent(boolean accepted, NBWForumOptionsRepository.Result<Object> result){
				throw new AssertionError("Rendering actions must not request consent");
			}
		}, ()->0L, new NBWForumOptionsRepository.SelectionStore(){
			@Override public NBWSelection load(){ return NBWSelection.ai(); }
			@Override public void save(NBWSelection value){}
		}, ()->{}, Runnable::run);
		ReflectionHelpers.setField(session, "nbwForumOptionsRepository", forum);
		forum.activate();
		try(ActivityController<Activity> controller=activity(R.style.Theme_Mastodon_Light)){
			Activity activity=controller.get();
			Status remote=status("nbw_123", "Remote body");
			String path="/forum.php?mod=viewthread&tid=123";
			remote.url=remote.uri=NBWEndpoints.PRIMARY+path;
			FooterStatusDisplayItem.Holder holder=footer(activity, remote, 0);
			View row=holder.itemView;
			assertTrue(row.findViewById(R.id.reply_btn).isEnabled());
			assertFalse(row.findViewById(R.id.favorite_btn).isEnabled());
			assertFalse(row.findViewById(R.id.bookmark_btn).isEnabled());
			assertEquals(NBWEndpoints.BACKUP+path, share(activity, row).getStringExtra(Intent.EXTRA_TEXT));

			endpoints.publish("backup", "endpoint-c", "local_only");
			holder.rebind();
			assertFalse(row.findViewById(R.id.reply_btn).isEnabled());
			assertFalse(row.findViewById(R.id.favorite_btn).isEnabled());
			assertFalse(row.findViewById(R.id.bookmark_btn).isEnabled());
			assertEquals("", share(activity, row).getStringExtra(Intent.EXTRA_TEXT));

			Status local=status("p_local", "Local body");
			holder.bind(footerItem(activity, local, 0));
			assertTrue(row.findViewById(R.id.reply_btn).isEnabled());
			assertTrue(row.findViewById(R.id.favorite_btn).isEnabled());
			assertTrue(row.findViewById(R.id.bookmark_btn).isEnabled());
			assertEquals(local.url, share(activity, row).getStringExtra(Intent.EXTRA_TEXT));
			assertTrue(LocalRequests.sent.isEmpty());
		}
	}

	@Test public void postOverflowKeepsQuoteComposerAndRespectsExistingVisibilityAndPolicy(){
		try(ActivityController<Activity> controller=activity(R.style.Theme_Mastodon_Light)){
			Activity activity=controller.get();
			Status wrapped=status("p_boost", "Boost wrapper"), content=status("p_quote_target", "Quote target");
			wrapped.reblog=content;
			content.quoteApproval=new QuoteApproval();
			content.quoteApproval.automatic=EnumSet.of(QuoteApproval.Policy.PUBLIC);
			content.quoteApproval.currentUser=QuoteApproval.CurrentUserPolicy.MANUAL;
			assertTrue(quoteMenu(activity, wrapped, 0).getMenu().performIdentifierAction(R.id.quote_post, 0));
			assertEquals(ComposeFragment.class, LocalNavigation.destination);
			assertEquals(session.getID(), LocalNavigation.args.getString("account"));
			Status quoted=Parcels.unwrap(LocalNavigation.args.getParcelable("quote"));
			assertEquals(content.id, quoted.id);

			content.quoteApproval.currentUser=QuoteApproval.CurrentUserPolicy.DENIED;
			assertTrue(quoteMenu(activity, wrapped, 0).getMenu().performIdentifierAction(R.id.quote_post, 0));
			assertFalse(LocalNavigation.args.containsKey("quote"));
			assertEquals("\n\n@"+content.account.acct+" "+content.url, LocalNavigation.args.getString("prefilledText"));
			for(StatusPrivacy visibility:List.of(StatusPrivacy.PRIVATE, StatusPrivacy.DIRECT, StatusPrivacy.LOCAL)){
				content.visibility=visibility;
				assertFalse(quoteMenu(activity, wrapped, 0).getMenu().findItem(R.id.quote_post).isVisible());
			}
			content.visibility=StatusPrivacy.PRIVATE;
			content.account=session.self;
			assertTrue(quoteMenu(activity, wrapped, 0).getMenu().findItem(R.id.quote_post).isVisible());
			assertFalse(quoteMenu(activity, wrapped, StatusDisplayItem.FLAG_NO_FOOTER).getMenu().findItem(R.id.quote_post).isVisible());
			assertFalse(quoteMenu(activity, status("nbw_123", "External post"), 0).getMenu().findItem(R.id.quote_post).isVisible());
			assertTrue(LocalRequests.sent.isEmpty());
		}
	}

	private static ActivityController<Activity> activity(int theme){
		ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class);
		controller.get().setTheme(theme);
		return controller.setup();
	}

	private static Account author(String id, String name){
		Account author=new Account();
		author.id=id;
		author.username=author.acct=author.displayName=name;
		author.emojis=List.of();
		author.avatar=author.avatarStatic="android.resource://"+MastodonApp.context.getPackageName()+"/"+R.drawable.default_avatar;
		return author;
	}

	private Status status(String id, String body){
		Status status=Status.ofFake(id, "<p>"+body+"</p>", Instant.parse("2026-11-20T00:00:00Z"));
		status.account=author("59", "author");
		status.url=status.uri="https://"+session.domain+"/statuses/"+id;
		status.favouritesCount=7;
		status.repliesCount=6;
		status.bookmarksCount=3;
		return status;
	}

	private List<StatusDisplayItem> build(Activity activity, Status status, int flags){
		return StatusDisplayItem.buildItems(activity, status, session.getID(), status, Map.of(), flags);
	}

	private FooterStatusDisplayItem footerItem(Activity activity, Status status, int flags){
		List<FooterStatusDisplayItem> footers=build(activity, status, flags).stream()
				.filter(FooterStatusDisplayItem.class::isInstance).map(FooterStatusDisplayItem.class::cast).toList();
		assertEquals(1, footers.size());
		assertSame(status.getContentStatus(), footers.get(0).status);
		return footers.get(0);
	}

	private FooterStatusDisplayItem.Holder footer(Activity activity, Status status, int flags){
		FooterStatusDisplayItem.Holder holder=(FooterStatusDisplayItem.Holder)StatusDisplayItem.createViewHolder(
				StatusDisplayItem.Type.FOOTER, activity, new FrameLayout(activity), null);
		holder.bind(footerItem(activity, status, flags));
		return holder;
	}

	private PopupMenu quoteMenu(Activity activity, Status status, int flags){
		HeaderStatusDisplayItem item=build(activity, status, flags).stream()
				.filter(HeaderStatusDisplayItem.class::isInstance).map(HeaderStatusDisplayItem.class::cast).findFirst().orElseThrow();
		HeaderStatusDisplayItem.Holder holder=new HeaderStatusDisplayItem.Holder(activity, new FrameLayout(activity));
		holder.bind(item);
		ReflectionHelpers.callInstanceMethod(holder, "updateOptionsMenu");
		return ReflectionHelpers.getField(holder, "optionsMenu");
	}

	private static Intent share(Activity activity, View row){
		View button=row.findViewById(R.id.share_btn);
		assertEquals(View.VISIBLE, button.getVisibility());
		assertTrue(button.isEnabled() && button.isClickable());
		assertTrue(button.performClick());
		ShadowActivity shadow=org.robolectric.shadow.api.Shadow.extract(activity);
		Intent chooser=shadow.getNextStartedActivity();
		assertNotNull(chooser);
		assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
		Intent send=chooser.getParcelableExtra(Intent.EXTRA_INTENT);
		assertNotNull(send);
		assertEquals(Intent.ACTION_SEND, send.getAction());
		assertEquals("text/plain", send.getType());
		return send;
	}
}
