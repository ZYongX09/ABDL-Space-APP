package org.joinmastodon.android.api;

import org.joinmastodon.android.api.session.NBWEndpointsRepository;
import org.joinmastodon.android.model.NBWEndpoints;

import java.net.URI;

/** Pure projection for typed NBW display/media fields only. Never use on OAuth or arbitrary body links. */
public final class NBWUrlResolver{
	private NBWUrlResolver(){}
	public static boolean isTrusted(String value){
		if(value==null || value.length()>8192 || value.indexOf('\\')>=0) return false;
		try{
			URI uri=new URI(value);
			return !uri.isOpaque() && "https".equals(uri.getScheme()) && uri.getRawUserInfo()==null && uri.getPort()==-1
					&& ("www.newbabyworld.top".equals(uri.getRawAuthority()) || "nbw.abdlcalendar.top".equals(uri.getRawAuthority()));
		}catch(Exception invalid){ return false; }
	}
	public static String resolve(String value, NBWEndpoints endpoints, boolean media){
		if(!isTrusted(value)) return value;
		if(endpoints==null || !endpoints.canRead()) return "";
		try{ endpoints.postprocess(); }catch(Exception invalid){ return ""; }
		int start=value.indexOf("://")+3, end=value.length();
		for(char delimiter:new char[]{'/','?','#'}){ int index=value.indexOf(delimiter,start); if(index>=0) end=Math.min(end,index); }
		return (media ? endpoints.mediaOrigin : endpoints.publicOrigin)+value.substring(end);
	}
	public static String current(String value, boolean media){
		var snapshot=NBWEndpointsRepository.getInstance().getSnapshot();
		return resolve(value,snapshot.fresh ? snapshot.endpoints : null,media);
	}
}
