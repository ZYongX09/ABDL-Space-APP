package org.joinmastodon.android.fragments;

import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.view.View;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.fragments.albums.AlbumUploadFragment;
import org.joinmastodon.android.ui.sheets.ComposeActionSheet;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import me.grishka.appkit.Nav;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

/** Exercises production FAB/sheet/liquid callbacks; only navigation is replaced with a local recorder. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, application=CompatibilityTestApplication.class, shadows=HomeComposeActionRoutingTest.LocalNavigation.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class HomeComposeActionRoutingTest{
	@Implements(Nav.class)
	public static class LocalNavigation{
		static final List<Class<? extends Fragment>> destinations=new ArrayList<>();
		static final List<String> accounts=new ArrayList<>();
		static ComposeActionSheet expectedDismissedSheet;
		@Implementation public static void go(Activity activity, Class<? extends Fragment> destination, Bundle args){
			assertNotNull(activity);
			if(expectedDismissedSheet!=null) assertFalse(expectedDismissedSheet.isShowing());
			destinations.add(destination);
			accounts.add(args.getString("account"));
		}
	}

	private ActivityController<Activity> controller;
	private Activity activity;
	private HomeTabFragment home;

	@Before public void setUp() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication();
		V.setApplicationContext(MastodonApp.context);
		controller=Robolectric.buildActivity(Activity.class);
		activity=controller.get();
		activity.setTheme(R.style.Theme_Mastodon_Light);
		controller.setup();
		// Borrow a real framework host without running HomeTab's network/location timeline setup.
		Fragment hostProbe=new Fragment();
		activity.getFragmentManager().beginTransaction().add(hostProbe, "host-probe").commit();
		activity.getFragmentManager().executePendingTransactions();
		home=new HomeTabFragment();
		Field host=Fragment.class.getDeclaredField("mHost");
		host.setAccessible(true);
		host.set(home, host.get(hostProbe));
		assertSame(activity, home.getActivity());
		LocalNavigation.destinations.clear();
		LocalNavigation.accounts.clear();
		LocalNavigation.expectedDismissedSheet=null;
	}

	@After public void tearDown(){
		if(ShadowDialog.getLatestDialog() instanceof ComposeActionSheet sheet && sheet.isShowing())
			sheet.dismissWithoutAnimation();
		LocalNavigation.expectedDismissedSheet=null;
		controller.close();
		MastodonApp.context=null;
	}

	@Test public void productionHomeFabSheetAndLiquidActionsRouteIdenticallyForEachCurrentAccount() throws Exception{
		int[] ids={R.id.compose_post, R.id.compose_friend_request, R.id.compose_album};
		Class<?>[] targets={ComposeFragment.class, FriendRequestCreateFragment.class, AlbumUploadFragment.class};
		for(String account:new String[]{"account-one@example.test", "account-two@example.test"}){
			setAccount(account);
			for(int i=0; i<ids.length; i++){
				Method fabClick=HomeTabFragment.class.getDeclaredMethod("onFabClick", View.class);
				fabClick.setAccessible(true);
				fabClick.invoke(home, new View(activity));
				assertTrue(ShadowDialog.getLatestDialog() instanceof ComposeActionSheet);
				ComposeActionSheet sheet=(ComposeActionSheet)ShadowDialog.getLatestDialog();
				assertTrue(sheet.isShowing());
				LocalNavigation.expectedDismissedSheet=sheet;
				View card=sheet.findViewById(ids[i]);
				int before=LocalNavigation.destinations.size();
				card.performClick();
				card.performClick();
				assertEquals(before+1, LocalNavigation.destinations.size());
				assertEquals(targets[i], LocalNavigation.destinations.get(before));
				assertEquals(account, LocalNavigation.accounts.get(before));
				LocalNavigation.expectedDismissedSheet=null;
				home.onLiquidMenuItem(ids[i]);
				assertEquals(before+2, LocalNavigation.destinations.size());
				assertEquals(targets[i], LocalNavigation.destinations.get(before+1));
				assertEquals(account, LocalNavigation.accounts.get(before+1));
			}
		}
	}

	@Test public void directLiquidButtonStillComposesOrdinaryPostAndUnknownActionCannotNavigate() throws Exception{
		setAccount("current-fragment-account");
		home.onLiquidCompose();
		home.openComposeAction(-1);
		assertEquals(List.of(ComposeFragment.class), LocalNavigation.destinations);
		assertEquals(List.of("current-fragment-account"), LocalNavigation.accounts);
	}

	private void setAccount(String id) throws Exception{
		Field account=HomeTabFragment.class.getDeclaredField("accountID");
		account.setAccessible(true);
		account.set(home, id);
	}
}
