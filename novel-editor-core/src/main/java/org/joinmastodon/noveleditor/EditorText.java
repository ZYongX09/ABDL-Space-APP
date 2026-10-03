package org.joinmastodon.noveleditor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Deterministic normalization and digest shared by storage and sync code. */
public final class EditorText{
	private EditorText(){}

	public static String normalize(String value){
		if(value==null) return "";
		String normalized=value.replace("\r\n", "\n").replace('\r', '\n');
		if(normalized.indexOf('\0')>=0) throw new IllegalArgumentException("Text contains NUL");
		return normalized;
	}

	public static String sha256(String value){
		try{
			byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result=new StringBuilder(digest.length*2);
			for(byte b:digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b&255));
			return result.toString();
		}catch(NoSuchAlgorithmException impossible){
			throw new IllegalStateException(impossible);
		}
	}

	public static String bounded(String value, int maximum, String label){
		String normalized=normalize(value).trim();
		if(normalized.isEmpty() || normalized.length()>maximum) throw new IllegalArgumentException(label+" length out of range");
		return normalized;
	}
}
