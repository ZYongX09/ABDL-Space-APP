package org.joinmastodon.android.api;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** Pure JVM time tests: no Android UI, device time zone, network or mutable global clock. */
public class ServiceResourcePauseTimeTest{
	private static final AtomicLong elapsed=new AtomicLong(1000);
	static JsonObject metadata(String server, String reset, String source){
		JsonObject body=new JsonObject(); body.addProperty("code", ServiceResourcePause.CODE);
		if(server!=null) body.addProperty("server_time", server);
		if(reset!=null) body.addProperty("reset_at", reset);
		if(source!=null) body.addProperty("reset_source", source);
		return body;
	}
	private ServiceResourcePause.Timing observe(String now, String zone, JsonObject body){
		return ServiceResourcePause.Timing.observe(body, Clock.fixed(Instant.parse(now), ZoneId.of(zone)), elapsed::get);
	}

	@Test public void fallbackUsesChinaEightBeforeAtAndAfterBoundary(){
		JsonObject body=new JsonObject();
		assertEquals(Instant.parse("2026-11-04T00:00:00Z"), observe("2026-11-03T23:59:59Z", "UTC", body).resetAt);
		assertEquals(Instant.parse("2026-11-05T00:00:00Z"), observe("2026-11-04T00:00:00Z", "UTC", body).resetAt);
		assertEquals(Instant.parse("2026-11-05T00:00:00Z"), observe("2026-11-04T00:00:01Z", "UTC", body).resetAt);
	}
	@Test public void fallbackIsIndependentOfUtcUsTokyoDeviceZone(){
		for(String zone:new String[]{"UTC", "America/Los_Angeles", "Asia/Tokyo", "Pacific/Kiritimati"}){
			assertEquals(Instant.parse("2026-11-04T00:00:00Z"), observe("2026-11-03T16:00:00Z", zone, new JsonObject()).resetAt);
			assertEquals(Instant.parse("2026-11-04T00:00:00Z"), observe("2026-11-03T15:59:59Z", zone, new JsonObject()).resetAt);
		}
	}
	@Test public void monthYearAndLeapBoundariesDoNotUseDayOfMonthArithmetic(){
		assertEquals(Instant.parse("2027-01-01T00:00:00Z"), observe("2026-12-31T15:59:59Z", "UTC", new JsonObject()).resetAt);
		assertEquals(Instant.parse("2026-12-01T00:00:00Z"), observe("2026-11-30T16:00:00Z", "Asia/Tokyo", new JsonObject()).resetAt);
		assertEquals(Instant.parse("2028-03-01T00:00:00Z"), observe("2028-02-29T16:00:00Z", "America/Los_Angeles", new JsonObject()).resetAt);
	}
	@Test public void serverClockSkewUsesElapsedAnchorAndNotDeviceWallClock(){
		AtomicLong monotonic=new AtomicLong(9000);
		ServiceResourcePause.Timing timing=ServiceResourcePause.Timing.observe(metadata("2026-11-03T15:59:59Z", "2026-11-04T00:00:00Z", "estimated_utc_midnight"),
				Clock.fixed(Instant.parse("2036-01-01T00:00:00Z"), ZoneOffset.UTC), monotonic::get);
		assertTrue(timing.serverClock);
		assertEquals(Instant.parse("2026-11-03T15:59:59Z"), timing.now());
		assertEquals(1001, timing.nextChangeMillis()); // China midnight, not the reset eight hours later.
		monotonic.addAndGet(2000);
		assertEquals(Instant.parse("2026-11-03T16:00:01Z"), timing.now());
		assertEquals(Instant.parse("2026-11-04T00:00:00Z"), timing.resetAt);
		monotonic.addAndGet(8*60*60*1000);
		assertEquals(Long.MAX_VALUE, timing.nextChangeMillis());
		assertEquals(Instant.parse("2026-11-04T00:00:00Z"), timing.resetAt); // Never wraps the old error to tomorrow.
	}
	@Test public void trustedUpstreamMayUseActualNonEightClock(){
		ServiceResourcePause.Timing timing=observe("2036-01-01T00:00:00Z", "UTC", metadata("2026-11-03T15:59:59Z", "2026-11-03T17:45:12Z", "upstream"));
		assertTrue(timing.serverClock);
		assertEquals(Instant.parse("2026-11-03T17:45:12Z"), timing.resetAt);
	}
	@Test public void responseAfterResetKeepsExpiredTargetInsteadOfFabricatingNextDay(){
		ServiceResourcePause.Timing timing=observe("2036-01-01T00:00:00Z", "UTC", metadata("2026-11-04T00:00:01Z", "2026-11-04T00:00:00Z", "estimated_utc_midnight"));
		assertTrue(timing.serverClock);
		assertEquals(Instant.parse("2026-11-04T00:00:00Z"), timing.resetAt);
		assertEquals(Long.MAX_VALUE, timing.nextChangeMillis());
	}
	@Test public void invalidMissingAndForgedTimeMetadataFallsBackToReceiveDayEight(){
		JsonObject[] bodies={
				metadata(null, "2026-11-04T00:00:00Z", "upstream"),
				metadata("2026-11-03T16:00:00Z", null, "upstream"),
				metadata("2026-11-03T16:00:00Z", "2026-11-04T00:00:00Z", null),
				metadata("2026-11-03T16:00:00Z", "2026-11-04T00:00:00Z", "actual"),
				metadata("2026-11-03T16:00:00Z", "2026-11-06T00:00:00Z", "upstream"),
				metadata("2026-11-03T16:00:00Z", "2026-10-01T00:00:00Z", "upstream"),
				metadata("2026-11-03T16:00:00Z", "2026-11-03T17:45:00Z", "estimated_utc_midnight"),
				metadata("2026-11-03T16:00:00Z", "1793750400000", "upstream"),
				metadata("2026-11-03T16:00:00Z", "9999999999999999999999999999", "upstream"),
				metadata("2026-11-03T16:00:00Z", "2026-11-04T08:00:00+08:00", "upstream"),
				metadata("not ISO", "2026-11-04T00:00:00Z", "upstream"),
				metadata("2026-11-03T16:00:00Z", "2026-13-04T00:00:00Z", "upstream")
		};
		for(JsonObject body:bodies){
			ServiceResourcePause.Timing timing=observe("2026-11-05T01:00:00Z", "America/Los_Angeles", body);
			assertFalse(body.toString(), timing.serverClock);
			assertEquals(body.toString(), Instant.parse("2026-11-06T00:00:00Z"), timing.resetAt);
		}
	}
	@Test public void jsonTimeTypesNeverCoerceNumericBooleanArraysOrNull(){
		for(String field:new String[]{"server_time", "reset_at", "reset_source"}){
			for(com.google.gson.JsonElement invalid:new com.google.gson.JsonElement[]{new com.google.gson.JsonPrimitive(1793750400L), new com.google.gson.JsonPrimitive(true), new com.google.gson.JsonArray(), new JsonObject(), com.google.gson.JsonNull.INSTANCE}){
				JsonObject body=metadata("2026-11-03T16:00:00Z", "2026-11-04T00:00:00Z", "estimated_utc_midnight"); body.add(field, invalid);
				ServiceResourcePause.Timing timing=observe("2026-11-05T01:00:00Z", "UTC", body);
				assertFalse(field+invalid, timing.serverClock);
				assertEquals(Instant.parse("2026-11-06T00:00:00Z"), timing.resetAt);
			}
		}
	}
}
