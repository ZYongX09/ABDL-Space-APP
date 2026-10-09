package org.joinmastodon.android.albums;

import android.net.Uri;

public final class AlbumInviteLink{
	private AlbumInviteLink(){}

	public static String parseToken(Uri uri){
		if(uri==null || !"https".equals(uri.getScheme()) || !"abdl-space.top".equalsIgnoreCase(uri.getHost())
				|| (uri.getPort()!=-1 && uri.getPort()!=443) || uri.getUserInfo()!=null
				|| uri.getQuery()!=null || uri.getFragment()!=null) return null;
		String path=uri.getEncodedPath();
		if(path==null || !path.matches("/album-invite/[0-9a-f]{64}")) return null;
		return path.substring("/album-invite/".length());
	}
}
