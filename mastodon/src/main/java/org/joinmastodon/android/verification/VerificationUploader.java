package org.joinmastodon.android.verification;

import android.os.Handler;
import android.os.Looper;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.api.requests.verification.VerificationRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.model.verification.VerificationModels.ApplicationDetail;
import org.joinmastodon.android.model.verification.VerificationModels.Evidence;
import org.joinmastodon.android.model.verification.VerificationModels.EvidenceComplete;
import org.joinmastodon.android.model.verification.VerificationModels.UploadAuthorization;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/** Cancellable and resumable authorize/PUT/complete state machine for private verification evidence. */
public final class VerificationUploader{
	private static final MediaType JPEG=MediaType.get("image/jpeg");
	private static final int MAX_VERIFY_ATTEMPTS=6, MAX_REAUTHORIZE_ATTEMPTS=2;
	private static final ExecutorService UPLOAD_EXECUTOR=Executors.newCachedThreadPool(r->{
		Thread thread=new Thread(r, "VerificationUpload");
		thread.setDaemon(true);
		return thread;
	});

	public enum State{ IDLE, AUTHORIZING, UPLOADING, COMPLETING, COMPLETE, FAILED, CANCELED }
	public enum Phase{ PREPARED, PUT_PENDING, COMPLETE_PENDING, COMPLETE }

	/** Persistable recovery boundary. Evidence in pending/verifying state resumes at COMPLETE_PENDING first. */
	public static final class Recovery{
		public final String evidenceId;
		public final Phase phase;
		public final Long verifiedSize;

		public Recovery(String evidenceId, Phase phase){ this(evidenceId, phase, null); }
		public Recovery(String evidenceId, Phase phase, Long verifiedSize){
			if(phase==null) throw new IllegalArgumentException("phase is required");
			if(phase!=Phase.PREPARED && (evidenceId==null || evidenceId.isBlank())) throw new IllegalArgumentException("evidenceId is required");
			if(phase==Phase.COMPLETE && (verifiedSize==null || verifiedSize<=0)) throw new IllegalArgumentException("verifiedSize is required");
			this.evidenceId=evidenceId;
			this.phase=phase;
			this.verifiedSize=verifiedSize;
		}
		public static Recovery prepared(){ return new Recovery(null, Phase.PREPARED); }
		public static Recovery putPending(String evidenceId){ return new Recovery(evidenceId, Phase.PUT_PENDING); }
		public static Recovery completePending(String evidenceId){ return new Recovery(evidenceId, Phase.COMPLETE_PENDING); }
		public static Recovery complete(String evidenceId, long verifiedSize){ return new Recovery(evidenceId, Phase.COMPLETE, verifiedSize); }
		public static Recovery fromEvidence(Evidence evidence){
			if(evidence==null) return prepared();
			if("ready".equals(evidence.status)) return complete(evidence.id, evidence.verifiedSize==null ? 0 : evidence.verifiedSize);
			return completePending(evidence.id);
		}
		public static Recovery fromDetail(ApplicationDetail detail, String kind){ return fromEvidence(detail==null ? null : detail.evidence(kind)); }
	}

	/** New integration callback. Production callbacks are serialized onto the Android main thread. */
	public interface UploadListener{
		default void onStateChanged(State state, Recovery recovery){}
		default void onCall(Call call){}
		void onSuccess(EvidenceComplete result);
		void onError(UploadError error);
	}

	/** Structured failure. Backend codes are preserved; local protocol failures use stable local codes. */
	public static final class UploadError extends MastodonErrorResponse{
		public final String code;
		public final boolean retryable, outcomeUnknown, requiresLocalImage, requiresNewCapture, putAttempted;
		public final Recovery recovery;

		private UploadError(String message, int status, Throwable cause, String code, boolean retryable, boolean outcomeUnknown,
				boolean requiresLocalImage, boolean requiresNewCapture, boolean putAttempted, Recovery recovery){
			super(message, status, cause);
			this.code=code;
			this.retryable=retryable;
			this.outcomeUnknown=outcomeUnknown;
			this.requiresLocalImage=requiresLocalImage;
			this.requiresNewCapture=requiresNewCapture;
			this.putAttempted=putAttempted;
			this.recovery=recovery;
		}
	}

