package org.joinmastodon.android.friendcards;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=CompatibilityTestApplication.class)
public class FriendCardPromotionTest{
	private Context context;
	private SharedPreferences preferences;
	@Before public void setup(){
		context=RuntimeEnvironment.getApplication();
		preferences=context.getSharedPreferences("friend_card_promotion",0);
		assertTrue(preferences.edit().clear().commit());
	}
	private FriendCardPromotion page(int version,String token){ return new FriendCardPromotion(context,version,token); }

	@Test public void onlyAppVersions33And34ShareFiveInstallationWidePresentations(){
		for(int version:new int[]{32,35}){
			assertFalse(FriendCardPromotion.supports(version));
			assertFalse(page(version,"outside").canShow()); assertFalse(page(version,"outside").onVisible());
		}
		for(int i=0;i<5;i++){
			FriendCardPromotion promotion=page(i%2==0 ? 33 : 34,"page-"+i);
			assertTrue(promotion.canShow()); assertTrue(promotion.onVisible());
		}
		assertEquals(5,preferences.getStringSet("shown_pages",java.util.Set.of()).size());
		assertFalse(page(33,"sixth").canShow()); assertFalse(page(34,"sixth").onVisible());
		assertTrue(page(34,"page-4").onVisible());
	}

	@Test public void preparingRedrawingAndRestoringSamePageNeverConsumeAgain(){
		for(int i=0;i<20;i++) assertTrue(page(33,"same").canShow());
		assertFalse(preferences.contains("shown_pages"));
		for(int i=0;i<20;i++) assertTrue(page(i%2==0 ? 33 : 34,"same").onVisible());
		assertEquals(java.util.Set.of("same"),preferences.getStringSet("shown_pages",java.util.Set.of()));
	}

	@Test public void closeIsPermanentAcrossRecreationVersionChangesAndAlreadyCountedPages(){
		assertTrue(page(33,"first").onVisible()); page(33,"first").dismiss();
		for(int version:new int[]{32,33,34,35}) for(String token:new String[]{"first","new"}){
			assertFalse(page(version,token).canShow()); assertFalse(page(version,token).onVisible());
		}
		assertEquals(1,preferences.getStringSet("shown_pages",java.util.Set.of()).size());
	}

	@Test public void concurrentPagesCannotExceedSharedCapOrDuplicateAToken() throws Exception{
		var executor=Executors.newFixedThreadPool(8);
		CountDownLatch start=new CountDownLatch(1);
		List<Future<Boolean>> results=new ArrayList<>();
		try{
			for(int i=0;i<24;i++){
				final int index=i;
				results.add(executor.submit(()->{ start.await(); return page(index%2==0 ? 33 : 34,"concurrent-"+index).onVisible(); }));
			}
			start.countDown(); int shown=0; for(Future<Boolean> result:results) if(result.get()) shown++;
			assertEquals(5,shown); assertEquals(5,preferences.getStringSet("shown_pages",java.util.Set.of()).size());
		}finally{ executor.shutdownNow(); }
	}
}
