package org.joinmastodon.android.api.novels;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.function.IntConsumer;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

public class PrivateBookUpload{
	public static final long MAX_SIZE=50L*1024L*1024L;
	private static final Set<String> REQUIRED_HEADERS=Set.of("Content-Length", "Content-MD5", "Content-Type", "Authorization", "x-cos-forbid-overwrite", "x-cos-meta-sha256");

	public enum State{ IDLE, PREPARING, AUTHORIZING, UPLOADING, COMPLETING, COMPLETE, FAILED, CANCELED }

	public static class Recovery{
		public static final String PUT_PENDING="PUT_PENDING";
		public static final String COMPLETE_PENDING="COMPLETE_PENDING";
		public static final String COMPLETE="COMPLETE";
		public final String uploadId;
		public final String phase;

		public Recovery(String uploadId, String phase){
			this.uploadId=uploadId;
			this.phase=phase;
		}
	}

	@FunctionalInterface
	public interface RecoveryListener{
		void onPhase(String uploadId, String phase) throws IOException;
	}

	private final PrivateNovelApi api;
	private final IntConsumer progressListener;
	private final LongConsumer sleeper;
	private final Runnable completionGate;
	private final AtomicReference<Call> currentCall=new AtomicReference<>();
	private final Object callLock=new Object();
	private volatile State state=State.IDLE;
	private volatile boolean canceled;
	private int lastProgress;

	public PrivateBookUpload(PrivateNovelApi api, IntConsumer progressListener){
		this(api, progressListener, millis -> {
			try{
				Thread.sleep(millis);
			}catch(InterruptedException e){
				Thread.currentThread().interrupt();
				throw new RuntimeException(e);
			}
		}, () -> {});
	}

	public PrivateBookUpload(PrivateNovelApi api, IntConsumer progressListener, LongConsumer sleeper){
		this(api, progressListener, sleeper, () -> {});
	}

	PrivateBookUpload(PrivateNovelApi api, IntConsumer progressListener, LongConsumer sleeper, Runnable completionGate){
		this.api=api;
		this.progressListener=progressListener;
		this.sleeper=sleeper;
		this.completionGate=completionGate;
	}

	public State getState(){
		return state;
	}

	public PrivateNovelApi.BookDto upload(File file, PrivateNovelApi.UploadMetadata metadata) throws IOException{
		return resume(file, metadata, null, (uploadId, phase) -> {});
	}

	public PrivateNovelApi.BookDto resume(File file, PrivateNovelApi.UploadMetadata metadata, Recovery recovery, RecoveryListener recoveryListener) throws IOException{
		try{
			validateFile(file, metadata);
			state=State.PREPARING;
			report(0);
			String sha256=sha256(file);
			String md5=md5Base64(file);
			checkCanceled();

			String uploadId=recovery==null ? null : recovery.uploadId;
			if(recovery==null || !Recovery.COMPLETE_PENDING.equals(recovery.phase)){
				state=State.AUTHORIZING;
				PrivateNovelApi.AuthorizeRequest request=new PrivateNovelApi.AuthorizeRequest(metadata, file.length(), sha256, md5);
				PrivateNovelApi.UploadAuthorization authorization=execute(api.newAuthorizeCall(request), PrivateNovelApi.UploadAuthorization.class);
				validateAuthorization(authorization, file.length(), sha256, md5, metadata.mimeType);
				uploadId=authorization.uploadId;
				recoveryListener.onPhase(uploadId, Recovery.PUT_PENDING);
				report(5);

				if(authorization.alreadyUploaded){
					PrivateNovelApi.BookDto ready=bookResult(uploadId, metadata, file.length(), sha256, authorization.parseStatus);
					completionGate.run();
					checkCanceled();
					recoveryListener.onPhase(uploadId, Recovery.COMPLETE);
					publishComplete();
					return ready;
				}
				state=State.UPLOADING;
				try{
					uploadFile(file, authorization);
				}catch(IOException error){
					if(!isUncertainPut(error)) throw error;
				}
				recoveryListener.onPhase(uploadId, Recovery.COMPLETE_PENDING);
				report(95);
			}else if(uploadId==null || uploadId.isEmpty()){
				throw new IOException("Missing recovery upload id");
			}

			checkCanceled();
			state=State.COMPLETING;
			PrivateNovelApi.CompleteResultDto completed=pollComplete(uploadId);
			PrivateNovelApi.BookDto result=bookResult(completed.id, metadata, completed.verifiedSize, sha256, completed.parseStatus);
			completionGate.run();
			checkCanceled();
			recoveryListener.onPhase(uploadId, Recovery.COMPLETE);
			publishComplete();
			return result;
		}catch(IOException e){
			state=canceled ? State.CANCELED : State.FAILED;
			throw e;
		}catch(RuntimeException e){
			state=canceled ? State.CANCELED : State.FAILED;
			throw new IOException("Upload failed", e);
		}finally{
			currentCall.set(null);
		}
	}

