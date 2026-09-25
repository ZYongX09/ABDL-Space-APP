package org.joinmastodon.android.verification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

public final class VerificationOperation{
	private VerificationOperation(){}
	public static String newId(){ return UUID.randomUUID().toString(); }
	public static String stableId(String accountId, String purpose){
		try{
			byte[] bytes=MessageDigest.getInstance("SHA-256").digest((accountId+"\n"+purpose).getBytes(StandardCharsets.UTF_8));
			StringBuilder result=new StringBuilder("bv_");
			for(byte value:bytes) result.append(String.format(java.util.Locale.US, "%02x", value));
			return result.toString();
		}catch(Exception impossible){ throw new AssertionError(impossible); }
	}
}
