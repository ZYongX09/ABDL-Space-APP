package org.joinmastodon.android.sponsors;

import org.joinmastodon.android.model.sponsors.SponsorModels.ThanksItem;
import org.joinmastodon.android.model.sponsors.SponsorModels.ThanksPage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Executes production state machine with an intentionally uncancellable callback source and fake clock. */
public class SponsorThanksControllerTest{
	private static class Call implements SponsorThanksController.Cancelable{
		int offset; boolean canceled; SponsorThanksController.Result result;
		@Override public void cancel(){ canceled=true; }
	}
	private static class Clock implements SponsorThanksController.Scheduler{
		Runnable task; long delay;
		@Override public void postDelayed(Runnable task, long delay){ this.task=task; this.delay=delay; }
		@Override public void remove(Runnable task){ if(this.task==task) this.task=null; }
		void advance(){ Runnable next=task; task=null; if(next!=null) next.run(); }
	}
	private final List<Call> calls=new ArrayList<>();
	private final Clock clock=new Clock();
	private boolean session=true;
	private int renders;
	private final SponsorThanksController controller=new SponsorThanksController((offset, callback)->{
		Call call=new Call(); call.offset=offset; call.result=callback; calls.add(call); return call;
	}, clock, ()->session, ()->renders++);
	private Call last(){ return calls.get(calls.size()-1); }
	private static ThanksPage page(int offset, int total, String prefix){
		ThanksPage page=new ThanksPage(); page.offset=offset; page.limit=100; page.total=total; page.items=new ArrayList<>();
		for(int i=offset;i<Math.min(total, offset+100);i++){
			ThanksItem item=new ThanksItem(); item.accountId=prefix+i; item.username="user"+i; item.displayName="用户"+i;
			item.avatar="https://avatar.example/"+item.accountId; item.avatarStatic=item.avatar;
			item.rank=i+1; item.totalAmountCents=100000-i; page.items.add(item);
		}
		return page;
	}
	private void first(int total){ controller.show(); last().result.success(page(0, total, "old")); }

