package org.joinmastodon.android.fragments.albums;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.ImageView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.albums.AlbumSecureWindow;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.model.albums.AlbumModels;
import org.joinmastodon.android.ui.views.M3Switch;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import me.grishka.appkit.utils.V;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class AlbumProtectionSettingsTest{
	private AccountSessionManager manager;
	private Map<String, AccountSession> sessions;
	private AccountSession session;
	private String accountId, previousActive;
	private OkHttpClient client;
	private Object originalInterceptors;
	private org.robolectric.android.controller.ActivityController<Activity> lifecycle;
	private Activity activity;
	private AlbumDetailFragment fragment;
	private View content;
	private volatile JsonObject patch;
	private volatile boolean protectedFlag;
	private boolean owner=true, defaultAlbum=true, ignorePatch;
	private int patchStatus=200;
	private final AtomicInteger photoReads=new AtomicInteger(), securePhotoReads=new AtomicInteger();
	private static Field field(Class<?> type, String name) throws Exception{ Field result=type.getDeclaredField(name); result.setAccessible(true); return result; }
	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		manager=AccountSessionManager.getInstance(); sessions=(Map<String, AccountSession>)field(AccountSessionManager.class, "sessions").get(manager); previousActive=manager.getLastActiveAccountID();
		var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true); session=constructor.newInstance();
		session.domain="protection-settings-offline.example.test"; session.self=new Account(); session.self.id="42"; session.token=new Token(); session.token.accessToken="offline-fixture";
		accountId=session.getID(); sessions.put(accountId, session); field(AccountSessionManager.class, "lastActiveAccountID").set(manager, accountId);
		client=(OkHttpClient)field(MastodonAPIController.class, "sensitiveHttpClient").get(null); originalInterceptors=field(OkHttpClient.class, "interceptors").get(client);
		field(OkHttpClient.class, "interceptors").set(client, List.of((okhttp3.Interceptor)chain->{
			if(!session.domain.equals(chain.request().url().host())) throw new IOException("Unexpected outbound host");
			String path=chain.request().url().encodedPath(), body; int status=200;
			if("PATCH".equals(chain.request().method())){
				okio.Buffer buffer=new okio.Buffer(); chain.request().body().writeTo(buffer); patch=JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
				status=patchStatus;
				if(status==200 && !ignorePatch) protectedFlag=patch.get("download_protected").getAsBoolean();
				body=status==200 ? "{\"album\":"+albumJson()+"}" : "{\"error\":\"protection unavailable\"}";
			}else if(path.endsWith("/photos")){
				photoReads.incrementAndGet(); if(secure()) securePhotoReads.incrementAndGet();
				body="{\"photos\":[{\"id\":\"photo\",\"album_id\":\"album\",\"preview_url\":\"file:///tmp/protection-missing-preview.jpg\",\"sort_at\":1791158401,\"is_owner\":"+owner+",\"download_protected\":"+protectedFlag+"}],\"has_more\":false}";
			}else if(path.endsWith("/albums/") || path.endsWith("/albums")) body="{\"albums\":["+albumJson()+"],\"has_more\":false}";
			else if(path.endsWith("/album")) body="{\"album\":"+albumJson()+"}";
			else throw new IOException("Unexpected fixture route "+path);
			return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture").body(ResponseBody.create(MediaType.get("application/json"), body)).build();
		}));
	}
	private String albumJson(){ return "{\"id\":\"album\",\"name\":\"Baby album\",\"visibility\":\"shared\",\"is_default\":"+defaultAlbum+",\"is_owner\":"+owner+",\"can_upload\":"+owner+",\"download_protected\":"+protectedFlag+",\"photo_count\":1,\"cover_url\":\"file:///tmp/protection-missing-cover.jpg\"}"; }
	private boolean secure(){ return activity!=null && (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)!=0; }
	private void start(boolean originalSecure) throws Exception{
		lifecycle=Robolectric.buildActivity(Activity.class).create(); activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light);
		if(originalSecure) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		lifecycle.start().resume(); fragment=new AlbumDetailFragment(); Bundle args=new Bundle(); args.putString("account", accountId); args.putString("albumId", "album"); fragment.setArguments(args);
		activity.getFragmentManager().beginTransaction().add(fragment, "detail").commit(); activity.getFragmentManager().executePendingTransactions();
		content=fragment.onCreateContentView(LayoutInflater.from(activity), null, null);
		int width=V.dp(393), height=V.dp(852);
		content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
		content.layout(0, 0, width, height);
		fragment.refresh();
		await(()->((RecyclerView)content.findViewById(R.id.album_grid)).getAdapter().getItemCount()==2 && content.findViewById(R.id.album_settings).isEnabled());
		ShadowLooper.idleMainLooper();
	}
	private void await(BooleanSupplier done) throws Exception{
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
		while(!done.getAsBoolean() && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(5); }
		ShadowLooper.idleMainLooper(); assertTrue("Async fixture finished", done.getAsBoolean());
	}
	private static <T extends View> T find(View root, Class<T> type){
		if(type.isInstance(root)) return type.cast(root);
		if(root instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++){ T found=find(group.getChildAt(i), type); if(found!=null) return found; }
		return null;
	}
	private AlertDialog openSettings(){
		content.findViewById(R.id.album_settings).performClick();
		// The real flow installs the save handler in the dialog's on-show pass; pump the paused main looper
		// so that pass completes before the positive button is exercised, exactly like production ordering.
		ShadowLooper.idleMainLooper();
		return ShadowAlertDialog.getLatestAlertDialog();
	}
	private void setProtection(boolean value) throws Exception{
		AlertDialog dialog=openSettings(); M3Switch control=find(dialog.getWindow().getDecorView(), M3Switch.class); assertNotNull(control); control.setChecked(value);
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); await(()->patch!=null && !dialog.isShowing() && content.findViewById(R.id.album_settings).isEnabled());
	}
	@After public void cleanup() throws Exception{
		if(fragment!=null) fragment.onDestroyView();
		if(lifecycle!=null) lifecycle.pause().stop().destroy();
		if(client!=null && originalInterceptors!=null) field(OkHttpClient.class, "interceptors").set(client, originalInterceptors);
		if(sessions!=null) sessions.remove(accountId); if(manager!=null) field(AccountSessionManager.class, "lastActiveAccountID").set(manager, previousActive);
	}
	@Test public void defaultOwnerSwitchSendsOnlyProtectionAndCanTurnItOff() throws Exception{
		start(false); AlertDialog dialog=openSettings(); View decor=dialog.getWindow().getDecorView();
		assertFalse(find(decor, EditText.class).isEnabled()); assertFalse(find(decor, RadioButton.class).isEnabled());
		assertFalse(find(decor, M3Switch.class).isChecked()); dialog.dismiss();
		setProtection(true); assertEquals(1, patch.size()); assertTrue(patch.get("download_protected").getAsBoolean()); assertFalse(secure());
		assertTrue(find(openSettings().getWindow().getDecorView(), M3Switch.class).isChecked()); ShadowAlertDialog.getLatestAlertDialog().dismiss();
		patch=null; setProtection(false); assertEquals(1, patch.size()); assertFalse(patch.get("download_protected").getAsBoolean());
	}
	@Test public void normalAlbumSendsNameVisibilityAndFlagTogether() throws Exception{
		defaultAlbum=false; start(false); AlertDialog dialog=openSettings(); View decor=dialog.getWindow().getDecorView();
		find(decor, EditText.class).setText("New name"); ((RadioButton)dialog.findViewById(2)).performClick(); find(decor, M3Switch.class).setChecked(true);
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); await(()->patch!=null && content.findViewById(R.id.album_settings).isEnabled());
		assertEquals(3, patch.size()); assertEquals("New name", patch.get("name").getAsString()); assertEquals("public", patch.get("visibility").getAsString()); assertTrue(patch.get("download_protected").getAsBoolean());
	}
	@Test public void rejectedOldRoute400KeepsOldSwitchAndShowsError() throws Exception{ rejectedPatch(400); }
	@Test public void backend503KeepsOldSwitchAndShowsError() throws Exception{ rejectedPatch(503); }
	private void rejectedPatch(int status) throws Exception{
		patchStatus=status; start(false); setProtection(true);
		assertFalse(protectedFlag); assertFalse(find(openSettings().getWindow().getDecorView(), M3Switch.class).isChecked());
		assertNotNull(ShadowToast.getTextOfLatestToast());
	}
	@Test public void oldServerIgnoringFlagNeverPretendsEnabled() throws Exception{
		ignorePatch=true; start(false); setProtection(true); assertFalse(protectedFlag);
		assertEquals(activity.getString(R.string.album_protection_not_confirmed), ShadowToast.getTextOfLatestToast());
		assertFalse(find(openSettings().getWindow().getDecorView(), M3Switch.class).isChecked());
	}
	@Test @SuppressWarnings({"rawtypes", "unchecked"}) public void sharedVisitorHasNoSettingsAndProtectedTilesMakeNoImageRequests() throws Exception{
		owner=false; protectedFlag=true; start(false); assertEquals(View.GONE, content.findViewById(R.id.album_settings).getVisibility()); assertTrue(secure());
		assertEquals(photoReads.get(), securePhotoReads.get()); // Secure before any photo GET.
		assertNull(content.findViewById(R.id.album_cover).getTag(me.grishka.appkit.R.id.tag_image_load_task));
		RecyclerView grid=content.findViewById(R.id.album_grid); RecyclerView.Adapter adapter=grid.getAdapter(); RecyclerView.ViewHolder tile=adapter.createViewHolder(grid, 1); adapter.bindViewHolder(tile, 1);
		ImageView image=tile.itemView.findViewById(R.id.album_photo); assertNotNull(image.getDrawable()); assertNull(image.getTag(me.grishka.appkit.R.id.tag_image_load_task));
		// A parentless view cannot dispatch showContextMenu. Use a plain container: adding a foreign
		// child to the RecyclerView itself would crash its recycle pass (no ViewHolder for that child).
		android.widget.FrameLayout host=new android.widget.FrameLayout(activity); host.addView(tile.itemView);
		assertTrue(tile.itemView.hasOnClickListeners()); assertFalse(tile.itemView.performLongClick());
		fragment.onHidden(); assertFalse(secure()); assertEquals(0, grid.getAdapter().getItemCount());
	}
	@Test public void preexistingSecureFlagSurvivesHiddenAndDestroy() throws Exception{
		owner=false; protectedFlag=true; start(true); assertTrue(secure()); fragment.onHidden(); assertTrue(secure()); fragment.onDestroyView(); fragment=null; assertTrue(secure());
	}
	@Test public void concurrentSecureScopesRestoreOnlyAfterLastLeaseAndCloseIsIdempotent(){
		lifecycle=Robolectric.buildActivity(Activity.class).setup(); activity=lifecycle.get(); assertFalse(secure());
		AlbumSecureWindow.Scope first=AlbumSecureWindow.acquire(activity.getWindow()), second=AlbumSecureWindow.acquire(activity.getWindow());
		assertTrue(secure()); first.close(); first.close(); assertTrue(secure()); second.close(); assertFalse(secure());
		activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); AlbumSecureWindow.acquire(activity.getWindow()).close(); assertTrue(secure());
	}
	@Test public void creationHasNoProtectionControlAndDefaultsOff(){
		lifecycle=Robolectric.buildActivity(Activity.class).create(); activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.start().resume();
		final AlbumUi.EditValues[] submitted=new AlbumUi.EditValues[1]; AlertDialog dialog=AlbumUi.edit(activity, null, values->submitted[0]=values);
		ShadowLooper.idleMainLooper(); // Creation dialog wires its submit handler on show; run that pass first.
		assertNull(find(dialog.getWindow().getDecorView(), M3Switch.class)); find(dialog.getWindow().getDecorView(), EditText.class).setText("New album"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertNotNull(submitted[0]); assertFalse(submitted[0].downloadProtected);
	}
	@Test @SuppressWarnings({"rawtypes", "unchecked"}) public void profileProtectedCoverMakesNoImageRequest() throws Exception{
		owner=false; protectedFlag=true; lifecycle=Robolectric.buildActivity(Activity.class).create(); activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.start().resume();
		AlbumListFragment list=new AlbumListFragment(); Bundle args=new Bundle(); args.putString("account", accountId); args.putString("ownerId", "43"); args.putBoolean("noAutoLoad", true); list.setArguments(args);
		activity.getFragmentManager().beginTransaction().add(list, "list").commit(); activity.getFragmentManager().executePendingTransactions(); list.onCreateContentView(LayoutInflater.from(activity), null, null); list.refresh();
		await(()->list.getRecyclerView().getAdapter().getItemCount()==1);
		RecyclerView grid=list.getRecyclerView(); RecyclerView.Adapter adapter=grid.getAdapter(); RecyclerView.ViewHolder row=adapter.createViewHolder(grid, 0); adapter.bindViewHolder(row, 0);
		assertNull(row.itemView.findViewById(R.id.album_cover).getTag(me.grishka.appkit.R.id.tag_image_load_task)); assertFalse(secure()); list.onDestroyView();
	}
}
