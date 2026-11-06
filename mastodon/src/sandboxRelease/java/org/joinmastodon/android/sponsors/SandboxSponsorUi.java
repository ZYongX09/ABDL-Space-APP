package org.joinmastodon.android.sponsors;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.joinmastodon.android.R;

/** Minimal real native entry form and the existing payment view, also used by offline Canvas tests. */
final class SandboxSponsorUi{
	interface Actions extends SponsorPaymentUi.Actions{
		void pair(String code);
		void showPair();
	}
	final LinearLayout root;
	final Context context;
	final Actions actions;
	EditText code;
	Button pair;
	TextView pairingStatus, localEntitlement;
	SponsorPaymentUi payment;

	SandboxSponsorUi(Context context, Actions actions){
		this.context=context; this.actions=actions;
		root=new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(SponsorUi.surface(context));
		root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
	}
	void entry(String origin, String status){
		root.removeAllViews(); payment=null;
		ScrollView scroll=new ScrollView(context); scroll.setFillViewport(true);
		LinearLayout content=SponsorUi.column(context); scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
		SponsorUi.headline(content, context.getString(R.string.sandbox_app_name));
		SponsorUi.text(content, context.getString(R.string.sandbox_warning), false);
		SponsorUi.label(content, context.getString(R.string.sandbox_origin, origin));
		SponsorUi.label(content, context.getString(R.string.sandbox_pair_hint));
		code=new EditText(context); code.setSingleLine(true);
		code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		code.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_DONE);
		code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
		code.setSaveEnabled(false); code.setSaveFromParentEnabled(false);
		code.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
		code.setContentDescription(context.getString(R.string.sandbox_pair_hint));
		content.addView(code, new LinearLayout.LayoutParams(-1, -2));
		pair=SponsorUi.button(content, context.getString(R.string.sandbox_pair), ()->{
			String input=code.getText().toString(); code.getText().clear(); actions.pair(input);
		});
		pairingStatus=SponsorUi.text(content, status, false);
		pairingStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
	}
	void pairing(String text, boolean busy){
		if(pairingStatus!=null) pairingStatus.setText(text);
		if(pair!=null) pair.setEnabled(!busy);
		if(code!=null) code.setEnabled(!busy);
	}
	void checkout(String origin){
		if(code!=null) code.getText().clear(); code=null; pair=null; pairingStatus=null;
		root.removeAllViews();
		payment=new SponsorPaymentUi(context, actions);
		LinearLayout warning=SponsorUi.column(context);
		SponsorUi.text(warning, context.getString(R.string.sandbox_app_name), true);
		SponsorUi.label(warning, context.getString(R.string.sandbox_warning));
		SponsorUi.label(warning, context.getString(R.string.sandbox_origin, origin));
		SponsorUi.textButton(warning, context.getString(R.string.sandbox_pair), actions::showPair);
		payment.content.addView(warning, 0);
		localEntitlement=SponsorUi.label(payment.content, ""); localEntitlement.setVisibility(View.GONE);
		root.addView(payment.root, new LinearLayout.LayoutParams(-1, -1));
	}
	void render(SponsorPaymentCoordinator state){
		if(payment==null) return;
		payment.render(state);
		payment.afdian.update(false, false, "disabled");
		payment.afdian.reason.setText(R.string.sandbox_afdian_disabled);
		payment.afdian.view.setContentDescription(context.getString(R.string.sandbox_afdian_disabled));
		if(state.confirmed()) payment.status.setText(R.string.sandbox_local_granted);
	}
	void granted(){
		if(localEntitlement!=null){ localEntitlement.setVisibility(View.VISIBLE); localEntitlement.setText(R.string.sandbox_local_granted); }
	}
}
