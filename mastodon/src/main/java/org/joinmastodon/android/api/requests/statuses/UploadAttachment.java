package org.joinmastodon.android.api.requests.statuses;

import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.ContentUriRequestBody;
import org.joinmastodon.android.api.CosMediaUpload;
import org.joinmastodon.android.api.CosPreviewRequestBody;
import org.joinmastodon.android.api.CosProgressRequestBody;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.ProgressListener;
import org.joinmastodon.android.api.ResizedImageRequestBody;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.io.IOException;

import okhttp3.MultipartBody;
import okhttp3.Call;
import okhttp3.RequestBody;
import okhttp3.Response;

public class UploadAttachment extends MastodonAPIRequest<Attachment>{
	private Uri uri;
	private ProgressListener progressListener;
	private int maxImageSize;
	private String description;
	private boolean isNsfw;
	private volatile Call currentCall;
	private volatile Thread uploadThread;
	private boolean useImgbedFallback;

	public UploadAttachment(Uri uri){
		super(HttpMethod.POST, "/media", Attachment.class);
		this.uri=uri;
	}

	public UploadAttachment(Uri uri, int maxImageSize, String description){
		this(uri);
		this.maxImageSize=maxImageSize;
		this.description=description;
	}

	public UploadAttachment setProgressListener(ProgressListener progressListener){
		this.progressListener=progressListener;
		return this;
	}

	public UploadAttachment setNsfw(boolean nsfw){
		this.isNsfw=nsfw;
		return this;
	}

	public UploadAttachment setUseImgbedFallback(boolean useImgbedFallback){
		this.useImgbedFallback=useImgbedFallback;
		return this;
	}

	@Override
	public UploadAttachment exec(String accountID){
		AccountSession session=AccountSessionManager.getInstance().getAccount(accountID);
		if(session==null || !"abdl-space.top".equalsIgnoreCase(session.domain))
			return (UploadAttachment)super.exec(accountID);
		String contentType=MastodonApp.context.getContentResolver().getType(uri);
		if(useImgbedFallback){
			addHeader("X-ABDL-Upload-Fallback", "imgbed");
			return (UploadAttachment)super.exec(accountID);
		}
		if(contentType==null || !contentType.startsWith("image/"))
			return (UploadAttachment)super.exec(accountID);
		Thread thread=new Thread(()->uploadToCos(accountID), "CosMediaUpload");
		thread.setDaemon(true);
		uploadThread=thread;
		thread.start();
		return this;
	}

	private void uploadToCos(String accountID){
		CosPreviewRequestBody previewBody=null;
		RequestBody originalDelegate=null;
		try{
			AccountSession session=AccountSessionManager.getInstance().getAccount(accountID);
			throwIfCanceled();
			BitmapFactory.Options bounds=new BitmapFactory.Options();
			bounds.inJustDecodeBounds=true;
			try(var input=MastodonApp.context.getContentResolver().openInputStream(uri)){
				BitmapFactory.decodeStream(input, null, bounds);
			}
			if(bounds.outWidth<=0 || bounds.outHeight<=0)
				throw new IOException("Invalid image");
			throwIfCanceled();
			originalDelegate=maxImageSize>0 ? new ResizedImageRequestBody(uri, maxImageSize, null) : new ContentUriRequestBody(uri, null);
			throwIfCanceled();
			previewBody=new CosPreviewRequestBody(uri, null);
			throwIfCanceled();
			CosMediaUpload.Progress progress=new CosMediaUpload.Progress(originalDelegate.contentLength(), previewBody.contentLength());
			ProgressListener originalProgress=(transferred, total)->notifyProgress(progress.updateOriginal(transferred));
			ProgressListener previewProgress=(transferred, total)->notifyProgress(progress.updatePreview(transferred));
			RequestBody originalBody=new CosProgressRequestBody(originalDelegate, originalProgress);
			RequestBody previewUploadBody=new CosProgressRequestBody(previewBody, previewProgress);
			var client=MastodonAPIController.getHttpClient();
			String token=session.token.accessToken;
			int originalWidth=originalDelegate instanceof ResizedImageRequestBody resized ? resized.getWidth() : bounds.outWidth;
			int originalHeight=originalDelegate instanceof ResizedImageRequestBody resized ? resized.getHeight() : bounds.outHeight;
			throwIfCanceled();
			var originalAuth=CosMediaUpload.authorize(client, session.domain, token, "status_original", originalBody, originalWidth, originalHeight, this::setCurrentCall);
			var previewAuth=CosMediaUpload.authorize(client, session.domain, token, "status_preview", previewUploadBody, previewBody.getWidth(), previewBody.getHeight(), this::setCurrentCall);
			CosMediaUpload.put(client, originalAuth, originalBody, this::setCurrentCall);
			CosMediaUpload.put(client, previewAuth, previewUploadBody, this::setCurrentCall);
			CosMediaUpload.complete(client, session.domain, token, previewAuth.uploadId, null, this::setCurrentCall);
			Attachment attachment=CosMediaUpload.complete(client, session.domain, token, originalAuth.uploadId, previewAuth.uploadId, this::setCurrentCall);
			validateAndPostprocessResponse(attachment, null);
			dispatchSuccess(attachment);
		}catch(Exception x){
			dispatchError(new MastodonErrorResponse(x.getLocalizedMessage(), -1, x));
		}finally{
			currentCall=null;
			uploadThread=null;
			if(previewBody!=null)
				previewBody.close();
			if(originalDelegate instanceof ResizedImageRequestBody resized)
				resized.cleanup();
		}
	}

	private void throwIfCanceled() throws IOException{
		if(isCanceled() || Thread.currentThread().isInterrupted())
			throw new IOException("Upload canceled");
	}

	private void setCurrentCall(Call call){
		currentCall=call;
		if(isCanceled())
			call.cancel();
	}

	private void notifyProgress(int percent){
		if(progressListener!=null)
			progressListener.onProgress(percent, 100);
	}

	@Override
	public synchronized void cancel(){
		super.cancel();
		if(uploadThread!=null)
			uploadThread.interrupt();
		if(currentCall!=null)
			currentCall.cancel();
	}

	@Override
	protected String getPathPrefix(){
		return "/api/v1";
	}

	@Override
	public void validateAndPostprocessResponse(Attachment respObj, Response httpResponse) throws IOException{
		if(respObj.url==null)
			respObj.url="";
		super.validateAndPostprocessResponse(respObj, httpResponse);
	}

	@Override
	public RequestBody getRequestBody() throws IOException{
		MultipartBody.Builder builder=new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("file", UiUtils.getFileName(uri), maxImageSize>0 ? new ResizedImageRequestBody(uri, maxImageSize, progressListener) : new ContentUriRequestBody(uri, progressListener));
		if(!TextUtils.isEmpty(description))
			builder.addFormDataPart("description", description);
		if(isNsfw)
			builder.addFormDataPart("is_nsfw", "true");
		return builder.build();
	}
}
