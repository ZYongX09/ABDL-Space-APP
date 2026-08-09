package org.joinmastodon.android.api.novels;

import com.google.gson.JsonParser;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PrivateNovelApiTest{
	private MockWebServer server;
	private PrivateNovelApi api;

	@Before public void setUp() throws Exception{
		server=new MockWebServer(); server.start();
		api=new PrivateNovelApi(server.url("/api/v1/novels/private").toString(), "token", new OkHttpClient(), true);
	}
	@After public void tearDown() throws Exception{ server.shutdown(); }

	@Test public void booksAndSyncUseCursorContracts() throws Exception{
		server.enqueue(json("{\"items\":[{\"id\":\"b\",\"title\":\"T\",\"verified_size\":2,\"created_at\":3,\"updated_at\":4}],\"next_cursor\":\"next\"}"));
		PrivateNovelApi.BooksPage books=api.executeJson(api.newBooksCall("a b", 20), PrivateNovelApi.BooksPage.class);
		assertEquals("next", books.nextCursor); assertEquals(4, books.items.get(0).updatedAt);
		assertEquals("/api/v1/novels/private/books?limit=20&cursor=a%20b", server.takeRequest().getPath());

		server.enqueue(json("{\"items\":[],\"next_cursor\":null,\"checkpoint_cursor\":\"19\"}"));
		PrivateNovelApi.SyncPageDto sync=api.executeJson(api.newSyncCall(null, 50), PrivateNovelApi.SyncPageDto.class);
		assertEquals("19", sync.checkpointCursor); assertNull(sync.nextCursor);
	}

	@Test public void pastePutAndDeleteUseExpectedBodies() throws Exception{
		server.enqueue(json("{\"id\":\"b\",\"title\":\"T\",\"author\":\"A\",\"verified_size\":1}"));
		api.executeJson(api.newPasteCall(new PrivateNovelApi.PasteRequest("T", "A", "body")), PrivateNovelApi.BookDto.class);
		RecordedRequest paste=server.takeRequest(); assertEquals("POST", paste.getMethod()); assertTrue(paste.getBody().readUtf8().contains("\"text\":\"body\""));

		server.enqueue(json("{\"seq\":1,\"book_id\":\"b\",\"item_type\":\"note\",\"item_id\":\"n\",\"payload\":{\"token\":\"remote\"},\"client_updated_at\":1,\"server_updated_at\":2}"));
		PrivateNovelApi.SyncItemDto sync=api.executeJson(api.newPutSyncItemCall("n", new PrivateNovelApi.SyncPutRequest("b", "note", "n", JsonParser.parseString("{\"token\":\"local\"}").getAsJsonObject(), 1, null)), PrivateNovelApi.SyncItemDto.class);
		RecordedRequest put=server.takeRequest();
		assertEquals("PUT", put.getMethod());
		assertEquals("local", JsonParser.parseString(put.getBody().readUtf8()).getAsJsonObject().getAsJsonObject("payload").get("token").getAsString());
		assertEquals("remote", sync.payload.get("token").getAsString());

		server.enqueue(new MockResponse().setResponseCode(204)); api.executeEmpty(api.newDeleteBookCall("b"));
		assertEquals("DELETE", server.takeRequest().getMethod());
	}

	private MockResponse json(String body){ return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body); }
}
