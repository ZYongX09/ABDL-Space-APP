package org.joinmastodon.android.api;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;

import org.joinmastodon.android.R;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import me.grishka.appkit.api.ErrorResponse;

public class MastodonErrorResponse extends ErrorResponse{
	public final String error;
	public final int httpStatus;
	public final Throwable underlyingException;
	public final int messageResource;

	public MastodonErrorResponse(String error, int httpStatus, Throwable exception){
		this.error=error;
		this.httpStatus=httpStatus;
		this.underlyingException=exception;

		if(exception instanceof UnknownHostException){
			this.messageResource=R.string.could_not_reach_server;
		}else if(exception instanceof SocketTimeoutException){
			this.messageResource=R.string.connection_timed_out;
		}else if(exception instanceof JsonSyntaxException || exception instanceof JsonIOException || httpStatus>=500){
			this.messageResource=R.string.server_error;
		}else if(httpStatus==404){
			this.messageResource=R.string.not_found;
		}else if(isLoginExpiredError(error, httpStatus)){
			this.messageResource=R.string.login_expired;
		}else{
			this.messageResource=0;
		}
	}

	@Override
	public void bindErrorView(View view){
		TextView text=view.findViewById(R.id.error_text);
		String message;
		if(messageResource>0){
			message=view.getContext().getString(messageResource, error);
		}else{
			message=error;
		}
		text.setText(message);
	}

	@Override
	public void showToast(Context context){
		if(context==null)
			return;
		String message;
		if(messageResource>0){
			message=context.getString(messageResource, error);
		}else{
			message=error;
		}
		Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
	}

	/** Only this machine code opens binding UI; ordinary permission failures keep their text. */
	public static MastodonErrorResponse nbwBindingError(com.google.gson.JsonObject body, int status){
		if(status!=403 || body==null || !body.has("code") || !body.get("code").isJsonPrimitive()
				|| !"NBW_BINDING_REQUIRED".equals(body.get("code").getAsString())) return null;
		String message=body.has("error") && body.get("error").isJsonPrimitive()
				? body.get("error").getAsString() : "请先绑定宝宝新天地账号后再操作";
		return new NBWBindingRequired(message, status);
	}

	public static class NBWBindingRequired extends MastodonErrorResponse{
		public final String code="NBW_BINDING_REQUIRED";
		public String accountID;
		public NBWBindingRequired(String error, int status){ super(error, status, null); }
		@Override public void showToast(Context context){
			Context owner=context;
			while(owner instanceof android.content.ContextWrapper wrapper && !(owner instanceof android.app.Activity)){
				Context base=wrapper.getBaseContext();
				if(base==owner) break;
				owner=base;
			}
			if(!(owner instanceof android.app.Activity activity) || activity.isFinishing() || activity.isDestroyed()){
				super.showToast(context);
				return;
			}
			android.app.AlertDialog dialog=new org.joinmastodon.android.ui.M3AlertDialogBuilder(activity)
					.setTitle(R.string.compose_newbabyworld_title)
					.setMessage(R.string.nbw_interaction_binding_required)
					.setPositiveButton(R.string.compose_newbabyworld_bind, null)
					.setNegativeButton(R.string.cancel, null).show();
			dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
				android.content.Intent intent=new android.content.Intent(activity, org.joinmastodon.android.fragments.settings.NBWPostRegisterActivity.class);
				intent.putExtra("show_back", true);
				intent.putExtra("account", accountID);
				activity.startActivity(intent);
				dialog.dismiss();
			});
		}
	}

	private static boolean isLoginExpiredError(String error, int httpStatus){
		if(httpStatus!=401 || error==null)
			return false;
		String lower=error.toLowerCase();
		return lower.contains("access token is invalid")
				|| lower.contains("authentication required")
				|| lower.contains("session expired")
				|| lower.contains("invalid token");
	}
}
