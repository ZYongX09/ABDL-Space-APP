package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.nbw.NBWAppRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.NBWForumOptionsRepository;
import org.joinmastodon.android.ui.compose.NBWClassificationController;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

/** Real V2 connection page. No account registration, UID proxy, password or legacy OAuth helper. */
public class NBWPostRegisterActivity extends Activity{
	private AccountSession session;
	private NBWForumOptionsRepository repository;
	private NBWForumOptionsRepository.Cancelable subscription;
	private NBWClassificationController classification;
	private MastodonAPIRequest<?> authorizationRequest;
	private TextView status;
	private Button connect, settings;
	private boolean starting;

	@Override protected void onCreate(Bundle savedInstanceState){
		UiUtils.setUserPreferredTheme(this);
		super.onCreate(savedInstanceState);
		String id=getIntent().getStringExtra("account");
		session=id==null ? AccountSessionManager.getInstance().getLastActiveAccount() : AccountSessionManager.getInstance().tryGetAccount(id);
		if(session==null){ finish(); return; }
		repository=session.getNBWForumOptionsRepository();
		LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
		int padding=(int)(24*getResources().getDisplayMetrics().density); root.setPadding(padding, padding, padding, padding);
		TextView title=new TextView(this); title.setText(R.string.nbw_v2_connection_title); title.setTextSize(22); root.addView(title);
		status=new TextView(this); status.setText(R.string.nbw_v2_connection_unavailable); root.addView(status);
		TextView explanation=new TextView(this); explanation.setText(R.string.nbw_v2_publish_explanation); root.addView(explanation);
		connect=new Button(this); connect.setText(R.string.nbw_v2_connect); connect.setOnClickListener(v->startAuthorization()); root.addView(connect);
		settings=new Button(this); settings.setText(R.string.nbw_v2_classification_settings); settings.setOnClickListener(v->classification.show()); root.addView(settings);
		Button close=new Button(this); close.setText(R.string.done); close.setOnClickListener(v->completeConnectionPage()); root.addView(close);
		android.widget.ScrollView scroll=new android.widget.ScrollView(this); scroll.addView(root); setContentView(scroll);
		classification=new NBWClassificationController(this, repository, ()->{});
		subscription=repository.observe(snapshot->runOnUiThread(()->render(snapshot)));
		if(session.getID().equals(AccountSessionManager.getInstance().getLastActiveAccountID())) repository.activate();
	}

	private void completeConnectionPage(){
		// Registration closes its MainActivity before opening this page. Settings keeps it.
		if(!getIntent().getBooleanExtra("show_back", false)){
			Intent intent=new Intent(this, MainActivity.class);
			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
			startActivity(intent);
		}
		finish();
	}
	@Override public void onBackPressed(){ completeConnectionPage(); }

	private void render(NBWForumOptionsRepository.Snapshot snapshot){
		if(isFinishing() || isDestroyed()) return;
		classification.updateSnapshot(snapshot);
		connect.setVisibility(snapshot.canWrite() ? View.VISIBLE : View.GONE);
		settings.setVisibility(snapshot.canWrite() ? View.VISIBLE : View.GONE);
		connect.setEnabled(!starting);
		status.setText(starting ? R.string.nbw_v2_connection_pending : !snapshot.canRead() ? R.string.nbw_v2_connection_unavailable
				: !snapshot.canWrite() ? R.string.nbw_v2_read_only : snapshot.capabilities.hasGrant() ? R.string.nbw_v2_connection_ready : R.string.nbw_v2_connection_missing);
	}
	private void startAuthorization(){
		if(starting || !repository.getSnapshot().canWrite() || AccountSessionManager.getInstance().tryGetAccount(session.getID())!=session) return;
		starting=true; render(repository.getSnapshot());
		final long generation=repository.getSnapshot().generation;
		authorizationRequest=NBWAppRequest.authorizationStart().setCallback(new Callback<>(){
			@Override public void onSuccess(NBWAppRequest.AuthorizationStart result){ runOnUiThread(()->{
				if(isFinishing() || isDestroyed() || repository.getSnapshot().generation!=generation || AccountSessionManager.getInstance().tryGetAccount(session.getID())!=session) return;
				authorizationRequest=null; starting=false;
				rememberAuthorizationLaunch(NBWPostRegisterActivity.this, session.getID());
				UiUtils.launchWebBrowser(NBWPostRegisterActivity.this, result.authorizationUrl);
				render(repository.getSnapshot());
			}); }
			@Override public void onError(ErrorResponse error){ runOnUiThread(()->{
				authorizationRequest=null; starting=false;
				if(!isFinishing() && !isDestroyed()){ render(repository.getSnapshot()); status.setText(R.string.nbw_v2_connection_failed); }
			}); }
		}).setNBWCancellationCleanup(()->{
			authorizationRequest=null;
			starting=false;
			if(!isFinishing() && !isDestroyed()) render(repository.getSnapshot());
		}).exec(session.getID());
	}

	@Override protected void onResume(){
		super.onResume();
		if(repository!=null) repository.refreshIfExpired();
	}
	@Override protected void onDestroy(){
		if(authorizationRequest!=null) authorizationRequest.cancel();
		if(subscription!=null) subscription.cancel();
		if(classification!=null) classification.dispose();
		super.onDestroy();
	}
	public static void rememberAuthorizationLaunch(Context context, String account){
		context.getSharedPreferences("nbw_v2_authorization", MODE_PRIVATE).edit().putString("account", account).putLong("started", System.currentTimeMillis()).commit();
	}
	/** Callback is only a metadata-refresh hint. Server owns single-use flow and binding identity. */
	public static String consumeAuthorizationLaunch(Context context){
		var prefs=context.getSharedPreferences("nbw_v2_authorization", MODE_PRIVATE);
		String account=prefs.getString("account", null); long age=System.currentTimeMillis()-prefs.getLong("started", 0);
		if(!prefs.edit().clear().commit() || account==null || age<0 || age>10*60*1000L) return null;
		return AccountSessionManager.getInstance().tryGetAccount(account)==null ? null : account;
	}

	/** Retired helpers remain source-compatible only; they cannot authorize or bind V2. */
	@Deprecated public static String createBindingState(Context context, String account, long uid){ return "retired"; }
	@Deprecated public static String consumeBindingAccount(Context context, String encoded){ return null; }
}
