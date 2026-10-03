package org.joinmastodon.android.fragments.sponsors;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.sponsors.SponsorAvailability;
import org.joinmastodon.android.api.requests.sponsors.SponsorRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.MastodonToolbarFragment;
import org.joinmastodon.android.model.sponsors.SponsorModels;
import org.joinmastodon.android.model.sponsors.SponsorModels.Catalog;
import org.joinmastodon.android.model.sponsors.SponsorModels.Config;
import org.joinmastodon.android.model.sponsors.SponsorModels.Me;
import org.joinmastodon.android.sponsors.SponsorContrast;
import org.joinmastodon.android.sponsors.SponsorRefresh;
import org.joinmastodon.android.sponsors.SponsorUi;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

/** Account-scoped supporter nickname color selection. All color definitions come from the server catalog. */
public class SponsorColorFragment extends MastodonToolbarFragment{
	private TextView centeredTitle;
	private ImageView moreButton;
	private String accountID;
	private AccountSession session;
	private Catalog catalog;
	private Me me;
	private String selectedColorKey;
	private LinearLayout content, footer, options, previewHeader, selectionContent;
	private ScrollView scroll;
	private Button applyButton;
	private MastodonAPIRequest<?> request;
	private final ArrayList<MastodonAPIRequest<?>> requests=new ArrayList<>();
	private final Handler main=new Handler(Looper.getMainLooper());
	private int generation;
	private boolean loading, busy, unsupported;

	@Override public void onCreate(Bundle state){
		super.onCreate(state);
		accountID=getArguments().getString("account");
		session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		setTitle(R.string.sponsor_ui_colors_page);
	}

