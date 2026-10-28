package org.joinmastodon.android;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.joinmastodon.android.api.requests.auth.HumanCheckRequest;
import org.joinmastodon.android.auth.HumanCheckCoordinator;
import org.joinmastodon.android.auth.HumanCheckCoordinator.Handle;
import org.joinmastodon.android.auth.HumanCheckCoordinator.Phase;
import org.joinmastodon.android.auth.HumanCheckCoordinator.Snapshot;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Set;

/** A passive native prompt first; the WebView exists only inside an explicitly started modal. */
public class HumanCheckActivity extends Activity{
	public static final String EXTRA_GENERATION="generation", EXTRA_OWNER="owner";
	private static final String BRIDGE="ABDLHumanCheck";
	private final HumanCheckCoordinator coordinator=HumanCheckCoordinator.getInstance();
	private final Handler handler=new Handler(Looper.getMainLooper());
	private Handle handle;
	private long entryOwner, modalAttempt;
	private boolean terminal, bridgeInstalled;
	private Dialog modal;
	private WebView webView;
	private FrameLayout webContainer;
	private TextView status, modalStatus;
	private Button start;
	private ProgressBar progress, modalProgress;
	private final Runnable lifetime=this::render;
	private final Runnable slow=()->{ if(modalStatus!=null && initializing()) modalStatus.setText(R.string.human_check_slow); };
	private final Runnable timeout=()->{ if(initializing()) componentFailure(R.string.human_check_timeout); };

