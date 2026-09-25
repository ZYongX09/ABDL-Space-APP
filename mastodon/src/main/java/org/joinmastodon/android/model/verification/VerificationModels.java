package org.joinmastodon.android.model.verification;

import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.model.BaseModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** DTOs for /api/v1/baby-verification. Field names are converted from snake_case by the shared Gson instance. */
public final class VerificationModels{
	private VerificationModels(){}

	public enum Status{
		NOT_STARTED, DRAFT, SUBMITTED, REVIEWING, APPROVED, REJECTED, CANCELLED;

		public static Status parse(String value) throws ObjectValidationException{
			if(value==null) return NOT_STARTED;
			try{ return valueOf(value.toUpperCase(Locale.US)); }
			catch(IllegalArgumentException ignored){ throw new ObjectValidationException("未知认证状态"); }
		}
	}

	public static class Config extends BaseModel{
		public int version, freeMonthlyLimit, sponsorMonthlyLimit, captureTtlSeconds, uploadTtlSeconds;
		public long maxEvidenceSize;
		public boolean enabled;
		public String declarationVersion;
		@Override public void postprocess() throws ObjectValidationException{
			if(version<1 || declarationVersion==null || declarationVersion.isBlank() || freeMonthlyLimit<0 || sponsorMonthlyLimit<freeMonthlyLimit
					|| captureTtlSeconds<300 || uploadTtlSeconds<60 || maxEvidenceSize<1024) throw new ObjectValidationException("认证配置响应无效");
		}
	}

	public static class Quota extends BaseModel{
		public int limit, used, remaining;
		public boolean sponsorActive;
		@Override public void postprocess() throws ObjectValidationException{
			if(limit<0 || used<0 || used>limit || remaining!=limit-used) throw new ObjectValidationException("认证额度响应无效");
		}
	}

	public static class Application extends BaseModel{
		public String id, status, decisionNote;
		public Long submittedAt, rejectionAcknowledgedAt, createdAt, updatedAt;
		public transient Status parsedStatus;
		@Override public void postprocess() throws ObjectValidationException{
			if(!uuid(id)) throw new ObjectValidationException("认证申请响应无效");
			parsedStatus=Status.parse(status);
			if(parsedStatus==Status.REJECTED && (decisionNote==null || decisionNote.isBlank())) throw new ObjectValidationException("驳回原因缺失");
		}
	}

	/** Private owner view returned by GET /applications/:id. */
	public static class ApplicationDetail extends Application{
		public long userId;
		public String captureSessionId, qq, declarationVersion;
		public boolean adultDeclaration;
		public Long declaredAt, claimedBy, claimedAt, decidedBy, decidedAt, cancelledAt;
		public List<Evidence> evidence;
		@Override public void postprocess() throws ObjectValidationException{
			super.postprocess();
			if(userId<=0 || !uuid(captureSessionId) || qq==null || !qq.matches("\\d{5,20}") || !adultDeclaration || declarationVersion==null || declarationVersion.isBlank() || declaredAt==null || declaredAt<=0 || evidence==null)
				throw new ObjectValidationException("认证申请详情响应无效");
			for(Evidence item:evidence){ if(item==null) throw new ObjectValidationException("认证照片响应无效"); item.postprocess(); }
		}
		public Evidence evidence(String kind){
			if(evidence==null) return null;
			for(Evidence item:evidence) if(item!=null && kind.equals(item.kind)) return item;
			return null;
		}
	}

	public static class Evidence extends BaseModel{
		public String id, kind, mimeType, status;
		public long declaredSize;
		public Long verifiedSize, completedAt;
		@Override public void postprocess() throws ObjectValidationException{
			if(!uuid(id) || !Set.of("capture_photo", "supporting_photo").contains(kind) || !Set.of("image/jpeg", "image/png", "image/webp").contains(mimeType)
					|| declaredSize<=0 || !Set.of("pending", "verifying", "ready").contains(status)) throw new ObjectValidationException("认证照片响应无效");
			if("ready".equals(status) && (verifiedSize==null || verifiedSize<=0 || completedAt==null || completedAt<=0)) throw new ObjectValidationException("认证照片响应无效");
		}
	}

	public static class State extends BaseModel{
		public Config config;
		public Quota quota;
		public Application application;
		@Override public void postprocess() throws ObjectValidationException{
			if(config==null || quota==null) throw new ObjectValidationException("认证状态响应无效");
			config.postprocess(); quota.postprocess(); if(application!=null) application.postprocess();
		}
		public Status status(){ return application==null ? Status.NOT_STARTED : application.parsedStatus; }
		public boolean canStart(){
			Status status=status();
			return config.enabled && quota.remaining>0 && Set.of(Status.NOT_STARTED, Status.CANCELLED, Status.REJECTED).contains(status);
		}
	}

	public static class CaptureSession extends BaseModel{
		public String id, status, nonce, paperShape, foldInstruction, placementInstruction, randomText;
		public int instructionsVersion;
		public long expiresAt;
		@Override public void postprocess() throws ObjectValidationException{
			if(!uuid(id) || !Set.of("active", "completed", "expired", "cancelled").contains(status) || nonce==null || nonce.isBlank() || expiresAt<=0) throw new ObjectValidationException("拍摄会话响应无效");
			if("active".equals(status) && (paperShape==null || foldInstruction==null || placementInstruction==null || randomText==null)) throw new ObjectValidationException("本次认证要求不完整");
			if("active".equals(status) && expiresAt<=System.currentTimeMillis()/1000) throw new ObjectValidationException("拍摄会话已过期");
		}
		public String requirement(String userId, String qq){
			return "裁剪出"+paperShape+"纸条，"+foldInstruction+"；在纸条上依次写用户 ID "+userId+"、QQ "+qq+"、认证文本“"+randomText+"”；"+placementInstruction+"，并拍下同时包含完整穿着的纸尿裤和纸条的照片。";
		}
	}

