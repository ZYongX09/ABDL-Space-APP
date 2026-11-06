package org.joinmastodon.android.sponsors;

import android.os.Handler;
import android.os.Looper;

import org.joinmastodon.android.api.requests.sponsors.SponsorPaymentRequest;
import org.joinmastodon.android.model.BaseModel;
import org.joinmastodon.android.model.sponsors.SponsorModels.*;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Native-client-only, fixed-origin transport. Never creates a production account/API controller. */
final class SandboxSponsorSource implements SponsorPaymentCoordinator.Source{
	private static final String API="/api/v1/sponsors";
	private static final MediaType JSON=MediaType.parse("application/json; charset=utf-8");
	private final SandboxSession session;
	private final HttpUrl origin;
	private final OkHttpClient client;
	private final Handler main;
	private interface Decoder<T>{ T decode(byte[] bytes) throws IOException; }

	SandboxSponsorSource(SandboxSession session){ this(session, client(), new Handler(Looper.getMainLooper())); }
	// Instance-only same-package transport seam for offline tests; never a mutable global override.
	SandboxSponsorSource(SandboxSession session, OkHttpClient client, Handler main){
		this.session=session; this.client=client; this.main=main;
		origin=HttpUrl.get(SandboxProtocol.requireOrigin(session.origin));
	}
	static OkHttpClient client(){
		return new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
				.retryOnConnectionFailure(false).cache(null).cookieJar(CookieJar.NO_COOKIES)
				.authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
				.connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
				.writeTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build();
	}

	SponsorPaymentCoordinator.Cancelable pair(String code, SponsorPaymentCoordinator.Result<String> result){
		if(!SandboxProtocol.validCode(code)) return fail(result, "invalid_pair_code");
		Request request=base("/__sandbox/pair").post(body(Map.of("code", code))).build();
		return send(request, null, SandboxProtocol.PAIR_LIMIT, bytes->SandboxProtocol.pair(bytes, session.origin, session.now()), new SponsorPaymentCoordinator.Result<>(){
			@Override public void success(SandboxProtocol.Pair pair){
				String problem=session.accept(pair);
				if(problem==null) result.success(session.owner());
				else result.failure(new SponsorPaymentCoordinator.Failure(-1, problem));
			}
			@Override public void failure(SponsorPaymentCoordinator.Failure failure){ result.failure(failure); }
		});
	}
	private Request.Builder base(String path){
		if(!allowed("GET", path) && !allowed("POST", path)) throw new IllegalArgumentException("Invalid sandbox route");
		return new Request.Builder().url(origin.newBuilder().encodedPath(path).build())
				.header("Accept", "application/json").header("Cache-Control", "no-store");
	}
	private static RequestBody body(Object value){ return RequestBody.create(JSON, SandboxProtocol.JSON.toJson(value)); }
	private static void plan(String id, int version){
		if(!SandboxProtocol.PLAN.equals(id) || version!=SandboxProtocol.VERSION) throw new IllegalArgumentException("Invalid sandbox plan");
	}
	private static String orderPath(String id){
		if(!SponsorPaymentModels.validOrderId(id)) throw new IllegalArgumentException("Invalid sandbox order");
		return API+"/payment-orders/"+id;
	}
	static boolean allowed(String method, String path){
		if("POST".equals(method) && "/__sandbox/pair".equals(path)) return true;
		if("GET".equals(method) && (API+"/catalog").equals(path) || "GET".equals(method) && (API+"/me").equals(path)
				|| "GET".equals(method) && (API+"/payment-options").equals(path)
				|| "GET".equals(method) && (API+"/payment-orders/current").equals(path)) return true;
		if("POST".equals(method) && (API+"/payment-orders").equals(path)) return true;
		String prefix=API+"/payment-orders/";
		if(!path.startsWith(prefix)) return false;
		String tail=path.substring(prefix.length());
		if("POST".equals(method) && tail.endsWith("/close")) tail=tail.substring(0, tail.length()-6);
		else if(!"GET".equals(method)) return false;
		return !"current".equals(tail) && SponsorPaymentModels.validOrderId(tail);
	}
	private <T extends BaseModel> SponsorPaymentCoordinator.Cancelable authenticated(Request.Builder request, Class<T> type, boolean create, SponsorPaymentCoordinator.Result<T> result){
		SandboxSession.Lease lease=session.lease();
		if(lease==null) return fail(result, "unauthorized");
		return send(request.header("Authorization", "Bearer "+lease.token).build(), lease, SandboxProtocol.BODY_LIMIT,
				bytes->SandboxProtocol.model(bytes, type, create), result);
	}
	@Override public SponsorPaymentCoordinator.Cancelable catalog(SponsorPaymentCoordinator.Result<Catalog> result){ return authenticated(base(API+"/catalog"), Catalog.class, false, result); }
	@Override public SponsorPaymentCoordinator.Cancelable me(SponsorPaymentCoordinator.Result<Me> result){ return authenticated(base(API+"/me"), Me.class, false, result); }
	@Override public SponsorPaymentCoordinator.Cancelable options(String id, int version, SponsorPaymentCoordinator.Result<Options> result){
		plan(id, version);
		Request.Builder request=base(API+"/payment-options");
		request.url(origin.newBuilder().encodedPath(API+"/payment-options").addQueryParameter("plan_id", id).addQueryParameter("plan_version", String.valueOf(version)).build());
		return authenticated(request, Options.class, false, result);
	}
	@Override public SponsorPaymentCoordinator.Cancelable create(String id, int version, String operation, SponsorPaymentCoordinator.Result<Order> result){
		plan(id, version);
		if(!SponsorPaymentRequest.validOperation(operation)) throw new IllegalArgumentException("Invalid payment operation");
		return authenticated(base(API+"/payment-orders").post(body(Map.of("plan_id", id, "plan_version", version, "channel", "alipay", "operation_id", operation)))
				.header("Idempotency-Key", operation), Order.class, true, result);
	}
	@Override public SponsorPaymentCoordinator.Cancelable current(SponsorPaymentCoordinator.Result<CurrentOrder> result){ return authenticated(base(API+"/payment-orders/current"), CurrentOrder.class, false, result); }
	@Override public SponsorPaymentCoordinator.Cancelable order(String id, SponsorPaymentCoordinator.Result<Order> result){ return authenticated(base(orderPath(id)), Order.class, false, result); }
	@Override public SponsorPaymentCoordinator.Cancelable close(String id, SponsorPaymentCoordinator.Result<Order> result){ return authenticated(base(orderPath(id)+"/close").post(body(Map.of())), Order.class, false, result); }

