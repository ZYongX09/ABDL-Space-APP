package org.joinmastodon.android.api;

import java.util.Map;

public class CosUploadAuthorization{
	public String uploadId;
	public String uploadUrl;
	public String publicUrl;
	public long expiresAt;
	public Map<String, String> requiredHeaders;
}
