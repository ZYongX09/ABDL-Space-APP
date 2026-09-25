package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.QQAuthActivity;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.requests.accounts.GetOwnAccount;
import org.joinmastodon.android.api.requests.catalog.GetDonationCampaigns;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.donations.DonationCampaign;
import org.joinmastodon.android.model.viewmodel.ListItem;
import org.joinmastodon.android.model.viewmodel.SectionHeaderListItem;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.sheets.DonationSheet;
import org.joinmastodon.android.ui.sheets.DonationSuccessfulSheet;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.utils.CubicBezierInterpolator;
import me.grishka.appkit.utils.MergeRecyclerAdapter;
import me.grishka.appkit.utils.SingleViewRecyclerAdapter;

public class SettingsAccountFragment extends BaseSettingsFragment<Void>{
	private static final int DONATION_RESULT=433;
	private static final int QQ_BIND_RESULT=434;
	private DonationSheet donationSheet;
	private boolean loggedOut;
	private ListItem<Void> nbwItem;
	private ListItem<Void> qqItem;
	private ListItem<Void> verificationItem;
	private boolean qqBound;
	private org.joinmastodon.android.api.MastodonAPIRequest<?> verificationRequest;
	private AccountSession pinnedSession;
	private Call qqStatusCall;
	private Call qqUnbindCall;
	private int verificationGeneration;
	private int qqStatusGeneration;

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
			AccountSession account=AccountSessionManager.getInstance().tryGetAccount(accountID);
			if(account==null){
				if(getActivity()!=null) getActivity().finish();
				return;
			}
			pinnedSession=account;
			setTitle(account.getFullUsername());


		ArrayList<ListItem<Void>> items=new ArrayList<>();
		items.add(new SectionHeaderListItem(R.string.account_settings));
		items.add(new ListItem<>(R.string.settings_privacy, 0, R.drawable.ic_privacy_tip_24px, this::onPrivacyClick));
		items.add(new ListItem<>(R.string.settings_filters, 0, R.drawable.ic_filter_alt_24px, this::onFiltersClick));
		items.add(new ListItem<>(R.string.settings_notifications, 0, R.drawable.ic_notifications_24px, this::onNotificationsClick));
			items.add(new ListItem<>(R.string.settings_posting_defaults, 0, R.drawable.ic_edit_square_24px, this::onPostingDefaultsClick));

			items.add(new SectionHeaderListItem(R.string.verification_section));
			verificationItem=new ListItem<>(getString(R.string.verification_title), getString(R.string.verification_status_loading), R.drawable.ic_badge_24px, this::onVerificationClick);
			items.add(verificationItem);

			// 第三方账户

		String nbwStatus = account.self.nbwUsername != null ? account.self.nbwUsername : getString(R.string.nbw_unbound);
		items.add(new SectionHeaderListItem(R.string.nbw_third_party_account));
			nbwItem = new ListItem<>(getString(R.string.nbw_third_party_nbw), nbwStatus, R.drawable.ic_nbw, this::onNBWBindClick);
			items.add(nbwItem);
			if(BuildConfig.QQ_LOGIN_ENABLED){
				qqItem=new ListItem<>(getString(R.string.qq_account_title), getString(R.string.qq_status_loading), R.drawable.ic_qq_login, this::onQQBindClick);
				items.add(qqItem);
			}


		// 赞助者中心：独立栏目，始终作用于当前点击的账号会话
		items.add(new SectionHeaderListItem(R.string.sponsor_section));
		items.add(new ListItem<>(R.string.sponsor_center, 0, R.drawable.ic_volunteer_activism_24px, this::onSponsorCenterClick));

		items.add(new SectionHeaderListItem(account.domain));
		items.add(new ListItem<>(getString(R.string.settings_about_this_server), getString(R.string.settings_server_explanation), R.drawable.ic_dns_24px, this::onServerClick));
		if(account.isEligibleForDonations()){
			items.add(new ListItem<>(R.string.settings_donate, 0, R.drawable.ic_volunteer_activism_24px, this::onDonateClick));
		}