	@Override public void onViewCreated(View view, Bundle state){
		super.onViewCreated(view, state);
		installSponsorToolbar(view.findViewById(R.id.toolbar));
	}
	@Override public void onUpdateToolbar(){
		super.onUpdateToolbar();
		installSponsorToolbar(getToolbar());
	}
	private void installSponsorToolbar(android.widget.Toolbar toolbar){
		if(toolbar==null) return;
		int toolbarColor=UiUtils.isDarkTheme() ? Color.parseColor("#18354A") : Color.parseColor("#BDE7FA");
		toolbar.setBackgroundColor(toolbarColor);
		setStatusBarColor(toolbarColor);
		if(centeredTitle==null || centeredTitle.getParent()!=toolbar){
			if(centeredTitle!=null && centeredTitle.getParent() instanceof ViewGroup oldParent) oldParent.removeView(centeredTitle);
			centeredTitle=new TextView(getActivity());
			centeredTitle.setGravity(Gravity.CENTER);
			centeredTitle.setTextAppearance(R.style.m3_title_large);
			centeredTitle.setTextColor(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurface));
			toolbar.addView(centeredTitle, new android.widget.Toolbar.LayoutParams(-2, -1, Gravity.CENTER));
		}
		centeredTitle.setText(getTitle());
		if(moreButton==null || moreButton.getParent()!=toolbar){
			if(moreButton!=null && moreButton.getParent() instanceof ViewGroup oldParent) oldParent.removeView(moreButton);
			moreButton=new ImageView(getActivity());
			moreButton.setImageResource(R.drawable.ic_fluent_more_horizontal_24_regular);
			moreButton.setContentDescription(getString(R.string.more_options));
			moreButton.setPadding(V.dp(16), V.dp(16), V.dp(16), V.dp(16));
			moreButton.setColorFilter(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurface));
			android.widget.Toolbar.LayoutParams params=new android.widget.Toolbar.LayoutParams(
					V.dp(56), V.dp(56), Gravity.END|Gravity.CENTER_VERTICAL);
			toolbar.addView(moreButton, params);
		}
		toolbar.setTitle("");
	}

	@Override public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle state){
		LinearLayout root=new LinearLayout(getActivity()); root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(SponsorUi.surface(getActivity()));
		scroll=new ScrollView(getActivity()); scroll.setFillViewport(true); scroll.setClipToPadding(false);
		content=new LinearLayout(getActivity()); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(0, 0, 0, V.dp(24)); scroll.addView(content);
		previewHeader=new LinearLayout(getActivity()); previewHeader.setOrientation(LinearLayout.VERTICAL); previewHeader.setPadding(V.dp(20), V.dp(18), V.dp(20), V.dp(28)); content.addView(previewHeader, new LinearLayout.LayoutParams(-1, -2));
		selectionContent=new LinearLayout(getActivity()); selectionContent.setOrientation(LinearLayout.VERTICAL); selectionContent.setPadding(V.dp(20), V.dp(22), V.dp(20), 0); content.addView(selectionContent, new LinearLayout.LayoutParams(-1, -2));
		root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
		footer=new LinearLayout(getActivity()); footer.setOrientation(LinearLayout.VERTICAL); footer.setPadding(V.dp(20), V.dp(6), V.dp(20), V.dp(12)); footer.setBackgroundColor(SponsorUi.surface(getActivity()));
		applyButton=SponsorUi.button(footer, getString(R.string.sponsor_ui_checking), this::applyColor);
		applyButton.setMinHeight(V.dp(56)); applyButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
		root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
		showLoading();
		load();
		return root;
	}

	@Override public void onApplyWindowInsets(WindowInsets insets){
		if(content!=null) content.setPadding(insets.getSystemWindowInsetLeft(), 0, insets.getSystemWindowInsetRight(), V.dp(24));
		if(footer!=null) footer.setPadding(V.dp(20)+insets.getSystemWindowInsetLeft(), V.dp(6), V.dp(20)+insets.getSystemWindowInsetRight(), V.dp(12)+insets.getSystemWindowInsetBottom());
		super.onApplyWindowInsets(insets.replaceSystemWindowInsets(0, insets.getSystemWindowInsetTop(), 0, 0));
	}

	private boolean sessionValid(){ return session!=null && AccountSessionManager.getInstance().tryGetAccount(accountID)==session; }
	private boolean live(int token){ return token==generation && sessionValid() && getActivity()!=null; }
	private boolean enabled(){
		return sessionValid() && !unsupported && !loading && catalog!=null && me!=null
				&& Boolean.TRUE.equals(catalog.config.enabled) && me.configVersion==catalog.config.version;
	}

	private void load(){
		if(content==null || loading || busy) return;
		if(!sessionValid()){ showError(getString(R.string.sponsor_ui_session_changed)); return; }
		loading=true; unsupported=false; updateButton();
		int token=++generation;
		request=SponsorAvailability.check(accountID, new Callback<>(){
			@Override public void onSuccess(Boolean supported){ main.post(()->{
				if(!live(token)) return;
				unsupported=!Boolean.TRUE.equals(supported); request=null;
				if(unsupported){ loading=false; showUnsupported(); return; }
				loadCatalog(token);
			}); }
			@Override public void onError(ErrorResponse error){ main.post(()->{ if(live(token)){ request=null; loading=false; showError(error.toString()); } }); }
		});
	}

	private void loadCatalog(int token){
		request=SponsorRequest.catalog().setCallback(new Callback<>(){
			@Override public void onSuccess(Catalog result){
				if(!live(token)) return;
				request=SponsorRequest.me().setCallback(new Callback<>(){
					@Override public void onSuccess(Me resultMe){
						if(!live(token)) return;
						request=null; loading=false;
						if(resultMe.configVersion!=result.config.version){ showError(getString(R.string.sponsor_ui_stale)); return; }
						catalog=result; me=resultMe;
						selectedColorKey=resultMe.sponsor.colorKey;
						SponsorRefresh.apply(accountID, resultMe);
						render();
					}
					@Override public void onError(ErrorResponse error){ if(live(token)){ request=null; loading=false; showError(error.toString()); } }
				}).exec(accountID);
			}
			@Override public void onError(ErrorResponse error){ if(live(token)){ request=null; loading=false; showError(error.toString()); } }
		}).exec(accountID);
	}

	private void clearPage(){
		if(previewHeader!=null) previewHeader.removeAllViews();
		if(selectionContent!=null) selectionContent.removeAllViews();
	}
	private void showLoading(){
		if(content==null) return;
		clearPage();
		TextView loadingText=SponsorUi.text(selectionContent, getString(R.string.sponsor_ui_checking), true);
		loadingText.setPadding(0, V.dp(24), 0, V.dp(24));
		updateButton();
	}

	private void showUnsupported(){
		clearPage();
		SponsorUi.text(selectionContent, getString(R.string.sponsor_ui_unsupported), true);
		SponsorUi.text(selectionContent, getString(R.string.sponsor_ui_unsupported_body), false);
		updateButton();
	}

	private void showError(CharSequence error){
		if(content==null) return;
		clearPage();
		SponsorUi.text(selectionContent, error==null ? getString(R.string.sponsor_ui_check_failed) : error, true);
		SponsorUi.secondary(selectionContent, getString(R.string.sponsor_ui_retry), this::load);
		updateButton();
	}

	private void render(){
		if(content==null || catalog==null || me==null) return;
		clearPage();
		Config config=catalog.config;
		if(selectedColorKey==null && config.defaultColorKey!=null) selectedColorKey=config.defaultColorKey;
		renderPreview();
		TextView heading=SponsorUi.text(selectionContent, getString(R.string.sponsor_ui_choose_color), true); heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22); heading.setPadding(0, 0, 0, V.dp(16));
		options=new LinearLayout(getActivity()); options.setOrientation(LinearLayout.VERTICAL); selectionContent.addView(options, new LinearLayout.LayoutParams(-1, -2));
		List<SponsorModels.Color> colors=new ArrayList<>(config.colors==null ? List.of() : config.colors);
		List<SponsorModels.Color> ordinary=new ArrayList<>();
		List<SponsorModels.Color> permanent=new ArrayList<>();
		for(SponsorModels.Color color:colors){
			if(color.permanentOnly) permanent.add(color);
			else ordinary.add(color);
		}
		renderOrdinaryColors(ordinary);
		for(SponsorModels.Color color:permanent) addFullWidthColor(color);
		if(colors.isEmpty()) SponsorUi.text(options, getString(R.string.sponsor_ui_no_colors), false);
		if(!me.sponsor.isActive()){
			TextView locked=SponsorUi.label(selectionContent, getString(R.string.sponsor_ui_color_requires_sponsor)); locked.setPadding(0, V.dp(14), 0, V.dp(8));
		}
		updateButton();
	}

	private void renderOrdinaryColors(List<SponsorModels.Color> ordinary){
		for(int index=0;index<ordinary.size();index+=2){
			LinearLayout row=new LinearLayout(getActivity());
			row.setOrientation(LinearLayout.HORIZONTAL);
			LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1, V.dp(106));
			rowParams.bottomMargin=V.dp(14);
			options.addView(row, rowParams);
			for(int offset=0;offset<2;offset++){
				int colorIndex=index+offset;
				if(colorIndex>=ordinary.size()){
					row.addView(new View(getActivity()), new LinearLayout.LayoutParams(0, -1, 1));
					continue;
				}
				LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0, -1, 1);
				if(offset==0) params.rightMargin=V.dp(10);
				row.addView(colorCard(ordinary.get(colorIndex)), params);
			}
		}
	}

	private void addFullWidthColor(SponsorModels.Color color){
		LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1, V.dp(106));
		params.bottomMargin=V.dp(12);
		options.addView(colorCard(color), params);
	}

	private void renderPreview(){
		int[] gradient=UiUtils.isDarkTheme() ? new int[]{Color.parseColor("#18354A"), Color.parseColor("#172733"), Color.parseColor("#243D4A")} : new int[]{Color.parseColor("#BDE7FA"), Color.parseColor("#EAF7FF"), Color.parseColor("#CFE6F8")};
		GradientDrawable headerBackground=new GradientDrawable(GradientDrawable.Orientation.TL_BR, gradient); previewHeader.setBackground(headerBackground);
		LinearLayout preview=new LinearLayout(getActivity()); preview.setOrientation(LinearLayout.VERTICAL); preview.setPadding(V.dp(18), V.dp(16), V.dp(18), V.dp(16));
		int previewSurface=UiUtils.isDarkTheme() ? UiUtils.alphaBlendColors(gradient[1], Color.WHITE, .04f) : Color.WHITE;
		preview.setBackground(SponsorUi.rounded(previewSurface, 16));
		LinearLayout row=new LinearLayout(getActivity()); row.setGravity(android.view.Gravity.CENTER_VERTICAL); preview.addView(row, new LinearLayout.LayoutParams(-1, -2));
		ImageView avatar=new ImageView(getActivity()); avatar.setImageResource(R.drawable.default_avatar); avatar.setScaleType(ImageView.ScaleType.CENTER_CROP); avatar.setOutlineProvider(OutlineProviders.OVAL); avatar.setClipToOutline(true);
		row.addView(avatar, new LinearLayout.LayoutParams(V.dp(48), V.dp(48)));
		if(session!=null && session.self!=null && !TextUtils.isEmpty(session.self.avatarStatic)) ViewImageLoader.loadWithoutAnimation(avatar, avatar.getDrawable(), new UrlImageLoaderRequest(GlobalUserPreferences.playGifs ? session.self.avatar : session.self.avatarStatic, V.dp(48), V.dp(48)));
		LinearLayout user=new LinearLayout(getActivity()); user.setOrientation(LinearLayout.VERTICAL); user.setPadding(V.dp(12), 0, 0, 0); row.addView(user, new LinearLayout.LayoutParams(0, -2, 1));
		String name=session==null || session.self==null ? "ABDL Space 宝宝" : (TextUtils.isEmpty(session.self.displayName) ? session.self.username : session.self.displayName);
		TextView nameView=new TextView(getActivity()); nameView.setText(name); nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18); nameView.setTypeface(Typeface.DEFAULT, Typeface.BOLD); user.addView(nameView, new LinearLayout.LayoutParams(-1, -2));
		TextView time=new TextView(getActivity()); time.setText(R.string.sponsor_ui_preview_time); time.setTextAppearance(R.style.m3_body_medium); time.setTextColor(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurfaceVariant)); user.addView(time, new LinearLayout.LayoutParams(-1, -2));
		nameView.setTextColor(previewColor());
		TextView body=new TextView(getActivity());
		body.setText(R.string.sponsor_ui_color_preview_body);
		body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		body.setLineSpacing(V.dp(2), 1);
		body.setTextColor(SponsorContrast.ensure(
				UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurface), previewSurface));
		body.setPadding(0, V.dp(14), 0, V.dp(2));
		preview.addView(body, new LinearLayout.LayoutParams(-1, -2));
		previewHeader.addView(preview, new LinearLayout.LayoutParams(-1, V.dp(158)));
	}

	private int previewColor(){
		SponsorModels.Color color=findColor(selectedColorKey);
		Integer parsed=color==null ? null : SponsorContrast.parse(UiUtils.isDarkTheme() ? color.dark : color.light);
		int background=UiUtils.isDarkTheme() ? Color.parseColor("#172733") : Color.parseColor("#EAF7FF");
		return SponsorContrast.ensure(parsed==null ? UiUtils.getThemeColor(getActivity(), R.attr.colorM3Primary) : parsed, background);
	}

	private SponsorModels.Color findColor(String key){
		if(catalog==null || catalog.config.colors==null || key==null) return null;
		for(SponsorModels.Color color:catalog.config.colors) if(key.equals(color.key)) return color;
		return null;
	}

	private boolean colorBenefitAvailable(){
		return catalog!=null && catalog.config!=null && catalog.config.benefits!=null
				&& catalog.config.benefits.stream().anyMatch(benefit->"color".equals(benefit.action) && "available".equals(benefit.status));
	}

	private boolean canSelect(SponsorModels.Color color){
		if(color==null || !enabled() || !colorBenefitAvailable() || !me.sponsor.isActive()) return false;
		if(me.sponsor.permanent) return color.permanentOnly;
		return !color.permanentOnly;
	}

	private View colorCard(SponsorModels.Color color){
		boolean selected=Objects.equals(selectedColorKey, color.key);
		boolean allowed=canSelect(color);
		FrameLayout card=new FrameLayout(getActivity());
		Integer parsed=SponsorContrast.parse(UiUtils.isDarkTheme() ? color.dark : color.light);
		int base=parsed==null ? SponsorUi.cardColor(getActivity()) : parsed;
		int fill=UiUtils.alphaBlendColors(SponsorUi.surface(getActivity()), base, UiUtils.isDarkTheme() ? .20f : .12f);
		GradientDrawable shape=new GradientDrawable();
		shape.setColor(fill);
		shape.setCornerRadius(V.dp(16));
		shape.setStroke(V.dp(selected ? 2 : 1),
				selected ? base : UiUtils.getThemeColor(getActivity(), R.attr.colorM3OutlineVariant));
		card.setBackground(new RippleDrawable(ColorStateList.valueOf(UiUtils.getThemeColor(getActivity(), android.R.attr.colorControlHighlight)), shape, null));
		card.setAlpha(allowed ? 1f : .48f); card.setEnabled(allowed); card.setFocusable(true);
		card.setOnClickListener(v->{ if(!allowed) return; selectedColorKey=color.key; render(); });
		TextView title=new TextView(getActivity());
		title.setText(color.name);
		title.setGravity(android.view.Gravity.CENTER);
		title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
		title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		title.setTextColor(SponsorContrast.ensure(base, fill));
		card.addView(title, new FrameLayout.LayoutParams(-1, -1));
		if(selected) addSelectedCheck(card, base);
		if(color.permanentOnly && !me.sponsor.permanent) addLockedLabel(card);
		card.setContentDescription(color.name
				+(selected ? ", "+getString(R.string.sponsor_ui_selected) : "")
				+(allowed ? "" : ", "+getString(R.string.sponsor_ui_not_obtained)));
		card.setAccessibilityDelegate(new View.AccessibilityDelegate(){
			@Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info){
				super.onInitializeAccessibilityNodeInfo(host, info);
				info.setClassName("android.widget.RadioButton");
				info.setCheckable(true);
				info.setChecked(selected);
			}
		});
		return card;
	}

	private void addSelectedCheck(FrameLayout card, int color){
		TextView check=new TextView(getActivity());
		check.setText("✓");
		check.setGravity(android.view.Gravity.CENTER);
		check.setTextColor(Color.WHITE);
		check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
		check.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		check.setBackground(SponsorUi.rounded(color, 50));
		FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(V.dp(34), V.dp(34), android.view.Gravity.TOP|android.view.Gravity.END);
		params.setMargins(0, V.dp(10), V.dp(10), 0);
		card.addView(check, params);
	}

	private void addLockedLabel(FrameLayout card){
		TextView locked=new TextView(getActivity());
		locked.setText(R.string.sponsor_ui_not_obtained);
		locked.setGravity(android.view.Gravity.CENTER);
		locked.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
		locked.setTextColor(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurfaceVariant));
		locked.setBackground(SponsorUi.rounded(
				UiUtils.getThemeColor(getActivity(), R.attr.colorM3OutlineVariant), 20));
		FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(V.dp(72), V.dp(28), android.view.Gravity.TOP|android.view.Gravity.END);
		params.setMargins(0, V.dp(10), V.dp(10), 0);
		card.addView(locked, params);
	}

	private void updateButton(){
		if(applyButton==null) return;
		boolean dirty=me!=null && selectedColorKey!=null && !Objects.equals(selectedColorKey, me.sponsor.colorKey);
		applyButton.setEnabled(!loading && !busy && dirty && me!=null && canSelect(findColor(selectedColorKey)));
		applyButton.setText(loading ? R.string.sponsor_ui_checking : busy ? R.string.sponsor_ui_setting : dirty ? R.string.sponsor_ui_set_color : R.string.sponsor_ui_set_done);
	}

	private void applyColor(){
		SponsorModels.Color color=findColor(selectedColorKey);
		if(!sessionValid() || color==null || !canSelect(color) || me==null || busy) return;
		busy=true; updateButton();
		int token=generation;
		MastodonAPIRequest<Me> operation=SponsorRequest.color(color.key);
		requests.add(operation.setCallback(new Callback<>(){
			@Override public void onSuccess(Me result){
				requests.remove(operation); if(!live(token)) return;
				busy=false; me=result; selectedColorKey=result.sponsor.colorKey; SponsorRefresh.apply(accountID, result); render();
				Toast.makeText(getActivity(), result.message==null ? getString(R.string.sponsor_ui_updated) : result.message, Toast.LENGTH_SHORT).show();
			}
			@Override public void onError(ErrorResponse error){ requests.remove(operation); if(!live(token)) return; busy=false; error.showToast(getActivity()); updateButton(); }
		}).exec(accountID));
	}

	@Override public void onDestroyView(){
		generation++;
		main.removeCallbacksAndMessages(null);
		if(request!=null) request.cancel();
		request=null;
		for(MastodonAPIRequest<?> operation:requests) operation.cancel();
		requests.clear();
		content=null;
		footer=null;
		options=null;
		previewHeader=null;
		selectionContent=null;
		centeredTitle=null;
		moreButton=null;
		scroll=null;
		applyButton=null;
		loading=false;
		busy=false;
		super.onDestroyView();
	}

}
