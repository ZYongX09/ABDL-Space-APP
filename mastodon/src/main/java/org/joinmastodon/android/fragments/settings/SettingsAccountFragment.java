package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.MainActivity;
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

import java.util.ArrayList;
import java.util.Locale;

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
	private DonationSheet donationSheet;
	private boolean loggedOut;
	private ListItem<Void> nbwItem;

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		AccountSession account=AccountSessionManager.get(accountID);
		setTitle(account.getFullUsername());

		ArrayList<ListItem<Void>> items=new ArrayList<>();
		items.add(new SectionHeaderListItem(R.string.account_settings));
		items.add(new ListItem<>(R.string.settings_privacy, 0, R.drawable.ic_privacy_tip_24px, this::onPrivacyClick));
		items.add(new ListItem<>(R.string.settings_filters, 0, R.drawable.ic_filter_alt_24px, this::onFiltersClick));
		items.add(new ListItem<>(R.string.settings_notifications, 0, R.drawable.ic_notifications_24px, this::onNotificationsClick));
		items.add(new ListItem<>(R.string.settings_posting_defaults, 0, R.drawable.ic_edit_square_24px, this::onPostingDefaultsClick));

		// 第三方账户
		AccountSession acctSession = AccountSessionManager.get(accountID);
		String nbwStatus = acctSession.self.nbwUsername != null ? acctSession.self.nbwUsername : getString(R.string.nbw_unbound);
		items.add(new SectionHeaderListItem(R.string.nbw_third_party_account));
		nbwItem = new ListItem<>(getString(R.string.nbw_third_party_nbw), nbwStatus, R.drawable.ic_nbw, this::onNBWBindClick);
		items.add(nbwItem);

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
	}

	private void refreshNBWStatus(){
		if(nbwItem == null) return;
		new GetOwnAccount()
			.setCallback(new me.grishka.appkit.api.Callback<Account>(){
				@Override
				public void onSuccess(Account account){
					if(getActivity() == null) return;
					getActivity().runOnUiThread(()->{
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
		if(requestCode==DONATION_RESULT){
			if(donationSheet!=null)
				donationSheet.dismissWithoutAnimation();
			if(resultCode==Activity.RESULT_OK){
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

	private boolean useStagingEnvironmentForDonations(){
		return (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE.equals("appcenterPrivateBeta")) && getActivity().getSharedPreferences("debug", Context.MODE_PRIVATE).getBoolean("donationsStaging", false);
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
			Intent intent=new Intent(getActivity(), NBWBindGuideActivity.class);
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
							android.widget.Toast.makeText(getActivity(), R.string.nbw_unbound, android.widget.Toast.LENGTH_SHORT).show();
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
