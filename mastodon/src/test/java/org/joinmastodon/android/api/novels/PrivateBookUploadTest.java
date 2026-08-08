package org.joinmastodon.android.api.novels;

import org.joinmastodon.android.novel.download.NovelDownloadWorker;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PrivateBookUploadTest{
	private MockWebServer server;
	private File tempDir;

	@Before
	public void setUp() throws IOException{
		server=new MockWebServer();
		server.start();
		tempDir=Files.createTempDirectory("private-book-upload").toFile();
	}

	@After
	public void tearDown() throws IOException{
		server.shutdown();
		deleteRecursively(tempDir);
	}

	@Test
	public void authorizesPutsExactHeadersThenCompletes() throws Exception{
		File source=write("book.txt", "hello novel");
		String uploadUrl=server.url("/cos/object").toString();
		String md5=PrivateBookUpload.md5Base64(source);
		String sha256=PrivateBookUpload.sha256(source);
		server.enqueue(json(200, "{\"upload_id\":\"book-1\",\"upload_url\":\""+uploadUrl+"\",\"required_headers\":{\"Content-Length\":\"11\",\"Content-MD5\":\""+md5+"\",\"Content-Type\":\"text/plain\",\"Authorization\":\"signed-value\",\"x-cos-forbid-overwrite\":\"true\",\"x-cos-meta-sha256\":\""+sha256+"\"},\"expires_at\":123}"));
		server.enqueue(new MockResponse().setResponseCode(200));
		server.enqueue(json(200, "{\"id\":\"book-1\",\"title\":\"Title\",\"author\":\"Author\",\"format\":\"txt\",\"content_hash\":\"hash\",\"verified_size\":11,\"parse_status\":\"ready\"}"));

		List<Integer> progress=new ArrayList<>();
		PrivateBookUpload upload=new PrivateBookUpload(api(), progress::add);
		PrivateNovelApi.BookDto result=upload.upload(source, metadata());

		assertEquals("book-1", result.id);
		assertEquals(PrivateBookUpload.State.COMPLETE, upload.getState());
		assertMonotonic(progress);
		assertEquals(Integer.valueOf(100), progress.get(progress.size()-1));

		RecordedRequest authorize=server.takeRequest();
		assertEquals("/api/v1/novels/private/authorize", authorize.getPath());
		assertEquals("Bearer token", authorize.getHeader("Authorization"));
		String body=authorize.getBody().readUtf8();
		assertTrue(body.contains("\"declared_size\":11"));
		assertTrue(body.contains("\"content_hash\":\""+PrivateBookUpload.sha256(source)+"\""));
		assertTrue(body.contains("\"content_md5\":\""+PrivateBookUpload.md5Base64(source)+"\""));

		RecordedRequest put=server.takeRequest();
		assertEquals("PUT", put.getMethod());
		assertEquals("signed-value", put.getHeader("Authorization"));
		assertEquals("11", put.getHeader("Content-Length"));
		assertEquals(md5, put.getHeader("Content-MD5"));
		assertEquals("text/plain", put.getHeader("Content-Type"));
		assertEquals("true", put.getHeader("x-cos-forbid-overwrite"));
		assertEquals(sha256, put.getHeader("x-cos-meta-sha256"));
		assertEquals("hello novel", put.getBody().readUtf8());

		RecordedRequest complete=server.takeRequest();
		assertEquals("/api/v1/novels/private/book-1/complete", complete.getPath());
		assertEquals("{}", complete.getBody().readUtf8());
	}

	@Test
	public void readyAuthorizationSkipsPut() throws Exception{
		File source=write("book.txt", "ready");
		server.enqueue(json(200, "{\"upload_id\":\"book-2\",\"already_uploaded\":true,\"parse_status\":\"ready\"}"));

		PrivateNovelApi.BookDto result=new PrivateBookUpload(api(), ignored -> {}).upload(source, metadata());

		assertEquals("book-2", result.id);
		assertEquals(1, server.getRequestCount());
	}

	@Test
	public void cancelAfterReadyResponseCannotPublishComplete() throws Exception{
		File source=write("book.txt", "ready cancellation");
		server.enqueue(json(200, "{\"upload_id\":\"book-ready-cancel\",\"already_uploaded\":true,\"parse_status\":\"ready\"}"));
		CountDownLatch responseHandled=new CountDownLatch(1);
		CountDownLatch continueCompletion=new CountDownLatch(1);
		PrivateBookUpload upload=new PrivateBookUpload(api(), ignored -> {}, millis -> {}, () -> {
			responseHandled.countDown();
			try{
				continueCompletion.await(2, TimeUnit.SECONDS);
			}catch(InterruptedException e){
				Thread.currentThread().interrupt();
			}
		});
		AtomicReference<Throwable> failure=new AtomicReference<>();
		Thread thread=new Thread(() -> {
			try{
				upload.upload(source, metadata());
			}catch(Throwable error){
				failure.set(error);
			}
		});
		thread.start();

		assertTrue(responseHandled.await(2, TimeUnit.SECONDS));
		upload.cancel();
		continueCompletion.countDown();
		thread.join(2000);

		assertTrue(failure.get() instanceof IOException);
		assertEquals(PrivateBookUpload.State.CANCELED, upload.getState());
	}

	@Test
	public void cancelAfterCompleteResponseCannotPublishComplete() throws Exception{
		File source=write("book.txt", "complete cancellation");
		server.enqueue(authorizeResponse(source, server.url("/cos/cancel-complete").toString(), "book-complete-cancel"));
		server.enqueue(new MockResponse().setResponseCode(200));
		server.enqueue(json(200, "{\"id\":\"book-complete-cancel\",\"format\":\"txt\",\"verified_size\":19,\"parse_status\":\"ready\"}"));
		CountDownLatch responseHandled=new CountDownLatch(1);
		CountDownLatch continueCompletion=new CountDownLatch(1);
		PrivateBookUpload upload=new PrivateBookUpload(api(), ignored -> {}, millis -> {}, () -> {
			responseHandled.countDown();
			try{
				continueCompletion.await(2, TimeUnit.SECONDS);
			}catch(InterruptedException e){
				Thread.currentThread().interrupt();
			}
		});
		AtomicReference<Throwable> failure=new AtomicReference<>();
		Thread thread=new Thread(() -> {
			try{
				upload.upload(source, metadata());
			}catch(Throwable error){
				failure.set(error);
			}
		});
		thread.start();

		assertTrue(responseHandled.await(2, TimeUnit.SECONDS));
		upload.cancel();
		continueCompletion.countDown();
		thread.join(2000);

		assertTrue(failure.get() instanceof IOException);
		assertEquals(PrivateBookUpload.State.CANCELED, upload.getState());
	}

	@Test
	public void parsingCompletePollsUntilReadyAndOnlyThenReportsComplete() throws Exception{
		File source=write("book.txt", "poll me");
		server.enqueue(authorizeResponse(source, server.url("/cos/poll").toString(), "book-poll"));
		server.enqueue(new MockResponse().setResponseCode(200));
		server.enqueue(json(202, "{\"id\":\"book-poll\",\"format\":\"txt\",\"verified_size\":7,\"parse_status\":\"parsing\"}"));
		server.enqueue(json(200, "{\"id\":\"book-poll\",\"format\":\"txt\",\"verified_size\":7,\"parse_status\":\"ready\"}"));
		List<Integer> progress=new ArrayList<>();

		PrivateBookUpload upload=new PrivateBookUpload(api(), progress::add, millis -> {});
		PrivateNovelApi.BookDto result=upload.upload(source, metadata());

		assertEquals("ready", result.parseStatus);
		assertEquals(PrivateBookUpload.State.COMPLETE, upload.getState());
		assertEquals(Integer.valueOf(100), progress.get(progress.size()-1));
		assertEquals(4, server.getRequestCount());
	}

	@Test
	public void lostPutResponseRecoversThroughCompleteWithoutSecondPut() throws Exception{
		File source=write("book.txt", "lost response");
		server.enqueue(authorizeResponse(source, server.url("/cos/lost").toString(), "book-lost"));
		server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
		server.enqueue(json(200, "{\"id\":\"book-lost\",\"format\":\"txt\",\"verified_size\":13,\"parse_status\":\"ready\"}"));

		PrivateNovelApi.BookDto result=new PrivateBookUpload(api(), ignored -> {}, millis -> {}).upload(source, metadata());

		assertEquals("book-lost", result.id);
		assertEquals(3, server.getRequestCount());
		server.takeRequest();
		assertEquals("PUT", server.takeRequest().getMethod());
	}

	@Test
	public void temporaryCompleteFailureRetriesCompleteWithoutRepeatingPut() throws Exception{
		File source=write("book.txt", "temporary");
		server.enqueue(authorizeResponse(source, server.url("/cos/temporary").toString(), "book-temp"));
		server.enqueue(new MockResponse().setResponseCode(200));
		server.enqueue(json(502, "{\"error\":{\"code\":\"verification_unavailable\",\"status\":502}}"));
		server.enqueue(json(200, "{\"id\":\"book-temp\",\"format\":\"txt\",\"verified_size\":9,\"parse_status\":\"ready\"}"));

		new PrivateBookUpload(api(), ignored -> {}, millis -> {}).upload(source, metadata());

		assertEquals(4, server.getRequestCount());
		server.takeRequest();
		assertEquals("PUT", server.takeRequest().getMethod());
	}

	@Test
	public void malformedJsonFailsAndConvergesToFailed() throws Exception{
		File source=write("book.txt", "bad json");
		server.enqueue(json(200, "{not-json"));
		PrivateBookUpload upload=new PrivateBookUpload(api(), ignored -> {});

		assertThrows(IOException.class, () -> upload.upload(source, metadata()));

		assertEquals(PrivateBookUpload.State.FAILED, upload.getState());
	}

	@Test
	public void putFailureNeverCompletes() throws Exception{
		File source=write("book.txt", "failed put");
		server.enqueue(authorizeResponse(source, server.url("/cos/fail").toString(), "book-3"));
		server.enqueue(new MockResponse().setResponseCode(403));

		PrivateBookUpload upload=new PrivateBookUpload(api(), ignored -> {});
		assertThrows(IOException.class, () -> upload.upload(source, metadata()));

		assertEquals(PrivateBookUpload.State.FAILED, upload.getState());
		assertEquals(2, server.getRequestCount());
	}

	@Test
	public void cancelStopsCurrentCallAndDoesNotComplete() throws Exception{
		File source=write("book.txt", "cancel me");
		server.enqueue(authorizeResponse(source, server.url("/cos/hang").toString(), "book-4"));
		server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
		PrivateBookUpload upload=new PrivateBookUpload(api(), ignored -> {});
		CountDownLatch finished=new CountDownLatch(1);

		Thread thread=new Thread(() -> {
			try{
				upload.upload(source, metadata());
			}catch(IOException ignored){
			}finally{
				finished.countDown();
			}
		});
		thread.start();
		server.takeRequest(2, TimeUnit.SECONDS);
		server.takeRequest(2, TimeUnit.SECONDS);
		upload.cancel();

		assertTrue(finished.await(2, TimeUnit.SECONDS));
		assertEquals(PrivateBookUpload.State.CANCELED, upload.getState());
		assertEquals(2, server.getRequestCount());
	}

	@Test
	public void cancelBeforeCallRegistrationPreventsExecutionAndStaysCanceled() throws Exception{
		File source=write("book.txt", "cancel before start");
		PrivateBookUpload upload=new PrivateBookUpload(api(), ignored -> {});

		upload.cancel();
		assertThrows(IOException.class, () -> upload.upload(source, metadata()));

		assertEquals(PrivateBookUpload.State.CANCELED, upload.getState());
		assertEquals(0, server.getRequestCount());
	}

	@Test
	public void rejectsMissingRequiredHeadersAndNonHttpsUrl() throws Exception{
		File source=write("book.txt", "invalid");
		server.enqueue(json(200, "{\"book_id\":\"book-5\",\"upload_url\":\"https://example.test/object\",\"required_headers\":{\"Authorization\":\"signed\"},\"already_uploaded\":false}"));
		PrivateBookUpload upload=new PrivateBookUpload(api(), ignored -> {});
		assertThrows(IOException.class, () -> upload.upload(source, metadata()));

		PrivateNovelApi strictApi=new PrivateNovelApi(server.url("/api/v1/novels/private").toString(), "token", new okhttp3.OkHttpClient(), true, false);
		PrivateBookUpload strictUpload=new PrivateBookUpload(strictApi, ignored -> {});
		server.enqueue(authorizeResponse(source, server.url("/plain-http").toString(), "book-6"));
		assertThrows(IOException.class, () -> strictUpload.upload(source, metadata()));
	}

	@Test
	public void rejectsInvalidFilesAndMismatchedSignedHeaders() throws Exception{
		File empty=write("empty.txt", "");
		PrivateBookUpload upload=new PrivateBookUpload(api(), ignored -> {});
		assertThrows(IOException.class, () -> upload.upload(empty, metadata()));
		assertEquals(0, server.getRequestCount());

		File source=write("book.txt", "invalid headers");
		server.enqueue(json(200, "{\"upload_id\":\"book-7\",\"upload_url\":\""+server.url("/cos/object")+"\",\"required_headers\":{\"Content-Length\":\"15\",\"Content-MD5\":\""+PrivateBookUpload.md5Base64(source)+"\",\"Content-Type\":\"text/plain\",\"Authorization\":\"signed\",\"x-cos-forbid-overwrite\":\"false\",\"x-cos-meta-sha256\":\""+PrivateBookUpload.sha256(source)+"\"}}"));
		assertThrows(IOException.class, () -> upload.upload(source, metadata()));
		assertEquals(1, server.getRequestCount());

		File oversized=new File(tempDir, "oversized.epub");
		try(RandomAccessFile randomAccessFile=new RandomAccessFile(oversized, "rw")){
			randomAccessFile.setLength(PrivateBookUpload.MAX_SIZE+1);
		}
		assertThrows(IOException.class, () -> upload.upload(oversized, new PrivateNovelApi.UploadMetadata("Title", "Author", "epub", "application/epub+zip")));
		assertThrows(IOException.class, () -> upload.upload(source, new PrivateNovelApi.UploadMetadata("Title", "Author", "txt", "application/epub+zip")));
		assertEquals(1, server.getRequestCount());
	}

	@Test
	public void uploadAndDownloadRejectRedirects() throws Exception{
		File source=write("book.txt", "redirect");
		server.enqueue(authorizeResponse(source, server.url("/cos/redirect").toString(), "book-8"));
		server.enqueue(new MockResponse().setResponseCode(307).setHeader("Location", server.url("/cos/target")));
		PrivateBookUpload upload=new PrivateBookUpload(api(), ignored -> {});
		assertThrows(IOException.class, () -> upload.upload(source, metadata()));
		assertEquals(2, server.getRequestCount());

		File destination=new File(tempDir, "redirect-download.txt");
		server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", server.url("/download/target")));
		assertThrows(IOException.class, () -> NovelDownloadWorker.downloadVerified(api().getCallFactory(), server.url("/download/redirect").toString(), destination, 1, PrivateBookUpload.sha256(new byte[]{1}), true, null));
		assertFalse(destination.exists());
		assertFalse(new File(tempDir, "redirect-download.txt.part").exists());
		assertEquals(3, server.getRequestCount());
	}

	@Test
	public void apiUsesBackendContractPathsAndUniqueWorkNameIsAccountScoped() throws Exception{
		server.enqueue(json(200, "{\"download_url\":\"https://download.example/book\",\"expires_at\":123}"));
		PrivateNovelApi api=api();
		api.executeJson(api.newDownloadAuthorizeCall("book-9"), PrivateNovelApi.DownloadAuthorization.class);
		assertEquals("/api/v1/novels/private/book-9/download/authorize", server.takeRequest().getPath());
		assertEquals("novel-download:"+org.joinmastodon.android.novel.importer.NovelImportCoordinator.Companion.accountHash("account-a")+":book-9", NovelDownloadWorker.uniqueWorkName("account-a", "book-9"));
		assertEquals("novel-download-account:"+org.joinmastodon.android.novel.importer.NovelImportCoordinator.Companion.accountHash("account-a"), NovelDownloadWorker.accountWorkTag("account-a"));
		assertFalse(NovelDownloadWorker.uniqueWorkName("account-a", "book-9").equals(NovelDownloadWorker.uniqueWorkName("account-b", "book-9")));
		assertFalse(NovelDownloadWorker.accountWorkTag("account-a").equals(NovelDownloadWorker.accountWorkTag("account-b")));
	}

	@Test
	public void downloadUsesPartThenAtomicallyReplacesAndHashFailureCleansUp() throws Exception{
		File destination=write("download.txt", "old");
		byte[] expected="downloaded novel".getBytes(StandardCharsets.UTF_8);
		server.enqueue(new MockResponse().setResponseCode(200).setBody(new okio.Buffer().write(expected)));

		NovelDownloadWorker.downloadVerified(api().getCallFactory(), server.url("/book").toString(), destination, expected.length, PrivateBookUpload.sha256(expected), true, null);

		assertEquals("downloaded novel", Files.readString(destination.toPath()));
		assertFalse(new File(destination.getParentFile(), destination.getName()+".part").exists());
		assertEquals(null, server.takeRequest().getHeader("Authorization"));

		server.enqueue(new MockResponse().setResponseCode(200).setBody("corrupt"));
		assertThrows(IOException.class, () -> NovelDownloadWorker.downloadVerified(api().getCallFactory(), server.url("/bad").toString(), destination, 7, "00", true, null));
		assertEquals("downloaded novel", Files.readString(destination.toPath()));
		assertFalse(new File(destination.getParentFile(), destination.getName()+".part").exists());
	}

	@Test
	public void canceledDownloadClosesCallAndCleansPart() throws Exception{
		File destination=new File(tempDir, "canceled.txt");
		server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
		AtomicReference<okhttp3.Call> callRef=new AtomicReference<>();
		CountDownLatch finished=new CountDownLatch(1);
		Thread thread=new Thread(() -> {
			try{
				NovelDownloadWorker.downloadVerified(api().getCallFactory(), server.url("/canceled").toString(), destination, 1, PrivateBookUpload.sha256(new byte[]{1}), true, call -> {
					callRef.set(call);
					return kotlin.Unit.INSTANCE;
				});
			}catch(IOException ignored){
			}finally{
				finished.countDown();
			}
		});
		thread.start();
		assertTrue(server.takeRequest(2, TimeUnit.SECONDS)!=null);
		okhttp3.Call call=callRef.get();
		assertTrue(call!=null);
		call.cancel();
		assertTrue(finished.await(2, TimeUnit.SECONDS));
		assertFalse(destination.exists());
		assertFalse(new File(tempDir, "canceled.txt.part").exists());
	}

	private PrivateNovelApi api(){
		return new PrivateNovelApi(server.url("/api/v1/novels/private").toString(), "token", new okhttp3.OkHttpClient(), true);
	}

	private PrivateNovelApi.UploadMetadata metadata(){
		return new PrivateNovelApi.UploadMetadata("Title", "Author", "txt", "text/plain");
	}

	private MockResponse authorizeResponse(File source, String uploadUrl, String bookId) throws IOException{
		return json(200, "{\"upload_id\":\""+bookId+"\",\"upload_url\":\""+uploadUrl+"\",\"required_headers\":{\"Content-Length\":\""+source.length()+"\",\"Content-MD5\":\""+PrivateBookUpload.md5Base64(source)+"\",\"Content-Type\":\"text/plain\",\"Authorization\":\"signed\",\"x-cos-forbid-overwrite\":\"true\",\"x-cos-meta-sha256\":\""+PrivateBookUpload.sha256(source)+"\"}}");
	}

	private static MockResponse json(int code, String body){
		return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
	}

	private File write(String name, String value) throws IOException{
		File file=new File(tempDir, name);
		Files.writeString(file.toPath(), value);
		return file;
	}

	private static void assertMonotonic(List<Integer> values){
		int previous=-1;
		for(int value:values){
			assertTrue(value>=previous);
			previous=value;
		}
	}

	private static void deleteRecursively(File file){
		if(file==null || !file.exists()) return;
		File[] children=file.listFiles();
		if(children!=null){
			for(File child:children) deleteRecursively(child);
		}
		file.delete();
	}
}
