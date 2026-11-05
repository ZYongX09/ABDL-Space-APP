package org.joinmastodon.android.sponsors;

import org.joinmastodon.android.model.sponsors.SponsorModels.*;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels.*;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.joinmastodon.android.sponsors.SponsorPaymentFixtures.*;
import static org.junit.Assert.*;

/** Isolated state-machine tests: no SDK, merchant, network or entitlement mutation. */
public class SponsorPaymentCoordinatorTest{
	static final class Source implements SponsorPaymentCoordinator.Source{
		Catalog catalog=SponsorPaymentFixtures.catalog(); Me membership=SponsorPaymentFixtures.me(false); Options options=SponsorPaymentFixtures.options(null);
		SponsorPaymentCoordinator.Failure optionsFailure;
		SponsorPaymentCoordinator.Result<Order> create, query, close;
		SponsorPaymentCoordinator.Result<CurrentOrder> current;
		int catalogs, mes, optionsCalls, creates, queries, currents, closes;
		String operation, queriedId;
		@Override public SponsorPaymentCoordinator.Cancelable catalog(SponsorPaymentCoordinator.Result<Catalog> result){ catalogs++; result.success(catalog); return ()->{}; }
		@Override public SponsorPaymentCoordinator.Cancelable me(SponsorPaymentCoordinator.Result<Me> result){ mes++; result.success(membership); return ()->{}; }
		@Override public SponsorPaymentCoordinator.Cancelable options(String id, int version, SponsorPaymentCoordinator.Result<Options> result){ optionsCalls++; if(optionsFailure==null) result.success(options); else result.failure(optionsFailure); return ()->{}; }
		@Override public SponsorPaymentCoordinator.Cancelable create(String id, int version, String operation, SponsorPaymentCoordinator.Result<Order> result){ creates++; this.operation=operation; create=result; return ()->{}; }
		@Override public SponsorPaymentCoordinator.Cancelable current(SponsorPaymentCoordinator.Result<CurrentOrder> result){ currents++; current=result; return ()->{}; }
		@Override public SponsorPaymentCoordinator.Cancelable order(String id, SponsorPaymentCoordinator.Result<Order> result){ queries++; queriedId=id; query=result; return ()->{}; }
		@Override public SponsorPaymentCoordinator.Cancelable close(String id, SponsorPaymentCoordinator.Result<Order> result){ closes++; close=result; return ()->{}; }
	}
	static final class Launcher implements SponsorPaymentCoordinator.PaymentLauncher{
		boolean available=true; int calls, canceled; Consumer<String> callback;
		@Override public boolean available(){ return available; }
		@Override public SponsorPaymentCoordinator.Cancelable pay(String signed, Consumer<String> completed){ calls++; assertEquals("fixture-only-signed-order", signed); callback=completed; return ()->canceled++; }
	}
	static final class Store implements SponsorPaymentCoordinator.Store{
		SponsorPaymentCoordinator.Pending value; boolean writable=true;
		@Override public SponsorPaymentCoordinator.Pending read(String owner){ return value; }
		@Override public boolean compareAndSet(String owner, SponsorPaymentCoordinator.Pending expected, SponsorPaymentCoordinator.Pending pending){
			if(!writable || !SponsorPaymentCoordinator.samePending(value, expected)) return false; value=pending; return true;
		}
	}
	static final class Clock implements SponsorPaymentCoordinator.Scheduler{
		long now=10000; final List<Runnable> jobs=new ArrayList<>();
		@Override public long now(){ return now; }
		@Override public void post(Runnable task, long delay){ if(!jobs.contains(task)) jobs.add(task); }
		@Override public void remove(Runnable task){ jobs.remove(task); }
		void next(){ now+=2000; List<Runnable> current=new ArrayList<>(jobs); jobs.clear(); current.forEach(Runnable::run); }
	}
	static final class Harness implements SponsorPaymentCoordinator.Listener{
		final Source source=new Source(); final Launcher sdk=new Launcher(); final Store store=new Store(); final Clock clock=new Clock();
		boolean valid=true; int granted, afdian, changed;
		SponsorPaymentCoordinator coordinator;
		Harness(){ build(); }
		void build(){ coordinator=new SponsorPaymentCoordinator("owner@fixture", plan().id, source, sdk, store, clock, ()->valid, this); }
		void start(){ coordinator.resume(); }
		void create(){ start(); coordinator.pay(); Order pending=order("PENDING_PAYMENT", "PENDING"); pending.orderStr="fixture-only-signed-order"; source.create.success(pending); assertNull(pending.orderStr); }
		@Override public void changed(){ changed++; }
		@Override public void granted(Me value){ granted++; assertTrue(value.sponsor.isActive()); }
		@Override public void openAfdian(){ afdian++; }
	}

