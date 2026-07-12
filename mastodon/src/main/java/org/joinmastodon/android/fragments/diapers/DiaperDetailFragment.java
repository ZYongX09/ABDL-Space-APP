package org.joinmastodon.android.fragments.diapers;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.joinmastodon.android.R;

import me.grishka.appkit.fragments.LoaderFragment;

public class DiaperDetailFragment extends LoaderFragment {
	private int diaperId;
	private String accountID;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		diaperId = getArguments() != null ? getArguments().getInt("diaper_id", 0) : 0;
		accountID = getArguments() != null ? getArguments().getString("account") : null;
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		setTitle(getString(R.string.diaper_detail));
	}

	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		FrameLayout wrapper = new FrameLayout(getContext());
		wrapper.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		TextView placeholder = new TextView(getContext());
		placeholder.setText("Diaper Detail: " + diaperId);
		placeholder.setPadding(32, 32, 32, 32);
		wrapper.addView(placeholder);

		return wrapper;
	}

	@Override
	protected void doLoadData() {
		dataLoaded();
	}

	@Override
	public void onRefresh() {
		loadData();
	}
}
