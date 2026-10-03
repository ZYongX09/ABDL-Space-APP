package org.joinmastodon.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

public class MastodonAppProcessTest{
	@Test
	public void onlyMainProcessSchedulesPendingTransfers(){
		assertTrue(MastodonApp.isMainProcess("top.abdl_space.app", "top.abdl_space.app"));
		assertFalse(MastodonApp.isMainProcess("top.abdl_space.app", "top.abdl_space.app:pushcore"));
		assertFalse(MastodonApp.isMainProcess("top.abdl_space.app", null));
	}

	@Test
	public void mainProcessRestoresPersistentNovelCleanupMarkers(){
		String source=new File("src/main/java/org/joinmastodon/android/MastodonApp.java").toPath().toFile().exists()
				? readSource("src/main/java/org/joinmastodon/android/MastodonApp.java") : "";
		assertTrue(source.contains("NovelAccountCleanupWorker.enqueuePending(context)"));
	}

	private static String readSource(String path){
		try{
			return java.nio.file.Files.readString(new File(path).toPath());
		}catch(java.io.IOException e){
			throw new AssertionError(e);
		}
	}
}
