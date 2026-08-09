package org.joinmastodon.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MastodonAppProcessTest{
	@Test
	public void onlyMainProcessSchedulesPendingTransfers(){
		assertTrue(MastodonApp.isMainProcess("top.abdl_space.app", "top.abdl_space.app"));
		assertFalse(MastodonApp.isMainProcess("top.abdl_space.app", "top.abdl_space.app:pushcore"));
		assertFalse(MastodonApp.isMainProcess("top.abdl_space.app", null));
	}
}
