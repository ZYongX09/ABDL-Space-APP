package org.joinmastodon.android.sponsors;

import org.joinmastodon.android.model.sponsors.SponsorModels.*;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Offline test data only. No production default, entitlement override, or real merchant config. */
public final class SponsorPaymentFixtures{
	private SponsorPaymentFixtures(){}
	public static Plan plan(){
		Plan p=new Plan(); p.id="fixture-month"; p.name="月度赞助 / Monthly sponsor"; p.version=7;
		p.currency="CNY"; p.priceMinor=590; p.durationUnit="month"; p.durationCount=1; p.enabled=true;
		p.afdianPlanId="fixture-afdian-plan"; p.afdianSkuId="fixture-afdian-sku";
		String sku=URLEncoder.encode("[{\"sku_id\":\"fixture-afdian-sku\",\"count\":1}]", StandardCharsets.UTF_8);
		p.purchaseUrl="https://ifdian.net/order/create?product_type=1&plan_id=fixture-afdian-plan&sku="+sku+"&viokrz_ex=0";
		return p;
	}
	public static Catalog catalog(){
		Catalog c=new Catalog(); c.plans=List.of(plan()); c.config=new Config();
		c.config.enabled=true; c.config.version=4; c.config.noticeVersion=1; c.config.minimumReadSeconds=5;
		c.config.freeDailyLimit=5; c.config.sponsorDailyLimit=30; c.config.timezone="Asia/Shanghai";
		c.config.centerTitle="Fixture sponsor"; c.config.purchaseTitle="Fixture Afdian guide";
		c.config.purchaseSteps=List.of("Fixture checkout instructions"); c.config.colors=List.of(); c.config.benefits=List.of();
		c.config.noticeTitle="{x}"; c.config.noticeBody="{y}"; c.config.exhaustedTitle="{a}";
		c.config.exhaustedBody="{reset}"; c.config.sponsorExhaustedBody="{y}";
		return c;
	}
	public static Me me(boolean active){
		Me m=new Me(); m.configVersion=4; m.claimedBenefitIds=List.of(); m.sponsor=new Sponsor();
		m.sponsor.active=active; m.sponsor.expiresAt=System.currentTimeMillis()/1000+86400;
		m.quota=new Quota(); m.quota.limit=active ? 30 : 5; m.quota.used=0; m.quota.remaining=m.quota.limit;
		m.quota.resetsAt=System.currentTimeMillis()/1000+86400; m.quota.dayKey="2026-11-05"; return m;
	}
	public static Options options(String reason){
		Options o=new Options(); o.schemaVersion=1; o.plan=plan();
		Channel alipay=new Channel(); alipay.id="alipay"; alipay.available=reason==null; alipay.reason=reason;
		Channel afdian=new Channel(); afdian.id="afdian"; afdian.available=true;
		o.channels=List.of(alipay, afdian); return o;
	}
	public static Order order(String state, String fulfillment){
		Order o=new Order(); o.orderId="fixture-order-01"; o.planId=plan().id; o.planVersion=7;
		o.currency="CNY"; o.amountMinor=590; o.expiresAt=System.currentTimeMillis()/1000+600;
		o.paymentState=state; o.fulfillmentState=fulfillment; o.canSwitchChannel="CLOSED".equals(state); return o;
	}
	public static CurrentOrder current(Order order){ CurrentOrder current=new CurrentOrder(); current.order=order; return current; }
}