	public void cancel(){
		synchronized(callLock){
			canceled=true;
			state=State.CANCELED;
			Call call=currentCall.get();
			if(call!=null) call.cancel();
		}
	}

	private void uploadFile(File file, PrivateNovelApi.UploadAuthorization authorization) throws IOException{
		RequestBody body=new RequestBody(){
			@Override
			public MediaType contentType(){
				return MediaType.parse(authorization.requiredHeaders.get("Content-Type"));
			}

			@Override
			public long contentLength(){
				return file.length();
			}

			@Override
			public void writeTo(BufferedSink sink) throws IOException{
				try(FileInputStream in=new FileInputStream(file)){
					byte[] buffer=new byte[8192];
					long sent=0;
					int read;
					while((read=in.read(buffer))!=-1){
						checkCanceled();
						sink.write(buffer, 0, read);
						sent+=read;
						report(5+(int)(sent*90/file.length()));
					}
				}
			}
		};
		Request.Builder builder=new Request.Builder().url(authorization.uploadUrl).put(body);
		for(Map.Entry<String, String> header:authorization.requiredHeaders.entrySet())
			builder.header(header.getKey(), header.getValue());
		Call call=api.getCallFactory().newCall(builder.build());
		register(call);
		try(Response response=call.execute()){
			if(response.priorResponse()!=null)
				throw new IOException("Redirects are not allowed");
			if(!response.isSuccessful())
				throw new PutException(response.code());
		}finally{
			currentCall.compareAndSet(call, null);
		}
	}

	private <T> T execute(Call call, Class<T> type) throws IOException{
		register(call);
		try{
			return api.executeJson(call, type);
		}finally{
			currentCall.compareAndSet(call, null);
		}
	}

	private PrivateNovelApi.CompleteResultDto pollComplete(String uploadId) throws IOException{
		IOException lastError=null;
		for(int attempt=0; attempt<6; attempt++){
			checkCanceled();
			try{
				PrivateNovelApi.ApiResponse<PrivateNovelApi.CompleteResultDto> response=executeResponse(api.newCompleteCall(uploadId), PrivateNovelApi.CompleteResultDto.class);
				PrivateNovelApi.CompleteResultDto result=response.body;
				if(result==null || result.id==null || result.format==null || result.verifiedSize<=0 || result.parseStatus==null)
					throw new IOException("Invalid complete response");
				if("ready".equals(result.parseStatus)) return result;
				if(response.status!=202 || !"parsing".equals(result.parseStatus)) throw new IOException("Unexpected parse status");
			}catch(PrivateNovelApi.ApiException e){
				if(!isRetryableComplete(e)) throw e;
				lastError=e;
			}
			if(attempt<5) sleeper.accept(Math.min(2000L, 100L << attempt));
		}
		throw lastError!=null ? lastError : new IOException("Book parsing timed out");
	}

	private <T> PrivateNovelApi.ApiResponse<T> executeResponse(Call call, Class<T> type) throws IOException{
		register(call);
		try{
			return api.executeJsonResponse(call, type);
		}finally{
			currentCall.compareAndSet(call, null);
		}
	}

	private void register(Call call) throws IOException{
		synchronized(callLock){
			if(canceled){
				call.cancel();
				throw new IOException("Canceled");
			}
			currentCall.set(call);
		}
	}

	private static boolean isRetryableComplete(PrivateNovelApi.ApiException error){
		return error.status==408 || error.status==429 || error.status>=500 ||
				(error.status==422 && ("verification_failed".equals(error.code) || "verification_unavailable".equals(error.code)));
	}

