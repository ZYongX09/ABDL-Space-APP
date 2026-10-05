package org.joinmastodon.android.fragments.albums;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.model.albums.AlbumModels;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.grishka.appkit.utils.V;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AlbumScreensBehaviorTest{
	private AccountSessionManager manager;
	private Map<String, AccountSession> sessions;
	private AccountSession session;
	private String accountId, previousActive;
	private OkHttpClient client;
	private Object originalInterceptors;
	private final java.util.concurrent.atomic.AtomicInteger albumReads=new java.util.concurrent.atomic.AtomicInteger(), deleteCalls=new java.util.concurrent.atomic.AtomicInteger();
	private boolean withPhoto;
	private java.util.concurrent.CountDownLatch firstAlbumEntered, releaseFirstAlbum;
	private static Field field(Class<?> type, String name) throws Exception{ Field field=type.getDeclaredField(name); field.setAccessible(true); return field; }
	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		manager=AccountSessionManager.getInstance(); sessions=(Map<String, AccountSession>)field(AccountSessionManager.class, "sessions").get(manager); previousActive=manager.getLastActiveAccountID();
		var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true); session=constructor.newInstance();
		session.domain="album-screen-offline.example.test"; session.self=new Account(); session.self.id="42"; session.token=new Token(); session.token.accessToken="screen-fixture-token";
		accountId=session.getID(); sessions.put(accountId, session); field(AccountSessionManager.class, "lastActiveAccountID").set(manager, accountId);
		client=(OkHttpClient)field(MastodonAPIController.class, "sensitiveHttpClient").get(null); originalInterceptors=field(OkHttpClient.class, "interceptors").get(client);
		field(OkHttpClient.class, "interceptors").set(client, List.of((okhttp3.Interceptor)chain->{
			if(!session.domain.equals(chain.request().url().host())) throw new IOException("Unexpected outbound host");
			String path=chain.request().url().encodedPath(); String body;
			if("DELETE".equals(chain.request().method())){
				assertTrue(path.endsWith("/photos/photo") || path.endsWith("/album"));
				assertTrue(chain.request().body().contentType().toString().startsWith("application/json"));
				okio.Buffer json=new okio.Buffer(); chain.request().body().writeTo(json); assertEquals("{}", json.readUtf8());
				deleteCalls.incrementAndGet(); withPhoto=false; body="{\"deleted\":true}";
			}else if(path.endsWith("/quota")) body="{\"limit_bytes\":1073741824,\"remaining_bytes\":1073741824,\"sponsor_active\":true,\"original_upload_allowed\":true}";
			else if(path.endsWith("/photos")) body=withPhoto ? "{\"photos\":[{\"id\":\"photo\",\"album_id\":\"album\",\"preview_url\":\"file:///tmp/album-offline-missing.jpg\",\"sort_at\":1791158401,\"uploaded_at\":1791158401,\"is_owner\":true,\"width\":64,\"height\":48}],\"has_more\":false}" : "{\"photos\":[],\"has_more\":false}";
			else if(path.endsWith("/albums/") || path.endsWith("/albums")){
				int count=albumReads.incrementAndGet();
				if(count==1 && firstAlbumEntered!=null){ firstAlbumEntered.countDown(); try{ releaseFirstAlbum.await(5, TimeUnit.SECONDS); }catch(InterruptedException canceled){ Thread.currentThread().interrupt(); throw new IOException("Canceled"); } }
				body="{\"albums\":["+albumJson()+"],\"has_more\":false}";
			}
			else if(path.endsWith("/album")) body="{\"album\":"+albumJson()+"}";
			else if(path.endsWith("/import-history")) body="{\"imported\":0,\"skipped\":0,\"remaining\":false}";
			else throw new IOException("Unexpected fixture route");
			return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(ResponseBody.create(MediaType.get("application/json"), body)).build();
		}));
	}
	@After public void cleanup() throws Exception{
		if(releaseFirstAlbum!=null) releaseFirstAlbum.countDown();
		if(client!=null && originalInterceptors!=null) field(OkHttpClient.class, "interceptors").set(client, originalInterceptors);
		if(sessions!=null) sessions.remove(accountId); if(manager!=null) field(AccountSessionManager.class, "lastActiveAccountID").set(manager, previousActive);
	}
	private static String albumJson(){ return "{\"id\":\"album\",\"owner_id\":42,\"name\":\"宝宝相册\",\"visibility\":\"private\",\"is_default\":true,\"photo_count\":0,\"can_upload\":true,\"is_owner\":true}"; }

	@Test public void draftRestoresDescriptionCustomSecondAndSelectedAlbum(){
		AlbumUploadDraft draft=new AlbumUploadDraft(); assertTrue(draft.select("album", "第一天", "shared")); draft.description="成长记录";
		draft.customCapturedAt=true; draft.customTime="2026-10-05 12:34:56"; draft.addPhoto("content://fixture/first");
		AlbumUploadDraft restored=AlbumUploadDraft.restore(draft.save());
		assertEquals("album", restored.albumId); assertEquals("shared", restored.visibility); assertEquals("成长记录", restored.description); assertEquals(draft.photos, restored.photos);
		assertEquals(Long.valueOf(java.time.LocalDateTime.of(2026,10,5,12,34,56).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()), restored.capturedAt(ZoneId.of("Asia/Shanghai")));
		assertTrue(restored.descriptionValid()); assertFalse(restored.select("../escape", "name", "private")); assertEquals("album", restored.albumId);
	}
	@Test public void draftRejectsInvalidDatesDescriptionAndExcessSelection(){
		AlbumUploadDraft draft=new AlbumUploadDraft(); draft.description="a".repeat(3000); assertTrue(draft.descriptionValid()); draft.description+="b"; assertFalse(draft.descriptionValid());
		draft.customCapturedAt=true;
		for(String date:List.of("2026-02-30 12:00:00", "2026-10-05 12:34", "invalid")){
			draft.customTime=date; try{ draft.capturedAt(ZoneId.of("UTC")); fail(date); }catch(RuntimeException expected){}
		}
		for(int i=0;i<20;i++) assertTrue(draft.addPhoto("content://fixture/"+i)); assertFalse(draft.addPhoto("content://fixture/20")); assertFalse(draft.addPhoto("content://fixture/1")); assertFalse(draft.addPhoto("https://remote.test/image"));
	}
	@Test public void originalChoiceNeedsSponsorAndEveryKnownSupportedSmallSource(){
		AlbumModels.StorageQuota quota=new AlbumModels.StorageQuota(); quota.sponsorActive=true; quota.originalUploadAllowed=true;
		List<AlbumUploadDraft.SourceInfo> small=List.of(new AlbumUploadDraft.SourceInfo(123, "image/jpeg"), new AlbumUploadDraft.SourceInfo(456, "image/png"));
		assertTrue(AlbumUploadDraft.originalAllowed(quota, small)); quota.sponsorActive=false; assertFalse(AlbumUploadDraft.originalAllowed(quota, small)); quota.sponsorActive=true;
		for(AlbumUploadDraft.SourceInfo invalid:List.of(new AlbumUploadDraft.SourceInfo(-1, "image/jpeg"), new AlbumUploadDraft.SourceInfo(20L*1024*1024, "image/jpeg"), new AlbumUploadDraft.SourceInfo(100, "image/avif"))){
			List<AlbumUploadDraft.SourceInfo> sources=new ArrayList<>(small); sources.add(invalid); assertFalse(AlbumUploadDraft.originalAllowed(quota, sources));
		}
	}
	@Test public void localDayAndSecondGroupsRespectDeviceZone(){
		long time=java.time.Instant.parse("2026-10-04T16:00:01Z").getEpochSecond();
		assertEquals("2026-10-05 00:00:01", AlbumDetailFragment.groupLabel(time, true, ZoneId.of("Asia/Shanghai")));
		assertNotEquals(AlbumDetailFragment.groupLabel(time, false, ZoneId.of("UTC")), AlbumDetailFragment.groupLabel(time, false, ZoneId.of("Asia/Shanghai")));
	}
	@Test public void uploaderEditorPersistsStateAcrossActualViewRecreation() throws Exception{
		var activity=Robolectric.buildActivity(Activity.class).create(); activity.get().setTheme(R.style.Theme_Mastodon_Light); activity.start().resume();
		AlbumUploadFragment fragment=new AlbumUploadFragment(); Bundle args=new Bundle(); args.putString("account", accountId); args.putString("albumId", "album"); fragment.setArguments(args);
		activity.get().getFragmentManager().beginTransaction().add(fragment, "upload").commit(); activity.get().getFragmentManager().executePendingTransactions();
		View first=fragment.onCreateContentView(LayoutInflater.from(activity.get()), null, null);
		((EditText)first.findViewById(R.id.album_description)).setText("保留编辑内容");
		first.findViewById(R.id.album_upload_time_row).performClick(); ShadowLooper.idleMainLooper(); android.app.AlertDialog time=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
		((android.widget.RadioButton)time.findViewById(R.id.album_custom_time)).performClick(); ((EditText)time.findViewById(R.id.album_time_input)).setText("2026-10-05 12:34:56"); time.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
		Bundle saved=new Bundle(); fragment.onSaveInstanceState(saved); assertEquals("保留编辑内容", saved.getBundle("draft").getString("description")); assertEquals("2026-10-05 12:34:56", saved.getBundle("draft").getString("customTime"));
		fragment.onDestroyView(); View restored=fragment.onCreateContentView(LayoutInflater.from(activity.get()), null, saved);
		assertEquals("保留编辑内容", ((EditText)restored.findViewById(R.id.album_description)).getText().toString()); assertEquals("2026-10-05 12:34:56", ((TextView)restored.findViewById(R.id.album_upload_time_value)).getText().toString());
		assertNull(restored.findViewById(R.id.album_time_input)); // Input is a real dialog control, not a hidden page field.
		fragment.onDestroyView(); activity.pause().stop().destroy();
	}
	@Test public void embeddedProfileVisibilityCancelsOffscreenAndReloadsOnReturn() throws Exception{
		var activity=Robolectric.buildActivity(Activity.class).create(); activity.get().setTheme(R.style.Theme_Mastodon_Light); activity.start().resume();
		AlbumListFragment fragment=new AlbumListFragment(); Bundle args=new Bundle(); args.putString("account", accountId); args.putString("ownerId", "43"); args.putBoolean("__is_tab", true); fragment.setArguments(args);
		activity.get().getFragmentManager().beginTransaction().add(fragment, "albums").commit(); activity.get().getFragmentManager().executePendingTransactions();
		fragment.onCreateContentView(LayoutInflater.from(activity.get()), null, null); ShadowLooper.idleMainLooper(); assertEquals(0, albumReads.get());
		firstAlbumEntered=new java.util.concurrent.CountDownLatch(1); releaseFirstAlbum=new java.util.concurrent.CountDownLatch(1);
		fragment.setProfileVisible(true); assertTrue(firstAlbumEntered.await(5, TimeUnit.SECONDS)); fragment.setProfileVisible(false); releaseFirstAlbum.countDown();
		Thread.sleep(50); ShadowLooper.idleMainLooper(); assertEquals(0, fragment.getRecyclerView().getAdapter().getItemCount()); assertEquals(View.INVISIBLE, fragment.getRecyclerView().getVisibility());
		fragment.setProfileVisible(true); long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
		while(fragment.getRecyclerView().getAdapter().getItemCount()==0 && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(5); }
		assertEquals(2, albumReads.get()); assertEquals(1, fragment.getRecyclerView().getAdapter().getItemCount()); assertEquals(View.VISIBLE, fragment.getRecyclerView().getVisibility());
		fragment.setProfileVisible(false); assertEquals(0, fragment.getRecyclerView().getAdapter().getItemCount()); fragment.onDestroyView(); activity.pause().stop().destroy();
	}
	@Test @SuppressWarnings({"rawtypes", "unchecked"}) public void ownerPhotoLongPressRequiresExplicitConfirmationAndCancelDoesNotDelete() throws Exception{
		withPhoto=true;
		var activity=Robolectric.buildActivity(Activity.class).create(); activity.get().setTheme(R.style.Theme_Mastodon_Light); activity.start().resume();
		AlbumDetailFragment fragment=new AlbumDetailFragment(); Bundle args=new Bundle(); args.putString("account", accountId); args.putString("albumId", "album"); fragment.setArguments(args);
		activity.get().getFragmentManager().beginTransaction().add(fragment, "detail").commit(); activity.get().getFragmentManager().executePendingTransactions();
		View content=fragment.onCreateContentView(LayoutInflater.from(activity.get()), null, null); RecyclerView grid=content.findViewById(R.id.album_grid); fragment.refresh();
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
		while(grid.getAdapter().getItemCount()<2 && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(5); }
		assertEquals(2, grid.getAdapter().getItemCount()); RecyclerView.Adapter adapter=grid.getAdapter();
		RecyclerView.ViewHolder photo=adapter.createViewHolder(grid, 1); adapter.bindViewHolder(photo, 1);
		assertTrue(photo.itemView.performLongClick()); android.app.AlertDialog confirmation=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(confirmation); assertEquals(0, deleteCalls.get());
		confirmation.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick(); ShadowLooper.idleMainLooper(); assertEquals(0, deleteCalls.get()); assertEquals(2, grid.getAdapter().getItemCount());
		assertTrue(photo.itemView.performLongClick()); confirmation=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog(); confirmation.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
		deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
		while(deleteCalls.get()==0 && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(5); }
		assertEquals(1, deleteCalls.get()); fragment.onDestroyView(); activity.pause().stop().destroy();
	}
	@Test public void defaultAlbumSettingsNeverOfferAlbumDelete(){
		var activity=Robolectric.buildActivity(Activity.class).create(); activity.get().setTheme(R.style.Theme_Mastodon_Light); activity.start().resume(); Context context=activity.get();
		AlbumModels.Album album=new AlbumModels.Album(); album.name="宝宝相册"; album.visibility="private"; album.isDefault=true;
		android.app.AlertDialog dialog=AlbumUi.edit(context, album, values->fail("Default album mutation"), ()->fail("Default album delete"));
		assertFalse(containsText(dialog.getWindow().getDecorView(), context.getString(R.string.album_delete_album))); dialog.dismiss(); activity.pause().stop().destroy();
	}
	private static boolean containsText(View view, String text){
		if(view instanceof TextView label && text.contentEquals(label.getText())) return true;
		if(view instanceof android.view.ViewGroup group) for(int i=0;i<group.getChildCount();i++) if(containsText(group.getChildAt(i), text)) return true;
		return false;
	}
	@Test public void actualM3LayoutsRenderOfflineInLightAndDark() throws Exception{
		for(int theme:new int[]{R.style.Theme_Mastodon_Light, R.style.Theme_Mastodon_Dark}){
			Context context=new ContextThemeWrapper(MastodonApp.context, theme); String mode=theme==R.style.Theme_Mastodon_Light ? "light" : "dark";
			View list=LayoutInflater.from(context).inflate(R.layout.album_list, null, false); RecyclerView grid=list.findViewById(R.id.album_grid); grid.setLayoutManager(new GridLayoutManager(context, 2)); grid.setAdapter(new FixtureAlbums(context));
			((TextView)list.findViewById(R.id.album_quota)).setText("已用 0 B / 1 GiB · 可用 1 GiB"); list.findViewById(R.id.album_quota).setVisibility(View.VISIBLE); capture(list, "album-list-"+mode);
			View detail=LayoutInflater.from(context).inflate(R.layout.album_detail, null, false); ((TextView)detail.findViewById(R.id.album_name)).setText("宝宝相册"); ((TextView)detail.findViewById(R.id.album_meta)).setText("0 张照片 · 私密");
			((ImageView)detail.findViewById(R.id.album_cover)).setImageDrawable(AlbumUi.gradient(context, "first")); detail.findViewById(R.id.album_upload).setVisibility(View.VISIBLE); ((TextView)detail.findViewById(R.id.album_state)).setText(R.string.album_empty_photos); detail.findViewById(R.id.album_state).setVisibility(View.VISIBLE); capture(detail, "album-detail-"+mode);
			// Upload screenshots are captured from the attached full fragment (including its real Toolbar)
			// by AlbumUploadUiBehaviorTest, not a disconnected raw layout with fabricated state.
		}
	}
	private static void capture(View view, String name) throws Exception{
		try(var lifecycle=Robolectric.buildActivity(Activity.class)){
			Activity activity=lifecycle.get(); activity.setTheme(name.endsWith("dark") ? R.style.Theme_Mastodon_Dark : R.style.Theme_Mastodon_Light);
			lifecycle.setup().visible(); activity.setContentView(view); ShadowLooper.idleMainLooper();
			int width=V.dp(393), height=V.dp(852);
			for(int pass=0;pass<3;pass++){
				view.forceLayout(); view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
				view.layout(0,0,width,height); view.getViewTreeObserver().dispatchOnPreDraw(); ShadowLooper.idleMainLooper();
			}
			assertTrue(view.getMeasuredWidth()>0); Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); view.draw(new Canvas(bitmap));
			File directory=new File("/tmp/baby-albums-verification/screens"); assertTrue(directory.isDirectory() || directory.mkdirs());
			try(FileOutputStream output=new FileOutputStream(new File(directory, name+".png"))){ assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output)); } bitmap.recycle();
		}
	}
	private static class FixtureAlbums extends RecyclerView.Adapter<FixtureHolder>{
		final Context context; FixtureAlbums(Context context){ this.context=context; }
		@Override public FixtureHolder onCreateViewHolder(android.view.ViewGroup parent,int type){ return new FixtureHolder(LayoutInflater.from(context).inflate(R.layout.album_card,parent,false)); }
		@Override public void onBindViewHolder(FixtureHolder holder,int position){ ((TextView)holder.itemView.findViewById(R.id.album_name)).setText(position==0 ? "宝宝相册" : "成长时光"); ((TextView)holder.itemView.findViewById(R.id.album_meta)).setText(position==0 ? "0 张照片 · 私密" : "0 张照片 · 共享"); ((ImageView)holder.itemView.findViewById(R.id.album_cover)).setImageDrawable(AlbumUi.gradient(context,String.valueOf(position))); AlbumUi.rounded(holder.itemView); }
		@Override public int getItemCount(){ return 2; }
	}
	private static class FixtureHolder extends RecyclerView.ViewHolder{ FixtureHolder(View view){ super(view); } }
}
