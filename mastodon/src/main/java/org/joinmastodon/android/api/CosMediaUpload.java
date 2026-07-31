package org.joinmastodon.android.api;

import java.util.LinkedHashMap;
import java.util.Map;

public class CosMediaUpload{
	private CosMediaUpload(){}

	public static boolean isValidAuthorization(CosUploadAuthorization authorization){
		return authorization!=null
				&& authorization.uploadId!=null
				&& authorization.uploadUrl!=null
				&& authorization.publicUrl!=null
				&& authorization.requiredHeaders!=null
				&& authorization.requiredHeaders.containsKey("Authorization")
				&& authorization.requiredHeaders.containsKey("Content-Type")
				&& "true".equals(authorization.requiredHeaders.get("x-cos-forbid-overwrite"));
	}

	public static Map<String, String> requiredHeaders(CosUploadAuthorization authorization){
		if(!isValidAuthorization(authorization))
			throw new IllegalArgumentException("Invalid COS upload authorization");
		return new LinkedHashMap<>(authorization.requiredHeaders);
	}

	public static class Progress{
		private final long originalTotal;
		private final long previewTotal;
		private long originalTransferred;
		private long previewTransferred;
		private int lastPercent;

		public Progress(long originalTotal, long previewTotal){
			if(originalTotal<=0 || previewTotal<0)
				throw new IllegalArgumentException("Invalid upload size");
			this.originalTotal=originalTotal;
			this.previewTotal=previewTotal;
		}

		public int updateOriginal(long transferred){
			originalTransferred=clamp(transferred, originalTotal);
			return percent();
		}

		public int updatePreview(long transferred){
			previewTransferred=clamp(transferred, previewTotal);
			return percent();
		}

		private int percent(){
			long total=originalTotal+previewTotal;
			int current=(int)Math.min(100, Math.round((originalTransferred+previewTransferred)*100d/total));
			lastPercent=Math.max(lastPercent, current);
			return lastPercent;
		}

		private static long clamp(long value, long maximum){
			return Math.max(0, Math.min(value, maximum));
		}
	}
}
