package org.joinmastodon.android.api.requests.advertisements;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.Map;

import okhttp3.internal.http.HttpMethod;

/** Best-effort ad telemetry. Callers intentionally do not block on this request. */
public class RecordAdvertisementEvent extends MastodonAPIRequest<Map<String, Object>> {
	@Override protected String getPathPrefix(){ return "/api"; }
	public enum Type {
		IMPRESSION("impression"),
			CLICK("link_click"),
			IMAGE_VIEW("image_view"),
			NAVIGATION("ad_navigation");

		public final String wireValue;
		Type(String wireValue){ this.wireValue=wireValue; }
	}

	public RecordAdvertisementEvent(String advertisementId, Type type){
		 super(HttpMethod.POST, "/merchant/ads/"+android.net.Uri.encode(advertisementId)+"/events", new com.google.gson.reflect.TypeToken<Map<String, Object>>(){});

		setRequestBody(Map.of("ad_id", advertisementId, "event_type", type.wireValue, "event_key", advertisementId+":"+type.wireValue+":"+System.nanoTime()));
		setSkipValidation();
	}
}
