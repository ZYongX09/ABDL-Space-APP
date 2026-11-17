package org.joinmastodon.android.api;

import android.net.Uri;

import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.ContentAccessCoordinator;

import java.io.IOException;
import java.util.Objects;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Header-only policy boundary for shared-client callers (albums/maps/novels included).
 * No JSON sniffing, navigation, persisted allow, response replay or interception of foreign hosts. */
final class ContentAccessInterceptor implements Interceptor{
	@Override public Response intercept(Chain chain) throws IOException{
		Request request=chain.request();
		Uri uri=Uri.parse(request.url().toString());
		if(!ContentAccessCoordinator.isProtectedRoute(uri,request.method())) return chain.proceed(request);
		ContentAccessCoordinator.Owner owner=request.tag(ContentAccessCoordinator.Owner.class);
		if(owner!=null && !owner.canDispatch()) throw unavailable();
		AccountSession session=AccountSessionManager.getInstance().getLastActiveAccount();
		if(!ContentAccessCoordinator.applies(session)) return chain.proceed(request);
		String authorization=request.header("Authorization");
		// Never attribute an unauthenticated request or another account's request to the selected account.
		if(session.token==null || !Objects.equals(authorization,"Bearer "+session.token.accessToken)) return chain.proceed(request);
		if(owner==null) owner=session.getContentAccessCoordinator().capture();
		// No capturable owner means the account identity is mid-change; the request still carries the
		// active account's own token, so let the server answer instead of fabricating a 503.
		if(owner==null) return chain.proceed(request);
		if(!owner.canDispatch()) throw unavailable();
		Response response=chain.proceed(request);
		if(response.code()==401 && owner.sameIdentity()) return response;
		if(!owner.isCurrent()){ response.close(); throw unavailable(); }
		if(ContentAccessCoordinator.isFirstParty(Uri.parse(response.request().url().toString()))
				&& (response.cacheResponse()==null || response.networkResponse()!=null)){
			String header=response.header(ContentAccessCoordinator.HEADER);
			owner.receiveHeader(header);
			if("v1:baby_verification_required".equals(header) || "v1:unavailable".equals(header)){
				response.close(); throw unavailable(); // No raw-client cache write before the UI-thread close is applied.
			}
		}
		return response;
	}
	private static IOException unavailable(){ return new IOException(org.joinmastodon.android.MastodonApp.context.getString(org.joinmastodon.android.R.string.content_access_unavailable)); }
}
