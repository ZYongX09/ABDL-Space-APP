package org.joinmastodon.android.fragments;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.app.Fragment;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.statuses.GetStatusContext;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.InstanceV1;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusContext;
import org.joinmastodon.android.ui.displayitems.HeaderStatusDisplayItem;
import org.joinmastodon.android.ui.views.ThreadSkeletonView;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.utils.V;

/** Actual production ThreadFragment/AppKit RecyclerView and callbacks; only IO/navigation captured.
 * Native graphics exercises real Canvas drawing, not a replacement screen or skeleton mock.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="en-rUS-w393dp-h852dp-mdpi",
		shadows={ThreadSkeletonBehaviorTest.LocalNavigation.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ThreadSkeletonBehaviorTest{
	/** Keep production exec, cancellation cleanup and callback delivery; intercept only transport submission. */
	private static class LocalRequests extends MastodonAPIController{
		static final List<MastodonAPIRequest<?>> sent=new ArrayList<>();
		LocalRequests(AccountSession session){ super(session); }
		@Override public <T> void submitRequest(MastodonAPIRequest<T> request){ sent.add(request); }
	}
	@Implements(Nav.class) public static class LocalNavigation{
		@Implementation public static void finish(Fragment fragment){}
	}

	private AccountSession session, previousSession;
	private Instance previousInstance;
	private String previousActive;
	private Map<String, AccountSession> sessions;
	private Map<String, Instance> instances;
	private ActivityController<FragmentStackActivity> lifecycle;
	private ThreadFragment thread;
	private boolean previousReduceMotion;
	private float previousFontScale;

	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		ShadowChoreographer.setPaused(true);
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		org.joinmastodon.android.ui.utils.UiUtils.updateLocalizedDateFormatters(MastodonApp.context);
		previousReduceMotion=GlobalUserPreferences.reduceMotion; GlobalUserPreferences.reduceMotion=false;
		previousFontScale=MastodonApp.context.getResources().getConfiguration().fontScale;
		var ctor=AccountSession.class.getDeclaredConstructor(); ctor.setAccessible(true); session=ctor.newInstance();
		session.domain="thread-skeleton.offline.example.test";
		session.self=new Account(); session.self.id="42";
		session.self.username=session.self.acct="offline_author"; session.self.displayName="Known author"; session.self.emojis=List.of();
		session.self.avatar=session.self.avatarStatic="android.resource://"+MastodonApp.context.getPackageName()+"/"+R.drawable.ic_nbw;
		AccountSessionManager manager=AccountSessionManager.getInstance();
		sessions=(Map<String, AccountSession>)field(AccountSessionManager.class, "sessions").get(manager);
		instances=(Map<String, Instance>)field(AccountSessionManager.class, "instances").get(manager);
		previousActive=(String)field(AccountSessionManager.class, "lastActiveAccountID").get(manager);
		previousSession=sessions.put(session.getID(), session);
		field(AccountSessionManager.class, "lastActiveAccountID").set(manager, session.getID());
		InstanceV1 instance=new InstanceV1(); instance.uri=instance.normalizedUri=session.domain; instance.version="4.3.0";
		previousInstance=instances.put(session.domain, instance);
		session.getLocalPreferences().serverSideFiltersSupported=false;
		field(AccountSession.class, "apiController").set(session, new LocalRequests(session));
		LocalRequests.sent.clear();
	}

	@After public void cleanup() throws Exception{
		// Restore the original session before genuine Fragment teardown (also tests account removal).
		sessions.put(session.getID(), session);
		field(AccountSessionManager.class, "lastActiveAccountID").set(AccountSessionManager.getInstance(), session.getID());
		if(lifecycle!=null) lifecycle.close();
		if(previousSession==null) sessions.remove(session.getID()); else sessions.put(session.getID(), previousSession);
		if(previousInstance==null) instances.remove(session.domain); else instances.put(session.domain, previousInstance);
		field(AccountSessionManager.class, "lastActiveAccountID").set(AccountSessionManager.getInstance(), previousActive);
		GlobalUserPreferences.reduceMotion=previousReduceMotion;
		RuntimeEnvironment.setFontScale(previousFontScale);
		LocalRequests.sent.clear(); ShadowChoreographer.setPaused(false);
	}

	@Test public void knownMainAndAuthorRenderImmediatelyWhileOnlyRepliesShimmerThenEmptySuccessStops() throws Exception{
		launch(false, false); GetStatusContext pending=lastContext();
		assertKnownPostVisible(); assertLoading();
		Bitmap image=draw(thread.getView()); assertTrue(nonTransparentPixels(image)>0); image.recycle();
		success(pending, context(List.of())); layout();
		assertEquals(List.of("p_root"), ids()); assertTrue(thread.loaded); assertFalse(thread.dataLoading);
		assertStopped(); assertEquals(View.VISIBLE, viewField("endMark").getVisibility());
		int sent=contextRequests().size(); setThreadHidden(true); setThreadHidden(false);
		assertEquals("Natural empty is a successful load, not an endless retry", sent, contextRequests().size());
	}

	@Test public void realFooterErrorRetryKeepsMainVisibleAndRetriesExactlyOnceThenRendersReplies() throws Exception{
		launch(false, false); GetStatusContext first=lastContext(); ThreadSkeletonView skeleton=skeleton();
		assertKnownPostVisible(); assertLoading(); capture("thread-light-main-replies-shimmer");
		error(first); layout(); capture("thread-light-main-replies-error");
		assertKnownPostVisible(); assertFalse(thread.dataLoading); assertNull(animator(skeleton));
		assertEquals(View.VISIBLE, viewField("footerError").getVisibility());
		assertEquals(View.GONE, skeleton.getVisibility()); assertNull(skeleton.getContentDescription());
		TextView message=viewField("footerError").findViewById(R.id.error_text); assertTrue(message.length()>0);
		View retry=viewField("footerError").findViewById(R.id.error_retry);
		assertEquals(thread.getString(R.string.thread_loading_retry), ((TextView)retry).getText().toString());
		int sent=contextRequests().size(); assertTrue(retry.performClick()); assertTrue(retry.performClick()); idle(); layout();
		assertEquals(sent+1, contextRequests().size()); assertLoading();
		capture("thread-light-main-replies-retry-shimmer");
		GetStatusContext next=lastContext();
		// A duplicate delivery from the old request must not hide/replace the new loading footer.
		lateSuccess(first, context(List.of(status("c_old")))); lateError(first); assertLoading();
		assertSame(next, field(thread.getClass(), "currentRequest").get(thread));
		success(next, context(List.of(status("c_new")))); layout();
		assertEquals(List.of("p_root", "c_new"), ids()); assertStopped(); assertFalse(thread.dataLoading);
	}

	@Test public void refreshFailureStopsSpinnerAndRetryPreservesRefreshReconciliation() throws Exception{
		launch(false, false); success(lastContext(), context(List.of(status("c_before")))); layout();
		thread.onRefresh(); GetStatusContext refresh=lastContext(); assertTrue(thread.isRefreshing()); assertLoading();
		error(refresh); assertFalse(thread.isRefreshing()); assertFalse(thread.dataLoading);
		assertEquals(List.of("p_root", "c_before"), ids());
		viewField("footerError").findViewById(R.id.error_retry).performClick();
		assertTrue(thread.isRefreshing());
		success(lastContext(), context(List.of(status("c_after")))); layout();
		assertEquals(List.of("p_root", "c_after"), ids()); assertStopped(); assertFalse(thread.isRefreshing());
	}

	@Test public void hidePauseCancelAndDestroyedViewStopAndRejectLateCallbacks() throws Exception{
		launch(false, false); GetStatusContext first=lastContext(); ThreadSkeletonView old=skeleton();
		assertLoading(); assertNotNull(animator(old)); setThreadHidden(true); assertTrue(thread.isHidden()); assertNull(animator(old));
		setThreadHidden(false); assertFalse(thread.isHidden()); assertLoading();
		int requestsBeforePause=contextRequests().size();
		lifecycle.pause(); assertNull(animator(old)); lifecycle.resume(); layout();
		assertNotNull("Pending replies must resume animation after a background/foreground cycle", animator(old));
		assertSame(first, field(thread.getClass(), "currentRequest").get(thread));
		assertEquals(requestsBeforePause, contextRequests().size());
		thread.cancelLoading(); assertFalse(thread.dataLoading); assertStopped();
		assertTrue(field(MastodonAPIRequest.class, "canceled").getBoolean(first));
		lateSuccess(first, context(List.of(status("c_cancelled")))); lateError(first); assertEquals(List.of("p_root"), ids());
		thread.doLoadData(0, 20); GetStatusContext destroyed=lastContext(); assertLoading();
		thread.getFragmentManager().beginTransaction().detach(thread).commit();
		thread.getFragmentManager().executePendingTransactions(); idle();
		assertNull(thread.getView()); assertNull(animator(old)); assertFalse(thread.dataLoading);
		lateSuccess(destroyed, context(List.of(status("c_destroyed")))); lateError(destroyed);
		assertEquals(List.of("p_root"), ids());
		lifecycle.get().getFragmentManager().beginTransaction().attach(thread).commit();
		lifecycle.get().getFragmentManager().executePendingTransactions(); idle(); layout();
		assertKnownPostVisible(); assertLoading();
		GetStatusContext fresh=lastContext(); assertNotSame(destroyed, fresh);
		lateError(destroyed); assertSame(fresh, field(thread.getClass(), "currentRequest").get(thread));
		success(fresh, context(List.of())); assertStopped();
	}

	@Test public void replacedGenerationOldAccountAndReplacedSessionCannotMutateReplies() throws Exception{
		launch(false, false); GetStatusContext old=lastContext();
		thread.doLoadData(0, 20); GetStatusContext newer=lastContext();
		assertTrue(field(MastodonAPIRequest.class, "canceled").getBoolean(old));
		lateSuccess(old, context(List.of(status("c_old")))); lateError(old); assertLoading();
		assertSame(newer, field(thread.getClass(), "currentRequest").get(thread));
		field(AccountSessionManager.class, "lastActiveAccountID").set(AccountSessionManager.getInstance(), "another-account");
		success(newer, context(List.of(status("c_wrong_account")))); assertStopped(); assertFalse(thread.dataLoading);
		assertEquals(List.of("p_root"), ids());
		field(AccountSessionManager.class, "lastActiveAccountID").set(AccountSessionManager.getInstance(), session.getID());
		thread.doLoadData(0, 20); GetStatusContext replaced=lastContext();
		var ctor=AccountSession.class.getDeclaredConstructor(); ctor.setAccessible(true);
		sessions.put(session.getID(), ctor.newInstance());
		error(replaced); assertStopped(); assertEquals(List.of("p_root"), ids()); assertFalse(thread.dataLoading);
		sessions.put(session.getID(), session);
		thread.doLoadData(0, 20); GetStatusContext removed=lastContext(); sessions.remove(session.getID());
		success(removed, context(List.of(status("c_logged_out")))); assertStopped(); assertFalse(thread.dataLoading);
		assertEquals(List.of("p_root"), ids());
	}

	@Test public void cancellationCleanupIsNotFakeErrorAndNoAutoLoadShowsNoPhantomSkeleton() throws Exception{
		launch(true, false); assertKnownPostVisible(); assertStopped(); assertTrue(contextRequests().isEmpty());
		thread.doLoadData(0, 20); assertLoading();
		GetStatusContext cancelled=lastContext();
		assertNotNull("Production request must own a cancellation cleanup", field(MastodonAPIRequest.class, "nbwCancellationCleanup").get(cancelled));
		cancelled.cancel(); idle();
		assertTrue(field(MastodonAPIRequest.class, "canceled").getBoolean(cancelled));
		assertTrue("Request cancellation must deliver its terminal cleanup", field(MastodonAPIRequest.class, "callbackDelivered").getBoolean(cancelled));
		assertStopped(); assertFalse(thread.dataLoading); assertEquals(View.GONE, viewField("footerError").getVisibility());
		assertNull(field(thread.getClass(), "currentRequest").get(thread));
		long generation=field(thread.getClass(), "repliesRequestGeneration").getLong(thread);
		cancelled.cancel(); idle();
		assertEquals("Cancellation cleanup is delivered once", generation, field(thread.getClass(), "repliesRequestGeneration").getLong(thread));
		success(cancelled, context(List.of(status("c_late")))); assertEquals(List.of("p_root"), ids());
		lateSuccess(cancelled, context(List.of(status("c_late_callback")))); assertEquals(List.of("p_root"), ids());
	}

	@Test @Config(qualifiers="zh-rCN-w320dp-h640dp-mdpi") public void dark320dpLargeFontHasOneLoadingNodeAndErrorRetryFits() throws Exception{
		RuntimeEnvironment.setFontScale(1.6f); launch(false, true); layout();
		assertEquals(320, thread.getResources().getConfiguration().screenWidthDp);
		assertEquals(1.6f, thread.getResources().getConfiguration().fontScale, .001f);
		assertKnownPostVisible(); assertLoading();
		assertEquals("正在加载回复", skeleton().getContentDescription().toString());
		assertEquals(1, nodesWithDescription(thread.getView(), "正在加载回复"));
		assertEquals(0, skeleton().createAccessibilityNodeInfo().getChildCount());
		Bitmap image=draw(skeleton()); assertTrue(nonTransparentPixels(image)>100); image.recycle();
		capture("thread-dark-320dp-font160-main-replies-shimmer");
		error(lastContext()); layout(); capture("thread-dark-320dp-font160-main-replies-error");
		View error=viewField("footerError"), retry=error.findViewById(R.id.error_retry);
		assertTrue(retry.getMeasuredHeight()>=48); assertTrue(retry.getLeft()>=0);
		assertTrue(retry.getRight()<=error.getWidth()); assertTrue(retry.getBottom()<=error.getHeight());
		assertEquals("重试", ((TextView)retry).getText().toString());
	}

	private void launch(boolean noAutoLoad, boolean dark){
		lifecycle=Robolectric.buildActivity(FragmentStackActivity.class);
		FragmentStackActivity activity=lifecycle.get();
		activity.setTheme(dark ? R.style.Theme_Mastodon_Dark : R.style.Theme_Mastodon_Light); lifecycle.create();
		thread=new ThreadFragment(); Bundle args=new Bundle(); args.putString("account", session.getID());
		args.putParcelable("status", Parcels.wrap(status("p_root"))); args.putBoolean("noAutoLoad", noAutoLoad);
		thread.setArguments(args); activity.showFragment(thread); lifecycle.start().resume().visible(); idle(); layout();
	}

	private void layout(){
		View root=lifecycle.get().getWindow().getDecorView();
		root.requestLayout(); idle();
		assertTrue("ViewRoot traversal must really attach the decor", root.isAttachedToWindow());
		assertTrue("The genuine fragment hierarchy must be attached", thread.getView().isAttachedToWindow());
		assertEquals(View.VISIBLE, root.getWindowVisibility());
		int screenHeight=Math.round(thread.getResources().getConfiguration().screenHeightDp
				*thread.getResources().getDisplayMetrics().density);
		assertTrue("Use the configured device viewport, never an enlarged 1000px test window", root.getHeight()>0 && root.getHeight()<=screenHeight);
	}

	private void setThreadHidden(boolean hidden){
		var manager=lifecycle.get().getFragmentManager();
		var transaction=manager.beginTransaction();
		if(hidden) transaction.hide(thread); else transaction.show(thread);
		transaction.commit(); manager.executePendingTransactions(); idle();
	}

	private void showFooterInViewport() throws Exception{
		layout(); RecyclerView list=(RecyclerView)viewField("list");
		assertTrue(list.getLayoutManager() instanceof LinearLayoutManager);
		LinearLayoutManager manager=(LinearLayoutManager)list.getLayoutManager();
		// UsableRecyclerView.getAdapter() unwraps the footer adapter; LayoutManager owns the actual count.
		manager.scrollToPositionWithOffset(manager.getItemCount()-1, 0); layout();
		View footer=viewField("footerView");
		RecyclerView.ViewHolder holder=list.findContainingViewHolder(footer);
		assertNotNull("The production footer must be an actual RecyclerView child", holder);
		assertSame(footer, holder.itemView);
		assertTrue(footer.isAttachedToWindow()); assertTrue(skeleton().isAttachedToWindow());
		assertEquals(View.VISIBLE, skeleton().getWindowVisibility());
		assertInViewport(footer); assertInViewport(skeleton());
	}

	private void assertInViewport(View view) throws Exception{
		Rect viewport=new Rect(), visible=new Rect();
		assertTrue(((RecyclerView)viewField("list")).getGlobalVisibleRect(viewport));
		assertTrue("View must have visible geometry in the real window", view.getGlobalVisibleRect(visible));
		assertTrue("View must intersect the RecyclerView viewport", visible.intersect(viewport) && !visible.isEmpty());
	}

	private void assertKnownPostVisible() throws Exception{
		RecyclerView list=(RecyclerView)viewField("list"); assertTrue(list.isShown());
		assertEquals(View.GONE, thread.getView().findViewById(R.id.loading).getVisibility());
		boolean header=false;
		for(int i=0;i<list.getChildCount();i++){
			RecyclerView.ViewHolder holder=list.getChildViewHolder(list.getChildAt(i));
			if(holder instanceof HeaderStatusDisplayItem.Holder){ header=true; assertTrue(holder.itemView.isShown()); }
		}
		assertTrue("Genuine author header must already be on screen", header);
		assertTrue(allText(thread.getView()).contains("Known author"));
		assertTrue(allText(thread.getView()).contains("Known main body"));
	}

	private void assertLoading() throws Exception{
		showFooterInViewport(); ThreadSkeletonView skeleton=skeleton();
		assertTrue(thread.dataLoading); assertEquals(View.VISIBLE, viewField("footerView").getVisibility());
		assertEquals(View.VISIBLE, skeleton.getVisibility()); assertTrue(skeleton.isShown());
		assertEquals(View.GONE, viewField("footerError").getVisibility());
		assertEquals(View.GONE, viewField("endMark").getVisibility());
		assertNotNull(skeletonDiagnostic(skeleton), animator(skeleton));
		assertEquals(1, nodesWithDescription(thread.getView(), thread.getString(R.string.thread_loading_replies)));
		assertEquals(List.of("p_root"), ids().stream().filter(id->id.equals("p_root")).collect(Collectors.toList()));
	}

	private void assertStopped() throws Exception{
		assertNull(animator(skeleton())); assertEquals(View.GONE, viewField("footerView").getVisibility());
		assertNull(skeleton().getContentDescription());
		assertEquals(0, nodesWithDescription(thread.getView(), thread.getString(R.string.thread_loading_replies)));
	}

	private ThreadSkeletonView skeleton() throws Exception{ return (ThreadSkeletonView)viewField("footerProgress"); }
	private String skeletonDiagnostic(ThreadSkeletonView view){
		return "attached="+view.isAttachedToWindow()+", shown="+view.isShown()+", visibility="+view.getVisibility()
				+", windowVisibility="+view.getWindowVisibility()+", size="+view.getWidth()+"x"+view.getHeight()
				+", reduceMotion="+GlobalUserPreferences.reduceMotion+", animators="+ValueAnimator.areAnimatorsEnabled()
				+", dataLoading="+thread.dataLoading+", resumed="+thread.isResumed()+", hidden="+thread.isHidden();
	}
	private View viewField(String name) throws Exception{ return (View)field(thread.getClass(), name).get(thread); }
	private Status status(String id){
		Status status=Status.ofFake(id, "<p>Known main body</p>", Instant.parse("2026-10-22T00:00:00Z"));
		status.account=session.self; status.uri=status.url="https://"+session.domain+"/statuses/"+id;
		if(!id.equals("p_root")) status.inReplyToId="p_root";
		return status;
	}
	private static StatusContext context(List<Status> descendants){
		StatusContext result=new StatusContext(); result.ancestors=List.of(); result.descendants=descendants; return result;
	}
	@SuppressWarnings("unchecked") private List<String> ids() throws Exception{
		return ((List<Status>)field(thread.getClass(), "data").get(thread)).stream().map(s->s.id).collect(Collectors.toList());
	}
	private static List<GetStatusContext> contextRequests(){
		return LocalRequests.sent.stream().filter(GetStatusContext.class::isInstance).map(GetStatusContext.class::cast).collect(Collectors.toList());
	}
	private static GetStatusContext lastContext(){ List<GetStatusContext> requests=contextRequests(); return requests.get(requests.size()-1); }
	private static ValueAnimator animator(ThreadSkeletonView view) throws Exception{ return (ValueAnimator)field(ThreadSkeletonView.class, "animator").get(view); }
	private static void success(MastodonAPIRequest<?> request, Object result) throws Exception{
		Method dispatch=MastodonAPIRequest.class.getDeclaredMethod("dispatchSuccess", Object.class);
		dispatch.setAccessible(true); dispatch.invoke(request, result); idle();
	}
	private static void error(MastodonAPIRequest<?> request) throws Exception{
		Method dispatch=MastodonAPIRequest.class.getDeclaredMethod("dispatchError", me.grishka.appkit.api.ErrorResponse.class);
		dispatch.setAccessible(true); dispatch.invoke(request, new MastodonErrorResponse("offline", 503, null)); idle();
	}
	/** Bypass transport only for deliberately adversarial late/duplicate deliveries. */
	@SuppressWarnings("unchecked") private static void lateSuccess(MastodonAPIRequest<?> request, Object result) throws Exception{
		((Callback<Object>)field(APIRequest.class, "callback").get(request)).onSuccess(result); idle();
	}
	@SuppressWarnings("unchecked") private static void lateError(MastodonAPIRequest<?> request) throws Exception{
		((Callback<Object>)field(APIRequest.class, "callback").get(request)).onError(new MastodonErrorResponse("offline", 503, null)); idle();
	}
	private static String allText(View view){
		StringBuilder text=new StringBuilder();
		if(view instanceof TextView label) text.append(label.getText()).append('\n');
		if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) text.append(allText(group.getChildAt(i)));
		return text.toString();
	}
	private static int nodesWithDescription(View view, String description){
		if(view==null || view.getVisibility()!=View.VISIBLE) return 0;
		int count=description.contentEquals(view.getContentDescription()==null ? "" : view.getContentDescription()) ? 1 : 0;
		if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) count+=nodesWithDescription(group.getChildAt(i), description);
		return count;
	}
	private void capture(String name) throws Exception{
		layout();
		ValueAnimator running=animator(skeleton());
		if(running!=null) running.setCurrentPlayTime(700);
		// Draw the actual production Fragment hierarchy: real main post, AppKit list and footer.
		Bitmap image=draw(thread.getView());
		try{
			File directory=new File(System.getProperty("threadLoadingRenderDir",
					"/home/ZYongX/.local/share/abdl-post-translation-20261102/android-ui-renders/thread"));
			assertTrue(directory.isDirectory() || directory.mkdirs());
			try(FileOutputStream output=new FileOutputStream(new File(directory, name+".png"))){
				assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
			}
		}finally{ image.recycle(); }
	}

	private static Bitmap draw(View view){
		Bitmap image=Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); view.draw(new Canvas(image)); return image;
	}
	private static int nonTransparentPixels(Bitmap bitmap){
		int count=0; for(int y=0;y<bitmap.getHeight();y+=2) for(int x=0;x<bitmap.getWidth();x+=2) if((bitmap.getPixel(x,y)>>>24)!=0) count++;
		return count;
	}
	private static Field field(Class<?> type, String name) throws NoSuchFieldException{
		for(Class<?> current=type;current!=null;current=current.getSuperclass()){
			try{ Field field=current.getDeclaredField(name); field.setAccessible(true); return field; }catch(NoSuchFieldException ignored){}
		}
		throw new NoSuchFieldException(name);
	}
	private static void idle(){
		for(int frame=0;frame<3;frame++){
			// Advance actual vsync/traversal; current-time idle alone can leave ViewRoot's barrier installed.
			ShadowLooper.getShadowMainLooper().idleFor(ShadowChoreographer.getFrameDelay());
			ShadowLooper.idleMainLooper();
		}
	}
}
