package org.joinmastodon.android.sponsors;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.R;
import org.joinmastodon.android.model.sponsors.SponsorModels.Plan;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26, 30, 32}, application=CompatibilityTestApplication.class)
public class SponsorUiLayoutTest{
	@Before public void setUp(){
		V.setApplicationContext(ApplicationProvider.getApplicationContext());
	}

	@Test public void compactPlanCardUsesStableTextBands(){
		Context context=ApplicationProvider.getApplicationContext();
		context.setTheme(R.style.Theme_Mastodon_Light);
		LinearLayout parent=new LinearLayout(context);
		Plan plan=new Plan();
		plan.id="monthly";
		plan.name="一个特别特别长的月度赞助者方案名称";
		plan.description="这是一段会自然换成两行但不能挤压标题和价格的服务器说明";
		plan.currency="CNY";
		plan.priceMinor=590;
		plan.durationUnit="month";
		plan.durationCount=1;
		plan.enabled=true;

		LinearLayout card=SponsorUi.compactPlanCard(parent, plan, true, true, ()->{});
		TextView name=(TextView) card.getChildAt(0);
		TextView amount=(TextView) card.getChildAt(1);
		TextView hint=(TextView) card.getChildAt(2);

		assertEquals(V.dp(132), card.getLayoutParams().width);
		assertEquals(V.dp(116), card.getLayoutParams().height);
		assertEquals(V.dp(24), name.getLayoutParams().height);
		assertEquals(V.dp(32), amount.getLayoutParams().height);
		assertEquals(V.dp(46), hint.getLayoutParams().height);
		assertEquals(1, name.getMaxLines());
		assertEquals(2, hint.getMaxLines());
		assertSame(hint, card.getTag(R.id.sponsor_plan_hint));
		assertEquals(View.VISIBLE, card.getVisibility());
	}
}
