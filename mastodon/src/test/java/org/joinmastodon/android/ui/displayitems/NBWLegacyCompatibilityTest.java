package org.joinmastodon.android.ui.displayitems;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.ui.text.NBWSourceSpan;
import org.joinmastodon.android.ui.utils.NBWAvatarImageDownloader;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.processing.ImageProcessingStep;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,27,28}, application=CompatibilityTestApplication.class)
public class NBWLegacyCompatibilityTest{
	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication();
		V.setApplicationContext(MastodonApp.context);
	}

	private Account author() throws Exception{
		Account account=new Account();
		account.id="nbw_123";
		account.username=account.acct=account.displayName="Author";
		account.url="https://www.newbabyworld.top/?123";
		account.avatar=null;
		account.avatarStatic=" ";
		account.postprocess();
		return account;
	}

	@Test public void legacyAvatarDecodeIsBoundedSoftwareAndProcessesInOrder() throws Exception{
		Account account=author();
		assertEquals(Account.NBW_DEFAULT_AVATAR,account.avatar);
		NBWAvatarImageDownloader downloader=new NBWAvatarImageDownloader(ImageCache.getInstance(MastodonApp.context),MastodonApp.context);
		List<Integer> order=new ArrayList<>();
		ImageProcessingStep first=new ImageProcessingStep(){
			@Override public Drawable processDrawable(Drawable image){
				Bitmap bitmap=((BitmapDrawable)image).getBitmap();
				assertEquals(48,bitmap.getWidth());
				assertEquals(48,bitmap.getHeight());
				assertEquals(Bitmap.Config.ARGB_8888,bitmap.getConfig());
				order.add(1);
				return image;
			}
			@Override public String getMemoryCacheKey(){ return "nbw-legacy-first"; }
		};
		ImageProcessingStep second=new ImageProcessingStep(){
			@Override public Drawable processDrawable(Drawable image){
				assertEquals(List.of(1),order);
				order.add(2);
				return image;
			}
			@Override public String getMemoryCacheKey(){ return "nbw-legacy-second"; }
		};
		UrlImageLoaderRequest request=new UrlImageLoaderRequest(Bitmap.Config.ARGB_8888,48,96,List.of(first,second),Uri.parse(account.avatar));
		assertTrue(downloader.canHandleRequest(request));
		assertFalse(downloader.needsDiskCache());
		assertNull(downloader.getDrawable(request,false,null));
		Bitmap bitmap=((BitmapDrawable)downloader.getDrawable(request,true,null)).getBitmap();
		assertEquals(List.of(1,2),order);
		assertEquals(Bitmap.DENSITY_NONE,bitmap.getDensity());
		bitmap.recycle();
	}

	@Test public void sourceLogoTracksNameSizeWithoutChangingFontMetrics() throws Exception{
		SpannableStringBuilder name=new SpannableStringBuilder("Author");
		NBWSourceSpan.appendTo(name,author(),MastodonApp.context);
		NBWSourceSpan[] spans=name.getSpans(0,name.length(),NBWSourceSpan.class);
		assertEquals(1,spans.length);
		Paint paint=new Paint();
		for(float size:new float[]{12f,24f,37f}){
			paint.setTextSize(size);
			Paint.FontMetricsInt metrics=paint.getFontMetricsInt();
			int ascent=metrics.ascent,descent=metrics.descent;
			assertEquals(Math.round(size),spans[0].getSize(paint,name,name.length()-1,name.length(),metrics));
			assertEquals(ascent,metrics.ascent);
			assertEquals(descent,metrics.descent);
			assertEquals(Math.round(size),spans[0].getDrawable().getBounds().width());
			assertEquals(Math.round(size),spans[0].getDrawable().getBounds().height());
		}
	}
}
