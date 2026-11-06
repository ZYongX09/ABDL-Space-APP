package org.joinmastodon.android.sponsors;

import com.google.gson.JsonObject;

import org.joinmastodon.android.model.sponsors.SponsorModels.*;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels.*;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.joinmastodon.android.sponsors.SandboxFixtures.*;
import static org.junit.Assert.*;

public class SandboxProtocolTest{
	@Test public void originAndCodeAreExactAndHaveNoRuntimeFallback(){
		assertEquals(ORIGIN, SandboxProtocol.requireOrigin(ORIGIN)); assertTrue(SandboxProtocol.validCode(CODE));
		assertTrue(SandboxProtocol.validCode("ABCD_EFGH-IJKL123456"));
		for(String bad:List.of("", "https://abdl-space.top", "http://offline-fixture.trycloudflare.com", ORIGIN+"/", ORIGIN+":443", ORIGIN+"?token=x", "https://x@offline-fixture.trycloudflare.com", "https://127.0.0.1", "https://offline-fixture.trycloudflare.com.evil.test"))
			assertThrows(IllegalArgumentException.class, ()->SandboxProtocol.requireOrigin(bad));
		for(String bad:List.of("123", CODE+"\n", "https://example.test/pair", "has space 123456789")) assertFalse(SandboxProtocol.validCode(bad));
	}
	@Test public void exactPairRequiresSandboxFixedOriginRunUserAndOneHourTtl() throws Exception{
		String text=json(pair()); assertEquals(TOKEN, SandboxProtocol.pair(bytes(text), ORIGIN, NOW).accessToken);
		// Server TTL remains one hour. A phone clock behind by <=120s is tolerated without
		// changing the server-issued expires_at or accepting an already expired token.
		assertEquals(NOW+3600, SandboxProtocol.pair(bytes(text), ORIGIN, NOW-60).expiresAt);
		assertEquals(NOW+3600, SandboxProtocol.pair(bytes(text), ORIGIN, NOW-120).expiresAt);
		assertThrows(IOException.class, ()->SandboxProtocol.pair(bytes(text), ORIGIN, NOW-121));
		for(String bad:List.of(text.replace("sandbox", "production"), text.replace(ORIGIN, "https://other.trycloudflare.com"), text.replace("\"101\"", "101"),
				text.replace(RUN, "not-a-uuid"), text.replace("\"schema_version\":1", "\"schema_version\":\"1\""),
				text.replace("\"expires_at\":"+(NOW+3600), "\"expires_at\":"+(NOW+3721)), text.replace("\"expires_at\":"+(NOW+3600), "\"expires_at\":"+NOW),
				text.substring(0, text.length()-1)+",\"extra\":true}"))
			assertThrows(IOException.class, ()->SandboxProtocol.pair(bytes(bad), ORIGIN, NOW));
	}
	@Test public void sharedTypedModelsAndValidationAcceptOnlyTheOneCentFiniteSandboxPlan() throws Exception{
		Catalog catalog=SandboxProtocol.model(bytes(json(catalog())), Catalog.class, false);
		assertEquals(SandboxProtocol.PLAN, catalog.plans.get(0).id);
		assertEquals(1, SandboxProtocol.model(bytes(json(options())), Options.class, false).plan.priceMinor);
		assertFalse(SandboxProtocol.model(bytes(json(me())), Me.class, false).sponsor.permanent);
		for(String bad:List.of(json(catalog()).replace("\"price_minor\":1", "\"price_minor\":2"),
				json(catalog()).replace("\"duration_unit\":\"day\"", "\"duration_unit\":\"permanent\""),
				json(catalog()).replace("\"purchase_url\":\"\"", "\"purchase_url\":\"https://ifdian.net/order/create\""),
				json(catalog()).replace("\"enabled\":true", "\"enabled\":\"true\"")))
			assertThrows(IOException.class, ()->SandboxProtocol.model(bytes(bad), Catalog.class, false));
		Options options=options(); options.channels.get(1).available=true; options.channels.get(1).reason=null;
		assertThrows(IOException.class, ()->SandboxProtocol.model(bytes(json(options)), Options.class, false));
	}
	@Test public void signedDataIsUnchangedOnCreateButForbiddenOnReads() throws Exception{
		Order order=order(); order.orderStr="offline-fixture-signed-order-unchanged";
		assertEquals(order.orderStr, SandboxProtocol.model(bytes(json(order)), Order.class, true).orderStr);
		assertThrows(IOException.class, ()->SandboxProtocol.model(bytes(json(order)), Order.class, false));
		assertThrows(IOException.class, ()->SandboxProtocol.model(bytes("{\"order\":"+json(order)+"}"), CurrentOrder.class, false));
		Options options=options(); options.activeOrder=order;
		assertThrows(IOException.class, ()->SandboxProtocol.model(bytes(json(options)), Options.class, false));
	}
	@Test public void strictJsonRejectsDuplicateCoercedFractionMissingTrailingAndOversizedData() throws Exception{
		String order=json(order());
		for(String bad:List.of("{\"a\":1,\"a\":2}", "{unquoted:1}", "{/*comment*/\"a\":1}", "{\"a\":NaN}", "{\"a\":1.0}", "{\"a\":1e2}", "{\"a\":9007199254740992}", "{} {}", "{\"a\":1,}"))
			assertThrows(IOException.class, ()->SandboxProtocol.object(bytes(bad), SandboxProtocol.BODY_LIMIT));
		for(String bad:List.of(order.replace("\"amount_minor\":1", "\"amount_minor\":\"1\""),
				order.replace("\"can_switch_channel\":false", "\"can_switch_channel\":0"),
				order.replace("\"plan_version\":1,", ""), order.replace("\"amount_minor\":1", "\"amount_minor\":2147483648"),
				order.substring(0, order.length()-1)+",\"unknown\":1}"))
			assertThrows(IOException.class, ()->SandboxProtocol.model(bytes(bad), Order.class, false));
		assertThrows(IOException.class, ()->SandboxProtocol.object(bytes("{\"x\":\""+"x".repeat(SandboxProtocol.BODY_LIMIT)+"\"}"), SandboxProtocol.BODY_LIMIT));
		assertThrows(IOException.class, ()->SandboxProtocol.object(new byte[]{'{', '"', 'a', '"', ':', '"', (byte)0xff, '"', '}'}, 100));
		JsonObject scalar=new JsonObject(); scalar.addProperty("order", "not-object");
		assertThrows(IOException.class, ()->SandboxProtocol.model(bytes(scalar.toString()), CurrentOrder.class, false));
	}
}