	/** Backend transport is replaceable so the state machine can be unit-tested without AccountSession globals. */
	public interface Transport{
		UploadAuthorization authorize(String applicationId, String kind, String sha256, String md5, long size) throws IOException;
		EvidenceComplete complete(String evidenceId) throws IOException;
		void cancel();
		VerificationRequest<?> getCurrentRequest();
	}

	/** IOException carrying an exact backend error through a synchronous Transport. */
	public static final class TransportException extends IOException{
		public final ErrorResponse error;
		public final String code;
		public final int status;
		public final boolean retryable, outcomeUnknown;

		public TransportException(ErrorResponse error){
			super(error==null ? "认证服务请求失败" : error.toString(), error instanceof MastodonErrorResponse value ? value.underlyingException : null);
			this.error=error;
			if(error instanceof VerificationRequest.VerificationError value){
				code=value.code;
				status=value.httpStatus;
				retryable=value.retryable;
				outcomeUnknown=value.outcomeUnknown;
			}else if(error instanceof MastodonErrorResponse value){
				code="unknown";
				status=value.httpStatus;
				retryable=status==0 || status==408 || status==429 || status>=500;
				outcomeUnknown=status==0;
			}else{
				code="unknown";
				status=0;
				retryable=true;
				outcomeUnknown=true;
			}
		}
		public TransportException(String message, int status, String code, boolean retryable, boolean outcomeUnknown){
			super(message);
			this.error=new MastodonErrorResponse(message, status, null);
			this.code=code;
			this.status=status;
			this.retryable=retryable;
			this.outcomeUnknown=outcomeUnknown;
		}
	}

	@FunctionalInterface interface Sleeper{ void sleep(long millis) throws InterruptedException; }
	@FunctionalInterface interface UploadUrlValidator{ void validate(String url) throws IOException; }

	private final Transport transport;
	private final Call.Factory putCallFactory;
	private final Executor executor, callbackExecutor;
	private final Sleeper sleeper;
	private final UploadUrlValidator urlValidator;
	private final UploadListener listener;
	private final AtomicReference<Call> currentCall=new AtomicReference<>();
	private final AtomicReference<Thread> workerThread=new AtomicReference<>();
	private final Object stateLock=new Object();
	private volatile State state=State.IDLE;
	private volatile Recovery recovery=Recovery.prepared();
	private volatile boolean canceled, putAttempted;

	public VerificationUploader(AccountSession session, UploadListener listener){
		this(new RequestTransport(session), productionPutClient(), UPLOAD_EXECUTOR, mainExecutor(), Thread::sleep, VerificationUploader::validateUploadUrl, listener);
	}

	private static okhttp3.OkHttpClient productionPutClient(){
		return MastodonAPIController.getSystemTrustSensitiveHttpClient().newBuilder()
				.cache(null).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
	}

	VerificationUploader(Transport transport, Call.Factory putCallFactory, Executor executor, Executor callbackExecutor, Sleeper sleeper,
			UploadUrlValidator urlValidator, UploadListener listener){
		if(transport==null || putCallFactory==null || executor==null || callbackExecutor==null || sleeper==null || urlValidator==null || listener==null)
			throw new IllegalArgumentException("VerificationUploader dependencies are required");
		this.transport=transport;
		this.putCallFactory=putCallFactory;
		this.executor=executor;
		this.callbackExecutor=callbackExecutor;
		this.sleeper=sleeper;
		this.urlValidator=urlValidator;
		this.listener=listener;
	}

	/** Starts a new capture_photo upload. One uploader instance owns one operation. */
	public void start(String applicationId, VerificationImageProcessor.Result image){ begin(applicationId, image, Recovery.prepared()); }

	/** Resumes a persisted boundary. image may be null only when complete can be attempted without re-uploading. */
	public void resume(String applicationId, VerificationImageProcessor.Result image, Recovery recovery){ begin(applicationId, image, recovery); }

