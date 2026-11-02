package org.joinmastodon.android.fragments;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.statuses.TranslateStatus;
import org.joinmastodon.android.api.requests.translation.GetTranslationPreferences;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.AccountLoggedOutEvent;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.FilterContext;
import org.joinmastodon.android.model.Quote;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.Translation;
import org.joinmastodon.android.model.TranslationPreferences;
import org.joinmastodon.android.translation.PostTranslationCoordinator;
import org.joinmastodon.android.ui.displayitems.HeaderStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.SpoilerStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.TextStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.TranslationStatusDisplayItem;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import androidx.recyclerview.widget.RecyclerView;
import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

/** Real AppKit/Fragment/RecyclerView lifecycle, native holders and production coordinator; no HTTP leaves the process. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="en-w393dp-h852dp-mdpi", shadows=PostTranslationFragmentTest.OfflineRequests.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class PostTranslationFragmentTest{
	@Implements(MastodonAPIRequest.class) public static class OfflineRequests{
		@RealObject MastodonAPIRequest<?> request;
		static final List<MastodonAPIRequest<?>> sent=new ArrayList<>(), canceled=new ArrayList<>();
		@Implementation public MastodonAPIRequest<?> exec(String account){ sent.add(request); return request; }
		@Implementation public void cancel(){ canceled.add(request); } // Late responses deliberately remain deliverable.
	}
	public static class HostActivity extends FragmentStackActivity{}
	public static class Timeline extends StatusListFragment{
		@Override public android.net.Uri getWebUri(android.net.Uri.Builder base){ return base.path("/").build(); }
		@Override protected FilterContext getFilterContext(){ return FilterContext.HOME; }
		@Override protected void doLoadData(int offset, int count){}
		@Override protected void loadRelationships(java.util.Set<String> ids){}
		void install(Status... statuses){
			data.clear(); displayItems.clear();
			for(Status status:statuses){ data.add(status); displayItems.addAll(buildDisplayItems(status)); }
			loaded=true; adapter.notifyDataSetChanged();
			if(content!=null) content.setVisibility(View.VISIBLE);
			if(list!=null) list.setVisibility(View.VISIBLE);
		}
		RecyclerView recycler(){ return list; }
	}
	private ActivityController<HostActivity> lifecycle;
	private HostActivity activity;
	private Timeline fragment;
	private AccountSession session, previousSession;
	private Map<String, AccountSession> sessions;
	private String accountID, previousActive;
	private PostTranslationCoordinator coordinator;
	private static Field field(Class<?> type, String name) throws Exception{ Field value=type.getDeclaredField(name); value.setAccessible(true); return value; }
	private Object invoke(String method, Class<?>[] types, Object... args) throws Exception{ Method value=BaseStatusListFragment.class.getDeclaredMethod(method, types); value.setAccessible(true); return value.invoke(fragment, args); }
	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context); GlobalUserPreferences.showCWs=true;
		var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true); session=constructor.newInstance(); session.domain="translation-fragment.offline.example.test";
		session.self=new Account(); session.self.id="42"; session.self.username=session.self.acct="offline"; session.self.displayName="Offline"; session.self.emojis=List.of();
		session.self.sponsor=new Account.PublicSponsor(); session.self.sponsor.active=true; session.self.sponsor.permanent=true; accountID=session.getID();
		var manager=AccountSessionManager.getInstance(); sessions=(Map<String,AccountSession>)field(AccountSessionManager.class, "sessions").get(manager); previousSession=sessions.put(accountID,session); previousActive=manager.getLastActiveAccountID(); field(AccountSessionManager.class,"lastActiveAccountID").set(manager,accountID);
		OfflineRequests.sent.clear(); OfflineRequests.canceled.clear(); lifecycle=Robolectric.buildActivity(HostActivity.class); activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.setup();
		fragment=new Timeline(); Bundle arguments=new Bundle(); arguments.putString("account",accountID); fragment.setArguments(arguments); activity.showFragment(fragment); activity.getFragmentManager().executePendingTransactions(); ShadowLooper.idleMainLooper();
		coordinator=PostTranslationCoordinator.get(activity,accountID); invoke("startPostTranslations",new Class<?>[]{});
	}
	@After public void cleanup() throws Exception{
		if(coordinator!=null) coordinator.dispose();
		if(lifecycle!=null) lifecycle.close();
		if(sessions!=null){ if(previousSession==null) sessions.remove(accountID); else sessions.put(accountID,previousSession); field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),previousActive); }
		OfflineRequests.sent.clear();
	}
	private Status post(String id){
		Status result=Status.ofFake(id,"<p>原文正文</p>",Instant.parse("2026-11-01T00:00:00Z")); result.language="zh";
		Account account=new Account(); account.id="99"; account.username=account.acct="owner"; account.displayName="Owner"; account.emojis=List.of();
		account.avatar=account.avatarStatic="android.resource://"+MastodonApp.context.getPackageName()+"/"+R.drawable.default_avatar;
		result.account=account; return result;
	}
	@SuppressWarnings("unchecked") private void success(MastodonAPIRequest<?> request,Object value) throws Exception{ ((Callback<Object>)field(APIRequest.class,"callback").get(request)).onSuccess(value); ShadowLooper.idleMainLooper(); }
	private <T> List<MastodonAPIRequest<?>> requests(Class<T> type){ return OfflineRequests.sent.stream().filter(type::isInstance).toList(); }
	private void preferences(boolean automatic) throws Exception{
		List<MastodonAPIRequest<?>> calls=requests(GetTranslationPreferences.class); assertFalse("Fragment must load capability on entry", calls.isEmpty());
		TranslationPreferences value=new TranslationPreferences(); value.readingLanguage="en"; value.revision="revision-1"; value.enabled=value.providerConfigured=value.autoTranslateAllowed=true; value.autoTranslateEnabled=automatic; success(calls.get(calls.size()-1),value);
	}
	private Translation ready(){ Translation result=new Translation(); result.content="<p>Translated body</p>"; result.spoilerText="Translated warning"; result.detectedSourceLanguage="zh"; result.provider="ABDL Space AI"; result.sourceHash="server-source-1"; result.targetLanguage="en"; return result; }
	private void layout(){
		ViewGroup host=activity.findViewById(me.grishka.appkit.R.id.fragment_wrap); assertNotNull(host);
		host.forceLayout(); host.measure(View.MeasureSpec.makeMeasureSpec(393,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(852,View.MeasureSpec.EXACTLY)); host.layout(0,0,393,852);
		fragment.recycler().forceLayout(); fragment.recycler().measure(View.MeasureSpec.makeMeasureSpec(393,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.EXACTLY)); fragment.recycler().layout(0,0,393,700); ShadowLooper.idleMainLooper();
	}
	private StatusDisplayItem.Holder<?> holder(StatusDisplayItem item){
		RecyclerView list=fragment.recycler();
		for(int i=0;i<list.getChildCount();i++) if(list.getChildViewHolder(list.getChildAt(i)) instanceof StatusDisplayItem.Holder<?> holder && holder.getItem()==item) return holder;
		throw new AssertionError("No attached production holder for "+item.getType());
	}
	private TranslationStatusDisplayItem module(Status status){ return fragment.getDisplayItems().stream().filter(item->item instanceof TranslationStatusDisplayItem translation && translation.contentStatus==status).map(item->(TranslationStatusDisplayItem)item).findFirst().orElseThrow(); }
	private boolean attachedInViewport(StatusDisplayItem item){
		RecyclerView list=fragment.recycler(); android.graphics.Rect viewport=new android.graphics.Rect(), bounds=new android.graphics.Rect();
		if(!list.getGlobalVisibleRect(viewport)) return false;
		for(int i=0;i<list.getChildCount();i++){
			View child=list.getChildAt(i);
			if(list.getChildViewHolder(child) instanceof StatusDisplayItem.Holder<?> holder && holder.getItem()==item)
				return child.isShown() && child.getGlobalVisibleRect(bounds) && android.graphics.Rect.intersects(viewport,bounds);
		}
		return false;
	}
	private void listViewport(int height){
		RecyclerView list=fragment.recycler();
		list.measure(View.MeasureSpec.makeMeasureSpec(393,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY)); list.layout(0,0,393,height); ShadowLooper.idleMainLooper();
	}
	private void scrollToItem(StatusDisplayItem item){
		RecyclerView list=fragment.recycler(); assertTrue(list.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager);
		((androidx.recyclerview.widget.LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(fragment.getDisplayItems().indexOf(item)+fragment.getMainAdapterOffset(),0);
		listViewport(700);
	}

	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
	public void autoContinuesWhenModuleScrollsOffscreenButBodyRemainsVisible() throws Exception{
		preferences(false);
		Status parent=post("scroll-parent"), quote=post("scroll-quote"); quote.content="<p>很长的原文正文，用于真实视口滚动，翻译模块离屏后仍然阅读同一段正文。</p>".repeat(60);
		parent.quote=new Quote(); parent.quote.state=Quote.State.ACCEPTED; parent.quote.quotedStatus=quote;
		fragment.install(parent); layout(); TranslationStatusDisplayItem item=module(quote);
		TextStatusDisplayItem body=(TextStatusDisplayItem)fragment.getDisplayItems().stream().filter(display->display instanceof TextStatusDisplayItem text && text.status==quote).findFirst().orElseThrow();
		TranslationStatusDisplayItem parentModule=module(parent);
		TextStatusDisplayItem parentBody=(TextStatusDisplayItem)fragment.getDisplayItems().stream().filter(display->display instanceof TextStatusDisplayItem text && text.status==parent).findFirst().orElseThrow();
		assertTrue(item.isQuote); assertTrue(body.isQuote);
		scrollToItem(body); assertFalse("The translation module must actually be offscreen",attachedInViewport(item)); assertTrue("The quoted body must still be in the viewport",attachedInViewport(body));
		assertFalse("The parent module must not authorize an automatic request",attachedInViewport(parentModule)); assertFalse("The parent body must also be outside the viewport",attachedInViewport(parentBody));
		coordinator.refreshPreferences(true); preferences(true); invoke("checkVisibleTranslations",new Class<?>[]{});
		assertEquals(1,requests(TranslateStatus.class).size()); MastodonAPIRequest<?> request=requests(TranslateStatus.class).get(0);
		assertEquals("/statuses/scroll-quote/translate",field(MastodonAPIRequest.class,"path").get(request));
		fragment.recycler().scrollBy(0,80); listViewport(700); invoke("checkVisibleTranslations",new Class<?>[]{});
		assertFalse(attachedInViewport(item)); assertTrue(attachedInViewport(body)); assertFalse("Reading farther down the same body must not cancel its POST",OfflineRequests.canceled.contains(request));
		assertFalse(attachedInViewport(parentModule)); assertFalse(attachedInViewport(parentBody));
		// Keep translated content long: replacing sixty paragraphs with one would expose the parent
		// through RecyclerView's legitimate layout re-anchoring, creating an unrelated automatic request.
		Translation translated=ready(); translated.content="<p>A long translated quote remains readable while its translation module is outside the viewport.</p>".repeat(60);
		success(request,translated); layout();
		assertFalse("The translated quote must not shrink and re-expose the parent module",attachedInViewport(parentModule)); assertFalse(attachedInViewport(parentBody));
		assertTrue(attachedInViewport(body)); assertEquals(Status.TranslationState.SHOWN,quote.translationState); assertNull(parent.translation); assertEquals(Status.TranslationState.HIDDEN,parent.translationState); assertEquals(1,requests(TranslateStatus.class).size());
	}
	@Test public void headerOnlyDoesNotStartAuto() throws Exception{
		preferences(false); Status post=post("header-only"); fragment.install(post); layout();
		HeaderStatusDisplayItem header=(HeaderStatusDisplayItem)fragment.getDisplayItems().stream().filter(item->item instanceof HeaderStatusDisplayItem).findFirst().orElseThrow();
		TranslationStatusDisplayItem item=module(post); listViewport(V.dp(32)); assertTrue(attachedInViewport(header)); assertFalse(attachedInViewport(item));
		coordinator.refreshPreferences(true); preferences(true); invoke("checkVisibleTranslations",new Class<?>[]{}); assertTrue("Header exposure cannot prefetch a translation",requests(TranslateStatus.class).isEmpty());
	}
	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
	public void fullyOffscreenOrCollapsedCwCancelsAuto() throws Exception{
		preferences(false); Status first=post("offscreen-first"), second=post("offscreen-second"); second.content="<p>第二篇帖子正文</p>".repeat(60);
		fragment.install(first,second); layout(); TranslationStatusDisplayItem firstModule=module(first);
		coordinator.refreshPreferences(true); preferences(true); invoke("checkVisibleTranslations",new Class<?>[]{});
		MastodonAPIRequest<?> firstRequest=requests(TranslateStatus.class).stream().filter(request->{ try{ return "/statuses/offscreen-first/translate".equals(field(MastodonAPIRequest.class,"path").get(request)); }catch(Exception error){ throw new AssertionError(error); } }).findFirst().orElseThrow();
		TextStatusDisplayItem secondBody=(TextStatusDisplayItem)fragment.getDisplayItems().stream().filter(item->item instanceof TextStatusDisplayItem text && text.status==second).findFirst().orElseThrow(); scrollToItem(secondBody); invoke("checkVisibleTranslations",new Class<?>[]{});
		assertFalse(attachedInViewport(firstModule)); assertTrue(OfflineRequests.canceled.contains(firstRequest)); success(firstRequest,ready()); assertNull(first.translation);
		Status warning=post("collapsed-after-scroll"); warning.spoilerText="Warning"; warning.revealedSpoilers.add(Status.SpoilerType.CONTENT_WARNING); fragment.install(warning); layout(); invoke("checkVisibleTranslations",new Class<?>[]{});
		MastodonAPIRequest<?> cwRequest=requests(TranslateStatus.class).stream().filter(request->{ try{ return "/statuses/collapsed-after-scroll/translate".equals(field(MastodonAPIRequest.class,"path").get(request)); }catch(Exception error){ throw new AssertionError(error); } }).findFirst().orElseThrow();
		SpoilerStatusDisplayItem cw=(SpoilerStatusDisplayItem)fragment.getDisplayItems().stream().filter(item->item instanceof SpoilerStatusDisplayItem).findFirst().orElseThrow(); fragment.onRevealSpoilerClick((SpoilerStatusDisplayItem.Holder)holder(cw)); layout(); invoke("checkVisibleTranslations",new Class<?>[]{});
		assertTrue(OfflineRequests.canceled.contains(cwRequest)); success(cwRequest,ready()); assertNull(warning.translation); assertEquals(Status.TranslationState.HIDDEN,warning.translationState);
	}

	@Test public void visibleAutoWaitsForCwExpansionAndExpiresWithSponsorWithoutRebinding() throws Exception{
		preferences(true); Status post=post("100"); post.spoilerText="Warning"; post.revealedSpoilers.clear(); fragment.install(post); layout(); invoke("checkVisibleTranslations",new Class<?>[]{}); assertTrue(requests(TranslateStatus.class).isEmpty());
		SpoilerStatusDisplayItem cw=(SpoilerStatusDisplayItem)fragment.getDisplayItems().stream().filter(item->item instanceof SpoilerStatusDisplayItem).findFirst().orElseThrow();
		fragment.onRevealSpoilerClick((SpoilerStatusDisplayItem.Holder)holder(cw)); layout(); invoke("checkVisibleTranslations",new Class<?>[]{}); assertEquals(1,requests(TranslateStatus.class).size());
		session.self.sponsor.permanent=false; session.self.sponsor.validUntil=System.currentTimeMillis()/1000-1; ShadowLooper.idleMainLooper(1,java.util.concurrent.TimeUnit.SECONDS);
		success(requests(TranslateStatus.class).get(0),ready()); assertNull(post.translation); assertEquals(Status.TranslationState.HIDDEN,post.translationState);
	}
	@Test public void quoteManualUpdatesOnlyRealResourceAndOldFooterMenuRemainInSync() throws Exception{
		preferences(false); Status parent=post("parent"), quote=post("quote-real"); parent.quote=new Quote(); parent.quote.state=Quote.State.ACCEPTED; parent.quote.quotedStatus=quote; fragment.install(parent); layout();
		TranslationStatusDisplayItem item=module(quote); ((TextView)holder(item).itemView.findViewById(R.id.post_translation_action)).performClick(); assertEquals(1,requests(TranslateStatus.class).size());
		assertEquals("/statuses/quote-real/translate",field(MastodonAPIRequest.class,"path").get(requests(TranslateStatus.class).get(0))); success(requests(TranslateStatus.class).get(0),ready()); layout();
		assertEquals(Status.TranslationState.SHOWN,quote.translationState); assertEquals(Status.TranslationState.HIDDEN,parent.translationState); assertNull(parent.translation);
		TextView action=holder(item).itemView.findViewById(R.id.post_translation_action); assertEquals("Show original",action.getText().toString());
		HeaderStatusDisplayItem header=(HeaderStatusDisplayItem)fragment.getDisplayItems().stream().filter(display->display instanceof HeaderStatusDisplayItem h && h.status==parent).findFirst().orElseThrow();
		Method update=HeaderStatusDisplayItem.Holder.class.getDeclaredMethod("updateOptionsMenu"); update.setAccessible(true); update.invoke(holder(header)); PopupMenu menu=(PopupMenu)field(HeaderStatusDisplayItem.Holder.class,"optionsMenu").get(holder(header)); assertEquals("Translate post",menu.getMenu().findItem(R.id.translate).getTitle().toString());
		assertTrue(action.performClick()); layout(); assertEquals(Status.TranslationState.HIDDEN,quote.translationState); assertEquals(1,requests(TranslateStatus.class).size());
	}
	@Test public void reblogTargetsContentAndHiddenScreenRejectsOldAsyncCompletion() throws Exception{
		preferences(false); Status content=post("real-content"), boost=post("wrapper"); boost.reblog=content; fragment.install(boost); layout(); fragment.togglePostTranslation(content,boost.id);
		assertEquals("/statuses/real-content/translate",field(MastodonAPIRequest.class,"path").get(requests(TranslateStatus.class).get(0)));
		invoke("stopPostTranslations",new Class<?>[]{}); success(requests(TranslateStatus.class).get(0),ready()); assertNull(content.translation); assertNull(boost.translation); assertEquals(Status.TranslationState.HIDDEN,content.translationState);
	}
	@Test public void switchedAccountAndLogoutFenceCurrentFragmentCallbacks() throws Exception{
		preferences(false); Status post=post("101"); fragment.install(post); layout(); fragment.togglePostTranslation(post,post.id);
		field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),"other-account"); success(requests(TranslateStatus.class).get(0),ready()); assertNull(post.translation);
		field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),accountID); coordinator.loggedOut(new AccountLoggedOutEvent(accountID)); assertEquals(Status.TranslationState.HIDDEN,post.translationState);
	}
	@Test public void collapsedAfterAutomaticRequestCancelsWithoutOverwritingOriginal() throws Exception{
		preferences(true); Status post=post("102"); post.spoilerText="Warning"; post.revealedSpoilers.add(Status.SpoilerType.CONTENT_WARNING); fragment.install(post); layout(); invoke("checkVisibleTranslations",new Class<?>[]{}); assertEquals(1,requests(TranslateStatus.class).size());
		SpoilerStatusDisplayItem cw=(SpoilerStatusDisplayItem)fragment.getDisplayItems().stream().filter(item->item instanceof SpoilerStatusDisplayItem).findFirst().orElseThrow(); fragment.onRevealSpoilerClick((SpoilerStatusDisplayItem.Holder)holder(cw)); layout(); invoke("checkVisibleTranslations",new Class<?>[]{}); success(requests(TranslateStatus.class).get(0),ready()); assertNull(post.translation); assertEquals("<p>原文正文</p>",post.content);
	}
}