	public static Intent intent(Context context, Handle handle){
		return new Intent(context, HumanCheckActivity.class).putExtra(EXTRA_GENERATION, handle.generation).putExtra(EXTRA_OWNER, handle.owner);
	}
	@Override protected void onCreate(@Nullable Bundle saved){
		UiUtils.setUserPreferredTheme(this);
		super.onCreate(saved);
		setResult(RESULT_CANCELED);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
		getActionBarSafeHide();
		setContentView(R.layout.activity_human_check);
		View root=findViewById(R.id.human_check_root);
		WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
		ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets)->{
			androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
			view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
		});
		ViewCompat.requestApplyInsets(root);
		boolean light=(getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)!=android.content.res.Configuration.UI_MODE_NIGHT_YES;
		WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(light);
		WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightNavigationBars(light);
		getWindow().setStatusBarColor(UiUtils.getThemeColor(this, R.attr.colorM3Surface));
		getWindow().setNavigationBarColor(UiUtils.getThemeColor(this, R.attr.colorM3Surface));
		status=findViewById(R.id.human_check_status); start=findViewById(R.id.human_check_start); progress=findViewById(R.id.human_check_progress);
		configureProgress(progress);
		GradientDrawable pill=new GradientDrawable(); pill.setColor(UiUtils.getThemeColor(this, R.attr.colorM3Primary)); pill.setCornerRadius(dp(100)); start.setBackground(pill);
		findViewById(R.id.human_check_back).setOnClickListener(view->cancelParent());
		start.setOnClickListener(view->{
			Snapshot snapshot=coordinator.snapshot(handle);
			if(snapshot!=null && snapshot.canQuery) coordinator.query(handle);
			else coordinator.start(handle);
			render();
		});
		Intent intent=getIntent();
		if(saved!=null){
			coordinator.invalidateGeneration(saved.getLong(EXTRA_GENERATION, 0));
			fail(R.string.human_check_restart); return; // No Activity recreation ever resumes a widget/send.
		}
		if(intent.getData()!=null || intent.getExtras()==null || !intent.getExtras().keySet().equals(Set.of(EXTRA_GENERATION, EXTRA_OWNER))){ fail(R.string.human_check_expired); return; }
		entryOwner=intent.getLongExtra(EXTRA_OWNER, 0);
		handle=coordinator.attach(intent.getLongExtra(EXTRA_GENERATION, 0), entryOwner, this::render);
		if(handle==null){ fail(R.string.human_check_expired); return; }
		render();
	}
	private void configureProgress(ProgressBar view){
		androidx.swiperefreshlayout.widget.CircularProgressDrawable drawable=new androidx.swiperefreshlayout.widget.CircularProgressDrawable(this);
		drawable.setStyle(androidx.swiperefreshlayout.widget.CircularProgressDrawable.DEFAULT);
		drawable.setColorSchemeColors(UiUtils.getThemeColor(this, R.attr.colorM3Primary));
		drawable.setStartEndTrim(0f, 0.75f);
		view.setIndeterminateDrawable(drawable);
	}
	private void getActionBarSafeHide(){ if(getActionBar()!=null) getActionBar().hide(); }
	private void render(){
		if(terminal || isFinishing() || isDestroyed()) return;
		handler.removeCallbacks(lifetime);
		Snapshot snapshot=coordinator.snapshot(handle);
		if(snapshot==null){ fail(R.string.human_check_expired); return; }
		boolean inModal=snapshot.phase==Phase.CREATING || snapshot.phase==Phase.LOADING || snapshot.phase==Phase.READY || snapshot.phase==Phase.VERIFYING;
		if(inModal){
			ensureModal(snapshot.attempt);
			modalStatus.setText(snapshot.message);
			modalProgress.setVisibility(snapshot.phase==Phase.READY ? View.GONE : View.VISIBLE);
			if(snapshot.phase==Phase.LOADING && webView==null && snapshot.pageUrl!=null){
				loadComponent(snapshot);
				Snapshot afterLoad=coordinator.snapshot(handle);
				if(afterLoad==null || afterLoad.phase!=snapshot.phase) return; // Synchronous WebView failure already rerendered the prompt.
			}
			if(snapshot.phase==Phase.READY || snapshot.phase==Phase.VERIFYING){ handler.removeCallbacks(slow); handler.removeCallbacks(timeout); }
		}else destroyModal();
		status.setText(snapshot.message);
		status.setVisibility(snapshot.message==R.string.human_check_description ? View.GONE : View.VISIBLE);
		progress.setVisibility(snapshot.busy && !inModal ? View.VISIBLE : View.GONE);
		start.setText(snapshot.canQuery ? R.string.human_check_query : R.string.human_check_start);
		start.setEnabled(snapshot.canStart || snapshot.canQuery);
		if(snapshot.phase==Phase.SENT && coordinator.issueSentResult(handle)){
			terminal=true;
			setResult(RESULT_OK, new Intent().putExtra(EXTRA_GENERATION, handle.generation).putExtra(EXTRA_OWNER, entryOwner));
			finish(); return;
		}
		if(snapshot.remainingMillis>0) handler.postDelayed(lifetime, snapshot.remainingMillis);
	}
	private void ensureModal(long attempt){
		if(modal!=null && modalAttempt==attempt) return;
		destroyModal(); modalAttempt=attempt;
		modal=new Dialog(this); modal.requestWindowFeature(Window.FEATURE_NO_TITLE);
		modal.setCanceledOnTouchOutside(false);
		modal.setCancelable(true);
		modal.setOnCancelListener(dialog->coordinator.cancelAttempt(handle, R.string.human_check_failed));
		LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(24), dp(20), dp(24), dp(16));
		GradientDrawable background=new GradientDrawable(); background.setColor(UiUtils.getThemeColor(this, R.attr.colorM3Surface)); background.setCornerRadius(dp(28)); body.setBackground(background);
		modalProgress=new ProgressBar(this); modalProgress.setId(R.id.human_check_modal_progress);
		configureProgress(modalProgress);
		LinearLayout.LayoutParams progressParams=new LinearLayout.LayoutParams(dp(32), dp(32)); progressParams.gravity=Gravity.CENTER_HORIZONTAL; body.addView(modalProgress, progressParams);
		modalStatus=new TextView(this); modalStatus.setId(R.id.human_check_modal_status); modalStatus.setGravity(Gravity.CENTER); modalStatus.setTextSize(16);
		modalStatus.setTextColor(UiUtils.getThemeColor(this, R.attr.colorM3OnSurface)); modalStatus.setText(R.string.human_check_loading); modalStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
		LinearLayout.LayoutParams textParams=new LinearLayout.LayoutParams(-1, -2); textParams.topMargin=dp(16); body.addView(modalStatus, textParams);
		webContainer=new FrameLayout(this); webContainer.setId(R.id.human_check_web_container); body.addView(webContainer, new LinearLayout.LayoutParams(-1, -2));
		Button close=new Button(this); close.setId(R.id.human_check_modal_close); close.setText(R.string.human_check_close); close.setTextColor(UiUtils.getThemeColor(this, R.attr.colorM3Primary)); close.setBackgroundColor(Color.TRANSPARENT);
		close.setOnClickListener(view->coordinator.cancelAttempt(handle, R.string.human_check_failed));
		LinearLayout.LayoutParams closeParams=new LinearLayout.LayoutParams(-1, -2); closeParams.topMargin=dp(12); body.addView(close, closeParams);
		ScrollView scroll=new ScrollView(this); scroll.setFillViewport(false); scroll.addView(body);
		modal.setContentView(scroll); modal.show();
		Window window=modal.getWindow();
		if(window!=null){
			window.setBackgroundDrawableResource(android.R.color.transparent); window.setGravity(Gravity.CENTER); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
			int available=getResources().getDisplayMetrics().widthPixels-dp(40);
			window.setLayout(Math.min(dp(400), available), ViewGroup.LayoutParams.WRAP_CONTENT);
		}
		handler.postDelayed(slow, 10_000); handler.postDelayed(timeout, 30_000);
	}
	/** Overridable solely by a controlled Robolectric shadow; production always uses a real WebView. */
	protected WebView createChallengeWebView(){ return new WebView(this); }
	private void loadComponent(Snapshot snapshot){
		try{
			if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)){ componentFailure(R.string.human_check_environment); return; }
			WebView view=createChallengeWebView(); webView=view; view.setId(R.id.human_check_webview);
			view.setSaveEnabled(false); view.setSaveFromParentEnabled(false); view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
			view.setBackgroundColor(UiUtils.getThemeColor(this, R.attr.colorM3Surface));
			WebSettings settings=view.getSettings();
			settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
			settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
			settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
			settings.setGeolocationEnabled(false); settings.setMediaPlaybackRequiresUserGesture(true); settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
			// Leave the stable default UA unchanged. Only this explicitly started WebView allows CF's necessary third-party cookies.
			CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
			long attempt=snapshot.attempt; String expected=snapshot.pageUrl;
			view.setWebViewClient(new WebViewClient(){
				@Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request){
					boolean allowed=allowedNavigation(request.getUrl().toString(), request.isForMainFrame(), expected);
					if(!allowed && request.isForMainFrame()) componentFailure(web, attempt, R.string.human_check_failed); return !allowed;
				}
				@Override public boolean shouldOverrideUrlLoading(WebView web, String url){ return !expected.equals(url); }
				@Override public WebResourceResponse shouldInterceptRequest(WebView web, WebResourceRequest request){
					if(allowedResource(request.getUrl().toString(), request.isForMainFrame(), expected)) return null;
					return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", Map.of("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0]));
				}
				@Override public void onReceivedSslError(WebView web, SslErrorHandler ssl, SslError error){ ssl.cancel(); componentFailure(web, attempt, R.string.human_check_tls); }
				@Override public void onReceivedError(WebView web, WebResourceRequest request, WebResourceError error){ if(request.isForMainFrame()) componentFailure(web, attempt, R.string.human_check_network); }
				@Override public void onReceivedHttpError(WebView web, WebResourceRequest request, WebResourceResponse response){ if(request.isForMainFrame()) componentFailure(web, attempt, R.string.human_check_unavailable); }
				@Override public boolean onRenderProcessGone(WebView web, RenderProcessGoneDetail detail){ componentFailure(web, attempt, R.string.human_check_environment); return true; }
				// onPageFinished is deliberately not readiness, verification, or business success.
			});
			view.setWebChromeClient(new WebChromeClient(){
				@Override public void onPermissionRequest(PermissionRequest request){ request.deny(); }
				@Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback){ callback.invoke(origin, false, false); }
				@Override public boolean onCreateWindow(WebView web, boolean dialog, boolean gesture, android.os.Message message){ return false; }
				@Override public boolean onShowFileChooser(WebView web, android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params){ callback.onReceiveValue(null); return true; }
			});
			view.setDownloadListener((url, agent, disposition, type, size)->componentFailure(view, attempt, R.string.human_check_failed));
			WebViewCompat.addWebMessageListener(view, BRIDGE, Set.of(HumanCheckRequest.ORIGIN), (web, message, sourceOrigin, mainFrame, replyProxy)->{
				if(web!=webView || message.getType()!=WebMessageCompat.TYPE_STRING || modalAttempt!=attempt || terminal) return;
				coordinator.webMessage(handle, attempt, sourceOrigin.toString(), mainFrame, message.getData());
			});
			bridgeInstalled=true;
			FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-1, dp(240)); params.topMargin=dp(16); webContainer.addView(view, params);
			view.loadUrl(expected, Map.of("Cache-Control", "no-store"));
		}catch(Exception unsupported){ componentFailure(R.string.human_check_environment); }
	}
	public static boolean allowedNavigation(String value, boolean mainFrame, String expected){
		return mainFrame ? expected!=null && expected.equals(value) : allowedResource(value, false, expected);
	}
	public static boolean allowedResource(String value, boolean mainFrame, String expected){
		if(mainFrame) return expected!=null && expected.equals(value);
		if("about:blank".equals(value) || "about:srcdoc".equals(value)) return true;
		try{
			Uri uri=Uri.parse(value);
			return uri.isHierarchical() && "https".equals(uri.getScheme()) && uri.getUserInfo()==null && uri.getPort()==-1
					&& Set.of(HumanCheckRequest.DOMAIN, "challenges.cloudflare.com").contains(uri.getEncodedAuthority());
		}catch(Exception invalid){ return false; }
	}
	private boolean initializing(){ Snapshot s=coordinator.snapshot(handle); return modal!=null && s!=null && (s.phase==Phase.CREATING || s.phase==Phase.LOADING); }
	private void componentFailure(WebView source, long attempt, int message){
		if(Looper.myLooper()!=Looper.getMainLooper()){
			handler.post(()->componentFailure(source, attempt, message)); return; // Recheck the same source/attempt at actual delivery.
		}
		if(terminal || source!=webView || attempt!=modalAttempt) return;
		Snapshot current=coordinator.snapshot(handle);
		if(current==null || current.attempt!=attempt) return;
		coordinator.componentFailure(handle, message);
	}
	private void componentFailure(int message){ if(Looper.myLooper()!=Looper.getMainLooper()){ handler.post(()->componentFailure(message)); return; } coordinator.componentFailure(handle, message); }
	private void destroyModal(){
		handler.removeCallbacks(slow); handler.removeCallbacks(timeout);
		if(webView!=null){
			WebView old=webView; webView=null;
			if(bridgeInstalled){ try{ WebViewCompat.removeWebMessageListener(old, BRIDGE); }catch(Exception ignored){} }
			bridgeInstalled=false; old.stopLoading(); old.setWebChromeClient(null); old.setWebViewClient(new WebViewClient());
			if(old.getParent() instanceof ViewGroup group) group.removeView(old);
			old.destroy();
		}
		if(modal!=null){ Dialog old=modal; modal=null; old.setOnCancelListener(null); old.dismiss(); }
		modalStatus=null; modalProgress=null; webContainer=null;
	}
	private void fail(int message){ terminal=true; destroyModal(); status.setVisibility(View.VISIBLE); status.setText(message); progress.setVisibility(View.GONE); start.setEnabled(false); }
	private void cancelParent(){
		if(handle!=null) coordinator.cancel(handle);
		terminal=true; handler.removeCallbacksAndMessages(null); destroyModal(); setResult(RESULT_CANCELED); finish();
	}
	@Override public void onBackPressed(){ if(modal!=null) coordinator.cancelAttempt(handle, R.string.human_check_failed); else cancelParent(); }
	@Override protected void onResume(){ super.onResume(); if(handle!=null && !terminal) render(); }
	@Override protected void onSaveInstanceState(Bundle out){
		if(handle!=null){ out.putLong(EXTRA_GENERATION, handle.generation); out.putLong(EXTRA_OWNER, entryOwner); }
		// No WebView, challenge, proof, email or tokens are serialized by this Activity.
		super.onSaveInstanceState(out);
	}
	@Override protected void onDestroy(){
		handler.removeCallbacksAndMessages(null); destroyModal();
		if(!terminal || isChangingConfigurations()) coordinator.detachUi(handle);
		super.onDestroy();
	}
	private int dp(int value){ return (int)(value*getResources().getDisplayMetrics().density+0.5f); }
}
