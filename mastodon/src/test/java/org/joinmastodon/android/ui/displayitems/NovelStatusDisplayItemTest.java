package org.joinmastodon.android.ui.displayitems;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.res.ResourcesCompat;
import com.google.gson.JsonParser;
import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.NovelAttachmentWireTest;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.Filter;
import org.joinmastodon.android.model.FilterResult;
import org.joinmastodon.android.model.NovelAttachment;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.viewcontrollers.ComposeNovelControllerTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.utils.V;
import okio.Buffer;

/** Real native View tree, real clicks, real display-item builder and real bitmaps; no source-string checks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="zh-rCN-w393dp-h852dp-mdpi",application=CompatibilityTestApplication.class)
public class NovelStatusDisplayItemTest{
	@Before public void setup(){MastodonApp.context=RuntimeEnvironment.getApplication();V.setApplicationContext(MastodonApp.context);}
	private Status post(){
		Status s=Status.ofFake("p-1","<p>My text &amp; spaces </p>\n<p>"+NovelStatusDisplayItem.FALLBACK+"</p>",Instant.parse("2026-10-28T00:00:00Z"));
		Account a=new Account();a.id="42";a.username=a.acct="owner";a.displayName="Owner";a.emojis=List.of();s.account=a;
		s.novel=MastodonAPIController.gson.fromJson("{\"novel\":"+NovelAttachmentWireTest.NOVEL+"}",Status.class).novel;return s;
	}
	private List<StatusDisplayItem> items(Status s,int flags){return StatusDisplayItem.buildItems(null,MastodonApp.context,s,"offline",s,Map.of(),flags);}
	private NovelStatusDisplayItem item(Activity activity,Status s,boolean hidden){return new NovelStatusDisplayItem(s.id,new StatusDisplayItem.NoOpCallbacks(activity),activity,s,"offline",hidden);}
	@Test public void allModesKeepUserBodyHeaderFooterAndMedia(){
		for(String mode:List.of("new_book","chapter_update","link")){
			Status s=post();s.novel.mode=mode;
			Attachment audio=new Attachment();audio.id="a";audio.type=Attachment.Type.AUDIO;audio.url="https://offline.invalid/audio";audio.previewUrl="https://offline.invalid/audio-cover";s.mediaAttachments=List.of(audio);
			assertEquals(List.of(StatusDisplayItem.Type.HEADER,StatusDisplayItem.Type.TEXT,StatusDisplayItem.Type.NOVEL,StatusDisplayItem.Type.AUDIO,StatusDisplayItem.Type.FOOTER),items(s,0).stream().map(StatusDisplayItem::getType).toList());
			assertEquals("<p>My text &amp; spaces </p>\n",NovelStatusDisplayItem.contentWithoutFallback(s.content,s.novel));
		}
	}
	@Test public void exactSuffixRemovalIsAvailableOnlyAndNeverTrimsUserData(){
		NovelAttachment n=post().novel;String suffix="<p>"+NovelStatusDisplayItem.FALLBACK+"</p>";String user="<p> body &amp; tail </p>\n  ";
		assertEquals(user,NovelStatusDisplayItem.contentWithoutFallback(user+suffix,n));
		assertEquals(user+suffix,NovelStatusDisplayItem.contentWithoutFallback(user+suffix+suffix,n));
		assertEquals(user+suffix+" ",NovelStatusDisplayItem.contentWithoutFallback(user+suffix+" ",n));
		assertEquals(user+suffix+"<p>end</p>",NovelStatusDisplayItem.contentWithoutFallback(user+suffix+"<p>end</p>",n));
		assertEquals(user+suffix.replace("</p>","。</p>"),NovelStatusDisplayItem.contentWithoutFallback(user+suffix.replace("</p>","。</p>"),n));
		assertEquals("original\n ",NovelStatusDisplayItem.contentWithoutFallback("original\n \n\n"+NovelStatusDisplayItem.FALLBACK,n));
		n.available=false;assertEquals(user+suffix,NovelStatusDisplayItem.contentWithoutFallback(user+suffix,n));n.schemaVersion=9;assertEquals(user+suffix,NovelStatusDisplayItem.contentWithoutFallback(user+suffix,n));
		assertEquals(user+suffix,NovelStatusDisplayItem.contentWithoutFallback(user+suffix,null));
	}
	@Test public void malformedMetadataFallsBackToBodyUnavailableAddsOnlyNeutralRow(){
		Status s=post();s.novel.author=null;
		assertEquals(List.of(StatusDisplayItem.Type.TEXT),items(s,StatusDisplayItem.FLAG_NO_HEADER|StatusDisplayItem.FLAG_NO_FOOTER).stream().map(StatusDisplayItem::getType).toList());
		s.novel.available=false;assertEquals(List.of(StatusDisplayItem.Type.TEXT,StatusDisplayItem.Type.NOVEL),items(s,StatusDisplayItem.FLAG_NO_HEADER|StatusDisplayItem.FLAG_NO_FOOTER).stream().map(StatusDisplayItem::getType).toList());
	}
	@Test public void cwAndFilterKeepNovelUnderTheExistingConcealmentChain(){
		Status s=post();s.spoilerText="Warning";s.revealedSpoilers.clear();Filter f=new Filter();f.title="Filtered";FilterResult result=new FilterResult();result.filter=f;s.filtered=List.of(result);
		List<StatusDisplayItem> built=items(s,0);assertEquals(3,built.size());
		SpoilerStatusDisplayItem filter=(SpoilerStatusDisplayItem)built.get(1);assertEquals(StatusDisplayItem.Type.FILTER_SPOILER,filter.getType());
		SpoilerStatusDisplayItem cw=(SpoilerStatusDisplayItem)filter.contentItems.get(0);assertEquals(StatusDisplayItem.Type.SPOILER,cw.getType());
		assertEquals(List.of(StatusDisplayItem.Type.TEXT,StatusDisplayItem.Type.NOVEL),cw.contentItems.stream().map(StatusDisplayItem::getType).toList());
		assertEquals(0,cw.contentItems.get(1).getImageCount());
	}
	@Test public void actualViewsShowModeSpecificDetailsAndRouteDetailAndReaderSeparately(){
		try(ActivityController<Activity> life=Robolectric.buildActivity(Activity.class)){
			Activity a=life.get();a.setTheme(R.style.Theme_Mastodon_Light);life.setup();Status s=post();NovelStatusDisplayItem.Holder h=new NovelStatusDisplayItem.Holder(a,new FrameLayout(a));h.bind(item(a,s,false));
			assertNotNull(findText(h.itemView,"章节更新"));assertNotNull(findText(h.itemView,"第12章 — 第13章（2 章）"));assertNotNull(findText(h.itemView,"本次新增 3460 字"));assertNotNull(findText(h.itemView,"章节摘录"));
			findText(h.itemView,"柔软日常").performClick();Intent detail=((org.robolectric.shadows.ShadowActivity) org.robolectric.shadow.api.Shadow.extract(a)).getNextStartedActivity();assertEquals("org.joinmastodon.android.novel.app.NovelAppActivity",detail.getComponent().getClassName());assertEquals("work-1",detail.getStringExtra("work_id"));assertFalse(detail.hasExtra("chapter_id"));
			findText(h.itemView,"阅读新章节").performClick();Intent read=((org.robolectric.shadows.ShadowActivity) org.robolectric.shadow.api.Shadow.extract(a)).getNextStartedActivity();assertEquals("org.joinmastodon.android.novel.app.editor.NovelReaderActivity",read.getComponent().getClassName());assertEquals("chapter-12",read.getStringExtra("chapter_id"));assertEquals("offline",read.getStringExtra("account"));
			s.novel.mode="link";h.bind(item(a,s,false));assertNull(findText(h.itemView,"章节更新"));assertNull(findDescription(h.itemView,"加入书架"));assertNotNull(findText(h.itemView,"我的小说"));findText(h.itemView,"阅读作品").performClick();Intent resume=((org.robolectric.shadows.ShadowActivity) org.robolectric.shadow.api.Shadow.extract(a)).getNextStartedActivity();assertFalse(resume.hasExtra("chapter_id"));
			s.novel.mode="new_book";h.bind(item(a,s,false));assertNotNull(findText(h.itemView,"新书上架"));assertNotNull(findText(h.itemView,"A description"));assertNotNull(findDescription(h.itemView,"加入书架"));
		}
	}
	@Test public void protectedRowsRequestNoImagesAndClearLateOrRecycledBitmaps(){
		try(ActivityController<Activity> life=Robolectric.buildActivity(Activity.class)){
			Activity a=life.get();a.setTheme(R.style.Theme_Mastodon_Light);life.setup();Status s=post();NovelStatusDisplayItem.Holder h=new NovelStatusDisplayItem.Holder(a,new FrameLayout(a));
			NovelStatusDisplayItem normal=item(a,s,false);assertEquals(1,normal.getImageCount());h.bind(normal);
			BitmapDrawable image=new BitmapDrawable(a.getResources(),Bitmap.createBitmap(160,220,Bitmap.Config.ARGB_8888));h.setImage(0,image);assertTrue(images(h.itemView).stream().anyMatch(v->v.getDrawable()==image));
			s.sensitive=true;NovelStatusDisplayItem hidden=item(a,s,false);assertEquals(0,hidden.getImageCount());h.bind(hidden);h.setImage(0,image);assertFalse(images(h.itemView).stream().anyMatch(v->v.getDrawable()==image));
			s.sensitive=false;s.spoilerText="CW";s.revealedSpoilers.clear();assertEquals(0,item(a,s,false).getImageCount());s.revealedSpoilers.add(Status.SpoilerType.CONTENT_WARNING);assertEquals(1,item(a,s,false).getImageCount());
			assertEquals(0,item(a,s,true).getImageCount());s.visibility=StatusPrivacy.PRIVATE;assertEquals(0,item(a,s,false).getImageCount());s.visibility=StatusPrivacy.DIRECT;assertEquals(0,item(a,s,false).getImageCount());
			s.visibility=StatusPrivacy.PUBLIC;s.novel.available=false;h.bind(item(a,s,false));h.setImage(0,image);assertFalse(images(h.itemView).stream().anyMatch(v->v.getDrawable()==image));assertNotNull(findText(h.itemView,"作品暂不可用"));assertNull(findText(h.itemView,"柔软日常"));assertNull(findText(h.itemView,"阅读新章节"));
		}
	}
	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
	@Config(qualifiers="zh-rCN-w393dp-h852dp-mdpi")
	public void nativeCanvasRendersThreeRealCardModesInBothThemesAndAt320dp() throws Exception{
		for(boolean dark:new boolean[]{false,true})for(int width:new int[]{393,320}){
			try(ActivityController<Activity> life=Robolectric.buildActivity(Activity.class)){
				Activity a=life.get();a.setTheme(dark ? R.style.Theme_Mastodon_Dark : R.style.Theme_Mastodon_Light);life.setup();
				ImageCache cache=ImageCache.getInstance(a);cache.clearTopLevel();
				try{
					for(String mode:List.of("new_book","chapter_update","link")){
						Status s=renderPost(mode);LinearLayout root=renderRoot(a);
						List<StatusDisplayItem> built=StatusDisplayItem.buildItems(null,a,s,"offline",s,Map.of(),StatusDisplayItem.FLAG_NO_HEADER|StatusDisplayItem.FLAG_NO_FOOTER);
						assertEquals(mode.equals("link") ? List.of(StatusDisplayItem.Type.TEXT,StatusDisplayItem.Type.NOVEL) : List.of(StatusDisplayItem.Type.NOVEL),built.stream().map(StatusDisplayItem::getType).toList());
						if(mode.equals("link")){
							TextStatusDisplayItem.Holder body=new TextStatusDisplayItem.Holder(a,root);body.bind((TextStatusDisplayItem)built.get(0));root.addView(body.itemView);
							assertTrue(((TextView)body.itemView.findViewById(R.id.text)).getText().toString().contains("今天的故事，想和你一起读"));
							assertFalse(((TextView)body.itemView.findViewById(R.id.text)).getText().toString().contains(NovelStatusDisplayItem.FALLBACK));
						}
						NovelStatusDisplayItem model=(NovelStatusDisplayItem)built.get(built.size()-1);
						NovelStatusDisplayItem.Holder holder=new NovelStatusDisplayItem.Holder(a,root);holder.bind(model);root.addView(holder.itemView);
						TextView heading=findText(root,s.novel.title);assertNotNull(heading);
						assertEquals(ResourcesCompat.getFont(a,R.font.noto_serif_sc_semibold),heading.getTypeface());
						assertEquals(UiUtils.getThemeColor(a,R.attr.colorM3OnSurface),heading.getCurrentTextColor());
						String name=(dark ? "dark-" : "light-")+width+"dp-"+mode;
						capture(root,width,"cards/"+name+"-fallback");
						assertTrue("Fallback is a real metadata book jacket",images(root).stream().anyMatch(v->v.getDrawable() instanceof org.joinmastodon.android.ui.drawables.NovelCoverDrawable));
						if(!mode.equals("link")){View shelf=findDescription(root,a.getString(R.string.novel_card_shelf_add));assertTrue(shelf instanceof android.widget.ImageButton);assertEquals(V.dp(48),shelf.getWidth());assertEquals(shelf.getWidth(),shelf.getHeight());}
						if(mode.equals("chapter_update"))assertNull("Excerpt is unboxed body copy",findText(root,s.novel.excerpt).getBackground());
						BitmapDrawable image=(BitmapDrawable)a.getDrawable(R.drawable.splash_art_layer0);assertNotNull(image);
						holder.setImage(0,image);assertTrue(images(holder.itemView).stream().anyMatch(v->v.getVisibility()==View.VISIBLE && v.getDrawable()==image));
						capture(root,width,"cards/"+name+"-cover");
						assertEquals(View.VISIBLE,heading.getVisibility());assertTrue(heading.getWidth()>0);
						assertTrue("Heading stays in its native column",heading.getRight()<=((View)heading.getParent()).getWidth());
						String readLabel=a.getString(mode.equals("new_book") ? R.string.novel_card_read_new : mode.equals("chapter_update") ? R.string.novel_card_read_update : R.string.novel_card_read_link);
						TextView read=findText(root,readLabel);assertNotNull(read);assertTrue(read.getWidth()>0);assertTrue(read.getHeight()>=V.dp(48));
						if(mode.equals("chapter_update"))assertEquals(ResourcesCompat.getFont(a,R.font.noto_serif_sc_regular),findText(root,s.novel.excerpt).getTypeface());
						assertTrue(read.performClick());Intent intent=((org.robolectric.shadows.ShadowActivity) org.robolectric.shadow.api.Shadow.extract(a)).getNextStartedActivity();assertEquals("org.joinmastodon.android.novel.app.editor.NovelReaderActivity",intent.getComponent().getClassName());assertEquals("work-1",intent.getStringExtra("work_id"));
						assertEquals(mode.equals("link") ? null : "chapter-12",intent.getStringExtra("chapter_id"));
						assertTrue(heading.performClick());assertEquals("org.joinmastodon.android.novel.app.NovelAppActivity",((org.robolectric.shadows.ShadowActivity) org.robolectric.shadow.api.Shadow.extract(a)).getNextStartedActivity().getComponent().getClassName());
					}
				}finally{cache.clearTopLevel();}
			}
		}
	}
	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
	@Config(qualifiers="zh-rCN-w320dp-h852dp-mdpi")
	public void nativeCanvasCwAndLateImageNeverExposeConcealedOrWithdrawnCover() throws Exception{
		boolean previous=GlobalUserPreferences.showCWs;GlobalUserPreferences.showCWs=true;
		try{
			for(boolean dark:new boolean[]{false,true})try(ActivityController<Activity> life=Robolectric.buildActivity(Activity.class)){
				Activity a=life.get();a.setTheme(dark ? R.style.Theme_Mastodon_Dark : R.style.Theme_Mastodon_Light);life.setup();
				ImageCache.getInstance(a).clearTopLevel();String name=(dark ? "dark-" : "light-")+"320dp-";
				Status s=renderPost("chapter_update");s.spoilerText="内容提示：本章含情绪描写";s.revealedSpoilers.clear();
				List<StatusDisplayItem> built=StatusDisplayItem.buildItems(null,a,s,"offline",s,Map.of(),StatusDisplayItem.FLAG_NO_HEADER|StatusDisplayItem.FLAG_NO_FOOTER);
				assertEquals(List.of(StatusDisplayItem.Type.SPOILER),built.stream().map(StatusDisplayItem::getType).toList());
				SpoilerStatusDisplayItem cw=(SpoilerStatusDisplayItem)built.get(0);NovelStatusDisplayItem concealed=(NovelStatusDisplayItem)cw.contentItems.get(0);assertEquals(0,concealed.getImageCount());
				LinearLayout root=renderRoot(a);SpoilerStatusDisplayItem.Holder warning=new SpoilerStatusDisplayItem.Holder(a,root,StatusDisplayItem.Type.SPOILER);warning.bind(cw);root.addView(warning.itemView);
				capture(root,320,"concealment/"+name+"cw-collapsed");assertNull(findText(root,s.novel.title));
				NovelStatusDisplayItem.Holder holder=new NovelStatusDisplayItem.Holder(a,root);holder.bind(concealed);
				BitmapDrawable image=(BitmapDrawable)a.getDrawable(R.drawable.splash_art_layer0);holder.setImage(0,image);
				assertFalse(images(holder.itemView).stream().anyMatch(v->v.getDrawable()==image));
				s.revealedSpoilers.add(Status.SpoilerType.CONTENT_WARNING);warning.bind(cw);holder.bind(concealed);assertEquals(1,concealed.getImageCount());holder.setImage(0,image);root.addView(holder.itemView);
				capture(root,320,"concealment/"+name+"cw-revealed");assertNotNull(findText(root,s.novel.title));
				s.revealedSpoilers.clear();holder.bind(concealed);holder.setImage(0,image);root.removeView(holder.itemView);warning.bind(cw);
				capture(root,320,"concealment/"+name+"cw-reclosed-late");assertFalse(images(holder.itemView).stream().anyMatch(v->v.getDrawable()==image));
				s.spoilerText="";s.sensitive=true;root.removeAllViews();root.addView(holder.itemView);holder.bind(item(a,s,false));holder.setImage(0,image);
				capture(root,320,"concealment/"+name+"sensitive-late-rejected");assertFalse(images(holder.itemView).stream().anyMatch(v->v.getDrawable()==image));
				s.sensitive=false;s.novel.available=false;holder.bind(item(a,s,false));holder.setImage(0,image);
				capture(root,320,"concealment/"+name+"unavailable-late-rejected");assertNull(findText(root,s.novel.title));assertNotNull(findText(root,a.getString(R.string.novel_card_unavailable)));assertNull(findText(root,a.getString(R.string.novel_card_read_update)));
				ImageCache.getInstance(a).clearTopLevel();
			}
		}finally{GlobalUserPreferences.showCWs=previous;}
	}
	@Test @Config(shadows=ComposeNovelControllerTest.Requests.class)
	public void realShelfClicksSendIdempotentAddThenRemoveAndDiscardRecycledCallbacks() throws Exception{
		ComposeNovelControllerTest.Requests.sent.clear();
		try(ActivityController<Activity> life=Robolectric.buildActivity(Activity.class)){
			Activity a=life.get();a.setTheme(R.style.Theme_Mastodon_Light);life.setup();Status s=post();
			NovelStatusDisplayItem model=new NovelStatusDisplayItem(s.id,new StatusDisplayItem.NoOpCallbacks(a),a,s,"account-42",false);
			NovelStatusDisplayItem.Holder holder=new NovelStatusDisplayItem.Holder(a,new FrameLayout(a));holder.bind(model);
			assertTrue(findDescription(holder.itemView,a.getString(R.string.novel_card_shelf_add)).performClick());assertEquals(1,ComposeNovelControllerTest.Requests.sent.size());
			MastodonAPIRequest<?> add=ComposeNovelControllerTest.Requests.sent.get(0);assertEquals("/api/v1/novels/app/shelf/work-1",add.getURL().getPath());
			Buffer bytes=new Buffer();add.getRequestBody().writeTo(bytes);assertTrue(JsonParser.parseString(bytes.readUtf8()).getAsJsonObject().get("collected").getAsBoolean());
			shelfSuccess(add,true);View remove=findDescription(holder.itemView,a.getString(R.string.novel_card_shelf_remove));assertNotNull(remove);assertTrue(remove.isEnabled());assertTrue(remove.performClick());
			MastodonAPIRequest<?> request=ComposeNovelControllerTest.Requests.sent.get(1);bytes=new Buffer();request.getRequestBody().writeTo(bytes);assertFalse(JsonParser.parseString(bytes.readUtf8()).getAsJsonObject().get("collected").getAsBoolean());
			holder.bind(model);shelfSuccess(request,false);assertNotNull(findDescription(holder.itemView,a.getString(R.string.novel_card_shelf_add)));assertTrue(findDescription(holder.itemView,a.getString(R.string.novel_card_shelf_add)).isEnabled());
		}finally{ComposeNovelControllerTest.Requests.sent.clear();}
	}
	@SuppressWarnings("unchecked") private static void shelfSuccess(MastodonAPIRequest<?> request,boolean collected) throws Exception{
		Field f=APIRequest.class.getDeclaredField("callback");f.setAccessible(true);((Callback<Object>)f.get(request)).onSuccess(JsonParser.parseString("{\"collected\":"+collected+"}").getAsJsonObject());
	}
	private Status renderPost(String mode){
		Status s=post();s.novel.mode=mode;s.novel.title="柔软日常：在每一个平凡的清晨与你重逢";
		s.novel.description="搬进新家的第一天，窗边的阳光和一杯热牛奶，让我们慢慢学会把日常写成温柔的故事。";
		s.novel.excerpt="雨声渐渐轻了。他把书签夹在刚读完的那页，轻声说：明天的故事，我们一起写。";
		s.content=mode.equals("link") ? "<p>今天的故事，想和你一起读。保留我自己的推荐语 &amp; 空格。</p><p>"+NovelStatusDisplayItem.FALLBACK+"</p>" : "<p>"+NovelStatusDisplayItem.FALLBACK+"</p>";return s;
	}
	private static LinearLayout renderRoot(Activity activity){LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(0,V.dp(16),0,V.dp(16));root.setBackgroundColor(UiUtils.getThemeColor(activity,R.attr.colorM3Surface));return root;}
	private static void capture(View root,int width,String name) throws Exception{
		root.measure(View.MeasureSpec.makeMeasureSpec(V.dp(width),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
		assertEquals(V.dp(width),root.getWidth());assertTrue(root.getHeight()>0);Bitmap rendered=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
		try{
			root.draw(new Canvas(rendered));File target=new File(System.getProperty("novelUiRenderDir","/tmp/abdl-novel-ui-renders"),name+".png");assertTrue(target.getParentFile().isDirectory() || target.getParentFile().mkdirs());
			try(FileOutputStream output=new FileOutputStream(target)){assertTrue(rendered.compress(Bitmap.CompressFormat.PNG,100,output));}assertTrue(target.length()>0);
			int background=rendered.getPixel(0,0),ink=0;for(int y=0;y<rendered.getHeight();y+=2)for(int x=0;x<rendered.getWidth();x+=2)if(rendered.getPixel(x,y)!=background && Color.alpha(rendered.getPixel(x,y))>0)ink++;
			assertTrue("Native Android Views must paint real text/card pixels in "+name,ink>50);
		}finally{rendered.recycle();}
	}
	private static View findDescription(View root,String value){if(root.getVisibility()!=View.VISIBLE)return null;if(value.contentEquals(root.getContentDescription()==null ? "" : root.getContentDescription()))return root;if(root instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++){View found=findDescription(g.getChildAt(i),value);if(found!=null)return found;}return null;}
	private static TextView findText(View root,String value){if(root.getVisibility()!=View.VISIBLE)return null;if(root instanceof TextView text && text.getText().toString().equals(value))return text;if(root instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++){TextView text=findText(g.getChildAt(i),value);if(text!=null)return text;}return null;}
	private static List<ImageView> images(View root){List<ImageView> out=new ArrayList<>();if(root instanceof ImageView i)out.add(i);if(root instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)out.addAll(images(g.getChildAt(i)));return out;}
}
