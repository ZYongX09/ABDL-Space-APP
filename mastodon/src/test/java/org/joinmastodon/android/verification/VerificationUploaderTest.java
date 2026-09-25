package org.joinmastodon.android.verification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.joinmastodon.android.api.requests.verification.VerificationRequest;
import org.joinmastodon.android.model.verification.VerificationModels.EvidenceComplete;
import org.joinmastodon.android.model.verification.VerificationModels.UploadAuthorization;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class VerificationUploaderTest{
	private static final String APPLICATION="123e4567-e89b-42d3-a456-426614174001";
	private static final String EVIDENCE="123e4567-e89b-42d3-a456-426614174002";
	private final TemporaryFolder folder=new TemporaryFolder();
	private VerificationImageProcessor.Result image;
	private int putStatus=200;

	@Before
	public void setUp() throws Exception{
		folder.create();
		File file=folder.newFile("capture.jpg");
		try(FileOutputStream output=new FileOutputStream(file)){
			output.write(new byte[]{(byte)0xff, (byte)0xd8, 1, 2, 3, 4, 5, (byte)0xff, (byte)0xd9});
		}
		byte[] bytes=java.nio.file.Files.readAllBytes(file.toPath());
		image=new VerificationImageProcessor.Result(file, 1, 1, bytes.length, hex(MessageDigest.getInstance("SHA-256").digest(bytes)), Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes)));
	}

	@After
	public void tearDown(){
		folder.delete();
	}

	@Test
	public void noOverwriteConflictStillCompletesTheExistingObject(){
		putStatus=409;
		FakeTransport transport=new FakeTransport();
		transport.authorization=authorization();
		transport.completeResult=complete();
		RecordingListener listener=new RecordingListener();
		VerificationUploader uploader=uploader(transport, listener);

		uploader.start(APPLICATION, image);

		assertSame(transport.completeResult, listener.success);
		assertNull(listener.error);
		assertEquals(1, transport.authorizeCalls);
		assertEquals(1, transport.completeCalls);
		assertEquals(VerificationUploader.State.COMPLETE, uploader.getState());
	}

	@Test
	public void expiredCompleteReauthorizesAndUploadsAgain(){
		putStatus=200;
		FakeTransport transport=new FakeTransport();
		transport.authorization=authorization();
		transport.completeErrors.add(new VerificationUploader.TransportException("expired", 410, "upload_expired", false, false));
		transport.completeResult=complete();
		RecordingListener listener=new RecordingListener();
		VerificationUploader uploader=uploader(transport, listener);

		uploader.resume(APPLICATION, image, VerificationUploader.Recovery.completePending(EVIDENCE));

		assertSame(transport.completeResult, listener.success);
		assertNull(listener.error);
		assertEquals(1, transport.authorizeCalls);
		assertEquals(2, transport.completeCalls);
	}

	@Test
	public void missingLocalPhotoCanCompleteAnAlreadyUploadedObject(){
		FakeTransport transport=new FakeTransport();
		transport.completeResult=complete();
		RecordingListener listener=new RecordingListener();
		VerificationUploader uploader=uploader(transport, listener);

		uploader.resume(APPLICATION, null, VerificationUploader.Recovery.completePending(EVIDENCE));

		assertSame(transport.completeResult, listener.success);
		assertNull(listener.error);
		assertEquals(0, transport.authorizeCalls);
		assertEquals(1, transport.completeCalls);
	}

	private VerificationUploader uploader(FakeTransport transport, RecordingListener listener){
		OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain->new Response.Builder()
				.request(chain.request())
				.protocol(Protocol.HTTP_1_1)
				.code(putStatus)
				.message("test")
				.body(ResponseBody.create(null, new byte[0]))
				.build()).build();
		return new VerificationUploader(transport, client, Runnable::run, Runnable::run, millis->{}, value->{}, listener);
	}

	private UploadAuthorization authorization(){
		UploadAuthorization value=new UploadAuthorization();
		value.evidenceId=EVIDENCE;
		value.status="pending";
		value.uploadUrl="https://uploads.example.test/upload";
		value.expiresAt=System.currentTimeMillis()/1000+300;
		value.requiredHeaders=new LinkedHashMap<>();
		value.requiredHeaders.put("Content-Length", Long.toString(image.size()));
		value.requiredHeaders.put("Content-MD5", image.md5Base64());
		value.requiredHeaders.put("Content-Type", "image/jpeg");
		value.requiredHeaders.put("Authorization", "signed");
		value.requiredHeaders.put("x-cos-acl", "private");
		value.requiredHeaders.put("x-cos-forbid-overwrite", "true");
		value.requiredHeaders.put("x-cos-meta-sha256", image.sha256());
		return value;
	}

	private EvidenceComplete complete(){
		EvidenceComplete result=new EvidenceComplete();
		result.id=EVIDENCE;
		result.status="ready";
		result.verifiedSize=image.size();
		return result;
	}

	private static String hex(byte[] bytes){
		StringBuilder result=new StringBuilder(bytes.length*2);
		for(byte value:bytes) result.append(String.format(Locale.US, "%02x", value&0xff));
		return result.toString();
	}

	private static final class RecordingListener implements VerificationUploader.UploadListener{
		EvidenceComplete success;
		VerificationUploader.UploadError error;
		@Override public void onSuccess(EvidenceComplete result){ success=result; }
		@Override public void onError(VerificationUploader.UploadError error){ this.error=error; }
	}

	private static final class FakeTransport implements VerificationUploader.Transport{
		UploadAuthorization authorization;
		EvidenceComplete completeResult;
		final java.util.ArrayDeque<VerificationUploader.TransportException> completeErrors=new java.util.ArrayDeque<>();
		int authorizeCalls, completeCalls;

		@Override public UploadAuthorization authorize(String applicationId, String kind, String sha256, String md5, long size){ authorizeCalls++; return authorization; }
		@Override public EvidenceComplete complete(String evidenceId) throws java.io.IOException{
			completeCalls++;
			if(!completeErrors.isEmpty()) throw completeErrors.removeFirst();
			return completeResult;
		}
		@Override public void cancel(){}
		@Override public VerificationRequest<?> getCurrentRequest(){ return null; }
	}
}
