package org.joinmastodon.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.joinmastodon.android.model.verification.VerificationLink;

/** Minimal exported App Link gate; all application UI remains inside the non-exported MainActivity. */
public class VerificationLinkActivity extends Activity{
	@Override
	protected void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		Uri uri=getIntent()==null ? null : getIntent().getData();
		if(VerificationLink.parseToken(uri)!=null){
			Intent intent=new Intent(this, MainActivity.class).setAction(Intent.ACTION_VIEW).setData(uri);
			startActivity(intent);
		}
		finish();
	}
}
