package org.joinmastodon.android.sponsors;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Pure retry-ticket policy. Tickets identify attempts, never grant client-side authorization. */
public final class SponsorOperation{
	private SponsorOperation(){}
	public static String hash(String value){
		try{
			byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result=new StringBuilder(64);
			for(byte b:bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b&255));
			return result.toString();
		}catch(NoSuchAlgorithmException impossible){ throw new IllegalStateException(impossible); }
	}
	public static String mediaKey(String mediaId, String originalUrl){ return hash((mediaId==null ? "" : mediaId)+"\n"+originalUrl); }
	public static String scope(String account, String day, String media){ return hash(account)+"."+day+"."+media; }
	public static boolean reusable(String storedDay, String currentDay, long resetsAt, long now){
		return storedDay!=null && storedDay.equals(currentDay) && now<resetsAt;
	}
	public static String newId(){ return UUID.randomUUID().toString(); }
}
