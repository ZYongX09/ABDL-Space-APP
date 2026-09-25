package org.joinmastodon.android.sponsors;

import org.joinmastodon.android.model.sponsors.SponsorModels.Plan;
import org.junit.Test;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class SponsorPurchaseSafetyTest{
	private Plan plan(){
		Plan plan=new Plan(); plan.enabled=true; plan.afdianPlanId="server-plan"; plan.afdianSkuId="server-sku";
		plan.purchaseUrl=url("[{\"sku_id\":\"server-sku\",\"count\":1}]"); return plan;
	}
	private String url(String sku){
		return "https://ifdian.net/order/create?product_type=1&plan_id=server-plan&sku="+URLEncoder.encode(sku, StandardCharsets.UTF_8)+"&viokrz_ex=0";
	}
	@Test public void acceptsOnlyOfficialMappedCheckout(){ assertTrue(SponsorPurchaseSafety.valid(plan())); }
	@Test public void rejectsDisabledMissingOrChangedSku(){
		assertFalse(SponsorPurchaseSafety.valid(null));
		Plan plan=plan(); plan.enabled=false; assertFalse(SponsorPurchaseSafety.valid(plan));
		plan=plan(); plan.afdianSkuId="different"; assertFalse(SponsorPurchaseSafety.valid(plan));
		plan=plan(); plan.afdianPlanId="different"; assertFalse(SponsorPurchaseSafety.valid(plan));
	}
	@Test public void rejectsSchemesHostsCredentialsAndFragments(){
		for(String prefix:new String[]{"http://ifdian.net", "https://ifdian.net.evil.test", "https://ifdian.net@evil.test", "https://user@ifdian.net", "https://ifdian.net:444"}){
			Plan plan=plan(); plan.purchaseUrl=plan.purchaseUrl.replace("https://ifdian.net", prefix); assertFalse(prefix, SponsorPurchaseSafety.valid(plan));
		}
		Plan plan=plan(); plan.purchaseUrl+="#redirect"; assertFalse(SponsorPurchaseSafety.valid(plan));
	}
	@Test public void rejectsExtraDuplicateAndRedirectParameters(){
		for(String suffix:new String[]{"&redirect=https%3A%2F%2Fevil.test", "&plan_id=server-plan", "&%70lan_id=server-plan", "&unknown=1"}){
			Plan plan=plan(); plan.purchaseUrl+=suffix; assertFalse(SponsorPurchaseSafety.valid(plan));
		}
	}
	@Test public void rejectsCoercedCountsAndAdditionalSku(){
		for(String sku:new String[]{"[{\"sku_id\":\"server-sku\",\"count\":1.5}]", "[{\"sku_id\":\"server-sku\",\"count\":\"1\"}]",
				"[{\"sku_id\":\"server-sku\",\"count\":2}]", "[]", "[{\"sku_id\":\"server-sku\",\"count\":1,\"url\":\"evil\"}]",
				"[{\"sku_id\":\"server-sku\",\"count\":1},{\"sku_id\":\"server-sku\",\"count\":1}]"}){
			Plan plan=plan(); plan.purchaseUrl=url(sku); assertFalse(sku, SponsorPurchaseSafety.valid(plan));
		}
	}
	@Test public void rejectsMissingMalformedAndWrongPath(){
		for(String url:new String[]{null, "", "https://ifdian.net/order/create", "https://ifdian.net/order/create?sku=%", "https://ifdian.net/other"}){
			Plan plan=plan(); plan.purchaseUrl=url; assertFalse(SponsorPurchaseSafety.valid(plan));
		}
	}
}
