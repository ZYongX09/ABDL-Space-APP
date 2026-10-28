package org.joinmastodon.android;

import static org.joinmastodon.android.auth.HumanCheckTestSupport.*;
import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Dialog;
import android.app.Fragment;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.joinmastodon.android.api.requests.auth.HumanCheckRequest;
import org.joinmastodon.android.api.session.AccountLocalPreferences;
import org.joinmastodon.android.auth.HumanCheckCoordinator;
import org.joinmastodon.android.auth.HumanCheckCoordinator.Action;
import org.joinmastodon.android.auth.HumanCheckCoordinator.Handle;
import org.joinmastodon.android.auth.HumanCheckCoordinator.Phase;
import org.joinmastodon.android.auth.HumanCheckTestSupport;
import org.joinmastodon.android.fragments.auth.LoginCodeFragment;
import org.joinmastodon.android.fragments.auth.LoginEmailFragment;
import org.joinmastodon.android.ui.utils.UiUtils;
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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowWebView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import me.grishka.appkit.Nav;
import me.grishka.appkit.fragments.AppKitFragment;
import me.grishka.appkit.utils.V;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="zh-rCN-w393dp-h852dp-notnight-mdpi",
		shadows={Requests.class,Sessions.class,Features.class,WebMessages.class,Factory.class,HumanCheckActivityTest.Navigation.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class HumanCheckActivityTest{
	@Implements(Nav.class) public static class Navigation{
		static int navigations;
		static Class<?> destination;
		static Bundle arguments;
		@Implementation public static void go(Activity activity, Class<? extends Fragment> target, Bundle args){ navigations++; destination=target; arguments=new Bundle(args); }
	}
	private final List<ActivityController<?>> activities=new ArrayList<>();
	private HumanCheckCoordinator flow;
	private GlobalUserPreferences.ThemePreference oldTheme;
	private AccountLocalPreferences.ColorPreference oldColor;
	@Before public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context); reset(); flow=HumanCheckCoordinator.getInstance();
		oldTheme=GlobalUserPreferences.theme; oldColor=GlobalUserPreferences.color;
		GlobalUserPreferences.theme=GlobalUserPreferences.ThemePreference.LIGHT; GlobalUserPreferences.color=AccountLocalPreferences.ColorPreference.PURPLE;
		Navigation.navigations=0; Navigation.destination=null; Navigation.arguments=null;
	}
	@After public void cleanup() throws Exception{
		for(var activity:activities) activity.close(); reset();
		GlobalUserPreferences.theme=oldTheme; GlobalUserPreferences.color=oldColor;
	}
	private static ShadowActivity shadow(Activity activity){ return org.robolectric.shadow.api.Shadow.extract(activity); }
	private ActivityController<HumanCheckActivity> prompt() throws Exception{
		Handle entry=flow.begin(EMAIL,Action.SEND,()->{}); required(last());
		var life=Robolectric.buildActivity(HumanCheckActivity.class,HumanCheckActivity.intent(MastodonApp.context,entry)).setup(); activities.add(life); return life;
	}
	private AppKitFragment entry(boolean resend) throws Exception{
		var life=Robolectric.buildActivity(Activity.class).setup(); activities.add(life); Activity host=life.get(); UiUtils.setUserPreferredTheme(host);
		FrameLayout container=new FrameLayout(host); container.setId(0x1020088); host.setContentView(container);
		AppKitFragment fragment=resend ? new LoginCodeFragment() : new LoginEmailFragment(); Bundle args=new Bundle(); args.putString("email",EMAIL); fragment.setArguments(args);
		host.getFragmentManager().beginTransaction().add(container.getId(),fragment,"entry").commit(); host.getFragmentManager().executePendingTransactions();
		assertNotNull(fragment.getView());
		if(resend){
			android.os.CountDownTimer timer=(android.os.CountDownTimer)field(LoginCodeFragment.class,"resendTimer").get(fragment);
			var countdown=org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowCountDownTimer>extract(timer);
			assertEquals(60_000L,countdown.getMillisInFuture()); assertFalse(fragment.getView().findViewById(R.id.tv_resend).isEnabled());
			countdown.invokeFinish(); assertTrue(fragment.getView().findViewById(R.id.tv_resend).isEnabled());
			fragment.getView().findViewById(R.id.tv_resend).performClick();
		}
		else{ ((EditText)fragment.getView().findViewById(R.id.email_edit)).setText(EMAIL); ((CheckBox)fragment.getView().findViewById(R.id.cb_agreement)).setChecked(true); fragment.getView().findViewById(R.id.btn_send_code).performClick(); }
		return fragment;
	}
	private ActivityController<HumanCheckActivity> launch(AppKitFragment entry) throws Exception{
		required(last()); Intent intent=shadow(entry.getActivity()).getNextStartedActivity(); assertNotNull(intent);
		assertEquals(Set.of("generation","owner"),intent.getExtras().keySet()); assertNull(intent.getData()); assertFalse(intent.toString().contains(EMAIL));
		var life=Robolectric.buildActivity(HumanCheckActivity.class,intent).setup(); activities.add(life); return life;
	}
	private void load(HumanCheckActivity activity) throws Exception{ activity.findViewById(R.id.human_check_start).performClick(); challenge(last()); assertNotNull(bridge); }
	@Test public void promptCreationHasZeroWebViewsAndZeroChallengeRequests() throws Exception{
		var page=prompt().get(); assertEquals(1,sent.size()); assertEquals(0,factoryCalls); assertNull(page.findViewById(R.id.human_check_webview));
		assertEquals(page.getString(R.string.human_check_start),((TextView)page.findViewById(R.id.human_check_start)).getText().toString());
	}
	@Test public void startShowsOneCenteredLoadingModalBeforeChallengeResponse() throws Exception{
		var page=prompt().get(); page.findViewById(R.id.human_check_start).performClick(); Dialog dialog=ShadowDialog.getLatestDialog();
		assertTrue(dialog.isShowing()); assertEquals(android.view.Gravity.CENTER,dialog.getWindow().getAttributes().gravity);
		assertEquals(View.VISIBLE,dialog.findViewById(R.id.human_check_modal_progress).getVisibility());
		assertEquals(page.getString(R.string.human_check_loading),((TextView)dialog.findViewById(R.id.human_check_modal_status)).getText().toString());
		page.findViewById(R.id.human_check_start).performClick(); assertSame(dialog,ShadowDialog.getLatestDialog()); assertEquals(2,sent.size()); assertEquals(0,factoryCalls);
		challenge(last()); assertEquals(1,factoryCalls); assertSame(dialog,ShadowDialog.getLatestDialog());
	}
	@Test public void pageFinishedIsNotReadinessAndReadyNeverReturnsSuccess() throws Exception{
		var page=prompt().get(); load(page); WebView web=bridgedView;
		org.robolectric.shadow.api.Shadow.<ShadowWebView>extract(web).getWebViewClient().onPageFinished(web,HumanCheckRequest.pageUrl(CID)); Dialog dialog=ShadowDialog.getLatestDialog();
		assertEquals(View.VISIBLE,dialog.findViewById(R.id.human_check_modal_progress).getVisibility());
		event("ready"); assertEquals(View.GONE,dialog.findViewById(R.id.human_check_modal_progress).getVisibility()); assertFalse(page.isFinishing()); assertEquals(2,sent.size());
		event("verifying"); assertEquals(View.VISIBLE,dialog.findViewById(R.id.human_check_modal_progress).getVisibility()); assertFalse(page.isFinishing());
	}
	@Test public void promptBackReturnsCancelledAndNeverLeaksEmailInResult() throws Exception{
		var page=prompt().get(); page.onBackPressed(); assertTrue(page.isFinishing()); assertEquals(Activity.RESULT_CANCELED,shadow(page).getResultCode());
		assertNull(shadow(page).getResultIntent()); assertEquals(HumanCheckRequest.Endpoint.CANCEL,last().endpoint); assertEquals(0,factoryCalls);
	}
	@Test public void modalCloseAndBackCancelAttemptOnlyAndReturnToPrompt() throws Exception{
		var page=prompt().get(); load(page); Dialog dialog=ShadowDialog.getLatestDialog(); dialog.findViewById(R.id.human_check_modal_close).performClick();
		assertFalse(dialog.isShowing()); assertFalse(page.isFinishing()); assertEquals(HumanCheckRequest.Endpoint.CHALLENGE_CANCEL,last().endpoint);
		cancelled(last()); assertTrue(page.findViewById(R.id.human_check_start).isEnabled()); load(page); page.onBackPressed();
		assertEquals(HumanCheckRequest.Endpoint.CHALLENGE_CANCEL,last().endpoint); assertFalse(page.isFinishing());
	}
	@Test public void forgedWebMessagesAreRejectedByRealOriginFrameAndStrictPayloadGuards() throws Exception{
		var page=prompt().get(); load(page); var listener=bridge; WebView web=bridgedView;
		for(String origin:new String[]{"https://api.abdl-space.top.evil","http://api.abdl-space.top","https://challenges.cloudflare.com"})
			listener.onPostMessage(web,new androidx.webkit.WebMessageCompat("{\"event\":\"ready\",\"challenge_id\":\""+CID+"\"}"),Uri.parse(origin),true,null);
		listener.onPostMessage(web,new androidx.webkit.WebMessageCompat("{\"event\":\"ready\",\"challenge_id\":\""+CID+"\"}"),Uri.parse(HumanCheckRequest.ORIGIN),false,null);
		event("verified"); assertEquals(2,sent.size()); assertFalse(page.isFinishing());
	}
	@Test public void lateJsVerifiedAfterCloseCannotCompleteOrReturnOk() throws Exception{
		var page=prompt().get(); load(page); var listener=bridge; WebView web=bridgedView;
		ShadowDialog.getLatestDialog().findViewById(R.id.human_check_modal_close).performClick(); int count=sent.size();
		listener.onPostMessage(web,new androidx.webkit.WebMessageCompat("{\"event\":\"verified\",\"challenge_id\":\""+CID+"\"}"),Uri.parse(HumanCheckRequest.ORIGIN),true,null);
		assertEquals(count,sent.size()); assertFalse(page.isFinishing()); assertEquals(Activity.RESULT_CANCELED,shadow(page).getResultCode());
	}
	@Test public void unavailableSecureBridgeFailsClosedWithoutUnsafeWebViewFallback() throws Exception{
		var page=prompt().get(); supported=false; loadUnavailable(page); assertEquals(0,factoryCalls); assertNull(bridge);
		assertEquals(HumanCheckRequest.Endpoint.CHALLENGE_CANCEL,last().endpoint); assertFalse(page.isFinishing());
		assertTrue(((TextView)page.findViewById(R.id.human_check_status)).getText().toString().contains("WebView"));
	}
	private void loadUnavailable(HumanCheckActivity page) throws Exception{ page.findViewById(R.id.human_check_start).performClick(); challenge(last()); }
	@Test public void tlsErrorCancelsAndDestroysWidgetWithoutPass() throws Exception{
		var page=prompt().get(); load(page); WebView web=bridgedView;
		SslErrorHandler ssl=org.robolectric.shadow.api.Shadow.newInstanceOf(SslErrorHandler.class);
		org.robolectric.shadow.api.Shadow.<ShadowWebView>extract(web).getWebViewClient().onReceivedSslError(web,ssl,null);
		assertTrue(org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowSslErrorHandler>extract(ssl).wasCancelCalled());
		assertFalse(org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowSslErrorHandler>extract(ssl).wasProceedCalled()); assertNull(bridge); assertEquals(HumanCheckRequest.Endpoint.CHALLENGE_CANCEL,last().endpoint); assertFalse(page.isFinishing());
	}
	private static android.webkit.WebResourceRequest mainRequest(){
		return new android.webkit.WebResourceRequest(){
			@Override public Uri getUrl(){ return Uri.parse(HumanCheckRequest.pageUrl(CID)); }
			@Override public boolean isForMainFrame(){ return true; }
			@Override public boolean isRedirect(){ return false; }
			@Override public boolean hasGesture(){ return false; }
			@Override public String getMethod(){ return "GET"; }
			@Override public java.util.Map<String,String> getRequestHeaders(){ return java.util.Map.of(); }
		};
	}
	@Test public void oldWebViewErrorTlsAndRendererCallbacksCannotCancelNewReadyAttempt() throws Exception{
		var page=prompt().get(); load(page); WebView old=bridgedView;
		var oldClient=org.robolectric.shadow.api.Shadow.<ShadowWebView>extract(old).getWebViewClient();
		ShadowDialog.getLatestDialog().findViewById(R.id.human_check_modal_close).performClick(); cancelled(last());
		load(page); event("ready"); WebView current=bridgedView; Dialog currentDialog=ShadowDialog.getLatestDialog(); int requests=sent.size();
		oldClient.onReceivedError(old,mainRequest(),null);
		oldClient.onReceivedHttpError(old,mainRequest(),null);
		SslErrorHandler ssl=org.robolectric.shadow.api.Shadow.newInstanceOf(SslErrorHandler.class);
		oldClient.onReceivedSslError(old,ssl,null);
		assertTrue(oldClient.onRenderProcessGone(old,null));
		assertTrue(org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowSslErrorHandler>extract(ssl).wasCancelCalled());
		assertFalse(org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowSslErrorHandler>extract(ssl).wasProceedCalled());
		assertEquals(requests,sent.size()); assertSame(current,bridgedView); assertTrue(currentDialog.isShowing());
		assertEquals(View.GONE,currentDialog.findViewById(R.id.human_check_modal_progress).getVisibility());
		org.robolectric.shadow.api.Shadow.<ShadowWebView>extract(current).getWebViewClient().onReceivedError(current,mainRequest(),null);
		assertEquals(requests+1,sent.size()); assertEquals(HumanCheckRequest.Endpoint.CHALLENGE_CANCEL,last().endpoint); assertNull(bridge); assertFalse(currentDialog.isShowing());
	}
	@Test public void queuedOldWebViewFailureRechecksSourceAndAttemptOnMainDelivery() throws Exception{
		var page=prompt().get(); load(page); WebView old=bridgedView;
		var oldClient=org.robolectric.shadow.api.Shadow.<ShadowWebView>extract(old).getWebViewClient();
		Thread oldCallback=new Thread(()->oldClient.onReceivedError(old,mainRequest(),null)); oldCallback.start(); oldCallback.join();
		ShadowDialog.getLatestDialog().findViewById(R.id.human_check_modal_close).performClick(); cancelled(last());
		load(page); event("ready"); WebView current=bridgedView; int requests=sent.size();
		ShadowLooper.idleMainLooper(); assertEquals(requests,sent.size()); assertSame(current,bridgedView); assertTrue(ShadowDialog.getLatestDialog().isShowing());
		Thread currentCallback=new Thread(()->org.robolectric.shadow.api.Shadow.<ShadowWebView>extract(current).getWebViewClient().onReceivedError(current,mainRequest(),null));
		currentCallback.start(); currentCallback.join(); ShadowLooper.idleMainLooper();
		assertEquals(requests+1,sent.size()); assertEquals(HumanCheckRequest.Endpoint.CHALLENGE_CANCEL,last().endpoint); assertNull(bridge);
	}
	@Test public void initialSlowHintThenTimeoutRequiresManualRetryNoLoop() throws Exception{
		var page=prompt().get(); page.findViewById(R.id.human_check_start).performClick(); Dialog dialog=ShadowDialog.getLatestDialog();
		ShadowLooper.idleMainLooper(11,TimeUnit.SECONDS); assertEquals(page.getString(R.string.human_check_slow),((TextView)dialog.findViewById(R.id.human_check_modal_status)).getText().toString());
		ShadowLooper.idleMainLooper(20,TimeUnit.SECONDS); assertFalse(dialog.isShowing()); assertEquals(2,sent.size()); assertEquals(0,factoryCalls);
	}
	@Test public void rotationAndProcessLossNeverAutoResumeWidgetOrMail() throws Exception{
		var first=prompt(); load(first.get()); Bundle saved=new Bundle(); first.saveInstanceState(saved).pause().stop().destroy(); activities.remove(first);
		assertFalse(saved.toString().contains(EMAIL)); assertFalse(saved.toString().contains("code_verifier")); assertFalse(saved.toString().contains(CID));
		int count=sent.size(); var next=Robolectric.buildActivity(HumanCheckActivity.class,new Intent(MastodonApp.context,HumanCheckActivity.class)).create(saved).start().resume(); activities.add(next);
		assertEquals(count,sent.size()); assertEquals(1,factoryCalls); assertNull(bridge); assertEquals(Activity.RESULT_CANCELED,shadow(next.get()).getResultCode());
		field(HumanCheckCoordinator.class,"pending").set(flow,null); var dead=Robolectric.buildActivity(HumanCheckActivity.class,first.get().getIntent()).setup(); activities.add(dead);
		assertEquals(count,sent.size()); assertEquals(1,factoryCalls); assertFalse(dead.get().findViewById(R.id.human_check_start).isEnabled());
	}
	@Test public void bothRealEntriesUseNewSendOnlyAndSentIsOneNavigationOr60SecondTimer() throws Exception{
		for(boolean resend:new boolean[]{false,true}){
			reset(); AppKitFragment entry=entry(resend); HumanCheckRequest send=last(); assertEquals(HumanCheckRequest.Endpoint.SEND_CODE,send.endpoint);
			assertEquals("register",body(send).get("type").getAsString()); assertEquals(0,factoryCalls); sent(send); sent(send);
			if(resend){
				android.os.CountDownTimer timer=(android.os.CountDownTimer)field(LoginCodeFragment.class,"resendTimer").get(entry);
				var countdown=org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowCountDownTimer>extract(timer);
				assertEquals(60_000L,countdown.getMillisInFuture()); assertTrue(countdown.hasStarted()); countdown.invokeTick(59_000L);
				assertFalse(entry.getView().findViewById(R.id.tv_resend).isEnabled()); assertTrue(((TextView)entry.getView().findViewById(R.id.tv_resend)).getText().toString().contains("秒"));
			}
			else{ assertEquals(1,Navigation.navigations); assertEquals(LoginCodeFragment.class,Navigation.destination); assertEquals(EMAIL,Navigation.arguments.getString("email")); }
			assertNull(shadow(entry.getActivity()).getNextStartedActivity());
		}
	}
	@Test public void bothRealEntriesRequiredPromptCancelPreservesInputAndRestoresControls() throws Exception{
		for(boolean resend:new boolean[]{false,true}){
			reset(); AppKitFragment entry=entry(resend); var page=launch(entry).get(); assertEquals(0,factoryCalls); page.onBackPressed(); cancelled(last());
			entry.onActivityResult(resend?4872:4871,Activity.RESULT_CANCELED,null);
			assertTrue(entry.getView().findViewById(resend?R.id.tv_resend:R.id.btn_send_code).isEnabled()); assertEquals(0,Navigation.navigations);
			if(!resend) assertEquals(EMAIL,((EditText)entry.getView().findViewById(R.id.email_edit)).getText().toString());
			assertEquals(0,factoryCalls);
		}
	}
	@Test public void resultOkAloneCannotNavigateOrStartTimerAndBackendSentCanOnlyCompleteOnce() throws Exception{
		AppKitFragment entry=entry(false); var page=launch(entry).get(); Intent owner=page.getIntent();
		entry.onActivityResult(4871,Activity.RESULT_OK,owner); assertEquals(0,Navigation.navigations);
		load(page); event("ready"); event("verified"); assertEquals(HumanCheckRequest.Endpoint.COMPLETE,last().endpoint); assertFalse(page.isFinishing()); sent(last());
		assertTrue(page.isFinishing()); assertEquals(Activity.RESULT_OK,shadow(page).getResultCode()); Intent result=shadow(page).getResultIntent();
		assertEquals(Set.of("generation","owner"),result.getExtras().keySet()); entry.onActivityResult(4871,Activity.RESULT_OK,result); entry.onActivityResult(4871,Activity.RESULT_OK,result);
		assertEquals(1,Navigation.navigations);
	}
	@Test public void realWebViewSettingsDenyFilesMixedContentWindowsAndDownloads() throws Exception{
		var page=prompt().get(); load(page); var settings=bridgedView.getSettings();
		assertTrue(settings.getJavaScriptEnabled()); assertTrue(settings.getDomStorageEnabled());
		assertFalse(settings.getAllowFileAccess()); assertFalse(settings.getAllowContentAccess());
		assertFalse(settings.getAllowFileAccessFromFileURLs()); assertFalse(settings.getAllowUniversalAccessFromFileURLs());
		assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW,settings.getMixedContentMode());
		assertFalse(settings.getJavaScriptCanOpenWindowsAutomatically()); assertFalse(settings.supportMultipleWindows());
		assertFalse(bridgedView.isSaveEnabled()); assertTrue(bridgedView.getLayoutParams().height>=240);
	}
	@Test public void landscapeLargeFontRemainsScrollableWithReachableStart() throws Exception{
		RuntimeEnvironment.setQualifiers("zh-rCN-w852dp-h393dp-land-notnight-mdpi");
		android.content.res.Configuration config=new android.content.res.Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
		config.fontScale=1.6f; RuntimeEnvironment.getApplication().getResources().updateConfiguration(config,RuntimeEnvironment.getApplication().getResources().getDisplayMetrics());
		var page=prompt().get(); View decor=page.getWindow().getDecorView(); layout(decor,852,393);
		View start=page.findViewById(R.id.human_check_start); assertTrue(start.isEnabled()); assertTrue(start.getHeight()>=48); assertTrue(start.getWidth()>0);
		boolean scrollable=false; android.view.ViewParent parent=start.getParent();
		while(parent!=null){ if(parent instanceof android.widget.ScrollView) scrollable=true; parent=parent.getParent(); }
		assertTrue(scrollable); assertEquals(0,factoryCalls);
	}
	@Test public void fixedNavigationPolicyBlocksExternalFilesCleartextAndMainFrameCf(){
		String expected=HumanCheckRequest.pageUrl(CID);
		for(String url:new String[]{"file:///etc/passwd","content://private","http://api.abdl-space.top/path","https://evil.test/path","https://api.abdl-space.top.evil/path","https://user@api.abdl-space.top/path","https://api.abdl-space.top:443/path","javascript:alert(1)"}){
			assertFalse(url,HumanCheckActivity.allowedResource(url,false,expected)); assertFalse(url,HumanCheckActivity.allowedNavigation(url,true,expected));
		}
		assertTrue(HumanCheckActivity.allowedResource("https://challenges.cloudflare.com/turnstile/v0/api.js",false,expected));
		assertFalse(HumanCheckActivity.allowedNavigation("https://challenges.cloudflare.com/frame",true,expected));
		assertTrue(HumanCheckActivity.allowedResource("about:srcdoc",false,expected)); assertTrue(HumanCheckActivity.allowedNavigation(expected,true,expected));
	}
	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void nativeCanvasLightDarkPromptAndImmediateLoadingEvidence() throws Exception{
		File dir=new File(System.getProperty("human.check.renderDir","/home/ZYongX/projects/app-human-check-evidence-20261028/android")); assertTrue(dir.isDirectory() || dir.mkdirs());
		for(boolean dark:new boolean[]{false,true}){
			reset(); GlobalUserPreferences.theme=dark?GlobalUserPreferences.ThemePreference.DARK:GlobalUserPreferences.ThemePreference.LIGHT;
			RuntimeEnvironment.setQualifiers("zh-rCN-w393dp-h852dp-"+(dark?"night":"notnight")+"-mdpi");
			var page=prompt().get(); View decor=page.getWindow().getDecorView(); layout(decor,393,852);
			capture(decor,null,new File(dir,"human-check-prompt-"+(dark?"dark":"light")+".png")); assertEquals(0,factoryCalls);
			page.findViewById(R.id.human_check_start).performClick(); Dialog dialog=ShadowDialog.getLatestDialog();
			org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300));
			ShadowLooper.idleMainLooper(32,TimeUnit.MILLISECONDS); layoutModal(dialog);
			ShadowLooper.idleMainLooper(32,TimeUnit.MILLISECONDS); layoutModal(dialog);
			View modal=dialog.getWindow().getDecorView();
			assertVisibleWithin(modal,dialog.findViewById(R.id.human_check_modal_progress));
			android.widget.ProgressBar meter=dialog.findViewById(R.id.human_check_modal_progress);
			assertTrue(meter.getIndeterminateDrawable() instanceof androidx.swiperefreshlayout.widget.CircularProgressDrawable);
			assertVisibleWithin(modal,dialog.findViewById(R.id.human_check_modal_close));
			capture(decor,dialog,new File(dir,"human-check-loading-"+(dark?"dark":"light")+".png")); assertEquals(0,factoryCalls);
			android.animation.ValueAnimator animator=(android.animation.ValueAnimator)field(androidx.swiperefreshlayout.widget.CircularProgressDrawable.class,"mAnimator").get(meter.getIndeterminateDrawable());
			assertEquals(android.animation.ValueAnimator.INFINITE,org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowValueAnimator>extract(animator).getActualRepeatCount());
			HumanCheckRequest challengeRequest=last(); callback(challengeRequest).onError(new HumanCheckRequest.Failure(409,"HUMAN_CHECK_POLICY_CHANGED"));
			layout(decor,393,852); capture(decor,null,new File(dir,"human-check-failure-"+(dark?"dark":"light")+".png"));
			assertFalse(page.findViewById(R.id.human_check_start).isEnabled());
			reset(); page=prompt().get(); decor=page.getWindow().getDecorView(); load(page); event("ready"); event("verified");
			assertEquals(HumanCheckRequest.Endpoint.COMPLETE,last().endpoint); assertFalse(page.isFinishing());
			assertEquals(View.VISIBLE,page.findViewById(R.id.human_check_progress).getVisibility());
			layout(decor,393,852); capture(decor,null,new File(dir,"human-check-continuing-"+(dark?"dark":"light")+".png"));
			assertEquals(page.getString(R.string.human_check_continuing),((TextView)page.findViewById(R.id.human_check_status)).getText().toString());
		}
	}
	private static void layoutModal(Dialog dialog){
		View view=dialog.getWindow().getDecorView(); forceLayout(view);
		view.measure(View.MeasureSpec.makeMeasureSpec(353,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.AT_MOST));
		view.layout(0,0,353,view.getMeasuredHeight()); view.getViewTreeObserver().dispatchOnPreDraw();
	}
	private static void assertVisibleWithin(View root,View control){
		int[] outer=new int[2],inner=new int[2]; root.getLocationInWindow(outer); control.getLocationInWindow(inner);
		android.graphics.Rect bounds=new android.graphics.Rect(inner[0]-outer[0],inner[1]-outer[1],inner[0]-outer[0]+control.getWidth(),inner[1]-outer[1]+control.getHeight());
		assertTrue(bounds.toString(),control.isShown() && control.getWidth()>0 && control.getHeight()>0 && new android.graphics.Rect(0,0,root.getWidth(),root.getHeight()).contains(bounds));
	}
	private static void layout(View view,int width,int height){
		for(int pass=0;pass<2;pass++){ forceLayout(view); view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY)); view.layout(0,0,width,height); view.getViewTreeObserver().dispatchOnPreDraw(); }
	}
	private static void forceLayout(View view){ view.forceLayout(); if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) forceLayout(group.getChildAt(i)); }
	private static void capture(View decor,Dialog modal,File output) throws Exception{
		Bitmap image=Bitmap.createBitmap(393,852,Bitmap.Config.ARGB_8888);
		try{ Canvas canvas=new Canvas(image);
			for(int frame=0;frame<3;frame++){
				image.eraseColor(Color.TRANSPARENT); decor.draw(canvas);
				if(modal!=null){ canvas.drawColor(Color.argb(100,0,0,0)); View view=modal.getWindow().getDecorView(); canvas.save(); canvas.translate((393-view.getWidth())/2f,(852-view.getHeight())/2f); view.draw(canvas); canvas.restore(); }
				if(frame<2){ org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(97)); ShadowLooper.idleMainLooper(32,TimeUnit.MILLISECONDS); }
			}
			try(FileOutputStream file=new FileOutputStream(output)){ assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,file)); } assertTrue(output.length()>0);
		}finally{ image.recycle(); }
	}
}
