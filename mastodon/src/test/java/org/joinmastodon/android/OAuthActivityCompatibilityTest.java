package org.joinmastodon.android;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.accounts.GetOwnAccount;
import org.joinmastodon.android.api.requests.oauth.GetOauthToken;
import org.joinmastodon.android.api.session.AccountActivationInfo;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Application;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.InstanceV2;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.security.AppSecurity;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowActivity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.utils.V;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,27,28}, application=CompatibilityTestApplication.class,
		shadows={OAuthActivityCompatibilityTest.LocalRequests.class,OAuthActivityCompatibilityTest.LocalSessions.class,
				OAuthActivityCompatibilityTest.UnlockedGate.class})
public class OAuthActivityCompatibilityTest{
	@Implements(MastodonAPIRequest.class)
	public static class LocalRequests{
		@RealObject MastodonAPIRequest<?> request;
		static final List<MastodonAPIRequest<?>> sent=new ArrayList<>();
		static final List<String> domains=new ArrayList<>();
		@Implementation public MastodonAPIRequest<?> execNoAuth(String domain){ sent.add(request); domains.add(domain); return request; }
		@Implementation public MastodonAPIRequest<?> exec(String domain,Token token){ sent.add(request); domains.add(domain); return request; }
	}

	@Implements(AccountSessionManager.class)
	public static class LocalSessions{
		static Instance authenticatingInstance,addedInstance;
		static Application authenticatingApp,addedApp;
		static Token addedToken;
		static Account addedAccount;
		static int additions;
		@Implementation public Instance getAuthenticatingInstance(){ return authenticatingInstance; }
		@Implementation public Application getAuthenticatingApp(){ return authenticatingApp; }
		@Implementation public void addAccount(Instance instance,Token token,Account account,Application app,AccountActivationInfo activation){
			assertNotNull("Resolved instance must survive the callback",instance);
			assertNotNull("Resolved application must survive the callback",app);
			addedInstance=instance; addedApp=app; addedToken=token; addedAccount=account; additions++;
		}
	}

	@Implements(AppSecurity.class)
	public static class UnlockedGate{
		@Implementation public static void runAfterUnlock(Activity activity,Runnable continuation){ continuation.run(); }
	}

	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication();
		V.setApplicationContext(MastodonApp.context);
		GlobalUserPreferences.getPrefs().edit().clear().putBoolean("perAccountMigrationDone",true).commit();
		GlobalUserPreferences.load();
		LocalRequests.sent.clear(); LocalRequests.domains.clear();
		LocalSessions.authenticatingInstance=LocalSessions.addedInstance=null;
		LocalSessions.authenticatingApp=LocalSessions.addedApp=null;
		LocalSessions.addedAccount=null; LocalSessions.addedToken=null; LocalSessions.additions=0;
	}

	@Test public void successfulCallbackUsesResolvedFallbackAfterTransientContextIsLost() throws Exception{
		completeCallback(false);
	}

	@Test public void successfulCallbackKeepsExistingAuthenticationContext() throws Exception{
		completeCallback(true);
	}

	private void completeCallback(boolean warm) throws Exception{
		if(warm){
			InstanceV2 instance=new InstanceV2(); instance.domain="warm.offline.example.test";
			LocalSessions.authenticatingInstance=instance;
			Application app=new Application(); app.clientId="offline-client"; app.clientSecret="offline-secret";
			LocalSessions.authenticatingApp=app;
		}
		Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse("abdl-space://callback?code=offline-code"));
		try(ActivityController<OAuthActivity> controller=Robolectric.buildActivity(OAuthActivity.class,intent).create()){
			OAuthActivity activity=controller.get();
			assertEquals(1,LocalRequests.sent.size());
			assertTrue(LocalRequests.sent.get(0) instanceof GetOauthToken);
			String domain=warm ? "warm.offline.example.test" : "abdl-space.top";
			assertEquals("api."+domain,LocalRequests.domains.get(0));
			Token token=new Token(); token.accessToken="offline-token";
			callback(LocalRequests.sent.get(0)).onSuccess(token);
			assertEquals(2,LocalRequests.sent.size());
			assertTrue(LocalRequests.sent.get(1) instanceof GetOwnAccount);
			assertEquals(domain,LocalRequests.domains.get(1));
			Account account=new Account(); account.id="42";
			callback(LocalRequests.sent.get(1)).onSuccess(account);
			assertEquals(1,LocalSessions.additions);
			assertEquals(domain,LocalSessions.addedInstance.getDomain());
			assertSame(token,LocalSessions.addedToken); assertSame(account,LocalSessions.addedAccount);
			if(warm){
				assertSame(LocalSessions.authenticatingInstance,LocalSessions.addedInstance);
				assertSame(LocalSessions.authenticatingApp,LocalSessions.addedApp);
			}
			ShadowActivity shadow=org.robolectric.shadow.api.Shadow.extract(activity);
			Intent next=shadow.getNextStartedActivity();
			assertNotNull(next); assertEquals(MainActivity.class.getName(),next.getComponent().getClassName());
			assertNull(shadow.getNextStartedActivity());
			assertTrue(activity.isFinishing());
		}
	}

	@SuppressWarnings("unchecked") private static Callback<Object> callback(MastodonAPIRequest<?> request) throws Exception{
		Field field=APIRequest.class.getDeclaredField("callback"); field.setAccessible(true);
		return (Callback<Object>)field.get(request);
	}
}
