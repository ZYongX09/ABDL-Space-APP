package org.joinmastodon.android.api.requests.translation;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.JsonObject;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.model.Translation;

import java.io.IOException;

import me.grishka.appkit.api.ErrorResponse;
import okhttp3.Response;

/** Private account traffic: no HTTP cache, no redirect of bearer credentials or raw error copy. */
public abstract class TranslationRequest<T> extends MastodonAPIRequest<T>{
	protected TranslationRequest(HttpMethod method, String path, Class<T> type){
		super(method, path, type);
		addHeader("Cache-Control", "no-store");
		setTimeout(25000);
	}
	@Override public boolean isSensitiveRequest(){ return true; }
	@Override public ErrorResponse deserializeError(JsonObject body, int status){
		String code=null;
		if(body!=null && body.has("code") && body.get("code").isJsonPrimitive() && body.getAsJsonPrimitive("code").isString())
			code=body.get("code").getAsString();
		return new TranslationError(code, status);
	}
	@Override public void validateAndPostprocessResponse(T value, Response response) throws IOException{
		super.validateAndPostprocessResponse(value, response);
		if(value instanceof Translation translation && ((response.code()==202)==translation.isReady()))
			throw new ObjectValidationException("Translation HTTP state disagrees with envelope");
	}

	public static class TranslationError extends MastodonErrorResponse{
		public final String code;
		public TranslationError(String code, int status){ super("Translation unavailable", status, null); this.code=code; }
		public int localizedMessage(){
			if("translation_quota_exhausted".equals(code)) return R.string.post_translation_quota_exhausted;
			if("translation_unavailable".equals(code)) return R.string.post_translation_unavailable;
			if("translation_rate_limited".equals(code)) return R.string.post_translation_rate_limited;
			if(httpStatus==409) return R.string.post_translation_preferences_conflict;
			return R.string.post_translation_failed;
		}
		@Override public String localizedMessage(Context context){ return serviceResourcePause!=null ? serviceResourcePause.message(context) : context.getString(localizedMessage()); }
		@Override public void showToast(Context context){
			if(context==null) return;
			if(serviceResourcePause!=null) serviceResourcePause.show(context);
			else Toast.makeText(context, localizedMessage(), Toast.LENGTH_LONG).show();
		}
		@Override public void bindErrorView(View view){
			TextView text=view.findViewById(R.id.error_text);
			if(serviceResourcePause!=null) serviceResourcePause.bind(text);
			else{ org.joinmastodon.android.api.ServiceResourcePause.unbind(text); text.setText(localizedMessage()); }
		}
	}
}
