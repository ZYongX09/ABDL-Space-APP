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
	@Override public void onCreate(Bundle state){ super.onCreate(state); token=getArguments().getString("token"); setTitle(R.string.verification_verify_title); }
	@Override public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle state){ content=new LinearLayout(getActivity()); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(V.dp(24),V.dp(32),V.dp(24),V.dp(32)); load(); return content; }
	private void load(){ content.removeAllViews(); ProgressBar progress=new ProgressBar(getActivity()); content.addView(progress,new LinearLayout.LayoutParams(-1,-2)); VerificationRequest<VerifyResult> next=VerificationRequest.verifyCertificate(token); request=next; next.setCallback(new Callback<>(){
		@Override public void onSuccess(VerifyResult result){ request=null; if(getActivity()==null) return; render(result); }
		@Override public void onError(ErrorResponse error){ request=null; if(getActivity()==null) return; content.removeAllViews(); text(error.toString(),true); Button retry=new Button(getActivity(),null,0,R.style.Widget_Mastodon_M3_Button_Tonal); retry.setText(R.string.verification_retry); retry.setOnClickListener(v->load()); content.addView(retry,new LinearLayout.LayoutParams(-1,V.dp(52))); }
	}); next.execNoAuth("api.abdl-space.top"); }
	private void render(VerifyResult result){ content.removeAllViews(); text(getString(result.valid ? R.string.verification_verify_valid : R.string.verification_verify_invalid),true); if(result.valid){ text(getString(R.string.verification_verify_user,result.username),false); String issued=DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(Instant.ofEpochSecond(result.issuedAt).atZone(ZoneId.systemDefault())); text(getString(R.string.verification_verify_issued,issued),false); text(getString(R.string.verification_verify_generation,result.generation),false); }else text(getString(switch(result.status){ case "superseded" -> R.string.verification_verify_superseded; case "revoked" -> R.string.verification_verify_revoked; default -> R.string.verification_verify_unknown; }),false); }
	private void text(String value,boolean heading){ TextView view=new TextView(getActivity()); view.setText(value); view.setTextAppearance(heading?R.style.m3_title_large:R.style.m3_body_large); view.setTextColor(UiUtils.getThemeColor(getActivity(),heading?R.attr.colorM3OnSurface:R.attr.colorM3OnSurfaceVariant)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=V.dp(16); content.addView(view,lp); }
	@Override public void onDestroy(){ if(request!=null) request.cancel(); super.onDestroy(); }
}
