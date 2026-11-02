package org.joinmastodon.android;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.LocaleList;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.joinmastodon.android.fragments.FriendRequestCreateFragment;
import org.joinmastodon.android.sponsors.SponsorUi;
import org.joinmastodon.android.ui.utils.AppLanguageManager;
import org.joinmastodon.android.ui.views.PinView;
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

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.grishka.appkit.utils.V;

/** Attached, measured native views at real 320dp constraints; no fake text bounds or live requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=32, application=Application.class, qualifiers="en-rUS-w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class GeneralEnglishUiTest{
	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication();
		V.setApplicationContext(MastodonApp.context);
	}

	@Test public void nativeAuthAndNbwActionsWrapAt320dpWithLargeText() throws Exception{
		for(float scale:new float[]{1.6f, 1.8f}){
			for(int theme:new int[]{R.style.Theme_Mastodon_Light, R.style.Theme_Mastodon_Dark}){
				for(int layout:new int[]{R.layout.fragment_login_email, R.layout.fragment_login_password,
						R.layout.fragment_login_code, R.layout.fragment_register_info,
						R.layout.activity_nbw_bind_guide, R.layout.activity_nbw_not_bound}){
					try(Page page=new Page(layout, scale, theme)){
						assertEquals("en", AppLanguageManager.getEffectiveLocale(page.context).getLanguage());
						assertTrue("The inflated layout must be attached", page.root.isAttachedToWindow());
						assertNotNull("Long auth/NBW text needs a real scrolling container", findScroll(page.root));
						for(TextView text:textViews(page.root)){
							if(text.getVisibility()!=View.VISIBLE) continue;
							assertFalse("Untranslated UI: "+text.getText(), containsHan(text.getText().toString()));
							if(text instanceof Button && text.getText().length()>0){
								assertTextNotClipped(text);
								assertTrue("Actions must grow rather than use a fixed clipped height", text.getLayoutParams().height==ViewGroup.LayoutParams.WRAP_CONTENT);
							}
						}
						capture(page, "layout-"+page.context.getResources().getResourceEntryName(layout)+"-"+scale+"-"+(theme==R.style.Theme_Mastodon_Dark ? "dark" : "light"));
					}
				}
			}
		}
	}

	@Test public void pinMeasurementRespectsEveryParentSpecAndRetainsNormalSize(){
		Context context=localized(MastodonApp.context, "en-US", 1f, R.style.Theme_Mastodon_Light);
		PinView pin=new PinView(context);
		int naturalWidth=dp(context, 346), naturalHeight=dp(context, 76);
		pin.measure(spec(0, View.MeasureSpec.UNSPECIFIED), spec(0, View.MeasureSpec.UNSPECIFIED));
		assertEquals(naturalWidth, pin.getMeasuredWidth());
		assertEquals(naturalHeight, pin.getMeasuredHeight());
		for(int mode:new int[]{View.MeasureSpec.AT_MOST, View.MeasureSpec.EXACTLY}){
			for(int width:new int[]{280, 320, 400}){
				int pixels=dp(context, width);
				pin.measure(spec(pixels, mode), spec(dp(context, 60), mode));
				assertEquals(mode==View.MeasureSpec.EXACTLY ? pixels : Math.min(pixels, naturalWidth), pin.getMeasuredWidth());
				assertEquals(dp(context, 60), pin.getMeasuredHeight());
				pin.layout(0, 0, pin.getMeasuredWidth(), pin.getMeasuredHeight());
				assertSixPinBoxesAndDigitsInsideViewport(pin);
			}
		}
	}

	@Test public void pinActualLoginLayoutAndGutteredParentsDrawAllSixDigitsAtLargeFonts() throws Exception{
		for(float scale:new float[]{1.6f, 1.8f}){
			for(int theme:new int[]{R.style.Theme_Mastodon_Light, R.style.Theme_Mastodon_Dark}){
				try(Page page=new Page(R.layout.fragment_login_code, scale, theme)){
					PinView pin=page.root.findViewById(R.id.pin_view);
					ViewGroup parent=(ViewGroup)pin.getParent();
					assertEquals(dp(page.context, 320), page.root.getWidth());
					assertPinWithinParent(pin, parent);
					assertSixPinBoxesAndDigitsInsideViewport(pin);
					capture(page, "pin-login-320-"+scale+"-"+(theme==R.style.Theme_Mastodon_Dark ? "dark" : "light"));
					// The same production view must fit actual remaining width after 20dp gutters,
					// and a smaller viewport with asymmetric view padding. Never invent child bounds.
					for(int gutter:new int[]{20, 32}){
						FrameLayout host=new FrameLayout(page.context); host.setPadding(dp(page.context, gutter), 0, dp(page.context, gutter), 0);
						PinView constrained=new PinView(page.context);
						constrained.setPadding(dp(page.context, 8), dp(page.context, 6), dp(page.context, 12), dp(page.context, 10));
						host.addView(constrained, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
						page.lifecycle.get().setContentView(host); measure(host, dp(page.context, 320), dp(page.context, 200));
						assertTrue(constrained.isAttachedToWindow());
						assertEquals(dp(page.context, 320-2*gutter), constrained.getMeasuredWidth());
						assertPinWithinParent(constrained, host);
						assertSixPinBoxesAndDigitsInsideViewport(constrained);
					}
				}
			}
		}
	}

	@Test public void pinReturnsToItsOriginalRasterAfterNarrowParentIsRemoved(){
		Context context=localized(MastodonApp.context, "en-US", 1f, R.style.Theme_Mastodon_Light);
		PinView pin=new PinView(context); pin.setBackground(null); pin.setPadding(0, 0, 0, 0);
		pin.setText("789012");
		pin.measure(spec(0, View.MeasureSpec.UNSPECIFIED), spec(0, View.MeasureSpec.UNSPECIFIED));
		pin.layout(0, 0, pin.getMeasuredWidth(), pin.getMeasuredHeight());
		Bitmap original=pinBitmap(pin);
		try{
			pin.measure(spec(dp(context, 256), View.MeasureSpec.AT_MOST), spec(dp(context, 60), View.MeasureSpec.EXACTLY));
			pin.layout(0, 0, pin.getMeasuredWidth(), pin.getMeasuredHeight());
			Bitmap narrow=pinBitmap(pin); narrow.recycle();
			pin.measure(spec(0, View.MeasureSpec.UNSPECIFIED), spec(0, View.MeasureSpec.UNSPECIFIED));
			pin.layout(0, 0, pin.getMeasuredWidth(), pin.getMeasuredHeight());
			Bitmap restored=pinBitmap(pin);
			try{
				assertEquals(original.getWidth(), restored.getWidth()); assertEquals(original.getHeight(), restored.getHeight());
				int[] before=new int[original.getWidth()*original.getHeight()], after=new int[before.length];
				original.getPixels(before, 0, original.getWidth(), 0, 0, original.getWidth(), original.getHeight());
				restored.getPixels(after, 0, restored.getWidth(), 0, 0, restored.getWidth(), restored.getHeight());
				assertArrayEquals("Resizing must not accumulate smaller boxes or text", before, after);
			}finally{ restored.recycle(); }
		}finally{ original.recycle(); }
	}

	@Test public void pinLengthCompletionAndClearSemanticsAreUnchanged(){
		PinView pin=new PinView(localized(MastodonApp.context, "en-US", 1.8f, R.style.Theme_Mastodon_Light));
		List<String> completed=new ArrayList<>(); pin.setOnPinCompleteListener(completed::add);
		pin.setText("12345"); assertTrue(completed.isEmpty());
		pin.append("6"); assertEquals(List.of("123456"), completed);
		pin.append("7"); assertEquals("123456", pin.getText().toString());
		assertEquals(android.view.inputmethod.EditorInfo.IME_ACTION_DONE, pin.getImeOptions());
		assertEquals(android.text.InputType.TYPE_CLASS_NUMBER, pin.getInputType() & android.text.InputType.TYPE_MASK_CLASS);
		pin.clearPin(); assertEquals("", pin.getText().toString());
	}

	@Test public void longSponsorAndMapExplanationsUseMeasuredUnellipsizedText(){
		for(float scale:new float[]{1.6f, 1.8f}){
			try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup()){
				Context context=localized(controller.get(), "en-US", scale, R.style.Theme_Mastodon_Light);
				ScrollView scroll=new ScrollView(context);
				android.widget.LinearLayout column=SponsorUi.column(context);
				scroll.addView(column);
				for(int resource:new int[]{R.string.map_presence_privacy_body, R.string.map_presence_visibility_required,
						R.string.sponsor_ui_external, R.string.album_upload_design_quality_help,
						R.string.verification_certificate_note_4}){
					TextView text=SponsorUi.text(column, resource==R.string.sponsor_ui_external
							? context.getString(resource, "https://official.example.test") : context.getString(resource), false);
					text.setTag(resource);
				}
				controller.get().setContentView(scroll); measure(scroll, 320, 640);
				assertTrue(column.getHeight()>scroll.getHeight());
				for(TextView text:textViews(column)) assertTextNotClipped(text);
			}
		}
	}

	@Test public void englishAndChineseResourcesSwitchWithoutChangingMachineValues(){
		Context english=localized(MastodonApp.context, "en-US", 1f, R.style.Theme_Mastodon_Light);
		Context chinese=localized(MastodonApp.context, "zh-CN", 1f, R.style.Theme_Mastodon_Light);
		assertEquals("Sponsor center", english.getString(R.string.sponsor_ui_center));
		assertEquals("赞助者中心", chinese.getString(R.string.sponsor_ui_center));
		assertEquals("Friend map", english.getString(R.string.map_ui_city_loading));
		assertEquals("交友地图", chinese.getString(R.string.map_ui_city_loading));
		assertEquals("Forum / category", english.getString(R.string.nbw_v2_classification_title));
		assertEquals("板块 / 分类", chinese.getString(R.string.nbw_v2_classification_title));
		assertArrayEquals(english.getResources().getStringArray(R.array.album_report_reason_values), chinese.getResources().getStringArray(R.array.album_report_reason_values));
		assertEquals("Caregiver", FriendRequestCreateFragment.localizedLookingFor(english, "找家长"));
		assertEquals("找家长", FriendRequestCreateFragment.localizedLookingFor(chinese, "找家长"));
		assertEquals("Age", FriendRequestCreateFragment.localizedField(english, "年龄"));
		assertEquals("My custom field", FriendRequestCreateFragment.localizedField(english, "My custom field"));
		assertEquals("用户自定义字段", FriendRequestCreateFragment.localizedField(english, "用户自定义字段"));
	}

	@Test public void placeholdersPluralAndMarkupRemainFunctional(){
		Context english=localized(MastodonApp.context, "en-US", 1f, R.style.Theme_Mastodon_Light);
		Context chinese=localized(MastodonApp.context, "zh-CN", 1f, R.style.Theme_Mastodon_Light);
		for(Context context:new Context[]{english, chinese}){
			String quota=context.getString(R.string.verification_quota, 3, 1, 2);
			assertTrue(quota.contains("3")); assertTrue(quota.contains("1")); assertTrue(quota.contains("2"));
			assertFalse(context.getString(R.string.general_network_error, "offline").contains("%1$s"));
			String consent=context.getString(R.string.login_agreement_text);
			assertTrue(consent.contains("https://abdl-space.top/terms"));
			assertTrue(consent.contains("https://abdl-space.top/privacy"));
			assertTrue(android.text.Html.fromHtml(consent).toString().length()>10);
			assertTrue(context.getResources().getQuantityString(R.plurals.x_minutes, 1, 1).contains("1"));
			assertTrue(context.getResources().getQuantityString(R.plurals.x_minutes, 2, 2).contains("2"));
		}
		assertEquals("1 minute", english.getResources().getQuantityString(R.plurals.x_minutes, 1, 1));
		assertEquals("2 minutes", english.getResources().getQuantityString(R.plurals.x_minutes, 2, 2));
	}

	@Test public void visibleBoundaryAndNativeInkChecksRejectRealHorizontalOverflow(){
		try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup()){
			Context context=localized(controller.get(), "en-US", 1.8f, R.style.Theme_Mastodon_Light);
			FrameLayout host=new FrameLayout(context);
			TextView text=new TextView(context); text.setTextColor(Color.BLACK); text.setTextSize(24);
			text.setSingleLine(true); text.setEllipsize(null); text.setHorizontallyScrolling(true);
			text.setText("MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM");
			host.addView(text, new FrameLayout.LayoutParams(dp(context, 80), ViewGroup.LayoutParams.WRAP_CONTENT));
			controller.get().setContentView(host); measure(host, dp(context, 320), dp(context, 200));
			assertTrue(text.isAttachedToWindow()); assertEquals(dp(context, 80), text.getWidth());
			assertTrue("Negative control must contain genuinely overwide visible text", text.getLayout().getLineMax(0)>text.getWidth());
			assertEquals("Negative control may not hide overflow with ellipsis", 0, text.getLayout().getEllipsisCount(0));
			assertFailsWith("Visible line exceeds measured width", ()->assertTextNotClipped(text));
			// Call the ink detector independently: otherwise a passing geometric check could mask
			// a broken, clipped, transparent or non-native raster validation implementation.
			assertFailsWith("Native text ink crosses measured viewport", ()->assertNativeTextInkInsideViewport(text));
		}
	}

	@Test public void wrappedTrailingWhitespaceDoesNotCountAsVisibleOverflow(){
		try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup()){
			Context context=localized(controller.get(), "en-US", 1f, R.style.Theme_Mastodon_Light);
			FrameLayout host=new FrameLayout(context);
			TextView text=new TextView(context); text.setTextColor(Color.BLACK); text.setTextSize(20);
			text.setIncludeFontPadding(true); text.setSingleLine(false); text.setEllipsize(null);
			text.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE); text.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
			String word="MMMMMM";
			text.setText(word+"                    "+word);
			int width=(int)Math.ceil(text.getPaint().measureText(word))+1;
			host.addView(text, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
			controller.get().setContentView(host); measure(host, dp(context, 320), dp(context, 400));
			assertTrue(text.isAttachedToWindow());
			boolean includesInvisibleAdvance=false;
			for(int line=0;line<text.getLayout().getLineCount();line++){
				if(text.getLayout().getLineWidth(line)>width+1 && text.getLayout().getLineMax(line)<=width+1
						&& text.getLayout().getLineVisibleEnd(line)<text.getLayout().getLineEnd(line)) includesInvisibleAdvance=true;
			}
			assertTrue("Fixture must exercise real trailing-space advance, not bypass the overflow check", includesInvisibleAdvance);
			assertTextNotClipped(text);
		}
	}

	private static void assertFailsWith(String expected, Runnable assertion){
		try{ assertion.run(); }catch(AssertionError failure){
			assertTrue("Unexpected assertion: "+failure.getMessage(), failure.getMessage()!=null && failure.getMessage().contains(expected));
			return;
		}
		fail("The real overflow negative control must fail: "+expected);
	}

	private static int dp(Context context, int value){ return Math.round(value*context.getResources().getDisplayMetrics().density); }
	private static int spec(int value, int mode){ return View.MeasureSpec.makeMeasureSpec(value, mode); }
	private static void assertPinWithinParent(PinView pin, ViewGroup parent){
		assertTrue("PIN row exceeds parent's left content edge", pin.getLeft()>=parent.getPaddingLeft());
		assertTrue("PIN row exceeds parent's right content edge", pin.getRight()<=parent.getWidth()-parent.getPaddingRight());
		assertTrue("PIN row must respect remaining width", pin.getMeasuredWidth()<=parent.getWidth()-parent.getPaddingLeft()-parent.getPaddingRight());
	}

	/** Discover the six actual rasterized fill regions, then compare empty/filled native output. */
	private static void assertSixPinBoxesAndDigitsInsideViewport(PinView pin){
		int left=pin.getPaddingLeft(), top=pin.getPaddingTop(), right=pin.getWidth()-pin.getPaddingRight(), bottom=pin.getHeight()-pin.getPaddingBottom();
		assertTrue(right>left); assertTrue(bottom>top);
		pin.setBackground(null); pin.setPadding(left, top, pin.getWidth()-right, pin.getHeight()-bottom);
		pin.clearFocus(); pin.clearPin();
		Bitmap empty=pinBitmap(pin);
		try{
			List<Rect> boxes=new ArrayList<>();
			Rect current=null;
			for(int x=0;x<empty.getWidth();x++){
				int first=-1, last=-1, count=0;
				for(int y=0;y<empty.getHeight();y++){
					int pixel=empty.getPixel(x,y);
					if(Color.alpha(pixel)>240 && Color.red(pixel)>=220 && Color.red(pixel)==Color.green(pixel) && Color.green(pixel)==Color.blue(pixel)){
						if(first<0) first=y; last=y; count++;
					}
				}
				if(count>Math.max(2, dp(pin.getContext(), 3))){
					if(current==null) current=new Rect(x, first, x+1, last+1);
					else current.union(x, first, x+1, last+1);
				}else if(current!=null){ boxes.add(current); current=null; }
			}
			if(current!=null) boxes.add(current);
			assertEquals("All six boxes must actually be drawn", 6, boxes.size());
			for(int i=0;i<boxes.size();i++){
				Rect box=boxes.get(i);
				assertTrue("Box clipped at left", box.left>left);
				assertTrue("Box clipped at right", box.right<right);
				assertTrue("Box clipped at top", box.top>top);
				assertTrue("Box clipped at bottom", box.bottom<bottom);
				if(i>0) assertTrue("PIN cells overlap", boxes.get(i-1).right<box.left);
			}
			int[] fillCounts=new int[6];
			for(int i=0;i<boxes.size();i++) fillCounts[i]=boxes.get(i).width();
			for(int width:fillCounts) assertTrue("Cell widths must match", Math.abs(width-fillCounts[0])<=2);
			for(String code:new String[]{"123456", "789012"}){
				pin.setText(code);
				Bitmap filled=pinBitmap(pin);
				try{
					for(Rect box:boxes){
						Rect glyph=null;
						for(int x=box.left;x<box.right;x++) for(int y=box.top;y<box.bottom;y++){
							int pixel=filled.getPixel(x,y);
							if(Color.alpha(pixel)>240 && Color.red(pixel)<90 && Color.green(pixel)<90 && Color.blue(pixel)<90){
								if(glyph==null) glyph=new Rect(x,y,x+1,y+1); else glyph.union(x,y,x+1,y+1);
							}
						}
						assertNotNull("Each PIN cell must contain a real visible digit", glyph);
						assertTrue("Digit clipped horizontally", glyph.left>box.left && glyph.right<box.right);
						assertTrue("Digit clipped vertically", glyph.top>box.top && glyph.bottom<box.bottom);
					}
					for(int x=0;x<filled.getWidth();x++) for(int y=0;y<filled.getHeight();y++){
						if(x<left || x>=right || y<top || y>=bottom) assertEquals("PIN pixels outside padded viewport", 0, Color.alpha(filled.getPixel(x,y)));
					}
				}finally{ filled.recycle(); }
			}
			// Resize-aware geometry must restore the original style rather than compound shrinking.
			assertEquals("789012", pin.getText().toString());
		}finally{ empty.recycle(); }
	}
	private static Bitmap pinBitmap(PinView pin){
		Bitmap bitmap=Bitmap.createBitmap(pin.getWidth(), pin.getHeight(), Bitmap.Config.ARGB_8888);
		pin.draw(new Canvas(bitmap)); return bitmap;
	}

	private static Context localized(Context base, String tag, float scale, int theme){
		Configuration configuration=new Configuration(base.getResources().getConfiguration());
		configuration.setLocales(LocaleList.forLanguageTags(tag)); configuration.fontScale=scale;
		return new ContextThemeWrapper(base.createConfigurationContext(configuration), theme);
	}
	private static List<TextView> textViews(View root){
		ArrayList<TextView> result=new ArrayList<>();
		if(root instanceof TextView text) result.add(text);
		if(root instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) result.addAll(textViews(group.getChildAt(i)));
		return result;
	}
	private static ScrollView findScroll(View root){
		if(root instanceof ScrollView scroll) return scroll;
		if(root instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++){ ScrollView found=findScroll(group.getChildAt(i)); if(found!=null) return found; }
		return null;
	}
	private static boolean containsHan(String value){ return value.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN); }
	private static void assertTextNotClipped(TextView text){
		assertNotNull("No text layout for "+text.getText(), text.getLayout());
		android.text.Layout layout=text.getLayout();
		int width=text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight();
		int height=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom();
		assertTrue("No measured text viewport: "+text.getText(), width>0 && height>0);
		assertTrue("Text exceeds measured height: "+lineDiagnostic(text, 0), layout.getHeight()<=height+1);
		for(int line=0;line<layout.getLineCount();line++){
			String diagnostic=lineDiagnostic(text, line);
			assertEquals("Ellipsized line: "+diagnostic, 0, layout.getEllipsisCount(line));
			assertTrue("Invalid visible line range: "+diagnostic,
					layout.getLineVisibleEnd(line)>=layout.getLineStart(line) && layout.getLineVisibleEnd(line)<=layout.getLineEnd(line));
			// getLineWidth includes advance for invisible trailing spaces. Android lays out and
			// aligns visible text using getLineMax/getLineVisibleEnd, not that trailing advance.
			assertTrue("Visible line exceeds measured width: "+diagnostic, layout.getLineMax(line)<=width+1);
			assertTrue("Visible line crosses left viewport edge: "+diagnostic, layout.getLineLeft(line)>=-1);
			assertTrue("Visible line crosses right viewport edge: "+diagnostic, layout.getLineRight(line)<=width+1);
		}
		assertNativeTextInkInsideViewport(text);
	}
	private static String lineDiagnostic(TextView text, int line){
		android.text.Layout layout=text.getLayout();
		int start=layout.getLineStart(line), end=layout.getLineEnd(line), visibleEnd=layout.getLineVisibleEnd(line);
		String raw=layout.getText().subSequence(start, end).toString().replace("\n", "\\n").replace("\t", "\\t");
		String visible=layout.getText().subSequence(start, visibleEnd).toString().replace("\n", "\\n").replace("\t", "\\t");
		String id=text.getId()==View.NO_ID ? "no-id" : Integer.toHexString(text.getId());
		return "id="+id+", fontScale="+text.getResources().getConfiguration().fontScale+", textSize="+text.getTextSize()
				+", viewport="+(text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight())+"x"
				+(text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom())
				+", layout="+layout.getWidth()+"x"+layout.getHeight()+", line="+line+", range="+start+".."+end+", visibleEnd="+visibleEnd
				+", widthIncludingTrailing="+layout.getLineWidth(line)+", visibleMax="+layout.getLineMax(line)
				+", left="+layout.getLineLeft(line)+", right="+layout.getLineRight(line)
				+", raw=["+raw+"], visible=["+visible+"]";
	}
	/**
	 * Draw the real measured Layout on a larger transparent native bitmap. TextView.draw() would
	 * clip at its own bounds and conceal the very overflow this check must detect. The extra raster
	 * is observation space only: the allowed viewport remains the actual measured content rectangle.
	 */
	private static void assertNativeTextInkInsideViewport(TextView text){
		android.text.Layout layout=text.getLayout();
		assertNotNull("Native ink requires an actual measured Layout", layout);
		int width=text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight();
		int height=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom();
		float minLeft=0, maxRight=width;
		for(int line=0;line<layout.getLineCount();line++){
			minLeft=Math.min(minLeft, layout.getLineLeft(line)); maxRight=Math.max(maxRight, layout.getLineRight(line));
		}
		int guard=Math.max(16, (int)Math.ceil(text.getTextSize()*2));
		int originX=guard+(int)Math.ceil(-minLeft), originY=guard;
		int bitmapWidth=originX+(int)Math.ceil(maxRight)+guard;
		int bitmapHeight=originY+Math.max(height, layout.getHeight())+guard;
		Bitmap bitmap=Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
		try{
			Canvas canvas=new Canvas(bitmap); canvas.translate(originX, originY); layout.draw(canvas);
			Rect ink=null;
			int offendingX=-1, offendingY=-1;
			int[] row=new int[bitmapWidth];
			for(int y=0;y<bitmapHeight;y++){
				bitmap.getPixels(row, 0, bitmapWidth, 0, y, bitmapWidth, 1);
				for(int x=0;x<bitmapWidth;x++){
					if(Color.alpha(row[x])==0) continue;
					if(ink==null) ink=new Rect(x,y,x+1,y+1); else ink.union(x,y,x+1,y+1);
					// Keep the same one-pixel rounding allowance as the geometric assertions.
					if(offendingX<0 && (x<originX-1 || x>=originX+width+1 || y<originY-1 || y>=originY+height+1)){
						offendingX=x; offendingY=y;
					}
				}
			}
			if(layout.getText().toString().codePoints().anyMatch(c->!Character.isWhitespace(c)))
				assertNotNull("Native text must produce real visible ink: "+text.getText(), ink);
			int line=layout.getLineForVertical(Math.max(0, offendingY-originY));
			assertTrue("Native text ink crosses measured viewport: ink="+ink+", origin="+originX+","+originY
					+", firstOverflow="+offendingX+","+offendingY+", "+lineDiagnostic(text, line), offendingX<0);
		}finally{ bitmap.recycle(); }
	}
	private static void measure(View root, int width, int height){
		for(int pass=0;pass<3;pass++){
			root.forceLayout(); root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
			root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight()); root.getViewTreeObserver().dispatchOnPreDraw();
		}
	}
	private static void capture(Page page, String name) throws Exception{
		String destination=System.getProperty("EnglishRenderDir", "evidence/android-ui-renders/english");
		File directory=new File(destination); assertTrue(directory.isDirectory() || directory.mkdirs());
		Bitmap bitmap=Bitmap.createBitmap(page.root.getWidth(), page.root.getHeight(), Bitmap.Config.ARGB_8888);
		page.root.draw(new Canvas(bitmap));
		try(FileOutputStream output=new FileOutputStream(new File(directory, "general-"+name+".png"))){ assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); }
		bitmap.recycle();
	}
	private static final class Page implements AutoCloseable{
		final ActivityController<Activity> lifecycle;
		final Context context;
		final View root;
		Page(int layout, float scale, int theme){
			lifecycle=Robolectric.buildActivity(Activity.class).setup();
			context=localized(lifecycle.get(), "en-US", scale, theme);
			root=LayoutInflater.from(context).inflate(layout, new FrameLayout(context), false);
			lifecycle.get().setContentView(root); measure(root, 320, 640);
		}
		@Override public void close(){ lifecycle.close(); }
	}
}
