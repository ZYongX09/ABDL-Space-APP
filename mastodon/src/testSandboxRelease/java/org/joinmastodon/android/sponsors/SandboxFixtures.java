package org.joinmastodon.android.sponsors;

import org.joinmastodon.android.model.sponsors.SponsorModels.*;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Synthetic offline fixtures only, not a real code/token, wallet or merchant configuration. */
final class SandboxFixtures{
	static final String ORIGIN="https://offline-fixture.trycloudflare.com";
	static final String RUN="65adffb9-98bf-46b0-9fca-59a3bc6afdd0";
	static final String OTHER_RUN="85adffb9-98bf-46b0-9fca-59a3bc6afdd0";
	static final String OPERATION="75adffb9-98bf-46b0-9fca-59a3bc6afdd0";
	static final String TOKEN="offline_fixture_only_token_0123456789_ABCDE";
	static final String CODE="Offline_Only-Code_2026";
	static final long NOW=1793944800L;
	static SandboxProtocol.Pair pair(){
		SandboxProtocol.Pair pair=new SandboxProtocol.Pair(); pair.schemaVersion=1; pair.environment="sandbox";
		pair.origin=ORIGIN; pair.runId=RUN; pair.userId="101"; pair.accessToken=TOKEN; pair.expiresAt=NOW+3600;
		return pair;
	}
	static Plan plan(){
		Plan plan=new Plan(); plan.id=SandboxProtocol.PLAN; plan.version=1; plan.name="沙箱测试 Sandbox";
		plan.currency="CNY"; plan.priceMinor=1; plan.durationUnit="day"; plan.durationCount=1; plan.enabled=true;
		plan.purchaseUrl=""; plan.afdianPlanId=""; plan.afdianSkuId=""; plan.description="仅本地测试权益";
		return plan;
	}
	static Catalog catalog(){
		Catalog catalog=SponsorPaymentFixtures.catalog(); catalog.plans=List.of(plan()); return catalog;
	}
	static Me me(){ return SponsorPaymentFixtures.me(false); }
	static Options options(){
		Options options=SponsorPaymentFixtures.options(null); options.plan=plan();
		options.channels.get(1).available=false; options.channels.get(1).reason="disabled"; return options;
	}
	static Order order(){
		Order order=SponsorPaymentFixtures.order("PENDING_PAYMENT", "PENDING");
		order.planId=SandboxProtocol.PLAN; order.planVersion=1; order.amountMinor=1; return order;
	}
	static byte[] bytes(String text){ return text.getBytes(StandardCharsets.UTF_8); }
	static String json(Object value){ return SandboxProtocol.JSON.toJson(value); }
}
