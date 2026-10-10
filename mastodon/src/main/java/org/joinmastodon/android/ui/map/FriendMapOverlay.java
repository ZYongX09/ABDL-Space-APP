package org.joinmastodon.android.ui.map;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.map.MapModels;
import org.joinmastodon.android.sponsors.SponsorUi;
import org.joinmastodon.android.ui.compose.navigation.MapGlassSurface;
import org.joinmastodon.android.ui.utils.UiUtils;

/** Map content stays interactive outside the controls and the embedded sheet. */
public final class FriendMapOverlay extends FrameLayout{
	public interface Actions{
		void refresh();
		void locate();
		void visibility(String value);
		void precision(String value);
		void anonymous(boolean value);
		void provider(String value);
		void useDeviceLocation();
		void beginPin();
		void confirmPin();
		void cancelPin();
		void consent();
		void privacy();
		void sponsor();
		void profile(MapModels.Point point);
		void focus(MapModels.Point point);
		void panelChanged(int topInset,int bottomInset);
	}

	private final Actions actions;
	private final MapSheetState sheetState=new MapSheetState();
	private final FrameLayout mapHost, header, privacyCard, tools, panel;
	private final LinearLayout privacyRows, panelColumn, panelHeader;
	private final ScrollView scroll;
	private final LinearLayout body;
	private final TextView city, visibilityValue, precisionValue, panelTitle, panelSummary, message;
	private final ImageButton settingsButton, backButton, refreshButton;
	private final View handle;
	private final List<MapGlassMaterial> materials=new ArrayList<>();
	private final List<MapModels.Point> points=new ArrayList<>();
	private final ImageView crosshair;
	private final int primary,onSurface,secondary,surface,outline;
	private MapModels.Settings settings;
	private MapModels.Point selected;
	private String providerId="auto";
	private CharSequence messageText="";
	private boolean busy, serviceUnavailable;
	private int topInset,bottomInset, panelHeight;
	private ValueAnimator animator;
	private float dragY;
	private int dragHeight;
	private boolean dragged;

