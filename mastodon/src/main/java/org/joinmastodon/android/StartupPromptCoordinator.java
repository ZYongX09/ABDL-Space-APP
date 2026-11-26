package org.joinmastodon.android;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.requests.announcements.GetServiceNotice;
import org.joinmastodon.android.api.requests.verification.VerificationRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.settings.BabyVerificationFragment;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.ServiceNotice;
import org.joinmastodon.android.model.verification.VerificationModels.ApplicationResult;
import org.joinmastodon.android.model.verification.VerificationModels.State;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.sheets.NewBadgeSheet;

import java.io.IOException;
import java.util.ArrayDeque;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

/** Serializes cold-start prompts: rejection first, then new badges. Every async result is pinned to one account. */
public final class StartupPromptCoordinator{
	private final MainActivity activity;
	private final AccountSession session;
	private final String accountId;
	private boolean stopped, showing;
	private android.app.AlertDialog currentDialog;
	private NewBadgeSheet badgeSheet;
	private org.joinmastodon.android.api.session.ContentAccessCoordinator.Cancelable accessSubscription;
	private final org.joinmastodon.android.model.Token token;
	private final String tokenValue;
	private Call badgeCall;
	private VerificationRequest<?> verificationRequest;

	public StartupPromptCoordinator(MainActivity activity){ this.activity=activity; session=AccountSessionManager.getInstance().getLastActiveAccount(); accountId=session==null ? null : session.getID(); token=session==null ? null : session.token; tokenValue=token==null ? null : token.accessToken; }
	public void start(){
		if(session==null || !session.activated) return;
		if(org.joinmastodon.android.api.session.ContentAccessCoordinator.applies(session)) accessSubscription=session.getContentAccessCoordinator().observe(snapshot->{
			if(snapshot.withholdsContent()){
				if(badgeCall!=null) badgeCall.cancel();
				if(badgeSheet!=null){ badgeSheet.setOnDismissListener(null); badgeSheet.dismiss(); badgeSheet=null; showing=false; }
				if(verificationRequest!=null) verificationRequest.cancel();
				if(currentDialog!=null){ currentDialog.setOnDismissListener(null); currentDialog.dismiss(); currentDialog=null; showing=false; }
				showContentAccessPrompt();
			}else if(live()) activity.getWindow().getDecorView().postDelayed(this::checkServiceNotice,1200);
		});
		else activity.getWindow().getDecorView().postDelayed(this::checkServiceNotice,1200);
	}
	public void stop(){ stopped=true; if(badgeCall!=null) badgeCall.cancel(); if(verificationRequest!=null) verificationRequest.cancel(); if(accessSubscription!=null) accessSubscription.cancel(); if(currentDialog!=null) currentDialog.dismiss(); }
	private boolean live(){ return !stopped && !activity.isFinishing() && !activity.isDestroyed() && session!=null && session.token==token && java.util.Objects.equals(tokenValue,token==null ? null : token.accessToken) && AccountSessionManager.getInstance().tryGetAccount(accountId)==session && accountId.equals(AccountSessionManager.getInstance().getLastActiveAccountID()); }
	private boolean contentOpen(){ return !org.joinmastodon.android.api.session.ContentAccessCoordinator.applies(session) || session.getContentAccessCoordinator().isOpen(); }
	public void showContentAccessPrompt(){
		if(!live() || showing || contentOpen() || !activity.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
				|| org.joinmastodon.android.security.AppSecurity.isLocked() || org.joinmastodon.android.ContentAccessGate.isAllowedFragment(activity.getTopmostFragment())) return;
		if(!session.getContentAccessCoordinator().claimRequiredPrompt()) return;
		showing=true;
		currentDialog=new M3AlertDialogBuilder(activity).setTitle(R.string.content_access_required_title).setMessage(R.string.content_access_required_body)
				.setPositiveButton(R.string.verification_title,(dialog,which)->{ if(live()) org.joinmastodon.android.ContentAccessGate.openVerification(activity,accountId); })
				.setNegativeButton(android.R.string.cancel,null).create();
		currentDialog.setOnDismissListener(dialog->{ currentDialog=null; showing=false; });
		currentDialog.show();
	}

	private void checkServiceNotice(){
		if(!live() || showing || !contentOpen()) return;
		MastodonAPIController.runInBackground(()->{
			ServiceNotice notice=GetServiceNotice.fetch();
			activity.runOnUiThread(()->{
				if(!live() || !contentOpen()) return;
				if(notice==null || GlobalUserPreferences.alertSeen("service_notice_"+notice.id)){ checkRejection(); return; }
				showing=true;
				String key="service_notice_"+notice.id;
				AlertDialog dialog=new M3AlertDialogBuilder(activity).setTitle(notice.title==null || notice.title.isEmpty() ? activity.getString(R.string.service_notice_title) : notice.title)
						.setMessage(notice.content).setPositiveButton(R.string.service_notice_ok, null).setCancelable(false).create();
				dialog.setOnDismissListener(ignored->{ GlobalUserPreferences.setAlertSeen(key); showing=false; checkRejection(); });
				currentDialog=dialog; dialog.show();
			});
		});
	}

