package org.joinmastodon.android.fragments;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Fragment;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.friendcards.MyFriendCardsFragment;
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

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import me.grishka.appkit.Nav;
import me.grishka.appkit.utils.V;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,28,32},application=CompatibilityTestApplication.class,
		shadows={FriendRequestCreatePromotionTest.Navigation.class,FriendRequestCreatePromotionTest.NoNetwork.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FriendRequestCreatePromotionTest{
	private ActivityController<Activity> lifecycle;
	private Activity activity;
	private FrameLayout root;
	private String account,previousActive;
	private Map<String,AccountSession> sessions;
	private FriendRequestCreateFragment fragment;
	@Implements(Nav.class) public static class Navigation{
		static Class<?> target; static Bundle args;
		@Implementation public static void go(Activity activity,Class<? extends Fragment> type,Bundle bundle){ target=type; args=new Bundle(bundle); }
	}
	@Implements(MastodonAPIRequest.class) public static class NoNetwork{
		@Implementation public MastodonAPIRequest<?> exec(String account){ throw new AssertionError("Promotion must not post or create cards"); }
	}
	private static Field field(Class<?> type,String name)throws Exception{ Field f=type.getDeclaredField(name); f.setAccessible(true); return f; }
	@Before @SuppressWarnings("unchecked") public void setup()throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		lifecycle=Robolectric.buildActivity(Activity.class); activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.setup();
		activity.getSharedPreferences("friend_card_promotion",0).edit().clear().commit();
		activity.getSharedPreferences("friend_request",0).edit().putBoolean("privacy_warning_shown",true).commit();
		var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true); AccountSession session=constructor.newInstance();
		session.domain="offline.example.test"; session.self=new org.joinmastodon.android.model.Account(); session.self.id="42"; account=session.getID();
		var manager=AccountSessionManager.getInstance(); sessions=(Map<String,AccountSession>)field(AccountSessionManager.class,"sessions").get(manager);
		previousActive=manager.getLastActiveAccountID(); sessions.put(account,session); field(AccountSessionManager.class,"lastActiveAccountID").set(manager,account);
		root=new FrameLayout(activity); root.setId(0x34567); activity.setContentView(root);
		Navigation.target=null; Navigation.args=null;
	}
	@After public void cleanup()throws Exception{
		sessions.remove(account); field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),previousActive); lifecycle.close();
	}
	private FriendRequestCreateFragment attach(Bundle args,Fragment.SavedState state){
		FriendRequestCreateFragment value=new FriendRequestCreateFragment(); value.setArguments(args);
		if(state!=null) value.setInitialSavedState(state);
		activity.getFragmentManager().beginTransaction().replace(root.getId(),value).commit(); activity.getFragmentManager().executePendingTransactions();
		return fragment=value;
	}
	private Bundle args(){ Bundle args=new Bundle(); args.putString("account",account); return args; }
	private int count(){ return activity.getSharedPreferences("friend_card_promotion",0).getStringSet("shown_pages",Set.of()).size(); }
	private boolean eligible(){ return BuildConfig.VERSION_CODE==33 || BuildConfig.VERSION_CODE==34; }
	private void draw(){
		root.measure(View.MeasureSpec.makeMeasureSpec(V.dp(393),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(V.dp(852),View.MeasureSpec.EXACTLY));
		root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight()); root.getViewTreeObserver().dispatchOnPreDraw();
	}

	@Test public void actualVisibilityConsumesOnceAndRecreationRepeatedOnShownAndRedrawDoNotConsumeAgain()throws Exception{
		attach(args(),null); View promotion=root.findViewById(R.id.friend_card_promotion);
		assertEquals(eligible() ? View.VISIBLE : View.GONE,promotion.getVisibility());
		fragment.onShown(); fragment.onShown(); assertEquals(0,count());
		lifecycle.visible(); draw(); assertEquals(eligible() ? 1 : 0,count());
		for(int i=0;i<5;i++){ fragment.onShown(); draw(); } assertEquals(eligible() ? 1 : 0,count());
		Fragment.SavedState state=activity.getFragmentManager().saveFragmentInstanceState(fragment);
		Bundle restoredArgs=new Bundle(fragment.getArguments());
		attach(restoredArgs,state); fragment.onShown(); draw(); assertEquals(eligible() ? 1 : 0,count());
		if(eligible()) capture(root.findViewById(R.id.friend_card_promotion));
	}

	@Test public void buttonsOnlyNavigateToExistingHubOrPermanentlyDismiss()throws Exception{
		attach(args(),null); lifecycle.visible(); fragment.onShown(); draw();
		View promotion=root.findViewById(R.id.friend_card_promotion);
		if(!eligible()){ assertEquals(View.GONE,promotion.getVisibility()); return; }
		assertTrue(root.findViewById(R.id.friend_card_promotion_action).performClick());
		assertEquals(MyFriendCardsFragment.class,Navigation.target); assertEquals(account,Navigation.args.getString("account")); assertEquals(1,Navigation.args.size());
		Navigation.target=null;
		root.findViewById(R.id.friend_card_promotion_close).performClick(); assertEquals(View.GONE,promotion.getVisibility());
		attach(args(),null); fragment.onShown(); draw();
		assertEquals(View.GONE,root.findViewById(R.id.friend_card_promotion).getVisibility()); assertEquals(1,count()); assertNull(Navigation.target);
	}

	@Test public void hiddenUnlaidOutAndEditPagesNeverConsume()throws Exception{
		attach(args(),null); fragment.onShown(); fragment.onHidden(); draw(); assertEquals(0,count());
		Bundle edit=args(); edit.putString("editRequestId","offline-edit"); attach(edit,null); lifecycle.visible(); fragment.onShown(); draw();
		assertEquals(View.GONE,root.findViewById(R.id.friend_card_promotion).getVisibility()); assertEquals(0,count());
	}

	private void capture(View promotion)throws Exception{
		assertTrue(promotion.getWidth()>0); assertTrue(promotion.getHeight()>0);
		int surface=org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(activity,R.attr.colorM3Surface);
		root.setBackgroundColor(surface);
		assertTrue(root.getWidth()>0); assertTrue(root.getHeight()>0);
		int originalWidth=root.getWidth(),originalHeight=root.getHeight();
		File directory=new File("/tmp/abdl-friend-card-ui-renders/promotion/api"+RuntimeEnvironment.getApiLevel()); assertTrue(directory.isDirectory()||directory.mkdirs());
		try{
			for(int width:new int[]{originalWidth,V.dp(320)}){
				root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(originalHeight,View.MeasureSpec.EXACTLY));
				root.layout(0,0,width,originalHeight);
				Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
				try{
					root.draw(new Canvas(bitmap));
					String name=width==originalWidth ? "request-promotion.png" : "request-promotion-320dp.png";
					try(FileOutputStream output=new FileOutputStream(new File(directory,name))){ assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output)); }
				}finally{ bitmap.recycle(); }
			}
		}finally{
			root.measure(View.MeasureSpec.makeMeasureSpec(originalWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(originalHeight,View.MeasureSpec.EXACTLY));
			root.layout(0,0,originalWidth,originalHeight);
		}
	}
}
