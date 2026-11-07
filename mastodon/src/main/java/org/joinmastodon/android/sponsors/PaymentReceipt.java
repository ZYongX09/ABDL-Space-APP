package org.joinmastodon.android.sponsors;

/** Read-only receipt data for the payment result page; never a trust input or SDK result. */
public final class PaymentReceipt implements java.io.Serializable{
	public final String orderId, productName, currency, channel;
	public final long amountMinor, paidAt;
	public final String tradeNo;

	public PaymentReceipt(String orderId, long amountMinor, String currency, String productName, String channel, String tradeNo, long paidAt){
		this.orderId=orderId;
		this.amountMinor=amountMinor;
		this.currency=currency;
		this.productName=productName;
		this.channel=channel;
		this.tradeNo=tradeNo;
		this.paidAt=paidAt;
	}
}
