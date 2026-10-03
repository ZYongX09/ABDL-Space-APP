package org.joinmastodon.android.chat.ui;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import me.grishka.appkit.utils.V;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30, 35}, application=CompatibilityTestApplication.class, qualifiers="ldltr")
public class ConversationsFragmentLayoutTest{
	private static final int[] DIRECTIONS={View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL};

	@Before
	public void setUp(){
		V.setApplicationContext(ApplicationProvider.getApplicationContext());
	}

	@Test
	public void tabModeTitleHasSixteenDpGuttersAndNoBackButton(){
		for(int direction:DIRECTIONS){
			try(Page page=new Page(true, direction)){
				assertToolbar(page, 0);
				assertEquals(0, page.fragment.loadCalls);
				assertEquals(page.dp(8), page.recycler.getPaddingBottom());
			}
		}
	}

	@Test
	public void standaloneModeKeepsOriginalToolbarAndWorkingBackButton(){
		for(int direction:DIRECTIONS){
			try(Page page=new Page(false, direction)){
				assertToolbar(page, 0);
				assertEquals(page.dp(48), page.backButton.getWidth());
				assertEquals(1, page.fragment.loadCalls);
				assertTrue(page.backButton.performClick());
				assertEquals(1, page.activity.backPresses);
			}
		}
	}

	@Test
	public void repeatedAndChangedTopInsetsDoNotAccumulateOrLoseGutters(){
		for(boolean tabMode:new boolean[]{true, false}){
			for(int direction:DIRECTIONS){
				try(Page page=new Page(tabMode, direction)){
					for(int top:new int[]{24, 24, 41, 0, 0}){
						page.fragment.onApplyWindowInsets(insets(top, 32));
						page.layout();
						assertToolbar(page, top);
						assertEquals(page.dp(8)+(tabMode ? 0 : 32), page.recycler.getPaddingBottom());
					}
				}
			}
		}
	}

	@Test
	public void layoutDirectionChangesAfterInsetsKeepToolbarPaddingRelative(){
		for(boolean tabMode:new boolean[]{true, false}){
			try(Page page=new Page(tabMode, View.LAYOUT_DIRECTION_LTR)){
				page.fragment.onApplyWindowInsets(insets(24, 32));
				page.layout();
				assertToolbar(page, 24);

				page.container.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
				page.layout();
				assertToolbar(page, 24);
				page.fragment.onApplyWindowInsets(insets(41, 32));
				page.layout();
				assertToolbar(page, 41);

				page.container.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
				page.layout();
				assertToolbar(page, 41);
			}
		}
	}

