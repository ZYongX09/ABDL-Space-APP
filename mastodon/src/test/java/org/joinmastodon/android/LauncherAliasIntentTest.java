package org.joinmastodon.android;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;

import org.joinmastodon.android.startup.StartupLogoView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,application=CompatibilityTestApplication.class)
public class LauncherAliasIntentTest{
	@Test public void exportedAliasCannotForwardInternalNavigationOrGrants(){
		Intent launch=new Intent(Intent.ACTION_VIEW, Uri.parse("https://abdl-space.top/settings/baby-verification"));
		launch.setComponent(new ComponentName("top.abdl_space.app",StartupLogoView.LAUNCHER_ALIAS));
		launch.putExtra("fromExternalShare",true);
		launch.putExtra("account","fixture-account");
		launch.putExtra("compose",true);
		launch.setClipData(ClipData.newPlainText("fixture","not a launcher argument"));
		assertTrue(MainActivity.consumeLauncherIntent(launch));
		assertEquals(Intent.ACTION_MAIN,launch.getAction());
		assertNull(launch.getExtras());
		assertNull(launch.getData());
		assertNull(launch.getClipData());
	}

	@Test public void privateMainIntentsAreNotChanged(){
		Intent intent=new Intent(Intent.ACTION_VIEW, Uri.parse("https://abdl-space.top/settings/baby-verification"));
		intent.setComponent(new ComponentName("top.abdl_space.app",MainActivity.class.getName()));
		intent.putExtra("account","fixture-account");
		assertFalse(MainActivity.consumeLauncherIntent(intent));
		assertEquals(Intent.ACTION_VIEW,intent.getAction());
		assertEquals("fixture-account",intent.getStringExtra("account"));
		assertNotNull(intent.getData());
	}
}