	private void begin(String applicationId, VerificationImageProcessor.Result image, Recovery initialRecovery){
		if(applicationId==null || applicationId.isBlank()) throw new IllegalArgumentException("applicationId is required");
		if(initialRecovery==null) throw new IllegalArgumentException("recovery is required");
		synchronized(stateLock){
			if(state!=State.IDLE) throw new IllegalStateException("This uploader has already started");
			if(initialRecovery.phase==Phase.PREPARED && image==null) throw new IllegalArgumentException("image is required for a new upload");
			recovery=initialRecovery;
		}
		executor.execute(()->run(applicationId, image, initialRecovery));
	}

	public void cancel(){
		State previous;
		synchronized(stateLock){
			previous=state;
			if(state==State.COMPLETE || state==State.FAILED || state==State.CANCELED) return;
			canceled=true;
			state=State.CANCELED;
		}
		transport.cancel();
		Call call=currentCall.getAndSet(null);
		if(call!=null) call.cancel();
		Thread thread=workerThread.get();
		if(thread!=null) thread.interrupt();
		if(previous!=State.CANCELED) dispatchState(State.CANCELED, recovery);
	}

	public State getState(){ return state; }
	public Phase getPhase(){ return recovery.phase; }
	public Recovery getRecovery(){ return recovery; }
	public Call getCurrentCall(){ return currentCall.get(); }
	public VerificationRequest<?> getCurrentRequest(){ return transport.getCurrentRequest(); }
	public boolean isRunning(){ return state==State.AUTHORIZING || state==State.UPLOADING || state==State.COMPLETING; }

	private void run(String applicationId, VerificationImageProcessor.Result image, Recovery initialRecovery){
		workerThread.set(Thread.currentThread());
		try{
			EvidenceComplete result;
				if(initialRecovery.phase==Phase.COMPLETE){
					result=completed(initialRecovery.evidenceId, initialRecovery.verifiedSize);
				}else if(initialRecovery.phase==Phase.PUT_PENDING){
					result=authorizePutComplete(applicationId, requireImage(image), 0);
				}else if(initialRecovery.phase==Phase.COMPLETE_PENDING){
					result=completeOrReauthorize(applicationId, image, initialRecovery.evidenceId, 0);
				}else{
					result=authorizePutComplete(applicationId, requireImage(image), 0);
				}
			publishSuccess(result);
		}catch(CanceledException ignored){
			publishCanceled();
		}catch(UploadFailure failure){
			publishFailure(failure.error);
		}catch(Exception error){
			publishFailure(localError("internal_error", error.getMessage()==null ? "认证上传失败" : error.getMessage(), 0, error, true, false, false));
		}finally{
			workerThread.compareAndSet(Thread.currentThread(), null);
			currentCall.set(null);
		}
	}

	private EvidenceComplete authorizePutComplete(String applicationId, VerificationImageProcessor.Result image, int reauthorizeAttempt) throws IOException, UploadFailure{
		checkCanceled();
		validateImage(image);
		transition(State.AUTHORIZING, Recovery.prepared());
		UploadAuthorization authorization;
		try{
			authorization=transport.authorize(applicationId, "capture_photo", image.sha256(), image.md5Base64(), image.size());
		}catch(IOException error){
			throw failure(fromTransport(error, false, false));
		}
		checkCanceled();
		try{
			authorization.postprocess();
			validateAuthorization(authorization, image);
		}catch(IOException error){
			throw failure(localError("authorization_mismatch", error.getMessage(), 0, error, false, false, false));
		}
		transition(State.AUTHORIZING, Recovery.putPending(authorization.evidenceId));
		if(!authorization.alreadyUploaded){
			validateImage(image);
			transition(State.UPLOADING, recovery);
			putAttempted=true;
			try{
				put(authorization, image);
			}catch(PutException error){
				if(!isUncertainPut(error.status)) throw failure(localError("put_failed", error.getMessage(), error.status, error, false, false, false));
			}catch(IOException error){
				checkCanceled();
				// A lost PUT response is ambiguous. Completing is the only safe idempotent probe.
			}
		}
		return completeOrReauthorize(applicationId, image, authorization.evidenceId, reauthorizeAttempt);
	}

