package org.joinmastodon.android.api.requests.map;

import com.google.gson.JsonObject;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.model.map.MapModels;

import java.util.Map;

import me.grishka.appkit.api.ErrorResponse;

/** Shared sensitive request behavior for map presence and city entitlement checks. */
public final class MapRequest<T> extends MastodonAPIRequest<T>{
    private MapRequest(HttpMethod method, String path, Class<T> type, Object body){
        super(method, "/map"+path, type);
        if(body!=null) setRequestBody(body);
        setTimeout(20_000);
    }
    public static MapRequest<MapModels.Settings> settings(){ return new MapRequest<>(HttpMethod.GET, "/settings", MapModels.Settings.class, null); }
    public static MapRequest<MapModels.Settings> updateSettings(Map<String,Object> body){ return new MapRequest<>(HttpMethod.PUT, "/settings", MapModels.Settings.class, body); }
    public static MapRequest<MapModels.Presence> mine(){ return new MapRequest<>(HttpMethod.GET, "/presence/me", MapModels.Presence.class, null); }
    public static MapRequest<MapModels.Presence> updatePresence(Map<String,Object> body){ return new MapRequest<>(HttpMethod.PUT, "/presence", MapModels.Presence.class, body); }
    public static MapRequest<MapModels.PresenceList> list(double minLat, double minLng, double maxLat, double maxLng, double centerLat, double centerLng, String regionId, int zoom, int limit){
        MapRequest<MapModels.PresenceList> request=new MapRequest<>(HttpMethod.GET, "/presence", MapModels.PresenceList.class, null);
        request.addQueryParameter("min_lat", Double.toString(minLat));
        request.addQueryParameter("min_lng", Double.toString(minLng));
        request.addQueryParameter("max_lat", Double.toString(maxLat));
        request.addQueryParameter("max_lng", Double.toString(maxLng));
        request.addQueryParameter("center_lat", Double.toString(centerLat));
        request.addQueryParameter("center_lng", Double.toString(centerLng));
        if(regionId!=null) request.addQueryParameter("region_id", regionId);
        request.addQueryParameter("zoom", Integer.toString(zoom));
        request.addQueryParameter("limit", Integer.toString(limit));
        return request;
    }
    public static MapRequest<Void> delete(){
        MapRequest<Void> request=new MapRequest<>(HttpMethod.DELETE, "/presence", Void.class, null);
        request.setSkipValidation();
        return request;
    }
    @Override public boolean isSensitiveRequest(){ return true; }
    @Override public ErrorResponse deserializeError(JsonObject body, int status){ return new MapErrorResponse(body, status); }
    public static final class MapErrorResponse extends MastodonErrorResponse{
        public final String code;
        public MapErrorResponse(JsonObject body, int status){
            super(read(body,"error","地图请求失败"), status, null);
            code=read(body,"code","unknown");
        }
        private static String read(JsonObject body, String key, String fallback){
            try{return body!=null && body.has(key) && !body.get(key).isJsonNull()?body.get(key).getAsString():fallback;}catch(RuntimeException e){return fallback;}
        }
    }
}
