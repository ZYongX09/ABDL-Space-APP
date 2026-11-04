package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.session.NBWEndpointsRepository;
import org.joinmastodon.android.api.session.NBWForumOptionsRepository;
import org.joinmastodon.android.model.NBWEndpoints;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=CompatibilityTestApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class NBWEndpointsRepositoryTest{
	private long now;
	private List<NBWForumOptionsRepository.Result<NBWEndpoints>> calls;
	private NBWEndpointsRepository repository;
	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication(); calls=new ArrayList<>();
		repository=new NBWEndpointsRepository(result->{ calls.add(result); return ()->{}; },()->now,Runnable::run);
	}
	@Test public void identityFreeSingleFlightWaitersAndForegroundTtlHaveOneTimer(){
		Object activity=new Object(); List<Long> revisions=new ArrayList<>();
		var subscription=repository.observe(s->revisions.add(s.generation));
		repository.enterForeground(activity); repository.enterForeground(activity); repository.refreshNow(s->assertTrue(s.canWrite()));
		assertEquals(1,calls.size()); assertFalse(repository.getSnapshot().canRead());
		calls.get(0).success(NBWEndpointFixtures.primary()); assertTrue(repository.getSnapshot().fresh);
		now=29000; ShadowLooper.idleMainLooper(29,TimeUnit.SECONDS); assertEquals(2,calls.size()); assertTrue(repository.getSnapshot().canRead());
		repository.refreshIfExpired(); assertEquals(2,calls.size()); calls.get(1).success(NBWEndpointFixtures.primary());
		repository.leaveForeground(activity); repository.leaveForeground(activity);
		now+=60000; ShadowLooper.idleMainLooper(60,TimeUnit.SECONDS); assertEquals(2,calls.size());
		subscription.cancel();
	}
	@Test public void earlyRenewalKeepsSameRevisionVisibleButHeldOrFailedNetworkExpiresAtOriginalDeadline(){
		Object owner=new Object(); List<Boolean> readable=new ArrayList<>(); var observer=repository.observe(s->readable.add(s.canRead()));
		repository.enterForeground(owner); calls.get(0).success(NBWEndpointFixtures.primary()); readable.clear();
		now=29000; ShadowLooper.idleMainLooper(29,TimeUnit.SECONDS); assertEquals(2,calls.size()); assertEquals(List.of(true),readable);
		now=30000; ShadowLooper.idleMainLooper(1,TimeUnit.SECONDS); assertFalse(repository.getSnapshot().canRead()); assertEquals(List.of(true,false),readable);
		now=31000; calls.get(1).success(NBWEndpointFixtures.primary()); assertTrue(repository.getSnapshot().canRead());
		now=60000; ShadowLooper.idleMainLooper(29,TimeUnit.SECONDS); assertEquals(3,calls.size()); calls.get(2).failure();
		assertTrue("Failed early GET must not shorten original lease",repository.getSnapshot().canRead());
		now=61000; ShadowLooper.idleMainLooper(1,TimeUnit.SECONDS); assertFalse(repository.getSnapshot().canRead());
		observer.cancel(); repository.leaveForeground(owner);
	}
	@Test public void failedAuthorizationPreflightCannotUseAStillFreshOldLease(){
		repository.refreshNow(); calls.get(0).success(NBWEndpointFixtures.primary());
		boolean[] allowed={true}; repository.refreshNow(value->allowed[0]=value.canWrite()); calls.get(1).failure();
		assertFalse(allowed[0]); assertTrue(repository.getSnapshot().canRead());
	}
	@Test public void removingLastObserverAndPausingStopBackgroundPolling(){
		Object owner=new Object(); var observer=repository.observe(s->{}); repository.enterForeground(owner);
		calls.get(0).success(NBWEndpointFixtures.primary()); observer.cancel(); now=60000;
		ShadowLooper.idleMainLooper(60,TimeUnit.SECONDS); assertEquals(1,calls.size());
		repository.leaveForeground(owner);
	}
	@Test public void sameModeRevisionAndAbaChangeGenerationButRefreshOfSameSnapshotDoesNot(){
		repository.refreshNow(); calls.get(0).success(NBWEndpointFixtures.primary()); long a=repository.getSnapshot().generation;
		repository.refreshNow(); calls.get(1).success(NBWEndpointFixtures.primary()); assertEquals(a,repository.getSnapshot().generation);
		repository.refreshNow(); calls.get(2).success(NBWEndpointFixtures.endpoints("primary","endpoint-b","full")); long b=repository.getSnapshot().generation; assertTrue(b>a);
		repository.refreshNow(); calls.get(3).success(NBWEndpointFixtures.endpoints("backup","endpoint-c","full"));
		repository.refreshNow(); calls.get(4).success(NBWEndpointFixtures.endpoints("primary","endpoint-d","full")); assertTrue(repository.getSnapshot().generation>b);
		assertEquals("endpoint-d",repository.getSnapshot().endpoints.revision);
	}
	@Test public void malformedMissingAndLegacyDiscoveryFailClosedWithoutPrimaryFabrication(){
		for(int i=0;i<3;i++){
			repository.refreshNow(); calls.get(calls.size()-1).failure(); assertNull(repository.getSnapshot().endpoints); assertFalse(repository.getSnapshot().canWrite());
		}
		repository.refreshNow(); calls.get(3).success(new NBWEndpoints(1,"bad","primary",NBWEndpoints.BACKUP,NBWEndpoints.PRIMARY,NBWEndpoints.PRIMARY,NBWEndpoints.PRIMARY,30,"full"));
		assertNull(repository.getSnapshot().endpoints);
	}
	@Test public void readOnlyAndLocalOnlyDoNotAccidentallyGrantLogin(){
		repository.refreshNow(); calls.get(0).success(NBWEndpointFixtures.endpoints("backup","r1","read_only")); assertTrue(repository.getSnapshot().canRead()); assertFalse(repository.getSnapshot().canWrite());
		repository.refreshNow(); calls.get(1).success(NBWEndpointFixtures.endpoints("backup","r2","local_only")); assertFalse(repository.getSnapshot().canRead());
	}
}
