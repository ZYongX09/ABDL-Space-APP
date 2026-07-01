package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;

import org.joinmastodon.android.R;

public class NBWNotBoundActivity extends Activity {

	@Override
	protected void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_nbw_not_bound);

		Button backBtn = findViewById(R.id.btn_back);
		backBtn.setOnClickListener(v -> finish());
	}
}
