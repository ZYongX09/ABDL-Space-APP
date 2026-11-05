package org.joinmastodon.android;

import android.app.Activity;
import android.app.ProgressDialog;

import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.auth.IndependentAuthResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okio.Timeout;

import static org.junit.Assert.*;

/** The exchange owner guard, without launching the QQ SDK or sending a request. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, shadows=QQPauseOwnerTest.Sessions.class)
public class QQPauseOwnerTest{
	@Implements(AccountSessionManager.class) public static class Sessions{
		static String active;
		@Implementation public String getLastActiveAccountID(){ return active; }
		@Implementation public AccountSession tryGetAccount(String id){ return null; }
	}
	private static final class Progress extends ProgressDialog{
		boolean dismissed;
		Progress(Activity activity){ super(activity); setCancelable(false); }
		@Override public void dismiss(){ dismissed=true; }
	}
	private static final class Exchange implements Call{
		boolean canceled;
		@Override public Request request(){ return new Request.Builder().url("https://api.abdl-space.top/api/auth/qq/android/exchange").build(); }
		@Override public Response execute(){ throw new AssertionError("No transport in this test"); }
		@Override public void enqueue(Callback callback){ throw new AssertionError("No transport in this test"); }
		@Override public void cancel(){ canceled=true; }
		@Override public boolean isExecuted(){ return true; }
		@Override public boolean isCanceled(){ return canceled; }
		@Override public Timeout timeout(){ return new Timeout(); }
		@Override public Call clone(){ return new Exchange(); }
	}
	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication();
		me.grishka.appkit.utils.V.setApplicationContext(MastodonApp.context);
		Sessions.active=null;
	}
	private static Field field(String name) throws Exception{ Field field=QQAuthActivity.class.getDeclaredField(name); field.setAccessible(true); return field; }
	private boolean accept(QQAuthActivity activity) throws Exception{
		Method method=QQAuthActivity.class.getDeclaredMethod("acceptExchangeOwner"); method.setAccessible(true); return (boolean)method.invoke(activity);
	}
	@Test public void staleOwnerSilentlyClosesProgressAndCancelsTheOneTimeExchange() throws Exception{
		// Attach only: production onCreate would launch a third-party SDK in enabled variants.
		QQAuthActivity activity=Robolectric.buildActivity(QQAuthActivity.class).get();
		Progress progress=new Progress(activity); Exchange exchange=new Exchange();
		field("progress").set(activity, progress); field("exchangeCall").set(activity, exchange);
		field("exchangeOwner").set(activity, new IndependentAuthResponse.Owner(activity));
		Sessions.active="another-account";
		assertFalse(accept(activity)); assertTrue(progress.dismissed); assertTrue(exchange.canceled);
		assertTrue(activity.isFinishing()); assertTrue(field("finished").getBoolean(activity));
		assertNull(field("progress").get(activity)); assertNull(field("exchangeCall").get(activity)); assertNull(field("exchangeOwner").get(activity));
	}
	@Test public void unchangedOwnerIsAcceptedWhilePausedWithoutCancelingSuccessfulResponse() throws Exception{
		QQAuthActivity activity=Robolectric.buildActivity(QQAuthActivity.class).get();
		Progress progress=new Progress(activity); Exchange exchange=new Exchange();
		field("progress").set(activity, progress); field("exchangeCall").set(activity, exchange);
		field("exchangeOwner").set(activity, new IndependentAuthResponse.Owner(activity));
		assertFalse(field("resumed").getBoolean(activity));
		assertTrue(accept(activity)); assertFalse(activity.isFinishing()); assertFalse(progress.dismissed); assertFalse(exchange.canceled);
	}
}
