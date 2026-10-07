package org.joinmastodon.android.ui.sheets;

import android.app.Activity;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, application=CompatibilityTestApplication.class, qualifiers="zh-rCN-w393dp-h851dp")
@LooperMode(LooperMode.Mode.PAUSED)
public class ComposeActionSheetTest{
	private ActivityController<Activity> controller;
	private Activity activity;
	private ComposeActionSheet sheet;

	@Before public void setUp(){
		MastodonApp.context=RuntimeEnvironment.getApplication();
		V.setApplicationContext(MastodonApp.context);
		controller=Robolectric.buildActivity(Activity.class);
		activity=controller.get();
		activity.setTheme(R.style.Theme_Mastodon_Light);
		controller.setup();
	}

	@After public void tearDown(){
		if(sheet!=null && sheet.isShowing()) sheet.dismissWithoutAnimation();
		controller.close();
		MastodonApp.context=null;
	}

	@Test public void eachRealCardDispatchesExactIdOnceAfterDialogRemoval(){
		for(int id:new int[]{R.id.compose_post, R.id.compose_friend_request, R.id.compose_album}){
			List<Integer> delivered=new ArrayList<>();
			sheet=new ComposeActionSheet(activity, action->{
				assertFalse("Dispatch must follow actual dialog removal", sheet.isShowing());
				delivered.add(action);
			});
			sheet.showWithoutAnimation();
			View card=sheet.findViewById(id);
			assertNotNull(card);
			assertTrue(card.isFocusable());
			assertTrue(card.getContentDescription().length()>0);
			card.performClick();
			card.performClick();
			assertEquals(List.of(id), delivered);
			assertFalse(sheet.isShowing());
		}
	}

	@Test public void cancelHeaderAndSystemCancelNeverDispatchEvenThroughStaleCards(){
		for(boolean header:new boolean[]{true, false}){
			List<Integer> delivered=new ArrayList<>();
			sheet=new ComposeActionSheet(activity, delivered::add);
			sheet.showWithoutAnimation();
			View stale=sheet.findViewById(R.id.compose_post);
			if(header) sheet.findViewById(R.id.compose_action_cancel).performClick();
			else sheet.cancel();
			ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
			assertFalse(sheet.isShowing());
			stale.performClick();
			assertTrue(delivered.isEmpty());
		}
	}

	@Test public void lightAndDarkCardsUseTheirOwnThemeContainerPairs(){
		for(int theme:new int[]{R.style.Theme_Mastodon_Light, R.style.Theme_Mastodon_Dark}){
			activity.setTheme(theme);
			sheet=new ComposeActionSheet(activity, id->fail("No click expected"));
			sheet.showWithoutAnimation();
			assertContainer(R.id.compose_post, R.attr.colorM3PrimaryContainer, R.attr.colorM3OnPrimaryContainer);
			assertContainer(R.id.compose_friend_request, R.attr.colorM3SecondaryContainer, R.attr.colorM3OnSecondaryContainer);
			assertContainer(R.id.compose_album, R.attr.colorM3TertiaryContainer, R.attr.colorM3OnTertiaryContainer);
			sheet.dismissWithoutAnimation();
		}
	}

	@Test public void normalPhoneShowsThreeEqualColumnsAndLargeTextReflowsWithoutTruncation(){
		sheet=new ComposeActionSheet(activity, id->fail("No click expected"));
		sheet.showWithoutAnimation();
		LinearLayout cards=sheet.findViewById(R.id.compose_action_cards);
		measure(cards, 353);
		assertEquals(3, cards.getChildCount());
		assertEquals(LinearLayout.HORIZONTAL, cards.getOrientation());
		assertEquals(cards.getChildAt(0).getWidth(), cards.getChildAt(1).getWidth());
		assertEquals(cards.getChildAt(1).getWidth(), cards.getChildAt(2).getWidth());
		assertTrue(sheet.findViewById(R.id.compose_action_cancel).getMinimumHeight()>=V.dp(48));
		assertEquals("选择发布内容", ((TextView)sheet.findViewById(R.id.compose_action_title)).getText().toString());

		sheet.dismissWithoutAnimation();
		android.content.res.Configuration configuration=new android.content.res.Configuration(activity.getResources().getConfiguration());
		configuration.fontScale=2f;
		activity.getResources().updateConfiguration(configuration, activity.getResources().getDisplayMetrics());
		sheet=new ComposeActionSheet(activity, id->fail("No click expected"));
		sheet.showWithoutAnimation();
		cards=sheet.findViewById(R.id.compose_action_cards);
		measure(cards, 352);
		assertEquals(LinearLayout.VERTICAL, cards.getOrientation());
		for(int i=0; i<3; i++){
			ViewGroup card=(ViewGroup)cards.getChildAt(i);
			assertEquals(V.dp(352), card.getWidth());
			for(int textId:new int[]{R.id.compose_action_card_title, R.id.compose_action_card_description}){
				TextView text=card.findViewById(textId);
				assertNull(text.getEllipsize());
				assertEquals(Integer.MAX_VALUE, text.getMaxLines());
				assertTrue(text.getBottom()<=card.getHeight());
			}
		}
	}

	@Test public void outerContainerReservesTheRightSystemSafeInset(){
		sheet=new ComposeActionSheet(activity, id->fail("No click expected"));
		sheet.showWithoutAnimation();
		View root=sheet.findViewById(R.id.compose_action_sheet);
		View parent=(View)root.getParent();
		parent.dispatchApplyWindowInsets(new WindowInsets.Builder().setSystemWindowInsets(Insets.of(8, 24, 48, 30)).build());
		assertEquals(48, parent.getPaddingRight());
		assertEquals(8, parent.getPaddingLeft());
	}

	@Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
	public void actualPublishingSheetRendersInBothAppThemes() throws Exception{
		for(int theme:new int[]{R.style.Theme_Mastodon_Light, R.style.Theme_Mastodon_Dark}){
			activity.setTheme(theme); sheet=new ComposeActionSheet(activity, id->fail("No navigation while capturing")); sheet.showWithoutAnimation();
			View root=sheet.findViewById(R.id.compose_action_sheet);
			for(int pass=0;pass<3;pass++){
				root.forceLayout(); measure(root, 393); root.getViewTreeObserver().dispatchOnPreDraw(); ShadowLooper.idleMainLooper();
			}
			assertTrue(root.isAttachedToWindow());
			android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
			root.draw(new android.graphics.Canvas(bitmap));
			java.io.File directory=new java.io.File("/home/ZYongX/projects/dist/album-unified-release-evidence/screens"); assertTrue(directory.isDirectory() || directory.mkdirs());
			String mode=theme==R.style.Theme_Mastodon_Light ? "light" : "dark";
			try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(directory, "compose-action-"+mode+".png"))){ assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)); }
			bitmap.recycle(); sheet.dismissWithoutAnimation();
		}
	}

	private void assertContainer(int id, int background, int foreground){
		View card=sheet.findViewById(id);
		GradientDrawable drawable=(GradientDrawable)((RippleDrawable)card.getBackground()).getDrawable(0);
		assertEquals(UiUtils.getThemeColor(sheet.getContext(), background), drawable.getColor().getDefaultColor());
		assertEquals(UiUtils.getThemeColor(sheet.getContext(), foreground),
				((TextView)card.findViewById(R.id.compose_action_card_title)).getCurrentTextColor());
	}

	private static void measure(View view, int widthDp){
		int width=V.dp(widthDp);
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		view.layout(0, 0, width, view.getMeasuredHeight());
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		view.layout(0, 0, width, view.getMeasuredHeight());
	}
}
