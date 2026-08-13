package org.joinmastodon.android.api.novels;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import okhttp3.Cache;
import okhttp3.OkHttpClient;

import static org.junit.Assert.assertNull;

public class PrivateNovelApiCacheTest{
	@Test public void privateApiDoesNotInheritSharedDiskCache() throws Exception{
		File directory=Files.createTempDirectory("private-novel-api-cache").toFile();
		Cache cache=new Cache(directory, 1024*1024);
		try{
			OkHttpClient sharedClient=new OkHttpClient.Builder().cache(cache).build();
			PrivateNovelApi api=new PrivateNovelApi("http://localhost", "token", sharedClient, true);

			assertNull(((OkHttpClient)api.getCallFactory()).cache());
		}finally{
			cache.delete();
		}
	}
}