		items.add(new SectionHeaderListItem(R.string.manage_account));
		items.add(new ListItem<>(R.string.switch_to_this_account, 0, R.drawable.ic_switch_account_24px, AccountSessionManager.getInstance().getLastActiveAccountID().equals(accountID) ? null : this::onSwitchAccountClick));
		items.add(new ListItem<>(R.string.delete_account, 0, R.drawable.ic_delete_forever_24px, this::onDeleteAccountClick, R.attr.colorM3Error, false));
		items.add(new ListItem<>(R.string.log_out, 0, R.drawable.ic_logout_24px, this::onLogOutClick, R.attr.colorM3Error, false));

		onDataLoaded(items);
	}

	@Override
	protected void doLoadData(int offset, int count){}

	@Override
		public void onResume(){
			super.onResume();
			refreshNBWStatus();
			refreshQQStatus();
			refreshVerificationStatus();
		}


	private void refreshNBWStatus(){
		if(nbwItem == null || pinnedSession==null || AccountSessionManager.getInstance().tryGetAccount(accountID)!=pinnedSession) return;
		new GetOwnAccount()
			.setCallback(new me.grishka.appkit.api.Callback<Account>(){
				@Override
				public void onSuccess(Account account){
					Activity activity=getActivity();
					if(activity==null) return;
					activity.runOnUiThread(()->{
						if(getActivity()==null || AccountSessionManager.getInstance().tryGetAccount(accountID)!=pinnedSession) return;
						AccountSessionManager.getInstance().updateAccountInfo(accountID, account);
						String nbwStatus = account.nbwUsername != null ? account.nbwUsername : getString(R.string.nbw_unbound);
						nbwItem.subtitle = nbwStatus;
						rebindItem(nbwItem);
					});
				}

				@Override
				public void onError(ErrorResponse error){}
			})
			.exec(accountID);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data){
		super.onActivityResult(requestCode, resultCode, data);
		if(requestCode==QQ_BIND_RESULT && resultCode==QQAuthActivity.RESULT_BINDING_CHANGED){
			refreshQQStatus();
		}
			if(requestCode==DONATION_RESULT){
				if(donationSheet!=null)
					donationSheet.dismissWithoutAnimation();
				if(resultCode==Activity.RESULT_OK && data!=null && getActivity()!=null){
					new DonationSuccessfulSheet(getActivity(), accountID, data.getStringExtra("postText")).showWithoutAnimation();
				}
			}

	}

	@Override
	protected void onUpdateToolbar(){
		super.onUpdateToolbar();
		toolbarTitleView.setAlpha(list.getChildCount()==0 || list.getChildAdapterPosition(list.getChildAt(0))==0 ? 0 : 1);
	}

	@Override
	protected RecyclerView.Adapter<?> getAdapter(){
		TextView largeTitle=(TextView) getToolbarLayoutInflater().inflate(R.layout.large_title, list, false);
		largeTitle.setText(getTitle());
		largeTitle.setPadding(largeTitle.getPaddingLeft(), largeTitle.getPaddingTop(), largeTitle.getPaddingRight(), 0);

		MergeRecyclerAdapter adapter=new MergeRecyclerAdapter();
		adapter.addAdapter(new SingleViewRecyclerAdapter(largeTitle));
		adapter.addAdapter(super.getAdapter());
		return adapter;
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState){
		super.onViewCreated(view, savedInstanceState);
		list.addOnScrollListener(new RecyclerView.OnScrollListener(){
			private boolean titleVisible=true;

			@Override
			public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy){
				boolean newTitleVisible=list.getChildAdapterPosition(list.getChildAt(0))==0;
				if(newTitleVisible!=titleVisible){
					titleVisible=newTitleVisible;
					toolbarTitleView.animate().alpha(titleVisible ? 0 : 1).setDuration(250).setInterpolator(CubicBezierInterpolator.DEFAULT).start();
				}
			}
		});
	}

	private Bundle makeFragmentArgs(){
		Bundle args=new Bundle();
		args.putString("account", accountID);
		return args;
	}

	private void onPrivacyClick(ListItem<?> item_){
		Nav.go(getActivity(), SettingsPrivacyFragment.class, makeFragmentArgs());
	}

	private void onFiltersClick(ListItem<?> item_){
		Nav.go(getActivity(), SettingsFiltersFragment.class, makeFragmentArgs());
	}

	private void onNotificationsClick(ListItem<?> item_){
		Nav.go(getActivity(), SettingsNotificationsFragment.class, makeFragmentArgs());
	}

	private void onPostingDefaultsClick(ListItem<?> item_){
		Nav.go(getActivity(), SettingsPostingDefaultsFragment.class, makeFragmentArgs());
	}

	private void onServerClick(ListItem<?> item_){
		Nav.go(getActivity(), SettingsServerFragment.class, makeFragmentArgs());
	}

	private void onSponsorCenterClick(ListItem<?> item_){
		Nav.go(getActivity(), org.joinmastodon.android.fragments.sponsors.SponsorCenterFragment.class, makeFragmentArgs());
	}

	private void onVerificationClick(ListItem<?> item_){
		Nav.go(getActivity(), BabyVerificationFragment.class, makeFragmentArgs());
	}

	private void refreshVerificationStatus(){
		if(verificationItem==null) return;
		if(verificationRequest!=null) verificationRequest.cancel();
		int generation=++verificationGeneration;
		org.joinmastodon.android.api.requests.verification.VerificationRequest<org.joinmastodon.android.model.verification.VerificationModels.State> request=org.joinmastodon.android.api.requests.verification.VerificationRequest.state();
		verificationRequest=request.setCallback(new me.grishka.appkit.api.Callback<>(){
			@Override public void onSuccess(org.joinmastodon.android.model.verification.VerificationModels.State state){
				if(generation!=verificationGeneration || getActivity()==null || AccountSessionManager.getInstance().tryGetAccount(accountID)!=pinnedSession) return;
				verificationRequest=null;
				verificationItem.subtitle=getString(switch(state.status()){
					case APPROVED -> R.string.verification_status_approved;
					case REJECTED -> R.string.verification_status_rejected;
					case SUBMITTED, REVIEWING -> R.string.verification_status_review;
					case DRAFT -> R.string.verification_status_draft;
					default -> R.string.verification_status_not_started;
				});
				rebindItem(verificationItem);
			}
			@Override public void onError(me.grishka.appkit.api.ErrorResponse error){ if(generation==verificationGeneration) verificationRequest=null; }
		}).exec(accountID);
	}

	@Override
	public void onDestroy(){
		verificationGeneration++;
		qqStatusGeneration++;
		if(verificationRequest!=null) verificationRequest.cancel();
		if(qqStatusCall!=null) qqStatusCall.cancel();
		if(qqUnbindCall!=null) qqUnbindCall.cancel();
		verificationRequest=null;
		qqStatusCall=null;
		qqUnbindCall=null;
		super.onDestroy();
	}

	private boolean useStagingEnvironmentForDonations(){
		Activity activity=getActivity();
		return activity!=null && (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE.equals("appcenterPrivateBeta")) && activity.getSharedPreferences("debug", Context.MODE_PRIVATE).getBoolean("donationsStaging", false);
	}

	private void onDonateClick(ListItem<?> item){
		GetDonationCampaigns req=new GetDonationCampaigns(Locale.getDefault().toLanguageTag().replace('-', '_'), String.valueOf(AccountSessionManager.get(accountID).getDonationSeed()), "menu");
		if(useStagingEnvironmentForDonations()){
			req.setStaging(true);
		}
		req.setCallback(new Callback<>(){
					@Override
					public void onSuccess(DonationCampaign result){
						Activity activity=getActivity();
						if(activity==null)
							return;
						if(result==null){
							Toast.makeText(activity, "No campaign available (server misconfiguration?)", Toast.LENGTH_SHORT).show();
							return;
						}
						donationSheet=new DonationSheet(getActivity(), result, accountID, intent->startActivityForResult(intent, DONATION_RESULT));
						donationSheet.setOnDismissListener(dialog->donationSheet=null);
						donationSheet.show();
					}

					@Override
					public void onError(ErrorResponse error){
						error.showToast(getActivity());
					}
				})
				.wrapProgress(getActivity(), R.string.loading, true)
				.execNoAuth("");
	}

	private void onSwitchAccountClick(ListItem<?> item){
		if(AccountSessionManager.getInstance().tryGetAccount(accountID)!=null){
			AccountSessionManager.getInstance().setLastActiveAccountID(accountID);
			((MainActivity)getActivity()).restartHomeFragment();
			((MainActivity)getActivity()).restartStartupPrompts();
		}
	}

	private void onDeleteAccountClick(ListItem<?> item){
		AccountSession session=AccountSessionManager.getInstance().getAccount(accountID);
		UiUtils.launchWebBrowser(getActivity(), "https://"+session.domain+"/settings/delete");
	}

	private void onLogOutClick(ListItem<?> item_){
		AccountSession session=AccountSessionManager.getInstance().getAccount(accountID);
		new M3AlertDialogBuilder(getActivity())
				.setMessage(getString(R.string.confirm_log_out, session.getFullUsername()))
				.setPositiveButton(R.string.log_out, (dialog, which)->AccountSessionManager.get(accountID).logOut(getActivity(), ()->{
					loggedOut=true;
					((MainActivity)getActivity()).restartHomeFragment();
				}))
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void refreshQQStatus(){
		if(!BuildConfig.QQ_LOGIN_ENABLED || qqItem==null) return;
		AccountSession session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		if(session==null || session.token==null || TextUtils.isEmpty(session.token.accessToken)) return;
		if(qqStatusCall!=null) qqStatusCall.cancel();
		final int generation=++qqStatusGeneration;
		Request request=new Request.Builder()
				.url("https://api.abdl-space.top/api/auth/qq/status")
				.header("Authorization", "Bearer "+session.token.accessToken)
				.get()
				.build();
		qqStatusCall=MastodonAPIController.getHttpClient().newCall(request);
		qqStatusCall.enqueue(new okhttp3.Callback(){
			@Override
			public void onFailure(Call call, IOException error){
					if(call.isCanceled()) return;
					updateQQStatus(generation, false, getString(R.string.qq_status_unavailable));
				}

				@Override
				public void onResponse(Call call, Response response) throws IOException{
					okhttp3.ResponseBody responseBody=response.body();
					String body=responseBody==null ? "" : responseBody.string();
					boolean successful=response.isSuccessful();
					response.close();
					boolean bound=false;
					String subtitle=getString(successful ? R.string.qq_status_unbound : R.string.qq_status_unavailable);
					if(successful){
						try{
							org.json.JSONObject json=new org.json.JSONObject(body);
							bound=json.optBoolean("bound", json.optBoolean("is_bound", false));
							String nickname=json.optString("nickname", "").trim();
							subtitle=bound && !TextUtils.isEmpty(nickname) ? nickname : getString(bound ? R.string.qq_status_bound : R.string.qq_status_unbound);
						}catch(org.json.JSONException ignored){}
					}
					updateQQStatus(generation, bound, subtitle);
				}
			});
		}

		private void updateQQStatus(int generation, boolean bound, String subtitle){
			Activity activity=getActivity();
			if(activity==null) return;
			activity.runOnUiThread(()->{
				if(generation!=qqStatusGeneration || getActivity()==null || AccountSessionManager.getInstance().tryGetAccount(accountID)!=pinnedSession) return;
				qqBound=bound;
				if(qqItem!=null){
					qqItem.subtitle=subtitle;
					rebindItem(qqItem);
				}
			});
		}


	private void onQQBindClick(ListItem<?> item){
		if(!BuildConfig.QQ_LOGIN_ENABLED || getActivity()==null) return;
		if(qqBound){
			new M3AlertDialogBuilder(getActivity())
					.setTitle(R.string.qq_unbind_title)
					.setMessage(R.string.qq_unbind_message)
					.setPositiveButton(R.string.qq_unbind, (dialog, which)->unbindQQ())
					.setNegativeButton(R.string.cancel, null)
					.show();
		}else{
			new M3AlertDialogBuilder(getActivity())
					.setTitle(R.string.qq_consent_title)
					.setMessage(R.string.qq_consent_message)
					.setPositiveButton(R.string.confirm, (dialog, which)->startQQBind())
					.setNegativeButton(R.string.cancel, null)
					.show();
		}
	}

	private void startQQBind(){
		if(!BuildConfig.QQ_LOGIN_ENABLED || getActivity()==null || AccountSessionManager.getInstance().tryGetAccount(accountID)!=pinnedSession) return;
		Intent intent=new Intent(getActivity(), QQAuthActivity.class);
		intent.putExtra(QQAuthActivity.EXTRA_MODE, QQAuthActivity.MODE_BIND);
		intent.putExtra(QQAuthActivity.EXTRA_ACCOUNT_ID, accountID);
		intent.putExtra(QQAuthActivity.EXTRA_PERMISSION_CONFIRMED, true);
		startActivityForResult(intent, QQ_BIND_RESULT);
	}

	private void unbindQQ(){
		if(!BuildConfig.QQ_LOGIN_ENABLED) return;
		AccountSession session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		if(session==null || session!=pinnedSession || session.token==null || TextUtils.isEmpty(session.token.accessToken)) return;
		if(qqUnbindCall!=null) qqUnbindCall.cancel();
		Request request=new Request.Builder()
				.url("https://api.abdl-space.top/api/auth/qq/binding")
				.header("Authorization", "Bearer "+session.token.accessToken)
				.delete()
				.build();
		qqUnbindCall=MastodonAPIController.getHttpClient().newCall(request);
		qqUnbindCall.enqueue(new okhttp3.Callback(){
			@Override
			public void onFailure(Call call, IOException error){
				if(!call.isCanceled()) showQQToast(R.string.qq_network_error);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException{
					boolean successful=response.isSuccessful();
					String body=response.body()==null ? "" : response.body().string();
					response.close();
					if(successful){

					showQQToast(R.string.qq_unbind_success);
					Activity activity=getActivity();
					if(activity!=null) activity.runOnUiThread(()->{
						if(AccountSessionManager.getInstance().tryGetAccount(accountID)==pinnedSession) refreshQQStatus();
					});
					}else{
						boolean wouldLock=false;
						try{
							wouldLock="QQ_UNBIND_WOULD_LOCK_ACCOUNT".equals(new org.json.JSONObject(body).optString("error"));
						}catch(org.json.JSONException ignored){}
						showQQToast(wouldLock ? R.string.qq_unbind_would_lock_account : R.string.qq_unbind_failed);
					}

			}
		});
	}

	private void showQQToast(int stringRes){
		Activity activity=getActivity();
		if(activity!=null) activity.runOnUiThread(()->{
			if(getActivity()!=null && AccountSessionManager.getInstance().tryGetAccount(accountID)==pinnedSession)
				Toast.makeText(activity, stringRes, Toast.LENGTH_SHORT).show();
		});
	}

	private void onNBWBindClick(ListItem<?> item){
		AccountSession session=AccountSessionManager.get(accountID);
		if(session.self.nbwUsername!=null){
			// 已绑定 → 解绑对话框
			new M3AlertDialogBuilder(getActivity())
					.setTitle(R.string.nbw_unbind_title)
					.setMessage(getString(R.string.nbw_unbind_message, session.self.nbwUsername))
					.setPositiveButton(R.string.nbw_unbind, (d, w)->unbindNBW())
					.setNegativeButton(R.string.cancel, null)
					.show();
		}else{
			// 未绑定 → 打开引导页
			Intent intent=new Intent(getActivity(), NBWPostRegisterActivity.class);
			intent.putExtra("show_back", true);
			startActivity(intent);
		}
	}

	private void unbindNBW(){
		new org.joinmastodon.android.api.requests.accounts.UnbindNBW()
				.setCallback(new me.grishka.appkit.api.Callback<>(){
					@Override
					public void onSuccess(Object result){
						if(getActivity()==null) return;
						getActivity().runOnUiThread(()->{
							android.widget.Toast.makeText(getActivity(), R.string.nbw_unbind_success, android.widget.Toast.LENGTH_SHORT).show();
							refreshNBWStatus();
						});
					}
					@Override
					public void onError(me.grishka.appkit.api.ErrorResponse error){
						if(getActivity()==null) return;
						getActivity().runOnUiThread(()->error.showToast(getActivity()));
					}
				})
				.exec(accountID);
	}
}
