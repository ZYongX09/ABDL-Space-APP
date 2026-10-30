package org.joinmastodon.android.api;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import org.joinmastodon.android.model.Attachment;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.SAXParserFactory;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class CosMediaUpload{
	private CosMediaUpload(){}
	private static final MediaType JSON=MediaType.get("application/json; charset=utf-8");
	private static final int MAX_ERROR_BYTES=16*1024;

	public enum Stage{ AUTHORIZE, PUT, COMPLETE }
	public enum Category{ AUTHENTICATION, PERMISSION, EXPIRED, CONFLICT, VERIFICATION, SERVER, REDIRECT, INVALID_REQUEST, INVALID_RESPONSE, NETWORK, HTTP }

	/** Only these fields/message are display-safe. The original cause is internal: never log its message or stack. */
	public static final class UploadException extends IOException{
		public final Stage stage;
		/** Zero means no HTTP response was received. */
		public final int status;
		public final Category category;
		public final String code;
		public final String cosCode;
		public final String requestId;

		public UploadException(Stage stage, int status, Category category, Throwable cause){
			this(stage, status, category, null, null, null, cause);
		}

		private UploadException(Stage stage, int status, Category category, String code, String cosCode, String requestId, Throwable cause){
			super("COS upload "+stage+" failed: "+category+(status>0 ? " (HTTP "+status+")" : ""), cause);
			this.stage=stage;
			this.status=status;
			this.category=category;
			this.code=code;
			this.cosCode=cosCode;
			this.requestId=requestId;
		}
	}

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

	public static CosUploadAuthorization authorize(OkHttpClient client, String domain, String token, String purpose, RequestBody body, int width, int height, CallListener listener) throws IOException{
		JsonObject json=new JsonObject();
		json.addProperty("purpose", purpose);
		json.addProperty("mimeType", body.contentType().toString());
		json.addProperty("declaredSize", body.contentLength());
		json.addProperty("width", width);
		json.addProperty("height", height);
		Request request=new Request.Builder()
				.url("https://"+domain+"/api/v1/uploads/authorize")
				.header("Authorization", "Bearer "+token)
				.post(RequestBody.create(JSON, MastodonAPIController.gsonWithoutDeserializer.toJson(json)))
				.build();
		return execute(client, request, listener, Stage.AUTHORIZE, CosUploadAuthorization.class);
	}

	public static void put(OkHttpClient client, CosUploadAuthorization authorization, RequestBody body, CallListener listener) throws IOException{
		Request.Builder builder=new Request.Builder().url(authorization.uploadUrl).put(body);
		for(Map.Entry<String, String> header:requiredHeaders(authorization).entrySet())
			builder.header(header.getKey(), header.getValue());
		execute(client, builder.build(), listener, Stage.PUT, null);
	}

	public static Attachment complete(OkHttpClient client, String domain, String token, String uploadId, String previewUploadId, CallListener listener) throws IOException{
		JsonObject json=new JsonObject();
		if(previewUploadId!=null)
			json.addProperty("preview_upload_id", previewUploadId);
		Request request=new Request.Builder()
				.url("https://"+domain+"/api/v1/uploads/"+uploadId+"/complete")
				.header("Authorization", "Bearer "+token)
				.post(RequestBody.create(JSON, MastodonAPIController.gsonWithoutDeserializer.toJson(json)))
				.build();
		return execute(client, request, listener, Stage.COMPLETE, Attachment.class);
	}

	private static <T> T execute(OkHttpClient client, Request request, CallListener listener, Stage stage, Class<T> type) throws IOException{
		Call call=client.newCall(request);
		// Session/cancellation exceptions from the owner must keep their original type.
		listener.onCall(call);
		try(Response response=call.execute()){
			if(!response.isSuccessful()){
				UploadException failure=httpFailure(response, stage);
				if(call.isCanceled()) throw new IOException("Upload canceled");
				throw failure;
			}
			if(type==null) return null;
			try{
				ResponseBody body=response.body();
				T result=body==null ? null : MastodonAPIController.gson.fromJson(body.charStream(), type);
				if(result==null || (result instanceof CosUploadAuthorization authorization && !isValidAuthorization(authorization)))
					throw new IOException("Invalid server response");
				return result;
			}catch(RuntimeException error){
				throw new UploadException(stage, response.code(), Category.INVALID_RESPONSE, error);
			}catch(IOException error){
				if(call.isCanceled()) throw error;
				throw new UploadException(stage, response.code(), Category.INVALID_RESPONSE, error);
			}
		}catch(UploadException error){
			throw error;
		}catch(IOException error){
			if(call.isCanceled()) throw error;
			throw new UploadException(stage, 0, Category.NETWORK, error);
		}
	}

	private static UploadException httpFailure(Response response, Stage stage){
		String code=null, cosCode=null;
		String requestId=safeRequestId(response.header("x-cos-request-id"));
		if(requestId==null) requestId=safeRequestId(response.header("x-request-id"));
		Throwable cause=null;
		try{
			ResponseBody body=response.body();
			if(body!=null){
				// Never drain/parse an unbounded proxy error, and never retain the raw body.
				body.source().request(MAX_ERROR_BYTES+1L);
				if(body.source().buffer().size()<=MAX_ERROR_BYTES){
					String text=body.source().readUtf8().trim();
					if(text.startsWith("{")){
						try(JsonReader reader=new JsonReader(new StringReader(text))){
							reader.beginObject();
							while(reader.hasNext()){
								String name=reader.nextName();
								if(reader.peek()==JsonToken.STRING && (name.equals("code") || name.equals("request_id") || name.equals("requestId"))){
									String value=reader.nextString();
									if(name.equals("code")) code=safeApiCode(value);
									else if(requestId==null) requestId=safeRequestId(value);
								}else reader.skipValue();
							}
							reader.endObject();
							if(reader.peek()!=JsonToken.END_DOCUMENT) throw new IOException("Invalid error response");
						}
					}else if(text.startsWith("<")){
						CosXmlError xml=new CosXmlError();
						// No DTD, entity resolution, external IO, or parser diagnostics on stderr.
						if(text.contains("<!")) throw new IOException("Unsupported XML error response");
						SAXParserFactory factory=SAXParserFactory.newInstance();
						factory.setNamespaceAware(true);
						XMLReader reader=factory.newSAXParser().getXMLReader();
						reader.setEntityResolver((publicId, systemId)->{ throw new SAXException("External XML entity forbidden"); });
						reader.setContentHandler(xml);
						reader.setErrorHandler(xml);
						reader.parse(new InputSource(new StringReader(text)));
						cosCode=safeCosCode(xml.code);
						if(requestId==null) requestId=safeRequestId(xml.requestId);
					}
				}
			}
		}catch(Exception error){
			// HTTP is authoritative even for XML/HTML/malformed/empty/truncated error bodies.
			code=null;
			cosCode=null;
			requestId=safeRequestId(response.header("x-cos-request-id"));
			if(requestId==null) requestId=safeRequestId(response.header("x-request-id"));
			cause=error;
		}
		return new UploadException(stage, response.code(), category(response.code(), code, cosCode), code, cosCode, requestId, cause);
	}

	private static String safeApiCode(String value){
		return switch(value){
			case "unauthorized", "invalid_upload", "release_forbidden", "authorize_failed", "upload_not_found", "wrong_owner",
					"upload_conflict", "preview_not_allowed", "invalid_upload_status", "upload_expired", "preview_required",
					"invalid_preview", "cos_head_failed", "verification_header_missing", "size_mismatch", "type_mismatch", "complete_failed" -> value;
			default -> null;
		};
	}

	private static String safeCosCode(String value){
		if(value==null) return null;
		return switch(value){
			case "AccessDenied", "SignatureDoesNotMatch", "RequestTimeTooSkewed", "RequestHasExpired", "InvalidAccessKeyId",
					"InvalidArgument", "InvalidRequest", "NoSuchKey", "NoSuchBucket", "EntityTooLarge", "BadDigest", "IncompleteBody",
					"InternalError", "ServiceUnavailable", "SlowDown", "ObjectAlreadyExists" -> value;
			default -> null;
		};
	}

	private static String safeRequestId(String value){
		return value!=null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}") ? value : null;
	}

	private static Category category(int status, String code, String cosCode){
		if(status>=300 && status<400) return Category.REDIRECT;
		if("upload_expired".equals(code) || "RequestHasExpired".equals(cosCode) || "RequestTimeTooSkewed".equals(cosCode)) return Category.EXPIRED;
		if(status==401) return Category.AUTHENTICATION;
		if(status==403) return Category.PERMISSION;
		if(status==409) return Category.CONFLICT;
		if(status==422 || "cos_head_failed".equals(code)) return Category.VERIFICATION;
		if(status>=500) return Category.SERVER;
		if("invalid_upload".equals(code)) return Category.INVALID_REQUEST;
		return Category.HTTP;
	}

	private static final class CosXmlError extends DefaultHandler{
		private int depth;
		private String field, code, requestId;
		private final StringBuilder value=new StringBuilder();

		@Override public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException{
			depth++;
			if(depth>16 || (depth==1 && !"Error".equals(localName))) throw new SAXException("Invalid COS XML error");
			if(depth==2 && ("Code".equals(localName) || "RequestId".equals(localName))){ field=localName; value.setLength(0); }
		}
		@Override public void characters(char[] characters, int start, int length){
			if(depth==2 && field!=null) value.append(characters, start, length);
		}
		@Override public void endElement(String uri, String localName, String qName){
			if(depth==2 && field!=null){
				if("Code".equals(field)) code=value.toString(); else requestId=value.toString();
				field=null;
			}
			depth--;
		}
		@Override public void error(org.xml.sax.SAXParseException error) throws SAXException{ throw error; }
		@Override public void fatalError(org.xml.sax.SAXParseException error) throws SAXException{ throw error; }
	}

	public interface CallListener{
		void onCall(Call call);
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
