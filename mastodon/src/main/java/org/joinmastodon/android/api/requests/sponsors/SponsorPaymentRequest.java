package org.joinmastodon.android.api.requests.sponsors;

import com.google.gson.JsonObject;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels.*;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import me.grishka.appkit.api.ErrorResponse;
import okhttp3.Response;

/** Authenticated selected-origin payments. Never cache or redirect signed order responses. */
public final class SponsorPaymentRequest<T> extends MastodonAPIRequest<T>{
	private final boolean create;
	private SponsorPaymentRequest(HttpMethod method, String path, Class<T> type, Object body, boolean create){
		super(method, "/sponsors"+path, type);
		this.create=create;
		if(body!=null) setRequestBody(body);
		addHeader("Cache-Control", "no-store");
		setTimeout(30000);
	}
	@Override public boolean isSensitiveRequest(){ return true; }
	@Override public int responseByteLimit(){ return 98304; }
	@Override public ErrorResponse deserializeError(JsonObject body, int status){ return new SponsorRequest.SponsorError(body, status); }
	@Override public void validateAndPostprocessResponse(T value, Response response) throws IOException{
		super.validateAndPostprocessResponse(value, response);
		if(!create && value instanceof Order order && order.orderStr!=null){
			order.orderStr=null;
			throw new ObjectValidationException("Unexpected signed data on read-only payment response");
		}
	}
	public static SponsorPaymentRequest<Options> options(String planId, int version){
		validPlan(planId, version);
		SponsorPaymentRequest<Options> request=new SponsorPaymentRequest<>(HttpMethod.GET, "/payment-options", Options.class, null, false);
		request.addQueryParameter("plan_id", planId); request.addQueryParameter("plan_version", String.valueOf(version)); return request;
	}
	public static SponsorPaymentRequest<Order> create(String planId, int version, String operation){
		validPlan(planId, version);
		if(!validOperation(operation)) throw new IllegalArgumentException("Invalid payment operation");
		SponsorPaymentRequest<Order> request=new SponsorPaymentRequest<>(HttpMethod.POST, "/payment-orders", Order.class,
				Map.of("plan_id", planId, "plan_version", version, "channel", "alipay", "operation_id", operation), true);
		request.addHeader("Idempotency-Key", operation); return request;
	}
	public static SponsorPaymentRequest<CurrentOrder> current(){ return new SponsorPaymentRequest<>(HttpMethod.GET, "/payment-orders/current", CurrentOrder.class, null, false); }
	public static SponsorPaymentRequest<Order> order(String id){ return new SponsorPaymentRequest<>(HttpMethod.GET, orderPath(id), Order.class, null, false); }
	public static SponsorPaymentRequest<Order> close(String id){ return new SponsorPaymentRequest<>(HttpMethod.POST, orderPath(id)+"/close", Order.class, Map.of(), false); }
	public static boolean validOperation(String operation){
		try{ return operation!=null && UUID.fromString(operation).toString().equals(operation); }
		catch(IllegalArgumentException invalid){ return false; }
	}
	private static void validPlan(String id, int version){
		if(id==null || id.isBlank() || id.length()>200 || version<1) throw new IllegalArgumentException("Invalid payment plan");
	}
	private static String orderPath(String id){
		if(!SponsorPaymentModels.validOrderId(id)) throw new IllegalArgumentException("Invalid payment order");
		return "/payment-orders/"+id;
	}
}
