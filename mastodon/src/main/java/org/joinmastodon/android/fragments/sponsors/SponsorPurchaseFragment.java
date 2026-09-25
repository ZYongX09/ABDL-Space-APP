package org.joinmastodon.android.fragments.sponsors;

import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.sponsors.SponsorRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.MastodonToolbarFragment;
import org.joinmastodon.android.model.sponsors.SponsorModels.*;
import org.joinmastodon.android.sponsors.ForegroundReadTimer;
import org.joinmastodon.android.sponsors.SponsorOperation;
import org.joinmastodon.android.sponsors.SponsorPurchaseSafety;
import org.joinmastodon.android.sponsors.SponsorUi;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.utils.V;

/** Reading and checkout are separate. Every launch requires a fresh catalogue verification. */
public class SponsorPurchaseFragment extends MastodonToolbarFragment{
	private String accountID, planID, fingerprint;
	private Catalog catalog;
	private Plan plan;
	private LinearLayout content, footer;
	private ScrollView scroll;
	private Button buy;
	private TextView readStatus;
	private android.widget.ProgressBar readProgress;
	private AccountSession session;
	private boolean resumed, shown=true, validating, loaded, rendered;
	private int generation, scrollPosition;
	private MastodonAPIRequest<?> request;
	private final ForegroundReadTimer timer=new ForegroundReadTimer();
	private final Handler handler=new Handler(Looper.getMainLooper());
	private final Runnable tick=this::tick;
	private final ViewTreeObserver.OnWindowFocusChangeListener focusListener=focus->updateVisibility();

