package org.joinmastodon.android.sponsors;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class SponsorRedemptionRetryTest{
	private static class Store implements SponsorRedemptionRetry.Store{
		final Map<String, String> values=new HashMap<>();
		@Override public String read(String key){ return values.get(key); }
		@Override public boolean write(String key, String value){ values.put(key, value); return true; }
	}

	@Test public void survivesPageRecreationAndWhitespaceChanges(){
		Store store=new Store();
		String first=new SponsorRedemptionRetry(store).operation("account-a", "demo-code-123");
		assertEquals(first, new SponsorRedemptionRetry(store).operation("account-a", " DEMO- CODE-123 \n"));
	}
	@Test public void differentAccountsAndCodesNeverShareIdentity(){
		Store store=new Store();
		SponsorRedemptionRetry retry=new SponsorRedemptionRetry(store);
		String first=retry.operation("account-a", "sample-a");
		assertNotEquals(first, retry.operation("account-b", "sample-a"));
		assertNotEquals(first, retry.operation("account-a", "sample-b"));
	}
	@Test public void neverPersistsRawCodeOrAccount(){
		Store store=new Store();
		new SponsorRedemptionRetry(store).operation("private-account", "private-code");
		assertFalse(store.values.toString().contains("private-account"));
		assertFalse(store.values.toString().contains("private-code"));
	}
	@Test public void keepsOneRetryRecordPerAccount(){
		Store store=new Store();
		SponsorRedemptionRetry retry=new SponsorRedemptionRetry(store);
		for(int i=0;i<100;i++) retry.operation("account-a", "sample-"+i);
		assertEquals(1, store.values.size());
	}
	@Test public void storageFailurePreventsNewOperation(){
		SponsorRedemptionRetry retry=new SponsorRedemptionRetry(new SponsorRedemptionRetry.Store(){
			@Override public String read(String key){ return null; }
			@Override public boolean write(String key, String value){ return false; }
		});
		assertThrows(IllegalStateException.class, ()->retry.operation("account-a", "sample"));
	}
	@Test public void invalidSavedOperationIsReplaced(){
		Store store=new Store();
		store.values.put(SponsorOperation.hash("account-a"), SponsorOperation.hash("SAMPLE")+":not-a-uuid");
		String id=new SponsorRedemptionRetry(store).operation("account-a", "sample");
		assertEquals(id, java.util.UUID.fromString(id).toString());
	}
}
