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

	private final PrivateNovelApi api;
	private final IntConsumer progressListener;
	private final AtomicReference<Call> currentCall=new AtomicReference<>();
	private volatile State state=State.IDLE;
	private volatile boolean canceled;
	private int lastProgress;

	public PrivateBookUpload(PrivateNovelApi api, IntConsumer progressListener){
		this.api=api;
		this.progressListener=progressListener;
	}

	public State getState(){
		return state;
	}

	public PrivateNovelApi.BookDto upload(File file, PrivateNovelApi.UploadMetadata metadata) throws IOException{
		try{
			validateFile(file, metadata);
			state=State.PREPARING;
			report(0);
			String sha256=sha256(file);
			String md5=md5Base64(file);
			checkCanceled();

			state=State.AUTHORIZING;
			PrivateNovelApi.AuthorizeRequest request=new PrivateNovelApi.AuthorizeRequest(metadata, file.length(), sha256, md5);
			PrivateNovelApi.UploadAuthorization authorization=execute(api.newAuthorizeCall(request), PrivateNovelApi.UploadAuthorization.class);
			validateAuthorization(authorization, file.length(), sha256, md5, metadata.mimeType);
			report(5);

			if(!authorization.alreadyUploaded){
				state=State.UPLOADING;
				uploadFile(file, authorization);
				report(95);
			}

			checkCanceled();
			state=State.COMPLETING;
			PrivateNovelApi.BookDto result=execute(api.newCompleteCall(authorization.uploadId), PrivateNovelApi.BookDto.class);
			if(!"ready".equals(result.parseStatus) && !"parsing".equals(result.parseStatus))
				throw new IOException("Unexpected parse status");
			state=State.COMPLETE;
			report(100);
			return result;
		}catch(IOException e){
			state=canceled ? State.CANCELED : State.FAILED;
			throw e;
		}finally{
			currentCall.set(null);
		}
	}

	public void cancel(){
		canceled=true;
		state=State.CANCELED;
		Call call=currentCall.get();
		if(call!=null)
			call.cancel();
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
		currentCall.set(call);
		try(Response response=call.execute()){
			if(response.priorResponse()!=null)
				throw new IOException("Redirects are not allowed");
			if(!response.isSuccessful())
				throw new IOException("Upload failed: HTTP "+response.code());
		}finally{
			currentCall.compareAndSet(call, null);
		}
	}

	private <T> T execute(Call call, Class<T> type) throws IOException{
		checkCanceled();
		currentCall.set(call);
		try{
			return api.executeJson(call, type);
		}finally{
			currentCall.compareAndSet(call, null);
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