	private EvidenceComplete completeOrReauthorize(String applicationId, VerificationImageProcessor.Result image, String evidenceId, int reauthorizeAttempt) throws IOException, UploadFailure{
		transition(State.COMPLETING, Recovery.completePending(evidenceId));
		for(int attempt=0;attempt<MAX_VERIFY_ATTEMPTS;attempt++){
			checkCanceled();
			try{
				EvidenceComplete result=transport.complete(evidenceId);
				if(result==null) throw new ObjectValidationException("照片校验响应无效");
				result.postprocess();
				if(!evidenceId.equals(result.id) || (image!=null && result.verifiedSize!=image.size())) throw new ObjectValidationException("照片校验结果与本地文件不匹配");
				return result;
			}catch(TransportException error){
				if("evidence_verifying".equals(error.code) && attempt+1<MAX_VERIFY_ATTEMPTS){
					sleepBackoff(attempt);
					continue;
				}
				if("upload_expired".equals(error.code)){
					if(image==null) throw failure(fromTransport(error, true, true));
					if(reauthorizeAttempt>=MAX_REAUTHORIZE_ATTEMPTS) throw failure(fromTransport(error, false, false));
					return authorizePutComplete(applicationId, image, reauthorizeAttempt+1);
				}
				if("evidence_mismatch".equals(error.code)) throw failure(fromTransport(error, false, true));
				if("evidence_not_found".equals(error.code) && image==null) throw failure(fromTransport(error, true, true));
				throw failure(fromTransport(error, false, false));
			}catch(ObjectValidationException error){
				throw failure(localError("complete_result_mismatch", error.getMessage(), 0, error, false, false, true));
			}catch(IOException error){
				throw failure(fromTransport(error, false, false));
			}
		}
		throw failure(localError("evidence_verifying", "照片校验仍在进行", 409, null, true, false, false));
	}

	private void put(UploadAuthorization authorization, VerificationImageProcessor.Result image) throws IOException{
		RequestBody body=new RequestBody(){
			@Override public MediaType contentType(){ return JPEG; }
			@Override public long contentLength(){ return image.size(); }
			@Override public void writeTo(BufferedSink sink) throws IOException{
				try(FileInputStream input=new FileInputStream(image.file())){
					byte[] buffer=new byte[8192]; int read;
					while((read=input.read(buffer))!=-1){ checkCanceled(); sink.write(buffer, 0, read); }
				}
			}
		};
		Request.Builder builder=new Request.Builder().url(authorization.uploadUrl).put(body);
		for(Map.Entry<String, String> header:authorization.requiredHeaders.entrySet()) builder.header(header.getKey(), header.getValue());
		Call call=putCallFactory.newCall(builder.build());
		registerCall(call);
		try(Response response=call.execute()){
			if(response.priorResponse()!=null || response.isRedirect()) throw new PutException(response.code(), "认证上传不允许重定向");
			if(!response.isSuccessful()) throw new PutException(response.code(), "认证上传失败 (HTTP "+response.code()+")");
		}finally{
			currentCall.compareAndSet(call, null);
		}
	}

	private void registerCall(Call call) throws CanceledException{
		synchronized(stateLock){
			checkCanceled();
			currentCall.set(call);
		}
		callbackExecutor.execute(()->{ if(!canceled) listener.onCall(call); });
	}

	private void sleepBackoff(int attempt) throws CanceledException{
		try{
			sleeper.sleep(Math.min(2000L, 100L << attempt));
		}catch(InterruptedException error){
			Thread.currentThread().interrupt();
			checkCanceled();
			throw new CanceledException();
		}
	}

	private void transition(State next, Recovery nextRecovery) throws CanceledException{
		synchronized(stateLock){
			checkCanceled();
			state=next;
			recovery=nextRecovery;
		}
		dispatchState(next, nextRecovery);
	}

	private void publishSuccess(EvidenceComplete result){
		Recovery complete=Recovery.complete(result.id, result.verifiedSize);
		synchronized(stateLock){
			if(canceled || state==State.CANCELED) return;
			state=State.COMPLETE;
			recovery=complete;
		}
		dispatchState(State.COMPLETE, complete);
		callbackExecutor.execute(()->{ if(state==State.COMPLETE) listener.onSuccess(result); });
	}