	@Test public void defaultsOnlyToAvailableAlipayAndKeepsDisabledReasons(){
		Harness h=new Harness(); h.start(); assertEquals("alipay", h.coordinator.selected());
		assertTrue(h.coordinator.canPay());
		Harness disabled=new Harness(); disabled.source.options=options("limits_unverified"); disabled.start();
		assertTrue(disabled.coordinator.showAlipay()); assertEquals("limits_unverified", disabled.coordinator.disabledReason("alipay"));
		assertEquals("afdian", disabled.coordinator.selected()); disabled.coordinator.pay(); assertEquals(1, disabled.afdian); assertEquals(0, disabled.source.creates);
		Harness amount=new Harness(); amount.source.options=options("amount_unsupported"); amount.start();
		assertFalse(amount.coordinator.showAlipay()); assertEquals("afdian", amount.coordinator.selected());
	}
	@Test public void legacy404And501RevalidateBothCatalogAndMeNeverPost(){
		for(int status:new int[]{404, 501}){
			Harness h=new Harness(); h.source.optionsFailure=new SponsorPaymentCoordinator.Failure(status, "not_found"); h.start();
			assertEquals(2, h.source.catalogs); assertEquals(2, h.source.mes); assertTrue(h.coordinator.fallback());
			assertFalse(h.coordinator.showAlipay()); assertEquals("afdian", h.coordinator.selected());
			h.coordinator.select("alipay"); h.coordinator.pay(); assertEquals(0, h.source.creates); assertEquals(1, h.afdian);
		}
	}
	@Test public void invalidFallbackLinkAndStaleMeCannotPay(){
		Harness h=new Harness(); h.source.catalog.plans.get(0).purchaseUrl="https://evil.example/";
		h.source.optionsFailure=new SponsorPaymentCoordinator.Failure(404, "not_found"); h.start(); assertFalse(h.coordinator.canPay());
		Harness stale=new Harness(); stale.source.membership.configVersion=999; stale.start();
		assertEquals("plan_changed", stale.coordinator.problem()); assertEquals(0, stale.source.optionsCalls); assertFalse(stale.coordinator.canPay());
	}
	@Test public void operationIsDurableBeforeOnePostAndDoubleTapsLaunchOneSdk(){
		Harness h=new Harness(); h.start(); h.coordinator.pay(); h.coordinator.pay();
		assertEquals(1, h.source.creates); assertEquals(h.source.operation, h.store.value.operation);
		assertEquals(java.util.UUID.fromString(h.source.operation).toString(), h.source.operation); assertNull(h.store.value.orderId);
		Order pending=order("PENDING_PAYMENT", "PENDING"); pending.orderStr="fixture-only-signed-order"; h.source.create.success(pending);
		assertNull(pending.orderStr); assertEquals(pending.orderId, h.store.value.orderId); assertEquals(1, h.sdk.calls);
		h.coordinator.pay(); h.coordinator.select("afdian"); assertEquals(1, h.sdk.calls); assertEquals(0, h.afdian);
	}
	@Test public void cannotCreateWhenDurableRecoveryWriteFails(){
		Harness h=new Harness(); h.store.writable=false; h.start(); h.coordinator.pay();
		assertEquals(0, h.source.creates); assertEquals("storage_failed", h.coordinator.problem()); assertFalse(h.coordinator.canPay());
	}
	@Test public void sdkSuccessDuringPauseStillQueriesButCannotGrantUntilServerAndFreshMe(){
		Harness h=new Harness(); h.create(); int initialMe=h.source.mes;
		h.coordinator.pause(); h.sdk.callback.accept("9000"); assertEquals(1, h.source.queries); assertEquals(0, h.granted);
		h.source.query.success(order("PAID", "PENDING")); assertEquals(initialMe, h.source.mes); assertFalse(h.coordinator.canPay()); assertEquals(0, h.granted);
		h.coordinator.resume(); h.clock.next(); h.source.membership=me(true); h.source.query.success(order("PAID", "GRANTED"));
		assertEquals(initialMe+1, h.source.mes); assertEquals(1, h.granted); assertTrue(h.coordinator.confirmed()); assertNull(h.store.value);
	}
	@Test public void everySdkStatusQueriesSameOrderNeverGrantsOrAutomaticallyPaysAgain(){
		for(String status:List.of("9000", "8000", "6001", "4000", "6002", "6004", "5000", "UNKNOWN")){
			Harness h=new Harness(); h.create(); h.sdk.callback.accept(status);
			assertEquals(1, h.source.queries); assertEquals("fixture-order-01", h.source.queriedId); assertEquals(0, h.granted);
			h.source.query.success(order("UNKNOWN", "PENDING"));
			h.coordinator.select("afdian"); h.coordinator.pay(); assertFalse(h.coordinator.canSwitch());
			assertEquals(1, h.sdk.calls); assertEquals(1, h.source.creates); assertEquals(0, h.afdian); assertNotNull(h.store.value);
		}
	}
	@Test public void processRecoveryAndLostCreateResponseOnlyQueryNeverSdkOrNewPost(){
		Harness h=new Harness(); h.start(); h.coordinator.pay(); assertNotNull(h.store.value.operation);
		h.source.create.failure(new SponsorPaymentCoordinator.Failure(-1, "request_failed"));
		assertEquals(1, h.source.currents); h.source.current.success(current(null));
		assertEquals("payment_result_unknown", h.coordinator.problem()); assertFalse(h.coordinator.canPay());
		h.coordinator.destroy(); h.build(); h.start(); assertEquals(2, h.source.currents);
		h.source.current.success(current(order("PENDING_PAYMENT", "PENDING"))); assertEquals(0, h.sdk.calls); assertEquals(1, h.source.creates);
		assertFalse(h.coordinator.canPay()); assertNotNull(h.store.value.orderId);
	}
	@Test public void accountChangeAndDestroyRejectLateCallbacks(){
		Harness h=new Harness(); h.create(); h.valid=false; h.sdk.callback.accept("9000");
		assertEquals(0, h.source.queries); assertEquals(0, h.granted); assertEquals(1, h.sdk.canceled); assertNotNull(h.store.value);
		Harness dead=new Harness(); dead.create(); dead.coordinator.destroy(); dead.sdk.callback.accept("9000");
		assertEquals(0, dead.source.queries); assertEquals(0, dead.granted);
	}
	@Test public void explicitCloseMustConfirmClosedBeforeChoiceAndNeverSelectsAfdianAutomatically(){
		Harness h=new Harness(); h.create(); h.sdk.callback.accept("6001"); h.source.query.success(order("PENDING_PAYMENT", "PENDING"));
		assertTrue(h.coordinator.canSwitch()); assertFalse(h.coordinator.order().canSwitchChannel);
		h.coordinator.switchChannel(); assertEquals(1, h.source.closes); assertFalse(h.coordinator.canPay());
		h.source.close.success(order("UNKNOWN", "PENDING")); assertFalse(h.coordinator.canSwitch()); assertFalse(h.coordinator.canPay());
		Harness closed=new Harness(); closed.create(); closed.sdk.callback.accept("6001"); closed.source.query.success(order("PENDING_PAYMENT", "PENDING"));
		closed.coordinator.switchChannel(); closed.source.close.success(order("CLOSED", "PENDING"));
		assertNull(closed.store.value); assertNull(closed.coordinator.selected()); assertFalse(closed.coordinator.canPay()); assertEquals(0, closed.afdian);
		closed.coordinator.select("afdian"); assertTrue(closed.coordinator.canPay()); closed.coordinator.pay(); assertEquals(1, closed.afdian);
	}
	@Test public void paidManualAndRefundReviewNeverUnlockSwitch(){
		for(String fulfillment:List.of("PENDING", "MANUAL_REVIEW", "REFUND_REVIEW")){
			Harness h=new Harness(); h.create(); h.sdk.callback.accept("9000"); h.source.query.success(order("PAID", fulfillment));
			assertFalse(h.coordinator.canPay()); assertFalse(h.coordinator.canSwitch()); assertEquals(0, h.granted);
		}
	}
	@Test public void automaticReconciliationIsBoundedAndResumeNeverReinvokesSdk(){
		Harness h=new Harness(); h.create(); h.sdk.callback.accept("8000");
		for(int i=0;i<SponsorPaymentCoordinator.MAX_QUERIES;i++){
			h.source.query.success(order("UNKNOWN", "PENDING")); h.clock.next();
		}
		assertEquals(SponsorPaymentCoordinator.MAX_QUERIES, h.source.queries); assertTrue(h.clock.jobs.isEmpty());
		h.coordinator.pause(); h.coordinator.resume(); h.clock.next();
		assertEquals(1, h.sdk.calls); assertEquals(1, h.source.creates); assertEquals(SponsorPaymentCoordinator.MAX_QUERIES+1, h.source.queries);
	}
	@Test public void malformedRecoveryNeverIssuesAnyRequestAndPreservesRecord(){
		for(SponsorPaymentCoordinator.Pending bad:List.of(
				new SponsorPaymentCoordinator.Pending("other-owner", "65adffb9-98bf-46b0-9fca-59a3bc6afdd0", "fixture-order-01"),
				new SponsorPaymentCoordinator.Pending("owner@fixture", "not-uuid", null),
				new SponsorPaymentCoordinator.Pending("owner@fixture", null, "../current"))){
			Harness h=new Harness(); h.store.value=bad; h.build(); h.start(); h.coordinator.retry();
			assertSame(bad, h.store.value); assertEquals(0, h.source.catalogs); assertEquals(0, h.source.mes);
			assertEquals(0, h.source.queries); assertEquals(0, h.source.currents); assertEquals(0, h.source.creates);
			assertFalse(h.coordinator.canPay()); assertEquals("storage_failed", h.coordinator.problem());
		}
	}
	@Test public void newOperationAfterClosedOrderNeverQueriesOldTerminalOrderWhenCreateResponseIsLost(){
		Harness h=new Harness(); h.create(); h.sdk.callback.accept("6001"); h.source.query.success(order("PENDING_PAYMENT", "PENDING"));
		h.coordinator.switchChannel(); h.source.close.success(order("CLOSED", "PENDING")); h.coordinator.select("alipay"); h.coordinator.pay();
		String nextOperation=h.store.value.operation; assertNull(h.store.value.orderId);
		h.source.create.failure(new SponsorPaymentCoordinator.Failure(-1, "request_failed")); h.clock.next();
		assertEquals("New operation without an order ID must use current", 1, h.source.currents); assertEquals(1, h.source.queries);
		h.source.current.success(current(null)); assertNotNull(h.store.value); assertEquals(nextOperation, h.store.value.operation);
		assertFalse(h.coordinator.canPay()); assertEquals(1, h.sdk.calls);
	}
	@Test public void lateClosedFromOldPageCannotEraseNewOperationFromAnotherPage(){
		Harness h=new Harness(); h.create(); h.sdk.callback.accept("6001"); h.source.query.success(order("PENDING_PAYMENT", "PENDING"));
		h.clock.next(); SponsorPaymentCoordinator.Result<Order> oldLookup=h.source.query;
		SponsorPaymentCoordinator other=new SponsorPaymentCoordinator("owner@fixture", plan().id, h.source, h.sdk, h.store, h.clock, ()->true, h);
		other.resume(); h.source.query.success(order("CLOSED", "PENDING")); other.select("alipay"); other.pay();
		String newer=h.store.value.operation; assertNotNull(newer); assertNull(h.store.value.orderId);
		oldLookup.success(order("CLOSED", "PENDING"));
		assertNotNull("Stale closure must not clear the new operation", h.store.value); assertEquals(newer, h.store.value.operation);
		assertFalse(h.coordinator.canPay()); assertEquals("storage_failed", h.coordinator.problem());
	}
	@Test public void concurrentPagesShareTheDurableOperationWithoutStartingAnotherSdkCall(){
		Harness h=new Harness(); h.start();
		SponsorPaymentCoordinator other=new SponsorPaymentCoordinator("owner@fixture", plan().id, h.source, h.sdk, h.store, h.clock, ()->true, h);
		other.resume(); h.coordinator.pay(); other.pay(); assertEquals(1, h.source.creates); assertEquals(1, h.source.currents);
		Order pending=order("PENDING_PAYMENT", "PENDING"); pending.orderStr="fixture-only-signed-order"; h.source.create.success(pending);
		h.source.current.success(current(order("PENDING_PAYMENT", "PENDING"))); assertEquals(1, h.sdk.calls); assertFalse(other.canPay());
	}
}
