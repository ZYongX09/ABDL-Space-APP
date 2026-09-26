package org.joinmastodon.android.sponsors;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.sponsors.SponsorAvailability;
import org.joinmastodon.android.api.requests.sponsors.SponsorRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.sponsors.SponsorModels.*;
import org.joinmastodon.android.ui.sheets.SponsorNoticeSheet;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

/** One serial original-image authorization flow, owned by one photo overlay and account session. */
public final class SponsorOriginalGate{
	private final Activity activity;
	private final String accountID;
	private final AccountSession session;
	private final BooleanSupplier hostValid;
	private final Runnable changed, openCenter;
	private final Handler main=new Handler(Looper.getMainLooper());
	private final SponsorOriginalTickets tickets;
	private final SharedPreferences accountPrefs;
	private final SharedPreferences.OnSharedPreferenceChangeListener accountListener;
	private MastodonAPIRequest<?> request;
	private SponsorNoticeSheet sheet;
	private Attempt attempt;
	private long generation;

	public SponsorOriginalGate(Activity activity, String accountID, BooleanSupplier hostValid, Runnable changed, Runnable openCenter){
		this.activity=activity;
		this.accountID=accountID;
		this.hostValid=hostValid;
		this.changed=changed;
		this.openCenter=openCenter;
		session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		SharedPreferences prefs=activity.getSharedPreferences("sponsor_original_tickets", Context.MODE_PRIVATE);
		tickets=new SponsorOriginalTickets(new SponsorOriginalTickets.Store(){
			@Override public String read(){ return prefs.getString("tickets", ""); }
			@Override public boolean write(String value){ return prefs.edit().putString("tickets", value).commit(); }
		});
		accountPrefs=activity.getSharedPreferences("account_manager", Context.MODE_PRIVATE);
		accountListener=(preferences, key)->{
			if("lastActiveAccount".equals(key) && !accountID.equals(AccountSessionManager.getInstance().getLastActiveAccountID())) cancel();
		};
	}

	public boolean isBusy(){ return attempt!=null; }

	public void authorize(String mediaId, String originalUrl, Runnable allowed){
		if(isBusy()) return;
		if(!sessionValid()){
			Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show();
			return;
		}
		Attempt a=new Attempt(++generation, SponsorOperation.mediaKey(mediaId, originalUrl), allowed);
		attempt=a;
		accountPrefs.registerOnSharedPreferenceChangeListener(accountListener);
		changed.run();
		try{
			// Availability can call back synchronously; posting prevents clobbering the next request.
			request=SponsorAvailability.check(accountID, callback(a, supported->{
				if(Boolean.FALSE.equals(supported)) allow(a);
				else if(Boolean.TRUE.equals(supported)) catalog(a);
				else fail(a, null);
			}));
		}catch(RuntimeException error){ fail(a, null); }
	}

	public void cancel(){
		generation++;
		attempt=null;
		if(request!=null){ request.cancel(); request=null; }
		if(sheet!=null){ SponsorNoticeSheet previous=sheet; sheet=null; previous.dismiss(); }
		accountPrefs.unregisterOnSharedPreferenceChangeListener(accountListener);
		changed.run();
	}

	private boolean sessionValid(){
		return !activity.isFinishing() && !activity.isDestroyed() && hostValid.getAsBoolean() && session!=null
				&& AccountSessionManager.getInstance().tryGetAccount(accountID)==session
				&& accountID.equals(AccountSessionManager.getInstance().getLastActiveAccountID());
	}

	private boolean live(Attempt a){
		if(attempt!=a || generation!=a.generation) return false;
		if(!sessionValid()){ cancel(); return false; }
		return true;
	}

	private <T> Callback<T> callback(Attempt a, Consumer<T> success){
		return new Callback<>(){
			@Override public void onSuccess(T result){ main.post(()->{ if(live(a)){ request=null; success.accept(result); } }); }
			@Override public void onError(ErrorResponse error){ main.post(()->{ if(live(a)){ request=null; fail(a, error); } }); }
		};
	}

	private <T> void execute(Attempt a, SponsorRequest<T> req, Consumer<T> success){
		if(!live(a)) return;
		request=req;
		req.setCallback(callback(a, success)).exec(accountID);
	}

	private void catalog(Attempt a){
		execute(a, SponsorRequest.catalog(), catalog->{
			if(catalog==null || catalog.config==null || catalog.config.enabled==null){ fail(a, null); return; }
			a.config=catalog.config;
			// Only a successful, explicitly disabled config is a legacy bypass, never an API error.
			if(Boolean.FALSE.equals(a.config.enabled)){ allow(a); return; }
			me(a);
		});
	}

