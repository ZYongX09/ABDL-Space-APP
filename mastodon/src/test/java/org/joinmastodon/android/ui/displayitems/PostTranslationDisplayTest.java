package org.joinmastodon.android.ui.displayitems;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.report.ReportAddPostsChoiceFragment;
import org.joinmastodon.android.fragments.report.ReportReasonChoiceFragment;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.Filter;
import org.joinmastodon.android.model.FilterResult;
import org.joinmastodon.android.model.Poll;
import org.joinmastodon.android.model.Quote;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.Translation;
import org.joinmastodon.android.translation.PostTranslationCoordinator;
import org.joinmastodon.android.translation.PostTranslationSupport;
import org.joinmastodon.android.model.TranslationPreferences;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="en-w393dp-h852dp-mdpi",
		shadows={PostTranslationDisplayTest.NoOutbound.class, PostTranslationDisplayTest.MenuAccount.class})
public class PostTranslationDisplayTest{
	@Implements(MastodonAPIRequest.class) public static class NoOutbound{
		@RealObject MastodonAPIRequest<?> request;
		static int calls;
		@Implementation public MastodonAPIRequest<?> exec(String account){ calls++; return request; }
	}
	@Implements(AccountSessionManager.class) public static class MenuAccount{
		@Implementation public boolean isSelf(String accountID, Account account){ return false; }
	}
	private static final String ACCOUNT="translation-display.offline_42";
	/** Robolectric may access Activity resources while attaching, so configure the base first. */
	public static class RenderActivity extends Activity{
		static boolean dark;
		static float fontScale=1f;
		@Override protected void attachBaseContext(Context base){
			Configuration configuration=new Configuration(base.getResources().getConfiguration());
			configuration.fontScale=fontScale;
			configuration.uiMode=(configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
					| (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
			super.attachBaseContext(base.createConfigurationContext(configuration));
		}
	}
	private static final class Actions extends StatusDisplayItem.NoOpCallbacks{
		Status selected; String parent; int clicks;
		Actions(Context context){ super(context); }
		@Override public boolean supportsPostTranslation(){ return true; }
		@Override public void togglePostTranslation(Status status, String parent){ selected=status; this.parent=parent; clicks++; }
	}
	@Before public void setup(){ MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context); GlobalUserPreferences.showCWs=true; NoOutbound.calls=0; }
	private Status post(String id){
		Status result=Status.ofFake(id, "<p>原文正文</p>", Instant.parse("2026-11-01T00:00:00Z")); result.language="zh";
		Account owner=new Account(); owner.id="99"; owner.username=owner.acct="owner"; owner.displayName="Owner"; owner.emojis=List.of();
		owner.avatar=owner.avatarStatic="android.resource://"+MastodonApp.context.getPackageName()+"/"+R.drawable.default_avatar;
		result.account=owner; return result;
	}
	private List<StatusDisplayItem> build(Status status, StatusDisplayItem.Callbacks callbacks, int flags){ return StatusDisplayItem.buildItems(callbacks, MastodonApp.context, status, ACCOUNT, status, Map.of(), flags); }
	private Translation translated(){ Translation result=new Translation(); result.content="<p>Translated body</p>"; result.spoilerText="Translated CW"; result.detectedSourceLanguage="zh"; result.provider="ABDL Space AI"; result.sourceHash="server-source-1"; result.targetLanguage="en"; return result; }
	private void ready(Status status){ PostTranslationSupport.apply(status, ACCOUNT, "en", PostTranslationSupport.sourceVersion(status), translated()); }
	private Field field(Class<?> type, String name) throws Exception{ Field result=type.getDeclaredField(name); result.setAccessible(true); return result; }
	private void measure(View view){ view.measure(View.MeasureSpec.makeMeasureSpec(V.dp(393), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight()); }

	@Test public void builderPlacesModuleAboveBodyKeepsFooterAndHasNoRequestSideEffects(){
		Status post=post("100"); Actions callbacks=new Actions(MastodonApp.context); List<StatusDisplayItem> items=build(post, callbacks, 0);
		assertEquals(List.of(StatusDisplayItem.Type.HEADER, StatusDisplayItem.Type.TRANSLATION, StatusDisplayItem.Type.TEXT, StatusDisplayItem.Type.FOOTER), items.stream().map(StatusDisplayItem::getType).toList());
		assertSame(post, ((TranslationStatusDisplayItem)items.get(1)).contentStatus); assertSame(post, ((FooterStatusDisplayItem)items.get(3)).status); assertEquals(0, NoOutbound.calls);
		build(post, callbacks, 0); assertEquals(0, NoOutbound.calls); assertEquals(Status.TranslationState.HIDDEN, post.translationState);
	}
	@Test public void collapsedCwAndFilterKeepTranslationModuleInHiddenContentChain(){
		Status post=post("101"); post.spoilerText="Warning"; post.revealedSpoilers.clear(); List<StatusDisplayItem> items=build(post, new Actions(MastodonApp.context), 0);
		assertEquals(List.of(StatusDisplayItem.Type.HEADER, StatusDisplayItem.Type.SPOILER, StatusDisplayItem.Type.FOOTER), items.stream().map(StatusDisplayItem::getType).toList());
		SpoilerStatusDisplayItem cw=(SpoilerStatusDisplayItem)items.get(1); assertEquals(StatusDisplayItem.Type.TRANSLATION, cw.contentItems.get(0).getType());
		Filter filter=new Filter(); filter.title="Hidden filter"; FilterResult match=new FilterResult(); match.filter=filter; post.filtered=List.of(match);
		List<StatusDisplayItem> filtered=build(post, new Actions(MastodonApp.context), 0); SpoilerStatusDisplayItem outer=(SpoilerStatusDisplayItem)filtered.get(1);
		assertEquals(StatusDisplayItem.Type.FILTER_SPOILER, outer.getType()); assertEquals(StatusDisplayItem.Type.SPOILER, outer.contentItems.get(0).getType());
		assertEquals(StatusDisplayItem.Type.TRANSLATION, ((SpoilerStatusDisplayItem)outer.contentItems.get(0)).contentItems.get(0).getType()); assertEquals(0, NoOutbound.calls);
	}
	@Test public void quoteAndReblogModulesCarryRealContentIdentityNotSharedParent(){
		Status parent=post("parent"), quote=post("real-quote"); parent.quote=new Quote(); parent.quote.state=Quote.State.ACCEPTED; parent.quote.quotedStatus=quote;
		List<StatusDisplayItem> items=build(parent, new Actions(MastodonApp.context), 0);
		List<TranslationStatusDisplayItem> modules=items.stream().filter(item->item instanceof TranslationStatusDisplayItem).map(item->(TranslationStatusDisplayItem)item).toList();
		assertEquals(2, modules.size()); assertSame(parent, modules.get(0).contentStatus); assertSame(quote, modules.get(1).contentStatus); assertEquals("parent", modules.get(1).parentID); assertTrue(modules.get(1).isQuote);
		Status reblog=post("reblog-envelope"); reblog.reblog=quote; TranslationStatusDisplayItem boosted=build(reblog, new Actions(MastodonApp.context), 0).stream().filter(item->item instanceof TranslationStatusDisplayItem).map(item->(TranslationStatusDisplayItem)item).findFirst().orElseThrow();
		assertSame(quote, boosted.contentStatus); assertSame(reblog, boosted.visibilityStatus); assertEquals("reblog-envelope", boosted.parentID); assertEquals(0, NoOutbound.calls);
	}
	@Test public void previewsReportsNoOpAndNoTranslateNeverStartAutomaticWork(){
		Status post=post("102"); List<StatusDisplayItem> noop=build(post, null, 0); assertTrue(noop.stream().anyMatch(item->item instanceof TranslationStatusDisplayItem));
		assertTrue(build(post, new Actions(MastodonApp.context), StatusDisplayItem.FLAG_NO_TRANSLATE).stream().noneMatch(item->item instanceof TranslationStatusDisplayItem));
		post.preview=true; assertTrue(build(post, new Actions(MastodonApp.context), 0).stream().noneMatch(item->item instanceof TranslationStatusDisplayItem)); post.preview=false;
		assertFalse(new ReportReasonChoiceFragment().supportsPostTranslation()); assertFalse(new ReportAddPostsChoiceFragment().supportsPostTranslation());
		assertTrue(build(post, new ReportReasonChoiceFragment(), 0).stream().noneMatch(item->item instanceof TranslationStatusDisplayItem)); assertEquals(0, NoOutbound.calls);
	}
	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
	public void nativeModuleHasTwelveSpSixteenDpGrayIconAndFortyEightDpActionInBothThemes() throws Exception{
		Field theme=field(GlobalUserPreferences.class, "theme"); Object previous=theme.get(null);
		try{
			for(boolean dark:new boolean[]{false, true}) try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class)){
				Activity activity=lifecycle.get(); activity.setTheme(dark ? R.style.Theme_Mastodon_Dark : R.style.Theme_Mastodon_Light); lifecycle.setup(); theme.set(null, dark ? GlobalUserPreferences.ThemePreference.DARK : GlobalUserPreferences.ThemePreference.LIGHT);
				Status post=post("103"); Actions actions=new Actions(activity); TranslationStatusDisplayItem item=new TranslationStatusDisplayItem(post.id, actions, activity, post, post, ACCOUNT); TranslationStatusDisplayItem.Holder holder=new TranslationStatusDisplayItem.Holder(activity, new FrameLayout(activity)); holder.bind(item); measure(holder.itemView);
				TextView action=holder.itemView.findViewById(R.id.post_translation_action), label=holder.itemView.findViewById(R.id.post_translation_label); ImageView icon=holder.itemView.findViewById(R.id.post_translation_icon);
				assertEquals("Translate post", action.getText().toString()); assertEquals(12*activity.getResources().getDisplayMetrics().scaledDensity, action.getTextSize(), .01f); assertTrue(action.getHeight()>=V.dp(48)); assertTrue(action.getWidth()>=V.dp(48)); assertEquals(V.dp(16), icon.getWidth()); assertEquals(V.dp(16), icon.getHeight());
				int blue=activity.getColor(dark ? R.color.post_translation_action_blue_dark : R.color.post_translation_action_blue), gray=activity.getColor(dark ? R.color.post_translation_label_gray_dark : R.color.post_translation_label_gray);
				assertEquals(blue, action.getCurrentTextColor()); assertEquals(gray, icon.getImageTintList().getDefaultColor()); assertEquals(View.GONE, label.getVisibility());
				assertTrue(action.performClick()); assertSame(post, actions.selected); ready(post); holder.bind(item); measure(holder.itemView);
				assertEquals("Show original", action.getText().toString()); assertTrue(label.getText().toString().contains("Chinese")); assertEquals(gray, label.getCurrentTextColor()); assertEquals(View.VISIBLE, label.getVisibility()); assertTrue(action.getHeight()>=V.dp(48)); assertNotNull(icon.getDrawable());
			}
		}finally{ theme.set(null, previous); }
	}
	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
	public void captureAttachedNativeModuleAndBodyAcrossStatesThemesAndNarrowLargeText() throws Exception{
		Field theme=field(GlobalUserPreferences.class,"theme"); Object previous=theme.get(null);
		try{
			for(boolean dark:new boolean[]{false,true}) for(boolean narrow:new boolean[]{false,true}){
				int width=narrow ? 320 : 393; float scale=narrow ? 1.6f : 1f;
				RenderActivity.dark=dark; RenderActivity.fontScale=scale;
				try(ActivityController<RenderActivity> lifecycle=Robolectric.buildActivity(RenderActivity.class)){
					Activity activity=lifecycle.get(); activity.setTheme(dark ? R.style.Theme_Mastodon_Dark : R.style.Theme_Mastodon_Light); lifecycle.setup();
					theme.set(null,dark ? GlobalUserPreferences.ThemePreference.DARK : GlobalUserPreferences.ThemePreference.LIGHT);
					assertEquals(scale,activity.getResources().getConfiguration().fontScale,.001f);
					assertEquals(dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO,
							activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
					Status post=post("native-capture"); LinearLayout root=new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL);
					root.setPadding(0,V.dp(16),0,V.dp(16)); root.setBackgroundColor(UiUtils.getThemeColor(activity,R.attr.colorM3Surface)); activity.setContentView(root);
					class OfflineRenderTransport implements PostTranslationCoordinator.Transport{
						Callback<Translation> pending; int posts;
						@Override public PostTranslationCoordinator.Cancelable translate(PostTranslationCoordinator.Key key,boolean automatic,Callback<Translation> callback){ posts++; pending=callback; assertFalse(automatic); return ()->{}; }
						@Override public PostTranslationCoordinator.Cancelable query(PostTranslationCoordinator.Key key,Callback<Translation> callback){ throw new AssertionError("Fresh render must not query"); }
						@Override public PostTranslationCoordinator.Cancelable preferences(Callback<TranslationPreferences> callback){ throw new AssertionError("Rendering never loads preferences"); }
						@Override public PostTranslationCoordinator.Cancelable savePreferences(String language,boolean automatic,String revision,Callback<TranslationPreferences> callback){ throw new AssertionError("Rendering never writes preferences"); }
					}
					OfflineRenderTransport transport=new OfflineRenderTransport(); Handler main=new Handler(Looper.getMainLooper());
					PostTranslationCoordinator coordinator=new PostTranslationCoordinator(ACCOUNT,new PostTranslationCoordinator.Environment(){
						@Override public boolean current(){ return !activity.isDestroyed(); }
						@Override public String target(){ return "en"; }
						@Override public boolean sponsorActive(){ return false; }
						@Override public long now(){ return SystemClock.elapsedRealtime(); }
					},transport,(action,delay)->{ main.postDelayed(action,delay); return ()->main.removeCallbacks(action); });
					try{
						PostTranslationCoordinator.Visit visit=new PostTranslationCoordinator.Visit(); Runnable[] rebind={()->{}};
						PostTranslationCoordinator.Listener listener=new PostTranslationCoordinator.Listener(){
							@Override public boolean current(PostTranslationCoordinator.Key key){ return key.resourceID().equals(post.id) && key.sourceVersion().equals(PostTranslationSupport.sourceVersion(post)); }
							@Override public boolean visible(){ return root.isAttachedToWindow(); }
							@Override public void changed(){ rebind[0].run(); }
							@Override public void error(ErrorResponse error){ throw new AssertionError("Offline render failed: "+error); }
						};
						StatusDisplayItem.Callbacks callbacks=new StatusDisplayItem.NoOpCallbacks(activity){
							@Override public boolean supportsPostTranslation(){ return true; }
							@Override public void togglePostTranslation(Status status,String parent){ coordinator.toggle(status,visit,listener); }
						};
						List<StatusDisplayItem> items=StatusDisplayItem.buildItems(callbacks,activity,post,ACCOUNT,post,Map.of(),StatusDisplayItem.FLAG_NO_HEADER | StatusDisplayItem.FLAG_NO_FOOTER);
						assertEquals(List.of(StatusDisplayItem.Type.TRANSLATION,StatusDisplayItem.Type.TEXT),items.stream().map(StatusDisplayItem::getType).toList());
						TranslationStatusDisplayItem.Holder module=new TranslationStatusDisplayItem.Holder(activity,root); module.bind((TranslationStatusDisplayItem)items.get(0));
						TextStatusDisplayItem.Holder body=new TextStatusDisplayItem.Holder(activity,root); body.bind((TextStatusDisplayItem)items.get(1));
						root.addView(module.itemView,new LinearLayout.LayoutParams(-1,-2)); root.addView(body.itemView,new LinearLayout.LayoutParams(-1,-2));
						rebind[0]=()->{ module.rebind(); body.rebind(); };
						String prefix=(dark ? "dark" : "light")+"-"+width+(narrow ? "-font160" : "-font100");
						TextView action=module.itemView.findViewById(R.id.post_translation_action), text=body.itemView.findViewById(R.id.text);
						assertEquals(Status.TranslationState.HIDDEN,post.translationState); assertTrue(text.getText().toString().contains("原文正文")); captureNative(root,module,body,width,prefix+"-pre");
						assertTrue(action.performClick()); assertEquals(Status.TranslationState.LOADING,post.translationState); assertEquals("Translating…",action.getText().toString()); assertTrue(text.getText().toString().contains("原文正文")); captureNative(root,module,body,width,prefix+"-loading");
						transport.pending.onSuccess(translated()); ShadowLooper.idleMainLooper(); assertEquals(Status.TranslationState.SHOWN,post.translationState); assertTrue(text.getText().toString().contains("Translated body")); assertEquals("Show original",action.getText().toString()); captureNative(root,module,body,width,prefix+"-ready");
						assertTrue(body.itemView.findViewById(R.id.translation_show_original).performClick()); assertEquals(Status.TranslationState.HIDDEN,post.translationState); assertTrue(text.getText().toString().contains("原文正文")); assertEquals("Translate post",action.getText().toString()); captureNative(root,module,body,width,prefix+"-show-original");
						assertEquals("All four captures use one real coordinator request",1,transport.posts); assertEquals(0,NoOutbound.calls);
					}finally{ coordinator.dispose(); }
				}
			}
		}finally{ theme.set(null,previous); RenderActivity.dark=false; RenderActivity.fontScale=1f; }
	}
	private void captureNative(LinearLayout root,TranslationStatusDisplayItem.Holder module,TextStatusDisplayItem.Holder body,int width,String name) throws Exception{
		root.measure(View.MeasureSpec.makeMeasureSpec(V.dp(width),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)); root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
		assertTrue("Capture the real attached view hierarchy",root.isAttachedToWindow()); assertTrue(module.itemView.isAttachedToWindow()); assertTrue(body.itemView.isAttachedToWindow());
		TextView action=module.itemView.findViewById(R.id.post_translation_action), text=body.itemView.findViewById(R.id.text), label=module.itemView.findViewById(R.id.post_translation_label);
		ImageView icon=module.itemView.findViewById(R.id.post_translation_icon);
		assertTrue(action.getHeight()>=V.dp(48)); assertTrue(action.getWidth()>=V.dp(48)); assertTrue(text.getHeight()>0);
		assertEquals(12*root.getResources().getDisplayMetrics().scaledDensity,action.getTextSize(),.01f);
		assertEquals(V.dp(16),icon.getWidth()); assertEquals(V.dp(16),icon.getHeight());
		int blue=root.getContext().getColor(RenderActivity.dark ? R.color.post_translation_action_blue_dark : R.color.post_translation_action_blue); assertEquals(blue,action.getCurrentTextColor());
		android.graphics.Rect bounds=new android.graphics.Rect(0,0,action.getWidth(),action.getHeight()); root.offsetDescendantRectToMyCoords(action,bounds); assertTrue(bounds.left>=0 && bounds.right<=root.getWidth());
		if(name.endsWith("-ready")){
			assertEquals("Show original",action.getText().toString()); assertEquals(View.VISIBLE,label.getVisibility()); assertEquals("Translated from Chinese",label.getText().toString());
			assertEquals(12*root.getResources().getDisplayMetrics().scaledDensity,label.getTextSize(),.01f);
			assertEquals(View.VISIBLE,body.itemView.findViewById(R.id.translation_show_original).getVisibility());
			android.text.Layout labelLayout=label.getLayout(); assertNotNull(labelLayout); int lastLine=labelLayout.getLineCount()-1; assertTrue(lastLine>=0);
			for(int line=0;line<=lastLine;line++) assertEquals("Source language must never be ellipsized: "+name,0,labelLayout.getEllipsisCount(line));
			assertEquals("The rendered last line must contain the complete source language",label.length(),labelLayout.getLineVisibleEnd(lastLine));
			float lastGlyphStart=labelLayout.getPrimaryHorizontal(label.length()-1), lastGlyphEnd=labelLayout.getPrimaryHorizontal(label.length());
			assertTrue("The last source-language glyph must fit horizontally: "+name,Math.min(lastGlyphStart,lastGlyphEnd)>=0 && Math.max(lastGlyphStart,lastGlyphEnd)<=labelLayout.getWidth());
			assertTrue("The last source-language glyph must fit vertically: "+name,labelLayout.getLineBottom(lastLine)<=label.getHeight()-label.getCompoundPaddingTop()-label.getCompoundPaddingBottom());
			android.graphics.Rect labelBounds=new android.graphics.Rect(0,0,label.getWidth(),label.getHeight()); root.offsetDescendantRectToMyCoords(label,labelBounds);
			android.graphics.Rect moduleBounds=new android.graphics.Rect(0,0,module.itemView.getWidth(),module.itemView.getHeight()); root.offsetDescendantRectToMyCoords(module.itemView,moduleBounds);
			assertTrue("All label lines must be inside the actual module",moduleBounds.contains(labelBounds)); assertTrue(labelBounds.right<=bounds.left);
			if(width==320){ assertEquals(1.6f,root.getResources().getConfiguration().fontScale,.001f); assertTrue("Narrow large-text ready state must wrap rather than drop Chinese",labelLayout.getLineCount()>=3); }
		}else{
			assertEquals(View.GONE,label.getVisibility()); assertEquals(name.endsWith("-loading") ? "Translating…" : "Translate post",action.getText().toString());
		}
		Bitmap rendered=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
		try{
			root.draw(new Canvas(rendered)); boolean ink=false; int surface=rendered.getPixel(0,0);
			for(int y=0;y<rendered.getHeight() && !ink;y++) for(int x=0;x<rendered.getWidth();x++) if(rendered.getPixel(x,y)!=surface){ ink=true; break; }
			assertTrue("Native capture must contain actual holder text and icon",ink);
			File directory=new File(System.getProperty("postTranslationRenderDir",new File(System.getProperty("java.io.tmpdir"),"abdl-post-translation-renders").getAbsolutePath())); assertTrue(directory.isDirectory() || directory.mkdirs());
			File target=new File(directory,name+".png"); try(FileOutputStream output=new FileOutputStream(target)){ assertTrue(rendered.compress(Bitmap.CompressFormat.PNG,100,output)); } assertTrue(target.length()>0);
		}finally{ rendered.recycle(); }
	}

