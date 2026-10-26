package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.XmlResourceParser;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.nbw.NBWAppRequest;
import org.joinmastodon.android.api.requests.statuses.CreateStatus;
import org.joinmastodon.android.api.session.AccountLocalPreferences;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.NBWForumOptionsRepository;
import org.joinmastodon.android.fragments.settings.NBWPostRegisterActivity;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.InstanceV1;
import org.joinmastodon.android.model.NBWCapabilities;
import org.joinmastodon.android.model.NBWForumOptions;
import org.joinmastodon.android.model.NBWSelection;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.ui.sheets.NBWFallbackConsentSheet;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.views.SpaceBackgroundView;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.grishka.appkit.utils.V;
import okio.Buffer;

/** Inflates and exercises the real connection activity; only authenticated HTTP transport is captured. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="zh-rCN-w393dp-h852dp-notnight-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class NBWV2ConnectionUiTest{
	private static final String ANDROID="http://schemas.android.com/apk/res/android";
	private AccountSession session, previousSession;
	private Instance previousInstance;
	private String previousActive;
	private Map<String, AccountSession> sessions;
	private Map<String, Instance> instances;
	private GlobalUserPreferences.ThemePreference previousTheme;
	private AccountLocalPreferences.ColorPreference previousColor;
	private boolean previousCustomTabs;
	private NBWForumOptionsRepository repository;
	private CapturingTransport transport;
	private CapturingController controller;
	private final List<ActivityController<NBWPostRegisterActivity>> activities=new ArrayList<>();

	private static final class Pending<T> implements NBWForumOptionsRepository.Cancelable{
		final NBWForumOptionsRepository.Result<T> callback;
		boolean canceled, completed;
		Pending(NBWForumOptionsRepository.Result<T> callback){ this.callback=callback; }
		void success(T value){ completed=true; callback.success(value); }
		@Override public void cancel(){ canceled=true; }
	}
	private static final class CapturingTransport implements NBWForumOptionsRepository.Transport{
		final List<Pending<NBWCapabilities>> capabilities=new ArrayList<>();
		final List<Pending<NBWForumOptions>> options=new ArrayList<>();
		final List<Boolean> consents=new ArrayList<>();
		@Override public NBWForumOptionsRepository.Cancelable capabilities(NBWForumOptionsRepository.Result<NBWCapabilities> result){
			Pending<NBWCapabilities> pending=new Pending<>(result); capabilities.add(pending); return pending;
		}
		@Override public NBWForumOptionsRepository.Cancelable options(NBWForumOptionsRepository.Result<NBWForumOptions> result){
			Pending<NBWForumOptions> pending=new Pending<>(result); options.add(pending); return pending;
		}
		@Override public NBWForumOptionsRepository.Cancelable consent(boolean accepted, NBWForumOptionsRepository.Result<Object> result){
			consents.add(accepted); return new Pending<>(result);
		}
	}
	private static final class CapturingController extends MastodonAPIController{
		final List<MastodonAPIRequest<?>> requests=new ArrayList<>();
		CapturingController(AccountSession session){ super(session); }
		@Override public <T> void submitRequest(MastodonAPIRequest<T> request){ requests.add(request); }
	}

	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		previousTheme=GlobalUserPreferences.theme; previousColor=GlobalUserPreferences.color;
		previousCustomTabs=GlobalUserPreferences.useCustomTabs;
		GlobalUserPreferences.theme=GlobalUserPreferences.ThemePreference.LIGHT;
		GlobalUserPreferences.color=AccountLocalPreferences.ColorPreference.PURPLE;
		GlobalUserPreferences.useCustomTabs=false;
		var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true); session=constructor.newInstance();
		session.domain="nbw-connection.offline.example.test"; session.self=new Account(); session.self.id="83";
		AccountSessionManager manager=AccountSessionManager.getInstance();
		sessions=(Map<String, AccountSession>)field(AccountSessionManager.class, "sessions").get(manager);
		instances=(Map<String, Instance>)field(AccountSessionManager.class, "instances").get(manager);
		previousSession=sessions.put(session.getID(), session);
		previousActive=(String)field(AccountSessionManager.class, "lastActiveAccountID").get(manager);
		field(AccountSessionManager.class, "lastActiveAccountID").set(manager, session.getID());
		InstanceV1 instance=new InstanceV1(); instance.uri=instance.normalizedUri=session.domain; instance.version="4.3.0";
		previousInstance=instances.put(session.domain, instance);
		transport=new CapturingTransport();
		repository=new NBWForumOptionsRepository(transport, ()->0L, new NBWForumOptionsRepository.SelectionStore(){
			@Override public NBWSelection load(){ return NBWSelection.ai(); }
			@Override public void save(NBWSelection value){}
		}, ()->{}, Runnable::run);
		field(AccountSession.class, "nbwForumOptionsRepository").set(session, repository);
		controller=new CapturingController(session); field(AccountSession.class, "apiController").set(session, controller);
		MastodonApp.context.getSharedPreferences("nbw_v2_authorization", 0).edit().clear().commit();
	}

	@After public void cleanup() throws Exception{
		for(ActivityController<NBWPostRegisterActivity> activity:activities) activity.close();
		repository.deactivate(); ShadowLooper.idleMainLooper();
		if(previousSession==null) sessions.remove(session.getID()); else sessions.put(session.getID(), previousSession);
		if(previousInstance==null) instances.remove(session.domain); else instances.put(session.domain, previousInstance);
		field(AccountSessionManager.class, "lastActiveAccountID").set(AccountSessionManager.getInstance(), previousActive);
		GlobalUserPreferences.theme=previousTheme; GlobalUserPreferences.color=previousColor;
		GlobalUserPreferences.useCustomTabs=previousCustomTabs;
		MastodonApp.context.getSharedPreferences("nbw_v2_authorization", 0).edit().clear().commit();
	}

	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
	public void actualActivityReusesOldXmlBrandTypographyAndThreeControlStyles() throws Exception{
		publishCapabilities("full", false); NBWPostRegisterActivity page=open(false); layout(page);
		assertTrue(page.findViewById(R.id.space_bg) instanceof SpaceBackgroundView);
		assertTrue(page.findViewById(R.id.nbw_connection_scroll) instanceof ScrollView);
		TextView title=page.findViewById(R.id.nbw_connection_title);
		assertEquals(page.getString(R.string.nbw_v2_connection_title), title.getText().toString());
		assertEquals(28*page.getResources().getDisplayMetrics().scaledDensity, title.getTextSize(), 0.01f);
		assertBoldTitle(title);
		assertEquals(UiUtils.getThemeColor(page, R.attr.colorM3OnSurface), title.getCurrentTextColor());
		ImageView logo=page.findViewById(R.id.nbw_connection_logo);
		assertEquals(dp(page, 64), logo.getWidth()); assertEquals(dp(page, 64), logo.getHeight());
		assertNull(logo.getImageTintList()); assertEquals(page.getString(R.string.nbw_third_party_nbw), logo.getContentDescription());
		assertLayoutResource(R.id.nbw_connection_logo, "src", R.drawable.ic_nbw_large);
		assertLayoutResource(R.id.nbw_connection_logo, "background", R.drawable.bg_circle_white);
		assertLayoutResource(R.id.btn_back, "src", R.drawable.ic_arrow_back_24);
		assertLayoutResource(R.id.btn_nbw_connect, "background", R.drawable.bg_btn_gradient_purple);
		assertLayoutResource(R.id.btn_nbw_settings, "background", R.drawable.bg_btn_outline_blue);
		Button connect=page.findViewById(R.id.btn_nbw_connect), settings=page.findViewById(R.id.btn_nbw_settings), skip=page.findViewById(R.id.btn_skip);
		assertNull(connect.getBackgroundTintList()); assertNull(settings.getBackgroundTintList());
		assertEquals("Primary label must remain readable on the old light-blue fill", Color.rgb(16, 56, 77), connect.getCurrentTextColor());
		assertEquals(UiUtils.getThemeColor(page, R.attr.colorM3Primary), skip.getCurrentTextColor());
		assertEquals(dp(page, 52), connect.getHeight()); assertEquals(dp(page, 52), settings.getHeight()); assertEquals(dp(page, 48), skip.getHeight());
		GradientDrawable primary=(GradientDrawable)((StateListDrawable)connect.getBackground()).getCurrent();
		assertEquals(Color.rgb(161, 217, 247), primary.getColor().getDefaultColor());
		assertEquals(dp(page, 28), primary.getCornerRadius(), 0.01f);
		GradientDrawable secondary=(GradientDrawable)((StateListDrawable)settings.getBackground()).getCurrent();
		assertEquals(Color.TRANSPARENT, secondary.getColor().getDefaultColor()); assertEquals(dp(page, 28), secondary.getCornerRadius(), 0.01f);
		assertEquals(3, buttons(page.findViewById(R.id.root)).size());
		assertEquals(page.getString(R.string.nbw_v2_connect), connect.getText().toString());
		assertEquals(page.getString(R.string.nbw_v2_fallback_settings), settings.getText().toString());
		assertEquals("暂时跳过", skip.getText().toString());
		assertEquals(View.GONE, page.findViewById(R.id.btn_back).getVisibility());
		assertEquals("本站发帖不受影响", ((TextView)page.findViewById(R.id.nbw_connection_local_publish)).getText().toString());
		String text=text(page.findViewById(R.id.root));
		assertTrue(text.contains("ABDL Space 联邦·深度合作成员"));
		assertFalse(text.contains("未绑定宝宝新天地账户将无法发帖")); assertFalse(text.contains("一键注册")); assertFalse(text.contains("绑定已有"));
	}

	@Test @Config(sdk=33) public void systemInsetsAffectOnlyForegroundAndNeverAccumulate(){
		NBWPostRegisterActivity page=open(true); layout(page);
		View content=page.findViewById(R.id.content_container), background=page.findViewById(R.id.space_bg);
		WindowInsets insets=new WindowInsets.Builder()
				.setInsets(WindowInsets.Type.statusBars(), android.graphics.Insets.of(0, 24, 0, 0))
				.setInsets(WindowInsets.Type.navigationBars(), android.graphics.Insets.of(8, 0, 48, 30)).build();
		for(int i=0;i<2;i++){
			content.dispatchApplyWindowInsets(insets);
			assertEquals(8, content.getPaddingLeft()); assertEquals(24, content.getPaddingTop());
			assertEquals(48, content.getPaddingRight()); assertEquals(30, content.getPaddingBottom());
		}
		assertEquals(Color.TRANSPARENT, page.getWindow().getStatusBarColor());
		assertEquals(Color.TRANSPARENT, page.getWindow().getNavigationBarColor());
		assertEquals(0, background.getTop()); assertEquals(page.findViewById(R.id.root).getHeight(), background.getHeight());
		assertEquals(View.VISIBLE, page.findViewById(R.id.btn_back).getVisibility());
		assertTrue(page.findViewById(R.id.btn_back).isClickable());
	}

	@Test public void authenticatedReadLocalFullReadyAndUnboundSnapshotsDriveActualControls(){
		NBWPostRegisterActivity page=open(true);
		assertStatus(page, R.string.nbw_v2_connection_unavailable, false);
		session.self.nbwUsername="stale-legacy-binding-must-not-grant-access";
		publishCapabilities("local_only", false); assertStatus(page, R.string.nbw_v2_connection_local_only, false);
		publishCapabilities("read_only", false); assertStatus(page, R.string.nbw_v2_read_only, false);
		publishCapabilities("full", false); assertStatus(page, R.string.nbw_v2_connection_missing, true);
		publishCapabilities("full", true); assertStatus(page, R.string.nbw_v2_connection_ready, true);
		publishCapabilities("full", false); assertStatus(page, R.string.nbw_v2_connection_missing, true);
		repository.deactivate(); ShadowLooper.idleMainLooper(); assertStatus(page, R.string.nbw_v2_connection_local_only, false);
		assertTrue(controller.requests.isEmpty()); assertTrue(transport.consents.isEmpty());
	}

	@Test public void unboundLocalAndReadOnlyMetadataNeverBlockFirstPartyPublishing(){
		for(String mode:List.of("local_only", "read_only", "full")){
			publishCapabilities(mode, false);
			CreateStatus.Request body=new CreateStatus.Request(); body.status="本站内容"; body.visibility=StatusPrivacy.PUBLIC; body.setNBWSelection(NBWSelection.ai());
			MastodonAPIRequest<Status> request=new CreateStatus(body, "connection-ui-"+mode).exec(session.getID());
			assertSame(request, controller.requests.get(controller.requests.size()-1));
			assertEquals("/api/v1/statuses", request.getURL().getPath()); assertFalse(request.canceled);
			assertEquals("ai", body.toJson().get("nbw_classification").getAsString());
			assertFalse(body.toJson().has("nbw_fid")); assertFalse(body.toJson().has("uid"));
		}
		assertEquals(3, controller.requests.size()); assertTrue(transport.consents.isEmpty());
	}

	@Test public void primaryStartsOnlyAuthenticatedV2OnceAndLaunchesServerUrlWithSingleUseRefreshTicket() throws Exception{
		publishCapabilities("full", false); NBWPostRegisterActivity page=open(true);
		Button connect=page.findViewById(R.id.btn_nbw_connect); connect.performClick(); connect.performClick();
		assertEquals(1, controller.requests.size()); assertFalse(connect.isEnabled());
		assertEquals(page.getString(R.string.nbw_v2_connection_pending), ((TextView)page.findViewById(R.id.nbw_connection_status)).getText().toString());
		MastodonAPIRequest<?> request=controller.requests.get(0);
		assertTrue(request instanceof NBWAppRequest); assertEquals("POST", request.getMethod());
		assertEquals("/api/app/nbw/authorization/start", request.getURL().getPath()); assertNull(request.getURL().getQuery());
		assertEquals("2", ((Map<?, ?>)field(MastodonAPIRequest.class, "headers").get(request)).get("X-ABDL-NBW-Protocol"));
		Buffer buffer=new Buffer(); request.getRequestBody().writeTo(buffer); assertEquals(0, com.google.gson.JsonParser.parseString(buffer.readUtf8()).getAsJsonObject().size());
		assertNull(Shadow.<ShadowActivity>extract(page).getNextStartedActivity());
		NBWAppRequest.AuthorizationStart response=new NBWAppRequest.AuthorizationStart();
		response.authorizationUrl="https://authorization.offline.example.test/nbw/connect/flow-83";
		response.postprocess(); authorizationResult(request).onSuccess(response); ShadowLooper.idleMainLooper();
		Intent browser=Shadow.<ShadowActivity>extract(page).getNextStartedActivity();
		assertNotNull(browser); assertEquals(Intent.ACTION_VIEW, browser.getAction()); assertEquals(response.authorizationUrl, browser.getDataString());
		assertNull(browser.getData().getQuery()); assertNull(Shadow.<ShadowActivity>extract(page).getNextStartedActivity());
		assertEquals(session.getID(), NBWPostRegisterActivity.consumeAuthorizationLaunch(page));
		assertNull(NBWPostRegisterActivity.consumeAuthorizationLaunch(page));
		assertTrue(connect.isEnabled()); assertFalse(page.isFinishing()); assertTrue(transport.consents.isEmpty());
	}

	@Test public void secondaryOpensOnlyCompactDefaultConsentSheetWithoutStartingAuthorizationOrSelectingAForum(){
		publishCapabilities("full", false); NBWPostRegisterActivity page=open(true); layout(page);
		page.findViewById(R.id.btn_nbw_settings).performClick();
		Dialog dialog=ShadowDialog.getLatestDialog(); assertTrue(dialog instanceof NBWFallbackConsentSheet); assertTrue(dialog.isShowing());
		ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
		View decor=dialog.getWindow().getDecorView(); layout(decor, dp(page, 393), dp(page, 852));
		View sheet=dialog.findViewById(R.id.nbw_fallback_sheet);
		assertNotNull(sheet); assertTrue(sheet.getHeight()>0); assertTrue(sheet.getHeight()<decor.getHeight());
		Rect bounds=new Rect(0, 0, sheet.getWidth(), sheet.getHeight()); ((ViewGroup)decor).offsetDescendantRectToMyCoords(sheet, bounds);
		assertTrue("Default settings content sits below a visible scrim, not a full-screen form", bounds.top>0);
		assertTrue(bounds.bottom>decor.getHeight()*3/4); assertTrue(bounds.bottom<=decor.getHeight());
		assertTrue(text(sheet).contains(page.getString(R.string.nbw_v2_fallback_settings)));
		assertEquals(page.getString(R.string.nbw_v2_fallback_accept), ((Button)dialog.findViewById(R.id.nbw_fallback_accept)).getText().toString());
		assertTrue(dialog.findViewById(R.id.nbw_fallback_accept).isEnabled());
		assertFalse(text(sheet).contains(page.getString(R.string.nbw_v2_classification_title)));
		assertEquals(NBWSelection.ai(), repository.getSnapshot().selection);
		assertTrue(controller.requests.isEmpty()); assertTrue(transport.consents.isEmpty());
		assertNull(Shadow.<ShadowActivity>extract(page).getNextStartedActivity());
		dialog.findViewById(R.id.nbw_fallback_done).performClick(); ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
		assertFalse(dialog.isShowing()); assertFalse(page.isFinishing());
	}

	@Test public void unknownExplicitAccountClosesRatherThanFallingBackToCurrentAccount(){
		Intent intent=new Intent(MastodonApp.context, NBWPostRegisterActivity.class).putExtra("account", "missing_offline").putExtra("show_back", true);
		ActivityController<NBWPostRegisterActivity> lifecycle=Robolectric.buildActivity(NBWPostRegisterActivity.class, intent); activities.add(lifecycle); lifecycle.setup();
		assertTrue(lifecycle.get().isFinishing()); assertNull(Shadow.<ShadowActivity>extract(lifecycle.get()).getNextStartedActivity());
		assertTrue(transport.capabilities.isEmpty()); assertTrue(controller.requests.isEmpty());
	}

	@Test @SuppressWarnings("deprecation") public void retiredBindingHelpersCannotPersistOrConsumeLegacyTransactions(){
		var legacy=MastodonApp.context.getSharedPreferences("nbw_bind", 0);
		Map<String, ?> before=legacy.getAll();
		String retired=NBWPostRegisterActivity.createBindingState(MastodonApp.context, session.getID(), 83);
		assertNull(NBWPostRegisterActivity.consumeBindingAccount(MastodonApp.context, retired));
		assertNull(NBWPostRegisterActivity.consumeBindingAccount(MastodonApp.context, "not-a-v2-authorization"));
		assertEquals(before, legacy.getAll()); assertTrue(controller.requests.isEmpty()); assertTrue(transport.capabilities.isEmpty());
	}

	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
	public void nativeCanvasRendersRealRegistrationAndSettingsConnectionPagesInLightAndDark() throws Exception{
		publishCapabilities("full", false);
		File directory=new File(System.getProperty("nbw.connection.renderDir", "/tmp/abdl-nbw-v2-connection-renders"));
		assertTrue(directory.isDirectory() || directory.mkdirs());
		for(boolean dark:new boolean[]{false, true}){
			GlobalUserPreferences.theme=dark ? GlobalUserPreferences.ThemePreference.DARK : GlobalUserPreferences.ThemePreference.LIGHT;
			RuntimeEnvironment.setQualifiers("zh-rCN-w393dp-h852dp-"+(dark ? "night" : "notnight")+"-mdpi");
			for(boolean settings:new boolean[]{false, true}){
				NBWPostRegisterActivity page=open(settings); layout(page);
				View decor=page.getWindow().getDecorView();
				Bitmap bitmap=Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
				try{
					decor.draw(new Canvas(bitmap));
					String name="connection-"+(settings ? "settings" : "registration")+"-"+(dark ? "dark" : "light")+".png";
					File image=new File(directory, name);
					// Keep the actual composed page even when a subsequent visual assertion fails.
					try(FileOutputStream output=new FileOutputStream(image)){ assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); }
					assertEquals(settings ? View.VISIBLE : View.GONE, page.findViewById(R.id.btn_back).getVisibility());
					assertTrue(page.findViewById(R.id.space_bg).isAttachedToWindow());
					assertEquals(393, bitmap.getWidth()); assertEquals(852, bitmap.getHeight());
					View background=page.findViewById(R.id.space_bg);
					int[] position=new int[2], origin=new int[2]; background.getLocationInWindow(position); decor.getLocationInWindow(origin);
					int backdrop=bitmap.getPixel(position[0]-origin[0]+dp(page, 4), position[1]-origin[1]+dp(page, 4));
					assertEquals(255, Color.alpha(backdrop));
					assertTrue("Actual background must use the requested app theme; PNG="+image, dark ? Color.red(backdrop)<80 : Color.red(backdrop)>200);
					Button primary=page.findViewById(R.id.btn_nbw_connect);
					assertPrimaryFill(page, primary, decor, bitmap, image);
					assertBoldTitle(page.findViewById(R.id.nbw_connection_title));
					assertFourColorBrand(page.findViewById(R.id.nbw_connection_logo));
				}finally{
					bitmap.recycle(); activities.get(activities.size()-1).close(); activities.remove(activities.size()-1); ShadowLooper.idleMainLooper();
				}
			}
		}
	}

	private NBWPostRegisterActivity open(boolean settings){
		Intent intent=new Intent(MastodonApp.context, NBWPostRegisterActivity.class).putExtra("account", session.getID()).putExtra("show_back", settings);
		ActivityController<NBWPostRegisterActivity> lifecycle=Robolectric.buildActivity(NBWPostRegisterActivity.class, intent); activities.add(lifecycle); lifecycle.setup(); return lifecycle.get();
	}
	private void publishCapabilities(String mode, boolean granted){
		repository.activate(); repository.refreshNow();
		NBWCapabilities capabilities=NBWV2RepositoryTest.capabilities(mode);
		if(granted){ capabilities.binding.state="bound"; capabilities.binding.uid="remote-83"; capabilities.authorization.state="ready"; }
		transport.capabilities.get(transport.capabilities.size()-1).success(capabilities);
		for(Pending<NBWForumOptions> pending:new ArrayList<>(transport.options)) if(!pending.completed && !pending.canceled) pending.success(NBWV2RepositoryTest.options());
		ShadowLooper.idleMainLooper();
	}
	private static void assertStatus(NBWPostRegisterActivity page, int message, boolean writable){
		assertEquals(page.getString(message), ((TextView)page.findViewById(R.id.nbw_connection_status)).getText().toString());
		for(int control:new int[]{R.id.btn_nbw_connect, R.id.btn_nbw_settings}) assertEquals(writable ? View.VISIBLE : View.GONE, page.findViewById(control).getVisibility());
		assertEquals(View.VISIBLE, page.findViewById(R.id.btn_skip).getVisibility()); assertTrue(page.findViewById(R.id.btn_skip).isEnabled());
		assertTrue(((TextView)page.findViewById(R.id.nbw_connection_local_publish)).getText().toString().contains("本站发帖不受影响"));
	}
	private static void layout(NBWPostRegisterActivity page){
		View decor=page.getWindow().getDecorView();
		layout(decor, dp(page, 393), dp(page, 852));
		ViewCompat.requestApplyInsets(page.findViewById(R.id.content_container));
		ShadowLooper.idleMainLooper(); layout(decor, dp(page, 393), dp(page, 852));
	}
	private static void layout(View view, int width, int height){
		for(int pass=0;pass<2;pass++){
			view.forceLayout(); view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
			view.layout(0, 0, width, height); view.getViewTreeObserver().dispatchOnPreDraw();
		}
	}
	private static int dp(NBWPostRegisterActivity page, int value){ return Math.round(value*page.getResources().getDisplayMetrics().density); }
	private static List<Button> buttons(View view){
		List<Button> result=new ArrayList<>();
		if(view instanceof Button button) result.add(button);
		if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) result.addAll(buttons(group.getChildAt(i)));
		return result;
	}
	private static String text(View view){
		StringBuilder result=new StringBuilder(); if(view instanceof TextView text) result.append(text.getText()).append('\n');
		if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) result.append(text(group.getChildAt(i)));
		return result.toString();
	}
	private static void assertLayoutResource(int id, String attribute, int expected) throws Exception{
		try(XmlResourceParser parser=MastodonApp.context.getResources().getLayout(R.layout.activity_nbw_post_register)){
			for(int event=parser.next(); event!=XmlPullParser.END_DOCUMENT; event=parser.next()) if(event==XmlPullParser.START_TAG && parser.getAttributeResourceValue(ANDROID, "id", 0)==id){
				assertEquals(expected, parser.getAttributeResourceValue(ANDROID, attribute, 0)); return;
			}
		}
		fail("Connection layout is missing control "+id);
	}
	private static void assertBoldTitle(TextView title) throws Exception{
		boolean found=false;
		try(XmlResourceParser parser=title.getResources().getLayout(R.layout.activity_nbw_post_register)){
			for(int event=parser.next(); event!=XmlPullParser.END_DOCUMENT; event=parser.next()) if(event==XmlPullParser.START_TAG && parser.getAttributeResourceValue(ANDROID, "id", 0)==R.id.nbw_connection_title){
				assertEquals("The real title XML must retain bold typography", Typeface.BOLD, parser.getAttributeIntValue(ANDROID, "textStyle", Typeface.NORMAL));
				found=true; break;
			}
		}
		assertTrue("Connection layout is missing its title", found);
		Typeface face=title.getTypeface(), painted=title.getPaint().getTypeface();
		String diagnostic="title typeface(style/weight)="+(face==null ? "null" : face.getStyle()+"/"+face.getWeight())
				+", paint typeface(style/weight)="+(painted==null ? "null" : painted.getStyle()+"/"+painted.getWeight())
				+", fakeBold="+title.getPaint().isFakeBoldText();
		// Run callers in native graphics mode: legacy Typeface.isBold() reads an uninitialized
		// backing field, whereas its shadowed getStyle() can correctly report BOLD.
		assertTrue("Title must actually paint bold, not merely declare it in XML; "+diagnostic,
				painted!=null && (painted.getWeight()>=700 || title.getPaint().isFakeBoldText()));
	}
	private static void assertPrimaryFill(NBWPostRegisterActivity page, Button primary, View decor, Bitmap bitmap, File image){
		int[] position=new int[2], origin=new int[2]; primary.getLocationOnScreen(position); decor.getLocationOnScreen(origin);
		Rect bounds=new Rect(position[0]-origin[0], position[1]-origin[1], position[0]-origin[0]+primary.getWidth(), position[1]-origin[1]+primary.getHeight());
		Rect visible=new Rect(); boolean onScreen=primary.getGlobalVisibleRect(visible);
		Drawable background=primary.getBackground(), current=background==null ? null : background.getCurrent();
		ScrollView scroll=page.findViewById(R.id.nbw_connection_scroll);
		String diagnostic="PNG="+image+", measured="+primary.getMeasuredWidth()+"x"+primary.getMeasuredHeight()
				+", bounds="+bounds+", visible="+onScreen+"/"+visible+", visibility="+primary.getVisibility()
				+", shown="+primary.isShown()+", attached="+primary.isAttachedToWindow()+", enabled="+primary.isEnabled()+", alpha="+primary.getAlpha()
				+", tint="+primary.getBackgroundTintList()+", background="+(background==null ? "null" : background.getClass().getSimpleName()+"/"+background.getBounds())
				+", current="+(current==null ? "null" : current.getClass().getSimpleName()+"/"+current.getBounds())
				+", scroll="+scroll.getWidth()+"x"+scroll.getHeight()+"@"+scroll.getScrollX()+","+scroll.getScrollY();
		assertTrue("Primary must be enabled, shown and fully inside the actual viewport; "+diagnostic,
				primary.isEnabled() && primary.isShown() && primary.isAttachedToWindow() && onScreen
						&& visible.contains(new Rect(position[0], position[1], position[0]+primary.getWidth(), position[1]+primary.getHeight()))
						&& bounds.width()>0 && bounds.height()>dp(page, 16) && new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()).contains(bounds));
		assertNull("Primary must not acquire theme tint; "+diagnostic, primary.getBackgroundTintList());
		// Sample the composed hierarchy, not a second standalone draw: View.draw can skip
		// a dirty-opaque child's background after the first parent traversal.
		for(int fraction:new int[]{1, 2, 3}) for(int y:new int[]{dp(page, 8), primary.getHeight()-dp(page, 8)}){
			int x=primary.getWidth()*fraction/4;
			assertEquals("Old solid light-blue primary must not acquire theme tint or a gradient at "+x+","+y+"; "+diagnostic,
					Color.rgb(161, 217, 247), bitmap.getPixel(bounds.left+x, bounds.top+y));
		}
	}
	private static void assertFourColorBrand(ImageView logo){
		Bitmap image=Bitmap.createBitmap(logo.getWidth(), logo.getHeight(), Bitmap.Config.ARGB_8888); logo.draw(new Canvas(image));
		boolean red=false, green=false, yellow=false, purple=false;
		for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++){
			int color=image.getPixel(x, y), r=Color.red(color), g=Color.green(color), b=Color.blue(color);
			red|=r>220 && g<120 && b<120; green|=g>150 && r<100 && b<160;
			yellow|=r>220 && g>150 && b<90; purple|=r>130 && g<130 && b>180;
		}
		image.recycle(); assertTrue("The real XML logo must keep all four NBW brand colors", red && green && yellow && purple);
	}
	@SuppressWarnings("unchecked") private static MastodonAPIRequest<NBWAppRequest.AuthorizationStart> authorizationResult(MastodonAPIRequest<?> request){ return (MastodonAPIRequest<NBWAppRequest.AuthorizationStart>)request; }
	private static Field field(Class<?> type, String name) throws Exception{ Field field=type.getDeclaredField(name); field.setAccessible(true); return field; }
}
