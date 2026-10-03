package org.joinmastodon.android.sponsors;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.test.core.app.ApplicationProvider;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26, 35}, application=CompatibilityTestApplication.class)
public class SponsorCheckoutUiTest{
	@Before public void setUp(){ V.setApplicationContext(ApplicationProvider.getApplicationContext()); }

	@Test public void allWrongAnswersStayInQuizAndCorrectAnswerLaunchesOnlyOnce(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			Activity activity=controller.get(); activity.setTheme(R.style.Theme_Mastodon_Light);
			AtomicInteger launches=new AtomicInteger();
			AlertDialog dialog=SponsorCheckoutUi.createQuiz(activity, ()->true, launches::incrementAndGet);
			dialog.show();
			List<Button> choices=choices(dialog);
			assertEquals(List.of("返回按钮", "私信按钮", "获取兑换码按钮", "赞助按钮"), choices.stream().map(v->v.getText().toString()).toList());
			assertTrue(texts(dialog.getWindow().getDecorView()).stream().anyMatch(v->v.getText().toString().equals("爱发电付款成功后点击页面内的什么查询兑换码？")));
			for(Button choice:choices){
				if(choice.getText().toString().equals("私信按钮")) continue;
				choice.performClick();
				assertEquals(0, launches.get());
				assertTrue(dialog.isShowing());
				assertTrue(texts(dialog.getWindow().getDecorView()).stream().anyMatch(v->v.getVisibility()==View.VISIBLE && v.getText().toString().equals(activity.getString(R.string.sponsor_ui_quiz_wrong))));
			}
			Button correct=choices.get(1); correct.performClick(); correct.performClick();
			assertEquals(1, launches.get()); assertFalse(dialog.isShowing());
		}
	}

	@Test public void cancelNeverLaunchesOrAcceptsStaleClicks(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			Activity activity=controller.get(); activity.setTheme(R.style.Theme_Mastodon_Light);
			AtomicInteger launches=new AtomicInteger();
			AlertDialog dialog=SponsorCheckoutUi.createQuiz(activity, ()->true, launches::incrementAndGet);
			dialog.show(); Button correct=choices(dialog).get(1);
			dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
			org.robolectric.shadows.ShadowLooper.idleMainLooper(); correct.performClick();
			assertFalse(dialog.isShowing()); assertEquals(0, launches.get());
		}
	}

	@Test public void invalidatedPageCannotLaunchEvenWithCorrectAnswer(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			Activity activity=controller.get(); activity.setTheme(R.style.Theme_Mastodon_Light);
			AtomicInteger launches=new AtomicInteger(); AtomicBoolean active=new AtomicBoolean(true);
			AlertDialog dialog=SponsorCheckoutUi.createQuiz(activity, active::get, launches::incrementAndGet);
			dialog.show(); active.set(false); choices(dialog).get(1).performClick();
			assertEquals(0, launches.get()); dialog.dismiss();
		}
	}

	@Test public void babyPinkReminderRemainsReadableInLightAndDarkThemes(){
		for(int theme:new int[]{R.style.Theme_Mastodon_Light, R.style.Theme_Mastodon_Dark}){
			try(var controller=Robolectric.buildActivity(Activity.class).setup()){
				Activity activity=controller.get(); activity.setTheme(theme);
				LinearLayout parent=new LinearLayout(activity);
				LinearLayout card=SponsorCheckoutUi.paymentReminder(parent);
				assertEquals(Color.rgb(248, 215, 230), ((GradientDrawable)card.getBackground()).getColor().getDefaultColor());
				TextView body=(TextView)card.getChildAt(1);
				assertEquals(Color.rgb(85, 39, 61), body.getCurrentTextColor());
				assertTrue(body.getText().toString().contains("爱发电付款成功后"));
				Spanned text=(Spanned)body.getText();
				BackgroundColorSpan[] background=text.getSpans(0, text.length(), BackgroundColorSpan.class);
				assertEquals(1, background.length);
				assertEquals("私信", text.subSequence(text.getSpanStart(background[0]), text.getSpanEnd(background[0])).toString());
				assertEquals(Color.rgb(242, 166, 197), background[0].getBackgroundColor());
				assertEquals(Typeface.BOLD, text.getSpans(0, text.length(), StyleSpan.class)[0].getStyle());
			}
		}
	}

	private static List<Button> choices(AlertDialog dialog){
		return texts(dialog.getWindow().getDecorView()).stream().filter(v->v instanceof Button && List.of("返回按钮", "私信按钮", "获取兑换码按钮", "赞助按钮").contains(v.getText().toString())).map(v->(Button)v).toList();
	}
	private static List<TextView> texts(View view){
		List<TextView> result=new ArrayList<>();
		if(view instanceof TextView text) result.add(text);
		if(view instanceof ViewGroup group) for(int i=0; i<group.getChildCount(); i++) result.addAll(texts(group.getChildAt(i)));
		return result;
	}
}