	@Test public void visibleRefreshRunsEveryFifteenSecondsAndPreservesRowsUntilSuccess(){
		first(120); assertEquals(15000, clock.delay); assertEquals(100, controller.items().size());
		clock.advance(); assertEquals(2, calls.size()); assertEquals(0, last().offset);
		assertTrue(controller.loading()); assertEquals("old0", controller.items().get(0).accountId);
		last().result.success(page(0, 120, "new")); assertEquals("new0", controller.items().get(0).accountId);
		assertNotNull(clock.task);
	}
	@Test public void paginationUsesServerOffsetAndGlobalRankWithoutDuplicateRequests(){
		first(204); controller.more(); controller.more();
		assertEquals(2, calls.size()); assertEquals(100, last().offset);
		last().result.success(page(100, 204, "old"));
		controller.more(); assertEquals(200, last().offset); last().result.success(page(200, 204, "old"));
		assertEquals(204, controller.items().size()); assertEquals(204, controller.items().get(203).rank);
		assertFalse(controller.hasMore()); controller.more(); assertEquals(3, calls.size());
	}
	@Test public void refreshReplacesEveryLoadedPageAtomically(){
		first(140); controller.more(); last().result.success(page(100, 140, "old"));
		controller.refresh(); last().result.success(page(0, 140, "new"));
		assertEquals(100, last().offset); assertEquals(140, controller.items().size());
		assertEquals("old0", controller.items().get(0).accountId);
		last().result.success(page(100, 140, "new"));
		assertEquals("new0", controller.items().get(0).accountId); assertEquals("new139", controller.items().get(139).accountId);
	}
	@Test public void failureOnSecondRefreshPageRetainsOldWholeListAndRetriesFromZero(){
		first(130); controller.more(); last().result.success(page(100, 130, "old"));
		controller.refresh(); last().result.success(page(0, 130, "new")); last().result.failure("offline");
		assertEquals("old0", controller.items().get(0).accountId); assertEquals(130, controller.items().size());
		assertEquals("offline", controller.error()); controller.retry(); assertEquals(0, last().offset);
	}
	@Test public void oldSuccessAndErrorCannotOverwriteRefreshOrClearLoading(){
		controller.show(); Call old=last(); controller.refresh(); assertTrue(old.canceled);
		old.result.success(page(0, 1, "stale")); old.result.failure("stale error");
		assertTrue(controller.loading()); assertTrue(controller.items().isEmpty()); assertNull(controller.error());
		last().result.success(page(0, 1, "fresh")); assertEquals("fresh0", controller.items().get(0).accountId);
	}
	@Test public void hidingCancelsNetworkAndClockAndReturnRefreshes(){
		first(120); controller.more(); Call hidden=last(); controller.hide();
		assertTrue(hidden.canceled); assertNull(clock.task); int count=calls.size(); clock.advance(); assertEquals(count, calls.size());
		hidden.result.success(page(100, 120, "old")); assertEquals(100, controller.items().size());
		controller.show(); assertEquals(0, last().offset); assertEquals(count+1, calls.size());
	}
	@Test public void destroyRejectsAllLateCallbacksAndFutureWork(){
		controller.show(); Call pending=last(); controller.destroy(); int before=renders;
		pending.result.failure("late"); pending.result.success(page(0, 1, "late"));
		controller.show(); controller.refresh(); controller.more(); controller.sponsorChanged();
		assertEquals(before, renders); assertEquals(1, calls.size()); assertTrue(controller.items().isEmpty()); assertNull(clock.task);
	}
	@Test public void sponsorEventInvalidatesInflightRankingAndHiddenEventDoesNotFetch(){
		controller.show(); Call old=last(); controller.sponsorChanged(); assertTrue(old.canceled);
		old.result.success(page(0, 1, "old")); assertTrue(controller.items().isEmpty());
		last().result.success(page(0, 2, "redeemed")); assertEquals(2, controller.items().size());
		controller.hide(); controller.sponsorChanged(); assertEquals(2, calls.size());
	}
	@Test public void firstErrorRetriesAndEmptySuccessIsNotAnError(){
		controller.show(); last().result.failure("offline"); assertFalse(controller.loading()); assertEquals("offline", controller.error());
		controller.retry(); last().result.success(page(0, 0, "empty"));
		assertNull(controller.error()); assertFalse(controller.hasMore()); assertTrue(controller.items().isEmpty());
	}
	@Test public void appendErrorRetriesSameOffsetAndDoesNotDiscardLoadedRows(){
		first(101); controller.more(); last().result.failure("offline");
		assertEquals(100, controller.items().size()); controller.retry(); assertEquals(100, last().offset);
		last().result.success(page(100, 101, "old")); assertEquals(101, controller.items().size());
	}
	@Test public void duplicateAcrossPagesOrChangedCountRequiresFullRefresh(){
		first(102); controller.more(); ThanksPage duplicate=page(100, 102, "old");
		duplicate.items.get(0).accountId="old0"; last().result.success(duplicate);
		assertNotNull(controller.error()); assertEquals(100, controller.items().size());
		controller.retry(); assertEquals(0, last().offset); last().result.success(page(0, 102, "new"));
		controller.more(); last().result.success(page(100, 103, "new"));
		assertNotNull(controller.error()); controller.retry(); assertEquals(0, last().offset);
	}
	@Test public void logoutClearsPrivateRowsAndStopsTimerEvenWhenResponseArrives(){
		first(101); controller.more(); session=false; last().result.success(page(100, 101, "old"));
		assertTrue(controller.items().isEmpty()); assertNotNull(controller.error()); assertNull(clock.task);
		int count=calls.size(); controller.refresh(); assertEquals(count, calls.size());
	}
	@Test public void tickDoesNotStarveSlowRequestsByCancelingThem(){
		controller.show(); Call slow=last(); clock.advance(); clock.advance();
		assertEquals(1, calls.size()); assertFalse(slow.canceled);
		slow.result.success(page(0, 1, "ok")); clock.advance(); assertEquals(2, calls.size());
	}
	@Test public void synchronousSourceCanFinishWithoutLeavingBusyOrOldHandle(){
		SponsorThanksController synchronous=new SponsorThanksController((offset, callback)->{
			callback.success(page(offset, 1, "sync")); return ()->{};
		}, clock, ()->true, ()->{});
		synchronous.show(); assertFalse(synchronous.loading()); assertEquals(1, synchronous.items().size()); synchronous.destroy();
	}
	@Test public void refreshCanShrinkPreviouslyLoadedPagesToEmpty(){
		first(120); controller.more(); last().result.success(page(100, 120, "old"));
		controller.refresh(); last().result.success(page(0, 0, "new"));
		assertTrue(controller.items().isEmpty()); assertFalse(controller.hasMore()); assertFalse(controller.loading());
	}
}
