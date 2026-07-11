package org.joinmastodon.android.fragments;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.friendrequests.ReportFriendRequest;

import java.util.Map;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.ToolbarFragment;

public class FriendRequestReportFragment extends ToolbarFragment {
	private String requestId;
	private String requestTitle;
	private String accountID;
	private EditText reasonInput;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestId = getArguments().getString("requestId");
		requestTitle = getArguments().getString("requestTitle");
		accountID = getArguments().getString("account");
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		setTitle(getString(R.string.friend_request_report));
	}

	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_friend_request_report, container, false);

		reasonInput = view.findViewById(R.id.report_reason);

		view.findViewById(R.id.report_submit_btn).setOnClickListener(v -> submitReport());

		return view;
	}

	private void submitReport() {
		String reason = reasonInput.getText().toString().trim();
		if (reason.isEmpty()) {
			Toast.makeText(getContext(), "请填写举报原因", Toast.LENGTH_SHORT).show();
			return;
		}

		new ReportFriendRequest(requestId, reason, null)
			.setCallback(new Callback<Map<String, Object>>() {
				@Override
				public void onSuccess(Map<String, Object> result) {
					Toast.makeText(getContext(), "举报已提交，感谢您的反馈", Toast.LENGTH_SHORT).show();
					getActivity().onBackPressed();
				}

				@Override
				public void onError(ErrorResponse error) {
					error.showToast(getContext());
				}
			})
			.exec(accountID);
	}
}
