package org.joinmastodon.android.albums;

import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.CosPreviewRequestBody;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.ResizedImageRequestBody;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.albums.AlbumModels.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;

/**
 * A cancelable background batch. Immutable staged bytes and the operation UUID survive process
 * death; retries never recompress, overwrite an object, or create another publication.
 * No Activity is held and every listener delivery is on the main thread.
 */
public final class AlbumUploader{
	public static final int MAX_PHOTOS=20;
	public static final long SOURCE_LIMIT=20L*1024*1024, HD_SOURCE_LIMIT=100L*1024*1024;
	private static final long PREVIEW_LIMIT=2L*1024*1024, HD_LIMIT=10L*1024*1024;
	private static final java.util.concurrent.ConcurrentHashMap<String, AlbumUploader> active=new java.util.concurrent.ConcurrentHashMap<>();
	private static final MediaType JSON=MediaType.get("application/json; charset=utf-8");
	private static final com.google.gson.Gson UPLOAD_JSON=MastodonAPIController.gsonWithoutDeserializer.newBuilder().serializeNulls().create();
	public enum Stage{ READY, PREPARING, AUTHORIZING, UPLOADING, PUBLISHING, COMPLETE, FAILED, CANCELED }
	public interface Listener{
		void onProgress(Stage stage, int percent, long transferred, long total);
		void onSuccess(PublishResponse result);
		void onError(Failure error);
		void onCanceled();
	}
	public static final class Failure extends IOException{
		public final String code;
		public final int status;
		public final boolean retryable, outcomeUnknown;
		public Failure(String message, String code, int status, boolean retryable, boolean outcomeUnknown){
			super(message); this.code=code; this.status=status; this.retryable=retryable; this.outcomeUnknown=outcomeUnknown;
		}
	}
	private final Context context;
	private final String accountId;
	private final AccountSession session;
	private final File directory, manifestFile;
	private final OkHttpClient client;
	private final Handler main=new Handler(Looper.getMainLooper());
	private Manifest manifest;
	private volatile Listener listener;
	private volatile boolean canceled, running;
	private volatile Thread worker;
	private volatile Call activeCall;
	private Stage stage=Stage.READY;
	private int percent;
	private long transferred, total;
	private Failure failure;

