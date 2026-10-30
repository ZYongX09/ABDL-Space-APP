package org.joinmastodon.android.ui.viewcontrollers;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.novel.app.design.NovelComposeState;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.junit.After;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.utils.V;

/** Only the transport is replaced. All gates, searches, native radio clicks, preview actions and saved state are real. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="zh-rCN-w393dp-h852dp-mdpi",application=CompatibilityTestApplication.class,shadows=ComposeNovelControllerTest.Requests.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ComposeNovelControllerTest{
	@Implements(MastodonAPIRequest.class) public static class Requests{
		@RealObject MastodonAPIRequest<?> request;
		public static final List<MastodonAPIRequest<?>> sent=new ArrayList<>();
		@Implementation public MastodonAPIRequest<?> exec(String account){assertEquals("account-42",account);sent.add(request);return request;}
		@Implementation public MastodonAPIRequest<?> execNoAuth(String domain){throw new AssertionError("No unauthenticated requests");}
		@Implementation public MastodonAPIRequest<?> exec(String domain,Token token){throw new AssertionError("No instance requests");}
	}
	/** Decode/dispatch is real; image transport alone is held so stale delivery is deterministic and offline. */
	@Implements(ViewImageLoader.class) public static class Images{
		static final List<ViewImageLoader.Target> pending=new ArrayList<>();
		@Implementation public static void load(ViewImageLoader.Target target,Drawable placeholder,ImageLoaderRequest request,boolean animate,boolean fromCache){pending.add(target);}
	}
	private ActivityController<Activity> life;
	private Activity activity;
	private LinearLayout root;
	private ImageButton tool;
	private NovelComposeState state;
	private ComposeNovelController controller;
	private final AtomicBoolean friendCard=new AtomicBoolean();
	private final AtomicInteger changes=new AtomicInteger(),language=new AtomicInteger();
	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication();V.setApplicationContext(MastodonApp.context);Requests.sent.clear();ShadowToast.reset();
		life=Robolectric.buildActivity(Activity.class);activity=life.get();activity.setTheme(R.style.Theme_Mastodon_Light);life.setup();
		root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);activity.setContentView(root);tool=new ImageButton(activity);root.addView(tool);
		state=new NovelComposeState();controller=new ComposeNovelController(activity,"account-42","42",state,tool,root,1,friendCard::get,changes::incrementAndGet,language::incrementAndGet);
	}
	@After public void cleanup(){controller.dispose();life.close();Requests.sent.clear();Images.pending.clear();}
	private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
	private static String work(String id,String title,String status,long author){return "{\"id\":\""+id+"\",\"title\":\""+title+"\",\"status\":\""+status+"\",\"release_id\":\"r-1\",\"work_type\":\"original\",\"chapter_count\":13,\"word_count\":32000,\"author\":{\"id\":"+author+",\"username\":\"owner\",\"display_name\":\"棉云\"}}";}
	private static MastodonAPIRequest<?> last(String end){for(int i=Requests.sent.size()-1;i>=0;i--){MastodonAPIRequest<?> r=Requests.sent.get(i);if(r.getURL().getPath().endsWith(end))return r;}throw new AssertionError("Missing request "+end);}
	@SuppressWarnings("unchecked") private static Callback<Object> cb(MastodonAPIRequest<?> r) throws Exception{Field f=APIRequest.class.getDeclaredField("callback");f.setAccessible(true);return (Callback<Object>)f.get(r);}
	private static void success(String end,String data) throws Exception{cb(last(end)).onSuccess(json(data));ShadowLooper.idleMainLooper();}
	private void ready() throws Exception{
		success("/creator","{\"active\":true,\"eligible\":false}");
		success("/owner/works","{\"items\":["+work("w-1","柔软日常","published",42)+","+work("w-2","雨停之前","published",42)+","+work("draft-1","草稿","draft",42)+","+work("other","别人的","published",99)+","+work("hidden","下架","published",42)+"]}");
		success("/works/w-1",work("w-1","柔软日常","published",42));success("/works/w-2",work("w-2","雨停之前","published",42));cb(last("/works/hidden")).onError(new MastodonErrorResponse("Not found",404,null));ShadowLooper.idleMainLooper();
	}
	@Test public void onlyActiveCreatorWithOwnPublishedPublicWorkGetsNovelIcon() throws Exception{
		assertEquals(R.drawable.ic_language_24px,((org.robolectric.shadows.ShadowDrawable) org.robolectric.shadow.api.Shadow.extract(tool.getDrawable())).getCreatedFromResId());tool.performClick();assertEquals(1,language.get());
		success("/creator","{\"active\":false,\"eligible\":true,\"works\":["+work("w","book","published",42)+"]}");
		assertEquals(1,Requests.sent.size());assertEquals(R.drawable.ic_language_24px,((org.robolectric.shadows.ShadowDrawable) org.robolectric.shadow.api.Shadow.extract(tool.getDrawable())).getCreatedFromResId());
	}
	@Test public void nativeSearchSingleSelectionConfirmationPreviewReplaceAndRemove() throws Exception{
		ready();assertEquals(R.drawable.ic_novel_rebuilt,((org.robolectric.shadows.ShadowDrawable) org.robolectric.shadow.api.Shadow.extract(tool.getDrawable())).getCreatedFromResId());tool.performLongClick();assertEquals(1,language.get());
		tool.performClick();ShadowLooper.idleMainLooper();AlertDialog picker=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(picker);assertTrue(picker.isShowing());
		assertNull(findText(picker.getWindow().getDecorView(),"草稿\n本地草稿 · 13 章"));assertFalse(picker.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
		EditText query=find(picker.getWindow().getDecorView(),EditText.class);query.setText("雨停");
		List<RadioButton> radios=findAll(picker.getWindow().getDecorView(),RadioButton.class);assertEquals(1,radios.size());assertTrue(radios.get(0).getText().toString().contains("雨停之前"));radios.get(0).performClick();
		assertNotNull(findText(picker.getWindow().getDecorView(),"已选择 1 部"));picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		assertFalse(state.hasNovel()); // Selecting a row isn't a publishable attachment until revalidation succeeds.
		success("/works/w-2",work("w-2","雨停之前","published",42));assertEquals("w-2",state.selectedId);assertTrue(state.specified);assertEquals(1,changes.get());assertFalse(picker.isShowing());
		assertNotNull(findText(root,"雨停之前"));assertNotNull(findText(root,"更换作品"));assertNotNull(findText(root,"移除作品"));
		findText(root,"更换作品").performClick();ShadowLooper.idleMainLooper();AlertDialog replacement=ShadowAlertDialog.getLatestAlertDialog();List<RadioButton> options=findAll(replacement.getWindow().getDecorView(),RadioButton.class);
		RadioButton first=options.stream().filter(o->o.getText().toString().startsWith("柔软日常")).findFirst().orElseThrow();first.performClick();replacement.getButton(AlertDialog.BUTTON_POSITIVE).performClick();success("/works/w-1",work("w-1","柔软日常","published",42));
		assertEquals("w-1",state.selectedId);assertEquals(2,changes.get());findText(root,"移除作品").performClick();assertFalse(state.hasNovel());assertTrue(state.specified);assertEquals(3,changes.get());
	}
	@Test public void selectedRepostKeepsUploaderIdentityButRendersOriginalAuthorAndCover() throws Exception{
		String repost=work("w-repost","授权转载作品","published",42).replace("\"work_type\":\"original\"","\"work_type\":\"repost\",\"original_author\":\"原作作者\"");
		success("/creator","{\"active\":true}");success("/owner/works","{\"items\":["+repost+"]}");success("/works/w-repost",repost);
		tool.performClick();ShadowLooper.idleMainLooper();AlertDialog picker=ShadowAlertDialog.getLatestAlertDialog();
		ImageView jacket=findAll(picker.getWindow().getDecorView(),ImageView.class).stream().filter(v->v.getDrawable() instanceof org.joinmastodon.android.ui.drawables.NovelCoverDrawable).findFirst().orElseThrow();
		Field byline=org.joinmastodon.android.ui.drawables.NovelCoverDrawable.class.getDeclaredField("author");byline.setAccessible(true);assertEquals("原作作者",byline.get(jacket.getDrawable()).toString());
		findAll(picker.getWindow().getDecorView(),RadioButton.class).get(0).performClick();picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();success("/works/w-repost",repost);
		assertEquals("原作作者",state.preview.getDisplayAuthorName());assertEquals(42,state.preview.author.id);assertEquals("owner",state.preview.author.username);
		assertNotNull(findText(root,"原作作者 著\n连载中 · 13 章 · 32000 字"));
		Bundle saved=new Bundle();ComposeNovelController.save(state,saved);NovelComposeState restored=new NovelComposeState();ComposeNovelController.restore(restored,saved);assertEquals("原作作者",restored.preview.originalAuthor);assertEquals(42,restored.preview.author.id);
	}
	@Test public void confirmationRevalidatesVisibilityAndDoesNotInventSuccessfulAttachment() throws Exception{
		ready();tool.performClick();ShadowLooper.idleMainLooper();AlertDialog picker=ShadowAlertDialog.getLatestAlertDialog();findAll(picker.getWindow().getDecorView(),RadioButton.class).get(0).performClick();picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		String end=last("/works/w-2").getURL().getPath(); // Find the last work request, regardless of owner-list order.
		MastodonAPIRequest<?> request=Requests.sent.get(Requests.sent.size()-1);cb(request).onError(new MastodonErrorResponse("Work unavailable",404,null));ShadowLooper.idleMainLooper();
		assertFalse(state.hasNovel());assertTrue(picker.isShowing());assertTrue(picker.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());assertEquals(0,changes.get());
	}
	@Test public void mixedEntityAndDisposeDiscardCallbacksAndSavedStateRetainsAllThreeStates() throws Exception{
		ready();friendCard.set(true);tool.performClick();assertNull(ShadowAlertDialog.getLatestAlertDialog());assertEquals(activity.getString(R.string.novel_compose_mixed),ShadowToast.getTextOfLatestToast());
		state.initialize("w-old",null);Bundle untouched=new Bundle();ComposeNovelController.save(state,untouched);NovelComposeState restored=new NovelComposeState();ComposeNovelController.restore(restored,untouched);assertEquals("w-old",restored.selectedId);assertFalse(restored.specified);
		state.select("w-new",null);Bundle replacement=new Bundle();ComposeNovelController.save(state,replacement);ComposeNovelController.restore(restored,replacement);assertEquals("w-new",restored.selectedId);assertTrue(restored.specified);
		state.remove();Bundle removal=new Bundle();ComposeNovelController.save(state,removal);ComposeNovelController.restore(restored,removal);assertNull(restored.selectedId);assertTrue(restored.specified);
		controller.dispose();int count=Requests.sent.size();success("/creator","{\"active\":true}");assertEquals(count,Requests.sent.size());
	}
	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
	public void nativeCanvasSelectorSearchSelectReplaceRemoveInLightAndDark() throws Exception{renderSelector(false);renderSelector(true);}
	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="zh-rCN-w320dp-h852dp-mdpi")
	public void nativeCanvasSelectorAt320dpInLightAndDark() throws Exception{renderSelector(false);renderSelector(true);}
	private void renderSelector(boolean dark) throws Exception{
		controller.dispose();life.close();Requests.sent.clear();changes.set(0);language.set(0);friendCard.set(false);
		life=Robolectric.buildActivity(Activity.class);activity=life.get();activity.setTheme(dark ? R.style.Theme_Mastodon_Dark : R.style.Theme_Mastodon_Light);life.setup();
		root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(UiUtils.getThemeColor(activity,R.attr.colorM3Surface));activity.setContentView(root);
		tool=new ImageButton(activity);root.addView(tool);state=new NovelComposeState();controller=new ComposeNovelController(activity,"account-42","42",state,tool,root,1,friendCard::get,changes::incrementAndGet,language::incrementAndGet);
		ready();assertTrue(tool.performClick());ShadowLooper.idleMainLooper();AlertDialog picker=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(picker);assertTrue(picker.isShowing());
		String name=(dark ? "dark-" : "light-")+activity.getResources().getConfiguration().screenWidthDp+"dp-";
			captureDialog(picker,"selector/"+name+"all");
			assertTrue(findAll(picker.getWindow().getDecorView(),ImageView.class).stream().anyMatch(v->v.getDrawable() instanceof org.joinmastodon.android.ui.drawables.NovelCoverDrawable));
			assertTrue(findAll(picker.getWindow().getDecorView(),ImageButton.class).stream().anyMatch(v->activity.getString(R.string.novel_compose_close).equals(v.getContentDescription())));

		List<RadioButton> options=findAll(picker.getWindow().getDecorView(),RadioButton.class);assertEquals(2,options.size());assertTrue(options.stream().allMatch(r->r.getWidth()>0 && r.getHeight()>0));assertFalse(picker.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
		EditText query=find(picker.getWindow().getDecorView(),EditText.class);query.setText("不存在的标题");assertEquals(0,findAll(picker.getWindow().getDecorView(),RadioButton.class).size());assertNotNull(findText(picker.getWindow().getDecorView(),activity.getString(R.string.novel_compose_no_results)));
		captureDialog(picker,"selector/"+name+"no-results");
		query.setText("雨停");RadioButton rain=find(picker.getWindow().getDecorView(),RadioButton.class);assertTrue(rain.performClick());assertTrue(rain.isChecked());assertNotNull(findText(picker.getWindow().getDecorView(),activity.getString(R.string.novel_compose_select_count,1)));
			captureDialog(picker,"selector/"+name+"selected");
			assertNotNull("Selection is shown by the trailing radio icon",rain.getCompoundDrawablesRelative()[2]);
			assertTrue("Filtered result fits without a fixed blank viewport",find(picker.getWindow().getDecorView(),android.widget.ScrollView.class).getHeight()<V.dp(160));
			assertSame("Selected count and the real confirm button share the footer",findText(picker.getWindow().getDecorView(),activity.getString(R.string.novel_compose_select_count,1)).getParent(),picker.getButton(AlertDialog.BUTTON_POSITIVE).getParent());

		assertTrue(picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick());assertFalse(state.hasNovel());success("/works/w-2",work("w-2","雨停之前","published",42));assertTrue(state.hasNovel());assertEquals("w-2",state.selectedId);assertFalse(picker.isShowing());
		capture(root,"selector/"+name+"attached-preview");
		assertTrue(findText(root,"雨停之前").performClick());Intent detail=((org.robolectric.shadows.ShadowActivity) org.robolectric.shadow.api.Shadow.extract(activity)).getNextStartedActivity();assertEquals("org.joinmastodon.android.novel.app.NovelAppActivity",detail.getComponent().getClassName());assertEquals("w-2",detail.getStringExtra("work_id"));assertEquals("account-42",detail.getStringExtra("account"));
		assertTrue(findText(root,activity.getString(R.string.novel_compose_replace)).performClick());ShadowLooper.idleMainLooper();AlertDialog replacement=ShadowAlertDialog.getLatestAlertDialog();RadioButton first=findAll(replacement.getWindow().getDecorView(),RadioButton.class).stream().filter(r->r.getText().toString().startsWith("柔软日常")).findFirst().orElseThrow();assertTrue(first.performClick());
		captureDialog(replacement,"selector/"+name+"replace");
		assertTrue(replacement.getButton(AlertDialog.BUTTON_POSITIVE).performClick());success("/works/w-1",work("w-1","柔软日常","published",42));assertEquals("w-1",state.selectedId);
		capture(root,"selector/"+name+"replaced-preview");assertTrue(findText(root,activity.getString(R.string.novel_compose_remove)).performClick());assertFalse(state.hasNovel());assertTrue(state.specified);assertEquals(3,changes.get());
	}
	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(shadows={Requests.class,Images.class},qualifiers="zh-rCN-w320dp-h852dp-mdpi")
	public void lateImageFromRemovedPreviewCannotPaintTheNextSelectedWork() throws Exception{
		Images.pending.clear();ready();tool.performClick();ShadowLooper.idleMainLooper();AlertDialog first=ShadowAlertDialog.getLatestAlertDialog();findAll(first.getWindow().getDecorView(),RadioButton.class).get(0).performClick();first.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		success("/works/w-1",work("w-1","柔软日常","published",42).replace("\"word_count\":32000","\"word_count\":32000,\"cover_url\":\"https://offline.invalid/novel-cover-a\""));assertEquals(1,Images.pending.size());
		ViewImageLoader.Target old=Images.pending.get(0);Drawable art=activity.getDrawable(R.drawable.splash_art_layer0);old.setImageDrawable(art);ImageView image=(ImageView)old.getView();assertSame(art,image.getDrawable());
		capture(root,"selector/light-320dp-preview-before-remove");findText(root,activity.getString(R.string.novel_compose_remove)).performClick();
		tool.performClick();ShadowLooper.idleMainLooper();AlertDialog next=ShadowAlertDialog.getLatestAlertDialog();findAll(next.getWindow().getDecorView(),RadioButton.class).get(1).performClick();next.getButton(AlertDialog.BUTTON_POSITIVE).performClick();success("/works/w-2",work("w-2","雨停之前","published",42));
		assertEquals("w-2",state.selectedId);Drawable placeholder=image.getDrawable();assertNotSame(art,placeholder);old.setImageDrawable(art);assertSame(placeholder,image.getDrawable());
		capture(root,"selector/light-320dp-preview-late-image-rejected");controller.dispose();old.setImageDrawable(art);assertNull(image.getDrawable());
	}
	private static void captureDialog(AlertDialog dialog,String name) throws Exception{
		// Preserve the dialog window's real inset width; do not stretch its rows to the screen width.
		ShadowLooper.idleMainLooper();View decor=dialog.getWindow().getDecorView();assertTrue(decor.getWidth()>0);assertTrue(decor.getHeight()>0);captureMeasured(decor,name);
	}
	private static void capture(View view,String name) throws Exception{
		ShadowLooper.idleMainLooper();int width=V.dp(view.getResources().getConfiguration().screenWidthDp);
		view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());
		assertEquals(width,view.getWidth());captureMeasured(view,name);
	}
	private static void captureMeasured(View view,String name) throws Exception{
		assertTrue(view.getHeight()>0);Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
		try{
			view.draw(new Canvas(bitmap));File target=new File(System.getProperty("novelUiRenderDir","/tmp/abdl-novel-ui-renders"),name+".png");assertTrue(target.getParentFile().isDirectory() || target.getParentFile().mkdirs());try(FileOutputStream output=new FileOutputStream(target)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}assertTrue(target.length()>0);
			int background=bitmap.getPixel(0,0),ink=0;for(int y=0;y<bitmap.getHeight();y+=2)for(int x=0;x<bitmap.getWidth();x+=2)if(bitmap.getPixel(x,y)!=background && Color.alpha(bitmap.getPixel(x,y))>0)ink++;
			assertTrue("Real native selector/preview must paint pixels in "+name,ink>50);
		}finally{bitmap.recycle();}
	}
	private static TextView findText(View root,String s){for(TextView t:findAll(root,TextView.class))if(t.getVisibility()==View.VISIBLE && t.getText().toString().equals(s))return t;return null;}
	private static <T extends View> T find(View v,Class<T> type){return findAll(v,type).stream().findFirst().orElseThrow();}
	private static <T extends View> List<T> findAll(View v,Class<T> type){List<T> out=new ArrayList<>();if(type.isInstance(v))out.add(type.cast(v));if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)out.addAll(findAll(g.getChildAt(i),type));return out;}
}
