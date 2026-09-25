package org.joinmastodon.android.sponsors;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

public class SponsorOriginalTicketsTest{
	private static final String DAY="2026-09-12";
	private static final long NOW=1_789_200_000L, RESET=NOW+60;
	private static final String MEDIA=SponsorOperation.mediaKey("image-1", "https://cdn.example/image.jpg");

	@Test public void failedNetworkOrDownloadReusesDurableIdentity(){
		MemoryStore store=new MemoryStore();
		String first=new SponsorOriginalTickets(store).operation("example_user", MEDIA, DAY, RESET, NOW);
		assertEquals(first, new SponsorOriginalTickets(store).operation("example_user", MEDIA, DAY, RESET, NOW+1));
		UUID.fromString(first);
		assertFalse(store.value.contains("example_user"));
		assertFalse(store.value.contains("https://"));
	}

	@Test public void accountsMediaAndServerDaysNeverShareIdentity(){
		SponsorOriginalTickets tickets=new SponsorOriginalTickets(new MemoryStore());
		String first=tickets.operation("account-a", MEDIA, DAY, RESET, NOW);
		assertNotEquals(first, tickets.operation("account-b", MEDIA, DAY, RESET, NOW));
		assertNotEquals(first, tickets.operation("account-a", SponsorOperation.mediaKey("image-2", "https://cdn.example/2"), DAY, RESET, NOW));
		assertNotEquals(first, tickets.operation("account-a", MEDIA, "2026-09-13", RESET+86400, RESET));
	}

	@Test public void duplicateOperationAlwaysReturnsToServerEvenWhenQuotaIsExhausted(){
		MemoryStore store=new MemoryStore();
		SponsorOriginalTickets tickets=new SponsorOriginalTickets(store);
		FakeAuthorizer server=new FakeAuthorizer();
		String first=tickets.operation("account", MEDIA, DAY, RESET, NOW);
		server.authorize(first, MEDIA);
		tickets.authorized("account", MEDIA, first, DAY, RESET);
		String retry=new SponsorOriginalTickets(store).operation("account", MEDIA, DAY, RESET, NOW+1);
		server.authorize(retry, MEDIA);
		assertEquals(first, retry);
		assertEquals(2, server.calls);
		assertEquals(1, server.debits);
		// The media hash is a fingerprint, NOT a server idempotency key.
		server.authorize(UUID.randomUUID().toString(), MEDIA);
		assertEquals(2, server.debits);
	}

	@Test public void checkingRetryDoesNotCreateATicketOrAcknowledgeNotice(){
		MemoryStore store=new MemoryStore();
		SponsorOriginalTickets tickets=new SponsorOriginalTickets(store);
		assertFalse(tickets.hasRetry("account", MEDIA, DAY, RESET, NOW));
		assertEquals("", store.value);
		tickets.operation("account", MEDIA, DAY, RESET, NOW);
		assertTrue(tickets.hasRetry("account", MEDIA, DAY, RESET, NOW));
		assertFalse(tickets.hasRetry("account", MEDIA, DAY, RESET, RESET));
		assertFalse(tickets.hasRetry("other", MEDIA, DAY, RESET, NOW));
	}

	@Test public void responseDayBindsMidnightCrossingOperation(){
		SponsorOriginalTickets tickets=new SponsorOriginalTickets(new MemoryStore());
		String first=tickets.operation("account", MEDIA, DAY, RESET, NOW);
		tickets.authorized("account", MEDIA, first, "2026-09-13", RESET+86400);
		assertEquals(first, tickets.operation("account", MEDIA, "2026-09-13", RESET+86400, RESET+1));
	}

	@Test public void expiredOrInvalidQuotaCannotInventNewLocalDay(){
		SponsorOriginalTickets tickets=new SponsorOriginalTickets(new MemoryStore());
		assertThrows(IllegalArgumentException.class, ()->tickets.operation("account", MEDIA, DAY, RESET, RESET));
		assertThrows(IllegalArgumentException.class, ()->tickets.operation("account", MEDIA, null, RESET, NOW));
		assertThrows(IllegalArgumentException.class, ()->tickets.operation("account", MEDIA, "local-day", RESET, NOW));
		assertThrows(IllegalArgumentException.class, ()->tickets.operation("account", MEDIA, DAY, 0, NOW));
	}

	@Test public void persistenceFailureCannotDispatchUnrepeatableOperation(){
		MemoryStore store=new MemoryStore();
		store.writable=false;
		assertThrows(IllegalStateException.class, ()->new SponsorOriginalTickets(store).operation("account", MEDIA, DAY, RESET, NOW));
	}

	@Test public void malformedPersistenceIsIgnoredAndBounded(){
		MemoryStore store=new MemoryStore();
		store.value="corrupted\n"+SponsorOperation.scope("account", DAY, MEDIA)+" invalid-uuid nope\n";
		SponsorOriginalTickets tickets=new SponsorOriginalTickets(store);
		for(int i=0;i<140;i++) tickets.operation("account", SponsorOperation.mediaKey(""+i, "https://example/"+i), DAY, RESET, NOW);
		assertEquals(100, store.value.lines().count());
		assertTrue(store.value.length()<32768);
		String recent=tickets.operation("account", MEDIA, DAY, RESET, NOW);
		assertEquals(recent, new SponsorOriginalTickets(store).operation("account", MEDIA, DAY, RESET, NOW));
	}

	private static final class MemoryStore implements SponsorOriginalTickets.Store{
		String value="";
		boolean writable=true;
		@Override public String read(){ return value; }
		@Override public boolean write(String value){ if(!writable) return false; this.value=value; return true; }
	}

	/** Contract fake: actual authorization is server-owned; client tickets cannot grant access. */
	private static final class FakeAuthorizer{
		final Map<String, String> operations=new HashMap<>();
		int calls, debits;
		void authorize(String operation, String media){
			calls++;
			String existing=operations.putIfAbsent(operation, media);
			if(existing==null) debits++;
			else assertEquals(existing, media);
		}
	}
}
