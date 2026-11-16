package org.joinmastodon.android;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.ContentAccessCoordinator;
import org.joinmastodon.android.fragments.settings.BabyVerificationFragment;
import org.joinmastodon.android.fragments.settings.SettingsMainFragment;
import org.joinmastodon.android.model.ContentAccess;
import org.joinmastodon.android.security.AppSecurity;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.Nav;
import me.grishka.appkit.utils.V;

/** Opaque Activity boundary, including restored platform/Compose content and non-Main detail hosts.
 * Account utilities remain native and operable. This is not a substitute for server API enforcement. */
public final class ContentAccessGate implements Application.ActivityLifecycleCallbacks{
	public static final String SHIELD_TAG="abdl_content_access_shield";
	private static ContentAccessGate instance;
	private final Handler handler=new Handler(Looper.getMainLooper());
	private final Map<Activity, Screen> screens=new WeakHashMap<>();
	private Network network;
	private boolean observedNetwork;
	private ContentAccessGate(){}
	public static void install(Application application){
		if(instance!=null) return;
		instance=new ContentAccessGate(); application.registerActivityLifecycleCallbacks(instance);
		ConnectivityManager connectivity=application.getSystemService(ConnectivityManager.class);
		if(connectivity!=null) try{
			connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback(){
				@Override public void onAvailable(Network next){ instance.handler.post(()->instance.networkChanged(next)); }
				@Override public void onLost(Network old){ instance.handler.post(()->{ if(old.equals(instance.network)) instance.networkChanged(null); }); }
			});
		}catch(RuntimeException ignored){ /* Foreground and explicit refresh remain fail closed. */ }
	}
	private void networkChanged(Network next){
		if(observedNetwork && java.util.Objects.equals(network,next)) return;
		network=next; observedNetwork=true;
		refreshSelected();
	}
	public static void accountChanged(){
		if(instance!=null) instance.handler.post(()->{ for(Screen screen:new ArrayList<>(instance.screens.values())) screen.bind(); instance.refreshSelected(); });
	}
	private void refreshSelected(){
		AccountSession session=AccountSessionManager.getInstance().getLastActiveAccount();
		if(ContentAccessCoordinator.applies(session)) session.getContentAccessCoordinator().refresh();
	}
	public static void openVerification(Activity activity, String accountId){
		AccountSession session=AccountSessionManager.getInstance().tryGetAccount(accountId);
		if(session==null || !accountId.equals(AccountSessionManager.getInstance().getLastActiveAccountID())) return;
		if(activity instanceof MainActivity main && main.getTopmostFragment() instanceof BabyVerificationFragment) return;
		Bundle args=new Bundle(); args.putString("account",accountId);
		if(activity instanceof MainActivity) Nav.go(activity, BabyVerificationFragment.class,args);
		else activity.startActivity(new Intent(activity, MainActivity.class).setAction(Intent.ACTION_VIEW).setData(android.net.Uri.parse("https://abdl-space.top/settings/baby-verification")));
	}
	public static boolean isAllowedFragment(Fragment fragment){
		if(fragment==null) return false;
		String name=fragment.getClass().getName();
		return name.startsWith("org.joinmastodon.android.fragments.settings.")
				|| name.startsWith("org.joinmastodon.android.fragments.auth.")
				|| name.startsWith("org.joinmastodon.android.fragments.onboarding.")
				|| name.startsWith("org.joinmastodon.android.sponsors.") || name.startsWith("org.joinmastodon.android.fragments.sponsors.")
				|| name.endsWith(".HelpFragment") || name.endsWith(".ReportProblemFragment");
	}
	private static boolean utilityActivity(Activity activity){
		String name=activity.getClass().getName();
		return name.startsWith("org.joinmastodon.android.security.") || name.startsWith("org.joinmastodon.android.fragments.settings.")
				|| name.equals("org.joinmastodon.android.ui.MediaCameraActivity") || name.equals("org.joinmastodon.android.VerificationLinkActivity")
				|| name.equals("org.joinmastodon.android.OAuthActivity") || name.equals("org.joinmastodon.android.HumanCheckActivity")
				|| name.equals("org.joinmastodon.android.QQAuthActivity") || name.equals("org.joinmastodon.android.NBWLoginActivity")
				|| name.equals("org.joinmastodon.android.ui.SplashActivity") || name.equals("org.joinmastodon.android.ui.CrisisHelpActivity")
				|| name.equals("org.joinmastodon.android.ui.NotificationGuideActivity") || name.equals("org.joinmastodon.android.fragments.settings.ComposeAboutActivity");
	}
	@Override public void onActivityCreated(Activity activity, Bundle state){
		if(utilityActivity(activity)) return;
		Screen screen=new Screen(activity); screens.put(activity,screen); screen.bind();
	}
	@Override public void onActivityResumed(Activity activity){
		Screen screen=screens.get(activity); if(screen==null) return;
		screen.resumed=true; screen.bind(); refreshSelected(); screen.apply();
	}
	@Override public void onActivityPaused(Activity activity){ Screen screen=screens.get(activity); if(screen!=null) screen.resumed=false; }
	@Override public void onActivityDestroyed(Activity activity){
		Screen screen=screens.remove(activity); if(screen==null) return;
		if(screen.subscription!=null) screen.subscription.cancel();
	}
	@Override public void onActivityStarted(Activity activity){}
	@Override public void onActivityStopped(Activity activity){}
	@Override public void onActivitySaveInstanceState(Activity activity, Bundle state){}

	private static final class ViewOwner{
		final AccountSession session;
		final org.joinmastodon.android.model.Token token;
		final String value;
		ViewOwner(AccountSession session){ this.session=session; token=session==null ? null : session.token; value=token==null ? null : token.accessToken; }
		boolean current(AccountSession selected){ return session==selected && (session==null || session.token==token && java.util.Objects.equals(value,token==null ? null : token.accessToken)); }
	}

	private final class Screen implements ViewTreeObserver.OnPreDrawListener{
		final Activity activity;
		final Map<View,Integer> hidden=new IdentityHashMap<>(), accessibility=new IdentityHashMap<>();
		final Map<View,ViewOwner> viewOwners=new WeakHashMap<>();
		final ViewOwner activityOwner=new ViewOwner(AccountSessionManager.getInstance().getLastActiveAccount());
		AccountSession session;
		ContentAccessCoordinator.Cancelable subscription;
		FrameLayout shield;
		TextView message;
		ProgressBar progress;
		Button verify, refresh;
		boolean resumed, applying;
		Screen(Activity activity){ this.activity=activity; }
		void bind(){
			AccountSession selected=AccountSessionManager.getInstance().getLastActiveAccount();
			if(selected!=session){
				for(Fragment fragment:activity.getFragmentManager().getFragments()){
					View view=fragment==null ? null : fragment.getView();
					if(view!=null && !viewOwners.containsKey(view)) viewOwners.put(view,new ViewOwner(session==null ? selected : session));
				}
				if(subscription!=null) subscription.cancel(); subscription=null; session=selected;
				if(ContentAccessCoordinator.applies(session)) subscription=session.getContentAccessCoordinator().observe(ignored->apply());
			}
			apply();
		}
		@Override public boolean onPreDraw(){
			if(session!=AccountSessionManager.getInstance().getLastActiveAccount()) bind(); else apply();
			return true;
		}
		void apply(){
			if(applying || activity.isDestroyed() || activity.isFinishing()) return;
			applying=true;
			try{
				// Silent detection only: never cover or hide content and never show a blocking overlay.
				// Protected reads stay fenced by ContentAccessCoordinator; a required decision only raises
				// the existing native prompt so the user can start verification.
				if(!ContentAccessCoordinator.applies(session)) return;
				ContentAccessCoordinator.Snapshot snapshot=session.getContentAccessCoordinator().getSnapshot();
				if(resumed && snapshot.state==ContentAccess.State.BABY_VERIFICATION_REQUIRED
						&& activity instanceof MainActivity main && !AppSecurity.isLocked()){
					main.showContentAccessPrompt();
				}
			}finally{ applying=false; }
		}
		Fragment topFragment(){
			java.util.List<Fragment> fragments=activity.getFragmentManager().getFragments();
			for(int i=fragments.size()-1;i>=0;i--){ Fragment fragment=fragments.get(i); if(fragment!=null && fragment.isAdded() && !fragment.isHidden()) return fragment; }
			return null;
		}
		void ensureShield(){
			ViewGroup decor=(ViewGroup)activity.getWindow().getDecorView();
			if(shield==null){
				shield=new FrameLayout(activity); shield.setTag(SHIELD_TAG); shield.setClickable(true); shield.setFocusable(true); shield.setFilterTouchesWhenObscured(true);
				shield.setBackgroundColor(UiUtils.getThemeColor(activity,R.attr.colorM3Background));
				ScrollView scroll=new ScrollView(activity); scroll.setFillViewport(true); shield.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
				LinearLayout column=new LinearLayout(activity); column.setOrientation(LinearLayout.VERTICAL); column.setGravity(Gravity.CENTER); column.setPadding(V.dp(24),V.dp(48),V.dp(24),V.dp(48)); scroll.addView(column,new ScrollView.LayoutParams(-1,-1));
				progress=new ProgressBar(activity); column.addView(progress);
				message=new TextView(activity); message.setTextColor(UiUtils.getThemeColor(activity,R.attr.colorM3OnSurface)); message.setTextSize(16); message.setPadding(0,V.dp(16),0,V.dp(16)); column.addView(message,new LinearLayout.LayoutParams(-1,-2));
				verify=new Button(activity); verify.setText(R.string.verification_title); verify.setOnClickListener(v->{ if(session!=null) openVerification(activity,session.getID()); }); column.addView(verify);
				refresh=new Button(activity); refresh.setText(R.string.verification_refresh); refresh.setOnClickListener(v->{ if(session!=null) session.getContentAccessCoordinator().refresh(); }); column.addView(refresh);
				Button settings=new Button(activity); settings.setText(R.string.settings); settings.setOnClickListener(v->{
					if(activity instanceof FragmentStackActivity){ Bundle args=new Bundle(); args.putString("account",session.getID()); Nav.go(activity,SettingsMainFragment.class,args); }
					else activity.startActivity(new Intent(activity,MainActivity.class).putExtra("content_access_settings",true));
				}); column.addView(settings);
			}
			for(int i=0;i<decor.getChildCount();i++){ View child=decor.getChildAt(i); if(child!=shield && !"abdl_security_shield".equals(child.getTag()) && !accessibility.containsKey(child)){ accessibility.put(child,child.getImportantForAccessibility()); child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS); } }
			if(shield.getParent()==null) decor.addView(shield,new ViewGroup.LayoutParams(-1,-1));
		}
		void removeShield(){
			if(shield!=null && shield.getParent() instanceof ViewGroup parent) parent.removeView(shield);
			for(Map.Entry<View,Integer> entry:accessibility.entrySet()) entry.getKey().setImportantForAccessibility(entry.getValue()); accessibility.clear();
		}
	}
}
