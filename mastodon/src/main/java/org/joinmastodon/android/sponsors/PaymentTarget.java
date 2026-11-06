package org.joinmastodon.android.sponsors;

import android.os.Bundle;

/**
 * Read-only description of the product a payment flow is for. Nothing here is trusted pricing or
 * entitlement evidence: the coordinator always re-reads the server product before creating an
 * order. Amounts and names are presentation metadata only.
 */
public final class PaymentTarget{
	public static final String SPONSOR="sponsor";
	private static final int MAX_TEXT=200;

	public final String productType, productId, displayName, currency;
	public final int productVersion;
	public final long amountMinor;

	public PaymentTarget(String productType, String productId, int productVersion, String displayName, long amountMinor, String currency){
		this.productType=validType(productType) ? productType : SPONSOR;
		this.productId=productId;
		this.productVersion=Math.max(0, productVersion);
		this.displayName=displayName;
		this.amountMinor=Math.max(0, amountMinor);
		this.currency=currency;
	}

	public static PaymentTarget sponsor(String planId, int version, String name, long amountMinor, String currency){
		return new PaymentTarget(SPONSOR, planId, version, name, amountMinor, currency);
	}

	/** Legacy callers only supplied a sponsor plan id; keep reading their arguments. */
	public static PaymentTarget fromArguments(Bundle args){
		if(args==null) return sponsor(null, 0, null, 0, null);
		String type=args.getString("productType");
		if(type==null) return sponsor(args.getString("plan"), 0, null, 0, null);
		return new PaymentTarget(type, args.getString("productId"), args.getInt("productVersion", 0),
				args.getString("productName"), args.getLong("amountMinor", 0), args.getString("currency"));
	}

	public void writeArguments(Bundle args){
		args.putString("productType", productType);
		args.putString("productId", productId);
		args.putInt("productVersion", productVersion);
		if(displayName!=null) args.putString("productName", displayName);
		args.putLong("amountMinor", amountMinor);
		if(currency!=null) args.putString("currency", currency);
		// Sponsor-era fragments and the Afdian guide still read this key.
		if(SPONSOR.equals(productType) && productId!=null) args.putString("plan", productId);
	}

	public boolean validForCoordinator(){
		return validType(productType) && productId!=null && !productId.isBlank() && productId.length()<=MAX_TEXT;
	}

	public static boolean validType(String value){
		return value!=null && value.matches("[a-z][a-z0-9_]{0,31}");
	}
}