	public static class ApplicationResult extends BaseModel{
		public String id, status;
		@Override public void postprocess() throws ObjectValidationException{ if(!uuid(id) || status==null) throw new ObjectValidationException("认证申请响应无效"); }
	}

	public static class CancelResult extends BaseModel{
		public String id, status;
		public Long cancelledAt;
		@Override public void postprocess() throws ObjectValidationException{
			if(!uuid(id) || !"cancelled".equals(status) || cancelledAt==null || cancelledAt<=0) throw new ObjectValidationException("取消认证申请响应无效");
		}
	}

	public static class UploadAuthorization extends BaseModel{
		private static final Map<String, String> HEADER_NAMES=Map.of(
				"content-length", "Content-Length",
				"content-md5", "Content-MD5",
				"content-type", "Content-Type",
				"authorization", "Authorization",
				"x-cos-acl", "x-cos-acl",
				"x-cos-forbid-overwrite", "x-cos-forbid-overwrite",
				"x-cos-meta-sha256", "x-cos-meta-sha256"
		);
		public String evidenceId, status, uploadUrl;
		public Map<String, String> requiredHeaders;
		public long expiresAt;
		public boolean alreadyUploaded;
		@Override public void postprocess() throws ObjectValidationException{
			if(!uuid(evidenceId) || status==null) throw new ObjectValidationException("上传授权响应无效");
			if(alreadyUploaded || "ready".equals(status)){
				if(!alreadyUploaded || !"ready".equals(status)) throw new ObjectValidationException("上传授权响应无效");
				return;
			}
			if(!"pending".equals(status) || !validHttpsUrl(uploadUrl) || expiresAt<=System.currentTimeMillis()/1000) throw new ObjectValidationException("上传授权响应无效");
			normalizeRequiredHeaders();
		}
		public void normalizeRequiredHeaders() throws ObjectValidationException{
			if(requiredHeaders==null) throw new ObjectValidationException("上传授权请求头不完整");
			LinkedHashMap<String, String> normalized=new LinkedHashMap<>();
			for(Map.Entry<String, String> header:requiredHeaders.entrySet()){
				String name=header.getKey(), value=header.getValue();
				String canonical=name==null ? null : HEADER_NAMES.get(name.toLowerCase(Locale.US));
				if(canonical==null || value==null || value.isBlank() || normalized.put(canonical, value)!=null)
					throw new ObjectValidationException("上传授权包含重复或不允许的请求头");
			}
			if(normalized.size()!=HEADER_NAMES.size() || !normalized.keySet().containsAll(HEADER_NAMES.values()) || !"private".equals(normalized.get("x-cos-acl"))
					|| !"true".equals(normalized.get("x-cos-forbid-overwrite"))) throw new ObjectValidationException("上传授权请求头不完整");
			requiredHeaders=normalized;
		}
		private static boolean validHttpsUrl(String value){
			try{
				java.net.URI uri=value==null ? null : new java.net.URI(value);
				return uri!=null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null && uri.getFragment()==null;
			}catch(java.net.URISyntaxException ignored){ return false; }
		}
	}

	public static class EvidenceComplete extends BaseModel{
		public String id, status;
		public long verifiedSize;
		@Override public void postprocess() throws ObjectValidationException{ if(!uuid(id) || !"ready".equals(status) || verifiedSize<=0) throw new ObjectValidationException("照片校验响应无效"); }
	}

	public static class SubmitResult extends BaseModel{
		public String id, status;
		public Long submittedAt;
		public boolean replayed;
		@Override public void postprocess() throws ObjectValidationException{ if(!uuid(id) || (!"submitted".equals(status) && !"reviewing".equals(status))) throw new ObjectValidationException("提交响应无效"); }
	}

	public static class CertificateEnvelope extends BaseModel{
		public Certificate certificate;
		@Override public void postprocess() throws ObjectValidationException{ if(certificate!=null) certificate.postprocess(); }
	}

	public static class Certificate extends BaseModel{
		public String id, status, verificationToken, verifyPath;
		public long issuedAt;
		public int generation;
		@Override public void postprocess() throws ObjectValidationException{
			if(!uuid(id) || !"active".equals(status) || !VerificationLink.isValidToken(verificationToken) || verifyPath==null || !verifyPath.equals("/api/v1/baby-verification/verify/"+verificationToken))
				throw new ObjectValidationException("认证证书响应无效");
		}
		public String publicUrl(){ return "https://abdl-space.top/c/"+verificationToken; }
	}

	public static class VerifyResult extends BaseModel{
		public boolean valid, superseded;
		public String status, username;
		public Long issuedAt;
		public int generation;
		@Override public void postprocess() throws ObjectValidationException{
			if(!Set.of("active", "superseded", "revoked", "unknown").contains(status) || valid!="active".equals(status)) throw new ObjectValidationException("验真响应无效");
			if(valid && (username==null || username.isBlank() || issuedAt==null)) throw new ObjectValidationException("验真响应无效");
		}
	}

	private static boolean uuid(String value){ return value!=null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"); }
}
