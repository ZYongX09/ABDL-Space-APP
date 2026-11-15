package org.joinmastodon.android.api.requests;

import android.net.Uri;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.ContentAccess;

/** Uncached, non-redirecting first-party policy preflight. */
public final class GetContentAccess extends MastodonAPIRequest<ContentAccess>{
	public GetContentAccess(){ super(HttpMethod.GET, "/access", ContentAccess.class); setTimeout(15_000); }
	@Override public Uri getURL(){ return Uri.parse("https://api.abdl-space.top/api/access"); }
	@Override public boolean isSensitiveRequest(){ return true; }
	@Override public int responseByteLimit(){ return 16*1024; }
}
