package org.joinmastodon.android.albums;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.os.SystemClock;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.AccountLoggedOutEvent;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.Token;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class AlbumPreviewMemoryCacheTest{
	private static final String PHOTO="12345678-1234-1234-1234-123456789abc";
	private static final String BATCH="abcdef12-1234-1234-1234-123456789abc";
	private static final String BASE="https://abdl-1339643562.cos.ap-shanghai.myqcloud.com/albums/42/"+BATCH+"/"+PHOTO+"/preview.jpg";
	private AlbumPreviewMemoryCache cache;
	private AccountSession session, old;
	private String accountID, previous;
	private Map<String,AccountSession> sessions;

	@Before public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication();
		session=newSession("42", "cache-test-token"); accountID=session.getID();
		AccountSessionManager manager=AccountSessionManager.getInstance();
		@SuppressWarnings("unchecked") Map<String,AccountSession> map=(Map<String,AccountSession>)field(AccountSessionManager.class,"sessions").get(manager);
		sessions=map; previous=(String)field(AccountSessionManager.class,"lastActiveAccountID").get(manager);
		old=sessions.put(accountID,session); select(accountID);
		cache=AlbumPreviewMemoryCache.getInstance(MastodonApp.context);
	}
	@After public void cleanup() throws Exception{
		cache.invalidateSession(accountID,session);
		if(old==null) sessions.remove(accountID); else sessions.put(accountID,old);
		select(previous);
	}

	@Test public void strictCosSigningRotationPreservesOnlyPreviewContentIdentity(){
		long now=System.currentTimeMillis()/1000;
		String first=signed(now,"1"), next=signed(now+1,"2");
		assertEquals(AlbumPreviewMemoryCache.contentKey(PHOTO,first), AlbumPreviewMemoryCache.contentKey(PHOTO,next));
		assertEquals(AlbumPreviewMemoryCache.contentKey(PHOTO,first+"&versionId=v1&imageMogr2=thumbnail%2F320x"),
				AlbumPreviewMemoryCache.contentKey(PHOTO,next+"&imageMogr2=thumbnail%2F320x&versionId=v1"));
		assertNotEquals(AlbumPreviewMemoryCache.contentKey(PHOTO,first),AlbumPreviewMemoryCache.contentKey(PHOTO,next+"&imageMogr2=thumbnail%2F640x"));
		assertNotEquals(AlbumPreviewMemoryCache.contentKey(PHOTO,first),AlbumPreviewMemoryCache.contentKey(PHOTO,next.replace("preview.jpg","preview.webp")));
		for(String url:new String[]{next.replace("preview.jpg","hd.jpg"), next.replace("preview.jpg","original.jpg"), next.replace("abdl-1339643562.cos.ap-shanghai.myqcloud.com","other.example.test"), next.replace("/42/","/042/"), next.replace(BATCH,"legacy"), next.replace(PHOTO,"aaaaaaaa-1234-1234-1234-123456789abc")}){
			assertTrue("Unrecognized path must retain exact URL",AlbumPreviewMemoryCache.contentKey(PHOTO,url).startsWith("exact:"));
		}
		for(String invalid:List.of(next.replace("q-ak=TESTKEY","q-ak=bad%20key"), next.replace("q-header-list=host","q-header-list=host%3Bcookie"),
				next.replace("q-signature="+"2".repeat(40),"q-signature=bad"), next.replace("q-sign-algorithm=sha1","q-sign-algorithm=unknown"),
				next.replace("q-key-time="+(now+1)+";"+(now+61),"q-key-time=1;2"), BASE)){
			assertNull("Managed COS preview with malformed/incomplete signing is never cacheable",AlbumPreviewMemoryCache.contentKey(PHOTO,invalid));
		}
		assertNull(AlbumPreviewMemoryCache.contentKey(PHOTO,next+"&q-signature=duplicate"));
		assertNull(AlbumPreviewMemoryCache.contentKey(PHOTO,next+"#fragment"));
		assertNotEquals(AlbumPreviewMemoryCache.contentKey(PHOTO,"https://legacy.example.test/a?transform=1&sig=a"),
				AlbumPreviewMemoryCache.contentKey(PHOTO,"https://legacy.example.test/a?transform=1&sig=b"));
	}

	@Test public void exactUnknownUrlQueryDimensionsAndDecodeSizeAllParticipate(){
		Bitmap bitmap=immutable(32); Attachment image=preview("https://legacy.example.test/preview?transform=32&sig=one");
		cache.put(accountID,session,image,4096,false,bitmap);
		assertSame(bitmap,cache.get(accountID,session,image,4096,false));
		assertNull(cache.get(accountID,session,image,2048,false));
		image.meta.width++;
		assertNull(cache.get(accountID,session,image,4096,false)); image.meta.width--;
		image.previewUrl=image.previewUrl.replace("transform=32","transform=64"); assertNull(cache.get(accountID,session,image,4096,false));
		image.previewUrl="https://legacy.example.test/new-content?transform=32&sig=one"; assertNull(cache.get(accountID,session,image,4096,false));
	}

	@Test public void elapsedTtlDoesNotSlideOnHitsAndSignatureExpiryCapsLifetime(){
		Attachment image=preview("https://legacy.example.test/preview"); Bitmap bitmap=immutable(32);
		cache.put(accountID,session,image,4096,false,bitmap);
		ShadowSystemClock.advanceBy(Duration.ofMillis(AlbumPreviewMemoryCache.TTL_MILLIS-1));
		assertSame(bitmap,cache.get(accountID,session,image,4096,false));
		ShadowSystemClock.advanceBy(Duration.ofMillis(1)); assertNull(cache.get(accountID,session,image,4096,false));
		image.previewUrl=signed(System.currentTimeMillis()/1000,"1"); cache.put(accountID,session,image,4096,false,bitmap);
		assertSame(bitmap,cache.get(accountID,session,image,4096,false));
		ShadowSystemClock.advanceBy(Duration.ofSeconds(61)); assertNull(cache.get(accountID,session,image,4096,false));
		image.previewUrl=signed(System.currentTimeMillis()/1000-100,"2"); cache.put(accountID,session,image,4096,false,bitmap);
		assertNull(cache.get(accountID,session,image,4096,false));
		image.previewUrl=BASE+"?q-sign-time=bad"; cache.put(accountID,session,image,4096,false,bitmap);
		assertNull(cache.get(accountID,session,image,4096,false));
		assertTrue(SystemClock.elapsedRealtime()>=0);
	}

	@Test public void recycledMutableOversizedProtectedAndBlockedBitmapsNeverSurvive(){
		Attachment image=preview("https://legacy.example.test/preview");
		Bitmap mutable=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);
		cache.put(accountID,session,image,4096,false,mutable); assertNull(cache.get(accountID,session,image,4096,false));
		Bitmap bitmap=immutable(32); cache.put(accountID,session,image,4096,false,bitmap); bitmap.recycle();
		assertNull(cache.get(accountID,session,image,4096,false));
		bitmap=immutable(1536); cache.put(accountID,session,image,4096,false,bitmap); assertNull(cache.get(accountID,session,image,4096,false));
		bitmap=immutable(32); cache.put(accountID,session,image,4096,true,bitmap); assertNull(cache.get(accountID,session,image,4096,false));
		cache.put(accountID,session,image,4096,false,bitmap); assertNull(cache.get(accountID,session,image,4096,true));
		assertNull(cache.get(accountID,session,image,4096,false));
		cache.put(accountID,session,image,4096,false,bitmap); image.adminBlocked=true;
		assertNull(cache.get(accountID,session,image,4096,false)); image.adminBlocked=false;
		assertNull(cache.get(accountID,session,image,4096,false));
	}

	@Test public void lruEntryAndByteBudgetsEvictWithoutRecyclingDisplayedBitmaps(){
		Bitmap bitmap=immutable(32);
		for(int i=0;i<8;i++) cache.put(accountID,session,preview("https://legacy.example.test/"+i),4096,false,bitmap);
		assertSame(bitmap,cache.get(accountID,session,preview("https://legacy.example.test/0"),4096,false));
		cache.put(accountID,session,preview("https://legacy.example.test/8"),4096,false,bitmap);
		assertNull(cache.get(accountID,session,preview("https://legacy.example.test/1"),4096,false));
		assertFalse(bitmap.isRecycled());
		cache.invalidateSession(accountID,session);
		bitmap=immutable(1280);
		for(int i=0;i<4;i++) cache.put(accountID,session,preview("https://legacy.example.test/large"+i),4096,false,bitmap);
		assertNull(cache.get(accountID,session,preview("https://legacy.example.test/large0"),4096,false));
		assertSame(bitmap,cache.get(accountID,session,preview("https://legacy.example.test/large3"),4096,false));
		assertFalse(bitmap.isRecycled());
	}

	@Test public void sessionTokenAccountReplacementLogoutAndStopPurgeNotJustMiss() throws Exception{
		Attachment image=preview("https://legacy.example.test/preview"); Bitmap bitmap=immutable(32);
		cache.put(accountID,session,image,4096,false,bitmap);
		AccountSession replacement=newSession("42","replacement"); sessions.put(accountID,replacement);
		assertNull(cache.get(accountID,replacement,image,4096,false)); sessions.put(accountID,session);
		assertNull(cache.get(accountID,session,image,4096,false));
		cache.put(accountID,session,image,4096,false,bitmap); Token token=session.token;
		session.token=new Token(); session.token.accessToken="new-token";
		assertNull(cache.get(accountID,session,image,4096,false)); session.token=token;
		assertNull(cache.get(accountID,session,image,4096,false));
		cache.put(accountID,session,image,4096,false,bitmap); String access=token.accessToken; token.accessToken=new String(access);
		assertNull(cache.get(accountID,session,image,4096,false)); token.accessToken=access;
		assertNull(cache.get(accountID,session,image,4096,false));
		cache.put(accountID,session,image,4096,false,bitmap); select("other-account");
		assertNull(cache.get(accountID,session,image,4096,false)); select(accountID);
		assertNull(cache.get(accountID,session,image,4096,false));
		cache.put(accountID,session,image,4096,false,bitmap); cache.onAccountLoggedOut(new AccountLoggedOutEvent(accountID));
		assertNull(cache.get(accountID,session,image,4096,false));
		cache.put(accountID,session,image,4096,false,bitmap); cache.onActivityStopped(new Activity());
		assertNull(cache.get(accountID,session,image,4096,false));
	}

	@Test public void photoAndSessionInvalidationDoNotDeleteOtherPhotosOrReplacementSession() throws Exception{
		Attachment first=preview("https://legacy.example.test/a"), second=preview("https://legacy.example.test/b"); second.id="other-photo";
		Bitmap bitmap=immutable(32); cache.put(accountID,session,first,4096,false,bitmap); cache.put(accountID,session,second,4096,false,bitmap);
		cache.invalidatePhoto(accountID,session,first.id);
		assertNull(cache.get(accountID,session,first,4096,false)); assertSame(bitmap,cache.get(accountID,session,second,4096,false));
		AccountSession replacement=newSession("42","replacement"); sessions.put(accountID,replacement);
		cache.put(accountID,replacement,second,4096,false,bitmap);
		cache.invalidateSession(accountID,session);
		assertSame(bitmap,cache.get(accountID,replacement,second,4096,false));
		cache.invalidateSession(accountID,replacement); sessions.put(accountID,session);
	}

	private static AccountSession newSession(String id,String value) throws Exception{
		Constructor<AccountSession> constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true);
		AccountSession session=constructor.newInstance(); session.domain="album-preview-cache.example.test";
		session.self=new Account(); session.self.id=id; session.token=new Token(); session.token.accessToken=value; return session;
	}
	private static Attachment preview(String url){
		Attachment image=new Attachment(); image.type=Attachment.Type.IMAGE; image.id=PHOTO; image.previewUrl=image.url=url;
		image.meta=new Attachment.Metadata(); image.meta.width=32; image.meta.height=32; return image;
	}
	static String signed(long start,String digit){
		return BASE+"?q-sign-algorithm=sha1&q-ak=TESTKEY&q-sign-time="+start+";"+(start+60)+"&q-key-time="+start+";"+(start+60)
				+"&q-header-list=host&q-url-param-list=&q-signature="+digit.repeat(40);
	}
	private static Bitmap immutable(int size){
		Bitmap mutable=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888), result=mutable.copy(Bitmap.Config.ARGB_8888,false);
		mutable.recycle(); return result;
	}
	private static Field field(Class<?> type,String name) throws Exception{ Field value=type.getDeclaredField(name); value.setAccessible(true); return value; }
	private void select(String id) throws Exception{ field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),id); }
}