	public FriendMapOverlay(Context context,Actions actions){
		super(context); this.actions=actions;
		primary=color(R.attr.colorM3Primary); onSurface=color(R.attr.colorM3OnSurface); secondary=color(R.attr.colorM3OnSurfaceVariant);
		surface=color(R.attr.colorM3Surface); outline=color(R.attr.colorM3OutlineVariant);
		setBackgroundColor(surface);
		mapHost=new FrameLayout(context); mapHost.setId(R.id.friend_map_canvas); addView(mapHost,new LayoutParams(-1,-1));

		header=new FrameLayout(context); addView(header,new LayoutParams(-1,V.dp(48),Gravity.TOP));
		FrameLayout cityShell=glass(24,false); city=text(getString(R.string.map_ui_city_loading),R.style.m3_title_medium,onSurface);
		city.setGravity(Gravity.CENTER_VERTICAL); city.setPadding(V.dp(16),0,V.dp(16),0);
		cityShell.addView(city,new LayoutParams(-1,-1));
		header.addView(cityShell,new LayoutParams(-2,V.dp(48),Gravity.START));
		FrameLayout settingsShell=glass(24,false); settingsButton=icon(R.drawable.ic_settings_24px,R.string.map_presence_preferences,this::showSettings);
		settingsShell.addView(settingsButton,new LayoutParams(-1,-1));
		header.addView(settingsShell,new LayoutParams(V.dp(48),V.dp(48),Gravity.END));

		privacyCard=glass(24,false); privacyCard.setId(R.id.friend_map_privacy); addView(privacyCard,new LayoutParams(-1,-2,Gravity.TOP));
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
		panelColumn=new LinearLayout(context); panelColumn.setOrientation(LinearLayout.VERTICAL); panelColumn.setPadding(V.dp(20),0,V.dp(20),0); panel.addView(panelColumn,new LayoutParams(-1,-1));
		FrameLayout handleArea=new FrameLayout(context); handleArea.setMinimumHeight(V.dp(22));
		handle=new View(context); handle.setBackground(SponsorUi.rounded(withAlpha(secondary,.40f),2));
		LayoutParams handleParams=new LayoutParams(V.dp(36),V.dp(4),Gravity.CENTER); handleArea.addView(handle,handleParams);
		handleArea.setContentDescription(getString(R.string.map_ui_expand)); handleArea.setOnTouchListener(this::drag);
		handleArea.setOnClickListener(v->toggleHeight()); panelColumn.addView(handleArea,new LinearLayout.LayoutParams(-1,V.dp(24)));

		panelHeader=new LinearLayout(context); panelHeader.setGravity(Gravity.CENTER_VERTICAL); panelHeader.setMinimumHeight(V.dp(48));
		backButton=icon(R.drawable.ic_arrow_back_24,R.string.map_ui_back,()->{ sheetState.back(); rebuild(true); }); panelHeader.addView(backButton,new LinearLayout.LayoutParams(V.dp(44),V.dp(44)));
		panelTitle=text(getString(R.string.map_ui_nearby),R.style.m3_title_large,onSurface); panelTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
		panelTitle.setOnClickListener(v->toggleHeight()); panelHeader.addView(panelTitle,new LinearLayout.LayoutParams(0,-2,1));
		refreshButton=icon(R.drawable.ic_fluent_arrow_clockwise_24_regular,R.string.map_presence_refresh,actions::refresh); panelHeader.addView(refreshButton,new LinearLayout.LayoutParams(V.dp(44),V.dp(44)));
		panelHeader.setOnTouchListener(this::drag); panelColumn.addView(panelHeader,new LinearLayout.LayoutParams(-1,V.dp(48)));
		panelSummary=text(getString(R.string.map_ui_loading_summary),R.style.m3_body_medium,secondary); panelSummary.setMaxLines(2);
		panelSummary.setPadding(0,0,0,V.dp(12)); panelColumn.addView(panelSummary,new LinearLayout.LayoutParams(-1,-2));
		scroll=new ScrollView(context); scroll.setFillViewport(false); scroll.setClipToPadding(false);
		body=new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(0,V.dp(4),0,V.dp(20)); scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
		panelColumn.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
		message=text("",R.style.m3_body_medium,secondary);
		addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{ if(r-l!=or-ol || b-t!=ob-ot) layoutOverlays(false); });
		rebuild(false);
	}

	public FrameLayout mapHost(){ return mapHost; }
	public void updateSettings(MapModels.Settings settings){ this.settings=settings; rebuild(false); }
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
	public void showConsent(){ open(MapSheetState.Page.CONSENT); }
	public void showPin(){ crosshair.setVisibility(VISIBLE); open(MapSheetState.Page.PIN); }
	public void finishPin(){ crosshair.setVisibility(GONE); sheetState.back(); rebuild(true); }
	public void setNavigationInsets(int top,int bottom){ topInset=Math.max(0,top); bottomInset=Math.max(0,bottom); layoutOverlays(false); }
	public boolean onBackPressed(){
		if(sheetState.page()==MapSheetState.Page.PIN){ actions.cancelPin(); return true; }
		if(sheetState.back()){ rebuild(true); return true; } return false;
	}
	public MapSheetState state(){ return sheetState; }
	public void setSnapshot(Bitmap bitmap){
		int[] origin=new int[2]; mapHost.getLocationInWindow(origin);
		for(MapGlassMaterial material:materials) material.update(bitmap,origin[0],origin[1]);
	}
	public void dispose(){ if(animator!=null) animator.cancel(); for(MapGlassMaterial material:materials) material.dispose(); materials.clear(); }

	private void open(MapSheetState.Page page){ sheetState.open(page); rebuild(true); }
	private void toggleHeight(){ sheetState.setHeight(sheetState.height()==MapSheetState.Height.COLLAPSED ? MapSheetState.Height.HALF : MapSheetState.Height.COLLAPSED); rebuild(true); }
	private FrameLayout glass(int radius,boolean large){
		FrameLayout shell=new FrameLayout(getContext()); MapGlassMaterial material=new MapGlassMaterial(getContext(),radius,large); materials.add(material);
		shell.addView(material.getView(),new LayoutParams(-1,-1)); shell.setClipChildren(true); return shell;
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
		if(known){ String region=settings.currentRegion==null||settings.currentRegion.name==null?getString(R.string.map_presence_current_city):settings.currentRegion.name;
			city.setText(settings.canViewNationwide?getString(R.string.map_ui_scope_nationwide):getContext().getString(R.string.map_ui_scope_city,region)); }
		else city.setText(getString(R.string.map_ui_city_loading));
		backButton.setVisibility(sheetState.page()==MapSheetState.Page.NEARBY?GONE:VISIBLE);
		refreshButton.setVisibility(sheetState.page()==MapSheetState.Page.NEARBY?VISIBLE:GONE); refreshButton.setEnabled(!busy);
		panelTitle.setText(titleForPage());
		panelSummary.setText(summaryForPage());
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
		switchRow(R.string.map_ui_appear_on_map,!"hidden".equals(settings.visibility),value->actions.visibility(value?"public":"hidden"));
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
		if(!"hidden".equals(settings.visibility)) action(getString(R.string.map_ui_stop_appearance),()->actions.visibility("hidden"),false);
	}
	private void visibilityOptions(){
		if(settings==null){ messageBlock(); return; }
		choice(R.string.map_ui_everyone,R.string.map_ui_everyone_summary,"public".equals(settings.visibility),()->actions.visibility("public"));
		choice(R.string.map_ui_mutual,R.string.map_ui_mutual_summary,"friends".equals(settings.visibility),()->actions.visibility("friends"));
		choice(R.string.map_ui_invisible,R.string.map_ui_invisible_summary,"hidden".equals(settings.visibility),()->actions.visibility("hidden"));
	}
	private void precisionOptions(){
		if(settings==null){ messageBlock(); return; }
		String[] values={"city","5km","1km","200m"}; int[] labels={R.string.map_ui_city_precision,R.string.map_ui_five_km,R.string.map_ui_one_km,R.string.map_ui_two_hundred_m};
		for(int i=0;i<values.length;i++){ String value=values[i]; choice(labels[i],R.string.map_ui_precision_summary,value.equals(settings.precisionLevel),()->actions.precision(value)); }
	}
	private String providerLabel(String id){ return getString("baidu".equals(id)?R.string.map_presence_baidu:"amap".equals(id)?R.string.map_presence_amap:R.string.map_ui_provider_auto); }
	private void providerOptions(){
		for(String id:new String[]{"auto","baidu","amap"}) choice("auto".equals(id)?R.string.map_ui_provider_auto:"baidu".equals(id)?R.string.map_presence_baidu:R.string.map_presence_amap,R.string.map_ui_provider_summary,id.equals(providerId),()->actions.provider(id));
	}
	private void consentContent(){
		TextView consent=text(getString(R.string.map_presence_privacy_body),R.style.m3_body_medium,onSurface); consent.setLineSpacing(V.dp(3),1f); body.addView(consent,new LinearLayout.LayoutParams(-1,-2));
		action(getString(R.string.map_presence_privacy_link),actions::privacy,false); action(getString(R.string.map_presence_agree),actions::consent,true);
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
	private void choice(int title,int hint,boolean selected,Runnable action){
		LinearLayout card=new LinearLayout(getContext()); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(V.dp(16),V.dp(14),V.dp(16),V.dp(14));
		TextView label=text(getString(title)+(selected?"  ✓":""),R.style.m3_title_medium,selected?primary:onSurface); card.addView(label);
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
	private int availablePanel(){ return Math.max(0,getHeight()-topInset-bottomInset-V.dp(88)); }
	private int collapsedPanel(){ return V.dp(getResources().getConfiguration().fontScale>1.2f?128:112); }
	private void layoutOverlays(boolean animate){
		if(getHeight()==0) return;
		LayoutParams headerParams=(LayoutParams)header.getLayoutParams(); headerParams.topMargin=topInset+V.dp(12); headerParams.leftMargin=headerParams.rightMargin=V.dp(16); header.setLayoutParams(headerParams);
		LayoutParams privacyParams=(LayoutParams)privacyCard.getLayoutParams(); privacyParams.topMargin=topInset+V.dp(72); privacyParams.leftMargin=privacyParams.rightMargin=V.dp(16); privacyCard.setLayoutParams(privacyParams);
		privacyCard.setVisibility(sheetState.height()==MapSheetState.Height.EXPANDED?GONE:VISIBLE);
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
		tools.setVisibility(height>availablePanel()*.8f?GONE:VISIBLE);
		actions.panelChanged(topInset+V.dp(184),bottomInset+height+V.dp(20));
	}
	private boolean drag(View view,MotionEvent event){
		switch(event.getActionMasked()){
			case MotionEvent.ACTION_DOWN -> { dragY=event.getRawY(); dragHeight=panelHeight; dragged=false; return true; }
			case MotionEvent.ACTION_MOVE -> {
				float delta=dragY-event.getRawY();
				if(Math.abs(delta)>ViewConfiguration.get(getContext()).getScaledTouchSlop()) dragged=true;
				if(dragged){ if(animator!=null) animator.cancel(); getParent().requestDisallowInterceptTouchEvent(true); scroll.setVisibility(VISIBLE); applyPanelHeight(Math.max(Math.min(collapsedPanel(),availablePanel()),Math.min(availablePanel(),dragHeight+Math.round(delta)))); }
				return true;
			}
			case MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
				getParent().requestDisallowInterceptTouchEvent(false);
				if(dragged){ sheetState.setHeight(MapSheetState.settleHeight(panelHeight,availablePanel(),collapsedPanel())); rebuild(true); }else view.performClick();
				return true;
			}
		}
		return false;
	}
}
