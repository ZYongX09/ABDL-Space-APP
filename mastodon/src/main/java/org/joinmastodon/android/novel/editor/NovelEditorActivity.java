package org.joinmastodon.android.novel.editor;

import android.os.Bundle;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.ui.utils.UiUtils;

import androidx.annotation.Nullable;
import me.grishka.appkit.FragmentStackActivity;

/** Entry host for the new local-first novel experience (square + creation). */
public class NovelEditorActivity extends FragmentStackActivity{
	public static final String EXTRA_ACCOUNT_ID="account";
	@Override protected void onCreate(@Nullable Bundle savedInstanceState){
		String accountID=getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
		AccountSession session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		if(session!=null) UiUtils.setUserPreferredTheme(this, session);
		super.onCreate(savedInstanceState);
		if(session==null){ finish(); return; }
		if(savedInstanceState==null){
			Bundle args=new Bundle(); args.putString("account", accountID);
			NovelWorkspaceFragment fragment=new NovelWorkspaceFragment(); fragment.setArguments(args); showFragment(fragment);
			overridePendingTransition(R.anim.fragment_enter, R.anim.no_op_300ms);
		}
	}
	@Override public void finish(){ super.finish(); overridePendingTransition(0, R.anim.fragment_exit); }
}
