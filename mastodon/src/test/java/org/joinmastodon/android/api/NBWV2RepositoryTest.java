package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.requests.nbw.NBWAppRequest;
import org.joinmastodon.android.api.requests.statuses.CreateStatus;
import org.joinmastodon.android.api.session.NBWForumOptionsRepository;
import org.joinmastodon.android.model.NBWCapabilities;
import org.joinmastodon.android.model.NBWForumOptions;
import org.joinmastodon.android.model.NBWSelection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import okio.Buffer;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
public class NBWV2RepositoryTest{
	private long now;
	private int cleared;
	private FakeTransport transport;
	private NBWForumOptionsRepository repository;
	private NBWSelection stored=NBWSelection.ai();

	private static class Pending<T> implements NBWForumOptionsRepository.Cancelable{
		final NBWForumOptionsRepository.Result<T> callback; boolean canceled;
		Pending(NBWForumOptionsRepository.Result<T> callback){ this.callback=callback; }
		public void cancel(){ canceled=true; }
	}
	private static class FakeTransport implements NBWForumOptionsRepository.Transport{
		final List<Pending<NBWCapabilities>> caps=new ArrayList<>();
		final List<Pending<NBWForumOptions>> options=new ArrayList<>();
		final List<Pending<Object>> consents=new ArrayList<>();
		final List<Boolean> accepted=new ArrayList<>();
		public NBWForumOptionsRepository.Cancelable capabilities(NBWForumOptionsRepository.Result<NBWCapabilities> r){ var p=new Pending<>(r); caps.add(p); return p; }
		public NBWForumOptionsRepository.Cancelable options(NBWForumOptionsRepository.Result<NBWForumOptions> r){ var p=new Pending<>(r); options.add(p); return p; }
		public NBWForumOptionsRepository.Cancelable consent(boolean value, NBWForumOptionsRepository.Result<Object> r){ var p=new Pending<>(r); accepted.add(value); consents.add(p); return p; }
	}
	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication();
		me.grishka.appkit.utils.V.setApplicationContext(MastodonApp.context);
		transport=new FakeTransport();
		repository=new NBWForumOptionsRepository(transport, ()->now, new NBWForumOptionsRepository.SelectionStore(){
			public NBWSelection load(){ return stored; }
			public void save(NBWSelection value){ stored=value; }
		}, ()->cleared++, Runnable::run);
	}
	public static NBWCapabilities capabilities(String mode){
		NBWCapabilities c=new NBWCapabilities(); c.schemaVersion=1; c.remoteMode=mode; c.reason="test"; c.policyRevision="policy-a";
		c.binding=new NBWCapabilities.Binding(); c.binding.state="unbound";
		c.authorization=new NBWCapabilities.Authorization(); c.authorization.state="missing"; c.authorization.lifetimePolicy="non_expiring";
		c.fallback=new NBWCapabilities.Fallback(); c.fallback.fid=18; c.fallback.policyVersion=1; c.fallback.consented=false;
		return c;
	}
	public static NBWForumOptions options(){
		NBWForumOptions result=new NBWForumOptions(); result.schemaVersion=1; result.configRevision="tree-a";
		result.fallback=new NBWForumOptions.Fallback(); result.fallback.fid=18; result.fallback.policyVersion=1;
		NBWForumOptions.Forum chat=new NBWForumOptions.Forum(); chat.fid=18; chat.name="闲聊"; chat.required=false; chat.types=List.of();
		NBWForumOptions.Forum share=new NBWForumOptions.Forum(); share.fid=27; share.name="分享"; share.required=true;
		NBWForumOptions.Type type=new NBWForumOptions.Type(); type.typeid=41; type.name="经验分享"; type.moderatorOnly=false;
		NBWForumOptions.Type moderator=new NBWForumOptions.Type(); moderator.typeid=42; moderator.name="管理"; moderator.moderatorOnly=true;
		share.types=List.of(type, moderator); result.forums=List.of(chat, share); return result;
	}
	private void ready(){ repository.activate(); transport.caps.get(0).callback.success(capabilities("full")); transport.options.get(0).callback.success(options()); }

	@Test public void startupPrefetchIsSingleFlightAndEditorsOnlyConsumeSharedSnapshot(){
		repository.activate(); repository.activate(); repository.refreshIfExpired();
		assertEquals(1, transport.caps.size()); assertEquals(0, transport.options.size());
		transport.caps.get(0).callback.success(capabilities("full"));
		assertEquals(1, transport.options.size()); transport.options.get(0).callback.success(options());
		for(int i=0;i<20;i++){ var observer=repository.observe(value->assertNotNull(value)); observer.cancel(); repository.getSnapshot(); }
		assertEquals(1, transport.caps.size()); assertEquals(1, transport.options.size());
		now=NBWForumOptionsRepository.TTL_MS-1; repository.refreshIfExpired(); assertEquals(1, transport.caps.size());
		now++; repository.refreshIfExpired(); repository.refreshIfExpired(); assertEquals(2, transport.caps.size());
		transport.caps.get(1).callback.success(capabilities("full")); assertEquals(2, transport.options.size());
	}
	@Test public void absentMalformedOrTimeoutMetadataFailsClosedWithoutInferringBinding(){
		repository.activate(); transport.caps.get(0).callback.failure();
		assertEquals(NBWForumOptionsRepository.State.LOCAL_ONLY, repository.getSnapshot().state); assertFalse(repository.getSnapshot().canRead());
		repository.refreshNow(); NBWCapabilities bad=capabilities("full"); bad.authorization.state="future-state";
		transport.caps.get(1).callback.success(bad); assertFalse(repository.getSnapshot().canWrite()); assertEquals(0, transport.options.size());
		repository.refreshNow(); transport.caps.get(2).callback.success(capabilities("full"));
		NBWForumOptions invalid=options(); invalid.schemaVersion=99; transport.options.get(0).callback.success(invalid);
		assertEquals(NBWForumOptionsRepository.State.LOCAL_ONLY, repository.getSnapshot().state);
	}
	@Test public void localOnlyCancelsRemoteRequestsAndDiscardsLateAccountGeneration(){
		ready(); boolean[] canceled={false}; long old=repository.registerRemote(()->canceled[0]=true, true);
		repository.refreshNow(); transport.caps.get(1).callback.success(capabilities("local_only"));
		assertTrue(canceled[0]); assertFalse(repository.isCurrentRemote(old)); assertNull(repository.getSnapshot().options);
		assertFalse(repository.getSnapshot().canRead()); assertTrue(cleared>0);
		repository.refreshNow(); Pending<NBWCapabilities> late=transport.caps.get(2); repository.deactivate();
		late.callback.success(capabilities("full")); assertFalse(repository.getSnapshot().canRead());
		repository.activate(); assertEquals(4, transport.caps.size());
		// Mode recovery fetches metadata only, never retries an old remote operation or historical intent.
		assertEquals(1, transport.options.size()); assertTrue(late.canceled);
	}
	@Test public void readOnlyAllowsReadsButNeverWriteOrConsent(){
		repository.activate(); transport.caps.get(0).callback.success(capabilities("read_only")); transport.options.get(0).callback.success(options());
		assertTrue(repository.getSnapshot().canRead()); assertFalse(repository.getSnapshot().canWrite());
		assertEquals(-1, repository.registerRemote(()->{}, true)); assertTrue(repository.registerRemote(()->{}, false)>0);
		repository.setFallbackConsent(true, ok->assertFalse(ok)); assertEquals(0, transport.consents.size());
	}
	@Test public void consentIsNeverImplicitAndSuccessfulPutMustReReadAuthenticatedState(){
		ready(); assertFalse(repository.getSnapshot().hasFallbackConsent()); assertTrue(transport.consents.isEmpty());
		repository.select(NBWSelection.ai()); assertTrue(transport.consents.isEmpty());
		repository.setFallbackConsent(false, ok->assertTrue(ok)); assertEquals(List.of(false), transport.accepted);
		transport.consents.get(0).callback.success(new Object()); assertFalse(repository.getSnapshot().hasFallbackConsent());
		transport.caps.get(1).callback.success(capabilities("full"));
		repository.setFallbackConsent(true, ok->assertTrue(ok)); transport.consents.get(1).callback.success(new Object());
		assertFalse(repository.getSnapshot().hasFallbackConsent());
		NBWCapabilities accepted=capabilities("full"); accepted.fallback.consented=true; transport.caps.get(2).callback.success(accepted);
		assertTrue(repository.getSnapshot().hasFallbackConsent()); assertEquals(List.of(false, true), transport.accepted);
	}
	@Test public void selectionValidatesCompletePairAndNeverRewritesAnInvalidManualChoice(){
		ready(); assertEquals(NBWSelection.ai(), repository.getSnapshot().selection);
		assertTrue(repository.select(NBWSelection.manual(27,41)));
		assertFalse(repository.select(NBWSelection.manual(18,41))); assertFalse(repository.select(NBWSelection.manual(27,42)));
		assertFalse(repository.select(NBWSelection.manual(27,0))); assertTrue(options().canFallback());
		assertEquals(NBWSelection.manual(27,41), stored);
		now=NBWForumOptionsRepository.TTL_MS; repository.refreshIfExpired(); transport.caps.get(1).callback.success(capabilities("full"));
		NBWForumOptions newer=options(); newer.forums=List.of(newer.forums.get(0)); newer.configRevision="tree-b";
		transport.options.get(1).callback.success(newer);
		assertEquals(NBWSelection.manual(27,41), repository.getSnapshot().selection);
		assertFalse(repository.getSnapshot().selection.isLegal(repository.getSnapshot().options));
	}
	@Test public void requestsUseIndependentApiAppAndConsentContractOnly() throws Exception{
		assertEquals("/api/app/capabilities", NBWAppRequest.capabilities().getURL().getPath());
		assertEquals("/api/app/nbw/options", NBWAppRequest.options().getURL().getPath());
		assertEquals("/api/app/nbw/authorization/start", NBWAppRequest.authorizationStart().getURL().getPath());
		var request=NBWAppRequest.fallbackConsent(true); Buffer buffer=new Buffer(); request.getRequestBody().writeTo(buffer);
		var json=com.google.gson.JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
		assertEquals("PUT", request.getMethod()); assertEquals(2, json.size()); assertTrue(json.get("accepted").getAsBoolean()); assertEquals(1,json.get("policy_version").getAsInt());
		// Inspect real constructed requests, read-only: a comment about another wire test
		// cannot substitute for the protocol-header contract asserted here.
		var headersField=MastodonAPIRequest.class.getDeclaredField("headers");
		headersField.setAccessible(true);
		for(var appRequest:List.of(NBWAppRequest.capabilities(), NBWAppRequest.options(),
				NBWAppRequest.authorizationStart(), request, NBWAppRequest.fallbackConsent(false))){
			var headers=(java.util.Map<?, ?>)headersField.get(appRequest);
			assertNotNull(appRequest.getURL().getPath(), headers);
			assertEquals(appRequest.getURL().getPath(), "2", headers.get("X-ABDL-NBW-Protocol"));
			assertTrue(appRequest.isSensitiveRequest());
		}
		CreateStatus.Request post=new CreateStatus.Request(); post.setNBWSelection(NBWSelection.manual(27,41));
		assertEquals("manual",post.toJson().get("nbw_classification").getAsString()); assertEquals(41,post.toJson().get("nbw_typeid").getAsInt());
		post.setNBWSelection(NBWSelection.ai()); assertFalse(post.toJson().has("nbw_fid")); assertFalse(post.toJson().has("nbw_typeid"));
	}
}
