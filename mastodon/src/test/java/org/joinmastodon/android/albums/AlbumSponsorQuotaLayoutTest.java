package org.joinmastodon.android.albums;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.fragments.sponsors.SponsorCenterFragment;
import org.joinmastodon.android.model.albums.AlbumModels;
import org.joinmastodon.android.model.sponsors.SponsorModels;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import me.grishka.appkit.utils.V;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class AlbumSponsorQuotaLayoutTest{
	private static Field field(Class<?> type, String name) throws Exception{ Field f=type.getDeclaredField(name); f.setAccessible(true); return f; }

	public static class LayoutProbe extends SponsorCenterFragment{
		@Override protected void doLoadData(){}
		@Override public android.view.View onCreateView(android.view.LayoutInflater inflater, android.view.ViewGroup parent, android.os.Bundle state){ return null; }
		@Override public void onViewCreated(android.view.View view, android.os.Bundle state){}
		@Override protected void onShown(){}
		@Override protected void onHidden(){}
	}

	@Test public void largeStorageAmountsUseLongsAndMatchQuotaCard() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		for(int theme:new int[]{R.style.Theme_Mastodon_Light, R.style.Theme_Mastodon_Dark}){
			try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class)){
				Activity activity=lifecycle.get(); activity.setTheme(theme); lifecycle.setup();
				SponsorCenterFragment fragment=new LayoutProbe();
				android.os.Bundle args=new android.os.Bundle(); args.putString("account", "offline-quota-layout"); fragment.setArguments(args);
				activity.getFragmentManager().beginTransaction().add(fragment, "quota-layout").commit();
				activity.getFragmentManager().executePendingTransactions();
				SponsorModels.Me me=new SponsorModels.Me(); me.albumQuota=new AlbumModels.StorageQuota();
				me.albumQuota.limitBytes=100L*1024*1024*1024;
				me.albumQuota.usedBytes=25L*1024*1024*1024;
				me.albumQuota.reservedBytes=5L*1024*1024*1024;
				me.albumQuota.remainingBytes=70L*1024*1024*1024;
				field(SponsorCenterFragment.class, "me").set(fragment, me);
				LinearLayout parent=new LinearLayout(activity);
				Method render=SponsorCenterFragment.class.getDeclaredMethod("renderAlbumQuota", LinearLayout.class); render.setAccessible(true);
				render.invoke(fragment, parent);
				LinearLayout card=(LinearLayout)parent.getChildAt(0);
				assertEquals(activity.getString(R.string.baby_albums_storage_quota), ((TextView)card.getChildAt(0)).getText().toString());
				assertTrue(((TextView)card.getChildAt(1)).getText().toString().contains("100"));
				LinearLayout meter=(LinearLayout)card.getChildAt(2);
				assertEquals(70, ((ProgressBar)meter.getChildAt(0)).getProgress());
				assertEquals(4, card.getChildCount());
				me.albumQuota=null; parent.removeAllViews(); render.invoke(fragment, parent);
				card=(LinearLayout)parent.getChildAt(0);
				assertEquals(activity.getString(R.string.baby_albums_storage_unavailable), ((TextView)card.getChildAt(1)).getText().toString());
				assertEquals(2, card.getChildCount());
			}
		}
	}
}
