package org.joinmastodon.android.fragments.albums;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.albums.AlbumRequest;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.albums.AlbumModels.Report;
import org.joinmastodon.android.model.albums.AlbumModels.ReportResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.ToolbarFragment;

/**
 * Album report page. Own logic and DTOs only; the shared look follows the existing post report
 * page, but the albums API contract is handled entirely here.
 */
public class AlbumReportFragment extends ToolbarFragment{
	private String accountId, albumId;
	private LinearLayout reasonList;
	private EditText detailInput;
	private Button submitButton;
	private int selectedReason=-1;
	private boolean submitting;
	/** True once a successful submit asked to close the page; exposed for behavior tests. */
	boolean finished;
	private MastodonAPIRequest<ReportResponse> request;

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		Bundle args=getArguments()==null ? new Bundle() : getArguments();
		accountId=args.getString("account");
		albumId=args.getString("albumId", args.getString("album_id"));
	}

	@Override
	public void onAttach(Activity activity){
		super.onAttach(activity);
		setTitle(R.string.album_report_title);
	}

	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState){
		View view=inflater.inflate(R.layout.album_report, container, false);
		reasonList=view.findViewById(R.id.album_report_reasons);
		detailInput=view.findViewById(R.id.album_report_detail);
		submitButton=view.findViewById(R.id.album_report_submit);
		String[] labels=getResources().getStringArray(R.array.album_report_reasons);
		LayoutInflater rowInflater=LayoutInflater.from(getActivity());
		for(int i=0;i<labels.length;i++){
			View row=rowInflater.inflate(R.layout.album_report_reason_item, reasonList, false);
			TextView label=row.findViewById(R.id.album_report_reason_label);
			label.setText(labels[i]);
			row.setContentDescription(labels[i]);
			int index=i;
			row.setOnClickListener(v->selectReason(index));
			reasonList.addView(row);
		}
		if(savedInstanceState!=null){
			selectedReason=savedInstanceState.getInt("reason", -1);
			String detail=savedInstanceState.getString("detail");
			if(detail!=null) detailInput.setText(detail);
		}
		applyReasonSelection();
		submitButton.setOnClickListener(v->submitReport());
		updateSubmitState();
		return view;
	}

	@Override
	public void onSaveInstanceState(Bundle state){
		super.onSaveInstanceState(state);
		state.putInt("reason", selectedReason);
		if(detailInput!=null) state.putString("detail", detailInput.getText().toString());
	}

	private void selectReason(int index){
		if(submitting) return;
		selectedReason=index;
		applyReasonSelection();
		updateSubmitState();
	}

	private void applyReasonSelection(){
		if(reasonList==null) return;
		for(int i=0;i<reasonList.getChildCount();i++){
			RadioButton radio=reasonList.getChildAt(i).findViewById(R.id.album_report_reason_radio);
			radio.setChecked(i==selectedReason);
		}
	}

	private void updateSubmitState(){
		if(submitButton==null) return;
		submitButton.setEnabled(selectedReason>=0 && !submitting);
	}

	private boolean sessionValid(){
		return accountId!=null && accountId.equals(AccountSessionManager.getInstance().getLastActiveAccountID())
				&& AccountSessionManager.getInstance().tryGetAccount(accountId)!=null;
	}

	private void submitReport(){
		if(submitting || selectedReason<0 || selectedReason>=getResources().getStringArray(R.array.album_report_reasons).length) return;
		if(albumId==null || !albumId.matches("[A-Za-z0-9_-]{1,128}")){
			Toast.makeText(getActivity(), R.string.album_report_failed, Toast.LENGTH_SHORT).show();
			return;
		}
		if(!sessionValid()){
			Toast.makeText(getActivity(), R.string.album_session_changed, Toast.LENGTH_SHORT).show();
			return;
		}
		String reason=getResources().getStringArray(R.array.album_report_reason_values)[selectedReason];
		String detail=detailInput.getText().toString().trim();
		Map<String, Object> body=new HashMap<>();
		body.put("reason", reason);
		if(!detail.isEmpty()) body.put("detail", detail);
		body.put("operation_id", UUID.randomUUID().toString());
		submitting=true;
		updateSubmitState();
		request=AlbumRequest.post("/"+albumId+"/report", ReportResponse.class, body)
			.setCallback(new Callback<>(){
				@Override
				public void onSuccess(ReportResponse result){
					request=null;
					submitting=false;
					if(!validReport(result)){
						if(getActivity()!=null) Toast.makeText(getActivity(), R.string.album_invalid_response, Toast.LENGTH_SHORT).show();
						updateSubmitState();
						return;
					}
					if(getActivity()!=null) Toast.makeText(getActivity(), R.string.album_report_submitted, Toast.LENGTH_SHORT).show();
					closePage();
				}

				@Override
				public void onError(ErrorResponse error){
					request=null;
					submitting=false;
					if(getActivity()!=null) error.showToast(getActivity());
					updateSubmitState();
				}
			})
			.exec(accountId);
	}

	/** Only an acknowledged open report closes the page; anything else keeps the draft editable. */
	private boolean validReport(ReportResponse result){
		if(result==null || result.report==null) return false;
		Report report=result.report;
		String[] values=getResources().getStringArray(R.array.album_report_reason_values);
		return report.id!=null && albumId!=null && albumId.equals(report.albumId)
				&& selectedReason>=0 && selectedReason<values.length && values[selectedReason].equals(report.reason)
				&& "open".equals(report.status);
	}

	void closePage(){
		finished=true;
		Activity activity=getActivity();
		if(activity==null) return;
		if(activity instanceof me.grishka.appkit.FragmentStackActivity) Nav.finish(this);
		else activity.onBackPressed();
	}

	@Override
	public void onDestroyView(){
		if(request!=null){
			request.cancel();
			request=null;
		}
		submitting=false;
		super.onDestroyView();
	}
}
