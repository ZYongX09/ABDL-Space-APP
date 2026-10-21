package org.joinmastodon.android.friendcards;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.fragments.AppKitFragment;
import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.friendcards.FriendCardInput;
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.friendcards.FriendCardBaseFragment;
import org.joinmastodon.android.fragments.friendcards.FriendCardCreateFragment;
import org.joinmastodon.android.fragments.friendcards.FriendCardDiaperSelectFragment;
import org.joinmastodon.android.fragments.friendcards.FriendCardFeedFragment;
import org.joinmastodon.android.fragments.settings.BabyVerificationFragment;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.FriendCard;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

/** Executes production Fragment lifecycle and callbacks; view rendering and remote services are replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, shadows={FriendCardFragmentBehaviorTest.LocalRequests.class, FriendCardFragmentBehaviorTest.LocalNavigation.class, FriendCardFragmentBehaviorTest.LocalViews.class})
public class FriendCardFragmentBehaviorTest {
    private Activity activity;
    private FriendCardCreateFragment fragment;
    private AccountSession session;
    private String account;
    @Implements(MastodonAPIRequest.class)
    public static class LocalRequests {
        @RealObject MastodonAPIRequest<?> request;
        static final List<MastodonAPIRequest<?>> requests=new ArrayList<>();
        @Implementation public MastodonAPIRequest<?> exec(String account){ requests.add(request); return request; }
        @Implementation public void cancel(){} // Late callbacks still arrive to exercise generation checks.
    }
    @Implements(FriendCardBaseFragment.class)
    public static class LocalViews {
        @RealObject FriendCardBaseFragment fragment;
        @Implementation public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle state){ return new FrameLayout(fragment.getActivity()); }
    }
    @Implements(Nav.class)
    public static class LocalNavigation {
        static Class<?> target;
        static Bundle args;
        @Implementation public static void go(Activity activity, Class<? extends Fragment> type, Bundle bundle){ target=type; args=new Bundle(bundle); }
        @Implementation public static void goForResult(Activity activity, Class<? extends AppKitFragment> type, Bundle bundle, int code, AppKitFragment receiver){ target=type; args=new Bundle(bundle); }
        @Implementation public static void finish(Fragment fragment){}
    }
    private static Field field(Class<?> type, String name) throws Exception { Field value=type.getDeclaredField(name); value.setAccessible(true); return value; }
    private Object get(String name) throws Exception { return field(FriendCardCreateFragment.class,name).get(fragment); }
    private void set(String name,Object value) throws Exception { field(FriendCardCreateFragment.class,name).set(fragment,value); }
    private Object invoke(String name,Class<?>[] types,Object... values) throws Exception { Method method=FriendCardCreateFragment.class.getDeclaredMethod(name,types); method.setAccessible(true); return method.invoke(fragment,values); }
    private FriendCardDraft draft() throws Exception { return (FriendCardDraft)get("draft"); }
    private MastodonAPIRequest<?> request(String suffix) throws Exception {
        for(int i=LocalRequests.requests.size()-1;i>=0;i--) if(((String)field(MastodonAPIRequest.class,"path").get(LocalRequests.requests.get(i))).endsWith(suffix)) return LocalRequests.requests.get(i);
        throw new AssertionError("Missing request "+suffix);
    }
    @SuppressWarnings("unchecked") private static void success(MastodonAPIRequest<?> request,Object response) throws Exception { ((Callback<Object>)field(APIRequest.class,"callback").get(request)).onSuccess(response); }
    @SuppressWarnings("unchecked") private static void failure(MastodonAPIRequest<?> request,int status) throws Exception { ((Callback<Object>)field(APIRequest.class,"callback").get(request)).onError(new MastodonErrorResponse("测试网络错误",status,null)); }
    @Before @SuppressWarnings("unchecked") public void setup() throws Exception {
        MastodonApp.context=RuntimeEnvironment.getApplication();
        activity=Robolectric.buildActivity(Activity.class).setup().get(); activity.setTheme(R.style.Theme_Mastodon_Light);
        activity.getSharedPreferences("friend_card_drafts_v1",0).edit().clear().commit();
        var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true); session=constructor.newInstance();
        session.domain="offline.example.test"; session.self=new Account(); session.self.id="42"; account=session.getID();
        ((Map<String,AccountSession>)field(AccountSessionManager.class,"sessions").get(AccountSessionManager.getInstance())).put(account,session);
        field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),account);
        LocalRequests.requests.clear(); LocalNavigation.target=null; LocalNavigation.args=null;
        fragment=new FriendCardCreateFragment(); Bundle args=new Bundle(); args.putString("account",account); fragment.setArguments(args);
        activity.getFragmentManager().beginTransaction().add(fragment,"friend-card-create").commit(); activity.getFragmentManager().executePendingTransactions();
        field(FriendCardBaseFragment.class,"shown").set(fragment,true);
        LocalRequests.requests.clear();
        invoke("refresh",new Class<?>[]{});
    }
    @After public void cleanup(){ activity.getSharedPreferences("friend_card_drafts_v1",0).edit().clear().commit(); activity.finish(); }
    private FriendCardResponses.Options options(String verification){
        FriendCardResponses.Options result=new FriendCardResponses.Options(); result.schemaVersion=1; result.games=new ArrayList<>(); result.interestPresets=new ArrayList<>(); result.verificationState=verification; result.canCreate="active".equals(verification); return result;
    }
    private void ready(String verification) throws Exception { success(request("/options"),options(verification)); success(request("/me"),new FriendCardResponses.Me()); }
    private void edit(String title,String habits) throws Exception {
        Function1<FriendCardInput,Unit> mutation=input->{ input.title=title; input.lookingFor="找朋友"; input.interactionHabits=habits; return Unit.INSTANCE; };
        invoke("edit",new Class<?>[]{Function1.class},mutation);
    }
    @Test public void realCreateEntryOnlyGuidesCertificationAndSavesBeforeNavigation() throws Exception {
        ready("not_started");
        FriendCardResponses.Options eligibility=(FriendCardResponses.Options)get("options"); assertFalse(eligibility.canCreate);
        invoke("openVerification",new Class<?>[]{});
        assertEquals(BabyVerificationFragment.class,LocalNavigation.target); assertEquals(account,LocalNavigation.args.getString("account"));
        assertEquals(draft().getDraftId(),new FriendCardDraftStore(activity).load(account,null).getDraftId());
        assertEquals("",draft().getInput().interactionHabits);
        assertEquals(2,LocalRequests.requests.size());
    }
    @Test public void unavailableEligibilityStaysAnErrorInsteadOfFalseUnverifiedOrEmptySuccess() throws Exception {
        failure(request("/options"),503); failure(request("/me"),404);
        assertNull(get("options")); assertNotNull(get("eligibilityError")); assertNotNull(get("currentError"));
        assertFalse(draft().getCurrentLoaded());
        invoke("publish",new Class<?>[]{}); assertNull(draft().getPending()); assertEquals(2,LocalRequests.requests.size());
    }
    @Test public void realEditsPersistAndReturnRechecksEligibilityWithoutLosingInputs() throws Exception {
        ready("active"); edit("保留宣言","慢回复，但认真回\n熟悉后语音");
        invoke("stage",new Class<?>[]{int.class},1);
        FriendCardDraft saved=new FriendCardDraftStore(activity).load(account,null);
        assertEquals("保留宣言",saved.getInput().title); assertEquals(1,saved.getStage());
        invoke("onHidden",new Class<?>[]{});
        Method shown=FriendCardBaseFragment.class.getDeclaredMethod("onShown"); shown.setAccessible(true); shown.invoke(fragment);
        assertEquals("保留宣言",draft().getInput().title); assertEquals(saved.getInput().interactionHabits,draft().getInput().interactionHabits);
        assertEquals(4,LocalRequests.requests.size());
    }
    private Bundle result(String account,String draftId,List<Integer> ids){ Bundle value=new Bundle(); value.putString("account",account); value.putString("draft_id",draftId); value.putIntegerArrayList("diaper_ids",new ArrayList<>(ids)); value.putString("diaper_json","[]"); return value; }
    @Test public void diaperCancelAndWrongAccountOrDraftNeverChangeFormAndDoneZeroClears() throws Exception {
        ready("active"); edit("仍然保留","自由互动习惯");
        Function1<FriendCardInput,Unit> mutation=input->{ input.diaperIds=new ArrayList<>(List.of(7,3)); return Unit.INSTANCE; };
        invoke("edit",new Class<?>[]{Function1.class},mutation);
        String id=draft().getDraftId();
        set("pickerDraft",id); fragment.onFragmentResult(6281,false,result(account,id,List.of())); assertEquals(List.of(7,3),draft().getInput().diaperIds);
        set("pickerDraft",id); fragment.onFragmentResult(6281,true,result("other",id,List.of())); assertEquals(List.of(7,3),draft().getInput().diaperIds);
        set("pickerDraft",id); fragment.onFragmentResult(6281,true,result(account,"other-draft",List.of())); assertEquals(List.of(7,3),draft().getInput().diaperIds);
        set("pickerDraft",id); fragment.onFragmentResult(6281,true,result(account,id,List.of())); assertTrue(draft().getInput().diaperIds.isEmpty());
        assertEquals("仍然保留",draft().getInput().title); assertEquals("自由互动习惯",new FriendCardDraftStore(activity).load(account,null).getInput().interactionHabits);
    }
    @Test public void realPickerNavigationCarriesAccountDraftAndOrderedPreselection() throws Exception {
        ready("active");
        invoke("edit",new Class<?>[]{Function1.class},(Function1<FriendCardInput,Unit>)(input->{ input.diaperIds=new ArrayList<>(List.of(5,2)); return Unit.INSTANCE; }));
        invoke("selectDiapers",new Class<?>[]{});
        assertEquals(FriendCardDiaperSelectFragment.class,LocalNavigation.target); assertEquals(account,LocalNavigation.args.getString("account"));
        assertEquals(draft().getDraftId(),LocalNavigation.args.getString("draft_id")); assertEquals(List.of(5,2),LocalNavigation.args.getIntegerArrayList("selected_ids"));
    }
    @Test public void pendingNetworkTimeoutKeepsSameSnapshotOnActualPublishRetry() throws Exception {
        ready("active"); edit("发布原内容","原习惯"); invoke("publish",new Class<?>[]{});
        FriendCardOperation first=draft().getPending(); assertNotNull(first);
        MastodonAPIRequest<?> create=request("/friend-cards"); failure(create,0);
        assertFalse(draft().getPending().getRejected());
        invoke("publish",new Class<?>[]{});
        assertEquals(first.getOperationId(),draft().getPending().getOperationId()); assertEquals(first.getInputJson(),draft().getPending().getInputJson());
        invoke("edit",new Class<?>[]{Function1.class},(Function1<FriendCardInput,Unit>)(input->{ input.title="不应被修改"; return Unit.INSTANCE; }));
        assertEquals("发布原内容",draft().getInput().title);
    }
    @Test public void lateOptionsFromHiddenPageCannotChangeEligibility() throws Exception {
        MastodonAPIRequest<?> stale=request("/options"); invoke("onHidden",new Class<?>[]{});
        success(stale,options("active")); assertNull(get("options"));
    }
    private FriendCardFeedFragment feed() throws Exception {
        FriendCardFeedFragment feed=new FriendCardFeedFragment(); Bundle args=new Bundle(); args.putString("account",account); feed.setArguments(args);
        activity.getFragmentManager().beginTransaction().add(feed,"friend-card-feed").commit(); activity.getFragmentManager().executePendingTransactions();
        field(FriendCardBaseFragment.class,"shown").set(feed,true);
        LocalRequests.requests.clear();
        pages(feed).reset();
        feedMethod(feed,"refresh").invoke(feed);
        return feed;
    }
    // Only feed reflection follows the compatibility subclass's private hub owner.
    // Create-fragment field()/invoke() keep their exact declaring-class contracts.
    private Field feedField(FriendCardFeedFragment feed,String name) throws Exception {
        for(Class<?> type=feed.getClass();type!=null;type=type.getSuperclass()) {
            try { return field(type,name); } catch(NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(feed.getClass().getName()+"."+name);
    }
    private Method feedMethod(FriendCardFeedFragment feed,String name) throws Exception {
        for(Class<?> type=feed.getClass();type!=null;type=type.getSuperclass()) {
            try { Method method=type.getDeclaredMethod(name); method.setAccessible(true); return method; }
            catch(NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(feed.getClass().getName()+"."+name+"()");
    }
    private FriendCardPages pages(FriendCardFeedFragment feed) throws Exception { return (FriendCardPages)feedField(feed,"pages").get(feed); }
    private FriendCardResponses.Page page(String cursor,String... ids) {
        FriendCardResponses.Page page=new FriendCardResponses.Page(); page.cards=new ArrayList<>(); page.nextCursor=cursor;
        for(String id:ids){ FriendCard value=new FriendCard(); value.id=id; value.schemaVersion=1; value.title="宣言"; page.cards.add(value); }
        return page;
    }
    private String cursor(MastodonAPIRequest<?> request) throws Exception {
        List<?> queries=(List<?>)field(MastodonAPIRequest.class,"queryParams").get(request);
        if(queries!=null) for(Object item:queries){ android.util.Pair<?,?> query=(android.util.Pair<?,?>)item; if("cursor".equals(query.first)) return (String)query.second; }
        return null;
    }
    @Test public void emptyFirstFeedBatchFollowsCursorUntilReachableCards() throws Exception {
        FriendCardFeedFragment feed=feed();
        success(request("/feed"),page("second"));
        assertEquals(2,LocalRequests.requests.size()); assertTrue(pages(feed).getLoading()); assertEquals("second",cursor(request("/feed")));
        success(request("/feed"),page(null,"reachable"));
        assertEquals("reachable",pages(feed).getCards().get(0).id); assertNull(pages(feed).getNextCursor()); assertFalse(pages(feed).getLoading());
    }
    @Test public void emptyAndDuplicateMiddleBatchesAdvanceWithoutPagerMovement() throws Exception {
        FriendCardFeedFragment feed=feed(); success(request("/feed"),page("middle","a"));
        feedMethod(feed,"load").invoke(feed);
        success(request("/feed"),page("duplicates"));
        assertEquals("duplicates",cursor(request("/feed"))); assertTrue(pages(feed).getLoading());
        success(request("/feed"),page("last","a"));
        assertEquals("last",cursor(request("/feed"))); assertTrue(pages(feed).getLoading());
        success(request("/feed"),page(null,"b"));
        assertEquals(2,pages(feed).getCards().size()); assertEquals("a",pages(feed).getCards().get(0).id); assertEquals("b",pages(feed).getCards().get(1).id);
        assertEquals(4,LocalRequests.requests.size()); assertFalse(pages(feed).getLoading());
    }
    @Test public void repeatedAndCyclicEmptyFeedCursorsStopRatherThanLoop() throws Exception {
        FriendCardFeedFragment feed=feed(); success(request("/feed"),page("repeat")); success(request("/feed"),page("repeat"));
        assertEquals(2,LocalRequests.requests.size()); assertNull(pages(feed).getNextCursor()); assertFalse(pages(feed).getLoading()); assertTrue(pages(feed).getCards().isEmpty());
        pages(feed).reset();
        feedMethod(feed,"load").invoke(feed);
        success(request("/feed"),page("a")); success(request("/feed"),page("b")); success(request("/feed"),page("a"));
        assertEquals(5,LocalRequests.requests.size()); assertNull(pages(feed).getNextCursor()); assertFalse(pages(feed).getLoading());
    }
}