	private void publishFailure(UploadError error){
		synchronized(stateLock){
			if(canceled || state==State.CANCELED) return;
			state=State.FAILED;
		}
		dispatchState(State.FAILED, recovery);
		callbackExecutor.execute(()->{ if(state==State.FAILED) listener.onError(error); });
	}

	private void publishCanceled(){
		synchronized(stateLock){ canceled=true; state=State.CANCELED; }
		dispatchState(State.CANCELED, recovery);
	}

	private void dispatchState(State value, Recovery valueRecovery){ callbackExecutor.execute(()->listener.onStateChanged(value, valueRecovery)); }

	private UploadError fromTransport(IOException error, boolean requiresLocalImage, boolean requiresNewCapture){
		if(error instanceof TransportException value){
			String message=value.error==null ? value.getMessage() : value.error.toString();
			return new UploadError(message, value.status, value, value.code, value.retryable && !requiresNewCapture, value.outcomeUnknown,
					requiresLocalImage, requiresNewCapture, putAttempted, recovery);
		}
		return new UploadError(error.getMessage()==null ? "认证服务连接失败" : error.getMessage(), 0, error, "transport_failure", true, true,
				requiresLocalImage, requiresNewCapture, putAttempted, recovery);
	}

	private UploadError localError(String code, String message, int status, Throwable cause, boolean retryable, boolean outcomeUnknown, boolean requiresNewCapture){
		return new UploadError(message==null || message.isBlank() ? "认证上传失败" : message, status, cause, code, retryable, outcomeUnknown,
				false, requiresNewCapture, putAttempted, recovery);
	}

	private static UploadFailure failure(UploadError error){ return new UploadFailure(error); }
	private static VerificationImageProcessor.Result requireImage(VerificationImageProcessor.Result image) throws UploadFailure{
		if(image==null) throw failure(new UploadError("本地认证照片不存在", 0, null, "local_image_required", false, false, true, true, false, Recovery.prepared()));
		return image;
	}

	private static void validateImage(VerificationImageProcessor.Result image) throws IOException{
		if(image==null || image.file()==null || !image.file().isFile() || image.size()<=0 || image.file().length()!=image.size()
				|| image.width()<=0 || image.height()<=0 || Math.max(image.width(), image.height())>VerificationImageProcessor.MAX_EDGE
				|| image.sha256()==null || !image.sha256().matches("[a-f0-9]{64}") || image.md5Base64()==null)
			throw new IOException("本地认证照片元数据无效");
		try(FileInputStream input=new FileInputStream(image.file())){
			if(input.read()!=0xff || input.read()!=0xd8) throw new IOException("本地认证照片不是 JPEG");
		}
		if(!image.sha256().equals(digestHex(image.file(), "SHA-256")) || !image.md5Base64().equals(digestBase64(image.file(), "MD5")))
			throw new IOException("本地认证照片摘要不匹配");
	}

	private void validateAuthorization(UploadAuthorization authorization, VerificationImageProcessor.Result image) throws IOException{
		if(authorization.alreadyUploaded) return;
		urlValidator.validate(authorization.uploadUrl);
		Map<String, String> headers=authorization.requiredHeaders;
		if(headers==null || !Long.toString(image.size()).equals(headers.get("Content-Length")) || !image.md5Base64().equals(headers.get("Content-MD5"))
				|| !"image/jpeg".equals(headers.get("Content-Type")) || !image.sha256().equals(headers.get("x-cos-meta-sha256"))
				|| !"private".equals(headers.get("x-cos-acl")) || !"true".equals(headers.get("x-cos-forbid-overwrite"))
				|| headers.get("Authorization")==null || headers.get("Authorization").isBlank()) throw new IOException("签名上传请求头与本地照片不匹配");
	}

	static void validateUploadUrl(String value) throws IOException{
		try{
			URI uri=value==null ? null : new URI(value);
			String expected=BuildConfig.BABY_VERIFICATION_COS_HOST.trim().toLowerCase(Locale.US);
			if(expected.isEmpty() || uri==null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || !expected.equals(uri.getHost().toLowerCase(Locale.US))
					|| (uri.getPort()!=-1 && uri.getPort()!=443) || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null
					|| uri.getPath()==null || !uri.getPath().startsWith("/baby-verification/private/")) throw new IOException("认证私有存储地址不可信");
		}catch(URISyntaxException error){
			throw new IOException("认证私有存储地址不可信", error);
		}
	}