	private void checkRejection(){
		if(!live() || showing || !contentOpen()) return;
		VerificationRequest<State> request=VerificationRequest.state(); verificationRequest=request;
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(State state){ verificationRequest=null; if(!live() || !contentOpen()) return; if(state.status()==org.joinmastodon.android.model.verification.VerificationModels.Status.REJECTED && state.application.rejectionAcknowledgedAt==null) showRejection(state); else checkBadges(); }
			@Override public void onError(ErrorResponse error){ verificationRequest=null; if(live()) checkBadges(); }
		}).exec(accountId);
	}
	private void showRejection(State state){
		showing=true;
		AlertDialog dialog=new M3AlertDialogBuilder(activity).setTitle(R.string.verification_rejected_title).setMessage(state.application.decisionNote)
				.setPositiveButton(R.string.verification_status_rejected, (d,w)->{ BundleArgs.openVerification(activity, accountId); })
				.setNegativeButton(android.R.string.cancel, null).create();
		dialog.setOnDismissListener(d->{ currentDialog=null; showing=false; acknowledge(state.application.id); checkBadges(); }); currentDialog=dialog; dialog.show();
	}
	private void acknowledge(String applicationId){
		VerificationRequest<ApplicationResult> request=VerificationRequest.acknowledgeRejection(applicationId); verificationRequest=request;
		request.setCallback(new Callback<>(){ @Override public void onSuccess(ApplicationResult result){ if(verificationRequest==request) verificationRequest=null; } @Override public void onError(ErrorResponse error){ if(verificationRequest==request) verificationRequest=null; } }).exec(accountId);
	}
	private void checkBadges(){
		if(!live() || showing || !contentOpen()) return;
		Request request=new Request.Builder().url("https://api.abdl-space.top/api/users/"+session.self.id+"/badges").header("Authorization", "Bearer "+session.token.accessToken).get().build();
		badgeCall=MastodonAPIController.getHttpClient().newCall(request); badgeCall.enqueue(new okhttp3.Callback(){
			@Override public void onFailure(Call call, IOException e){}
			@Override public void onResponse(Call call, Response response) throws IOException{
				try(response){ if(!response.isSuccessful() || response.body()==null || !live()) return; JsonObject json=JsonParser.parseString(response.body().string()).getAsJsonObject(); JsonArray badges=json.getAsJsonArray("badges"); if(badges==null) return; ArrayDeque<Account.Badge> queue=new ArrayDeque<>(); SharedPreferences prefs=activity.getSharedPreferences("badge_popup", Context.MODE_PRIVATE);
					for(int i=badges.size()-1;i>=0;i--){ JsonObject value=badges.get(i).getAsJsonObject(); if(value.has("acknowledged") && value.get("acknowledged").getAsBoolean()) continue; Account.Badge badge=new Account.Badge(); badge.key=value.has("key")?value.get("key").getAsString():""; badge.name=value.has("name")?value.get("name").getAsString():""; badge.color=value.has("color")?value.get("color").getAsString():"#7C4DFF"; if(badge.key.isEmpty()) continue; String key="seen_once_"+accountId+"_"+badge.key; if(!prefs.getBoolean(key,false)) prefs.edit().putBoolean(key,true).apply(); else queue.add(badge); }
					if(!queue.isEmpty()) activity.runOnUiThread(()->showNext(queue));
				}catch(RuntimeException ignored){}
			}
		});
	}
	private void showNext(ArrayDeque<Account.Badge> queue){
		if(!live() || !contentOpen() || queue.isEmpty()){ showing=false; return; }
		showing=true;
		Account.Badge badge=queue.poll();
		boolean[] confirmed={false};
		NewBadgeSheet sheet=new NewBadgeSheet(activity,badge.name,null,badge.color,()->{ confirmed[0]=true; acknowledgeBadge(badge,()->showNext(queue)); });
		sheet.setOnDismissListener(dialog->{ if(!confirmed[0]){ showing=false; if(live()) showNext(queue); } });
		badgeSheet=sheet; sheet.show();
	}
	private void acknowledgeBadge(Account.Badge badge, Runnable done){
		if(!live()){ activity.runOnUiThread(done); return; }
		JsonObject body=new JsonObject(); JsonArray keys=new JsonArray(); keys.add(badge.key); body.add("badge_keys",keys);
		Request request=new Request.Builder().url("https://api.abdl-space.top/api/users/badge/acknowledge").header("Authorization","Bearer "+session.token.accessToken).post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"),body.toString())).build();
		MastodonAPIController.getHttpClient().newCall(request).enqueue(new okhttp3.Callback(){ @Override public void onFailure(Call call,IOException error){ activity.runOnUiThread(done); } @Override public void onResponse(Call call,Response response){ response.close(); activity.runOnUiThread(done); } });
	}
	private static final class BundleArgs{ static void openVerification(MainActivity activity,String accountId){ android.os.Bundle args=new android.os.Bundle(); args.putString("account",accountId); Nav.go(activity, BabyVerificationFragment.class,args); } }
}
