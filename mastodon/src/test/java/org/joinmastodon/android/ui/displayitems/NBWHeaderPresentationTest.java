package org.joinmastodon.android.ui.displayitems;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.text.NBWSourceSpan;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.utils.V;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="xxxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NBWHeaderPresentationTest{
	private ActivityController<Activity> lifecycle;
	private Activity activity;

	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		UiUtils.updateLocalizedDateFormatters(MastodonApp.context);
		lifecycle=Robolectric.buildActivity(Activity.class); activity=lifecycle.get();
		activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.setup();
	}
	@After public void cleanup(){ lifecycle.close(); }

	private Status post(String id) throws Exception{
		Account author=new Account(); author.id=id; author.username=author.acct="author"; author.displayName="Author";
		author.url="https://www.newbabyworld.top/?4";
		author.avatar=author.avatarStatic=Account.NBW_DEFAULT_AVATAR;
		author.postprocess();
		Status status=Status.ofFake("nbw_101", "", Instant.now()); status.account=author; status.url=status.uri="https://www.newbabyworld.top/forum.php?mod=viewthread&tid=101"; return status;
	}
	private TextView name(Status status, int flags){
		StatusDisplayItem item=StatusDisplayItem.buildItems(activity,status,"selected",status,Map.of(),flags|StatusDisplayItem.FLAG_NO_FOOTER)
				.stream().filter(i->i instanceof HeaderStatusDisplayItem || i instanceof CompactHeaderStatusDisplayItem).findFirst().orElseThrow();
		FrameLayout parent=new FrameLayout(activity);
		if(item instanceof CompactHeaderStatusDisplayItem compact){
			CompactHeaderStatusDisplayItem.Holder holder=new CompactHeaderStatusDisplayItem.Holder(activity,parent); holder.bind(compact);
			return holder.itemView.findViewById(R.id.name);
		}
		HeaderStatusDisplayItem.Holder holder=new HeaderStatusDisplayItem.Holder(activity,parent); holder.bind((HeaderStatusDisplayItem)item);
		return holder.itemView.findViewById(R.id.name);
	}
	private NBWSourceSpan span(TextView name){
		Spanned text=(Spanned)name.getText(); NBWSourceSpan[] spans=text.getSpans(0,text.length(),NBWSourceSpan.class);
		assertEquals(1,spans.length); assertEquals(text.length()-1,text.getSpanStart(spans[0]));
		assertEquals("Author\u00A0\uFFFC",text.toString()); return spans[0];
	}

	@Test public void standardAndCompactHeadersRenderLoginLogoInColourAtCurrentNameSize() throws Exception{
		for(int flags:new int[]{0,StatusDisplayItem.FLAG_IS_QUOTE}){
			TextView name=name(post("nbw_123"),flags); NBWSourceSpan logo=span(name);
			name.setTextColor(Color.BLUE); // A tinted icon would now lose the login logo's other colours.
			for(int sp:new int[]{14,27}){
				name.setTextSize(TypedValue.COMPLEX_UNIT_SP,sp);
				Paint.FontMetricsInt fm=new Paint.FontMetricsInt(); fm.top=-101; fm.ascent=-81; fm.descent=19; fm.bottom=29;
				assertEquals(Math.round(name.getTextSize()),logo.getSize(name.getPaint(),name.getText(),0,1,fm));
				assertEquals(-101,fm.top); assertEquals(-81,fm.ascent); assertEquals(19,fm.descent); assertEquals(29,fm.bottom);
				assertEquals(Math.round(name.getTextSize()),logo.getDrawable().getBounds().width());
				assertEquals(Math.round(name.getTextSize()),logo.getDrawable().getBounds().height());
				name.measure(View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.AT_MOST));
				name.layout(0,0,name.getMeasuredWidth(),name.getMeasuredHeight());
				Bitmap bitmap=Bitmap.createBitmap(name.getWidth(),name.getHeight(),Bitmap.Config.ARGB_8888); name.draw(new Canvas(bitmap));
				boolean red=false,green=false,yellow=false,purple=false;
				for(int y=0;y<bitmap.getHeight();y++) for(int x=0;x<bitmap.getWidth();x++){
					int c=bitmap.getPixel(x,y); if(Color.alpha(c)<200) continue;
					int r=Color.red(c),g=Color.green(c),b=Color.blue(c);
					red|=r>220 && g<120 && b<120; green|=g>150 && r<100 && b<160;
					yellow|=r>220 && g>150 && b<90; purple|=r>130 && g<130 && b>180;
				}
				assertTrue("Actual name Canvas must retain all four NBW logo colours",red && green && yellow && purple); bitmap.recycle();
			}
		}
	}

	@Test public void contentAuthorNotRebloggerOrLocalBindingControlsSourceIcon() throws Exception{
		Status remote=post("nbw_123"),local=post("42"); local.account.nbwUsername="bound-nbw-user";
		local.reblog=remote; span(name(local,0));
		remote.reblog=post("43"); TextView localContent=name(remote,0);
		assertEquals("Author",localContent.getText().toString());
		for(String id:List.of("42","nbw_quote_88","nbw_0","nbw_0001","nbw_9223372036854775808")){
			TextView plain=name(post(id),0); assertEquals("Author",plain.getText().toString());
		}
	}
}