	@Override public void onCreate(Bundle state){
		super.onCreate(state);
		accountID=getArguments().getString("account"); planID=getArguments().getString("plan");
		session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		setTitle(R.string.sponsor_ui_purchase);
		if(state!=null && accountID.equals(state.getString("readAccount")) && planID.equals(state.getString("readPlan"))){
			fingerprint=state.getString("readFingerprint");
			timer.restore(fingerprint, state.getLong("readRequired", 5000), state.getLong("readElapsed"));
			scrollPosition=state.getInt("readScroll");
		}
	}
	@Override public void onSaveInstanceState(Bundle state){
		super.onSaveInstanceState(state);
		state.putString("readAccount", accountID); state.putString("readPlan", planID);
		state.putString("readFingerprint", timer.fingerprint());
		state.putLong("readRequired", timer.requiredMillis()); state.putLong("readElapsed", timer.elapsedMillis(SystemClock.elapsedRealtime()));
		state.putInt("readScroll", scroll==null ? scrollPosition : scroll.getScrollY());
	}
	@Override public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle state){
		LinearLayout root=new LinearLayout(getActivity()); root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(SponsorUi.surface(getActivity()));
		scroll=new ScrollView(getActivity()); scroll.setFillViewport(true);
		content=SponsorUi.column(getActivity()); scroll.addView(content);
		root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
		footer=SponsorUi.column(getActivity()); footer.setPadding(V.dp(20), V.dp(4), V.dp(20), V.dp(12));
		footer.setBackgroundColor(SponsorUi.surface(getActivity())); root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
		readStatus=SponsorUi.label(footer, getString(R.string.sponsor_ui_checking));
		readProgress=new android.widget.ProgressBar(getActivity(), null, android.R.attr.progressBarStyleHorizontal);
		readProgress.setMax(1000);
		readProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(UiUtils.getThemeColor(getActivity(), R.attr.colorM3Primary)));
		readProgress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		footer.addView(readProgress, new LinearLayout.LayoutParams(-1, V.dp(4)));
		buy=SponsorUi.button(footer, getString(R.string.sponsor_ui_checking), ()->{
			if(visible() && loaded && !validating && timer.ready(SystemClock.elapsedRealtime())) refresh(true);
		});
		buy.setEnabled(false);
		scroll.getViewTreeObserver().addOnWindowFocusChangeListener(focusListener);
		rendered=false; loaded=false;
		SponsorUi.text(content, getString(R.string.sponsor_ui_checking), false);
		return root;
	}
	@Override public void onApplyWindowInsets(WindowInsets insets){
		if(footer!=null) footer.setPadding(V.dp(20)+insets.getSystemWindowInsetLeft(), V.dp(4), V.dp(20)+insets.getSystemWindowInsetRight(), V.dp(12)+insets.getSystemWindowInsetBottom());
		if(content!=null) content.setPadding(V.dp(20)+insets.getSystemWindowInsetLeft(), V.dp(12), V.dp(20)+insets.getSystemWindowInsetRight(), V.dp(24));
		super.onApplyWindowInsets(insets.replaceSystemWindowInsets(0, insets.getSystemWindowInsetTop(), 0, 0));
	}
	@Override public void onResume(){ super.onResume(); resumed=true; refresh(false); }
	@Override public void onPause(){ resumed=false; stopPending(); super.onPause(); }
	@Override protected void onShown(){ super.onShown(); shown=true; if(resumed) refresh(false); }
	@Override protected void onHidden(){ shown=false; stopPending(); super.onHidden(); }
	@Override public void onHiddenChanged(boolean hidden){ super.onHiddenChanged(hidden); shown=!hidden; if(hidden) stopPending(); else if(resumed) refresh(false); }
	@Override public void onConfigurationChanged(Configuration config){
		timer.visible(false, SystemClock.elapsedRealtime());
		super.onConfigurationChanged(config);
		// A rotation is not a business-config change: retain reading credit and revalidate.
		if(resumed) refresh(false);
	}
	private boolean sessionValid(){ return session!=null && AccountSessionManager.getInstance().tryGetAccount(accountID)==session; }
	private boolean visible(){ return sessionValid() && resumed && shown && !isHidden() && scroll!=null && scroll.isShown() && scroll.hasWindowFocus() && getActivity()!=null; }
	private void updateVisibility(){ timer.visible(visible() && loaded && !validating, SystemClock.elapsedRealtime()); tick(); }
	private void tick(){
		handler.removeCallbacks(tick);
		long now=SystemClock.elapsedRealtime();
		timer.visible(visible() && loaded && !validating, now);
		if(buy!=null){
			boolean ready=timer.ready(now);
			buy.setEnabled(visible() && loaded && !validating && ready);
			buy.setText(validating ? getString(R.string.sponsor_ui_rechecking) : !loaded ? getString(R.string.sponsor_ui_checking)
					: ready ? getString(R.string.sponsor_ui_open_store) : getString(R.string.sponsor_ui_read_seconds, (timer.remainingMillis(now)+999)/1000));
		}
		if(readStatus!=null) readStatus.setText(!loaded ? R.string.sponsor_ui_checking : !visible() ? R.string.sponsor_ui_read_paused
				: timer.ready(now) ? R.string.sponsor_ui_read_done : R.string.sponsor_ui_read_hint);
		if(readProgress!=null) readProgress.setProgress((int)(timer.elapsedMillis(now)*1000/timer.requiredMillis()));
		// Window focus callbacks resume the timer; a completed/hidden page needs no polling loop.
		if(visible() && loaded && !validating && !timer.ready(now)) handler.postDelayed(tick, 200);
	}
	private void stopPending(){
		generation++; if(request!=null) request.cancel(); request=null; validating=false;
		timer.visible(false, SystemClock.elapsedRealtime()); handler.removeCallbacks(tick);
		if(buy!=null) buy.setEnabled(false);
	}
	private void refresh(boolean navigate){
		if(content==null || !resumed || !shown) return;
		if(!sessionValid()){ stopPending(); showFailure(R.string.sponsor_ui_session_changed); return; }
		if(validating && !navigate) return;
		if(request!=null) request.cancel();
		int token=++generation;
		validating=true; timer.visible(false, SystemClock.elapsedRealtime()); tick();
		request=SponsorRequest.catalog().setCallback(new Callback<>(){
			@Override public void onSuccess(Catalog result){
				if(token!=generation || !sessionValid() || content==null || getActivity()==null) return;
				request=null; validating=false;
				Plan selected=result.findPlan(planID);
				if(!Boolean.TRUE.equals(result.config.enabled) || !SponsorPurchaseSafety.valid(selected)){
					showFailure(R.string.sponsor_ui_plan_invalid); return;
				}
				String next=SponsorOperation.hash(MastodonAPIController.gson.toJson(result.config)+MastodonAPIController.gson.toJson(selected));
				boolean changed=!next.equals(fingerprint);
				boolean mayOpen=navigate && visible() && SponsorPurchaseSafety.canOpen(fingerprint, next, timer.ready(SystemClock.elapsedRealtime()));
				catalog=result; plan=selected; fingerprint=next; loaded=true;
				timer.configure(next, result.config.minimumReadSeconds, SystemClock.elapsedRealtime());
				if(changed) scrollPosition=0;
				if(changed || !rendered) render();
				if(mayOpen) showCheckoutQuiz();
				else if(changed && navigate) Toast.makeText(getActivity(), R.string.sponsor_ui_changed, Toast.LENGTH_LONG).show();
				updateVisibility();
			}
			@Override public void onError(ErrorResponse error){
				if(token!=generation || !sessionValid() || content==null || getActivity()==null) return;
				request=null; validating=false; error.showToast(getActivity()); showFailure(R.string.sponsor_ui_check_failed);
			}
		}).exec(accountID);
	}
	/** The quiz is a hard gate: launch only after a correct answer on the visible page. */
	private void showCheckoutQuiz(){
		if(!visible()) return;
		LinearLayout quiz=new LinearLayout(getActivity()); quiz.setOrientation(LinearLayout.VERTICAL);
		LinearLayout.LayoutParams lp;
		TextView note=new TextView(getActivity());
		note.setText(R.string.sponsor_ui_quiz_note);
		note.setTextAppearance(R.style.m3_body_medium);
		note.setTextColor(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurfaceVariant));
		note.setPadding(0, V.dp(2), 0, V.dp(8));
		quiz.addView(note, new LinearLayout.LayoutParams(-1, -2));
		TextView question=new TextView(getActivity());
		question.setText(R.string.sponsor_ui_quiz_question);
		question.setTextAppearance(R.style.m3_title_medium);
		question.setTextColor(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurface));
		question.setPadding(0, V.dp(8), 0, V.dp(14));
		quiz.addView(question, new LinearLayout.LayoutParams(-1, -2));
		String[] options=getResources().getStringArray(R.array.sponsor_quiz_options);
		final boolean[] answered={false};
		for(String option:options){
			boolean correct=getString(R.string.sponsor_quiz_answer).contentEquals(option);
			// 四个选项外观一致，不泄露答案；只有答对才跳转购买页
			Button choice=new Button(new android.view.ContextThemeWrapper(getActivity(), R.style.Widget_Mastodon_M3_Button_Tonal), null, 0);
			choice.setAllCaps(false);
			choice.setText(option);
			choice.setTag(correct);
			choice.setOnClickListener(v->{
				if(answered[0]) return;
				if(Boolean.TRUE.equals(v.getTag())){ answered[0]=true; Toast.makeText(getActivity(), R.string.sponsor_ui_quiz_correct, Toast.LENGTH_SHORT).show(); launchStore(); }
				else Toast.makeText(getActivity(), R.string.sponsor_ui_quiz_wrong, Toast.LENGTH_SHORT).show();
			});
			lp=new LinearLayout.LayoutParams(-1, -2); lp.topMargin=V.dp(6);
			quiz.addView(choice, lp);
		}
		ScrollView quizScroll=new ScrollView(getActivity()); quizScroll.addView(quiz);
		new org.joinmastodon.android.ui.M3AlertDialogBuilder(getActivity())
				.setTitle(R.string.sponsor_ui_quiz_title)
				.setView(quizScroll)
				.setCancelable(false)
				.setNegativeButton(R.string.sponsor_ui_cancel, null)
				.show();
	}
	private void launchStore(){
		if(plan==null || !visible()) return;
		// No account token, extras, implicit payment success, or automatic launch on resume.
		try{ startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(plan.purchaseUrl))); }
		catch(android.content.ActivityNotFoundException | SecurityException error){ Toast.makeText(getActivity(), R.string.sponsor_ui_no_browser, Toast.LENGTH_LONG).show(); }
		timer.restart(); timer.configure(fingerprint, catalog.config.minimumReadSeconds, SystemClock.elapsedRealtime());
	}
	private void showFailure(int message){
		loaded=false; rendered=false; timer.visible(false, SystemClock.elapsedRealtime());
		content.removeAllViews(); SponsorUi.text(content, getString(message), true);
		SponsorUi.secondary(content, getString(R.string.sponsor_ui_retry), ()->refresh(false));
		SponsorUi.textButton(content, getString(R.string.sponsor_ui_direct_redeem), this::openCenter);
		footer.setVisibility(View.GONE); tick();
	}
	private void openCenter(){
		if(getArguments().getBoolean("returnToCenter", false)){ Nav.finish(this); return; }
		Bundle args=new Bundle(); args.putString("account", accountID); Nav.go(getActivity(), SponsorCenterFragment.class, args);
	}
	private void render(){
		content.removeAllViews(); rendered=true; footer.setVisibility(View.VISIBLE);
		SponsorUi.headline(content, catalog.config.purchaseTitle);
		LinearLayout summary=SponsorUi.card(content);
		SponsorUi.text(summary, plan.name, true); SponsorUi.headline(summary, SponsorUi.price(plan));
		SponsorUi.label(summary, SponsorUi.duration(getActivity(), plan));
		int index=1;
		for(String step:catalog.config.purchaseSteps){
			LinearLayout card=SponsorUi.card(content);
			TextView number=SponsorUi.label(card, getString(R.string.sponsor_ui_step, index++));
			number.setTextColor(UiUtils.getThemeColor(getActivity(), R.attr.colorM3Primary));
			SponsorUi.text(card, step, false);
		}
		// 强调：付款成功后要点页面内的“私信”查询兑换码（宝宝粉高亮）
		LinearLayout pink=SponsorUi.card(content);
		pink.setBackground(SponsorUi.rounded(android.graphics.Color.parseColor("#F8D7E6"), 24));
		TextView pinkTitle=new TextView(getActivity());
		pinkTitle.setText(R.string.sponsor_ui_pink_title);
		pinkTitle.setTextAppearance(R.style.m3_title_medium);
		pinkTitle.setTextColor(android.graphics.Color.parseColor("#A63D6F"));
		pinkTitle.setPadding(0, V.dp(2), 0, V.dp(8));
		pink.addView(pinkTitle, new LinearLayout.LayoutParams(-1, -2));
		TextView hintBody=new TextView(getActivity());
		hintBody.setTextAppearance(R.style.m3_body_large);
		hintBody.setLineSpacing(V.dp(2), 1);
		String full=getString(R.string.sponsor_ui_pink_hint);
		String target=getString(R.string.sponsor_ui_pink_key);
		android.text.SpannableStringBuilder ssb=new android.text.SpannableStringBuilder(full);
		int start=full.indexOf(target);
		if(start>=0){
			// 宝宝粉泡泡底 + 深粉文字 + 加粗，保证昼夜主题均可读
			ssb.setSpan(new android.text.style.BackgroundColorSpan(android.graphics.Color.parseColor("#F2A6C5")), start, start+target.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			ssb.setSpan(new android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#7A1F4D")), start, start+target.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			ssb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, start+target.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		hintBody.setText(ssb);
		hintBody.setTextColor(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurface));
		hintBody.setPadding(0, V.dp(2), 0, V.dp(2));
		pink.addView(hintBody, new LinearLayout.LayoutParams(-1, -2));
		SponsorUi.text(content, getString(R.string.sponsor_ui_external, Uri.parse(plan.purchaseUrl).getHost()), false);
		SponsorUi.textButton(content, getString(R.string.sponsor_ui_direct_redeem), this::openCenter);
		ScrollView scrollTarget=scroll; int restoreY=scrollPosition;
		scrollTarget.post(()->{ if(scrollTarget==scroll) scroll.scrollTo(0, restoreY); });
	}
	@Override public void onDestroyView(){
		stopPending();
		if(scroll!=null){
			scrollPosition=scroll.getScrollY();
			if(scroll.getViewTreeObserver().isAlive()) scroll.getViewTreeObserver().removeOnWindowFocusChangeListener(focusListener);
		}
		content=null; scroll=null; buy=null; footer=null; readStatus=null; readProgress=null; rendered=false; loaded=false;
		super.onDestroyView();
	}
}
