package org.joinmastodon.android.ui.displayitems;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.FriendCardAttachment;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.utils.UiUtils;
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
import java.util.Map;

import me.grishka.appkit.utils.V;

/** Offline native Canvas renders of the real body + inserted LINK holders, never a remote image. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="w393dp-h852dp-mdpi", application=CompatibilityTestApplication.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FriendCardLinkRenderTest{
	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication();
		V.setApplicationContext(MastodonApp.context);
		assertEquals("Corner raster assertions require physical MDPI pixels",1f,MastodonApp.context.getResources().getDisplayMetrics().density,.001f);
	}

	@Test public void renderLightAndDarkCoverFallbackUnavailableAndLongDeclarationWithUserBody() throws Exception{
		for(boolean dark:new boolean[]{false,true}){
			try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class)){
				Activity activity=lifecycle.get();
				activity.setTheme(dark ? R.style.Theme_Mastodon_Dark : R.style.Theme_Mastodon_Light);
				lifecycle.setup();
				for(String state:List.of("cover","fallback","unavailable","long-text")){
					Status status=post(state);
					List<StatusDisplayItem> items=StatusDisplayItem.buildItems(null,activity,status,"offline",status,Map.of(),StatusDisplayItem.FLAG_NO_HEADER | StatusDisplayItem.FLAG_NO_FOOTER);
					assertEquals(List.of(StatusDisplayItem.Type.TEXT,StatusDisplayItem.Type.FRIEND_CARD),items.stream().map(StatusDisplayItem::getType).toList());
					LinearLayout root=new LinearLayout(activity);
					root.setOrientation(LinearLayout.VERTICAL);
					root.setPadding(0,V.dp(16),0,V.dp(16));
					root.setBackgroundColor(UiUtils.getThemeColor(activity,R.attr.colorM3Surface));
					TextStatusDisplayItem.Holder body=new TextStatusDisplayItem.Holder(activity,root);
					body.bind((TextStatusDisplayItem)items.get(0));
					root.addView(body.itemView,new LinearLayout.LayoutParams(-1,-2));
					FriendCardStatusDisplayItem.Holder holder=new FriendCardStatusDisplayItem.Holder(activity,root);
					holder.bind((FriendCardStatusDisplayItem)items.get(1));
					root.addView(holder.itemView,new LinearLayout.LayoutParams(-1,-2));
					boolean hasCover=state.equals("cover") || state.equals("long-text");
					if(hasCover) holder.setImage(0,new BitmapDrawable(activity.getResources(),syntheticCover()));
					else if(state.equals("unavailable")) holder.setImage(0,new BitmapDrawable(activity.getResources(),syntheticCover())); // Late delivery must stay rejected.
					root.measure(View.MeasureSpec.makeMeasureSpec(V.dp(393),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
					root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());

					TextView userBody=body.itemView.findViewById(R.id.text);
					assertTrue(userBody.getText().toString().contains("今天也想认识温柔有趣的新朋友"));
					assertFalse(userBody.getText().toString().contains("旧客户端扩列卡链接"));
					assertTrue(userBody.getHeight()>0);
					LinearLayout row=(LinearLayout)((FrameLayout)holder.itemView).getChildAt(1);
					FrameLayout thumbnail=(FrameLayout)row.getChildAt(0);
					FrameLayout face=(FrameLayout)thumbnail.getChildAt(0);
					ImageView cover=(ImageView)face.getChildAt(0), icon=(ImageView)face.getChildAt(1);
					TextView declaration=(TextView)((LinearLayout)row.getChildAt(1)).getChildAt(1);
					assertEquals(View.VISIBLE,row.getVisibility());
					assertEquals(54,thumbnail.getWidth()); assertEquals(75,thumbnail.getHeight());
					assertTrue("Keep the compact row within the 2dp height allowance",row.getHeight()>=96 && row.getHeight()<=98);
					assertEquals(-4f,thumbnail.getRotation(),.01f);
					assertEquals(row.getHeight()/2f,thumbnail.getTop()+thumbnail.getHeight()/2f,.5f);
					assertFalse(row.getClipChildren()); assertFalse(row.getClipToPadding());
					assertTrue(thumbnail.getClipToOutline()); assertTrue(face.getClipToOutline());
					assertEquals(hasCover ? View.VISIBLE : View.GONE,cover.getVisibility());
					assertEquals(hasCover ? View.GONE : View.VISIBLE,icon.getVisibility());
					assertNotNull(icon.getDrawable());
					assertTrue(declaration.getHeight()>0);
					assertTrue(declaration.getRight()<=row.getWidth());
					if(state.equals("long-text")){
						assertEquals(2,declaration.getLineCount());
						assertTrue(declaration.getLayout().getEllipsisCount(1)>0);
					}
					if(state.equals("unavailable")){
						assertFalse(row.isClickable()); assertNull(cover.getDrawable());
						assertEquals(activity.getString(R.string.friend_card_post_unavailable),declaration.getText().toString());
					}

					Bitmap rendered=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
					root.draw(new Canvas(rendered));
					assertHasInk(rendered);
					File directory=new File(System.getProperty("friendCardLinkRenderDir","/tmp/abdl-friend-card-ui-renders/inserted-links"));
					assertTrue(directory.isDirectory() || directory.mkdirs());
					try(FileOutputStream output=new FileOutputStream(new File(directory,(dark ? "dark-" : "light-")+state+".png"))){
						assertTrue(rendered.compress(Bitmap.CompressFormat.PNG,100,output));
					}
					// Preserve the actual failing capture too, rather than inspecting a stale PNG.
					assertPortraitCorners(rendered,root,row,thumbnail,(dark ? "dark-" : "light-")+state);
					rendered.recycle();
				}
			}
		}
	}

	/** Compare every rounded corner with an unclipped, rotated portrait on the same surface.
	 * Full renders used to pass assertHasInk even with the entire left/top white edge missing. */
	private static void assertPortraitCorners(Bitmap rendered,LinearLayout root,LinearLayout row,FrameLayout thumbnail,String state){
		Matrix toRoot=new Matrix(thumbnail.getMatrix());
		float x=0,y=0;
		for(View view=thumbnail; view!=root; view=(View)view.getParent()){
			x+=view.getLeft(); y+=view.getTop();
		}
		toRoot.postTranslate(x,y);
		RectF bounds=new RectF(0,0,thumbnail.getWidth(),thumbnail.getHeight());
		toRoot.mapRect(bounds); bounds.inset(-1,-1);
		Rect rowRect=new Rect(0,0,row.getWidth(),row.getHeight());
		root.offsetDescendantRectToMyCoords(row,rowRect);
		assertTrue(state+" rotated bounds and AA gutter must stay inside the padded row",new RectF(rowRect.left+row.getPaddingLeft(),rowRect.top+row.getPaddingTop(),rowRect.right-row.getPaddingRight(),rowRect.bottom-row.getPaddingBottom()).contains(bounds));

		Bitmap expected=Bitmap.createBitmap(rendered.getWidth(),rendered.getHeight(),Bitmap.Config.ARGB_8888);
		Canvas referenceCanvas=new Canvas(expected);
		referenceCanvas.drawColor(UiUtils.getThemeColor(root.getContext(),R.attr.colorM3SurfaceVariant));
		// Draw only the portrait's background to make an independent rounded white outline
		// oracle. No parent or face clip, icon silhouette, or cover color can define it.
		referenceCanvas.save(); referenceCanvas.concat(toRoot); thumbnail.getBackground().draw(referenceCanvas); referenceCanvas.restore();
		Matrix fromRoot=new Matrix(); assertTrue(toRoot.invert(fromRoot));
		int[] borderPixels=new int[4], antialiasPixels=new int[4], overflowPixels=new int[4];
		int matchedBorder=0,matchedAntialias=0;
		for(int py=(int)Math.floor(bounds.top); py<(int)Math.ceil(bounds.bottom); py++){
			for(int px=(int)Math.floor(bounds.left); px<(int)Math.ceil(bounds.right); px++){
				float[] local={px+.5f,py+.5f}; fromRoot.mapPoints(local);
				float localX=local[0],localY=local[1];
				// The white ring is 2px wide, not 1px. At -4deg the outermost pixel
				// strip alone has too few fully covered samples on a dark surface.
				// Exclude the actual inner face rect; its legitimate crop is unchanged.
				if(!((localX<10 || localX>thumbnail.getWidth()-10) && (localY<10 || localY>thumbnail.getHeight()-10))) continue;
				if(localX>2 && localX<thumbnail.getWidth()-2 && localY>2 && localY<thumbnail.getHeight()-2) continue;
				float centerX=localX<thumbnail.getWidth()/2f ? 8 : thumbnail.getWidth()-8;
				float centerY=localY<thumbnail.getHeight()/2f ? 8 : thumbnail.getHeight()-8;
				float radius=(float)Math.hypot(localX-centerX,localY-centerY);
				if(radius<=7 || radius>9) continue;
				int corner=(localX<thumbnail.getWidth()/2f ? 0 : 1)+(localY<thumbnail.getHeight()/2f ? 0 : 2);
				int reference=expected.getPixel(px,py),actual=rendered.getPixel(px,py);
				boolean solid=isWhite(reference), antialiased=false;
				if(!solid && radius>=7.8f){
					int surface=UiUtils.getThemeColor(root.getContext(),R.attr.colorM3SurfaceVariant);
					antialiased=colorDistance(reference,surface)>=3 && colorDistance(reference,Color.WHITE)>=3;
				}
				if(!solid && !antialiased) continue;
				assertTrue(state+" corner "+corner+" clipped at native pixel "+px+","+py+" expected "+Integer.toHexString(reference)+" actual "+Integer.toHexString(actual),colorDistance(reference,actual)<=4);
				if(solid){ borderPixels[corner]++; matchedBorder++; }
				else{ antialiasPixels[corner]++; matchedAntialias++; }
				if(px+.5f<x || px+.5f>x+thumbnail.getWidth() || py+.5f<y || py+.5f>y+thumbnail.getHeight()) overflowPixels[corner]++;
			}
		}
		for(int corner=0;corner<4;corner++){
			assertTrue(state+" corner "+corner+" must retain its white border",borderPixels[corner]>=3);
			assertTrue(state+" corner "+corner+" must retain antialiased outer pixels",antialiasPixels[corner]>=2);
		}
		assertTrue(state+" must inspect actual rotated overflow, including the formerly clipped upper-left corner",overflowPixels[0]>0);
		assertTrue(state+" cannot pass with an empty corner oracle",matchedBorder>=12 && matchedAntialias>=8);
		expected.recycle();
	}

	private static boolean isWhite(int color){ return Color.alpha(color)==255 && Color.red(color)>=250 && Color.green(color)>=250 && Color.blue(color)>=250; }
	private static int colorDistance(int a,int b){
		return Math.max(Math.max(Math.abs(Color.red(a)-Color.red(b)),Math.abs(Color.green(a)-Color.green(b))),Math.max(Math.abs(Color.blue(a)-Color.blue(b)),Math.abs(Color.alpha(a)-Color.alpha(b))));
	}

	private static Status post(String state){
		String suffix="<p>旧客户端扩列卡链接</p>";
		Status status=Status.ofFake("p_101","<p>今天也想认识温柔有趣的新朋友，分享日常、一起散步。</p>"+suffix,Instant.parse("2026-10-18T12:30:00Z"));
		Account account=new Account(); account.id="42"; account.username=account.acct="owner";
		account.displayName="小鹿"; account.emojis=List.of(); status.account=account;
		FriendCardAttachment attachment=new FriendCardAttachment();
		attachment.schemaVersion=1; attachment.id="card-1"; attachment.mode="link";
		attachment.available=!state.equals("unavailable");
		attachment.title=state.equals("long-text") ? "想遇见能够一起聊天、读书、打游戏和分享日常的新朋友，慢慢熟悉，也尊重彼此的边界。".repeat(5) : "在平凡日常里，遇见合拍的你";
		attachment.fallbackSuffix=suffix;
		if(!state.equals("fallback")){
			attachment.linkPreview=new FriendCardAttachment.LinkPreview();
			attachment.linkPreview.coverUrl="https://offline.example.test/synthetic-public-owned-cover";
			attachment.linkPreview.coverFocusX=.65f; attachment.linkPreview.coverFocusY=.35f;
			attachment.linkPreview.ownerId="42"; attachment.linkPreview.ownerName="小鹿";
		}
		status.friendCard=attachment; return status;
	}

	/** Abstract local cover only: no private profile snapshot, fixture files, network or image loader. */
	private static Bitmap syntheticCover(){
		Bitmap bitmap=Bitmap.createBitmap(160,240,Bitmap.Config.ARGB_8888);
		Canvas canvas=new Canvas(bitmap); Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
		paint.setShader(new LinearGradient(0,0,160,240,new int[]{0xFFEEC5A6,0xFFBD929E,0xFF596B77},null,Shader.TileMode.CLAMP));
		canvas.drawRect(0,0,160,240,paint); paint.setShader(null);
		paint.setColor(0xFFFFE5BC); canvas.drawCircle(112,56,29,paint);
		paint.setColor(0x885F7C70); canvas.drawCircle(23,187,83,paint);
		paint.setColor(0xAA3F6269); canvas.drawCircle(142,237,100,paint);
		paint.setColor(0xCCFFFFFF); canvas.drawRoundRect(18,132,118,138,3,3,paint);
		paint.setColor(0x88FFFFFF); canvas.drawRoundRect(18,148,86,152,2,2,paint);
		return bitmap;
	}

	private static void assertHasInk(Bitmap bitmap){
		int background=bitmap.getPixel(0,0), changed=0;
		for(int y=0;y<bitmap.getHeight();y+=3) for(int x=0;x<bitmap.getWidth();x+=3)
			if(bitmap.getPixel(x,y)!=background && Color.alpha(bitmap.getPixel(x,y))>0) changed++;
		assertTrue("Native Canvas must draw actual card and body pixels",changed>100);
	}
}
