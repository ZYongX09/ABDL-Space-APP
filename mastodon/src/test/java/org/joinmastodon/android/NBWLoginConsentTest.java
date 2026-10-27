package org.joinmastodon.android;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.joinmastodon.android.api.session.AccountLocalPreferences;
import org.joinmastodon.android.fragments.auth.LoginEmailFragment;
import org.joinmastodon.android.fragments.auth.LoginPasswordFragment;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import me.grishka.appkit.fragments.AppKitFragment;
import me.grishka.appkit.utils.V;

/** Clicks the real two login layouts and their real bottom-sheet confirmation controls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="zh-rCN-w393dp-h852dp-notnight-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class NBWLoginConsentTest{
	private final List<ActivityController<Activity>> activities=new ArrayList<>();
	private GlobalUserPreferences.ThemePreference previousTheme;
	private AccountLocalPreferences.ColorPreference previousColor;
	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		previousTheme=GlobalUserPreferences.theme; GlobalUserPreferences.theme=GlobalUserPreferences.ThemePreference.LIGHT;
		previousColor=GlobalUserPreferences.color; GlobalUserPreferences.color=AccountLocalPreferences.ColorPreference.PURPLE;
	}
	@After public void cleanup(){
		Dialog dialog=ShadowDialog.getLatestDialog(); if(dialog!=null) dialog.dismiss();
		for(var activity:activities) activity.close(); GlobalUserPreferences.theme=previousTheme;
		GlobalUserPreferences.color=previousColor;
	}
	private AppKitFragment page(boolean password){
		var lifecycle=Robolectric.buildActivity(Activity.class).setup(); activities.add(lifecycle); var activity=lifecycle.get();
		UiUtils.setUserPreferredTheme(activity);
		FrameLayout container=new FrameLayout(activity); container.setId(0x1020099); activity.setContentView(container);
		AppKitFragment fragment=password ? new LoginPasswordFragment() : new LoginEmailFragment();
		activity.getFragmentManager().beginTransaction().add(container.getId(),fragment,"login").commit(); activity.getFragmentManager().executePendingTransactions();
		assertNotNull(fragment.getView()); layout(activity.getWindow().getDecorView()); return fragment;
	}
	private static void layout(View view){
		for(int pass=0;pass<2;pass++){
			forceLayout(view);
			view.measure(View.MeasureSpec.makeMeasureSpec(393,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(852,View.MeasureSpec.EXACTLY));
			view.layout(0,0,393,852); view.getViewTreeObserver().dispatchOnPreDraw();
		}
	}
	private static void forceLayout(View view){
		view.forceLayout();
		if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) forceLayout(group.getChildAt(i));
	}
	private static Intent next(Activity activity){ return org.robolectric.shadow.api.Shadow.<ShadowActivity>extract(activity).getNextStartedActivity(); }
	private static void assertNoTint(View view){
		if(view instanceof ImageView image) assertNull(image.getImageTintList());
		if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) assertNoTint(group.getChildAt(i));
	}
	@Test public void bothVisibleButtonsRequireAgreementAndBottomSheetConfirmation(){
		for(boolean password:new boolean[]{false,true}){
			var fragment=page(password); Activity activity=fragment.getActivity(); View root=fragment.getView(), button=root.findViewById(R.id.btn_nbw);
			assertEquals(View.VISIBLE,button.getVisibility()); assertTrue(button.isEnabled()); assertTrue(button.isClickable()); assertNoTint(button);
			button.performClick(); assertNull(next(activity));
			((CheckBox)root.findViewById(R.id.cb_agreement)).setChecked(true); button.performClick();
			Dialog sheet=ShadowDialog.getLatestDialog(); assertNotNull(sheet); assertTrue(sheet.isShowing());
			assertEquals(activity.getString(R.string.nbw_login_title),((TextView)sheet.findViewById(R.id.sheet_title)).getText().toString());
			assertNull(((ImageView)sheet.findViewById(R.id.icon)).getImageTintList()); assertNull(next(activity));
			sheet.findViewById(R.id.btn_cancel).performClick(); assertNull(next(activity));
			button.performClick(); sheet=ShadowDialog.getLatestDialog(); sheet.findViewById(R.id.btn_authorize).performClick();
			Intent intent=next(activity); assertNotNull(intent); assertEquals(NBWLoginActivity.class.getName(),intent.getComponent().getClassName());
			assertTrue(intent.getBooleanExtra(NBWLoginActivity.EXTRA_PERMISSION_CONFIRMED,false)); assertNull(intent.getData());
			assertEquals(java.util.Set.of(NBWLoginActivity.EXTRA_PERMISSION_CONFIRMED),intent.getExtras().keySet()); assertNull(next(activity));
		}
	}
	@Test public void uncheckingAgreementOrDetachingWhileSheetIsOpenCannotLaunch(){
		for(boolean password:new boolean[]{false,true}){
			var fragment=page(password); var activity=fragment.getActivity(); var agreement=(CheckBox)fragment.getView().findViewById(R.id.cb_agreement);
			agreement.setChecked(true); fragment.getView().findViewById(R.id.btn_nbw).performClick(); var sheet=ShadowDialog.getLatestDialog();
			agreement.setChecked(false); sheet.findViewById(R.id.btn_authorize).performClick(); assertNull(next(activity));
			agreement.setChecked(true); fragment.getView().findViewById(R.id.btn_nbw).performClick(); sheet=ShadowDialog.getLatestDialog();
			activity.getFragmentManager().beginTransaction().remove(fragment).commit(); activity.getFragmentManager().executePendingTransactions();
			sheet.findViewById(R.id.btn_authorize).performClick(); assertNull(next(activity));
		}
	}
	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void consentSheetRendersWithOriginalNontintedNbwBrand() throws Exception{
		File directory=new File(System.getProperty("nbw.login.renderDir",System.getProperty("java.io.tmpdir")));
		assertTrue(directory.isDirectory() || directory.mkdirs());
		for(boolean dark:new boolean[]{false,true}){
			GlobalUserPreferences.theme=dark ? GlobalUserPreferences.ThemePreference.DARK : GlobalUserPreferences.ThemePreference.LIGHT;
			RuntimeEnvironment.setQualifiers("zh-rCN-w393dp-h852dp-"+(dark ? "night" : "notnight")+"-mdpi");
			for(boolean password:new boolean[]{false,true}){
				var fragment=page(password); Activity activity=fragment.getActivity();
				((CheckBox)fragment.getView().findViewById(R.id.cb_agreement)).setChecked(true);
				fragment.getView().findViewById(R.id.btn_nbw).performClick(); var dialog=ShadowDialog.getLatestDialog();
				View pageDecor=activity.getWindow().getDecorView(), sheetDecor=dialog.getWindow().getDecorView();
				// show() begins its animation in pre-draw. Let the real 180ms animator finish,
				// then traverse again after two frames so TextViews have their final layouts.
				layout(pageDecor); layout(sheetDecor); ShadowLooper.idleMainLooper(1,TimeUnit.SECONDS);
				layout(pageDecor); layout(sheetDecor); ShadowLooper.idleMainLooper(32,TimeUnit.MILLISECONDS);
				layout(pageDecor); layout(sheetDecor);
				File output=new File(directory,"nbw-login-consent-"+(password ? "password-" : "email-")+(dark ? "dark" : "light")+".png");
				Bitmap bitmap=Bitmap.createBitmap(393,852,Bitmap.Config.ARGB_8888);
				try{
					// A dialog's decor is transparent above its sheet. Reproduce only Android's
					// real window composition; all page, scrim and sheet content come from actual views.
					Canvas canvas=new Canvas(bitmap); pageDecor.draw(canvas);
					WindowManager.LayoutParams attributes=dialog.getWindow().getAttributes();
					if((attributes.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND)!=0)
						canvas.drawColor(Color.argb(Math.round(255*attributes.dimAmount),0,0,0));
					sheetDecor.draw(canvas);
					try(FileOutputStream stream=new FileOutputStream(output)){ assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream)); }
					assertTrue(output.length()>0); assertEquals(255,Color.alpha(bitmap.getPixel(196,200)));
					assertTrue(fragment.getView().findViewById(R.id.btn_nbw).isAttachedToWindow());
					assertNull(((ImageView)dialog.findViewById(R.id.icon)).getImageTintList());
					assertRenderedControl(dialog,sheetDecor,R.id.btn_authorize,activity.getString(R.string.confirm),output);
					assertRenderedControl(dialog,sheetDecor,R.id.btn_cancel,"取消",output);
					assertEquals(0f,((View)dialog.findViewById(R.id.sheet_title).getParent().getParent()).getTranslationY(),0.01f);
				}finally{
					bitmap.recycle(); ((me.grishka.appkit.views.BottomSheet)dialog).dismissWithoutAnimation();
					activities.get(activities.size()-1).close(); activities.remove(activities.size()-1); ShadowLooper.idleMainLooper();
				}
			}
		}
	}
	private static void assertRenderedControl(Dialog dialog,View decor,int id,String expected,File output){
		TextView button=dialog.findViewById(id);
		int[] buttonOrigin=new int[2], decorOrigin=new int[2];
		button.getLocationInWindow(buttonOrigin); decor.getLocationInWindow(decorOrigin);
		Rect bounds=new Rect(buttonOrigin[0]-decorOrigin[0],buttonOrigin[1]-decorOrigin[1],
				buttonOrigin[0]-decorOrigin[0]+button.getWidth(),buttonOrigin[1]-decorOrigin[1]+button.getHeight());
		Rect visible=new Rect(); boolean onScreen=button.getGlobalVisibleRect(visible);
		String diagnostic="PNG="+output+", text="+button.getText()+", bounds="+bounds+", visible="+onScreen+"/"+visible
				+", shown="+button.isShown()+", attached="+button.isAttachedToWindow()+", alpha="+button.getAlpha()
				+", textColor="+Integer.toHexString(button.getCurrentTextColor())+", textSize="+button.getTextSize()
				+", layout="+button.getLayout();
		assertEquals(diagnostic,expected,button.getText().toString());
		assertTrue(diagnostic,button.isShown() && button.isAttachedToWindow() && button.isEnabled() && onScreen && button.getAlpha()==1f
				&& bounds.width()>0 && bounds.height()>0 && new Rect(0,0,393,852).contains(bounds)
				&& visible.width()==button.getWidth() && visible.height()==button.getHeight());
		assertNotNull(diagnostic,button.getLayout()); assertTrue(diagnostic,button.getLayout().getLineCount()>0 && button.getTextSize()>0);
		assertEquals(diagnostic,0,button.getLayout().getEllipsisCount(0)); assertTrue(diagnostic,Color.alpha(button.getCurrentTextColor())>0);
		// Both labels use the same M3 palette attributes as their actual XML styles.
		assertEquals(diagnostic,UiUtils.getThemeColor(button.getContext(),id==R.id.btn_authorize ? R.attr.colorM3OnPrimary : R.attr.colorM3Primary),button.getCurrentTextColor());
	}
}