	@Test
	public void tabBottomPaddingKeepsBaseAndUsesOverlayInsteadOfSystemInset(){
		for(int direction:DIRECTIONS){
			// Home can supply its overlay inset before the fragment creates its view.
			try(Page page=new Page(true, direction, 72)){
				seedRecyclerPadding(page);
				for(int top:new int[]{24, 24, 41}){
					page.fragment.onApplyWindowInsets(insets(top, 32));
					assertRecyclerPadding(page, 72);
				}
				page.fragment.setTabBarBottomInset(96);
				assertRecyclerPadding(page, 96);
				page.fragment.onApplyWindowInsets(insets(41, 80));
				assertRecyclerPadding(page, 96);

				page.container.setLayoutDirection(direction==View.LAYOUT_DIRECTION_LTR
						? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
				page.layout();
				assertRecyclerPadding(page, 96);
				page.fragment.setTabBarBottomInset(0);
				page.fragment.onApplyWindowInsets(insets(0, 80));
				assertRecyclerPadding(page, 0);
			}
		}
	}

	@Test
	public void standaloneBottomPaddingUsesStableInsetWithoutAccumulation(){
		for(int direction:DIRECTIONS){
			try(Page page=new Page(false, direction)){
				seedRecyclerPadding(page);
				for(int bottom:new int[]{32, 32, 64, 0, 0}){
					page.fragment.onApplyWindowInsets(insets(24, bottom));
					assertRecyclerPadding(page, bottom);
				}
				page.container.setLayoutDirection(direction==View.LAYOUT_DIRECTION_LTR
						? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
				page.layout();
				assertRecyclerPadding(page, 0);
				page.fragment.onApplyWindowInsets(insets(41, 48));
				assertRecyclerPadding(page, 48);
			}
		}
	}

	@Test
	@Config(sdk=26)
	public void legacyInsetsKeepTitleGuttersAndTabOverlay(){
		for(boolean tabMode:new boolean[]{true, false}){
			for(int direction:DIRECTIONS){
				try(Page page=new Page(tabMode, direction, 72)){
					for(int top:new int[]{24, 24, 0}){
						WindowInsets insets=new WindowInsetsCompat.Builder()
								.setSystemWindowInsets(androidx.core.graphics.Insets.of(0, top, 0, 32))
								.build().toWindowInsets();
						assertNotNull(insets);
						page.fragment.onApplyWindowInsets(insets);
						page.layout();
						assertToolbar(page, top);
						assertEquals(page.dp(8)+(tabMode ? 72 : insets.getStableInsetBottom()),
								page.recycler.getPaddingBottom());
					}
				}
			}
		}
	}

	private static WindowInsets insets(int top, int stableBottom){
		// Keep the current system bottom at zero to distinguish stable navigation insets.
		WindowInsets insets=new WindowInsets.Builder()
				.setSystemWindowInsets(Insets.of(0, top, 0, 0))
				.setStableInsets(Insets.of(0, top, 0, stableBottom))
				.build();
		assertEquals(top, insets.getSystemWindowInsetTop());
		assertEquals(stableBottom, insets.getStableInsetBottom());
		return insets;
	}

	private static void assertToolbar(Page page, int top){
		int gutter=page.dp(16);
		assertTrue(page.toolbar.isPaddingRelative());
		assertEquals(page.tabMode ? gutter : 0, page.toolbar.getPaddingStart());
		assertEquals(gutter, page.toolbar.getPaddingEnd());
		assertEquals(top, page.toolbar.getPaddingTop());
		assertEquals(0, page.toolbar.getPaddingBottom());
		assertEquals(page.dp(64)+top, page.toolbar.getLayoutParams().height);
		assertEquals(page.dp(64)+top, page.toolbar.getHeight());
		assertEquals(page.tabMode ? View.GONE : View.VISIBLE, page.backButton.getVisibility());
		assertEquals("私信", page.title.getText().toString());
		assertEquals(top, page.title.getTop());
		assertEquals(page.dp(64), page.title.getHeight());
		assertEquals(page.container.getLayoutDirection(), page.toolbar.getLayoutDirection());
		int titleStart=page.tabMode ? gutter : page.dp(48);
		if(page.toolbar.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL){
			assertEquals(gutter, page.title.getLeft());
			assertEquals(page.toolbar.getWidth()-titleStart, page.title.getRight());
		}else{
			assertEquals(titleStart, page.title.getLeft());
			assertEquals(page.toolbar.getWidth()-gutter, page.title.getRight());
		}
	}

	private static void seedRecyclerPadding(Page page){
		page.recycler.setPaddingRelative(7, 5, 13, page.recycler.getPaddingBottom());
	}

	private static void assertRecyclerPadding(Page page, int inset){
		assertTrue(page.recycler.isPaddingRelative());
		assertEquals(7, page.recycler.getPaddingStart());
		assertEquals(13, page.recycler.getPaddingEnd());
		assertEquals(5, page.recycler.getPaddingTop());
		assertEquals(page.dp(8)+inset, page.recycler.getPaddingBottom());
		boolean rtl=page.recycler.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
		assertEquals(rtl ? 13 : 7, page.recycler.getPaddingLeft());
		assertEquals(rtl ? 7 : 13, page.recycler.getPaddingRight());
	}

	public static class BackRecordingActivity extends Activity{
		int backPresses;

		@Override
		public void onBackPressed(){
			backPresses++;
		}
	}

	// Only network loading is replaced; lifecycle, XML inflation and inset handling are real.
	public static class LayoutTestFragment extends ConversationsFragment{
		int loadCalls;

		@Override
		public void loadData(){
			loadCalls++;
		}
	}

	private static class Page implements AutoCloseable{
		final ActivityController<BackRecordingActivity> controller;
		final BackRecordingActivity activity;
		final FrameLayout container;
		final LayoutTestFragment fragment;
		final LinearLayout toolbar;
		final ImageButton backButton;
		final TextView title;
		final RecyclerView recycler;
		final boolean tabMode;

		Page(boolean tabMode, int direction){
			this(tabMode, direction, 0);
		}

		Page(boolean tabMode, int direction, int initialOverlay){
			this.tabMode=tabMode;
			controller=Robolectric.buildActivity(BackRecordingActivity.class).setup();
			activity=controller.get();
			activity.setTheme(R.style.Theme_Mastodon_Light);
			// The shared compatibility manifest omits supportsRtl; enable it only in this fixture.
			activity.getApplicationInfo().flags|=ApplicationInfo.FLAG_SUPPORTS_RTL;
			activity.getApplicationInfo().targetSdkVersion=Math.max(17, activity.getApplicationInfo().targetSdkVersion);
			container=new FrameLayout(activity);
			container.setId(View.generateViewId());
			container.setLayoutDirection(direction);
			activity.setContentView(container);
			fragment=new LayoutTestFragment();
			Bundle args=new Bundle();
			args.putString("account", "offline-layout-test");
			if(tabMode){
				args.putBoolean("noAutoLoad", true);
				fragment.setTabBarBottomInset(initialOverlay);
			}
			fragment.setArguments(args);
			activity.getFragmentManager().beginTransaction().add(container.getId(), fragment, "conversations").commit();
			activity.getFragmentManager().executePendingTransactions();
			View root=fragment.getView();
			assertNotNull(root);
			toolbar=root.findViewById(R.id.toolbar);
			backButton=root.findViewById(R.id.back_btn);
			title=(TextView)toolbar.getChildAt(1);
			recycler=root.findViewById(R.id.recycler);
			layout();
		}

		int dp(int value){
			return Math.round(value*activity.getResources().getDisplayMetrics().density);
		}

		void layout(){
			int width=dp(360), height=dp(640);
			container.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
			container.layout(0, 0, width, height);
		}

		@Override
		public void close(){
			controller.close();
		}
	}
}
