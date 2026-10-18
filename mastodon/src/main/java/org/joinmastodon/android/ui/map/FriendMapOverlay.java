package org.joinmastodon.android.ui.map;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.model.map.MapModels;
import org.joinmastodon.android.sponsors.SponsorUi;
import org.joinmastodon.android.ui.utils.UiUtils;

/** Map content stays interactive outside the controls and the embedded sheet. */
public final class FriendMapOverlay extends FrameLayout{
	public interface Actions{
		void refresh();
		void locate();
		void visibility(String value);
		void confirmVisibility(String value);
		void stopAppearance();
		void visibilitySponsor();
		default void cityPrecisionSponsor(){ sponsor(); }
		void precision(String value);
		void anonymous(boolean value);
		void provider(String value);
		void useDeviceLocation();
		void beginPin();
		void confirmPin();
		void cancelPin();
		void consent();
		void privacy();
		void verification();
		void sponsor();
		void profile(MapModels.Point point);
		void focus(MapModels.Point point);
		void panelChanged(int topInset,int bottomInset);
	}

	private final Actions actions;
	private final MapSheetState sheetState=new MapSheetState();
	private final FrameLayout mapHost, header, privacyShell, privacyCard, tools, panel;
	private final MapGlassMaterial privacyMaterial;
	private final LinearLayout privacyRows, panelColumn, panelHeader;
	private final ScrollView scroll;
	private final LinearLayout body;
	private final TextView visibilityValue, precisionValue, panelTitle, panelSummary, message;
	private final ImageButton settingsButton, backButton, refreshButton, privacyButton;
	private FrameLayout verificationGate;
	private LinearLayout gateColumn;
	private MapModels.Access access=MapModels.Access.READY;
	private View sampleSource;
	private boolean hasSnapshot, consentRead, consentLayoutReady, pendingEnterDefaults;
	private final View gateBlanket;
	private Button consentButton;
	private float privacyProgress=1f, privacyRadius;
	private final ViewOutlineProvider privacyOutline=new ViewOutlineProvider(){
		@Override public void getOutline(View view,Outline outline){ outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),privacyRadius); }
	};
	private final View handle;
	private final List<MapGlassMaterial> materials=new ArrayList<>();
	private final List<MapModels.Point> points=new ArrayList<>();
	private final ImageView crosshair;
	private final int primary,onSurface,secondary,surface,outline;
	private MapModels.Settings settings;
	private MapModels.Point selected;
	private String providerId="auto";
	/** A first-use decision is never inferred from the server's existing/default visibility. */
	private String pendingVisibility;
	private CharSequence messageText="";
	private boolean busy, serviceUnavailable;
	private int topInset,bottomInset, panelHeight, overlayWidth,overlayHeight;
	private ValueAnimator animator;
	private ValueAnimator privacyAnimator;
	private float dragY;
	private int dragHeight;
	private boolean dragged;
	private boolean privacyExpanded=true;

	public FriendMapOverlay(Context context,Actions actions){
		super(context); this.actions=actions;
		primary=color(R.attr.colorM3Primary); onSurface=color(R.attr.colorM3OnSurface); secondary=color(R.attr.colorM3OnSurfaceVariant);
		surface=color(R.attr.colorM3Surface); outline=color(R.attr.colorM3OutlineVariant);
		setBackgroundColor(surface);
		mapHost=new MapTouchHost(context); mapHost.setId(R.id.friend_map_canvas); addView(mapHost,new LayoutParams(-1,-1));
		gateBlanket=new View(context); gateBlanket.setBackgroundColor(surface); gateBlanket.setClickable(true); gateBlanket.setVisibility(GONE); addView(gateBlanket,new LayoutParams(-1,-1));
		header=new FrameLayout(context); header.setClipChildren(false); header.setClipToPadding(false); addView(header,new LayoutParams(-1,V.dp(48),Gravity.TOP));
		privacyMaterial=new MapGlassMaterial(context,24,false); materials.add(privacyMaterial);
		privacyShell=new MapGlassContainer(context,privacyMaterial.getView());
		privacyShell.setOutlineProvider(privacyOutline); privacyShell.setClipToOutline(true); privacyShell.setElevation(V.dp(5));
		privacyButton=icon(R.drawable.ic_fluent_wrench_24_regular,R.string.map_ui_privacy_toggle,this::togglePrivacy);
		privacyShell.addView(privacyButton,new LayoutParams(V.dp(48),V.dp(48),Gravity.TOP|Gravity.END));
		addView(privacyShell,new LayoutParams(V.dp(48),V.dp(48),Gravity.TOP|Gravity.LEFT));
		FrameLayout settingsShell=glass(24,false); settingsButton=icon(R.drawable.ic_settings_24px,R.string.map_presence_preferences,this::showSettings);
		settingsShell.addView(settingsButton,new LayoutParams(-1,-1));
		header.addView(settingsShell,new LayoutParams(V.dp(48),V.dp(48),Gravity.END));

		privacyCard=new FrameLayout(context){
			@Override public boolean onInterceptTouchEvent(MotionEvent event){ return privacyProgress>0f&&privacyProgress<1f; }
			@Override public boolean onTouchEvent(MotionEvent event){
				if(event.getActionMasked()==MotionEvent.ACTION_UP) togglePrivacy();
				return true;
			}
		}; privacyCard.setId(R.id.friend_map_privacy); privacyShell.addView(privacyCard,new LayoutParams(-1,-1));
		privacyRows=new LinearLayout(context); privacyRows.setOrientation(LinearLayout.VERTICAL); privacyRows.setPadding(V.dp(16),V.dp(4),V.dp(16),V.dp(4));
		privacyCard.addView(privacyRows,new LayoutParams(-1,-2));
		visibilityValue=summaryRow(privacyRows,R.string.map_ui_visibility,"—",()->open(MapSheetState.Page.VISIBILITY));
		divider(privacyRows);
		precisionValue=summaryRow(privacyRows,R.string.map_ui_precision,"—",()->open(MapSheetState.Page.PRECISION));

		tools=glass(26,false); tools.setId(R.id.friend_map_tools); addView(tools,new LayoutParams(V.dp(52),V.dp(106),Gravity.END|Gravity.BOTTOM));
		LinearLayout toolRows=new LinearLayout(context); toolRows.setOrientation(LinearLayout.VERTICAL); tools.addView(toolRows,new LayoutParams(-1,-1));
		ImageButton layers=icon(R.drawable.ic_fluent_layer_24_regular,R.string.map_presence_provider,()->open(MapSheetState.Page.PROVIDER));
		toolRows.addView(layers,new LinearLayout.LayoutParams(-1,0,1)); divider(toolRows);
		ImageButton locate=icon(R.drawable.ic_fluent_globe_location_24_regular,R.string.map_presence_locate,actions::locate); toolRows.addView(locate,new LinearLayout.LayoutParams(-1,0,1));

		crosshair=new ImageView(context); crosshair.setImageResource(R.drawable.ic_fluent_globe_location_24_regular); crosshair.setImageTintList(ColorStateList.valueOf(primary));
		crosshair.setVisibility(GONE); crosshair.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
		addView(crosshair,new LayoutParams(V.dp(40),V.dp(40),Gravity.CENTER));

		panel=glass(32,true); panel.setId(R.id.friend_map_panel); addView(panel,new LayoutParams(-1,V.dp(112),Gravity.BOTTOM));
		panelColumn=new BlankDragColumn(context); panelColumn.setOrientation(LinearLayout.VERTICAL); panelColumn.setPadding(V.dp(20),0,V.dp(20),0); panel.addView(panelColumn,new LayoutParams(-1,-1));
		FrameLayout handleArea=new FrameLayout(context); handleArea.setMinimumHeight(V.dp(22));
		handle=new View(context); handle.setBackground(SponsorUi.rounded(withAlpha(secondary,.40f),2));
		LayoutParams handleParams=new LayoutParams(V.dp(36),V.dp(4),Gravity.CENTER); handleArea.addView(handle,handleParams);
		handleArea.setContentDescription(getString(R.string.map_ui_expand)); handleArea.setOnTouchListener(this::drag);
		handleArea.setOnClickListener(v->toggleHeight()); panelColumn.addView(handleArea,new LinearLayout.LayoutParams(-1,V.dp(24)));

		panelHeader=new LinearLayout(context); panelHeader.setGravity(Gravity.CENTER_VERTICAL); panelHeader.setMinimumHeight(V.dp(48));
		backButton=icon(R.drawable.ic_arrow_back_24,R.string.map_ui_back,this::onBackPressed); panelHeader.addView(backButton,new LinearLayout.LayoutParams(V.dp(44),V.dp(44)));
		panelTitle=text(getString(R.string.map_ui_nearby),R.style.m3_title_large,onSurface); panelTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
		panelTitle.setOnClickListener(v->toggleHeight()); panelHeader.addView(panelTitle,new LinearLayout.LayoutParams(0,-2,1));
		refreshButton=icon(R.drawable.ic_fluent_arrow_clockwise_24_regular,R.string.map_presence_refresh,actions::refresh); panelHeader.addView(refreshButton,new LinearLayout.LayoutParams(V.dp(44),V.dp(44)));
		panelHeader.setOnTouchListener(this::drag); panelColumn.addView(panelHeader,new LinearLayout.LayoutParams(-1,V.dp(48)));
		panelSummary=text(getString(R.string.map_ui_loading_summary),R.style.m3_body_medium,secondary); panelSummary.setMaxLines(2);
		panelSummary.setPadding(0,0,0,V.dp(12)); panelColumn.addView(panelSummary,new LinearLayout.LayoutParams(-1,-2));
		scroll=new ScrollView(context); scroll.setFillViewport(false); scroll.setClipToPadding(false);
		body=new LinearLayout(context){
			@Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
				super.onLayout(changed,left,top,right,bottom);
				consentLayoutReady=sheetState.page()==MapSheetState.Page.CONSENT; updateConsentRead();
			}
		}; body.setOrientation(LinearLayout.VERTICAL); body.setPadding(0,V.dp(4),0,V.dp(20)); scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
		panelColumn.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
		message=text("",R.style.m3_body_medium,secondary);
		scroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->updateConsentRead());
		scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateConsentRead());
		buildVerificationGate(context);
		rebuild(false);
	}
	@Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec){
		int width=MeasureSpec.getSize(widthMeasureSpec),height=MeasureSpec.getSize(heightMeasureSpec);
		if(width!=overlayWidth || height!=overlayHeight){
			overlayWidth=width; overlayHeight=height;
			layoutOverlays(false);
		}
		super.onMeasure(widthMeasureSpec,heightMeasureSpec);
	}

	/** Access protection is separate from decorative glass and never follows its preference. */
	private void buildVerificationGate(Context context){
		verificationGate=new FrameLayout(context); verificationGate.setClickable(true); verificationGate.setFocusable(true);
		gateColumn=new LinearLayout(context); gateColumn.setOrientation(LinearLayout.VERTICAL); gateColumn.setGravity(Gravity.CENTER);
		gateColumn.setPadding(V.dp(32),V.dp(24),V.dp(32),V.dp(24));
		ScrollView gateScroll=new ScrollView(context); gateScroll.setFillViewport(true);
		gateScroll.addView(gateColumn,new ScrollView.LayoutParams(-1,-1));
		verificationGate.addView(gateScroll,new LayoutParams(-1,-1));
		verificationGate.setVisibility(GONE); addView(verificationGate,new LayoutParams(-1,-1));
	}
	private boolean accessBlocked(){ return access!=MapModels.Access.READY || sheetState.consentRequired(); }
	private void renderAccessGate(CharSequence error){
		gateColumn.removeAllViews();
		boolean verification=access==MapModels.Access.VERIFICATION;
		if(verification){
			ImageView icon=new ImageView(getContext()); icon.setImageResource(R.drawable.ic_badge_verified_pill_icon); icon.setImageTintList(ColorStateList.valueOf(onSurface));
			icon.setContentDescription(getString(R.string.map_ui_go_verification)); icon.setBackground(roundRipple(withAlpha(primary,.12f),28));
			icon.setPadding(V.dp(16),V.dp(16),V.dp(16),V.dp(16)); icon.setOnClickListener(v->actions.verification());
			gateColumn.addView(icon,new LinearLayout.LayoutParams(V.dp(56),V.dp(56)));
		}
		TextView title=text(verification?getString(R.string.map_ui_verification_required):access==MapModels.Access.ERROR?error:getString(R.string.map_ui_loading_summary),R.style.m3_title_medium,onSurface);
		title.setGravity(Gravity.CENTER); title.setPadding(0,V.dp(16),0,V.dp(20));
		if(verification){ title.setBackground(roundRipple(0,16)); title.setOnClickListener(v->actions.verification()); }
		gateColumn.addView(title,new LinearLayout.LayoutParams(-1,-2));
		if(verification) SponsorUi.button(gateColumn,getString(R.string.map_ui_go_verification),actions::verification);
		else if(access==MapModels.Access.ERROR) SponsorUi.button(gateColumn,getString(R.string.map_ui_retry),actions::refresh);
		updateAccessPresentation();
	}
	private void updateAccessPresentation(){
		boolean blocked=accessBlocked(), decision=access==MapModels.Access.CONSENT || access==MapModels.Access.VISIBILITY;
		verificationGate.setVisibility(blocked&&!decision?VISIBLE:GONE);
		panel.setVisibility(!blocked||decision?VISIBLE:GONE);
		header.setVisibility(blocked?GONE:VISIBLE); tools.setVisibility(blocked || panelHeight>availablePanel()*.8f?GONE:VISIBLE);
		if(blocked) crosshair.setVisibility(GONE);
		mapHost.setImportantForAccessibility(blocked?IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS:IMPORTANT_FOR_ACCESSIBILITY_AUTO);
		panel.setImportantForAccessibility(!blocked||decision?IMPORTANT_FOR_ACCESSIBILITY_AUTO:IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		updateGateBackdrop(); applyPrivacyProgress(privacyProgress);
	}
	private void updateGateBackdrop(){
		boolean blur=access==MapModels.Access.VERIFICATION && Build.VERSION.SDK_INT>=31 && isHardwareAccelerated()
					&& (hasSnapshot || sampleSource!=null && mapHost.getChildCount()>0);
		boolean showNeutralBasemap=access==MapModels.Access.VISIBILITY && sampleSource!=null && mapHost.getChildCount()>0;
		if(Build.VERSION.SDK_INT>=31) mapHost.setRenderEffect(blur?RenderEffect.createBlurEffect(V.dp(30),V.dp(30),Shader.TileMode.CLAMP):null);
		verificationGate.setBackgroundColor(blur?withAlpha(surface,.70f):surface);
		// Keep the SDK rendering behind an opaque fallback until a blurred backdrop is available.
		gateBlanket.setVisibility(accessBlocked()&&!blur&&!showNeutralBasemap?VISIBLE:GONE);
		mapHost.setVisibility(VISIBLE);
	}
	public void showLoadingGate(){ access=MapModels.Access.LOADING; renderAccessGate(null); }
	public void showErrorGate(CharSequence error){ access=MapModels.Access.ERROR; renderAccessGate(error); }
	public void hideAccessGate(){
		if(settings==null){ showLoadingGate(); return; }
		MapModels.Access next=MapModels.accessFor(settings);
		if(next==MapModels.Access.CONSENT){ showConsent(); return; }
		if(next==MapModels.Access.VERIFICATION){ showVerificationGate(); return; }
		if(next==MapModels.Access.VISIBILITY){ showVisibilityGate(); return; }
		access=MapModels.Access.READY;
		sheetState.setConsentRequired(false); sheetState.setVisibilityRequired(false); pendingVisibility=null;
		if(sheetState.page()==MapSheetState.Page.CONSENT || sheetState.page()==MapSheetState.Page.VISIBILITY){ sheetState.open(MapSheetState.Page.NEARBY); rebuild(false); }
		if(pendingEnterDefaults){ pendingEnterDefaults=false; sheetState.open(MapSheetState.Page.NEARBY); rebuild(false); }
		updateAccessPresentation();
	}
	public boolean canSubmitConsent(){
		return access==MapModels.Access.CONSENT && sheetState.page()==MapSheetState.Page.CONSENT && consentRead && !busy
				&& settings!=null && settings.consent!=null && MapModels.CONSENT_VERSION.equals(settings.consent.version);
	}
	private void updateConsentRead(){
		if(!consentLayoutReady || sheetState.page()!=MapSheetState.Page.CONSENT || access!=MapModels.Access.CONSENT || scroll.getHeight()<=0 || body.getHeight()<=0) return;
		int viewport=scroll.getHeight()-scroll.getPaddingTop()-scroll.getPaddingBottom();
		if(body.getBottom()<=scroll.getScrollY()+viewport+1) consentRead=true;
		if(consentButton!=null) consentButton.setEnabled(canSubmitConsent());
	}

	public FrameLayout mapHost(){ return mapHost; }
	public void updateSettings(MapModels.Settings settings){
		boolean wasRequired=sheetState.consentRequired();
		// A server refresh invalidates an unsubmitted decision; never select its stored default.
		this.settings=settings; pendingVisibility=null;
		boolean required=settings==null || !MapModels.consentAccepted(settings.consent);
		sheetState.setConsentRequired(required);
		sheetState.setVisibilityRequired(!required && settings.babyVerified && !settings.visibilityConfirmed);
		if(required&&!wasRequired){ consentRead=false; scroll.scrollTo(0,0); }
		rebuild(false);
	}
	public void setBusy(boolean busy){ this.busy=busy; refreshButton.setEnabled(!busy); rebuild(false); }
	public void setProvider(String id){ providerId=id; if(sheetState.page()==MapSheetState.Page.PROVIDER || sheetState.page()==MapSheetState.Page.SETTINGS) rebuild(false); }
	public void setMessage(CharSequence value,boolean unavailable){
		messageText=value==null?"":value; serviceUnavailable=unavailable;
		if(unavailable && sheetState.page()==MapSheetState.Page.NEARBY) sheetState.setHeight(MapSheetState.Height.HALF);
		rebuild(true);
	}
	public void setPoints(List<MapModels.Point> newPoints){ points.clear(); points.addAll(newPoints); rebuild(false); }
	public void showPerson(MapModels.Point point){ selected=point; open(MapSheetState.Page.PERSON); }
	public void showSettings(){ open(MapSheetState.Page.SETTINGS); }
	public void showConsent(){
		if(access!=MapModels.Access.CONSENT && !sheetState.consentRequired()) consentRead=false;
		access=MapModels.Access.CONSENT;
		sheetState.setConsentRequired(settings==null || !MapModels.consentAccepted(settings.consent));
		open(MapSheetState.Page.CONSENT); updateAccessPresentation();
	}
	public void showPin(){ if(accessBlocked()) return; crosshair.setVisibility(VISIBLE); open(MapSheetState.Page.PIN); }
	public void finishPin(){ crosshair.setVisibility(GONE); sheetState.back(); rebuild(true); }
	public void setNavigationInsets(int top,int bottom){ topInset=Math.max(0,top); bottomInset=Math.max(0,bottom); layoutOverlays(false); }
	public boolean onBackPressed(){
		if(accessBlocked()) return true;
		if(sheetState.page()==MapSheetState.Page.PIN){ actions.cancelPin(); return true; }
		if(sheetState.back()){ rebuild(true); return true; } return false;
	}
	public MapSheetState state(){ return sheetState; }
	public void setSnapshot(Bitmap bitmap){
		hasSnapshot=bitmap!=null&&!bitmap.isRecycled(); updateGateBackdrop();
		int[] origin=new int[2]; mapHost.getLocationInWindow(origin);
		for(MapGlassMaterial material:materials) material.update(bitmap,origin[0],origin[1]);
	}
	public void setSampleSource(View source){
		sampleSource=source; updateGateBackdrop();
		for(MapGlassMaterial material:materials) material.setSampleSource(source);
	}
	public void dispose(){
		if(animator!=null) animator.cancel();
		if(privacyAnimator!=null) privacyAnimator.cancel();
		if(Build.VERSION.SDK_INT>=31) mapHost.setRenderEffect(null);
		sampleSource=null; hasSnapshot=false;
		for(MapGlassMaterial material:materials) material.dispose(); materials.clear();
	}
	public boolean privacyExpanded(){ return privacyExpanded; }
	private boolean liquidMorph(){ return Build.VERSION.SDK_INT>=33 && isHardwareAccelerated() && GlobalUserPreferences.isIosLiquidNavigationEnabled(); }
	public void setPrivacyExpanded(boolean expanded,boolean animate){
		if(privacyAnimator!=null){ privacyAnimator.cancel(); privacyAnimator=null; }
		privacyExpanded=expanded;
		float target=expanded?1f:0f;
		if(!animate || !liquidMorph() || overlayWidth==0){ applyPrivacyProgress(target); return; }
		// Reversals start from the current shell geometry.
		privacyAnimator=ValueAnimator.ofFloat(privacyProgress,target);
		privacyAnimator.setDuration(Math.max(1,Math.round(240*Math.abs(target-privacyProgress))));
		privacyAnimator.setInterpolator(new DecelerateInterpolator());
		privacyAnimator.addUpdateListener(value->applyPrivacyProgress((float)value.getAnimatedValue()));
		privacyAnimator.start();
	}
	private void applyPrivacyProgress(float progress){
		privacyProgress=Math.max(0f,Math.min(1f,progress));
		float t=privacyProgress;
		boolean high=sheetState.height()==MapSheetState.Height.EXPANDED;
		boolean morph=liquidMorph();
		if(!morph) t=privacyExpanded?1f:0f;
		if(high){ t=0f; privacyProgress=0f; }
		int cardWidth=Math.max(V.dp(48),overlayWidth-V.dp(32));
		privacyRows.measure(MeasureSpec.makeMeasureSpec(cardWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
		int cardHeight=Math.max(V.dp(48),privacyRows.getMeasuredHeight());
		float buttonLeft=Math.max(V.dp(16),overlayWidth-V.dp(120));
		LayoutParams params=(LayoutParams)privacyShell.getLayoutParams();
		params.leftMargin=Math.round(buttonLeft+(V.dp(16)-buttonLeft)*t);
		params.topMargin=topInset+V.dp(12)+Math.round(V.dp(60)*t);
		params.width=Math.round(V.dp(48)+(cardWidth-V.dp(48))*t);
		params.height=Math.round(V.dp(48)+(cardHeight-V.dp(48))*t);
		privacyShell.setLayoutParams(params);
		float radiusDp=24f-4f*t;
		privacyRadius=V.dp(radiusDp); privacyMaterial.setCornerRadius(radiusDp); privacyShell.invalidateOutline();
		privacyCard.setVisibility(t>0f?VISIBLE:GONE); privacyCard.setAlpha(t);
		privacyButton.setVisibility(t<1f?VISIBLE:GONE); privacyButton.setAlpha(1f-t);
		// During a morph, the shell remains a reversal target; rows only activate at completion.
		privacyShell.setOnClickListener(v->togglePrivacy()); privacyShell.setClickable(true);
		privacyCard.setImportantForAccessibility(t==1f?IMPORTANT_FOR_ACCESSIBILITY_AUTO:IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		privacyShell.setVisibility(accessBlocked()?GONE:VISIBLE);
	}
	private void togglePrivacy(){
		if(accessBlocked()) return;
		if(sheetState.height()==MapSheetState.Height.EXPANDED){
			sheetState.setHeight(MapSheetState.Height.HALF); setPrivacyExpanded(true,true); rebuild(true);
		}else setPrivacyExpanded(!privacyExpanded,true);
	}
	/** Kept for provider compatibility. Only real map gestures, not camera callbacks, resize UI. */
	public void onCameraMoved(){}
	private void onMapDragStarted(){
		if(accessBlocked()) return;
		if(privacyExpanded) setPrivacyExpanded(false,true);
		if(sheetState.onMapDragStarted()) rebuild(true);
	}
	/** Entering the map opens HIGH without weakening an outstanding consent lock. */
	public void enterMapDefaults(){
		if(accessBlocked()){ pendingEnterDefaults=true; return; }
		sheetState.open(MapSheetState.Page.NEARBY); rebuild(true);
	}
	public void showVerificationGate(){
		if(settings==null || !MapModels.consentAccepted(settings.consent)){ showConsent(); return; }
		access=MapModels.Access.VERIFICATION; renderAccessGate(null);
	}
	public void hideVerificationGate(){ hideAccessGate(); }
	public void showVisibilityGate(){
		if(settings==null || !MapModels.consentAccepted(settings.consent)){ showConsent(); return; }
		if(!settings.babyVerified){ showVerificationGate(); return; }
		access=MapModels.Access.VISIBILITY;
		sheetState.setConsentRequired(false); sheetState.setVisibilityRequired(true);
		open(MapSheetState.Page.VISIBILITY); updateAccessPresentation();
	}

	private void open(MapSheetState.Page page){
		if(accessBlocked() && !(access==MapModels.Access.CONSENT && page==MapSheetState.Page.CONSENT)
				&& !(access==MapModels.Access.VISIBILITY && page==MapSheetState.Page.VISIBILITY)) return;
		if(sheetState.page()!=page){ scroll.scrollTo(0,0); if(page==MapSheetState.Page.CONSENT) consentRead=false; }
		sheetState.open(page); rebuild(true);
	}
	private void toggleHeight(){
		if(accessBlocked()) return;
		sheetState.setHeight(sheetState.height()==MapSheetState.Height.COLLAPSED ? MapSheetState.Height.HALF : MapSheetState.Height.COLLAPSED); rebuild(true);
	}
	private FrameLayout glass(int radius,boolean large){
		MapGlassMaterial material=new MapGlassMaterial(getContext(),radius,large); materials.add(material);
		MapGlassContainer shell=new MapGlassContainer(getContext(),material.getView()); shell.setClipChildren(true);
		// Shadow belongs on the shell so the content added after the glass stays above it.
		shell.setOutlineProvider(org.joinmastodon.android.ui.OutlineProviders.roundedRect(radius));
		shell.setElevation(V.dp(large?10:5));
		return shell;
	}
	private int color(int attr){ return UiUtils.getThemeColor(getContext(),attr); }
	private int withAlpha(int color,float alpha){ return (color&0x00ffffff)|(Math.round(alpha*255)<<24); }
	private String getString(int id){ return getContext().getString(id); }
	private TextView text(CharSequence value,int appearance,int color){
		TextView text=new TextView(getContext()); text.setTextAppearance(appearance); text.setTextColor(color); text.setText(value); text.setIncludeFontPadding(false); return text;
	}
	private ImageButton icon(int drawable,int description,Runnable action){
		ImageButton button=new ImageButton(getContext()); button.setImageResource(drawable); button.setImageTintList(ColorStateList.valueOf(onSurface));
		button.setContentDescription(getString(description)); button.setBackground(roundRipple(0,24)); button.setPadding(V.dp(13),V.dp(13),V.dp(13),V.dp(13)); button.setOnClickListener(v->action.run()); return button;
	}
	private RippleDrawable roundRipple(int fill,int radius){
		return new RippleDrawable(ColorStateList.valueOf(withAlpha(primary,.14f)),SponsorUi.rounded(fill,radius),SponsorUi.rounded(0xffffffff,radius));
	}
	private void divider(LinearLayout parent){ View line=new View(getContext()); line.setBackgroundColor(withAlpha(outline,.5f)); parent.addView(line,new LinearLayout.LayoutParams(-1,Math.max(1,V.dp(.5f)))); }
	private TextView summaryRow(LinearLayout parent,int label,String value,Runnable action){
		LinearLayout row=new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(V.dp(44)); row.setPadding(0,V.dp(10),0,V.dp(10));
		TextView title=text(getString(label),R.style.m3_body_medium,secondary); row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
		TextView detail=text(value,R.style.m3_label_large,primary); detail.setGravity(Gravity.END); row.addView(detail,new LinearLayout.LayoutParams(-2,-2));
		TextView chevron=text("⌄",R.style.m3_body_large,secondary); chevron.setPadding(V.dp(10),0,0,0); row.addView(chevron);
		row.setBackground(roundRipple(0,12)); row.setOnClickListener(v->action.run()); parent.addView(row,new LinearLayout.LayoutParams(-1,-2)); return detail;
	}
	private void rebuild(boolean animate){
		if(panel==null) return;
		boolean known=settings!=null;
		visibilityValue.setText(known?visibilityLabel(settings.visibility):"—"); precisionValue.setText(known?precisionLabel(settings.precisionLevel):"—");
		backButton.setVisibility(sheetState.page()==MapSheetState.Page.NEARBY || sheetState.locked()?GONE:VISIBLE);
		refreshButton.setVisibility(sheetState.page()==MapSheetState.Page.NEARBY?VISIBLE:GONE); refreshButton.setEnabled(!busy);
		panelTitle.setText(titleForPage());
		panelSummary.setText(summaryForPage());
		consentButton=null; consentLayoutReady=false;
		int oldScroll=scroll.getScrollY();
		body.removeAllViews();
		switch(sheetState.page()){
			case NEARBY -> nearby();
			case PERSON -> person();
			case SETTINGS -> preferences();
			case VISIBILITY -> visibilityOptions();
			case PRECISION -> precisionOptions();
			case PROVIDER -> providerOptions();
			case CONSENT -> consentContent();
			case PIN -> pinContent();
		}
		layoutOverlays(animate);
		updateAccessPresentation();
		if(sheetState.page()==MapSheetState.Page.CONSENT) scroll.post(()->{ scroll.scrollTo(0,oldScroll); updateConsentRead(); });
	}
	private int titleForPage(){ return switch(sheetState.page()){
		case NEARBY -> R.string.map_ui_nearby; case PERSON -> R.string.map_ui_person; case SETTINGS -> R.string.map_presence_preferences;
		case VISIBILITY -> R.string.map_ui_visibility; case PRECISION -> R.string.map_ui_precision; case PROVIDER -> R.string.map_presence_provider;
		case CONSENT -> R.string.map_presence_privacy_title; case PIN -> R.string.map_presence_pin;
	}; }
	private String summaryForPage(){
		return switch(sheetState.page()){
			case NEARBY -> busy?getString(R.string.map_ui_loading_summary):serviceUnavailable?getString(R.string.map_ui_service_not_ready):getContext().getString(R.string.map_ui_count,points.size());
			case PERSON -> selected!=null&&selected.anonymous?getString(R.string.map_ui_anonymous_summary):getString(R.string.map_ui_person_summary);
			case SETTINGS -> getString(R.string.map_ui_privacy_summary);
			case VISIBILITY -> getString(R.string.map_ui_visibility_summary);
			case PRECISION -> getString(R.string.map_ui_precision_summary);
			case PROVIDER -> getString(R.string.map_ui_provider_summary);
			case CONSENT -> getString(R.string.map_ui_consent_summary);
			case PIN -> settings==null?"":getContext().getString(R.string.map_ui_pin_summary,precisionLabel(settings.precisionLevel));
		};
	}
	private String visibilityLabel(String value){ return getString("public".equals(value)?R.string.map_ui_everyone:"friends".equals(value)?R.string.map_ui_mutual:R.string.map_ui_invisible); }
	private String precisionLabel(String value){ return switch(value==null?"":value){case "5km"->getString(R.string.map_ui_five_km);case "1km"->getString(R.string.map_ui_one_km);case "200m"->getString(R.string.map_ui_two_hundred_m);default->getString(R.string.map_ui_city_precision);}; }
	private void messageBlock(){
		if(messageText.length()==0) return;
		TextView notice=text(messageText,R.style.m3_body_medium,secondary); notice.setPadding(V.dp(14),V.dp(14),V.dp(14),V.dp(14)); notice.setBackground(SponsorUi.rounded(withAlpha(surface,.75f),16)); body.addView(notice,new LinearLayout.LayoutParams(-1,-2));
	}
	private void nearby(){
		messageBlock();
		if(settings==null){ action(getString(R.string.map_ui_retry),actions::refresh,false); return; }
		LinearLayout stateRow=new LinearLayout(getContext()); stateRow.setGravity(Gravity.CENTER_VERTICAL); stateRow.setPadding(0,V.dp(6),0,V.dp(12));
		TextView appearance=text(visibilityLabel(settings.visibility),R.style.m3_label_large,primary); stateRow.addView(appearance,new LinearLayout.LayoutParams(0,-2,1));
		compactAction(stateRow,getString(R.string.map_ui_manage_appearance),this::showSettings); body.addView(stateRow);
		if(points.isEmpty()){ empty(R.string.map_ui_empty_title,R.string.map_ui_empty_body); action(getString(R.string.map_ui_recenter),actions::locate,false); }
		else for(MapModels.Point point:points) personRow(point);
	}
	private void personRow(MapModels.Point point){
		LinearLayout row=new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(V.dp(76)); row.setPadding(0,V.dp(8),0,V.dp(8));
		ImageView avatar=avatar(point,48); LinearLayout.LayoutParams avatarParams=new LinearLayout.LayoutParams(V.dp(48),V.dp(48)); avatarParams.rightMargin=V.dp(12); row.addView(avatar,avatarParams);
		LinearLayout details=new LinearLayout(getContext()); details.setOrientation(LinearLayout.VERTICAL);
		TextView name=text(name(point),R.style.m3_title_medium,onSurface); name.setSingleLine(); name.setEllipsize(TextUtils.TruncateAt.END); details.addView(name);
		TextView hint=text(point.anonymous?getString(R.string.map_ui_anonymous_summary):precisionLabel(point.precisionLevel),R.style.m3_body_small,secondary); hint.setPadding(0,V.dp(4),0,0); details.addView(hint);
		row.addView(details,new LinearLayout.LayoutParams(0,-2,1));
		if(canOpenProfile(point)) compactAction(row,getString(R.string.map_ui_view_profile),()->actions.profile(point));
		row.setOnClickListener(v->{ actions.focus(point); showPerson(point); }); body.addView(row,new LinearLayout.LayoutParams(-1,-2)); divider(body);
	}
	private String name(MapModels.Point point){
		if(point.anonymous || point.account==null) return getString(R.string.map_ui_anonymous_person);
		String name=point.account.displayName; return name==null||name.isBlank()?String.valueOf(point.account.username):name;
	}
	private boolean canOpenProfile(MapModels.Point point){ return !point.anonymous&&point.account!=null&&point.account.id!=null; }
	private ImageView avatar(MapModels.Point point,int size){
		ImageView image=new ImageView(getContext()); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setImageResource(point.anonymous?R.drawable.ic_person_24px:R.drawable.image_placeholder);
		image.setBackground(SponsorUi.rounded(withAlpha(primary,.12f),size/2)); image.setOutlineProvider(org.joinmastodon.android.ui.OutlineProviders.OVAL); image.setClipToOutline(true);
		if(!point.anonymous&&point.account!=null&&point.account.avatar!=null) ViewImageLoader.loadWithoutAnimation(image,image.getDrawable(),new UrlImageLoaderRequest(point.account.avatar,V.dp(size),V.dp(size)));
		return image;
	}
	private void person(){
		if(selected==null){ empty(R.string.map_ui_empty_title,R.string.map_ui_empty_body); return; }
		LinearLayout heading=new LinearLayout(getContext()); heading.setGravity(Gravity.CENTER_VERTICAL); heading.setPadding(0,V.dp(12),0,V.dp(16));
		heading.addView(avatar(selected,64),new LinearLayout.LayoutParams(V.dp(64),V.dp(64)));
		LinearLayout details=new LinearLayout(getContext()); details.setOrientation(LinearLayout.VERTICAL); details.setPadding(V.dp(16),0,0,0);
		details.addView(text(name(selected),R.style.m3_title_large,onSurface)); details.addView(text(precisionLabel(selected.precisionLevel),R.style.m3_body_medium,secondary)); heading.addView(details,new LinearLayout.LayoutParams(0,-2,1)); body.addView(heading);
		if(selected.region!=null&&selected.region.name!=null) body.addView(text(selected.region.name,R.style.m3_body_medium,secondary));
		if(canOpenProfile(selected)) action(getString(R.string.map_ui_view_profile),()->actions.profile(selected),true);
		else empty(R.string.map_ui_anonymous_person,R.string.map_ui_anonymous_summary);
	}
	private void preferences(){
		if(settings==null){ messageBlock(); action(getString(R.string.map_ui_retry),actions::refresh,false); return; }
		heading(R.string.map_ui_my_appearance);
		switchRow(R.string.map_ui_appear_on_map,!"hidden".equals(settings.visibility),value->{ if(value) open(MapSheetState.Page.VISIBILITY); else actions.stopAppearance(); });
		settingRow(R.string.map_ui_visibility,visibilityLabel(settings.visibility),()->open(MapSheetState.Page.VISIBILITY));
		switchRow(R.string.map_ui_anonymous,settings.anonymous,actions::anonymous);
		settingRow(R.string.map_ui_precision,precisionLabel(settings.precisionLevel),()->open(MapSheetState.Page.PRECISION));
		heading(R.string.map_ui_position_source);
		settingRow(R.string.map_ui_device_location,"device".equals(settings.locationMode)?getString(R.string.map_ui_selected):"",actions::useDeviceLocation);
		settingRow(R.string.map_ui_pinned_location,"pinned".equals(settings.locationMode)?getString(R.string.map_ui_selected):getString(R.string.map_ui_sponsor_only),settings.canChooseLocation?actions::beginPin:actions::sponsor);
		heading(R.string.map_ui_update_service);
		settingRow(R.string.map_ui_update_location,"",actions::locate);
		settingRow(R.string.map_presence_provider,providerLabel(providerId),()->open(MapSheetState.Page.PROVIDER));
		settingRow(R.string.map_presence_privacy_link,"",actions::privacy);
		if(!"hidden".equals(settings.visibility)) action(getString(R.string.map_ui_stop_appearance),actions::stopAppearance,false);
	}
	private void visibilityOptions(){
		if(settings==null){ messageBlock(); return; }
		boolean first=sheetState.visibilityRequired();
		messageBlock();
		if(first){
			TextView note=text(getString(R.string.map_presence_visibility_required),R.style.m3_body_medium,onSurface);
			note.setPadding(0,0,0,V.dp(12)); body.addView(note);
		}
		String[] values={"public","friends","hidden"};
		int[] titles={R.string.map_ui_everyone,R.string.map_ui_mutual,R.string.map_ui_invisible};
		int[] hints={R.string.map_ui_everyone_summary,R.string.map_ui_mutual_summary,R.string.map_ui_invisible_summary};
		for(int i=0;i<values.length;i++){
			String value=values[i]; boolean allowed=MapModels.canChooseVisibility(settings,value);
choice(titles[i],allowed?hints[i]:R.string.map_presence_visibility_sponsor_only,
						value.equals(first?pendingVisibility:settings.visibility),!allowed,()->{
				if(!allowed){ actions.visibilitySponsor(); return; }
				if(first){ pendingVisibility=value; rebuild(false); }
				else actions.visibility(value);
			});
		}
		if(first){
			Button confirm=SponsorUi.button(body,getString(R.string.map_presence_visibility_continue),()->{
				if(!busy && pendingVisibility!=null && MapModels.canConfirmVisibility(settings,pendingVisibility)) actions.confirmVisibility(pendingVisibility);
			});
			confirm.setEnabled(!busy && pendingVisibility!=null && MapModels.canConfirmVisibility(settings,pendingVisibility));
		}
	}
		private void precisionOptions(){
			if(settings==null){ messageBlock(); return; }
			String[] values={"city","5km","1km","200m"}; int[] labels={R.string.map_ui_city_precision,R.string.map_ui_five_km,R.string.map_ui_one_km,R.string.map_ui_two_hundred_m};
			for(int i=0;i<values.length;i++){
				String value=values[i]; boolean allowed=MapModels.canChoosePrecision(settings,value);
				choice(labels[i],allowed?R.string.map_ui_precision_summary:R.string.map_presence_city_sponsor_only,
						value.equals(settings.precisionLevel),!allowed,()->{
							if(!allowed){ actions.cityPrecisionSponsor(); return; }
							actions.precision(value);
						});
			}
		}
	private String providerLabel(String id){ return getString("baidu".equals(id)?R.string.map_presence_baidu:"amap".equals(id)?R.string.map_presence_amap:R.string.map_ui_provider_auto); }
	private void providerOptions(){
		for(String id:new String[]{"auto","baidu","amap"}) choice("auto".equals(id)?R.string.map_ui_provider_auto:"baidu".equals(id)?R.string.map_presence_baidu:R.string.map_presence_amap,R.string.map_ui_provider_summary,id.equals(providerId),()->actions.provider(id));
	}
	private void consentContent(){
		TextView consent=text(getString(R.string.map_presence_privacy_body),R.style.m3_body_medium,onSurface); consent.setLineSpacing(V.dp(3),1f); body.addView(consent,new LinearLayout.LayoutParams(-1,-2));
		TextView note=text(getString(R.string.map_ui_consent_location_note),R.style.m3_body_medium,primary); note.setLineSpacing(V.dp(3),1f); note.setPadding(0,V.dp(12),0,0); body.addView(note,new LinearLayout.LayoutParams(-1,-2));
		action(getString(R.string.map_presence_privacy_link),actions::privacy,false);
		consentButton=SponsorUi.button(body,getString(R.string.map_presence_agree),()->{ if(canSubmitConsent()) actions.consent(); });
		consentButton.setEnabled(canSubmitConsent());
		if(settings!=null && !"hidden".equals(settings.visibility)) action(getString(R.string.map_ui_stop_appearance),actions::stopAppearance,false);
	}
	private void pinContent(){
		body.addView(text(getString(R.string.map_ui_pin_instructions),R.style.m3_body_medium,secondary));
		action(getString(R.string.map_presence_confirm),actions::confirmPin,true); action(getString(R.string.map_ui_cancel),actions::cancelPin,false);
	}
	private void heading(int id){ TextView text=text(getString(id),R.style.m3_title_medium,onSurface); text.setTypeface(Typeface.DEFAULT,Typeface.BOLD); text.setPadding(0,V.dp(18),0,V.dp(8)); body.addView(text); }
	private void settingRow(int title,String value,Runnable action){ summaryRow(body,title,value,action); divider(body); }
	private void switchRow(int title,boolean checked,java.util.function.Consumer<Boolean> action){
		LinearLayout row=new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(V.dp(56));
		row.addView(text(getString(title),R.style.m3_body_large,onSurface),new LinearLayout.LayoutParams(0,-2,1));
		Switch control=new Switch(getContext()); control.setChecked(checked); control.setContentDescription(getString(title)); control.setButtonTintList(ColorStateList.valueOf(primary)); control.setOnCheckedChangeListener((button,value)->action.accept(value)); row.addView(control);
		body.addView(row,new LinearLayout.LayoutParams(-1,-2)); divider(body);
	}
	private void choice(int title,int hint,boolean selected,Runnable action){ choice(title,hint,selected,false,action); }
	private void choice(int title,int hint,boolean selected,boolean restricted,Runnable action){
		LinearLayout card=new LinearLayout(getContext()); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(V.dp(16),V.dp(14),V.dp(16),V.dp(14));
		TextView label=text(getString(title)+(selected?"  ✓":""),R.style.m3_title_medium,restricted?secondary:selected?primary:onSurface); card.addView(label);
		if(restricted){
			label.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_fluent_lock_closed_24_regular,0,0,0);
			label.setCompoundDrawableTintList(ColorStateList.valueOf(secondary)); label.setCompoundDrawablePadding(V.dp(8));
			label.setAlpha(.6f);
			card.setContentDescription(getString(title)+"，"+getString(hint));
		}
		TextView summary=text(getString(hint),R.style.m3_body_small,secondary); summary.setPadding(0,V.dp(6),0,0); card.addView(summary);
		SponsorUi.selectable(card,selected,!busy,action); LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.bottomMargin=V.dp(10); body.addView(card,params);
	}
	private void empty(int title,int subtitle){
		TextView heading=text(getString(title),R.style.m3_title_medium,onSurface); heading.setPadding(0,V.dp(12),0,V.dp(6)); body.addView(heading);
		TextView summary=text(getString(subtitle),R.style.m3_body_medium,secondary); summary.setPadding(0,0,0,V.dp(16)); body.addView(summary);
	}
	private void action(String title,Runnable action,boolean primaryAction){
		if(primaryAction) SponsorUi.button(body,title,action); else SponsorUi.secondary(body,title,action);
	}
	private void compactAction(LinearLayout parent,String title,Runnable action){
		Button button=SponsorUi.inlineAction(parent,title,action); button.setBackground(roundRipple(withAlpha(primary,.09f),20)); button.setTextColor(primary); button.setMinHeight(V.dp(44));
		button.getLayoutParams().height=V.dp(44);
	}
	private int availablePanel(){ return Math.max(0,overlayHeight-topInset-bottomInset-V.dp(88)); }
	private int collapsedPanel(){ return V.dp(getResources().getConfiguration().fontScale>1.2f?128:112); }
	private void layoutOverlays(boolean animate){
		if(overlayHeight==0) return;
		LayoutParams headerParams=(LayoutParams)header.getLayoutParams(); headerParams.topMargin=topInset+V.dp(12); headerParams.leftMargin=headerParams.rightMargin=V.dp(16); header.setLayoutParams(headerParams);
		LayoutParams gateParams=(LayoutParams)verificationGate.getLayoutParams(); gateParams.topMargin=topInset; gateParams.bottomMargin=bottomInset; verificationGate.setLayoutParams(gateParams);
		LayoutParams blanketParams=(LayoutParams)gateBlanket.getLayoutParams(); blanketParams.bottomMargin=bottomInset; gateBlanket.setLayoutParams(blanketParams);
		applyPrivacyProgress(privacyProgress);
		int target=MapSheetState.panelHeight(availablePanel(),collapsedPanel(),sheetState.height());
		if(animator!=null) animator.cancel();
		if(animate && panelHeight>0){ animator=ValueAnimator.ofInt(panelHeight,target); animator.setDuration(240); animator.addUpdateListener(value->applyPanelHeight((int)value.getAnimatedValue())); animator.start(); }
		else applyPanelHeight(target);
		scroll.setVisibility(sheetState.height()==MapSheetState.Height.COLLAPSED?GONE:VISIBLE);
	}
	private void applyPanelHeight(int height){
		panelHeight=height;
		LayoutParams params=(LayoutParams)panel.getLayoutParams(); params.height=height; params.leftMargin=params.rightMargin=V.dp(12); params.bottomMargin=bottomInset+V.dp(10); panel.setLayoutParams(params);
		LayoutParams toolParams=(LayoutParams)tools.getLayoutParams(); toolParams.rightMargin=V.dp(16); toolParams.bottomMargin=bottomInset+V.dp(26)+height; tools.setLayoutParams(toolParams);
		tools.setVisibility(accessBlocked() || height>availablePanel()*.8f?GONE:VISIBLE);
		actions.panelChanged(topInset+V.dp(184),bottomInset+height+V.dp(20));
	}
	private boolean drag(View view,MotionEvent event){
		if(accessBlocked()) return true;
		switch(event.getActionMasked()){
			case MotionEvent.ACTION_DOWN -> { dragY=event.getRawY(); dragHeight=panelHeight; dragged=false; return true; }
			case MotionEvent.ACTION_MOVE -> {
				float delta=dragY-event.getRawY();
				if(Math.abs(delta)>ViewConfiguration.get(getContext()).getScaledTouchSlop()) dragged=true;
				if(dragged){ if(animator!=null) animator.cancel(); if(getParent()!=null) getParent().requestDisallowInterceptTouchEvent(true); scroll.setVisibility(VISIBLE); applyPanelHeight(Math.max(Math.min(collapsedPanel(),availablePanel()),Math.min(availablePanel(),dragHeight+Math.round(delta)))); }
				return true;
			}
			case MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
				if(getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false);
				if(dragged){ sheetState.setHeight(MapSheetState.settleHeight(panelHeight,availablePanel(),collapsedPanel())); rebuild(true); }
				else if(event.getActionMasked()==MotionEvent.ACTION_UP) view.performClick();
				return true;
			}
		}
			return false;
	}
	/** Blank sheet space drags the sheet; any actually scrollable descendant keeps its gesture. */
	private final class BlankDragColumn extends LinearLayout{
		private boolean scrollGesture;
		private float downY;
		BlankDragColumn(Context context){ super(context); }
		@Override public boolean onInterceptTouchEvent(MotionEvent event){
			if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
				scrollGesture=scrollableAt(this,event.getX(),event.getY()); downY=event.getRawY();
				dragY=downY; dragHeight=panelHeight; dragged=false;
			}
			if(accessBlocked() || scrollGesture) return false;
			return event.getActionMasked()==MotionEvent.ACTION_MOVE && Math.abs(event.getRawY()-downY)>ViewConfiguration.get(getContext()).getScaledTouchSlop();
		}
		@Override public boolean onTouchEvent(MotionEvent event){ return drag(this,event); }
	}
	private boolean scrollableAt(View view,float x,float y){
		if(view.canScrollVertically(-1)||view.canScrollVertically(1)) return true;
		if(view instanceof ViewGroup group){
			for(int i=group.getChildCount()-1;i>=0;i--){
				View child=group.getChildAt(i);
				float cx=x+group.getScrollX()-child.getLeft(),cy=y+group.getScrollY()-child.getTop();
				if(child.getVisibility()==VISIBLE && cx>=0 && cy>=0 && cx<child.getWidth() && cy<child.getHeight() && scrollableAt(child,cx,cy)) return true;
			}
		}
		return false;
	}
	/** Observes the very start of a user drag without stealing SDK gestures. */
	private static final class MapTouchHost extends FrameLayout{
		private float downX,downY;
		private boolean notified;
		MapTouchHost(Context context){ super(context); }
		@Override public boolean dispatchTouchEvent(MotionEvent event){
			if(getParent() instanceof FriendMapOverlay overlay){
				if(overlay.accessBlocked()) return true;
				if(event.getActionMasked()==MotionEvent.ACTION_DOWN){ downX=event.getX(); downY=event.getY(); notified=false; }
				if(event.getActionMasked()==MotionEvent.ACTION_MOVE && !notified && Math.hypot(event.getX()-downX,event.getY()-downY)>ViewConfiguration.get(getContext()).getScaledTouchSlop()){
					notified=true; overlay.onMapDragStarted();
				}
			}
			return super.dispatchTouchEvent(event);
		}
	}
}