	public AlbumUploader(String accountId, String albumId, List<Uri> sources, String description, Long capturedAt, String quality) throws IOException{
		this.context=MastodonApp.context.getApplicationContext();
		this.accountId=accountId;
		this.session=requireSession(accountId);
		if(albumId==null || !safeId(albumId) || sources==null || sources.isEmpty() || sources.size()>MAX_PHOTOS
				|| description==null || description.codePointCount(0, description.length())>3000 || !("hd".equals(quality) || "original".equals(quality))
				|| (capturedAt!=null && capturedAt<0)) throw new IOException("相册上传参数无效");
		manifest=new Manifest(); manifest.accountId=accountId; manifest.albumId=albumId; manifest.description=description;
		manifest.capturedAt=capturedAt; manifest.quality=quality; manifest.operationId=UUID.randomUUID().toString();
		for(Uri uri:sources){
			if(uri==null || !("content".equals(uri.getScheme()) || "file".equals(uri.getScheme()))) throw new IOException("请选择本地图片");
			manifest.sources.add(uri.toString());
		}
		directory=new File(context.getNoBackupFilesDir(), "album_uploads/"+manifest.operationId); manifestFile=new File(directory, "manifest.json");
		if(!directory.mkdirs()) throw new IOException("无法保存相册上传任务");
		client=newClient(); save();
	}
	private AlbumUploader(String accountId, String operationId) throws IOException{
		context=MastodonApp.context.getApplicationContext(); this.accountId=accountId; session=requireSession(accountId);
		try{ UUID.fromString(operationId); }catch(RuntimeException invalid){ throw new IOException("上传任务编号无效"); }
		directory=new File(context.getNoBackupFilesDir(), "album_uploads/"+operationId); manifestFile=new File(directory, "manifest.json");
		try(InputStream input=new FileInputStream(manifestFile)){
			manifest=MastodonAPIController.gson.fromJson(new String(readBounded(input, 256*1024), StandardCharsets.UTF_8), Manifest.class);
		}catch(RuntimeException invalid){ throw new IOException("无法读取上传任务", invalid); }
		if(manifest==null || !accountId.equals(manifest.accountId) || !operationId.equals(manifest.operationId) || !safeId(manifest.albumId)
				|| manifest.sources==null || manifest.sources.isEmpty() || manifest.sources.size()>MAX_PHOTOS || manifest.photos==null
				|| manifest.description==null || manifest.description.codePointCount(0, manifest.description.length())>3000
				|| !("hd".equals(manifest.quality) || "original".equals(manifest.quality))) throw new IOException("上传任务不可用");
		client=newClient(); percent=manifest.progress;
		if(manifest.published!=null) stage=Stage.COMPLETE;
	}
	public static AlbumUploader resume(String accountId, String operationId) throws IOException{
		AlbumUploader existing=active.get(operationId);
		if(existing!=null){ if(!accountId.equals(existing.accountId)) throw new IOException("上传任务属于另一账户"); return existing; }
		return new AlbumUploader(accountId, operationId);
	}
	public String getOperationId(){ return manifest.operationId; }
	public synchronized boolean isRunning(){ return running; }
	public synchronized boolean isComplete(){ return stage==Stage.COMPLETE; }
	public synchronized void setListener(Listener next){ listener=next; if(next!=null) dispatch(); }
	public synchronized void start(){
		if(running) return;
		if(manifest.published!=null){ stage=Stage.COMPLETE; percent=100; dispatch(); return; }
		AlbumUploader previous=active.putIfAbsent(manifest.operationId, this);
		if(previous!=null && previous!=this) throw new IllegalStateException("This upload task is already running");
		canceled=false; failure=null; running=true;
		worker=new Thread(this::run, "AlbumUpload"); worker.setDaemon(true); worker.start();
	}
	public synchronized void cancel(){
		canceled=true;
		if(activeCall!=null) activeCall.cancel();
		if(worker!=null) worker.interrupt();
	}
	/** Discard only a stopped local task. The server's unpublished reservation expires independently. */
	public synchronized void discard(){
		if(running) throw new IllegalStateException("Cancel the upload before discarding it");
		deleteDirectory(directory);
	}
	private static AccountSession requireSession(String id) throws IOException{
		AccountSession session=AccountSessionManager.getInstance().tryGetAccount(id);
		if(session==null || session.self==null || session.token==null || session.token.accessToken==null) throw new IOException("登录状态已变更，请重新登录");
		HttpUrl origin=HttpUrl.parse("https://"+session.domain);
		if(origin==null || !origin.username().isEmpty() || !origin.password().isEmpty() || !"/".equals(origin.encodedPath()) || origin.query()!=null || origin.fragment()!=null
				|| !id.equals(AccountSessionManager.getInstance().getLastActiveAccountID()))
			throw new IOException("登录服务器地址无效");
		return session;
	}
	private OkHttpClient newClient(){
		return MastodonAPIController.getHttpClient().newBuilder().cache(null).followRedirects(false).followSslRedirects(false)
				.retryOnConnectionFailure(false).connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
				.writeTimeout(60, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES).build();
	}
	private void run(){
		try{
			check();
			if(manifest.batchId!=null){
				try{
					PublishResponse published=api("POST", "/batches/"+manifest.batchId+"/publish", Map.of(), PublishResponse.class);
					finishPublication(published); return;
				}catch(Failure error){ if(!"album_batch_incomplete".equals(error.code) && !"batch_incomplete".equals(error.code)) throw error; }
			}
			if(!manifest.ready){
				StorageQuota quota=api("GET", "/quota", null, StorageQuota.class);
				if("original".equals(manifest.quality) && (!quota.sponsorActive || !quota.originalUploadAllowed))
					throw new Failure("无损原图仅限有效赞助者上传，请刷新权益后重试", "sponsor_required", 403, true, false);
				prepare();
			}
			validateFiles();
			notifyProgress(Stage.AUTHORIZING, 12, 0, manifest.totalBytes);
			BatchAuthorization authorization=api("POST", "/"+manifest.albumId+"/batches/authorize", authorizationBody(), BatchAuthorization.class);
			if(authorization.published){
				if(!safeId(authorization.batchId) || !manifest.albumId.equals(authorization.albumId)) throw new IOException("发布批次响应无效");
				manifest.batchId=authorization.batchId;
				PublishResponse published=new PublishResponse(); published.batchId=authorization.batchId; published.albumId=authorization.albumId; published.postId=authorization.postId;
				finishPublication(published); return;
			}
			validateBatch(authorization);
			if(manifest.batchId!=null && !manifest.batchId.equals(authorization.batchId)) throw new Failure("服务器返回了不同的上传批次", "invalid_response", 0, false, true);
			manifest.batchId=authorization.batchId; save();
			long done=0;
			for(PreparedPhoto photo:manifest.photos){
				for(Variant variant:photo.variants){
					check();
					UploadAuthorization auth=findAuthorization(authorization, photo.clientId, variant.kind);
					if(variant.uploadId!=null && !variant.uploadId.equals(auth.uploadId)) throw new Failure("上传凭证已变更", "invalid_response", 0, false, true);
					variant.uploadId=auth.uploadId; save();
					if(!variant.complete){
						// A canceled/failed PUT may have succeeded. Verify on the server BEFORE another PUT.
						if(variant.putAttempted) variant.complete=tryComplete(auth.uploadId);
						if(!variant.complete){
							validateUploadAuthorization(auth, variant.mimeType, variant.size, variant.contentMd5, System.currentTimeMillis()/1000);
							variant.putAttempted=true; save();
							put(auth, variant, done);
							completeOrThrow(auth.uploadId);
							variant.complete=true;
						}
						save();
					}
					done+=variant.size; notifyBytes(done);
				}
			}
			check(); notifyProgress(Stage.PUBLISHING, 96, manifest.totalBytes, manifest.totalBytes);
			PublishResponse result=api("POST", "/batches/"+manifest.batchId+"/publish", Map.of(), PublishResponse.class);
			finishPublication(result);
		}catch(Exception error){
			if(!manifest.ready){
				File[] partial=directory.listFiles(); if(partial!=null) for(File file:partial) if(!file.equals(manifestFile) && file.isFile()) file.delete();
				manifest.photos.clear(); manifest.totalBytes=0;
			}
			synchronized(this){
					if(canceled || error instanceof Canceled){ stage=Stage.CANCELED; }
				else{
					stage=Stage.FAILED;
					failure=error instanceof Failure f ? f : new Failure(error.getMessage()==null ? "相册上传失败，请重试" : error.getMessage(), "transport_failure", 0, true, manifest.batchId!=null);
				}
			}
		}finally{
			synchronized(this){ activeCall=null; worker=null; active.remove(manifest.operationId, this); running=false; }
			dispatch();
		}
	}
	private void finishPublication(PublishResponse result) throws IOException{
		if(!manifest.albumId.equals(result.albumId) || !manifest.batchId.equals(result.batchId)) throw new Failure("发布结果不完整，请重试确认", "invalid_response", 0, true, true);
		manifest.published=result; manifest.progress=100; save();
		// Keep a receipt until UI acknowledgement; never retain completed private originals.
		File[] children=directory.listFiles(); if(children!=null) for(File child:children) if(!child.equals(manifestFile) && child.isFile()) child.delete();
		synchronized(this){ running=false; stage=Stage.COMPLETE; percent=100; transferred=total=manifest.totalBytes; }
		dispatch();
	}
	public String getBatchId(){ return manifest.batchId; }
	public BundleState getDraftState(){ return new BundleState(manifest.albumId, manifest.description, manifest.capturedAt, manifest.quality, new ArrayList<>(manifest.sources)); }
	public static final class BundleState{
		public final String albumId, description, quality;
		public final Long capturedAt;
		public final List<String> sources;
		BundleState(String albumId, String description, Long capturedAt, String quality, List<String> sources){ this.albumId=albumId; this.description=description; this.capturedAt=capturedAt; this.quality=quality; this.sources=sources; }
	}
	/** Called only in a background thread after the UI has stopped and explicitly discarded a task. */
	public void cancelReservation() throws IOException{
		if(isRunning()) throw new IOException("请先暂停上传");
		if(manifest.batchId==null || manifest.published!=null){ discard(); return; }
		canceled=false;
		try{
			CancelResponse result=api("POST", "/batches/"+manifest.batchId+"/cancel", Map.of(), CancelResponse.class);
			if(!result.cancelled) throw new IOException("未能确认上传任务已取消"); discard();
		}catch(Failure error){
			if(error.status==409){
				PublishResponse result=api("POST", "/batches/"+manifest.batchId+"/publish", Map.of(), PublishResponse.class); finishPublication(result); return;
			}
			throw error;
		}
	}
	private void prepare() throws IOException{
		notifyProgress(Stage.PREPARING, 0, 0, 0);
		// No authorization is issued until EVERY immutable variant has been safely staged.
		for(File child:directory.listFiles()==null ? new File[0] : directory.listFiles()) if(!child.equals(manifestFile)) child.delete();
		manifest.photos.clear(); manifest.totalBytes=0;
		Instance instance=session.getInstanceInfo();
		int maxImageSize=instance!=null && instance.configuration!=null && instance.configuration.mediaAttachments!=null
				&& instance.configuration.mediaAttachments.imageMatrixLimit>0 ? instance.configuration.mediaAttachments.imageMatrixLimit : 2_073_600;
		for(int index=0; index<manifest.sources.size(); index++){
			check(); Uri source=Uri.parse(manifest.sources.get(index));
			long reported=reportedSize(source);
			if(reported>=0 && !sourceSizeAllowed(reported, manifest.quality)) throw new Failure("original".equals(manifest.quality) ? "无损原图源文件必须小于 20 MiB" : "高清源文件不能超过 100 MiB", "source_too_large", 0, false, false);
			File raw=new File(directory, index+"_source.tmp");
			try(InputStream input=context.getContentResolver().openInputStream(source); FileOutputStream output=new FileOutputStream(raw)){
				if(input==null) throw new IOException("无法读取所选图片，请重新授予访问权限");
				byte[] buffer=new byte[32*1024]; int read; long size=0;
				while((read=input.read(buffer))!=-1){ check(); size+=read; if(!sourceSizeAllowed(size, manifest.quality)) throw new Failure("original".equals(manifest.quality) ? "无损原图源文件必须小于 20 MiB" : "高清源文件不能超过 100 MiB", "source_too_large", 0, false, false); output.write(buffer, 0, read); }
				if(size<=0 || (reported>=0 && reported!=size)) throw new IOException("图片大小已变更，请重新选择");
				output.getFD().sync();
			}
			BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true; BitmapFactory.decodeFile(raw.getPath(), bounds);
			if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outMimeType==null || !bounds.outMimeType.startsWith("image/")) throw new Failure("所选文件不是可读取的图片", "invalid_image", 0, false, false);
			boolean supportedRaw=Set.of("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif").contains(bounds.outMimeType);
			if("original".equals(manifest.quality) && !supportedRaw) throw new Failure("此格式不支持无损原图，请使用高清上传", "original_mime_unsupported", 0, false, false);
			String extension=switch(bounds.outMimeType){ case "image/png" -> ".png"; case "image/webp" -> ".webp"; case "image/gif" -> ".gif"; case "image/heif", "image/heic" -> ".heic"; case "image/avif" -> ".avif"; default -> ".jpg"; };
			File original=new File(directory, index+"_source"+extension);
			Files.move(raw.toPath(), original.toPath(), StandardCopyOption.REPLACE_EXISTING);
			Uri stagedUri=Uri.fromFile(original);
			PreparedPhoto photo=new PreparedPhoto(); photo.clientId=clientIdFor(manifest.operationId, index);
			ResizedImageRequestBody hd=null; CosPreviewRequestBody preview=null;
			try{
				long pixels=(long)bounds.outWidth*bounds.outHeight;
				boolean supportedHd=Set.of("image/jpeg", "image/png", "image/webp", "image/gif").contains(bounds.outMimeType);
				int hdMatrix=supportedHd ? maxImageSize : (int)Math.max(1, Math.min((long)maxImageSize, pixels-1));
				hd=new ResizedImageRequestBody(stagedUri, hdMatrix, null, this::isCanceled){
					@Override protected boolean needResize(int width, int height){ return (long)width*height>hdMatrix || !supportedHd; }
				};
				photo.width=hd.getWidth(); photo.height=hd.getHeight();
				if(photo.width<=0 || photo.height<=0) throw new IOException("无法获取图片尺寸");
				preview=new CosPreviewRequestBody(stagedUri, null, this::isCanceled);
				if(Math.max(preview.getWidth(), preview.getHeight())>540) throw new IOException("预览图片尺寸超出限制");
				Variant previewVariant=stageBody(preview, index+"_preview", "preview", PREVIEW_LIMIT);
				previewVariant.width=preview.getWidth(); previewVariant.height=preview.getHeight(); photo.variants.add(previewVariant);
				photo.variants.add(stageBody(hd, index+"_hd", "hd", HD_LIMIT));
				if("original".equals(manifest.quality)) photo.variants.add(variant(original, "original", bounds.outMimeType));
				else original.delete();
			}finally{ if(hd!=null) hd.cleanup(); if(preview!=null) preview.close(); }
			manifest.photos.add(photo); for(Variant variant:photo.variants) manifest.totalBytes+=variant.size;
			notifyProgress(Stage.PREPARING, (index+1)*10/manifest.sources.size(), 0, manifest.totalBytes);
		}
		manifest.ready=true; save();
	}
	private long reportedSize(Uri uri){
		if("file".equals(uri.getScheme())) return new File(uri.getPath()).length();
		try(Cursor cursor=context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)){
			if(cursor!=null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
		}catch(RuntimeException ignored){}
		return -1;
	}
	private Variant stageBody(RequestBody body, String name, String kind, long maximum) throws IOException{
		File destination=file(name);
		try(BufferedSink sink=Okio.buffer(new ForwardingSink(Okio.sink(destination)){
			long written;
			@Override public void write(Buffer source, long count) throws IOException{ check(); written+=count; if(written>maximum) throw new Failure("压缩图片超出上传限制，请选择较小的图片", "variant_too_large", 0, false, false); super.write(source, count); }
		})){ body.writeTo(sink); }
		return variant(destination, kind, body.contentType().toString());
	}
	private Variant variant(File source, String kind, String mime) throws IOException{
		Variant variant=new Variant(); variant.file=source.getName(); variant.kind=kind; variant.mimeType=mime; variant.size=source.length();
		if(variant.size<=0 || ("original".equals(kind) ? !sourceSizeAllowed(variant.size) : variant.size>("preview".equals(kind) ? PREVIEW_LIMIT : HD_LIMIT))) throw new IOException("图片大小不符合相册限制");
		variant.contentMd5=digest(source); return variant;
	}
	private void validateFiles() throws IOException{
		if(manifest.photos.size()!=manifest.sources.size()) throw new IOException("本地图片不完整，请重新选择");
		long bytes=0;
		for(int i=0; i<manifest.photos.size(); i++){
			PreparedPhoto photo=manifest.photos.get(i);
			if(photo==null || !clientIdFor(manifest.operationId, i).equals(photo.clientId) || photo.width<=0 || photo.height<=0 || photo.variants==null
					|| photo.variants.size()!=("original".equals(manifest.quality) ? 3 : 2)) throw new IOException("本地照片信息无效");
			Set<String> kinds=new HashSet<>();
			for(Variant variant:photo.variants){
				if(variant==null || !Set.of("preview", "hd", "original").contains(variant.kind) || !kinds.add(variant.kind)) throw new IOException("本地图片版本无效");
				File local=file(variant.file);
				if(!local.isFile() || local.length()!=variant.size || !digest(local).equals(variant.contentMd5)) throw new IOException("本地上传图片已变更，请重新选择");
				bytes+=variant.size;
			}
		}
		if(bytes!=manifest.totalBytes) throw new IOException("本地图片总大小无效");
	}
	private Map<String, Object> authorizationBody(){
		Map<String, Object> result=new LinkedHashMap<>(); result.put("operation_id", manifest.operationId); result.put("description", manifest.description);
		result.put("captured_at", manifest.capturedAt); result.put("quality", manifest.quality);
		List<Object> photos=new ArrayList<>();
		for(PreparedPhoto photo:manifest.photos){
			List<Object> variants=new ArrayList<>();
			for(Variant variant:photo.variants){
				Map<String, Object> data=new LinkedHashMap<>(); data.put("kind", variant.kind); data.put("mime_type", variant.mimeType); data.put("size", variant.size); data.put("content_md5", variant.contentMd5);
				if("preview".equals(variant.kind)){ data.put("width", variant.width); data.put("height", variant.height); } variants.add(data);
			}
			photos.add(Map.of("client_id", photo.clientId, "width", photo.width, "height", photo.height, "variants", variants));
		}
		result.put("photos", photos); return result;
	}
	private void validateBatch(BatchAuthorization result) throws IOException{
		int expected="original".equals(manifest.quality) ? 3 : 2;
		if(result==null || !safeId(result.batchId) || result.uploads==null || result.uploads.size()!=manifest.photos.size()*expected) throw new IOException("上传授权不完整");
		Set<String> ids=new HashSet<>(), keys=new HashSet<>(), urls=new HashSet<>(); Map<String, String> photoIds=new HashMap<>(); String host=null;
		for(UploadAuthorization upload:result.uploads){
			if(upload==null || !safeId(upload.uploadId) || !safeId(upload.photoId) || !ids.add(upload.uploadId) || !keys.add(upload.clientId+":"+upload.kind)) throw new IOException("上传授权重复或无效");
			PreparedPhoto photo=manifest.photos.stream().filter(p->p.clientId.equals(upload.clientId)).findFirst().orElse(null);
			Variant variant=photo==null ? null : photo.variants.stream().filter(v->v.kind.equals(upload.kind)).findFirst().orElse(null);
			if(variant==null) throw new IOException("上传授权与所选图片不匹配");
			String previousPhoto=photoIds.putIfAbsent(upload.clientId, upload.photoId);
			if(previousPhoto!=null && !previousPhoto.equals(upload.photoId)) throw new IOException("同一图片的授权标识不一致");
			// Completed objects only need a stable upload id, not a still-live PUT signature.
			if(!variant.complete){
				URI location=validateCosUrl(upload.uploadUrl); String current=location.getHost();
				if(!location.getPath().startsWith("/albums/"+session.self.id+"/") || !urls.add(upload.uploadUrl)) throw new IOException("上传对象不属于当前账户或地址重复");
				if(host!=null && !host.equals(current)) throw new IOException("批次存储地址不一致"); host=current;
			}
		}
	}
	private static UploadAuthorization findAuthorization(BatchAuthorization response, String clientId, String kind) throws IOException{
		for(UploadAuthorization upload:response.uploads) if(clientId.equals(upload.clientId) && kind.equals(upload.kind)) return upload;
		throw new IOException("缺少图片上传授权");
	}
	/** Definitive "object absent" answers: the batch may safely issue another PUT. */
	private static final Set<String> OBJECT_MISSING_CODES=Set.of("upload_object_missing", "upload_incomplete", "object_not_found", "upload_not_complete", "object_missing");
	/** The server could not run verification: the object may already exist, so only complete-first retries are safe. */
	private static final Set<String> VERIFICATION_UNAVAILABLE_CODES=Set.of("upload_verification_unavailable", "private_storage_unavailable", "albums_unavailable");
	private boolean tryComplete(String uploadId) throws IOException{
		try{ return api("POST", "/uploads/"+uploadId+"/complete", Map.of(), CompleteResponse.class).complete; }
		catch(Failure error){
			if(OBJECT_MISSING_CODES.contains(error.code)) return false;
			throw normalizeVerificationFailure(error); // Access/entitlement/transport failures must NEVER trigger another PUT.
		}
	}
	/** A refused verification is a known "not complete" outcome; resume retries the same complete-first flow. */
	private static Failure normalizeVerificationFailure(Failure error){
		if(VERIFICATION_UNAVAILABLE_CODES.contains(error.code) || error.status==502 || error.status==503 || error.status==429)
			return new Failure(error.getMessage(), error.code, error.status, true, false);
		return error;
	}
	private void completeOrThrow(String uploadId) throws IOException{
		try{
			if(!api("POST", "/uploads/"+uploadId+"/complete", Map.of(), CompleteResponse.class).complete)
				throw new Failure("图片尚未通过存储校验，请重试", "upload_incomplete", 409, true, true);
		}catch(Failure error){
			if(OBJECT_MISSING_CODES.contains(error.code)) throw new Failure(error.getMessage(), error.code, error.status, true, true);
			throw normalizeVerificationFailure(error);
		}
	}
	private void put(UploadAuthorization auth, Variant variant, long alreadyTransferred) throws IOException{
		RequestBody body=new RequestBody(){
			@Override public MediaType contentType(){ return MediaType.get(variant.mimeType); }
			@Override public long contentLength(){ return variant.size; }
			@Override public void writeTo(BufferedSink sink) throws IOException{
				try(InputStream input=new FileInputStream(file(variant.file))){
					byte[] bytes=new byte[32*1024]; int count; long written=0;
					while((count=input.read(bytes))!=-1){ check(); written+=count; if(written>variant.size) throw new IOException("本地图片大小已变更"); sink.write(bytes, 0, count); notifyBytes(alreadyTransferred+written); }
					if(written!=variant.size) throw new IOException("本地图片读取不完整");
				}
			}
		};
		Request.Builder builder=new Request.Builder().url(auth.uploadUrl).put(body);
		for(Map.Entry<String, String> header:auth.requiredHeaders.entrySet()) builder.header(header.getKey(), header.getValue());
		try(Response response=execute(builder.build(), false)){
			if(!response.isSuccessful()){
				// COS may have accepted an earlier PUT; completion proves the exact size and digest.
				if((response.code()==409 || response.code()==412) && tryComplete(auth.uploadId)) return;
				throw new Failure("私有存储上传失败（HTTP "+response.code()+"），请重试", "cos_put_failed", response.code(), true, true);
			}
		}
	}
	private <T> T api(String method, String path, Object body, Class<T> type) throws IOException{
		check();
		Request.Builder builder=new Request.Builder().url("https://"+session.domain+"/api/v1/albums"+path)
				.header("Authorization", "Bearer "+session.token.accessToken).header("Cache-Control", "no-store")
				.header("User-Agent", "MastodonAndroid/"+BuildConfig.VERSION_NAME).header("X-App-Version-Code", String.valueOf(BuildConfig.VERSION_CODE));
		builder.method(method, body==null ? null : RequestBody.create(JSON, UPLOAD_JSON.toJson(body)));
		try(Response response=execute(builder.build(), true)){
			if(response.body()==null) throw new Failure("服务器返回了空响应，请重试", "invalid_response", response.code(), true, !"GET".equals(method));
			String text=new String(readBounded(response.body().byteStream(), 1024*1024), StandardCharsets.UTF_8);
			if(!response.isSuccessful()){
				String code="http_error", message="相册服务请求失败，请重试";
				try{ JsonObject error=JsonParser.parseString(text).getAsJsonObject(); if(error.has("code")) code=error.get("code").getAsString(); if(error.has("error")) message=error.get("error").getAsString(); }catch(RuntimeException ignored){}
				boolean retryable=response.code()==408 || response.code()==425 || response.code()==429 || response.code()>=500;
				throw new Failure(message, code, response.code(), retryable || Set.of("sponsor_required", "album_sponsor_required", "quota_exceeded", "album_capacity_exceeded", "upload_incomplete", "album_batch_incomplete").contains(code), !"GET".equals(method) && retryable);
			}
			try{ T result=MastodonAPIController.gson.fromJson(text, type); if(result==null) throw new IllegalArgumentException(); return result; }
			catch(RuntimeException invalid){ throw new Failure("相册服务响应无效，请重试确认", "invalid_response", response.code(), true, !"GET".equals(method)); }
		}
	}
	private Response execute(Request request, boolean api) throws IOException{
		check(); Call call=client.newCall(request); if(api) call.timeout().timeout(30, TimeUnit.SECONDS); activeCall=call;
		if(isCanceled()) call.cancel(); Response response=call.execute();
		try{ check(); return response; }catch(IOException canceled){ response.close(); throw canceled; }
	}
	/** Strict HTTPS COS bucket URL, never a CDN, arbitrary hostname, credential, query or redirect. */
	public static URI validateCosUrl(String value) throws IOException{
		try{
			URI uri=value==null ? null : new URI(value);
			if(uri==null || !"https".equals(uri.getScheme()) || uri.getHost()==null
					|| !uri.getHost().matches("[a-z0-9][a-z0-9-]*-[0-9]+\\.cos\\.[a-z0-9-]+\\.myqcloud\\.com")
					|| (uri.getPort()!=-1 && uri.getPort()!=443) || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
					|| uri.getPath()==null || !uri.getPath().startsWith("/albums/") || uri.getPath().length()<=8 || !uri.normalize().getPath().equals(uri.getPath())
					|| uri.getRawPath().contains("%") || uri.getPath().contains("\\") || uri.getPath().contains("/../") || uri.getPath().contains("/./")
					|| uri.getPath().endsWith("/..") || uri.getPath().endsWith("/.") || uri.getPath().contains("//")) throw new IOException("私有存储地址不可信");
			return uri;
		}catch(URISyntaxException error){ throw new IOException("私有存储地址无效", error); }
	}
	public static void validateUploadAuthorization(UploadAuthorization auth, String mime, long size, String md5, long now) throws IOException{
		if(auth==null || auth.requiredHeaders==null || auth.expiresAt<=now || auth.expiresAt>now+3600) throw new IOException("上传凭证已过期，请重试");
		URI url=validateCosUrl(auth.uploadUrl);
		Map<String, String> headers=new HashMap<>();
		for(Map.Entry<String, String> header:auth.requiredHeaders.entrySet()){
			String key=header.getKey()==null ? "" : header.getKey().toLowerCase(Locale.US), value=header.getValue();
			if(value==null || value.contains("\r") || value.contains("\n") || headers.put(key, value)!=null
					|| !(Set.of("authorization", "host", "content-type", "content-length", "content-md5", "x-cos-acl", "x-cos-forbid-overwrite", "x-cos-security-token").contains(key) || key.startsWith("x-cos-meta-"))) throw new IOException("上传凭证含不支持的请求头");
		}
		if(!mime.equals(headers.get("content-type")) || !Long.toString(size).equals(headers.get("content-length")) || !md5.equals(headers.get("content-md5"))
				|| !"private".equals(headers.get("x-cos-acl")) || !"true".equals(headers.get("x-cos-forbid-overwrite"))
				|| (headers.containsKey("host") && !url.getHost().equals(headers.get("host")))) throw new IOException("上传凭证未绑定私有图片内容");
		String authorization=headers.get("authorization");
		if(authorization==null) throw new IOException("缺少存储签名");
		Map<String, String> parts=new HashMap<>();
		for(String part:authorization.split("&", -1)){ int equals=part.indexOf('='); if(equals<1 || parts.put(part.substring(0, equals), part.substring(equals+1))!=null) throw new IOException("存储签名无效"); }
		if(!"sha1".equals(parts.get("q-sign-algorithm")) || parts.get("q-ak")==null || parts.get("q-ak").isEmpty()
				|| !"".equals(parts.get("q-url-param-list")) || parts.get("q-signature")==null || !parts.get("q-signature").matches("[a-fA-F0-9]{40}")) throw new IOException("存储签名无效");
		String signed=parts.get("q-header-list"); Set<String> signedHeaders=new HashSet<>(List.of(signed==null ? new String[0] : signed.split(";")));
		if(!signedHeaders.containsAll(Set.of("host", "content-type", "content-length", "content-md5", "x-cos-acl", "x-cos-forbid-overwrite"))) throw new IOException("存储签名未保护私有内容与禁止覆盖设置");
		for(String header:headers.keySet()) if(!"authorization".equals(header) && !signedHeaders.contains(header)) throw new IOException("上传请求头未签名");
		try{
			String time=parts.get("q-sign-time"); if(time==null || !time.equals(parts.get("q-key-time"))) throw new NumberFormatException();
			String[] times=time.split(";", -1); if(times.length!=2) throw new NumberFormatException();
			long begin=Long.parseLong(times[0]), end=Long.parseLong(times[1]);
			if(begin>now+300 || end<=now || end>auth.expiresAt || end<=begin || end-begin>3600) throw new NumberFormatException();
		}catch(NumberFormatException invalid){ throw new IOException("存储签名时间无效或已过期"); }
	}
	public static boolean sourceSizeAllowed(long size){ return size>0 && size<SOURCE_LIMIT; }
	public static boolean sourceSizeAllowed(long size, String quality){ return "original".equals(quality) ? sourceSizeAllowed(size) : "hd".equals(quality) && size>0 && size<=HD_SOURCE_LIMIT; }
	public static String clientIdFor(String operation, int index){
		if(index<0 || index>=MAX_PHOTOS) throw new IllegalArgumentException("Photo index out of range");
		return String.format(Locale.US, "%04d", index);
	}
	private static boolean safeId(String value){ return value!=null && value.matches("[A-Za-z0-9_-]{1,128}"); }
	private File file(String name) throws IOException{
		if(name==null || !name.matches("[A-Za-z0-9_.-]{1,128}") || name.contains("..")) throw new IOException("本地图片路径无效");
		return new File(directory, name);
	}
	private String digest(File file) throws IOException{
		try{
			MessageDigest digest=MessageDigest.getInstance("MD5"); byte[] bytes=new byte[32*1024]; int read;
			try(InputStream input=new FileInputStream(file)){ while((read=input.read(bytes))!=-1){ check(); digest.update(bytes, 0, read); } }
			return Base64.getEncoder().encodeToString(digest.digest());
		}catch(NoSuchAlgorithmException impossible){ throw new AssertionError(impossible); }
	}
	private void save() throws IOException{
		File temporary=new File(directory, "manifest.new");
		try(FileOutputStream output=new FileOutputStream(temporary)){ output.write(MastodonAPIController.gsonWithoutDeserializer.toJson(manifest).getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
		Files.move(temporary.toPath(), manifestFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
	private static byte[] readBounded(InputStream input, int maximum) throws IOException{
		ByteArrayOutputStream output=new ByteArrayOutputStream(); byte[] bytes=new byte[8192]; int read;
		while((read=input.read(bytes))!=-1){ if(output.size()+read>maximum) throw new IOException("服务器响应超出限制"); output.write(bytes, 0, read); }
		return output.toByteArray();
	}
	private boolean isCanceled(){ return canceled || Thread.currentThread().isInterrupted(); }
	private void check() throws IOException{
		if(isCanceled()) throw new Canceled();
		if(!accountId.equals(AccountSessionManager.getInstance().getLastActiveAccountID()) || AccountSessionManager.getInstance().tryGetAccount(accountId)!=session) throw new Failure("登录状态已变更，上传已暂停", "session_changed", 401, false, true);
	}
	private void notifyBytes(long bytes){ notifyProgress(Stage.UPLOADING, 15+(int)(Math.min(bytes, manifest.totalBytes)*78/Math.max(1, manifest.totalBytes)), bytes, manifest.totalBytes); }
	private synchronized void notifyProgress(Stage next, int value, long bytes, long maximum){
		stage=next; percent=Math.max(percent, Math.min(99, value)); manifest.progress=percent;
		transferred=Math.max(transferred, bytes); total=maximum; dispatch();
	}
	private void dispatch(){
		Listener target=listener; if(target==null) return;
		Stage next; int value; long bytes, maximum; Failure error; PublishResponse result;
		synchronized(this){ next=stage; value=percent; bytes=transferred; maximum=total; error=failure; result=manifest.published; }
		main.post(()->{
			if(listener!=target) return;
			target.onProgress(next, value, bytes, maximum);
			if(next==Stage.COMPLETE && result!=null) target.onSuccess(result);
			else if(next==Stage.FAILED && error!=null) target.onError(error);
			else if(next==Stage.CANCELED) target.onCanceled();
		});
	}
	private static void deleteDirectory(File file){ File[] children=file.listFiles(); if(children!=null) for(File child:children){ if(child.isDirectory()) deleteDirectory(child); else child.delete(); } file.delete(); }
	private static class Canceled extends IOException{}
	private static class Manifest{
		String accountId, albumId, operationId, description, quality, batchId;
		Long capturedAt;
		List<String> sources=new ArrayList<>();
		List<PreparedPhoto> photos=new ArrayList<>();
		long totalBytes;
		int progress;
		boolean ready;
		PublishResponse published;
	}
	private static class PreparedPhoto{
		String clientId;
		int width, height;
		List<Variant> variants=new ArrayList<>();
	}
	private static class Variant{
		String file, kind, mimeType, contentMd5, uploadId;
		long size;
		int width, height;
		boolean putAttempted, complete;
	}
}