	private <T> SponsorPaymentCoordinator.Cancelable fail(SponsorPaymentCoordinator.Result<T> result, String code){
		AtomicBoolean canceled=new AtomicBoolean();
		main.post(()->{ if(!canceled.get()) result.failure(new SponsorPaymentCoordinator.Failure(401, code)); });
		return ()->canceled.set(true);
	}
	private <T> SponsorPaymentCoordinator.Cancelable send(Request request, SandboxSession.Lease lease, int limit, Decoder<T> decoder, SponsorPaymentCoordinator.Result<T> result){
		// No arbitrary URL, header or method can enter through UI/Intents. Recheck before dispatch.
		HttpUrl url=request.url();
		if(!origin.scheme().equals(url.scheme()) || !origin.host().equals(url.host()) || origin.port()!=url.port()
				|| !url.username().isEmpty() || !url.password().isEmpty() || url.fragment()!=null
				|| !allowed(request.method(), url.encodedPath()) || request.header("Cookie")!=null || request.header("Origin")!=null)
			throw new IllegalArgumentException("Invalid sandbox request");
		AtomicBoolean canceled=new AtomicBoolean(); Call call=client.newCall(request);
		call.enqueue(new Callback(){
			@Override public void onFailure(Call ignored, IOException failure){ deliver(null, new SponsorPaymentCoordinator.Failure(-1, "request_failed")); }
			@Override public void onResponse(Call ignored, Response response){
				try(response){
					ResponseBody body=response.body();
					if(body==null || body.contentType()==null || !"application".equals(body.contentType().type()) || !"json".equals(body.contentType().subtype())) throw new IOException("Invalid sandbox response");
					byte[] bytes=bounded(body, limit);
					if(response.code()!=200 && response.code()!=201){
						deliver(null, new SponsorPaymentCoordinator.Failure(response.code(), response.code()==401 ? "unauthorized" : SandboxProtocol.error(bytes))); return;
					}
					deliver(decoder.decode(bytes), null);
				}catch(IOException | RuntimeException failure){ deliver(null, new SponsorPaymentCoordinator.Failure(-1, "invalid_response")); }
			}
			private void deliver(T value, SponsorPaymentCoordinator.Failure failure){
				main.post(()->{
					if(canceled.get()) return;
					if(lease!=null && !session.valid(lease)){ result.failure(new SponsorPaymentCoordinator.Failure(401, "unauthorized")); return; }
					if(failure!=null){ if(failure.status==401 && lease!=null) session.invalidate(lease); result.failure(failure); }
					else result.success(value);
				});
			}
		});
		return ()->{ canceled.set(true); call.cancel(); };
	}
	private static byte[] bounded(ResponseBody body, int limit) throws IOException{
		if(body.contentLength()>limit) throw new IOException("Oversized sandbox response");
		ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[4096];
		try(var input=body.byteStream()){
			int count;
			while((count=input.read(buffer))!=-1){ if(bytes.size()+count>limit) throw new IOException("Oversized sandbox response"); bytes.write(buffer, 0, count); }
		}
		return bytes.toByteArray();
	}
}
