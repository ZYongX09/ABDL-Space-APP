package org.joinmastodon.android.albums;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;

import org.joinmastodon.android.E;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.AccountLoggedOutEvent;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Token;
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
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;

/** Real pixels and real session-manager identities; no server, HTTP execution or shadow cache. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class AlbumPublicCoverMemoryTest{
	private static final String HOST="abdl-1339643562.cos.ap-shanghai.myqcloud.com";
	private static final String ALBUM="11111111-1111-4111-8111-111111111111";
	private static final String OTHER_ALBUM="22222222-2222-4222-8222-222222222222";
	private static final String BATCH="33333333-3333-4333-8333-333333333333";
	private static final String OTHER_BATCH="44444444-4444-4444-8444-444444444444";
	private static final String PHOTO="55555555-5555-4555-8555-555555555555";
	private static final String OTHER_PHOTO="66666666-6666-4666-8666-666666666666";
	private static final String PATH="/albums/42/"+BATCH+"/"+PHOTO+"/preview.jpg";
	private static final int WIDTH=360, HEIGHT=224;
	private Context context;
	private AccountSessionManager manager;
	private Map<String, AccountSession> sessions;
	private AccountSession session, otherSession, previousSession, previousOtherSession;
	private String accountID, otherAccountID, previousActive;

	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication();
		context=MastodonApp.context;
		// Keep signatures near a deterministic, positive epoch. No production credentials are read.
		assertTrue(SystemClock.setCurrentTimeMillis(1_800_000_000_000L));
		manager=AccountSessionManager.getInstance();
		sessions=(Map<String, AccountSession>)field(AccountSessionManager.class, "sessions").get(manager);
		previousActive=manager.getLastActiveAccountID();
		session=syntheticSession("42"); otherSession=syntheticSession("43");
		accountID=session.getID(); otherAccountID=otherSession.getID();
		previousSession=sessions.put(accountID, session); previousOtherSession=sessions.put(otherAccountID, otherSession);
		activate(accountID);
		AlbumPublicCoverMemory.clearAll();
	}

	@After public void cleanup() throws Exception{
		AlbumPublicCoverMemory.clearAll();
		if(sessions!=null){
			if(previousSession==null) sessions.remove(accountID); else sessions.put(accountID, previousSession);
			if(previousOtherSession==null) sessions.remove(otherAccountID); else sessions.put(otherAccountID, previousOtherSession);
		}
		if(manager!=null) activate(previousActive);
	}

	@Test public void refreshedSignatureHitsWithoutRewritingNetworkUrl(){
		String initial=signed(); BitmapDrawable original=image(12, 10); put(initial, original);
		advance(1000);
		String refreshed=signed(PATH, now(), now()+60, "b");
		assertNotEquals(initial, refreshed);
		Request networkRequest=new Request.Builder().url(refreshed).build(); // Construct only; never executed.
		Drawable cached=get(refreshed);
		assertTrue(cached instanceof BitmapDrawable);
		assertSame(original.getBitmap(), ((BitmapDrawable)cached).getBitmap());
		assertNotSame(original, cached);
		assertEquals(refreshed, networkRequest.url().toString());
		assertEquals("b".repeat(40), networkRequest.url().queryParameter("q-signature"));
		assertEquals(now()+";"+(now()+60), networkRequest.url().queryParameter("q-sign-time"));
		assertEquals(1, entryCount());
	}

	@Test public void knownSigningFieldsMayChangeOrderAndSafeAccessKey(){
		String initial=signed(); put(initial, image(8, 8)); advance(1000);
		String fresh=signed(PATH, now(), now()+60, "c").replace("q-ak=AKID_SYNTHETIC", "q-ak=AKID_NEW_1-safe");
		String[] fields=fresh.substring(fresh.indexOf('?')+1).split("&");
		List<String> reversed=new ArrayList<>(List.of(fields)); java.util.Collections.reverse(reversed);
		assertNotNull(get("https://"+HOST+PATH+"?"+String.join("&", reversed)));
	}

	@Test public void albumIsANamespaceNotTheBatchUuid(){
		assertNotEquals(ALBUM, BATCH);
		put(signed(), image(8, 8));
		assertNotNull(get(signed()));
		assertNull(AlbumPublicCoverMemory.get(context, accountID, OTHER_ALBUM, signed(), WIDTH, HEIGHT));
		AlbumPublicCoverMemory.put(context, accountID, OTHER_ALBUM, signed(), WIDTH, HEIGHT, image(8, 8));
		assertEquals(2, entryCount());
		AlbumPublicCoverMemory.invalidate(accountID, ALBUM);
		assertNull(get(signed()));
		assertNotNull(AlbumPublicCoverMemory.get(context, accountID, OTHER_ALBUM, signed(), WIDTH, HEIGHT));
	}

	@Test public void everyLookupRequiresItsOwnStillValidSignature(){
		put(signed(), image(8, 8));
		assertNull(get(signed(PATH, now()-60, now(), "b")));
		assertNull(get(signed(PATH, now()-61, now()-1, "b")));
		assertNull(get(signed(PATH, now()+1, now()+60, "b")));
		assertNotNull("A rejected lookup cannot replace a live entry", get(signed()));
	}

	@Test public void expiredPutCannotSeedAnAlias(){
		String expired=signed(PATH, now()-60, now(), "a");
		put(expired, image(8, 8));
		assertEquals(0, entryCount()); assertNull(get(signed()));
	}

	@Test public void originalSignatureExpiryBoundsEvenAFreshLookup(){
		put(signed(PATH, now(), now()+5, "a"), image(8, 8));
		advance(4000); assertNotNull(get(signed()));
		advance(1000); assertNull(get(signed()));
		assertEquals(0, entryCount());
	}

	@Test public void getsAndRepeatedPutsDoNotSlideTheOriginalLifetime() throws Exception{
		String initial=signed(); put(initial, image(8, 8));
		Object first=onlyEntry(); long created=number(first, "createdElapsed"), expires=number(first, "expiresElapsed");
		long lifetime=expires-created;
		assertTrue("Fixture must leave room for its 30-second rebind", lifetime>30_000 && lifetime<=60_000);
		advance(30_000); String fresh=signed(); Drawable cached=get(fresh); assertNotNull(cached);
		put(fresh, cached); put(fresh, image(9, 9));
		assertSame(first, onlyEntry()); assertEquals(created, number(onlyEntry(), "createdElapsed"));
		assertEquals(expires, number(onlyEntry(), "expiresElapsed"));
		// The original signature ends on a whole second, so the initial lifetime may be <60,000ms.
		long remaining=expires-SystemClock.elapsedRealtime();
		assertTrue("Fixture must still be before its original deadline", remaining>0);
		advance(remaining-1);
		assertEquals("Last live millisecond of the original entry", expires-1, SystemClock.elapsedRealtime());
		assertTrue("Original signature must still be live", System.currentTimeMillis()<number(first, "expiresWall"));
		assertNotNull(get(signed()));
		advance(1);
		assertEquals("Original deadline, not a rebind-extended deadline", expires, SystemClock.elapsedRealtime());
		assertNull(get(signed())); assertEquals(0, entryCount());
	}

	@Test public void elapsedAgeCannotBeExtendedByWallClockRollback() throws Exception{
		put(signed(), image(8, 8));
		Object entry=onlyEntry();
		assertTrue(number(entry, "expiresElapsed")-number(entry, "createdElapsed")<=60_000L);
		// Equivalent clock-separation fixture: keep wall/signature current but age only RAM deadline.
		field(entry.getClass(), "expiresElapsed").setLong(entry, SystemClock.elapsedRealtime());
		assertNull(get(signed())); assertEquals(0, entryCount());
	}

	@Test public void scheduledPurgeDropsExpiredPixelsWithoutAnotherRead(){
		put(signed(PATH, now(), now()+3, "a"), image(8, 8));
		assertEquals(1, entryCount());
		org.robolectric.shadows.ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS);
		assertEquals(0, entryCount()); assertEquals(0, retainedBytes());
	}

	@Test public void fullManagedPathVariantAndDecodeBoundsArePartOfTheKey(){
		put(signed(), image(8, 8));
		for(String path:List.of(PATH.replace("/42/", "/43/"), PATH.replace(BATCH, OTHER_BATCH), PATH.replace(PHOTO, OTHER_PHOTO), PATH.replace(".jpg", ".webp"))){
			assertNull(path, get(signed(path, now(), now()+60, "b")));
		}
		assertNull(AlbumPublicCoverMemory.get(context, accountID, ALBUM, signed(), WIDTH+1, HEIGHT));
		assertNull(AlbumPublicCoverMemory.get(context, accountID, ALBUM, signed(), WIDTH, HEIGHT+1));
		assertNull(AlbumPublicCoverMemory.get(context, accountID, ALBUM, signed(), 0, HEIGHT));
		assertNull(AlbumPublicCoverMemory.get(context, accountID, ALBUM, signed(), WIDTH, -1));
		assertNotNull(get(signed()));
	}

	@Test public void managedWebpPreviewHasItsOwnShortLivedAlias(){
		String path=PATH.replace(".jpg", ".webp");
		put(signed(path, now(), now()+60, "a"), image(8, 8)); advance(1000);
		assertNotNull(get(signed(path, now(), now()+60, "b")));
		assertNull(get(signed()));
	}

	@Test public void arbitraryOriginsCredentialsPortsAndFragmentsNeverCache(){
		String valid=signed();
		assertRejected(List.of(
				valid.replace("https://", "http://"), valid.replace("https://", "HTTPS://"),
				valid.replace(HOST, "foreign.example.test"), valid.replace(HOST, HOST+".foreign.example.test"),
				valid.replace(HOST, "abdl-1339643562.COS.ap-shanghai.myqcloud.com"),
				valid.replace(HOST, HOST+"."), valid.replace(HOST, "user@"+HOST),
				valid.replace(HOST, HOST+":443"), valid.replace(HOST, HOST+":444"),
				valid+"#", valid+"#preview", "//"+valid.substring("https://".length()),
				"file:///albums/42/"+BATCH+"/"+PHOTO+"/preview.jpg", "https://[bad"));
	}

	@Test public void onlyPositiveOwnerBatchPhotoPreviewJpgOrWebpAreAdmitted(){
		List<String> badPaths=List.of(
				PATH.replace("/42/", "/0/"), PATH.replace("/42/", "/-1/"), PATH.replace("/42/", "/042/"),
				PATH.replace("/42/", "/9223372036854775808/"), PATH.replace("/42/", "/public/"),
				PATH.replace(BATCH, "batch"), PATH.replace(PHOTO, "photo"),
				PATH.replace(BATCH, "33333333-3333-4333-8333-33333333333z"),
				PATH.replace("preview.jpg", "hd.jpg"), PATH.replace("preview.jpg", "original.jpg"),
				PATH.replace("preview.jpg", "preview.png"), PATH.replace("preview.jpg", "preview.jpeg"),
				PATH.replace("preview.jpg", "PREVIEW.jpg"), PATH+"/", PATH+"/other", "/albums//42/"+BATCH+"/"+PHOTO+"/preview.jpg",
				PATH.replace("/albums/", "/media/"), PATH.replace("/albums/", "/albums/../albums/"),
				PATH.replace("/42/", "/%34%32/"), PATH.replace("/42/", "/42%2f"), PATH.replace("/42/", "/42%252f"),
				PATH.replace("preview.jpg", "%70review.jpg"), PATH.replace("preview.jpg", "preview%2ejpg"),
				PATH.replace("/42/", "/42\\"));
		List<String> urls=new ArrayList<>();
		for(String path:badPaths) urls.add(signed(path, now(), now()+60, "b"));
		assertRejected(urls);
	}

	@Test public void unknownParametersCannotAliasOrEvenSeedAnExactUrl(){
		String valid=signed();
		assertRejected(List.of(valid+"&imageMogr2/thumbnail/360x224", valid+"&imageMogr2=thumbnail", valid+"&x-cos-process=style/thumb",
				valid+"&response-content-type=image/webp", valid+"&unrecognized=1", valid+"&q-security-token=synthetic", valid+"&"));
	}

	@Test public void sevenSigningFieldsMustBeUniqueCompleteAndStrictlyValid(){
		String valid=signed(); List<String> invalid=new ArrayList<>();
		for(String pair:valid.substring(valid.indexOf('?')+1).split("&", -1)){
			invalid.add(valid+"&"+pair); // Duplicate even with an identical value.
			invalid.add(removeParameter(valid, pair.substring(0, pair.indexOf('='))));
		}
		invalid.addAll(List.of(
				parameter(valid, "q-sign-algorithm", "sha256"), parameter(valid, "q-sign-algorithm", "SHA1"),
				parameter(valid, "q-ak", ""), parameter(valid, "q-ak", "unsafe+key"), parameter(valid, "q-ak", "unsafe%2fkey"),
				parameter(valid, "q-ak", "a".repeat(129)), parameter(valid, "q-header-list", "content-type;host"),
				parameter(valid, "q-header-list", "HOST"), parameter(valid, "q-url-param-list", "transform"),
				parameter(valid, "q-signature", "z".repeat(40)), parameter(valid, "q-signature", "a".repeat(39)),
				parameter(valid, "q-signature", "A".repeat(40)), parameter(valid, "q-signature", ""),
				parameter(valid, "q-key-time", (now()-1)+";"+(now()+59)), parameter(valid, "q-sign-time", now()+"%3B"+(now()+60)),
				parameter(valid, "q-sign-time", "-1;0"), parameter(valid, "q-sign-time", "garbage"),
				parameter(valid, "q-sign-time", "999999999999999999999;999999999999999999999"),
				valid.replace("q-signature=", "Q-signature="), valid.replace("q-ak=", "q-ak"),
				signed(PATH, now(), now()+61, "a"), signed(PATH, now(), now(), "a"), signed(PATH, now()+1, now(), "a")));
		assertRejected(invalid);
	}

	@Test public void missingAnonymousInactiveAndUnactivatedSessionsNeverCache() throws Exception{
		String valid=signed(); Drawable drawable=image(8, 8);
		for(String missing:java.util.Arrays.asList(null, "", "anonymous", "missing-session")){
			AlbumPublicCoverMemory.put(context, missing, ALBUM, valid, WIDTH, HEIGHT, drawable);
			assertNull(AlbumPublicCoverMemory.get(context, missing, ALBUM, valid, WIDTH, HEIGHT));
		}
		AlbumPublicCoverMemory.put(context, otherAccountID, ALBUM, valid, WIDTH, HEIGHT, drawable);
		assertNull(AlbumPublicCoverMemory.get(context, otherAccountID, ALBUM, valid, WIDTH, HEIGHT));
		assertEquals(0, entryCount());
		activate(otherAccountID); assertNull(AlbumPublicCoverMemory.get(context, otherAccountID, ALBUM, valid, WIDTH, HEIGHT));
		activate(accountID); session.activated=false; put(valid, drawable); assertNull(get(valid)); assertEquals(0, entryCount());
		session.activated=true; session.token=null; put(valid, drawable); assertEquals(0, entryCount());
		session.token=new Token(); session.token.accessToken=" "; put(valid, drawable); assertEquals(0, entryCount());
	}

	@Test public void switchAwayDropsPriorPixelsOnTheVeryNextOperation() throws Exception{
		put(signed(), image(8, 8)); activate(otherAccountID);
		assertNull(get(signed())); assertEquals(0, entryCount());
		activate(accountID); assertNull(get(signed()));
		put(signed(), image(8, 8)); activate(otherAccountID);
		AlbumPublicCoverMemory.put(context, otherAccountID, ALBUM, signed(), WIDTH, HEIGHT, image(8, 8));
		activate(accountID); assertNull(get(signed())); assertEquals(0, entryCount());
	}

	@Test public void replacingSessionWithTheSameAccountAndTokenCannotReusePixels() throws Exception{
		put(signed(), image(8, 8)); AccountSession replacement=syntheticSession("42"); replacement.token=session.token;
		sessions.put(accountID, replacement); assertNull(get(signed())); assertEquals(0, entryCount());
		sessions.put(accountID, session); assertNull(get(signed()));
	}

	@Test public void tokenObjectAndAccessTokenIdentityChangesDropPixels(){
		Token original=session.token; put(signed(), image(8, 8));
		Token replacement=new Token(); replacement.accessToken=original.accessToken; session.token=replacement;
		assertNull(get(signed())); assertEquals(0, entryCount());
		session.token=original; assertNull(get(signed())); put(signed(), image(8, 8));
		session.token.accessToken=new String(original.accessToken); // Same value is still a different credential identity.
		assertNull(get(signed())); assertEquals(0, entryCount());
		put(signed(), image(8, 8)); session.token.accessToken="rotated-synthetic-token";
		assertNull(get(signed()));
	}

	@Test public void logoutAndClearReleasePixelsButNeverRecycleDisplayedBitmaps(){
		BitmapDrawable drawable=image(8, 8); put(signed(), drawable);
		E.post(new AccountLoggedOutEvent(otherAccountID)); org.robolectric.shadows.ShadowLooper.idleMainLooper();
		assertNotNull(get(signed()));
		E.post(new AccountLoggedOutEvent(accountID)); org.robolectric.shadows.ShadowLooper.idleMainLooper();
		assertEquals(0, entryCount()); assertNull(get(signed())); assertFalse(drawable.getBitmap().isRecycled());
		put(signed(), drawable); AlbumPublicCoverMemory.clearAll();
		assertNull(get(signed())); assertEquals(0, retainedBytes()); assertFalse(drawable.getBitmap().isRecycled());
	}

	@Test public void invalidateOnlyDropsItsAccountAlbumNamespace(){
		String url=signed(); put(url, image(8, 8));
		AlbumPublicCoverMemory.put(context, accountID, OTHER_ALBUM, url, WIDTH, HEIGHT, image(8, 8));
		AlbumPublicCoverMemory.invalidate(otherAccountID, ALBUM); assertNotNull(get(url)); assertEquals(2, entryCount());
		AlbumPublicCoverMemory.invalidate(accountID, ALBUM); assertNull(get(url));
		assertNotNull(AlbumPublicCoverMemory.get(context, accountID, OTHER_ALBUM, url, WIDTH, HEIGHT));
		assertEquals(1, entryCount());
	}

	@Test public void leastRecentlyUsedEntryIsEvictedAtSixteen(){
		BitmapDrawable drawable=image(8, 8);
		for(int i=0;i<16;i++) put(urlFor(i), drawable);
		assertEquals(16, entryCount()); assertNotNull(get(urlFor(0)));
		put(urlFor(16), drawable); assertEquals(16, entryCount());
		assertNotNull(get(urlFor(0))); assertNull(get(urlFor(1))); assertNotNull(get(urlFor(16)));
	}

	@Test public void byteBudgetAndSingleBitmapLimitAreBothEnforced(){
		BitmapDrawable twoMiB=image(1024, 512);
		assertEquals(2L*1024*1024, twoMiB.getBitmap().getAllocationByteCount());
		for(int i=0;i<5;i++) put(urlFor(i), twoMiB);
		assertEquals(4, entryCount()); assertEquals(8L*1024*1024, retainedBytes());
		assertNull(get(urlFor(0))); assertNotNull(get(urlFor(4)));
		AlbumPublicCoverMemory.clearAll();
		put(signed(), image(1024, 513)); assertEquals(0, entryCount()); assertNull(get(signed()));
		put(signed(), new ColorDrawable(Color.RED)); assertEquals(0, entryCount());
		put(signed(), null); assertEquals(0, entryCount());
	}

	@Test public void recycledBitmapNeverSeedsOrEscapesTheCache(){
		BitmapDrawable recycled=image(8, 8); recycled.getBitmap().recycle(); put(signed(), recycled);
		assertNull(get(signed())); assertEquals(0, entryCount());
		BitmapDrawable live=image(8, 8); put(signed(), live); assertNotNull(get(signed()));
		live.getBitmap().recycle(); assertNull(get(signed())); assertEquals(0, retainedBytes());
	}

	@Test public void eachHitHasIndependentDrawableStateAndOnlyApplicationResources() throws Exception{
		try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup()){
			Activity activity=lifecycle.get(); ImageView sourceView=new ImageView(activity);
			BitmapDrawable source=new BitmapDrawable(activity.getResources(), image(8, 8).getBitmap()); sourceView.setImageDrawable(source);
			AlbumPublicCoverMemory.put(activity, accountID, ALBUM, signed(), WIDTH, HEIGHT, source);
			BitmapDrawable first=(BitmapDrawable)AlbumPublicCoverMemory.get(activity, accountID, ALBUM, signed(), WIDTH, HEIGHT);
			BitmapDrawable second=(BitmapDrawable)get(signed());
			assertNotSame(source, first); assertNotSame(first, second); assertNotSame(first.getConstantState(), second.getConstantState());
			assertNull(first.getCallback()); assertNull(second.getCallback()); first.setAlpha(17); assertEquals(255, second.getAlpha());
			assertSame(source.getBitmap(), second.getBitmap());
			Object entry=onlyEntry(); Object key=entries().keySet().iterator().next();
			assertNoUiOrStrongSessionFields(AlbumPublicCoverMemory.class);
			assertNoUiOrStrongSessionFields(entry.getClass()); assertNoUiOrStrongSessionFields(key.getClass());
			Object identity=field(key.getClass(), "identity").get(key); assertNoUiOrStrongSessionFields(identity.getClass());
			for(String name:List.of("session", "token", "accessToken")) assertTrue(field(identity.getClass(), name).get(identity) instanceof WeakReference<?>);
			sourceView.setImageDrawable(null);
		}
		assertNotNull(get(signed())); // Destroying the source host is irrelevant to retained pixels.
	}

	@Test public void collectedSessionReferenceCannotBeRevived() throws Exception{
		put(signed(), image(8, 8)); Object key=entries().keySet().iterator().next();
		Object identity=field(key.getClass(), "identity").get(key);
		((Reference<?>)field(identity.getClass(), "session").get(identity)).clear();
		assertNull(get(signed())); assertEquals(0, entryCount());
	}

	@Test public void nullContextEmptyNamespaceAndInvalidDimensionsCannotSeedPixels(){
		String url=signed(); Drawable drawable=image(8, 8);
		AlbumPublicCoverMemory.put(null, accountID, ALBUM, url, WIDTH, HEIGHT, drawable);
		AlbumPublicCoverMemory.put(context, accountID, null, url, WIDTH, HEIGHT, drawable);
		AlbumPublicCoverMemory.put(context, accountID, " ", url, WIDTH, HEIGHT, drawable);
		AlbumPublicCoverMemory.put(context, accountID, "a".repeat(257), url, WIDTH, HEIGHT, drawable);
		AlbumPublicCoverMemory.put(context, accountID, ALBUM, null, WIDTH, HEIGHT, drawable);
		AlbumPublicCoverMemory.put(context, accountID, ALBUM, url, 0, HEIGHT, drawable);
		AlbumPublicCoverMemory.put(context, accountID, ALBUM, url, WIDTH, -1, drawable);
		assertEquals(0, entryCount());
	}

	/** Every rejection has a live positive control and also tries to seed an otherwise empty cache. */
	private void assertRejected(List<String> urls){
		for(String url:urls){
			AlbumPublicCoverMemory.clearAll(); String valid=signed(); put(valid, image(8, 8));
			assertNull(url, get(url)); assertNotNull("Positive control for "+url, get(valid));
			AlbumPublicCoverMemory.clearAll(); put(url, image(8, 8));
			assertEquals(url, 0, entryCount()); assertNull(url, get(url)); assertNull(url, get(valid));
		}
	}

	private static void assertNoUiOrStrongSessionFields(Class<?> type){
		for(Field field:type.getDeclaredFields()){
			if(Modifier.isStatic(field.getModifiers())) continue;
			Class<?> value=field.getType();
			assertFalse(field.toString(), Context.class.isAssignableFrom(value) || Resources.class.isAssignableFrom(value)
					|| Drawable.class.isAssignableFrom(value) || View.class.isAssignableFrom(value)
					|| AccountSession.class.isAssignableFrom(value) || Token.class.isAssignableFrom(value));
		}
	}

	private static AccountSession syntheticSession(String id) throws Exception{
		var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true);
		AccountSession session=constructor.newInstance(); session.domain="public-cover-offline.example.test";
		session.self=new Account(); session.self.id=id; session.token=new Token(); session.token.accessToken=new String("synthetic-public-cover-token-"+id);
		return session;
	}
	private void activate(String id) throws Exception{ field(AccountSessionManager.class, "lastActiveAccountID").set(manager, id); }
	private BitmapDrawable image(int width, int height){
		Bitmap bitmap=Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.rgb(40, 120, 180));
		return new BitmapDrawable(context.getResources(), bitmap);
	}
	private void put(String url, Drawable drawable){ AlbumPublicCoverMemory.put(context, accountID, ALBUM, url, WIDTH, HEIGHT, drawable); }
	private Drawable get(String url){ return AlbumPublicCoverMemory.get(context, accountID, ALBUM, url, WIDTH, HEIGHT); }
	private static void advance(long millis){ ShadowSystemClock.advanceBy(Duration.ofMillis(millis)); }
	private static long now(){ return System.currentTimeMillis()/1000; }
	private static String signed(){ return signed(PATH, now(), now()+60, "a"); }
	private static String signed(String path, long start, long end, String signature){
		return "https://"+HOST+path+"?q-sign-algorithm=sha1&q-ak=AKID_SYNTHETIC&q-sign-time="+start+";"+end
				+"&q-key-time="+start+";"+end+"&q-header-list=host&q-url-param-list=&q-signature="+signature.repeat(40);
	}
	private static String urlFor(int index){ return signed(PATH.replace(PHOTO, new UUID(0, index+1).toString()), now(), now()+60, "a"); }
	private static String parameter(String url, String name, String value){
		String[] parts=url.substring(url.indexOf('?')+1).split("&", -1);
		for(int i=0;i<parts.length;i++) if(parts[i].startsWith(name+"=")) parts[i]=name+"="+value;
		return url.substring(0, url.indexOf('?')+1)+String.join("&", parts);
	}
	private static String removeParameter(String url, String name){
		List<String> parts=new ArrayList<>(List.of(url.substring(url.indexOf('?')+1).split("&", -1)));
		parts.removeIf(part->part.startsWith(name+"="));
		return url.substring(0, url.indexOf('?')+1)+String.join("&", parts);
	}
	private static Field field(Class<?> type, String name) throws Exception{ Field field=type.getDeclaredField(name); field.setAccessible(true); return field; }
	private static Object cache() throws Exception{ return field(AlbumPublicCoverMemory.class, "INSTANCE").get(null); }
	@SuppressWarnings("unchecked") private static Map<Object, Object> entries(){
		try{ return (Map<Object, Object>)field(AlbumPublicCoverMemory.class, "entries").get(cache()); }
		catch(Exception error){ throw new AssertionError(error); }
	}
	private static int entryCount(){ return entries().size(); }
	private static Object onlyEntry(){ assertEquals(1, entryCount()); return entries().values().iterator().next(); }
	private static long number(Object object, String name) throws Exception{ return field(object.getClass(), name).getLong(object); }
	private static long retainedBytes(){
		try{ return number(cache(), "retainedBytes"); }
		catch(Exception error){ throw new AssertionError(error); }
	}
}
