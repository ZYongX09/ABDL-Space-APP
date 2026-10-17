package org.joinmastodon.android.fragments.sponsors;

import android.os.Bundle;

import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.FragmentStackActivity;

/** Separate stack above ComposeAboutActivity: never CLEAR_TOP or finish the about screen. */
public class SponsorThanksActivity extends FragmentStackActivity{
	public static final String EXTRA_ACCOUNT="account";
	@Override protected void onCreate(Bundle state){
		String account=getIntent().getStringExtra(EXTRA_ACCOUNT);
		UiUtils.setUserPreferredTheme(this, AccountSessionManager.getInstance().tryGetAccount(account));
		super.onCreate(state);
		if(state==null){
			Bundle args=new Bundle(); args.putString(EXTRA_ACCOUNT, account);
			SponsorThanksFragment fragment=new SponsorThanksFragment(); fragment.setArguments(args);
			showFragment(fragment);
		}
	}
}
