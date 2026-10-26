package org.joinmastodon.android.ui.sheets;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.NBWForumOptionsRepository;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.util.function.Consumer;

import me.grishka.appkit.views.BottomSheet;

/** Account-default consent only. Classification remains in the editor's anchored miuix menu. */
public final class NBWFallbackConsentSheet extends BottomSheet{
	private final TextView status, result;
	private final Button accept, decline;
	private final Consumer<Boolean> onConsent;
	private boolean saving, canWrite;

	public NBWFallbackConsentSheet(Context context, Consumer<Boolean> onConsent){
		super(context);
		this.onConsent=onConsent;
		View content=LayoutInflater.from(context).inflate(R.layout.sheet_nbw_fallback_consent, null);
		status=content.findViewById(R.id.nbw_fallback_status);
		result=content.findViewById(R.id.nbw_fallback_result);
		accept=content.findViewById(R.id.nbw_fallback_accept);
		decline=content.findViewById(R.id.nbw_fallback_decline);
		accept.setOnClickListener(v->submit(true));
		decline.setOnClickListener(v->submit(false));
		content.findViewById(R.id.nbw_fallback_done).setOnClickListener(v->dismiss());
		setContentView(content);
		// The shared AppKit sheet container owns system-bar/safe-area padding.
		setNavigationBarBackground(new ColorDrawable(UiUtils.alphaBlendColors(
				UiUtils.getThemeColor(context, R.attr.colorM3Surface),
				UiUtils.getThemeColor(context, R.attr.colorM3Primary), 0.05f)), !UiUtils.isDarkTheme());
	}

	public void updateSnapshot(NBWForumOptionsRepository.Snapshot snapshot){
		canWrite=snapshot.canWrite();
		saving=snapshot.consentSaving;
		status.setText(snapshot.hasFallbackConsent() ? R.string.nbw_v2_fallback_accepted : R.string.nbw_v2_fallback_not_accepted);
		accept.setEnabled(canWrite && !saving && !snapshot.hasFallbackConsent());
		decline.setEnabled(canWrite && !saving);
	}

	private void submit(boolean accepted){
		if(!isShowing() || dismissed || saving || !canWrite || !(accepted ? accept : decline).isEnabled()) return;
		// Disable repeat taps immediately; only a capability readback may change accepted status.
		saving=true;
		accept.setEnabled(false);
		decline.setEnabled(false);
		result.setText(R.string.nbw_v2_consent_saving);
		result.setVisibility(View.VISIBLE);
		onConsent.accept(accepted);
	}

	public void showResult(int message){
		if(!isShowing() || dismissed) return;
		result.setText(message);
		result.setVisibility(View.VISIBLE);
	}
}
