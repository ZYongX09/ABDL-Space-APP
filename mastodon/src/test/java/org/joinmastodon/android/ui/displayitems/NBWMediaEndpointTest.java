package org.joinmastodon.android.ui.displayitems;

import static org.junit.Assert.*;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.NBWEndpointFixtures;
import org.joinmastodon.android.api.NBWV2RepositoryTest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.NBWForumOptionsRepository;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.NBWEndpoints;
import org.joinmastodon.android.model.NBWSelection;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.ui.utils.NBWImageRequests;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;

/** Real cached display items and real session/public gates; metadata transport alone is local. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=CompatibilityTestApplication.class)
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
public class NBWMediaEndpointTest{
	private NBWEndpointFixtures endpoints;
	private AccountSession session;
	private String previousActive;
	private Map<String,AccountSession> sessions;
	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); me.grishka.appkit.utils.V.setApplicationContext(MastodonApp.context);
		endpoints=new NBWEndpointFixtures(); var ctor=AccountSession.class.getDeclaredConstructor(); ctor.setAccessible(true); session=ctor.newInstance();
		session.domain="nbw-media.offline.example.test"; session.self=new Account(); session.self.id="51"; session.token=new Token(); session.token.accessToken="fictional-media-token";
		var manager=AccountSessionManager.getInstance(); sessions=(Map<String,AccountSession>)field(AccountSessionManager.class,"sessions").get(manager);
		sessions.put(session.getID(),session); previousActive=manager.getLastActiveAccountID(); field(AccountSessionManager.class,"lastActiveAccountID").set(manager,session.getID());
		NBWForumOptionsRepository repository=new NBWForumOptionsRepository(new NBWForumOptionsRepository.Transport(){
			public NBWForumOptionsRepository.Cancelable capabilities(NBWForumOptionsRepository.Result<org.joinmastodon.android.model.NBWCapabilities> result){ result.success(NBWV2RepositoryTest.capabilities("full")); return ()->{}; }
			public NBWForumOptionsRepository.Cancelable options(NBWForumOptionsRepository.Result<org.joinmastodon.android.model.NBWForumOptions> result){ result.success(NBWV2RepositoryTest.options()); return ()->{}; }
			public NBWForumOptionsRepository.Cancelable consent(boolean value,NBWForumOptionsRepository.Result<Object> result){ throw new AssertionError(); }
		},android.os.SystemClock::elapsedRealtime,new NBWForumOptionsRepository.SelectionStore(){
			public NBWSelection load(){ return NBWSelection.ai(); } public void save(NBWSelection value){}
		},()->{},Runnable::run);
		field(AccountSession.class,"nbwForumOptionsRepository").set(session,repository); repository.activate();
	}
	@After public void cleanup() throws Exception{
		session.deactivateNBW(); sessions.remove(session.getID()); field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),previousActive); endpoints.close();
	}
	private static Field field(Class<?> type,String name) throws Exception{ Field field=type.getDeclaredField(name); field.setAccessible(true); return field; }
	@Test public void cachedHeadersAndCwMediaResolveAtActualLoaderSinkWithoutMutatingStableIdentity() throws Exception{
		Account author=new Account(); author.id="nbw_42"; author.username="name"; author.acct="name@www.newbabyworld.top"; author.displayName="Author";
		author.url=NBWEndpoints.PRIMARY+"/home.php?mod=space&uid=42"; author.avatar=author.avatarStatic=NBWEndpoints.PRIMARY+"/avatar.php?uid=42"; author.postprocess();
		Status status=Status.ofFake("nbw_123","",Instant.EPOCH); status.account=author; status.uri=NBWEndpoints.PRIMARY+"/thread-123-1-1.html"; status.url=status.uri; status.sensitive=true;
		Attachment attachment=new Attachment(); attachment.id="nbw_123_1"; attachment.type=Attachment.Type.IMAGE;
		attachment.url=NBWEndpoints.PRIMARY+"/original.jpg?sign=a%2Bb"; attachment.previewUrl=NBWEndpoints.PRIMARY+"/thumb.jpg?sign=a%2Bb"; status.mediaAttachments=List.of(attachment);
		var callbacks=new StatusDisplayItem.NoOpCallbacks(MastodonApp.context);
		var header=new HeaderStatusDisplayItem(status.id,author,status.createdAt,callbacks,MastodonApp.context,session.getID(),status,null);
		var media=new MediaGridStatusDisplayItem(status.id,callbacks,MastodonApp.context,null,List.of(attachment),status);
		var old=header.getImageRequest(0); assertNotNull(old); assertFalse(media.sensitiveRevealed);
		endpoints.publish("backup","endpoint-b","full");
		assertEquals(NBWEndpoints.BACKUP+"/avatar.php?uid=42",((UrlImageLoaderRequest)header.getImageRequest(0)).uri.toString());
		assertEquals(NBWEndpoints.BACKUP+"/thumb.jpg?sign=a%2Bb",((UrlImageLoaderRequest)media.getImageRequest(0)).uri.toString());
		assertFalse(media.sensitiveRevealed); assertFalse(NBWImageRequests.accepts(old));
		assertEquals("nbw_42",author.id); assertEquals("name@www.newbabyworld.top",author.acct); assertEquals("nbw_123",status.id);
		assertEquals(NBWEndpoints.PRIMARY+"/thread-123-1-1.html",status.uri); assertEquals(NBWEndpoints.PRIMARY+"/original.jpg?sign=a%2Bb",attachment.url);
	}
	@Test public void realPhotoViewerMapsOriginalAndClosesOnlyItsNbwLeaseOnRevocation() throws Exception{
		var lifecycle=org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup();
		var activity=lifecycle.get(); activity.setTheme(org.joinmastodon.android.R.style.Theme_Mastodon_Light);
		org.joinmastodon.android.ui.photoviewer.PhotoViewer viewer=null,local=null;
		try{
			Attachment attachment=new Attachment(); attachment.id="nbw_media_42"; attachment.type=Attachment.Type.IMAGE;
			attachment.url=NBWEndpoints.PRIMARY+"/original.jpg?sign=a%2Bb"; attachment.previewUrl=null;
			attachment.meta=new Attachment.Metadata(); attachment.meta.width=attachment.meta.height=100;
			endpoints.publish("backup","endpoint-b","full");
			viewer=new org.joinmastodon.android.ui.photoviewer.PhotoViewer(activity,null,List.of(attachment),0,null,session.getID(),new ViewerListener());
			var method=viewer.getClass().getDeclaredMethod("mediaRequest",String.class); method.setAccessible(true);
			var original=(UrlImageLoaderRequest)method.invoke(viewer,attachment.url);
			assertEquals(NBWEndpoints.BACKUP+"/original.jpg?sign=a%2Bb",original.uri.toString());
			Attachment firstParty=new Attachment(); firstParty.id="local-photo"; firstParty.type=Attachment.Type.IMAGE;
			firstParty.url="https://local.offline.example.test/original.jpg"; firstParty.meta=attachment.meta;
			local=new org.joinmastodon.android.ui.photoviewer.PhotoViewer(activity,null,List.of(firstParty),0,null,session.getID(),new ViewerListener());
			session.deactivateNBW(); org.robolectric.shadows.ShadowLooper.idleMainLooper();
			assertTrue(field(viewer.getClass(),"dismissed").getBoolean(viewer));
			assertFalse(field(local.getClass(),"dismissed").getBoolean(local));
			assertNull(method.invoke(viewer,attachment.url));
		}finally{ if(viewer!=null) viewer.onDismissed(); if(local!=null) local.onDismissed(); lifecycle.close(); }
	}
	private static final class ViewerListener implements org.joinmastodon.android.ui.photoviewer.PhotoViewer.Listener{
		public void setPhotoViewVisibility(int index,boolean visible){}
		public boolean startPhotoViewTransition(int index,android.graphics.Rect bounds,int[] corners){ return false; }
		public void setTransitioningViewTransform(float x,float y,float scale){} public void endPhotoViewTransition(){}
		public android.graphics.drawable.Drawable getPhotoViewCurrentDrawable(int index){ return null; }
		public void photoViewerDismissed(){} public void onRequestPermissions(String[] permissions){}
	}
	@Test public void sameUriRevisionLateRealListImageLoaderCallbackDoesNotDeliver() throws Exception{
		UrlImageLoaderRequest source=new UrlImageLoaderRequest(NBWEndpoints.PRIMARY+"/photo.jpg");
		var old=NBWImageRequests.current(source); assertEquals(old,NBWImageRequests.current(source));
		var loader=new me.grishka.appkit.imageloader.ListImageLoader(); int[] delivered={0};
		loader.setAdapter(new me.grishka.appkit.imageloader.ListImageLoaderAdapter(){
			public int getCount(){ return 1; } public int getImageCountForItem(int position){ return 1; }
			public me.grishka.appkit.imageloader.requests.ImageLoaderRequest getImageRequest(int position,int image){ return NBWImageRequests.current(source); }
			public void imageLoaded(int position,int image,android.graphics.drawable.Drawable drawable){ delivered[0]++; }
			public void imageLoadingFailed(int position,int image,Throwable error){ throw new AssertionError(error); }
		});
		Class<?> taskType=Class.forName("me.grishka.appkit.imageloader.ListImageLoader$RunnableTask");
		var taskCtor=taskType.getDeclaredConstructor(me.grishka.appkit.imageloader.ListImageLoader.class); taskCtor.setAccessible(true); Object task=taskCtor.newInstance(loader);
		field(taskType,"item").setInt(task,0); field(taskType,"image").setInt(task,0); field(taskType,"req").set(task,old);
		Class<?> callbackType=Class.forName("me.grishka.appkit.imageloader.ListImageLoader$RunnableTask$1");
		var callbackCtor=callbackType.getDeclaredConstructor(taskType); callbackCtor.setAccessible(true);
		var callback=(me.grishka.appkit.imageloader.ImageLoaderCallback)callbackCtor.newInstance(task);
		callback.onImageLoaded(old,new android.graphics.drawable.ColorDrawable(android.graphics.Color.RED));
		assertEquals(0,delivered[0]); endpoints.publish("primary","endpoint-b","full");
		var next=NBWImageRequests.current(source); assertEquals(((UrlImageLoaderRequest)old).uri,((UrlImageLoaderRequest)next).uri);
		assertNotEquals(old,next); assertFalse(old.sourcesEqual(next));
		org.robolectric.shadows.ShadowLooper.idleMainLooper(); assertEquals("Real Appkit queued callback must compare scope, not URI only",0,delivered[0]);
		callback.onImageLoaded(next,new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE));
		org.robolectric.shadows.ShadowLooper.idleMainLooper(); assertEquals(1,delivered[0]);
	}
	@Test public void inlineAvatarStaysRawUntilLazyGetterAndCanRecoverFromColdUnknown() throws Exception{
		Account author=new Account(); author.avatar=author.avatarStatic=NBWEndpoints.PRIMARY+"/avatar.php?uid=42";
		var span=new org.joinmastodon.android.ui.text.AvatarSpan(author); var text=new android.text.SpannableString("x");
		text.setSpan(span,0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		var helper=new org.joinmastodon.android.ui.utils.CustomEmojiHelper(); helper.setText(text);
		assertEquals(author.avatar,((UrlImageLoaderRequest)helper.requests.get(0)).uri.toString());
		endpoints.publish("primary","blocked","local_only"); assertNull(helper.getImageRequest(0));
		endpoints.publish("backup","endpoint-b","full");
		assertEquals(NBWEndpoints.BACKUP+"/avatar.php?uid=42",((UrlImageLoaderRequest)helper.getImageRequest(0)).uri.toString());
		assertEquals(author.avatar,((UrlImageLoaderRequest)helper.requests.get(0)).uri.toString());
	}
	@Test public void actualAudioDataSourceUsesBackupAndSessionRevocationReleasesPreparedPlayer() throws Exception{
		endpoints.publish("backup","endpoint-b","full");
		Status status=Status.ofFake("nbw_55","",Instant.EPOCH); status.account=session.self;
		session.self.avatar=android.net.Uri.fromFile(new java.io.File(MastodonApp.context.getCacheDir(),"absent.png")).toString();
		Attachment attachment=new Attachment(); attachment.id="nbw_audio_55"; attachment.type=Attachment.Type.AUDIO;
		attachment.url=NBWEndpoints.PRIMARY+"/audio.mp3?sign=a%2Bb";
		var info=new org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(10000,0);
		org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(org.robolectric.shadows.util.DataSource.toDataSource(MastodonApp.context,android.net.Uri.parse(NBWEndpoints.BACKUP+"/audio.mp3?sign=a%2Bb")),info);
		var lifecycle=org.robolectric.Robolectric.buildService(org.joinmastodon.android.AudioPlayerService.class).create();
		var service=lifecycle.get();
		try{
			service.onStartCommand(new android.content.Intent(MastodonApp.context,org.joinmastodon.android.AudioPlayerService.class)
					.putExtra("status",org.parceler.Parcels.wrap(status)).putExtra("attachment",org.parceler.Parcels.wrap(attachment)),0,1);
			android.media.MediaPlayer player=(android.media.MediaPlayer)field(service.getClass(),"player").get(service);
			assertNotNull(player); org.robolectric.shadows.ShadowLooper.idleMainLooper();
			assertEquals(org.robolectric.shadows.util.DataSource.toDataSource(MastodonApp.context,android.net.Uri.parse(NBWEndpoints.BACKUP+"/audio.mp3?sign=a%2Bb")),((org.robolectric.shadows.ShadowMediaPlayer)org.robolectric.shadow.api.Shadow.extract(player)).getDataSource());
			assertEquals(NBWEndpoints.PRIMARY+"/audio.mp3?sign=a%2Bb",attachment.url);
			session.deactivateNBW(); org.robolectric.shadows.ShadowLooper.idleMainLooper();
			assertNull(field(service.getClass(),"player").get(service));
			assertEquals(org.robolectric.shadows.ShadowMediaPlayer.State.END,((org.robolectric.shadows.ShadowMediaPlayer)org.robolectric.shadow.api.Shadow.extract(player)).getState());
		}finally{ lifecycle.destroy(); }
	}
	@Test public void lateImagesAreRejectedAfterAccountGenerationTokenRotationAndSwitchBack(){
		UrlImageLoaderRequest source=new UrlImageLoaderRequest(NBWEndpoints.PRIMARY+"/photo.jpg");
		var before=NBWImageRequests.current(source); assertTrue(NBWImageRequests.accepts(before));
		session.token.accessToken="rotated"; assertFalse(NBWImageRequests.accepts(before));
		var rotated=NBWImageRequests.current(source); session.deactivateNBW(); assertFalse(NBWImageRequests.accepts(rotated));
		session.getNBWForumOptionsRepository().activate(); var returned=NBWImageRequests.current(source);
		assertNotEquals(before.getMemoryCacheKey(),returned.getMemoryCacheKey());
		endpoints.publish("backup","endpoint-b","local_only"); assertNull(NBWImageRequests.current(source));
	}
}