	@Test public void existingMenuAndTextFooterRetainOriginalToggleAndMatchNewModule() throws Exception{
		try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class)){
			Activity activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.setup(); Status post=post("104"); Actions callbacks=new Actions(activity);
			HeaderStatusDisplayItem.Holder header=new HeaderStatusDisplayItem.Holder(activity, new FrameLayout(activity)); header.bind(new HeaderStatusDisplayItem(post.id, post.account, post.createdAt, callbacks, activity, ACCOUNT, post, null));
			Method update=HeaderStatusDisplayItem.Holder.class.getDeclaredMethod("updateOptionsMenu"); update.setAccessible(true); update.invoke(header);
			android.widget.PopupMenu menu=(android.widget.PopupMenu)field(HeaderStatusDisplayItem.Holder.class, "optionsMenu").get(header); assertTrue(menu.getMenu().findItem(R.id.translate).isVisible()); assertEquals("Translate post", menu.getMenu().findItem(R.id.translate).getTitle().toString());
			ready(post); update.invoke(header); assertEquals("Show original", menu.getMenu().findItem(R.id.translate).getTitle().toString());
			TextStatusDisplayItem textItem=(TextStatusDisplayItem)build(post, callbacks, 0).stream().filter(item->item instanceof TextStatusDisplayItem).findFirst().orElseThrow(); TextStatusDisplayItem.Holder text=new TextStatusDisplayItem.Holder(activity, new FrameLayout(activity)); text.bind(textItem);
			assertTrue(((TextView)text.itemView.findViewById(R.id.text)).getText().toString().contains("Translated body")); assertEquals(View.VISIBLE, text.itemView.findViewById(R.id.translation_show_original).getVisibility());
			assertTrue(text.itemView.findViewById(R.id.translation_show_original).performClick()); assertSame(post, callbacks.selected); assertEquals("104", callbacks.parent);
			post.translationState=Status.TranslationState.HIDDEN; text.rebind(); update.invoke(header); assertEquals("Translate post", menu.getMenu().findItem(R.id.translate).getTitle().toString()); assertTrue(((TextView)text.itemView.findViewById(R.id.text)).getText().toString().contains("原文正文")); assertEquals(0, NoOutbound.calls);
		}
	}
	@Test public void pollAndAltMissingEntriesFallbackAndNeverMutateOriginalAttachment(){
		Status post=post("105"); ready(post); Attachment original=new Attachment(); original.id="media-1"; original.description="Original ALT";
		assertSame(original, PostTranslationSupport.displayedAttachment(post, original));
		Translation.MediaAttachment attachment=new Translation.MediaAttachment(); attachment.id=original.id; attachment.description="Translated ALT"; post.translation.mediaAttachments=new Translation.MediaAttachment[]{null, attachment};
		Attachment display=PostTranslationSupport.displayedAttachment(post, original); assertNotSame(original, display); assertEquals("Translated ALT", display.description); assertEquals("Original ALT", original.description);
		attachment.description=null; assertSame(original, PostTranslationSupport.displayedAttachment(post, original));
		assertEquals("Original option", post.translation.pollOptionTitle("poll-1", 0, "Original option")); post.translation.poll=new Translation.PollTranslation(); post.translation.poll.id="poll-1"; post.translation.poll.options=new Translation.PollOption[]{null};
		assertEquals("Original option", post.translation.pollOptionTitle("poll-1", 0, "Original option")); assertEquals("Original option", post.translation.pollOptionTitle("poll-1", 1, "Original option"));
		Translation.PollOption option=new Translation.PollOption(); option.title="Translated option"; post.translation.poll.options[0]=option; assertEquals("Original option", post.translation.pollOptionTitle("wrong-poll", 0, "Original option")); assertEquals("Translated option", post.translation.pollOptionTitle("poll-1", 0, "Original option"));
		post.translationState=Status.TranslationState.HIDDEN; assertSame(original, PostTranslationSupport.displayedAttachment(post, original));
	}
}