	private void me(Attempt a){
		execute(a, SponsorRequest.me(), me->{
			if(me==null || me.sponsor==null || me.quota==null || !me.quota.valid() || me.configVersion!=a.config.version){ fail(a, null); return; }
			a.me=me;
			final boolean retry;
			try{
				retry=tickets.hasRetry(accountID, a.media, me.quota.dayKey, me.quota.resetsAt, System.currentTimeMillis()/1000);
			}catch(RuntimeException error){ fail(a, null); return; }
			if(me.quota.remaining==0 && (!retry || a.requireNotice)){
				exhausted(a, me.quota);
				return;
			}
			// A stored operation can replay even at zero remaining and without acknowledging new copy.
			if(!me.sponsor.isActive() && (a.requireNotice || me.noticeRequired && !retry)) notice(a);
			else authorize(a, null);
		});
	}

	private void notice(Attempt a){
		showSheet(a, a.config.noticeTitle, SponsorUi.render(a.config.noticeBody, a.config, a.me.quota),
				activity.getString(R.string.sponsor_ui_continue_free), ()->authorize(a, a.config.noticeVersion), false);
	}

	private void exhausted(Attempt a, Quota quota){
		boolean sponsor=a.me.sponsor.isActive();
		showSheet(a, a.config.exhaustedTitle,
				SponsorUi.render(sponsor ? a.config.sponsorExhaustedBody : a.config.exhaustedBody, a.config, quota), null, null, !sponsor);
	}

	private void authorize(Attempt a, Integer noticeVersion){
		if(!live(a)) return;
		try{
			a.operation=tickets.operation(accountID, a.media, a.me.quota.dayKey, a.me.quota.resetsAt, System.currentTimeMillis()/1000);
		}catch(RuntimeException error){ fail(a, null); return; }
		// An expired identity is never retried in a loop or treated as a local permission.
		if(a.operation.equals(a.expiredOperation)){ fail(a, null); return; }
		SponsorRequest<Authorization> req=SponsorRequest.authorize(a.operation, a.media, noticeVersion);
		request=req;
		req.setCallback(new Callback<>(){
			@Override public void onSuccess(Authorization result){
				main.post(()->{
					if(!live(a)) return;
					request=null;
					if(result==null || !a.operation.equals(result.operationId) || result.quota==null || !result.quota.valid()){ fail(a, null); return; }
					try{ tickets.authorized(accountID, a.media, a.operation, result.quota.dayKey, result.quota.resetsAt); }
					catch(RuntimeException error){ fail(a, null); return; }
					allow(a);
				});
			}
			@Override public void onError(ErrorResponse error){
				main.post(()->{
					if(!live(a)) return;
					request=null;
					if(error instanceof SponsorRequest.SponsorError sponsorError){
						if("quota_exhausted".equals(sponsorError.code) && sponsorError.quota!=null){
							exhausted(a, sponsorError.quota);
							return;
						}
						if("notice_required".equals(sponsorError.code) && !a.refreshedNotice){
							a.refreshedNotice=true;
							a.requireNotice=true;
							// Reload copy and version together. Never acknowledge a version the user did not read.
							catalog(a);
							return;
						}
						if("operation_expired".equals(sponsorError.code) && a.expiredOperation==null){
							a.expiredOperation=a.operation;
							catalog(a); // Fresh server day selects a fresh UUID; no local clock-based day.
							return;
						}
						if("sponsors_disabled".equals(sponsorError.code) && !a.refreshedDisabled){
							a.refreshedDisabled=true;
							catalog(a);
							return;
						}
					}
					fail(a, error);
				});
			}
		}).exec(accountID);
	}

	private void showSheet(Attempt a, String title, String body, String action, Runnable continued, boolean center){
		if(!live(a)) return;
		boolean[] acted={false};
		SponsorNoticeSheet notice=new SponsorNoticeSheet(activity, title, body, action,
				continued==null ? null : ()->{
					acted[0]=true;
					if(live(a)){ sheet=null; continued.run(); }
				}, center ? ()->{
					acted[0]=true;
					if(live(a)){ sheet=null; finish(a); openCenter.run(); }
				} : null);
		sheet=notice;
		notice.setOnDismissListener(dialog->main.post(()->{
			// The sheet dismisses before calling Continue; do not unlock its asynchronous POST.
			if(!acted[0] && live(a)){ sheet=null; cancel(); }
		}));
		notice.show();
	}

	private void allow(Attempt a){
		if(!live(a)) return;
		finish(a);
		a.allowed.run();
	}

	private void finish(Attempt a){
		if(attempt!=a) return;
		attempt=null;
		request=null;
		accountPrefs.unregisterOnSharedPreferenceChangeListener(accountListener);
		changed.run();
	}

	private void fail(Attempt a, ErrorResponse error){
		if(!live(a)) return;
		finish(a);
		if(error!=null) error.showToast(activity);
		else Toast.makeText(activity, R.string.error, Toast.LENGTH_LONG).show();
	}

	private static final class Attempt{
		final long generation;
		final String media;
		final Runnable allowed;
		Config config;
		Me me;
		String operation, expiredOperation;
		boolean requireNotice, refreshedNotice, refreshedDisabled;
		Attempt(long generation, String media, Runnable allowed){ this.generation=generation; this.media=media; this.allowed=allowed; }
	}
}
