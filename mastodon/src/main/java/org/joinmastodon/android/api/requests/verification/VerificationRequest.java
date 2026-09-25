package org.joinmastodon.android.api.requests.verification;

import com.google.gson.JsonObject;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.model.verification.VerificationModels.ApplicationDetail;
import org.joinmastodon.android.model.verification.VerificationModels.ApplicationResult;
import org.joinmastodon.android.model.verification.VerificationModels.CancelResult;
import org.joinmastodon.android.model.verification.VerificationModels.CaptureSession;
import org.joinmastodon.android.model.verification.VerificationModels.CertificateEnvelope;
import org.joinmastodon.android.model.verification.VerificationModels.EvidenceComplete;
import org.joinmastodon.android.model.verification.VerificationModels.State;
import org.joinmastodon.android.model.verification.VerificationModels.SubmitResult;
import org.joinmastodon.android.model.verification.VerificationModels.UploadAuthorization;
import org.joinmastodon.android.model.verification.VerificationModels.VerifyResult;
import java.util.Map;
import me.grishka.appkit.api.ErrorResponse;

/** Sensitive first-party requests. Redirects and debug body logging are disabled by the API controller. */
public final class VerificationRequest<T> extends MastodonAPIRequest<T>{
	private VerificationRequest(HttpMethod method,String path,Class<T> type,Object body){ super(method,"/baby-verification"+path,type); if(body!=null)setRequestBody(body); setTimeout(30_000); }
	@Override public boolean isSensitiveRequest(){ return true; }
	@Override public boolean requiresSystemTrust(){ return true; }
	@Override public ErrorResponse deserializeError(JsonObject body,int status){ return new VerificationError(body,status); }
	@Override protected void onError(String msg,int httpStatus,Throwable exception){ dispatchError(new VerificationError(msg,httpStatus,exception)); }
	public static VerificationRequest<State> state(){ return new VerificationRequest<>(HttpMethod.GET,"/me",State.class,null); }
	public static VerificationRequest<CaptureSession> start(){ return new VerificationRequest<>(HttpMethod.POST,"/capture-sessions",CaptureSession.class,Map.of()); }
	public static VerificationRequest<CaptureSession> finishCapture(String id){ return new VerificationRequest<>(HttpMethod.POST,"/capture-sessions/"+id+"/complete",CaptureSession.class,Map.of()); }
	public static VerificationRequest<CaptureSession> cancelCapture(String id){ return new VerificationRequest<>(HttpMethod.POST,"/capture-sessions/"+id+"/cancel",CaptureSession.class,Map.of()); }
	public static VerificationRequest<ApplicationResult> createApplication(String sessionId,String qq,String declarationVersion){ return new VerificationRequest<>(HttpMethod.POST,"/applications",ApplicationResult.class,Map.of("capture_session_id",sessionId,"qq",qq,"adult_declaration",true,"declaration_version",declarationVersion)); }
	public static VerificationRequest<ApplicationDetail> detail(String id){ return new VerificationRequest<>(HttpMethod.GET,"/applications/"+id,ApplicationDetail.class,null); }
	public static VerificationRequest<CancelResult> cancelApplication(String id){ return new VerificationRequest<>(HttpMethod.POST,"/applications/"+id+"/cancel",CancelResult.class,Map.of()); }
	public static VerificationRequest<UploadAuthorization> authorize(String id,String kind,String sha256,String md5,long size){ return new VerificationRequest<>(HttpMethod.POST,"/applications/"+id+"/evidence/authorize",UploadAuthorization.class,Map.of("kind",kind,"mime_type","image/jpeg","declared_size",size,"content_sha256",sha256,"content_md5",md5)); }
	public static VerificationRequest<EvidenceComplete> complete(String id){ return new VerificationRequest<>(HttpMethod.POST,"/evidence/"+id+"/complete",EvidenceComplete.class,Map.of()); }
	public static VerificationRequest<SubmitResult> submit(String id){ return new VerificationRequest<>(HttpMethod.POST,"/applications/"+id+"/submit",SubmitResult.class,Map.of()); }
	public static VerificationRequest<ApplicationResult> acknowledgeRejection(String id){ return new VerificationRequest<>(HttpMethod.POST,"/applications/"+id+"/rejection-acknowledge",ApplicationResult.class,Map.of()); }
	public static VerificationRequest<CertificateEnvelope> certificate(){ return new VerificationRequest<>(HttpMethod.GET,"/certificates/me",CertificateEnvelope.class,null); }
	public static VerificationRequest<VerifyResult> verifyCertificate(String token){ return new VerificationRequest<>(HttpMethod.GET,"/verify/"+token,VerifyResult.class,null); }
	public static final class VerificationError extends MastodonErrorResponse{
		public final String code; public final boolean retryable,outcomeUnknown;
		public VerificationError(JsonObject body,int status){ super(string(body,"error","认证服务请求失败，请重试"),status,null); code=string(body,"code","unknown"); retryable=isRetryable(status); outcomeUnknown=status>=500; }
		public VerificationError(String message,int status,Throwable exception){ super(message==null||message.isBlank()?(status==0?"认证服务连接失败，请检查申请状态后重试":"认证服务响应无效，请重试"):message,status,exception); code=status==0?"transport_failure":"invalid_response"; retryable=isRetryable(status); outcomeUnknown=status==0; }
		private static boolean isRetryable(int status){ return status==0||status==408||status==425||status==429||status>=500; }
		private static String string(JsonObject body,String key,String fallback){ try{return body!=null&&body.has(key)&&!body.get(key).isJsonNull()?body.get(key).getAsString():fallback;}catch(RuntimeException ignored){return fallback;} }
	}
}
