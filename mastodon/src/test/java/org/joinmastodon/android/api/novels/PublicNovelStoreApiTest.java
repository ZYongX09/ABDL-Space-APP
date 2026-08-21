package org.joinmastodon.android.api.novels;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PublicNovelStoreApiTest{
	private MockWebServer server;
	private PublicNovelStoreApi api;

	@Before public void setUp() throws Exception{
		server=new MockWebServer();
		server.start();
		api=new PublicNovelStoreApi(server.url("/api/v1/novels/store").toString(), new OkHttpClient(), true);
	}

	@After public void tearDown() throws Exception{ server.shutdown(); }

	@Test public void publicRequestsHaveExpectedPathsAndNeverSendAuthorization() throws Exception{
		server.enqueue(json("{\"items\":[{\"id\":\"work 1\",\"title\":\"T\",\"author\":{\"username\":\"writer\"},\"published_chapter_count\":1}],\"next_cursor\":\"next cursor\"}"));
		PublicNovelStoreApi.WorkListDto works=api.executeJson(api.newWorksCall(null), PublicNovelStoreApi.WorkListDto.class);
		assertEquals("work 1", works.items.get(0).id);
		assertEquals("next cursor", works.nextCursor);
		RecordedRequest listing=server.takeRequest();
		assertEquals("/api/v1/novels/store/works?limit=20", listing.getPath());
		assertNull(listing.getHeader("Authorization"));

		server.enqueue(json("{\"items\":[],\"next_cursor\":null}"));
		PublicNovelStoreApi.WorkListDto nextPage=api.executeJson(api.newWorksCall(works.nextCursor), PublicNovelStoreApi.WorkListDto.class);
		assertNull(nextPage.nextCursor);
		assertEquals("/api/v1/novels/store/works?limit=20&cursor=next%20cursor", server.takeRequest().getPath());

		server.enqueue(json("{\"id\":\"work 1\",\"title\":\"T\",\"volumes\":[]}"));
		api.executeJson(api.newWorkCall("work 1"), PublicNovelStoreApi.WorkDto.class);
		assertEquals("/api/v1/novels/store/works/work%201", server.takeRequest().getPath());

		server.enqueue(json("{\"revision_id\":\"revision\",\"chapter_id\":\"chapter 1\",\"body\":\"public body\"}"));
		PublicNovelStoreApi.PublishedChapterDto chapter=api.executeJson(api.newChapterCall("work 1", "chapter 1", "revision"), PublicNovelStoreApi.PublishedChapterDto.class);
		assertEquals("public body", chapter.body);
		assertEquals("/api/v1/novels/store/works/work%201/chapters/chapter%201?revision_id=revision", server.takeRequest().getPath());
	}

	private static MockResponse json(String body){ return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body); }
}
