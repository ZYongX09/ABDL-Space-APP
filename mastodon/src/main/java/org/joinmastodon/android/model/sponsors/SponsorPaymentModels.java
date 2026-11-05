package org.joinmastodon.android.model.sponsors;

import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.model.BaseModel;
import org.joinmastodon.android.model.sponsors.SponsorModels.Me;
import org.joinmastodon.android.model.sponsors.SponsorModels.Plan;

import java.util.List;
import java.util.Set;

/** Separate payment projections. Signed SDK data is allowed only in a create response. */
public final class SponsorPaymentModels{
	private SponsorPaymentModels(){}
	public static final String ALIPAY="alipay", AFDIAN="afdian";
	public static final long MAX_SAFE_INTEGER=9007199254740991L;

	public static class Channel{
		public String id, reason;
		public Boolean available;
	}
	public static class Options extends BaseModel{
		public int schemaVersion;
		public Plan plan;
		public List<Channel> channels;
		public Order activeOrder;
		@Override public void postprocess() throws ObjectValidationException{
			if(schemaVersion!=1 || !validPlan(plan) || channels==null || channels.size()!=2) invalid();
			for(int i=0;i<2;i++){
				Channel channel=channels.get(i);
				if(channel==null || !(i==0 ? ALIPAY : AFDIAN).equals(channel.id) || channel.available==null
						|| Boolean.TRUE.equals(channel.available) && channel.reason!=null
						|| !Boolean.TRUE.equals(channel.available) && (channel.reason==null || !channel.reason.matches("[a-z][a-z0-9_]{0,79}"))) invalid();
			}
			if(activeOrder!=null){ activeOrder.postprocess(); if(activeOrder.orderStr!=null) invalid(); }
		}
		public Channel channel(String id){ return channels==null ? null : channels.stream().filter(c->id.equals(c.id)).findFirst().orElse(null); }
	}
	public static class CurrentOrder extends BaseModel{
		public Order order;
		@Override public void postprocess() throws ObjectValidationException{
			if(order!=null){ order.postprocess(); if(order.orderStr!=null) invalid(); }
		}
	}
	public static class Order extends BaseModel{
		public String orderId, planId, currency, paymentState, fulfillmentState;
		public int planVersion;
		public long amountMinor, expiresAt;
		public Boolean canSwitchChannel;
		/** Never put this field in saved state, preferences, analytics or logs. */
		public String orderStr;
		public Me me;
		@Override public void postprocess() throws ObjectValidationException{
			if(!validOrderId(orderId) || planId==null || planId.isBlank() || planId.length()>200 || planVersion<1
					|| amountMinor<1 || amountMinor>MAX_SAFE_INTEGER || !"CNY".equals(currency) || expiresAt<=0
					|| !Set.of("PENDING_PAYMENT", "PAID", "CLOSED", "UNKNOWN").contains(nonNull(paymentState))
					|| !Set.of("PENDING", "GRANTED", "MANUAL_REVIEW", "REFUND_REVIEW").contains(nonNull(fulfillmentState))
					|| canSwitchChannel==null || Boolean.TRUE.equals(canSwitchChannel) && !"CLOSED".equals(paymentState)
					|| "GRANTED".equals(fulfillmentState) && !"PAID".equals(paymentState)
					|| orderStr!=null && (!"PENDING_PAYMENT".equals(paymentState) || !"PENDING".equals(fulfillmentState) || orderStr.isBlank() || orderStr.length()>65536)
					|| me!=null && !"GRANTED".equals(fulfillmentState)) invalid();
			if(me!=null) me.postprocess();
		}
	}
	public static boolean validOrderId(String id){ return id!=null && id.matches("[A-Za-z0-9_~.-]{1,200}") && !id.equals(".") && !id.equals(".."); }
	public static boolean validPlan(Plan plan){
		return plan!=null && plan.id!=null && !plan.id.isBlank() && plan.id.length()<=200 && plan.version>0
				&& plan.name!=null && !plan.name.isBlank() && plan.priceMinor>0 && "CNY".equals(plan.currency)
				&& Set.of("day", "month", "permanent").contains(nonNull(plan.durationUnit))
				&& ("permanent".equals(plan.durationUnit) ? plan.durationCount==0 : plan.durationCount>0);
	}
	public static boolean samePlan(Plan a, Plan b){
		return validPlan(a) && validPlan(b) && a.id.equals(b.id) && a.version==b.version && a.priceMinor==b.priceMinor
				&& a.currency.equals(b.currency) && a.durationUnit.equals(b.durationUnit) && a.durationCount==b.durationCount
				&& a.name.equals(b.name) && a.enabled==b.enabled;
	}
	private static String nonNull(String value){ return value==null ? "" : value; }
	private static void invalid() throws ObjectValidationException{ throw new ObjectValidationException("Invalid sponsor payment response"); }
}
