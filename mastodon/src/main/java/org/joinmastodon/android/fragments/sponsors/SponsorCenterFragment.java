package org.joinmastodon.android.fragments.sponsors;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.R;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.sponsors.SponsorAvailability;
import org.joinmastodon.android.api.requests.sponsors.SponsorRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.sponsors.SponsorRedemptionRetry;
import org.joinmastodon.android.model.sponsors.SponsorModels.*;
import org.joinmastodon.android.sponsors.SponsorOperation;
import org.joinmastodon.android.sponsors.SponsorRefresh;
import org.joinmastodon.android.sponsors.SponsorUi;
import org.joinmastodon.android.ui.OutlineProviders;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.LoaderFragment;
import me.grishka.appkit.utils.V;

public class SponsorCenterFragment extends LoaderFragment{
	private TextView centeredTitle, paymentHint;
	private String accountID, selectedPlanID;
	private Catalog catalog;
	private Me me;
	private LinearLayout content, statusContent, plansContent, benefitsContent, redeemContent, historyContent;
	private EditText codeInput;
	private Button redeemButton, purchaseButton;
	private TextView redeemNote, stateNotice, checkoutSummary, operationStatus;
	private LinearLayout checkout;
	private AccountSession session;
	private SponsorRedemptionRetry redemptionRetry;
	private boolean unsupported, busy, refreshing, historyLoading, fresh;
	private int generation, historyGeneration, historyOffset;
	private String redeemCode, redeemOperation;
	private final Handler main=new Handler(Looper.getMainLooper());
	private final Map<String, LinearLayout> planCards=new HashMap<>();
	private final Runnable expiryRefresh=()->{ if(content!=null && !busy && !refreshing){ fresh=false; onRefresh(); } };
	private final Map<String, String> claimOperations=new HashMap<>();
	private final Map<View, BooleanSupplier> controls=new HashMap<>();
	private final ArrayList<MastodonAPIRequest<?>> requests=new ArrayList<>();

