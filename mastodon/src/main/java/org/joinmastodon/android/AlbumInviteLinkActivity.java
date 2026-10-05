package org.joinmastodon.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.joinmastodon.android.albums.AlbumInviteLink;

public class AlbumInviteLinkActivity extends Activity{
	@Override protected void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		org.joinmastodon.android.security.AppSecurity.runAfterUnlock(this, ()->{
			Uri uri=getIntent()==null ? null : getIntent().getData();
			if(AlbumInviteLink.parseToken(uri)!=null)
				startActivity(new Intent(this, MainActivity.class).setAction(Intent.ACTION_VIEW).setData(uri));
			finish();
		});
	}
}