	private void checkCanceled() throws CanceledException{ if(canceled || Thread.currentThread().isInterrupted()) throw new CanceledException(); }
	private static boolean isUncertainPut(int status){ return status==408 || status==409 || status==429 || status>=500; }
	private static EvidenceComplete completed(String evidenceId, long verifiedSize){ EvidenceComplete result=new EvidenceComplete(); result.id=evidenceId; result.status="ready"; result.verifiedSize=verifiedSize; return result; }
	private static String digestHex(File file, String algorithm) throws IOException{ return hex(digest(file, algorithm)); }
	private static String digestBase64(File file, String algorithm) throws IOException{ return Base64.getEncoder().encodeToString(digest(file, algorithm)); }
	private static byte[] digest(File file, String algorithm) throws IOException{
		try{
			MessageDigest digest=MessageDigest.getInstance(algorithm);
			try(FileInputStream input=new FileInputStream(file)){ byte[] buffer=new byte[8192]; int read; while((read=input.read(buffer))!=-1) digest.update(buffer, 0, read); }
			return digest.digest();
		}catch(NoSuchAlgorithmException impossible){ throw new AssertionError(impossible); }
	}
	private static String hex(byte[] bytes){ StringBuilder result=new StringBuilder(bytes.length*2); for(byte value:bytes) result.append(String.format(Locale.US, "%02x", value&0xff)); return result.toString(); }
	private static Executor mainExecutor(){ Handler handler=new Handler(Looper.getMainLooper()); return command->handler.post(command); }

	private static final class PutException extends IOException{
		final int status;
		PutException(int status, String message){ super(message); this.status=status; }
	}
	private static final class CanceledException extends IOException{}
	private static final class UploadFailure extends Exception{
		final UploadError error;
		UploadFailure(UploadError error){ super(error.error, error.underlyingException); this.error=error; }
	}

	private static final class RequestTransport implements Transport{
		private final AccountSession session;
		private final AtomicReference<VerificationRequest<?>> currentRequest=new AtomicReference<>();
		private final AtomicReference<CountDownLatch> currentLatch=new AtomicReference<>();

		RequestTransport(AccountSession session){ if(session==null) throw new IllegalArgumentException("session is required"); this.session=session; }
		@Override public UploadAuthorization authorize(String applicationId, String kind, String sha256, String md5, long size) throws IOException{
			return execute(VerificationRequest.authorize(applicationId, kind, sha256, md5, size));
		}
		@Override public EvidenceComplete complete(String evidenceId) throws IOException{ return execute(VerificationRequest.complete(evidenceId)); }
		@Override public void cancel(){
			VerificationRequest<?> request=currentRequest.getAndSet(null); if(request!=null) request.cancel();
			CountDownLatch latch=currentLatch.getAndSet(null); if(latch!=null) latch.countDown();
		}
		@Override public VerificationRequest<?> getCurrentRequest(){ return currentRequest.get(); }

		private <T> T execute(VerificationRequest<T> request) throws IOException{
			CountDownLatch latch=new CountDownLatch(1);
			AtomicReference<T> result=new AtomicReference<>();
			AtomicReference<ErrorResponse> error=new AtomicReference<>();
			request.setCallback(new Callback<>(){
				@Override public void onSuccess(T value){ result.set(value); latch.countDown(); }
				@Override public void onError(ErrorResponse value){ error.set(value); latch.countDown(); }
			});
			currentRequest.set(request);
			currentLatch.set(latch);
			request.exec(session.getID());
			try{
				latch.await();
			}catch(InterruptedException interrupted){
				request.cancel();
				Thread.currentThread().interrupt();
				throw new CanceledException();
			}finally{
				currentRequest.compareAndSet(request, null);
				currentLatch.compareAndSet(latch, null);
			}
			if(error.get()!=null) throw new TransportException(error.get());
			if(result.get()==null) throw new CanceledException();
			return result.get();
		}
	}
}
