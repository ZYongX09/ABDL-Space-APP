package org.joinmastodon.android.model;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import org.joinmastodon.android.api.ObjectValidationException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, public routing metadata. It is never an account or an authorization grant. */
@JsonAdapter(NBWEndpoints.Adapter.class)
public final class NBWEndpoints extends BaseModel{
	public static final String PRIMARY="https://www.newbabyworld.top", BACKUP="https://nbw.abdlcalendar.top";
	public final int schemaVersion, ttlSeconds;
	public final String revision, mode, apiOrigin, authorizeOrigin, publicOrigin, mediaOrigin, remoteMode;

	public NBWEndpoints(int schemaVersion, String revision, String mode, String apiOrigin, String authorizeOrigin,
			String publicOrigin, String mediaOrigin, int ttlSeconds, String remoteMode){
		this.schemaVersion=schemaVersion; this.revision=revision; this.mode=mode; this.apiOrigin=apiOrigin;
		this.authorizeOrigin=authorizeOrigin; this.publicOrigin=publicOrigin; this.mediaOrigin=mediaOrigin;
		this.ttlSeconds=ttlSeconds; this.remoteMode=remoteMode;
	}
	@Override public void postprocess() throws ObjectValidationException{
		String origin="primary".equals(mode) ? PRIMARY : "backup".equals(mode) ? BACKUP : null;
		if(schemaVersion!=1 || revision==null || revision.isBlank() || revision.length()>96 || origin==null
				|| !origin.equals(apiOrigin) || !origin.equals(authorizeOrigin) || !origin.equals(publicOrigin) || !origin.equals(mediaOrigin)
				|| ttlSeconds<1 || ttlSeconds>30 || !Set.of("full", "read_only", "local_only").contains(remoteMode==null ? "" : remoteMode))
			throw new ObjectValidationException("NBW endpoint configuration unavailable; matching server required");
	}
	public boolean canRead(){ return "full".equals(remoteMode) || "read_only".equals(remoteMode); }
	public boolean canWrite(){ return "full".equals(remoteMode); }
	@Override public boolean equals(Object other){
		return other instanceof NBWEndpoints e && schemaVersion==e.schemaVersion && ttlSeconds==e.ttlSeconds
				&& Objects.equals(revision,e.revision) && Objects.equals(mode,e.mode) && Objects.equals(apiOrigin,e.apiOrigin)
				&& Objects.equals(authorizeOrigin,e.authorizeOrigin) && Objects.equals(publicOrigin,e.publicOrigin)
				&& Objects.equals(mediaOrigin,e.mediaOrigin) && Objects.equals(remoteMode,e.remoteMode);
	}
	@Override public int hashCode(){ return Objects.hash(schemaVersion,revision,mode,apiOrigin,authorizeOrigin,publicOrigin,mediaOrigin,ttlSeconds,remoteMode); }

	/** No coercion, duplicate/unknown fields, embedded credentials or missing legacy fields. */
	public static final class Adapter extends TypeAdapter<NBWEndpoints>{
		private static final Set<String> FIELDS=Set.of("schema_version","revision","mode","api_origin","authorize_origin","public_origin","media_origin","ttl_seconds","remote_mode");
		@Override public NBWEndpoints read(JsonReader in) throws IOException{
			if(in.peek()!=JsonToken.BEGIN_OBJECT) throw new IOException("Invalid NBW endpoints");
			Map<String,String> values=new HashMap<>(); in.beginObject();
			while(in.hasNext()){
				String key=in.nextName();
				if(!FIELDS.contains(key) || values.containsKey(key)) throw new IOException("Invalid NBW endpoint member");
				boolean number=key.equals("schema_version") || key.equals("ttl_seconds");
				if(in.peek()!=(number ? JsonToken.NUMBER : JsonToken.STRING)) throw new IOException("Invalid NBW endpoint type");
				String value=in.nextString();
				if(value.length()>128 || number && !value.matches("[1-9][0-9]?")) throw new IOException("Invalid NBW endpoint value");
				values.put(key,value);
			}
			in.endObject();
			if(!values.keySet().equals(FIELDS)) throw new IOException("Matching NBW endpoint server required");
			NBWEndpoints result=new NBWEndpoints(Integer.parseInt(values.get("schema_version")),values.get("revision"),values.get("mode"),
					values.get("api_origin"),values.get("authorize_origin"),values.get("public_origin"),values.get("media_origin"),
					Integer.parseInt(values.get("ttl_seconds")),values.get("remote_mode"));
			result.postprocess(); return result;
		}
		@Override public void write(JsonWriter out, NBWEndpoints value) throws IOException{
			out.beginObject(); out.name("schema_version").value(value.schemaVersion); out.name("revision").value(value.revision);
			out.name("mode").value(value.mode); out.name("api_origin").value(value.apiOrigin); out.name("authorize_origin").value(value.authorizeOrigin);
			out.name("public_origin").value(value.publicOrigin); out.name("media_origin").value(value.mediaOrigin);
			out.name("ttl_seconds").value(value.ttlSeconds); out.name("remote_mode").value(value.remoteMode); out.endObject();
		}
	}
}
