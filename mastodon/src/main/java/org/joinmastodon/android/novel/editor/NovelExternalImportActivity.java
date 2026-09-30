package org.joinmastodon.android.novel.editor;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.ui.utils.UiUtils;

import androidx.annotation.Nullable;
import me.grishka.appkit.FragmentStackActivity;

/** Copies external VIEW/SEND documents into the local creation centre, never the private cloud shelf. */
public class NovelExternalImportActivity extends FragmentStackActivity{
	private static final int REQUEST_UNLOCK=12022;
	private boolean awaitingUnlock;

	@Override protected void onCreate(@Nullable Bundle state){
		String accountID=AccountSessionManager.getInstance().getLastActiveAccountID();
		var session=AccountSessionManager.getInstance().tryGetAccount(accountID); if(session!=null)UiUtils.setUserPreferredTheme(this,session);
		super.onCreate(state); if(session==null){finish();return;}
		if(org.joinmastodon.android.security.AppSecurity.isLocked()){
			awaitingUnlock=true;
			startActivityForResult(new Intent(this, org.joinmastodon.android.security.ui.lock.LockActivity.class), REQUEST_UNLOCK);
			return;
		}
		if(state==null){Uri uri=document(getIntent());if(uri==null){finish();return;}showFragment(NovelWorkspaceFragment.newInstance(accountID,uri,getIntent().getFlags()));}
	}

	@Override protected void onActivityResult(int requestCode, int resultCode, Intent data){
		super.onActivityResult(requestCode, resultCode, data);
		if(requestCode==REQUEST_UNLOCK && awaitingUnlock){
			awaitingUnlock=false;
			if(resultCode==RESULT_OK) onCreate(null);
			else finish();
		}
	}

	private static Uri document(Intent intent){
		if(Intent.ACTION_VIEW.equals(intent.getAction()))return intent.getData();
		if(Intent.ACTION_SEND.equals(intent.getAction()))return Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri.class):getLegacy(intent);
		return null;
	}
	@SuppressWarnings("deprecation") private static Uri getLegacy(Intent intent){return intent.getParcelableExtra(Intent.EXTRA_STREAM);}
}
