package org.joinmastodon.android.fragments.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.verification.VerificationRequest;
import org.joinmastodon.android.model.verification.VerificationModels.VerifyResult;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.ToolbarFragment;
import me.grishka.appkit.utils.V;

/** Native, no-browser verifier for https://abdl-space.top/c/<token>. */
public class BabyVerificationResultFragment extends ToolbarFragment{
	private String token;
	private LinearLayout content;
	private VerificationRequest<VerifyResult> request;
	private int generation;
	private boolean viewReady;

	@Override public void onCreate(Bundle state){
		super.onCreate(state);
		token=getArguments().getString("token");
		setTitle(R.string.verification_verify_title);
	}

	@Override public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle state){
		content=new LinearLayout(getActivity());
		content.setOrientation(LinearLayout.VERTICAL);
		content.setPadding(V.dp(24),V.dp(32),V.dp(24),V.dp(32));
		viewReady=true;
		load();
		return content;
	}

	private void load(){
		if(!viewReady) return;
		if(request!=null) request.cancel();
		int tokenGeneration=++generation;
		content.removeAllViews();
		ProgressBar progress=new ProgressBar(getActivity());
		content.addView(progress,new LinearLayout.LayoutParams(-1,-2));
		VerificationRequest<VerifyResult> next=VerificationRequest.verifyCertificate(token);
		request=next;
		next.setCallback(new Callback<>(){
			@Override public void onSuccess(VerifyResult result){
				if(!live(tokenGeneration, next)) return;
				request=null;
				render(result);
			}
			@Override public void onError(ErrorResponse error){
				if(!live(tokenGeneration, next)) return;
				request=null;
				renderNetworkError();
			}
		});
		next.execNoAuth("api.abdl-space.top");
	}

	private void renderNetworkError(){
		content.removeAllViews();
		text(getString(R.string.verification_verify_network_error), true);
		Button retry=new Button(getActivity(),null,0,R.style.Widget_Mastodon_M3_Button_Tonal);
		retry.setText(R.string.verification_retry);
		retry.setOnClickListener(v->load());
		content.addView(retry,new LinearLayout.LayoutParams(-1,V.dp(52)));
	}

	private void render(VerifyResult result){
		content.removeAllViews();
		switch(result.status){
			case "active" -> {
				text(getString(R.string.verification_verify_valid),true);
				text(getString(R.string.verification_verify_user,result.username),false);
				text(getString(R.string.verification_verify_issued,formatTime(result.issuedAt)),false);
				text(getString(R.string.verification_verify_generation,result.generation),false);
			}
			case "superseded" -> {
				text(getString(R.string.verification_verify_status_superseded),true);
				text(getString(R.string.verification_verify_superseded),false);
				text(getString(R.string.verification_verify_superseded_at,formatTime(result.supersededAt)),false);
			}
			case "revoked" -> {
				text(getString(R.string.verification_verify_status_revoked),true);
				text(getString(R.string.verification_verify_revoked),false);
				text(getString(R.string.verification_verify_revoked_at,formatTime(result.revokedAt)),false);
					text(getString(R.string.verification_verify_reason,result.revokeReason),false);
			}
			case "unknown" -> {
				text(getString(R.string.verification_verify_status_unknown),true);
				text(getString(R.string.verification_verify_unknown),false);
			}
		}
	}

	private String formatTime(Long seconds){
		if(seconds==null || seconds<=0) return "—";
		return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()));
	}

	private void text(String value,boolean heading){
		TextView view=new TextView(getActivity());
		view.setText(value);
		view.setTextAppearance(heading?R.style.m3_title_large:R.style.m3_body_large);
		view.setTextColor(UiUtils.getThemeColor(getActivity(),heading?R.attr.colorM3OnSurface:R.attr.colorM3OnSurfaceVariant));
		LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
		lp.bottomMargin=V.dp(16);
		content.addView(view,lp);
	}

	private boolean live(int expectedGeneration, VerificationRequest<?> expected){
		return viewReady && generation==expectedGeneration && request==expected && getActivity()!=null;
	}

	@Override public void onDestroyView(){
		viewReady=false;
		generation++;
		if(request!=null) request.cancel();
		request=null;
		content=null;
		super.onDestroyView();
	}
}