	private static boolean isUncertainPut(IOException error){
		if(!(error instanceof PutException)) return true;
		int status=((PutException) error).status;
		return status==408 || status==409 || status==429 || status>=500;
	}

	private static PrivateNovelApi.BookDto bookResult(String id, PrivateNovelApi.UploadMetadata metadata, long size, String hash, String parseStatus){
		PrivateNovelApi.BookDto result=new PrivateNovelApi.BookDto();
		result.id=id;
		result.title=metadata.title;
		result.author=metadata.author;
		result.format=metadata.format;
		result.contentHash=hash;
		result.verifiedSize=size;
		result.parseStatus=parseStatus;
		return result;
	}

	private static class PutException extends IOException{
		final int status;

		PutException(int status){
			super("Upload failed: HTTP "+status);
			this.status=status;
		}
	}

	private static void validateFile(File file, PrivateNovelApi.UploadMetadata metadata) throws IOException{
		if(!file.isFile() || file.length()==0 || file.length()>MAX_SIZE)
			throw new IOException("Book file size is invalid");
		if(metadata==null || metadata.title==null || metadata.title.trim().isEmpty() || metadata.author==null || metadata.author.trim().isEmpty())
			throw new IOException("Book metadata is invalid");
		if(!("txt".equals(metadata.format) && "text/plain".equals(metadata.mimeType)) &&
				!("epub".equals(metadata.format) && "application/epub+zip".equals(metadata.mimeType)))
			throw new IOException("Unsupported book format");
	}

	private void validateAuthorization(PrivateNovelApi.UploadAuthorization authorization, long size, String sha256, String md5, String mimeType) throws IOException{
		if(authorization==null || authorization.uploadId==null || authorization.uploadId.isEmpty())
			throw new IOException("Missing book id");
		if(authorization.alreadyUploaded){
			if(!"ready".equals(authorization.parseStatus))
				throw new IOException("Unexpected uploaded book status");
			return;
		}
		if(!api.isAllowedTransferUrl(authorization.uploadUrl))
			throw new IOException("Upload URL must use HTTPS");
		if(authorization.requiredHeaders==null || !authorization.requiredHeaders.keySet().containsAll(REQUIRED_HEADERS))
			throw new IOException("Missing required upload headers");
		if(!Long.toString(size).equals(authorization.requiredHeaders.get("Content-Length")) ||
				!md5.equals(authorization.requiredHeaders.get("Content-MD5")) ||
				!mimeType.equals(authorization.requiredHeaders.get("Content-Type")) ||
				!sha256.equals(authorization.requiredHeaders.get("x-cos-meta-sha256")) ||
				!"true".equals(authorization.requiredHeaders.get("x-cos-forbid-overwrite")) ||
				authorization.requiredHeaders.get("Authorization").isEmpty())
			throw new IOException("Signed upload headers do not match the file");
	}

	private void checkCanceled() throws IOException{
		if(canceled)
			throw new IOException("Canceled");
	}

	private void publishComplete() throws IOException{
		synchronized(callLock){
			checkCanceled();
			state=State.COMPLETE;
			report(100);
		}
	}

	private synchronized void report(int value){
		int bounded=Math.max(lastProgress, Math.min(100, value));
		if(bounded!=lastProgress || value==0){
			lastProgress=bounded;
			progressListener.accept(bounded);
		}
	}

	public static String sha256(File file) throws IOException{
		return hex(digest(file, "SHA-256"));
	}

	public static String sha256(byte[] bytes){
		try{
			return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}catch(NoSuchAlgorithmException e){
			throw new AssertionError(e);
		}
	}

	public static String md5Base64(File file) throws IOException{
		return Base64.getEncoder().encodeToString(digest(file, "MD5"));
	}

	private static byte[] digest(File file, String algorithm) throws IOException{
		try{
			MessageDigest digest=MessageDigest.getInstance(algorithm);
			try(FileInputStream in=new FileInputStream(file)){
				byte[] buffer=new byte[8192];
				int read;
				while((read=in.read(buffer))!=-1)
					digest.update(buffer, 0, read);
			}
			return digest.digest();
		}catch(NoSuchAlgorithmException e){
			throw new AssertionError(e);
		}
	}

	private static String hex(byte[] bytes){
		StringBuilder result=new StringBuilder(bytes.length*2);
		for(byte value:bytes)
			result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
		return result.toString();
	}
}
