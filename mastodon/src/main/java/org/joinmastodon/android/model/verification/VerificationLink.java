package org.joinmastodon.android.model.verification;

import android.net.Uri;

public final class VerificationLink{
	private VerificationLink(){}
	public static String parseToken(Uri uri){
		if(uri==null || !"https".equals(uri.getScheme()) || !"abdl-space.top".equals(uri.getHost()) || uri.getPort()!=-1 || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null) return null;
		java.util.List<String> segments=uri.getPathSegments();
		if(segments.size()!=2 || !"c".equals(segments.get(0)) || !isValidToken(segments.get(1))) return null;
		return segments.get(1);
	}
	public static boolean isCanonical(String url,String token){ return token!=null && token.equals(parseToken(url==null?null:Uri.parse(url))); }
	public static boolean isValidToken(String token){ return token!=null && token.matches("[A-Za-z0-9_-]{16,128}"); }
}