	@Override public void onCreate(Bundle state){
		super.onCreate(state);
		accountID=getArguments().getString("account");
		session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		var prefs=getActivity().getSharedPreferences("sponsor_redemption_retry", Context.MODE_PRIVATE);
		redemptionRetry=new SponsorRedemptionRetry(new SponsorRedemptionRetry.Store(){
			@Override public String read(String key){ return prefs.getString(key, null); }
			@Override public boolean write(String key, String value){ return prefs.edit().putString(key, value).commit(); }
		});
		if(state!=null) selectedPlanID=state.getString("selectedPlan");
		setTitle(R.string.sponsor_ui_center);
		loadData();
	}
	@Override public void onSaveInstanceState(Bundle state){
		super.onSaveInstanceState(state); state.putString("selectedPlan", selectedPlanID);
		// Raw redemption codes are deliberately never saved in a Bundle or preferences.
	}
	@Override public void onViewCreated(View view, Bundle state){
		super.onViewCreated(view, state);
		installCenteredTitle(view.findViewById(R.id.toolbar));
	}
	@Override public void onUpdateToolbar(){
		super.onUpdateToolbar();
		installCenteredTitle(getToolbar());
	}
	private void installCenteredTitle(android.widget.Toolbar toolbar){
		if(toolbar==null) return;
		if(centeredTitle==null || centeredTitle.getParent()!=toolbar){
			if(centeredTitle!=null && centeredTitle.getParent() instanceof ViewGroup oldParent) oldParent.removeView(centeredTitle);
			centeredTitle=new TextView(getActivity());
			centeredTitle.setGravity(Gravity.CENTER);
			centeredTitle.setTextAppearance(R.style.m3_title_large);
			centeredTitle.setTextColor(org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurface));
			android.widget.Toolbar.LayoutParams params=new android.widget.Toolbar.LayoutParams(-2, -1, Gravity.CENTER);
			toolbar.addView(centeredTitle, params);
		}
		centeredTitle.setText(getTitle());
		toolbar.setTitle("");
	}

	@Override public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle state){
		LinearLayout root=new LinearLayout(getActivity()); root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(SponsorUi.surface(getActivity()));
		ScrollView scroll=new ScrollView(getActivity()); scroll.setFillViewport(true); scroll.setClipToPadding(false);
		root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
		content=SponsorUi.column(getActivity()); content.setPadding(V.dp(12), V.dp(8), V.dp(12), V.dp(24)); content.setFocusableInTouchMode(true); scroll.addView(content);
		statusContent=section(content);
		stateNotice=SponsorUi.label(content, ""); stateNotice.setVisibility(View.GONE);
		stateNotice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
		plansContent=section(content); benefitsContent=section(content);
		redeemContent=SponsorUi.designCard(content);
		SponsorUi.text(redeemContent, getString(R.string.sponsor_ui_redeem_title), true);
		codeInput=new EditText(getActivity(), null, 0, R.style.Widget_Mastodon_M3_EditText);
		codeInput.setHint(R.string.sponsor_ui_code_hint); codeInput.setSingleLine(true);
		codeInput.setMinHeight(V.dp(56)); codeInput.setPadding(V.dp(16), V.dp(12), V.dp(16), V.dp(12));
		codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		codeInput.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
		codeInput.setSaveEnabled(false); codeInput.setSaveFromParentEnabled(false);
		if(android.os.Build.VERSION.SDK_INT>=26) codeInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
		codeInput.setOnEditorActionListener((v, action, event)->{ if(action==EditorInfo.IME_ACTION_DONE){ redeem(); return true; } return false; });
		redeemContent.addView(codeInput, new LinearLayout.LayoutParams(-1, -2));
		redeemButton=SponsorUi.secondary(redeemContent, getString(R.string.sponsor_ui_redeem), this::redeem);
		redeemNote=SponsorUi.label(redeemContent, getString(R.string.sponsor_ui_permanent_redeem));
		operationStatus=SponsorUi.label(redeemContent, ""); operationStatus.setVisibility(View.GONE);
		operationStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
		historyContent=SponsorUi.designCard(content);
		resetHistory();
		checkout=SponsorUi.column(getActivity()); checkout.setPadding(V.dp(16), 0, V.dp(16), 0);
		checkout.setBackgroundColor(SponsorUi.surface(getActivity()));
		root.addView(checkout, new LinearLayout.LayoutParams(-1, -2));
		checkoutSummary=SponsorUi.label(checkout, getString(R.string.sponsor_ui_choose)); checkoutSummary.setVisibility(View.GONE);
		purchaseButton=SponsorUi.button(checkout, getString(R.string.sponsor_ui_continue), this::openPurchase);
		purchaseButton.setMinHeight(V.dp(52));
		purchaseButton.setPadding(V.dp(20), V.dp(8), V.dp(20), V.dp(8));
		purchaseButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
		LinearLayout.LayoutParams purchaseParams=(LinearLayout.LayoutParams)purchaseButton.getLayoutParams();
		purchaseParams.topMargin=0;
		purchaseButton.setLayoutParams(purchaseParams);
		paymentHint=plainText(getString(R.string.sponsor_ui_purchase_hint), 11, false,
				org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurfaceVariant));
		paymentHint.setGravity(Gravity.CENTER);
		checkout.addView(paymentHint, new LinearLayout.LayoutParams(-1, V.dp(20)));
		render();
		return root;
	}
	private LinearLayout section(LinearLayout parent){
		LinearLayout section=new LinearLayout(parent.getContext()); section.setOrientation(LinearLayout.VERTICAL);
		parent.addView(section, new LinearLayout.LayoutParams(-1, -2)); return section;
	}
	@Override public void onApplyWindowInsets(WindowInsets insets){
		if(content!=null) content.setPadding(V.dp(12)+insets.getSystemWindowInsetLeft(), V.dp(8), V.dp(12)+insets.getSystemWindowInsetRight(), V.dp(20)+(checkout!=null && checkout.getVisibility()==View.VISIBLE ? 0 : insets.getSystemWindowInsetBottom()));
		if(checkout!=null) checkout.setPadding(V.dp(16)+insets.getSystemWindowInsetLeft(), 0, V.dp(16)+insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
		super.onApplyWindowInsets(insets.replaceSystemWindowInsets(0, insets.getSystemWindowInsetTop(), 0, 0));
	}
	@Override public void onRefresh(){ if(!busy && !refreshing) loadData(); }
	@Override protected void onShown(){
		super.onShown();
		if(content!=null && !busy && !refreshing) loadData();
	}
	@Override protected void onHidden(){
		main.removeCallbacks(expiryRefresh);
		super.onHidden();
	}
	private boolean sessionValid(){ return session!=null && AccountSessionManager.getInstance().tryGetAccount(accountID)==session; }
	private boolean live(int token){ return token==generation && sessionValid() && getActivity()!=null; }
	@Override protected void doLoadData(){
		if(busy || refreshing) return;
		if(!sessionValid()){
			fresh=false;
			onError(new org.joinmastodon.android.api.MastodonErrorResponse(getString(R.string.sponsor_ui_session_changed), 401, null));
			return;
		}
		refreshing=true; fresh=false; updateActions();
		int token=++generation;
		currentRequest=SponsorAvailability.check(accountID, new Callback<>(){
			@Override public void onSuccess(Boolean supported){
				// Availability may answer synchronously; continue only after its request reference is assigned.
				main.post(()->{
					if(!live(token)) return;
					unsupported=!Boolean.TRUE.equals(supported);
					if(unsupported){ currentRequest=null; refreshing=false; dataLoaded(); render(); return; }
					loadCatalog(token);
				});
			}
			@Override public void onError(ErrorResponse error){ main.post(()->fail(token, error)); }
		});
	}
	private void loadCatalog(int token){
		currentRequest=SponsorRequest.catalog().setCallback(new Callback<>(){
			@Override public void onSuccess(Catalog nextCatalog){
				if(!live(token)) return;
				currentRequest=SponsorRequest.me().setCallback(new Callback<>(){
					@Override public void onSuccess(Me result){
						if(!live(token)) return;
						if(result.configVersion!=nextCatalog.config.version){
							fail(token, new org.joinmastodon.android.api.MastodonErrorResponse(getString(R.string.sponsor_ui_stale), 409, null));
							return;
						}
						catalog=nextCatalog; me=result; currentRequest=null; refreshing=false; fresh=true;
						SponsorRefresh.apply(accountID, result);
						dataLoaded(); render(); scheduleExpiry();
					}
					@Override public void onError(ErrorResponse error){ fail(token, error); }
				}).exec(accountID);
			}
			@Override public void onError(ErrorResponse error){ fail(token, error); }
		}).exec(accountID);
	}
	private void fail(int token, ErrorResponse error){
		if(token!=generation) return;
		currentRequest=null; refreshing=false; fresh=false; updateActions();
		if(getActivity()!=null){ if(me==null) onError(error); else error.showToast(getActivity()); }
	}
	private void scheduleExpiry(){
		main.removeCallbacks(expiryRefresh);
		if(me==null || isHidden()) return;
		long deadline=me.quota.resetsAt;
		if(me.sponsor.isActive() && !me.sponsor.permanent && me.sponsor.expiresAt!=null) deadline=Math.min(deadline, me.sponsor.expiresAt);
		main.postDelayed(expiryRefresh, Math.max(1, deadline-System.currentTimeMillis()/1000)*1000);
	}
	private boolean enabled(){ return fresh && sessionValid() && !unsupported && catalog!=null && me!=null && Boolean.TRUE.equals(catalog.config.enabled) && me.configVersion==catalog.config.version; }
	private void gate(View view, BooleanSupplier eligible){ controls.put(view, eligible); view.setEnabled(!busy && !refreshing && eligible.getAsBoolean()); }
	private void updateActions(){
		for(Map.Entry<View, BooleanSupplier> control:controls.entrySet()) control.getKey().setEnabled(!busy && !refreshing && control.getValue().getAsBoolean());
		if(redeemButton!=null){
			redeemButton.setEnabled(!busy && !refreshing && enabled() && !me.sponsor.permanent);
			redeemButton.setText(busy ? R.string.sponsor_ui_processing : R.string.sponsor_ui_redeem);
		}
		if(purchaseButton!=null) purchaseButton.setEnabled(!busy && !refreshing && enabled() && !me.sponsor.permanent && catalog.findPlan(selectedPlanID)!=null);
		if(codeInput!=null) codeInput.setEnabled(!busy && !refreshing && enabled());
		if(stateNotice!=null){
			stateNotice.setVisibility(catalog!=null && me!=null && (!fresh || refreshing || !Boolean.TRUE.equals(catalog.config.enabled)) ? View.VISIBLE : View.GONE);
			stateNotice.setText(refreshing ? R.string.sponsor_ui_refreshing : !fresh ? R.string.sponsor_ui_stale : R.string.sponsor_ui_unavailable);
		}
	}
	/** Only data sections change. The editor, its focus/selection and IME remain attached. */
	private void render(){
		if(content==null || getActivity()==null) return;
		controls.clear(); planCards.clear(); statusContent.removeAllViews(); plansContent.removeAllViews(); benefitsContent.removeAllViews();
		boolean ready=!unsupported && catalog!=null && me!=null;
		checkout.setVisibility(ready && !me.sponsor.permanent ? View.VISIBLE : View.GONE);
		// Redemption and history remain after the redesigned primary content, below the first-screen composition.
		redeemContent.setVisibility(ready ? View.VISIBLE : View.GONE);
		historyContent.setVisibility(ready ? View.VISIBLE : View.GONE);
		View rootView=getView(); if(rootView!=null) rootView.requestApplyInsets();
		if(unsupported){
			SponsorUi.text(statusContent, getString(R.string.sponsor_ui_unsupported), true);
			SponsorUi.text(statusContent, getString(R.string.sponsor_ui_unsupported_body), false); return;
		}
		if(!ready) return;
		Config config=catalog.config;
		setTitle(config.centerTitle);
		if(centeredTitle!=null){
			centeredTitle.setText(config.centerTitle);
			android.widget.Toolbar toolbar=getView()==null ? null : getView().findViewById(R.id.toolbar);
			if(toolbar!=null) toolbar.setTitle("");
		}
		renderIdentity(statusContent);
		renderQuota(statusContent);
		codeInput.setVisibility(me.sponsor.permanent ? View.GONE : View.VISIBLE);
		redeemButton.setVisibility(me.sponsor.permanent ? View.GONE : View.VISIBLE);
		redeemNote.setVisibility(me.sponsor.permanent ? View.VISIBLE : View.GONE);
		renderPlans();
		renderBenefits(config);
		updateActions();
	}

	private void renderIdentity(LinearLayout parent){
		LinearLayout hero=SponsorUi.designCard(parent);
		hero.setPadding(V.dp(14), V.dp(8), V.dp(14), V.dp(8));
		LinearLayout row=new LinearLayout(getActivity()); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
		hero.addView(row, new LinearLayout.LayoutParams(-1, -2));
		ImageView avatar=new ImageView(getActivity()); avatar.setScaleType(ImageView.ScaleType.CENTER_CROP); avatar.setImageResource(R.drawable.default_avatar);
		avatar.setOutlineProvider(OutlineProviders.OVAL); avatar.setClipToOutline(true);
		row.addView(avatar, new LinearLayout.LayoutParams(V.dp(54), V.dp(54)));
		if(session!=null && session.self!=null && session.self.avatarStatic!=null && !session.self.avatarStatic.isBlank())
			ViewImageLoader.loadWithoutAnimation(avatar, avatar.getDrawable(), new UrlImageLoaderRequest(GlobalUserPreferences.playGifs ? session.self.avatar : session.self.avatarStatic, V.dp(54), V.dp(54)));
		LinearLayout identity=new LinearLayout(getActivity()); identity.setOrientation(LinearLayout.VERTICAL); identity.setPadding(V.dp(14), 0, V.dp(8), 0);
		row.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
		String name=session==null || session.self==null ? "" : (session.self.displayName==null || session.self.displayName.isBlank() ? session.self.username : session.self.displayName);
		TextView nameView=plainText(name, 19, true,
				org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurface));
		nameView.setMaxLines(1);
		nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
		identity.addView(nameView, new LinearLayout.LayoutParams(-1, V.dp(30)));
		String sub=getString(me.sponsor.isActive()
				? (me.sponsor.permanent ? R.string.sponsor_ui_permanent : R.string.sponsor_ui_active)
				: R.string.sponsor_ui_regular);
		String subtitle=sub+"  ·  "+(me.sponsor.planName==null ? getString(R.string.sponsor_ui_welcome) : me.sponsor.planName);
		TextView subView=plainText(subtitle, 13, false,
				org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurfaceVariant));
		subView.setMaxLines(1);
		subView.setEllipsize(android.text.TextUtils.TruncateAt.END);
		identity.addView(subView, new LinearLayout.LayoutParams(-1, V.dp(24)));
		LinearLayout status=new LinearLayout(getActivity()); status.setOrientation(LinearLayout.VERTICAL); status.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
		row.addView(status, new LinearLayout.LayoutParams(V.dp(78), -2));
		TextView statusLabel=plainText(getString(R.string.sponsor_ui_status), 12, false,
				org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurfaceVariant));
		statusLabel.setGravity(Gravity.CENTER);
		status.addView(statusLabel, new LinearLayout.LayoutParams(-1, V.dp(22)));
		String stateText=me.sponsor.isActive()
				? getString(me.sponsor.permanent ? R.string.sponsor_ui_permanent : R.string.sponsor_ui_active)
				: getString(R.string.sponsor_ui_not_active);
		int stateColor=android.graphics.Color.parseColor(me.sponsor.isActive() ? "#E5AE5B" : "#C88A2C");
		TextView state=plainText(stateText, 18, true, stateColor);
		state.setGravity(Gravity.CENTER);
		status.addView(state, new LinearLayout.LayoutParams(-1, V.dp(30)));
	}

	private TextView plainText(CharSequence value, int size, boolean bold, int color){
		TextView view=new TextView(getActivity());
		view.setText(value);
		view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size);
		view.setTextColor(color);
		if(bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
		return view;
	}

	private void renderQuota(LinearLayout parent){
		LinearLayout card=SponsorUi.designCard(parent); card.setPadding(V.dp(18), V.dp(8), V.dp(18), V.dp(8));
		TextView quotaLabel=plainText(getString(R.string.sponsor_ui_quota), 13, false,
				org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurfaceVariant));
		card.addView(quotaLabel, new LinearLayout.LayoutParams(-1, V.dp(24)));
		TextView value=plainText(getString(R.string.sponsor_ui_used_compact, me.quota.used, me.quota.limit), 24, true,
				org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurface));
		card.addView(value, new LinearLayout.LayoutParams(-1, V.dp(38)));
		LinearLayout meterRow=new LinearLayout(getActivity()); meterRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
		card.addView(meterRow, new LinearLayout.LayoutParams(-1, V.dp(16)));
		android.widget.ProgressBar meter=new android.widget.ProgressBar(getActivity(), null, android.R.attr.progressBarStyleHorizontal);
		meter.setMax(Math.max(1, me.quota.limit)); meter.setProgress(Math.min(me.quota.limit, Math.max(0, me.quota.remaining)));
		meter.setProgressTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F0B44D")));
		meter.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
				org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), R.attr.colorM3OutlineVariant)));
		meterRow.addView(meter, new LinearLayout.LayoutParams(0, V.dp(9), 1));
		int percent=me.quota.limit<=0 ? 0 : Math.max(0, Math.min(100, Math.round((me.quota.limit-me.quota.used)*100f/me.quota.limit)));
		TextView percentView=new TextView(getActivity());
		percentView.setText(getString(R.string.sponsor_ui_remaining_percent, percent));
		percentView.setTextAppearance(R.style.m3_label_large);
		percentView.setTextColor(org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurfaceVariant));
		percentView.setGravity(Gravity.CENTER_VERTICAL);
		percentView.setPadding(V.dp(10), 0, 0, 0);
		meterRow.addView(percentView, new LinearLayout.LayoutParams(-2, -1));
	}

	private void renderBenefits(Config config){
		LinearLayout card=SponsorUi.designCard(benefitsContent); card.setPadding(V.dp(16), V.dp(8), V.dp(16), V.dp(8));
		TextView heading=SponsorUi.text(card, getString(R.string.sponsor_ui_benefits), true); heading.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18); heading.setPadding(0, 0, 0, V.dp(2));
		renderBenefitRow(card, coreBenefit(config, "original", R.string.sponsor_ui_original_benefit, R.string.sponsor_ui_original_benefit_description, "automatic"));
		renderBenefitRow(card, coreBenefit(config, "color", R.string.sponsor_ui_color_benefit, R.string.sponsor_ui_color_benefit_description, "coming_soon"));
		renderBenefitRow(card, coreBenefit(config, "none", R.string.sponsor_ui_support_benefit, R.string.sponsor_ui_support_benefit_description, "automatic"));
		ArrayList<Benefit> claimBenefits=new ArrayList<>();
		for(Benefit benefit:config.benefits) if("claim".equals(benefit.action)) claimBenefits.add(benefit);
		claimBenefits.sort(Comparator.comparingInt(benefit->benefit.sortOrder));
		if(!claimBenefits.isEmpty()){
			LinearLayout extras=SponsorUi.designCard(benefitsContent);
			extras.setPadding(V.dp(16), V.dp(8), V.dp(16), V.dp(8));
			TextView extrasTitle=SponsorUi.text(extras, getString(R.string.sponsor_ui_more_benefits), true);
			extrasTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
			extrasTitle.setPadding(0, 0, 0, V.dp(2));
			for(Benefit benefit:claimBenefits) renderBenefitRow(extras, benefit);
		}
	}

	private Benefit coreBenefit(Config config, String action, int title, int description, String status){
		Benefit source=config.benefits.stream().filter(b->action.equals(b.action)).findFirst().orElse(null);
		Benefit benefit=new Benefit();
		benefit.id=source==null ? action : source.id;
		benefit.title=source==null || source.title==null || source.title.isBlank() ? getString(title) : source.title;
		benefit.description=source==null || source.description==null || source.description.isBlank() ? getString(description) : source.description;
		benefit.action=action;
		benefit.status=source==null || source.status==null ? status : source.status;
		benefit.sortOrder=source==null ? 0 : source.sortOrder;
		return benefit;
	}

	private void renderBenefitRow(LinearLayout parent, Benefit benefit){
		if(parent.getChildCount()>1){
			View divider=new View(getActivity());
			divider.setBackgroundColor(org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), R.attr.colorM3OutlineVariant));
			parent.addView(divider, new LinearLayout.LayoutParams(-1, V.dp(1)));
		}
		LinearLayout row=new LinearLayout(getActivity());
		row.setGravity(android.view.Gravity.CENTER_VERTICAL);
		row.setMinimumHeight(V.dp(56));
		row.setPadding(0, V.dp(5), 0, V.dp(5));
		int icon="original".equals(benefit.action) ? R.drawable.ic_add_photo_alternate_24px
				: "color".equals(benefit.action) ? R.drawable.ic_fluent_paint_brush_24_regular
				: R.drawable.ic_fluent_heart_24_regular;
		ImageView iconView=new ImageView(getActivity());
		iconView.setImageResource(icon);
		iconView.setColorFilter(android.graphics.Color.parseColor("#E5AE5B"));
		row.addView(iconView, new LinearLayout.LayoutParams(V.dp(24), V.dp(24)));
		LinearLayout texts=new LinearLayout(getActivity()); texts.setOrientation(LinearLayout.VERTICAL); texts.setPadding(V.dp(12), 0, V.dp(8), 0); row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
		TextView title=plainText(benefit.title, 14, true,
				org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurface));
		title.setSingleLine(true);
		title.setEllipsize(android.text.TextUtils.TruncateAt.END);
		texts.addView(title, new LinearLayout.LayoutParams(-1, -2));
		String status=switch(benefit.status){
			case "automatic" -> me.sponsor.isActive() ? getString(R.string.sponsor_ui_automatic) : getString(R.string.sponsor_ui_automatic_inactive);
			case "available" -> getString("color".equals(benefit.action) ? R.string.sponsor_ui_configurable : R.string.sponsor_ui_available);
			default -> getString(R.string.sponsor_ui_coming);
		};
		CharSequence descriptionText=benefit.description==null || benefit.description.isBlank() ? status : benefit.description;
		TextView description=plainText(descriptionText, 12, false,
				org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurfaceVariant));
		description.setMaxLines(2);
		description.setEllipsize(android.text.TextUtils.TruncateAt.END);
		description.setLineSpacing(V.dp(1), 1f);
		texts.addView(description, new LinearLayout.LayoutParams(-1, -2));
		if("color".equals(benefit.action)){
			Button action=SponsorUi.inlineAction(row, getString(R.string.sponsor_ui_set), this::openColorPage);
			gate(action, ()->enabled() && me.sponsor.isActive() && "available".equals(benefit.status));
		}else if("claim".equals(benefit.action) && "available".equals(benefit.status)){
			boolean claimed=me.claimedBenefitIds.contains(benefit.id);
			Button action=SponsorUi.inlineAction(row, getString(claimed ? R.string.sponsor_ui_claimed : R.string.sponsor_ui_claim),
					()->mutate(SponsorRequest.claim(benefit.id,
							claimOperations.computeIfAbsent(benefit.id, ignored->SponsorOperation.newId())), false));
			gate(action, ()->enabled() && me.sponsor.isActive() && !claimed);
		}
		parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
	}

	private void openColorPage(){
		Bundle args=new Bundle(); args.putString("account", accountID); Nav.go(getActivity(), SponsorColorFragment.class, args);
	}

	private void renderPlans(){
		controls.keySet().removeIf(view->view.getParent()==plansContent);
		plansContent.removeAllViews(); planCards.clear();
		if(me.sponsor.permanent) return;
		LinearLayout titleRow=new LinearLayout(getActivity());
		titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
		titleRow.setPadding(V.dp(4), 0, V.dp(4), 0);
		TextView title=new TextView(getActivity());
		title.setText(R.string.sponsor_ui_plans);
		title.setTextAppearance(R.style.m3_title_large);
		title.setTextColor(org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurface));
		title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 21);
		title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
		titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
		TextView more=new TextView(getActivity());
		more.setText(R.string.sponsor_ui_swipe_more);
		more.setTextAppearance(R.style.m3_label_large);
		more.setTextColor(android.graphics.Color.parseColor("#C88A2C"));
		more.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
		titleRow.addView(more, new LinearLayout.LayoutParams(-2, -1));
		plansContent.addView(titleRow, new LinearLayout.LayoutParams(-1, V.dp(40)));
		if(catalog.findPlan(selectedPlanID)==null) selectedPlanID=defaultPlanID();
		HorizontalScrollView carousel=new HorizontalScrollView(getActivity());
		carousel.setHorizontalScrollBarEnabled(false);
		carousel.setClipToPadding(false);
		carousel.setPadding(V.dp(4), 0, 0, 0);
		LinearLayout row=new LinearLayout(getActivity());
		carousel.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
		plansContent.addView(carousel, new LinearLayout.LayoutParams(-1, -2));
		catalog.plans.stream().filter(p->p.enabled).sorted(Comparator.comparingInt(p->p.sortOrder)).forEach(plan->{
			LinearLayout card=SponsorUi.compactPlanCard(row, plan, plan.id.equals(selectedPlanID), enabled() && !busy && !refreshing, ()->selectPlan(plan.id));
			planCards.put(plan.id, card); gate(card, ()->enabled() && !me.sponsor.permanent && plan.enabled);
		});
		if(catalog.plans.isEmpty()) SponsorUi.text(plansContent, getString(R.string.sponsor_ui_no_plans), false);
		String planNotice=planNotice();
		if(planNotice!=null){
			LinearLayout notice=SponsorUi.designCard(plansContent);
			notice.setPadding(V.dp(14), V.dp(6), V.dp(14), V.dp(6));
			TextView noticeText=plainText(planNotice, 12, false,
					org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurfaceVariant));
			noticeText.setGravity(Gravity.CENTER_VERTICAL);
			notice.addView(noticeText, new LinearLayout.LayoutParams(-1, V.dp(22)));
		}
		selectPlan(selectedPlanID);
	}

	private String planNotice(){
		return catalog.plans.stream().anyMatch(plan->plan.enabled) ? getString(R.string.sponsor_ui_official_purchase_notice) : null;
	}

	private String defaultPlanID(){
		String preferred=catalog.plans.stream()
				.filter(p->p.enabled && (p.durationUnit.equals("month") || p.durationUnit.equals("permanent")))
				.sorted(Comparator.comparingInt(p->p.sortOrder))
				.map(p->p.id)
				.findFirst()
				.orElse(null);
		if(preferred!=null) return preferred;
		return catalog.plans.stream()
				.filter(p->p.enabled)
				.sorted(Comparator.comparingInt(p->p.sortOrder))
				.map(p->p.id)
				.findFirst()
				.orElse(null);
	}
	private void selectPlan(String id){
		selectedPlanID=id;
		for(Map.Entry<String, LinearLayout> card:planCards.entrySet()) SponsorUi.compactSelection(card.getValue(), card.getKey().equals(id));
		Plan plan=catalog.findPlan(id);
		checkoutSummary.setText(plan==null ? getString(R.string.sponsor_ui_choose) : plan.name+" · "+SponsorUi.price(plan));
		if(purchaseButton!=null) purchaseButton.setText(plan==null ? getString(R.string.sponsor_ui_become_sponsor) : getString(R.string.sponsor_ui_become_sponsor_price, SponsorUi.price(plan)));
		updateActions();
	}
	private void openPurchase(){
		if(!enabled() || busy || refreshing || selectedPlanID==null || me.sponsor.permanent) return;
		Bundle args=new Bundle(); args.putString("account", accountID); args.putString("plan", selectedPlanID);
		args.putBoolean("returnToCenter", true);
		Nav.go(getActivity(), SponsorPurchaseFragment.class, args);
	}
	private void redeem(){
		if(busy || refreshing || !enabled() || me.sponsor.permanent || codeInput==null) return;
		String code=SponsorRedemptionRetry.normalize(codeInput.getText().toString());
		if(code.isEmpty()){ codeInput.setError(getString(R.string.sponsor_ui_enter_code)); return; }
		if(code.length()>100){ codeInput.setError(getString(R.string.sponsor_ui_invalid_code)); return; }
		try{
			redeemOperation=redemptionRetry.operation(accountID, code);
			redeemCode=code;
		}catch(RuntimeException error){
			operationStatus.setText(R.string.sponsor_ui_retry_storage); operationStatus.setVisibility(View.VISIBLE); return;
		}
		mutate(SponsorRequest.redeem(code, redeemOperation), true);
	}
	private void mutate(SponsorRequest<Me> request, boolean redemption){
		if(busy || refreshing || !enabled()) return;
		busy=true; updateActions();
		if(redemption){ operationStatus.setText(R.string.sponsor_ui_processing); operationStatus.setVisibility(View.VISIBLE); }
		int token=generation;
		requests.add(request.setCallback(new Callback<>(){
			@Override public void onSuccess(Me result){
				requests.remove(request);
				if(!live(token)) return;
				SponsorRefresh.apply(accountID, result);
				busy=false; me=result;
				if(redemption){
					if(codeInput!=null && SponsorRedemptionRetry.normalize(codeInput.getText().toString()).equals(redeemCode)) codeInput.setText("");
					operationStatus.setText(result.message==null ? getString(R.string.sponsor_ui_updated) : result.message);
					redeemCode=null; redeemOperation=null;
					resetHistory();
				}
				Toast.makeText(getActivity(), result.message==null ? getString(R.string.sponsor_ui_updated) : result.message, Toast.LENGTH_LONG).show();
				render();
				// Mutation replays return the original snapshot; refresh before enabling another operation.
				onRefresh();
			}
			@Override public void onError(ErrorResponse error){
				requests.remove(request);
				if(!live(token)) return;
				busy=false; error.showToast(getActivity());
				if(redemption){
					operationStatus.setText(error instanceof SponsorRequest.SponsorError e ? e.error : getString(R.string.sponsor_ui_redemption_unknown));
					operationStatus.setVisibility(View.VISIBLE);
				}
				updateActions();
				if(error instanceof SponsorRequest.SponsorError e && Set.of("sponsors_disabled", "config_changed", "sponsor_required", "already_permanent").contains(e.code)) onRefresh();
			}
		}).exec(accountID));
	}
	private void resetHistory(){
		historyGeneration++; historyLoading=false; historyOffset=0;
		if(historyContent==null) return;
		historyContent.removeAllViews();
		SponsorUi.text(historyContent, getString(R.string.sponsor_ui_history), true);
		SponsorUi.textButton(historyContent, getString(R.string.sponsor_ui_history_load), ()->loadHistory(true));
	}
	private void loadHistory(boolean first){
		if(historyContent==null || historyLoading || !sessionValid()) return;
		historyLoading=true;
		if(first) historyOffset=0;
		int token=historyGeneration, offset=historyOffset;
		LinearLayout target=historyContent;
		View trigger=target.getChildAt(target.getChildCount()-1); trigger.setEnabled(false);
		SponsorRequest<History> request=SponsorRequest.history(offset);
		requests.add(request.setCallback(new Callback<>(){
			@Override public void onSuccess(History result){
				requests.remove(request);
				if(token!=historyGeneration || !sessionValid() || target!=historyContent || getActivity()==null) return;
				historyLoading=false;
				if(first){ target.removeAllViews(); SponsorUi.text(target, getString(R.string.sponsor_ui_history), true); }
				else target.removeView(trigger);
				for(Redemption item:result.items){
					SponsorUi.text(target, item.planName, true);
					SponsorUi.label(target, getString(R.string.sponsor_ui_redeemed_at, SponsorUi.date(item.redeemedAt)));
					SponsorUi.label(target, item.permanent ? getString(R.string.sponsor_ui_duration_permanent) : getString(R.string.sponsor_ui_expiry, SponsorUi.date(item.expiresAt)));
				}
				historyOffset=offset+result.items.size();
				if(result.total==0) SponsorUi.text(target, getString(R.string.sponsor_ui_history_empty), false);
				if(historyOffset<result.total && !result.items.isEmpty()) SponsorUi.textButton(target, getString(R.string.sponsor_ui_more), ()->loadHistory(false));
			}
			@Override public void onError(ErrorResponse error){
				requests.remove(request);
				if(token!=historyGeneration || !sessionValid() || getActivity()==null || target!=historyContent) return;
				historyLoading=false; error.showToast(getActivity()); trigger.setEnabled(true);
			}
		}).exec(accountID));
	}
	@Override public void onDestroyView(){
		generation++; historyGeneration++;
		main.removeCallbacksAndMessages(null);
		if(currentRequest!=null) currentRequest.cancel();
		currentRequest=null;
		for(MastodonAPIRequest<?> request:requests) request.cancel();
		requests.clear(); controls.clear(); planCards.clear(); busy=false; refreshing=false; historyLoading=false; fresh=false;
		redeemCode=null; redeemOperation=null;
		content=null; codeInput=null; historyContent=null; statusContent=null; plansContent=null; benefitsContent=null; redeemContent=null; redeemButton=null; purchaseButton=null; redeemNote=null;
		checkout=null; checkoutSummary=null; stateNotice=null; operationStatus=null; paymentHint=null; centeredTitle=null;
		super.onDestroyView();
	}
}
