package org.joinmastodon.android.ui.displayitems;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.model.FriendCard;
import org.joinmastodon.android.model.FriendCardAttachment;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.model.albums.AlbumModels.AlbumUpdate;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.List;

import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.utils.V;

/** Native rendering of real, warm-cache card hierarchies. No downloader, feed or network is involved. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CoverCardCacheRenderTest{
	private enum Kind{ ALBUM, PUBLICATION, LINK }
	private static final File OUTPUT=new File("/tmp/album-card-cache-renders");

	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication();
		V.setApplicationContext(MastodonApp.context);
	}

	@Test public void warmAlbumRendersLightAndDarkWithAspectFit() throws Exception{ renderThemes(Kind.ALBUM); }
	@Test public void warmPublicationRendersLightAndDarkWithFocusAndOverlayTitle() throws Exception{ renderThemes(Kind.PUBLICATION); }
	@Test public void warmLinkRendersLightAndDarkInItsOriginalCompactRow() throws Exception{ renderThemes(Kind.LINK); }

	private void renderThemes(Kind kind) throws Exception{
		assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
		for(boolean dark:new boolean[]{false, true}){
			try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class)){
				Activity activity=lifecycle.get(); activity.setTheme(dark ? R.style.Theme_Mastodon_Dark : R.style.Theme_Mastodon_Light); lifecycle.setup();
				ImageCache cache=ImageCache.getInstance(activity); cache.clearTopLevel();
				Bitmap pattern=pattern();
				BitmapDrawable image=new BitmapDrawable(activity.getResources(), pattern);
				try{
					Status status=post(kind);
					StatusDisplayItem model=kind==Kind.ALBUM
							? new AlbumUpdateStatusDisplayItem(status.id, new StatusDisplayItem.NoOpCallbacks(activity), activity, status, "render-offline")
							: new FriendCardStatusDisplayItem(status.id, new StatusDisplayItem.NoOpCallbacks(activity), activity, status, "render-offline", false);
					model.fullWidth=true;
					StatusDisplayItem.Holder<StatusDisplayItem> holder=holder(kind, activity);
					cache.put(model.getImageRequest(0), image);
					holder.bind(model); // Must restore synchronously, without a wrapper or manual setImage.
					ImageView cover=findCover(kind, holder.itemView);
					assertSame(image, cover.getDrawable()); assertEquals(View.VISIBLE, cover.getVisibility());
					View root=holder.itemView;
					root.measure(View.MeasureSpec.makeMeasureSpec(V.dp(393), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
					root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
					assertTrue(root.getWidth()>0 && root.getHeight()>0); assertTrue(cover.getWidth()>0 && cover.getHeight()>0);
					assertFalse(cover.getDrawable().getBounds().isEmpty());
					if(kind==Kind.ALBUM) assertEquals(ImageView.ScaleType.FIT_CENTER, cover.getScaleType());
					else assertEquals(ImageView.ScaleType.MATRIX, cover.getScaleType());
					Bitmap rendered=Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
					try{
						Canvas canvas=new Canvas(rendered); canvas.drawColor(dark ? 0xFF18141B : 0xFFFFF7FA); root.draw(canvas);
						// Exercise actual raster output, not only holder.setImage or non-zero layout.
						// Searching for two source stripe colors is robust to LINK rotation and focus crops.
						int teal=0, gold=0;
						for(int y=0; y<rendered.getHeight(); y++) for(int x=0; x<rendered.getWidth(); x++){
							int pixel=rendered.getPixel(x, y);
							if(pixel==0xFF185B7B) teal++;
							if(pixel==0xFFDFB44B) gold++;
						}
						assertTrue("The patterned warm bitmap must reach the rendered cover", teal>5 && gold>5);
						File file=new File(OUTPUT, kind.name().toLowerCase(java.util.Locale.ROOT)+"-warm-"+(dark ? "dark" : "light")+".png");
						try(FileOutputStream stream=new FileOutputStream(file)){ assertTrue(rendered.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
						assertTrue(file.length()>0);
					}finally{ rendered.recycle(); }
				}finally{ cache.clearTopLevel(); pattern.recycle(); }
			}
		}
	}

	@SuppressWarnings("unchecked") private static StatusDisplayItem.Holder<StatusDisplayItem> holder(Kind kind, Activity activity){
		return (StatusDisplayItem.Holder<StatusDisplayItem>)(StatusDisplayItem.Holder<?>)(kind==Kind.ALBUM
				? new AlbumUpdateStatusDisplayItem.Holder(activity, new FrameLayout(activity)) : new FriendCardStatusDisplayItem.Holder(activity, new FrameLayout(activity)));
	}
	private static ImageView findCover(Kind kind, View root){
		FrameLayout wrapper=(FrameLayout)root;
		if(kind==Kind.LINK){
			LinearLayout row=(LinearLayout)wrapper.getChildAt(1);
			return (ImageView)((FrameLayout)((FrameLayout)row.getChildAt(0)).getChildAt(0)).getChildAt(0);
		}
		LinearLayout card=(LinearLayout)wrapper.getChildAt(0);
		return kind==Kind.ALBUM ? (ImageView)card.getChildAt(0) : (ImageView)((ViewGroup)card.getChildAt(0)).getChildAt(0);
	}
	private static Status post(Kind kind){
		Status status=Status.ofFake("p_cache_render", "", Instant.parse("2026-10-27T00:00:00Z")); status.visibility=StatusPrivacy.PUBLIC;
		String cover="https://offline.example.test/render-only/"+kind.name().toLowerCase(java.util.Locale.ROOT);
		if(kind==Kind.ALBUM){
			AlbumUpdate album=new AlbumUpdate(); album.albumId="render-album"; album.albumName="成长足迹 · 内存封面"; album.photoCount=6;
			album.width=880; album.height=400; album.coverUrl=cover; status.albumUpdate=album;
		}else{
			FriendCard card=new FriendCard(); card.schemaVersion=1; card.id="render-card"; card.title="一起分享平凡生活里的小确幸"; card.state="active";
			card.coverUrl=cover; card.coverFocusX=.5f; card.coverFocusY=.4f; card.gender="随和"; card.region="杭州";
			card.interests=List.of("阅读", "音乐", "旅行"); card.lookingFor="寻找聊得来的朋友"; card.publicFields=List.of("gender", "region", "interests", "looking_for");
			FriendCardAttachment attachment=new FriendCardAttachment(); attachment.schemaVersion=1; attachment.id=card.id; attachment.available=true;
			attachment.mode=kind==Kind.LINK ? "link" : "publication"; attachment.card=card; attachment.title=card.title;
			FriendCardAttachment.LinkPreview preview=new FriendCardAttachment.LinkPreview(); preview.coverUrl=cover; preview.ownerName="小鹿"; preview.coverFocusX=.5f; preview.coverFocusY=.4f;
			attachment.linkPreview=preview; status.friendCard=attachment;
		}
		return status;
	}
	private static Bitmap pattern(){
		Bitmap bitmap=Bitmap.createBitmap(880, 400, Bitmap.Config.ARGB_8888); Canvas canvas=new Canvas(bitmap); Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
		int[] colors={0xFF185B7B, 0xFFDFB44B, 0xFFB75365, 0xFF447A57};
		for(int x=0; x<880; x+=40){ paint.setColor(colors[(x/40)%colors.length]); canvas.drawRect(x, 0, x+40, 400, paint); }
		paint.setColor(0xFFE9F2EE); canvas.drawCircle(440, 190, 88, paint); paint.setColor(0xFF263F43); paint.setTextSize(32);
		paint.setTextAlign(Paint.Align.CENTER); canvas.drawText("WARM CACHE", 440, 201, paint);
		paint.setColor(Color.WHITE); paint.setStrokeWidth(3); canvas.drawLine(0, 300, 880, 300, paint);
		return bitmap;
	}
}
