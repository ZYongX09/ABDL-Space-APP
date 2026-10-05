package org.joinmastodon.android.fragments.albums;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Fragment;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.albums.AlbumUploader;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.sponsors.SponsorCenterFragment;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.InstanceV1;
import org.joinmastodon.android.model.Token;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.Nav;
import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Real attached fragment/UI interactions. All API requests and selected image bytes stay local. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class, shadows=AlbumUploadUiBehaviorTest.RecordingNav.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AlbumUploadUiBehaviorTest{
	private AccountSessionManager manager;
	private Map<String, AccountSession> sessions;
	private Map<String, Instance> instances;
	private AccountSession session;
	private Instance previousInstance;
	private String accountId, previousActive;
	private OkHttpClient sensitiveClient, originalUploadClient;
	private Object originalSensitiveInterceptors;
	private final List<Host> hosts=new ArrayList<>();
	private final List<File> localImages=new ArrayList<>();
	private final List<AlbumUploader> tasks=new ArrayList<>();
	private final List<String> requests=new CopyOnWriteArrayList<>(), authorizations=new CopyOnWriteArrayList<>();
	private volatile boolean sponsorActive=true;
	private volatile CountDownLatch authorizationEntered, releaseAuthorization;
	private File source;
	private static Field field(Class<?> type, String name) throws Exception{ Field field=type.getDeclaredField(name); field.setAccessible(true); return field; }

	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context); RecordingNav.reset();
		manager=AccountSessionManager.getInstance(); sessions=(Map<String, AccountSession>)field(AccountSessionManager.class, "sessions").get(manager); previousActive=manager.getLastActiveAccountID();
		var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true); session=constructor.newInstance();
		session.domain="album-upload-ui-offline.example.test"; session.self=new Account(); session.self.id="42"; session.token=new Token(); session.token.accessToken="local-ui-fixture-token";
		accountId=session.getID(); sessions.put(accountId, session); field(AccountSessionManager.class, "lastActiveAccountID").set(manager, accountId);
		instances=(Map<String, Instance>)field(AccountSessionManager.class, "instances").get(manager);
		InstanceV1 instance=new InstanceV1(); instance.uri=session.domain; instance.normalizedUri=session.domain; instance.version="4.5.3"; instance.title="Local fixture"; instance.description="Offline"; instance.email="local@example.test";
		instance.configuration=new Instance.Configuration(); instance.configuration.mediaAttachments=new Instance.MediaAttachmentsConfiguration(); instance.configuration.mediaAttachments.imageMatrixLimit=2_073_600; instance.configuration.mediaAttachments.imageSizeLimit=10*1024*1024;
		instance.configuration.mediaAttachments.supportedMimeTypes=List.of("image/jpeg", "image/png", "image/webp", "image/gif"); previousInstance=instances.put(session.domain, instance);
		org.robolectric.shadows.ShadowMimeTypeMap mime=org.robolectric.shadow.api.Shadow.extract(android.webkit.MimeTypeMap.getSingleton()); mime.addExtensionMimeTypeMapping("jpg", "image/jpeg"); mime.addExtensionMimeTypeMapping("png", "image/png");
		source=localPhoto("warm", Color.rgb(227, 185, 124));
		sensitiveClient=(OkHttpClient)field(MastodonAPIController.class, "sensitiveHttpClient").get(null); originalSensitiveInterceptors=field(OkHttpClient.class, "interceptors").get(sensitiveClient);
		field(OkHttpClient.class, "interceptors").set(sensitiveClient, List.of((okhttp3.Interceptor)chain->respond(chain.request())));
		originalUploadClient=MastodonAPIController.getHttpClient(); field(MastodonAPIController.class, "httpClient").set(null, new OkHttpClient.Builder().addInterceptor(chain->respond(chain.request())).build());
		MastodonApp.context.getSharedPreferences("album_upload_tasks", Context.MODE_PRIVATE).edit().remove(accountId).commit();
	}
	@After public void cleanup() throws Exception{
		if(releaseAuthorization!=null) releaseAuthorization.countDown();
		for(Host host:hosts) if(host.fragment.isAdded()) host.fragment.onHidden();
		for(AlbumUploader task:tasks){ task.setListener(null); task.cancel(); await(()->!task.isRunning()); task.discard(); }
		for(Host host:hosts) host.lifecycle.pause().stop().destroy();
		if(sensitiveClient!=null && originalSensitiveInterceptors!=null) field(OkHttpClient.class, "interceptors").set(sensitiveClient, originalSensitiveInterceptors);
		if(originalUploadClient!=null) field(MastodonAPIController.class, "httpClient").set(null, originalUploadClient);
		if(instances!=null){ if(previousInstance==null) instances.remove(session.domain); else instances.put(session.domain, previousInstance); }
		if(sessions!=null) sessions.remove(accountId); if(manager!=null) field(AccountSessionManager.class, "lastActiveAccountID").set(manager, previousActive);
		MastodonApp.context.getSharedPreferences("album_upload_tasks", Context.MODE_PRIVATE).edit().remove(accountId).commit();
		for(File image:localImages) image.delete();
	}
	private Response respond(Request request) throws IOException{
		if(!session.domain.equals(request.url().host())) throw new IOException("Unexpected network host: "+request.url().host());
		String path=request.url().encodedPath(); requests.add(request.method()+" "+path);
		if(path.endsWith("/quota")) return json(request, 200, "{\"limit_bytes\":1073741824,\"used_bytes\":12582912,\"remaining_bytes\":1061158912,\"sponsor_active\":"+sponsorActive+",\"original_upload_allowed\":"+sponsorActive+"}");
		if(path.endsWith("/album")) return json(request, 200, "{\"album\":"+albumJson()+"}");
		if(path.endsWith("/albums") || path.endsWith("/albums/")) return json(request, 200, "{\"albums\":["+albumJson()+"],\"has_more\":false}");
		if(path.endsWith("/authorize")){
			okio.Buffer body=new okio.Buffer(); request.body().writeTo(body); authorizations.add(body.readUtf8());
			CountDownLatch entered=authorizationEntered, release=releaseAuthorization;
			if(entered!=null){ entered.countDown(); try{ if(!release.await(5, TimeUnit.SECONDS)) throw new IOException("Local authorizer timed out"); }catch(InterruptedException canceled){ Thread.currentThread().interrupt(); throw new IOException("Paused local task"); } }
			return json(request, 503, "{\"error\":\"Local retryable authorization error\",\"code\":\"fixture_unavailable\"}");
		}
		throw new IOException("Unexpected fixture route: "+path);
	}
	private String albumJson(){ return "{\"id\":\"album\",\"owner_id\":42,\"name\":\"宝宝相册\",\"visibility\":\"private\",\"is_default\":true,\"can_upload\":true,\"is_owner\":true,\"cover_url\":\""+Uri.fromFile(source)+"\"}"; }
	private static Response json(Request request, int code, String body){ return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Local fixture").body(ResponseBody.create(MediaType.get("application/json"), body)).build(); }
	private File localPhoto(String name, int background) throws Exception{
		File file=File.createTempFile("album_ui_"+name, ".jpg", MastodonApp.context.getCacheDir()); localImages.add(file);
		// An actual local JPEG, not a fabricated ImageView drawable or a production/network image.
		Bitmap bitmap=Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888); Canvas canvas=new Canvas(bitmap); Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); canvas.drawColor(background);
		paint.setColor(Color.rgb(205, 141, 83)); canvas.drawRect(0, 235, 360, 360, paint); paint.setColor(Color.rgb(248, 225, 178)); canvas.drawRoundRect(28, 24, 332, 324, 20, 20, paint);
		paint.setColor(Color.rgb(166, 108, 68)); canvas.drawCircle(127, 105, 32, paint); canvas.drawCircle(233, 105, 32, paint); canvas.drawOval(103, 94, 257, 257, paint);
		paint.setColor(Color.rgb(206, 152, 94)); canvas.drawOval(110, 222, 250, 316, paint); canvas.drawOval(139, 171, 220, 231, paint);
		paint.setColor(Color.rgb(60, 43, 34)); canvas.drawCircle(146, 158, 7, paint); canvas.drawCircle(214, 158, 7, paint); canvas.drawOval(170, 187, 190, 201, paint);
		paint.setColor(Color.rgb(123, 177, 171)); canvas.drawRoundRect(113, 245, 246, 275, 10, 10, paint);
		try(FileOutputStream output=new FileOutputStream(file)){ assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)); } bitmap.recycle();
		// Seed the real loader cache with the decoded file so captures are deterministic and offline.
		ImageCache.getInstance(MastodonApp.context).put(new UrlImageLoaderRequest(Uri.fromFile(file)), new BitmapDrawable(MastodonApp.context.getResources(), BitmapFactory.decodeFile(file.getAbsolutePath())));
		return file;
	}
	private Host open(int theme, String description, List<File> photos) throws Exception{
		ActivityController<FragmentStackActivity> lifecycle=Robolectric.buildActivity(FragmentStackActivity.class); lifecycle.get().setTheme(theme); lifecycle.create().start().resume().visible();
		AlbumUploadFragment fragment=new AlbumUploadFragment(); Bundle args=new Bundle(); args.putString("account", accountId); args.putString("albumId", "album"); args.putString("description", description);
		ArrayList<String> uris=new ArrayList<>(); for(File photo:photos) uris.add(Uri.fromFile(photo).toString()); args.putStringArrayList("photoUris", uris); fragment.setArguments(args);
		lifecycle.get().showFragment(fragment); lifecycle.get().getFragmentManager().executePendingTransactions(); Host host=new Host(lifecycle, fragment); hosts.add(host);
		await(()->{ try{ return field(AlbumUploadFragment.class, "selected").get(fragment)!=null && !(boolean)field(AlbumUploadFragment.class, "refreshing").get(fragment) && !(boolean)field(AlbumUploadFragment.class, "checkingSources").get(fragment); }catch(Exception invalid){ throw new AssertionError(invalid); } });
		layout(host); return host;
	}
	private static void await(BooleanSupplier finished) throws Exception{
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
		while(!finished.getAsBoolean() && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(5); }
		ShadowLooper.idleMainLooper(); assertTrue("Offline operation must settle", finished.getAsBoolean());
	}
	private static void layout(Host host){
		View root=host.root(); int width=V.dp(393), height=V.dp(852);
		try{
			var constructor=WindowInsets.class.getDeclaredConstructor(Rect.class); constructor.setAccessible(true);
			host.fragment.onApplyWindowInsets(constructor.newInstance(new Rect(0, V.dp(24), 0, V.dp(24))));
		}catch(ReflectiveOperationException invalid){ throw new AssertionError(invalid); }
		for(int pass=0; pass<3; pass++){
			root.forceLayout(); root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); root.layout(0, 0, width, height);
			root.getViewTreeObserver().dispatchOnPreDraw(); ShadowLooper.idleMainLooper();
		}
	}
	private static void capture(Host host, String name) throws Exception{
		layout(host); View root=host.root(); assertTrue(root.isAttachedToWindow()); assertEquals(View.VISIBLE, root.getVisibility());
		Bitmap bitmap=Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888); root.draw(new Canvas(bitmap)); File directory=new File("/tmp/baby-albums-fixes/screens"); assertTrue(directory.isDirectory() || directory.mkdirs());
		try(FileOutputStream output=new FileOutputStream(new File(directory, name+".png"))){ assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); } bitmap.recycle();
	}
	private static final class Host{
		final ActivityController<FragmentStackActivity> lifecycle; final AlbumUploadFragment fragment;
		Host(ActivityController<FragmentStackActivity> lifecycle, AlbumUploadFragment fragment){ this.lifecycle=lifecycle; this.fragment=fragment; }
		View root(){ return fragment.getView(); }
		<T extends View> T view(int id){ return root().findViewById(id); }
		RecyclerView grid(){ return view(R.id.album_preview_grid); }
	}
	private static int countId(View root, int id){ int count=root.getId()==id ? 1 : 0; if(root instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) count+=countId(group.getChildAt(i), id); return count; }
	private static String text(View root, int id){ return ((TextView)root.findViewById(id)).getText().toString(); }
	private static AlertDialog latest(){ ShadowLooper.idleMainLooper(); AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(dialog); assertTrue(dialog.isShowing()); return dialog; }
	private static Bundle saved(AlbumUploadFragment fragment){ Bundle saved=new Bundle(); fragment.onSaveInstanceState(saved); return saved.getBundle("draft"); }
	private static boolean containsText(View root, String expected){
		if(root instanceof TextView label && label.getText().toString().contains(expected)) return true;
		if(root instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) if(containsText(group.getChildAt(i), expected)) return true; return false;
	}

	@Test public void singleActualToolbarAndSelectedPhotoCaptureInLightAndDark() throws Exception{
		for(int theme:new int[]{R.style.Theme_Mastodon_Light, R.style.Theme_Mastodon_Dark}){
			Host host=open(theme, "记录成长的每一个瞬间", List.of(source)); View root=host.root();
			host.fragment.onUpdateToolbar(); host.fragment.onUpdateToolbar(); layout(host);
			android.widget.Toolbar toolbar=root.findViewById(R.id.toolbar);
			assertEquals(1, countId(root, R.id.album_upload_header)); assertNull(toolbar.getNavigationIcon()); assertEquals("", toolbar.getTitle().toString());
			assertEquals("传相册", text(root, R.id.album_upload_header_title)); assertEquals("取消", text(root, R.id.album_upload_close)); assertEquals("上传", text(root, R.id.album_upload));
			assertTrue(host.<Button>view(R.id.album_upload).isEnabled()); assertTrue(host.view(R.id.album_upload).getHeight()>=V.dp(48)); assertTrue(host.view(R.id.album_upload_close).getHeight()>=V.dp(48));
			assertEquals("宝宝相册", text(root, R.id.album_upload_album_name)); assertEquals("私密", text(root, R.id.album_permissions)); assertEquals("高清", text(root, R.id.album_upload_quality_value)); assertEquals("使用上传时间", text(root, R.id.album_upload_time_value));
			assertEquals(2, host.grid().getAdapter().getItemCount()); assertNotNull(host.view(R.id.album_pick)); assertNull(root.findViewById(R.id.album_original)); assertNull(root.findViewById(R.id.album_time_input));
			RecyclerView.ViewHolder photo=host.grid().findViewHolderForAdapterPosition(0); assertNotNull(photo); assertEquals(photo.itemView.getWidth(), photo.itemView.getHeight());
			assertTrue(((ImageView)photo.itemView.findViewById(R.id.album_photo)).getDrawable() instanceof BitmapDrawable); assertTrue(host.<ImageView>view(R.id.album_upload_cover).getDrawable() instanceof BitmapDrawable);
			assertFalse(containsText(root, host.lifecycle.get().getString(R.string.album_privacy_help))); assertFalse(containsText(root, host.lifecycle.get().getString(R.string.album_pick_hint)));
			capture(host, "album-upload-selected-"+(theme==R.style.Theme_Mastodon_Dark ? "dark" : "light"));
			host.fragment.onHidden();
		}
	}
	@Test public void qualityDialogRechecksFreshSponsorAndPrivacyIsReadOnly() throws Exception{
		Host host=open(R.style.Theme_Mastodon_Light, "", List.of(source)); host.view(R.id.album_upload_quality_row).performClick(); AlertDialog quality=latest();
		assertTrue(quality.findViewById(R.id.album_original).isEnabled()); ((RadioButton)quality.findViewById(R.id.album_original)).performClick(); quality.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		assertEquals("原图", text(host.root(), R.id.album_upload_quality_value)); assertEquals("original", saved(host.fragment).getString("quality"));
		host.fragment.onHidden(); sponsorActive=false; host.fragment.onShown();
		host.view(R.id.album_upload_quality_row).performClick(); quality=latest(); assertFalse(quality.findViewById(R.id.album_original).isEnabled()); quality.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
		await(()->"高清".equals(text(host.root(), R.id.album_upload_quality_value))); host.view(R.id.album_upload_quality_row).performClick(); quality=latest(); assertFalse(quality.findViewById(R.id.album_original).isEnabled()); assertTrue(containsText(quality.getWindow().getDecorView(), "20 MiB")); quality.dismiss();
		host.view(R.id.album_upload_permissions_row).performClick(); AlertDialog permission=latest(); assertTrue(containsText(permission.getWindow().getDecorView(), "默认宝宝相册始终私密")); assertTrue(containsText(permission.getWindow().getDecorView(), "本页仅展示"));
		assertNull(permission.findViewById(R.id.album_custom_time)); permission.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertEquals("private", saved(host.fragment).getString("visibility"));
		assertTrue(requests.stream().allMatch(route->route.startsWith("GET ")));
	}
	@Test public void originalModalRejectsUnknownUnsupportedAndExactTwentyMiBSources() throws Exception{
		Host host=open(R.style.Theme_Mastodon_Dark, "", List.of(source));
		File oversized=File.createTempFile("album_ui_exact20", ".jpg", MastodonApp.context.getCacheDir()); localImages.add(oversized); Files.copy(source.toPath(), oversized.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); try(RandomAccessFile bytes=new RandomAccessFile(oversized, "rw")){ bytes.setLength(20L*1024*1024); }
		File invalid=File.createTempFile("album_ui_unsupported", ".jpg", MastodonApp.context.getCacheDir()); localImages.add(invalid); Files.writeString(invalid.toPath(), "Not an image");
		for(Uri uri:List.of(Uri.fromFile(oversized), Uri.fromFile(invalid), Uri.parse("content://local-fixture/unknown-size"))){
			host.fragment.onActivityResult(7820, Activity.RESULT_OK, new Intent().setData(uri)); await(()->{ try{ return !(boolean)field(AlbumUploadFragment.class, "checkingSources").get(host.fragment); }catch(Exception error){ throw new AssertionError(error); } });
			host.view(R.id.album_upload_quality_row).performClick(); AlertDialog quality=latest(); assertFalse(quality.findViewById(R.id.album_original).isEnabled()); quality.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertEquals("hd", saved(host.fragment).getString("quality"));
			layout(host); RecyclerView.ViewHolder photo=host.grid().findViewHolderForAdapterPosition(1); assertNotNull(photo); photo.itemView.findViewById(R.id.album_upload_remove).performClick();
			await(()->{ try{ return !(boolean)field(AlbumUploadFragment.class, "checkingSources").get(host.fragment); }catch(Exception error){ throw new AssertionError(error); } });
		}
	}
	@Test public void plusTileLaunchesSafAndOnlyRemovalBadgeOrConfirmedLongPressRemoves() throws Exception{
		Host host=open(R.style.Theme_Mastodon_Light, "", List.of(source)); host.view(R.id.album_pick).performClick();
		org.robolectric.shadows.ShadowActivity shadow=org.robolectric.shadow.api.Shadow.extract(host.lifecycle.get());
		var picker=shadow.getNextStartedActivityForResult(); assertNotNull(picker); assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent.getAction()); assertEquals("image/*", picker.intent.getType()); assertTrue(picker.intent.hasCategory(Intent.CATEGORY_OPENABLE)); assertTrue(picker.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)); assertTrue((picker.intent.getFlags()&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0);
		File second=localPhoto("blue", Color.rgb(155, 190, 210)), third=localPhoto("pink", Color.rgb(213, 175, 180)); ClipData clip=ClipData.newRawUri("Local", Uri.fromFile(second)); clip.addItem(new ClipData.Item(Uri.fromFile(third))); clip.addItem(new ClipData.Item(Uri.fromFile(source)));
		Intent picked=new Intent(); picked.setClipData(clip);
		host.fragment.onActivityResult(7820, Activity.RESULT_OK, picked); layout(host); assertEquals(4, host.grid().getAdapter().getItemCount()); assertEquals(3, saved(host.fragment).getStringArrayList("photos").size());
		RecyclerView.ViewHolder photo=host.grid().findViewHolderForAdapterPosition(0); assertNotNull(photo); photo.itemView.performClick(); photo.itemView.findViewById(R.id.album_photo).performClick(); assertEquals(3, saved(host.fragment).getStringArrayList("photos").size());
		assertTrue(photo.itemView.performLongClick()); latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); ShadowLooper.idleMainLooper(); assertEquals(3, saved(host.fragment).getStringArrayList("photos").size());
		photo.itemView.findViewById(R.id.album_upload_remove).performClick(); assertEquals(2, saved(host.fragment).getStringArrayList("photos").size()); assertTrue(source.isFile()); layout(host);
		photo=host.grid().findViewHolderForAdapterPosition(0); assertTrue(photo.itemView.performLongClick()); latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper(); assertEquals(1, saved(host.fragment).getStringArrayList("photos").size()); assertTrue(second.isFile());
	}
	@Test public void timeDialogValidatesSecondsCancelDoesNotEditAndRecreatedViewUsesDraft() throws Exception{
		Host host=open(R.style.Theme_Mastodon_Light, "保留描述", List.of(source)); host.view(R.id.album_upload_time_row).performClick(); AlertDialog time=latest();
		((RadioButton)time.findViewById(R.id.album_custom_time)).performClick(); EditText input=time.findViewById(R.id.album_time_input); input.setText("2026-02-30 12:34:56"); time.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertTrue(time.isShowing()); assertNotNull(input.getError()); assertFalse(saved(host.fragment).getBoolean("customCapturedAt"));
		input.setText("2026-10-05 12:34:56"); time.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertEquals("2026-10-05 12:34:56", text(host.root(), R.id.album_upload_time_value));
		AlbumUploadDraft draft=AlbumUploadDraft.restore(saved(host.fragment)); assertEquals(Long.valueOf(java.time.LocalDateTime.of(2026,10,5,12,34,56).atZone(ZoneId.systemDefault()).toEpochSecond()), draft.capturedAt(ZoneId.systemDefault()));
		host.view(R.id.album_upload_time_row).performClick(); time=latest(); ((EditText)time.findViewById(R.id.album_time_input)).setText("2027-01-01 00:00:00"); time.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); assertEquals("2026-10-05 12:34:56", saved(host.fragment).getString("customTime"));
		host.lifecycle.get().getFragmentManager().beginTransaction().detach(host.fragment).commit(); host.lifecycle.get().getFragmentManager().executePendingTransactions(); host.lifecycle.get().getFragmentManager().beginTransaction().attach(host.fragment).commit(); host.lifecycle.get().getFragmentManager().executePendingTransactions(); layout(host);
		assertEquals("保留描述", text(host.root(), R.id.album_description)); assertEquals("2026-10-05 12:34:56", text(host.root(), R.id.album_upload_time_value)); assertNull(host.root().findViewById(R.id.album_time_input));
		host.view(R.id.album_upload_time_row).performClick(); time=latest(); ((RadioButton)time.findViewById(R.id.album_time_auto)).performClick(); time.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertFalse(saved(host.fragment).getBoolean("customCapturedAt")); assertEquals("使用上传时间", text(host.root(), R.id.album_upload_time_value)); assertNull(AlbumUploadDraft.restore(saved(host.fragment)).capturedAt(ZoneId.systemDefault()));
	}
	@Test public void topUploadPauseAndContinueReuseSameRealTaskAfterViewRecreation() throws Exception{
		Host host=open(R.style.Theme_Mastodon_Light, "同一任务描述", List.of(source)); authorizationEntered=new CountDownLatch(1); releaseAuthorization=new CountDownLatch(1); host.view(R.id.album_upload).performClick();
		AlbumUploader task=(AlbumUploader)field(AlbumUploadFragment.class, "uploader").get(host.fragment); assertNotNull(task); tasks.add(task); String operation=task.getOperationId(); assertTrue(authorizationEntered.await(5, TimeUnit.SECONDS)); ShadowLooper.idleMainLooper();
		assertEquals("暂停", text(host.root(), R.id.album_upload)); assertFalse(host.view(R.id.album_description).isEnabled()); assertFalse(host.view(R.id.album_upload_time_row).isEnabled()); assertFalse(host.view(R.id.album_pick).isEnabled());
		host.view(R.id.album_upload_close).performClick(); AlertDialog back=latest(); assertTrue(containsText(back.getWindow().getDecorView(), "上传在后台继续")); back.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); assertTrue(task.isRunning());
		host.view(R.id.album_upload).performClick(); releaseAuthorization.countDown(); await(()->!task.isRunning()); assertEquals("继续", text(host.root(), R.id.album_upload)); assertEquals(operation, host.lifecycle.get().getSharedPreferences("album_upload_tasks", Context.MODE_PRIVATE).getString(accountId, null));
		host.lifecycle.get().getFragmentManager().beginTransaction().detach(host.fragment).commit(); host.lifecycle.get().getFragmentManager().executePendingTransactions(); host.lifecycle.get().getFragmentManager().beginTransaction().attach(host.fragment).commit(); host.lifecycle.get().getFragmentManager().executePendingTransactions(); layout(host);
		assertSame(task, field(AlbumUploadFragment.class, "uploader").get(host.fragment)); assertEquals("继续", text(host.root(), R.id.album_upload));
		authorizationEntered=new CountDownLatch(1); releaseAuthorization=new CountDownLatch(1); host.view(R.id.album_upload).performClick(); assertTrue(authorizationEntered.await(5, TimeUnit.SECONDS)); ShadowLooper.idleMainLooper(); assertEquals("暂停", text(host.root(), R.id.album_upload)); assertEquals(operation, task.getOperationId());
		assertEquals(2, authorizations.size()); assertEquals(authorizations.get(0), authorizations.get(1)); assertTrue(authorizations.get(0).contains("\"captured_at\":null")); assertEquals("同一任务描述", task.getDraftState().description);
		releaseAuthorization.countDown(); await(()->!task.isRunning()); host.view(R.id.album_upload_status).performClick(); assertTrue(containsText(latest().getWindow().getDecorView(), "Local retryable authorization error")); latest().dismiss();
		host.view(R.id.album_upload_discard).performClick(); latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); assertSame(task, field(AlbumUploadFragment.class, "uploader").get(host.fragment));
	}
	@Test public void longDescriptionHasCompactErrorAndHelpDialogsAndSponsorUsesSelectedSession() throws Exception{
		Host host=open(R.style.Theme_Mastodon_Dark, "", List.of(source)); host.<EditText>view(R.id.album_description).setText("长".repeat(3001)); assertFalse(host.view(R.id.album_upload).isEnabled()); assertNotNull(host.<EditText>view(R.id.album_description).getError());
		assertEquals("描述过长 · 查看说明", text(host.root(), R.id.album_upload_status)); host.view(R.id.album_upload_status).performClick(); assertTrue(containsText(latest().getWindow().getDecorView(), "3000")); latest().dismiss();
		host.view(R.id.album_upload_help).performClick(); AlertDialog help=latest(); assertTrue(containsText(help.getWindow().getDecorView(), "20 MiB")); assertTrue(containsText(help.getWindow().getDecorView(), "3000")); help.dismiss();
		host.<EditText>view(R.id.album_description).setText("未改相册权限"); assertEquals(View.GONE, host.view(R.id.album_upload_bar).getVisibility()); host.view(R.id.album_upload_sponsor).performClick(); assertEquals(SponsorCenterFragment.class, RecordingNav.destination); assertEquals(accountId, RecordingNav.arguments.getString("account"));
		RecordingNav.reset(); field(AccountSessionManager.class, "lastActiveAccountID").set(manager, "other-session"); host.view(R.id.album_upload_sponsor).performClick(); assertNull(RecordingNav.destination); host.fragment.onShown(); assertFalse(host.view(R.id.album_upload).isEnabled()); assertFalse(host.view(R.id.album_upload_sponsor).isEnabled());
	}

	/** Only records navigation destinations; fragment state, widgets, SAF and uploader are real. */
	@Implements(Nav.class) public static class RecordingNav{
		static Class<? extends Fragment> destination; static Bundle arguments;
		static void reset(){ destination=null; arguments=null; }
		@Implementation public static void go(Activity activity, Class<? extends Fragment> fragment, Bundle args){ destination=fragment; arguments=new Bundle(args); }
	}
}
