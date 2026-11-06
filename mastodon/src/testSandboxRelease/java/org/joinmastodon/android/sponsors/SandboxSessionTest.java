package org.joinmastodon.android.sponsors;

import android.app.Application;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Set;

import static org.joinmastodon.android.sponsors.SandboxFixtures.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=31, application=Application.class)
public class SandboxSessionTest{
	private Context context;
	@Before public void setup(){
		context=RuntimeEnvironment.getApplication();
		context.getSharedPreferences(SandboxSession.IDENTITY_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
		context.getSharedPreferences("sponsor_payment_recovery", Context.MODE_PRIVATE).edit().clear().commit();
	}
	private SandboxSession session(){ return new SandboxSession(context, ORIGIN, ()->NOW); }
	@Test public void onlyPublicIdentityPersistsAndProcessRestartRequiresNewPairWithOriginalPendingOwner(){
		SandboxSession original=session(); assertNull(original.accept(pair()));
		String owner=original.owner();
		assertEquals(Set.of("origin", "run_id", "user_id"), context.getSharedPreferences(SandboxSession.IDENTITY_PREFS, Context.MODE_PRIVATE).getAll().keySet());
		var store=new SponsorPaymentCoordinator.PreferencesStore(context);
		var pending=new SponsorPaymentCoordinator.Pending(owner, OPERATION, "fixture-order-01");
		assertTrue(store.compareAndSet(owner, null, pending));
		SandboxSession restarted=session(); assertNull(restarted.lease()); assertEquals(owner, restarted.owner());
		assertTrue(SponsorPaymentCoordinator.samePending(pending, store.read(owner)));
		assertNull(restarted.accept(pair())); assertNotNull(restarted.lease()); assertEquals(owner, restarted.owner());
		assertTrue(SponsorPaymentCoordinator.samePending(pending, store.read(owner)));
	}
	@Test public void reparingResumesUnchangedCoordinatorByOriginalOrderOnlyAndUnknownNeverClears(){
		SandboxSession first=session(); assertNull(first.accept(pair())); String owner=first.owner();
		var store=new SponsorPaymentCoordinator.PreferencesStore(context);
		var pending=new SponsorPaymentCoordinator.Pending(owner, OPERATION, "fixture-order-01");
		assertTrue(store.compareAndSet(owner, null, pending));
		SandboxSession restarted=session(); assertNull(restarted.lease()); assertNull(restarted.accept(pair()));
		var source=new SponsorPaymentCoordinatorTest.Source(); source.catalog=catalog(); source.options=options(); source.membership=me();
		var launcher=new SponsorPaymentCoordinatorTest.Launcher(); var clock=new SponsorPaymentCoordinatorTest.Clock();
		var lease=restarted.lease();
		var coordinator=new SponsorPaymentCoordinator(owner, SandboxProtocol.PLAN, source, launcher, store, clock,
				()->restarted.valid(lease), new SponsorPaymentCoordinator.Listener(){
			@Override public void changed(){}
			@Override public void granted(org.joinmastodon.android.model.sponsors.SponsorModels.Me me){ fail("Unknown is not granted"); }
			@Override public void openAfdian(){ fail("No sandbox Afdian"); }
		});
		coordinator.resume(); assertEquals(1, source.queries); assertEquals("fixture-order-01", source.queriedId);
		var unknown=order(); unknown.paymentState="UNKNOWN"; source.query.success(unknown);
		coordinator.pay(); assertEquals(0, source.creates); assertEquals(0, launcher.calls); assertEquals(0, source.currents);
		assertTrue(SponsorPaymentCoordinator.samePending(pending, store.read(owner))); assertFalse(coordinator.canPay());
		coordinator.pause(); coordinator.destroy(); assertTrue(SponsorPaymentCoordinator.samePending(pending, store.read(owner)));
	}
	@Test public void ownerChangeWithPendingIsDeniedWithoutClearingUnknownAndSameOwnerCanRenew(){
		SandboxSession session=session(); assertNull(session.accept(pair())); String original=session.owner();
		var store=new SponsorPaymentCoordinator.PreferencesStore(context);
		var pending=new SponsorPaymentCoordinator.Pending(original, OPERATION, null);
		assertTrue(store.compareAndSet(original, null, pending));
		var changed=pair(); changed.runId=OTHER_RUN;
		assertEquals("pending_owner_changed", session.accept(changed)); assertEquals(original, session.owner());
		assertTrue(SponsorPaymentCoordinator.samePending(pending, store.read(original)));
		assertNull(session.accept(pair()));
		assertTrue(SponsorPaymentCoordinator.samePending(pending, store.read(original)));
	}
	@Test public void malformedOrOrphanedRecoveryIsRetainedAndBlocksPairing(){
		context.getSharedPreferences("sponsor_payment_recovery", Context.MODE_PRIVATE).edit().putString("payment.orphan.owner", "orphan").commit();
		SandboxSession session=session(); assertEquals("pending_owner_changed", session.accept(pair()));
		assertEquals("orphan", context.getSharedPreferences("sponsor_payment_recovery", Context.MODE_PRIVATE).getString("payment.orphan.owner", null));
		assertNull(session.lease());
	}
	@Test public void freshOwnerIsAllowedOnlyWhenNoRecoveryExistsAndOldLeaseIsFenced(){
		SandboxSession session=session(); assertNull(session.accept(pair())); var old=session.lease();
		var next=pair(); next.runId=OTHER_RUN; assertNull(session.accept(next));
		assertFalse(session.valid(old)); assertTrue(session.valid(session.lease()));
		assertEquals(SandboxSession.owner(ORIGIN, OTHER_RUN, "101"), session.owner());
	}
	@Test public void expiryAndUnauthorizedDiscardMemoryOnlyAndKeepRecovery(){
		long[] now={NOW}; SandboxSession session=new SandboxSession(context, ORIGIN, ()->now[0]);
		assertNull(session.accept(pair())); var lease=session.lease();
		var store=new SponsorPaymentCoordinator.PreferencesStore(context);
		var pending=new SponsorPaymentCoordinator.Pending(session.owner(), OPERATION, "fixture-order-01");
		assertTrue(store.compareAndSet(session.owner(), null, pending));
		session.invalidate(lease); assertNull(session.lease());
		assertNull(session.accept(pair())); now[0]+=3600; assertNull(session.lease());
		assertTrue(SponsorPaymentCoordinator.samePending(pending, store.read(session.owner())));
	}
}
