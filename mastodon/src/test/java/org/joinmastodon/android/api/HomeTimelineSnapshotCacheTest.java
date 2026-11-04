package org.joinmastodon.android.api;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.cache.HomeTimelineSnapshot;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.NBWForumOptionsRepository;
import org.joinmastodon.android.events.AccountLoggedOutEvent;
import org.joinmastodon.android.events.RemoveAccountPostsEvent;
import org.joinmastodon.android.events.StatusUpdatedEvent;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Advertisement;
import org.joinmastodon.android.model.Quote;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.model.Token;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Production controller and private SQLite files, including queued CAS writes. No HTTP. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class HomeTimelineSnapshotCacheTest{
	private AccountSession session;
	private CacheController cache;
	private Map<String, AccountSession> sessions;
	private String previousActive;
	private final List<AccountSession> accounts=new ArrayList<>();

	public static Field field(Class<?> type, String name) throws Exception{ Field field=type.getDeclaredField(name); field.setAccessible(true); return field; }
	public static AccountSession session(String domain, String id) throws Exception{
		var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true);
		AccountSession session=constructor.newInstance(); session.domain=domain;
		session.self=new Account(); session.self.id=id; session.self.username=session.self.acct="fictional";
		session.self.displayName="Fictional reader"; session.self.emojis=List.of();
		session.token=new Token(); session.token.accessToken="fictional-token-"+id;
		return session;
	}
	public static Status post(String id){
		Status status=Status.ofFake(id, "<p>A fictional walk in the park.</p>", Instant.parse("2026-11-01T00:00:00Z"));
		status.uri="https://home-cache.offline.example.test/posts/"+id;
		status.account=new Account(); status.account.id="fictional-author";
		status.account.username=status.account.acct="fictional-author"; status.account.displayName="Fictional author";
		status.account.url="https://home-cache.offline.example.test/@fictional-author"; status.account.emojis=List.of();
		status.language="en";
		return status;
	}
	public static void drain() throws Exception{
		CountDownLatch done=new CountDownLatch(1); CacheController.databaseThread.postRunnable(done::countDown, 0);
		assertTrue("Database queue must finish", done.await(5, TimeUnit.SECONDS)); ShadowLooper.idleMainLooper();
	}
	private NBWEndpointFixtures endpoints;
	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		endpoints=new NBWEndpointFixtures();
		MastodonApp.context=RuntimeEnvironment.getApplication(); me.grishka.appkit.utils.V.setApplicationContext(MastodonApp.context);
		var manager=AccountSessionManager.getInstance(); sessions=(Map<String,AccountSession>)field(AccountSessionManager.class,"sessions").get(manager);
		previousActive=manager.getLastActiveAccountID(); session=add("home-cache.offline.example.test","home-cache-1");
		field(AccountSessionManager.class,"lastActiveAccountID").set(manager,session.getID()); cache=session.getCacheController();
	}
	private AccountSession add(String domain, String id) throws Exception{
		AccountSession value=session(domain,id); sessions.put(value.getID(),value); accounts.add(value); return value;
	}
	@After public void cleanup() throws Exception{
		drain();
		for(AccountSession account:accounts){ sessions.remove(account.getID()); account.getCacheController().closeDatabase(); MastodonApp.context.deleteDatabase(account.getID()+".db"); }
		field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),previousActive);
		for(AccountSession account:accounts) account.deactivateNBW(); endpoints.close();
	}
	private HomeTimelineSnapshot read(CacheController controller) throws Exception{
		AtomicReference<HomeTimelineSnapshot> result=new AtomicReference<>();
		controller.getHomeTimelineSnapshot(controller.beginHomeTimelineSnapshot(),result::set); drain(); return result.get();
	}
	private void raw(String value) throws Exception{
		// Open through the real helper first so this also tests the existing misc schema.
		read(cache);
		try(SQLiteDatabase database=SQLiteDatabase.openDatabase(MastodonApp.context.getDatabasePath(session.getID()+".db").getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
			ContentValues row=new ContentValues(); row.put("key",HomeTimelineSnapshot.KEY); row.put("value",value);
			database.insertWithOnConflict("misc",null,row,SQLiteDatabase.CONFLICT_REPLACE);
		}
	}
	private int storedRows(){
		try(SQLiteDatabase database=SQLiteDatabase.openDatabase(MastodonApp.context.getDatabasePath(session.getID()+".db").getPath(),null,SQLiteDatabase.OPEN_READONLY);
			Cursor cursor=database.rawQuery("SELECT count(*) FROM misc WHERE `key`=?",new String[]{HomeTimelineSnapshot.KEY})){
			cursor.moveToFirst(); return cursor.getInt(0);
		}
	}

	@Test public void shortPageHitsInServerOrderAndKeepsOpaqueCursorAcrossReopen() throws Exception{
		cache.putHomeTimelineSnapshot(cache.beginHomeTimelineSnapshot(),List.of(post("c_9"),post("p_2")),"opaque+/cursor=not-a-status-id"); drain(); cache.closeDatabase();
		HomeTimelineSnapshot result=read(cache); assertNotNull(result);
		assertEquals(List.of("c_9","p_2"),result.posts.stream().map(s->s.id).toList()); assertEquals("opaque+/cursor=not-a-status-id",result.nextMaxID);
	}
	@Test public void filteringIsRecursiveAndNeverPersistsPrivateDirectAdsOrPaidProjection() throws Exception{
		Status privatePost=post("p_private"); privatePost.visibility=StatusPrivacy.PRIVATE;
		Status direct=post("p_direct"); direct.visibility=StatusPrivacy.DIRECT;
		Status ad=post("p_ad"); ad.advertisement=new Advertisement();
		Status boosted=post("p_boost"); boosted.reblog=direct;
		Status quoted=post("p_quote"); quoted.quote=new Quote(); quoted.quote.quotedStatus=ad;
		cache.putHomeTimelineSnapshot(cache.beginHomeTimelineSnapshot(),List.of(privatePost,direct,ad,boosted,quoted,post("p_safe")),"original-cursor"); drain();
		assertEquals(List.of("p_safe"),read(cache).posts.stream().map(s->s.id).toList());
	}
	@Test public void allBadExpiredFutureWrongFormatAndOversizedRowsAreDeletedWhole() throws Exception{
		String identity=HomeTimelineSnapshot.identity(session.getID(),session.token.accessToken);
		long now=System.currentTimeMillis(); String valid=HomeTimelineSnapshot.encode(List.of(post("p_valid")),null,identity,now,false);
		for(String bad:List.of("{broken",valid.replace("\"format\":1","\"format\":2"),
				HomeTimelineSnapshot.encode(List.of(post("p_old")),null,identity,now-HomeTimelineSnapshot.MAX_AGE_MS-2000,false),
				HomeTimelineSnapshot.encode(List.of(post("p_future")),null,identity,now+60000,false),"中".repeat(HomeTimelineSnapshot.MAX_UTF8_BYTES/2))){
			raw(bad); assertNull(read(cache)); assertEquals(0,storedRows());
		}
	}
	@Test public void malformedSingleEntryDiscardsWholeSnapshot() throws Exception{
		String json=HomeTimelineSnapshot.encode(List.of(post("p_good"),post("c_broken")),null,HomeTimelineSnapshot.identity(session.getID(),session.token.accessToken),System.currentTimeMillis(),false);
		raw(json.replace("\"id\":\"c_broken\"","\"id\":null")); assertNull(read(cache)); assertEquals(0,storedRows());
	}
	@Test public void countUtf8DepthAndTransientTranslationsAreBounded() throws Exception{
		List<Status> posts=new ArrayList<>(); for(int i=0;i<70;i++) posts.add(post("p_"+i));
		posts.get(0).translation=new org.joinmastodon.android.model.Translation(); posts.get(0).translation.content="must-not-persist";
		posts.get(0).translationState=Status.TranslationState.SHOWN;
		cache.putHomeTimelineSnapshot(cache.beginHomeTimelineSnapshot(),posts,"cursor"); drain(); HomeTimelineSnapshot result=read(cache);
		assertEquals(40,result.posts.size()); assertNull(result.posts.get(0).translation); assertEquals(Status.TranslationState.HIDDEN,result.posts.get(0).translationState);
		Status huge=post("p_huge"); huge.content="中".repeat(HomeTimelineSnapshot.MAX_POST_UTF8_BYTES);
		String json=HomeTimelineSnapshot.encode(List.of(huge,post("p_small")),null,"digest",System.currentTimeMillis(),false);
		assertTrue(json.getBytes(StandardCharsets.UTF_8).length<=HomeTimelineSnapshot.MAX_UTF8_BYTES); assertFalse(json.contains("p_huge"));
		Status deep=post("p_deep"), pointer=deep; for(int i=0;i<8;i++){ pointer.reblog=post("p_nested"+i); pointer=pointer.reblog; }
		assertNull(HomeTimelineSnapshot.encode(List.of(deep),null,"digest",System.currentTimeMillis(),false));
	}
	@Test public void differentAccountsAndRotatedTokenCannotHydrateEachOther() throws Exception{
		cache.putHomeTimelineSnapshot(cache.beginHomeTimelineSnapshot(),List.of(post("p_first")),null); drain();
		AccountSession second=add("home-cache.offline.example.test","home-cache-2"); assertNull(read(second.getCacheController()));
		session.token.accessToken="replacement-token"; assertNull(read(cache)); assertEquals(0,storedRows());
	}
	@Test public void deleteNbwAndBlockUpdateEventsInvalidateQueuedLeaseWrites() throws Exception{
		for(Runnable invalidate:List.<Runnable>of(()->cache.deleteStatus("p_1"),cache::clearNBWRemoteCache,
				()->cache.new HomeSnapshotEvents().removed(new RemoveAccountPostsEvent(session.getID(),"owner",false)),
				()->cache.new HomeSnapshotEvents().updated(new StatusUpdatedEvent(post("p_1"))))){
			var lease=cache.beginHomeTimelineSnapshot(); cache.putHomeTimelineSnapshot(lease,List.of(post("p_1")),null); drain();
			invalidate.run(); cache.putHomeTimelineSnapshot(lease,List.of(post("p_stale")),null); drain(); assertNull(read(cache));
		}
	}
	@Test public void logoutDeletesDatabaseAndRejectsOldAsyncWriteAndSameIdNewSession() throws Exception{
		var lease=cache.beginHomeTimelineSnapshot(); cache.putHomeTimelineSnapshot(lease,List.of(post("p_1")),null); drain();
		sessions.remove(session.getID()); cache.closeDatabase(); MastodonApp.context.deleteDatabase(session.getID()+".db");
		cache.putHomeTimelineSnapshot(lease,List.of(post("p_resurrect")),null); drain(); assertFalse(MastodonApp.context.getDatabasePath(session.getID()+".db").exists());
		sessions.put(session.getID(),session(session.domain,session.self.id)); assertNull(cache.beginHomeTimelineSnapshot());
	}
	@Test public void regularCloseBeforeFirstLeaseDoesNotRetireAndEmptyAuthorityClearsHistory() throws Exception{
		cache.putRecentSearch(new org.joinmastodon.android.model.SearchResult(post("p_search"))); drain(); cache.closeDatabase();
		assertNotNull(cache.beginHomeTimelineSnapshot()); cache.putHomeTimelineSnapshot(cache.beginHomeTimelineSnapshot(),List.of(post("p_1")),null); drain();
		cache.putHomeTimelineSnapshot(cache.beginHomeTimelineSnapshot(),List.of(),null); drain(); assertNull(read(cache));
	}
	@Test public void nbwGateAllowsOrderedMixedHeadOnlyWhileCurrentlyReadable() throws Exception{
		NBWForumOptionsRepository repository=new NBWForumOptionsRepository(new NBWForumOptionsRepository.Transport(){
			public NBWForumOptionsRepository.Cancelable capabilities(NBWForumOptionsRepository.Result<org.joinmastodon.android.model.NBWCapabilities> result){ result.success(NBWV2RepositoryTest.capabilities("full")); return ()->{}; }
			public NBWForumOptionsRepository.Cancelable options(NBWForumOptionsRepository.Result<org.joinmastodon.android.model.NBWForumOptions> result){ result.success(NBWV2RepositoryTest.options()); return ()->{}; }
			public NBWForumOptionsRepository.Cancelable consent(boolean accepted,NBWForumOptionsRepository.Result<Object> result){ throw new AssertionError(); }
		},()->0L,new NBWForumOptionsRepository.SelectionStore(){
			public org.joinmastodon.android.model.NBWSelection load(){ return org.joinmastodon.android.model.NBWSelection.ai(); }
			public void save(org.joinmastodon.android.model.NBWSelection selection){}
		},cache::clearNBWRemoteCache,Runnable::run);
		field(AccountSession.class,"nbwForumOptionsRepository").set(session,repository); repository.activate();
		cache.putHomeTimelineSnapshot(cache.beginHomeTimelineSnapshot(),List.of(post("p_2"),post("nbw_10"),post("c_3")),"mixed-cursor"); drain();
		assertEquals(List.of("p_2","nbw_10","c_3"),read(cache).posts.stream().map(s->s.id).toList());
		repository.deactivate(); drain(); assertNull(read(cache));
	}
	@Test public void repositoryLockAndDatabaseLeaseReadCannotInvertOrResurrectInvalidatedWrite() throws Exception{
		var lease=cache.beginHomeTimelineSnapshot(); var repository=session.getNBWForumOptionsRepository();
		CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), finished=new CountDownLatch(1);
		CacheController.databaseThread.postRunnable(()->{ entered.countDown(); try{ release.await(); }catch(InterruptedException e){ Thread.currentThread().interrupt(); } },0);
		assertTrue(entered.await(5,TimeUnit.SECONDS));
		cache.getHomeTimelineSnapshot(lease,result->{});
		Thread invalidator=new Thread(()->{ synchronized(repository){ release.countDown(); cache.clearNBWRemoteCache(); } finished.countDown(); });
		invalidator.start(); assertTrue("Repository-to-cache invalidation must not deadlock the queued cache read",finished.await(5,TimeUnit.SECONDS)); invalidator.join();
		cache.putHomeTimelineSnapshot(lease,List.of(post("p_stale")),null); drain(); assertNull(read(cache));
	}
}
