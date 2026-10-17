package org.joinmastodon.android.fragments.sponsors;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toolbar;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.sponsors.SponsorRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.SponsorChangedEvent;
import org.joinmastodon.android.model.sponsors.SponsorModels.ThanksItem;
import org.joinmastodon.android.model.sponsors.SponsorModels.ThanksPage;
import org.joinmastodon.android.sponsors.SponsorThanksController;
import org.joinmastodon.android.sponsors.SponsorUi;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.ToolbarFragment;
import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.ImageLoaderCallback;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;

import java.util.HashSet;
import java.util.Set;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

/** Server-ranked, four-column thanks wall. Amounts are intentionally not shown. */
public class SponsorThanksFragment extends ToolbarFragment{
	private final Handler main=new Handler(Looper.getMainLooper());
	private String account;
	private AccountSession session;
	private SponsorThanksController controller;
	private RecyclerView grid;
	private ThanksAdapter adapter;
	private LinearLayout root, footer;
	private TextView status, centeredTitle;
	private Button retry, more;
	private boolean showing, resumed;
	private final Set<Holder> boundHolders=new HashSet<>();
	private int renderedRevision=-1;

	@Override public void onCreate(Bundle state){
		super.onCreate(state);
		account=getArguments()==null ? null : getArguments().getString("account");
		session=AccountSessionManager.getInstance().tryGetAccount(account);
		setTitle(R.string.sponsor_thanks_title);
		E.register(this);
	}
	private boolean sessionValid(){
		return session!=null && account!=null && account.equals(AccountSessionManager.getInstance().getLastActiveAccountID())
				&& AccountSessionManager.getInstance().tryGetAccount(account)==session;
	}
	@Override public View onCreateContentView(LayoutInflater inflater, ViewGroup parent, Bundle state){
		root=new LinearLayout(getActivity()); root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(SponsorUi.surface(getActivity()));
		grid=new RecyclerView(getActivity()); grid.setClipToPadding(false);
		grid.setPadding(V.dp(12), V.dp(28), V.dp(12), V.dp(12));
		GridLayoutManager layout=new GridLayoutManager(getActivity(), 4); grid.setLayoutManager(layout);
		adapter=new ThanksAdapter(); grid.setAdapter(adapter);
		root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
		LinearLayout actions=new LinearLayout(getActivity()); actions.setOrientation(LinearLayout.VERTICAL);
		root.addView(actions, new LinearLayout.LayoutParams(-1, -2));
		status=SponsorUi.label(actions, ""); status.setGravity(Gravity.CENTER);
		status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
		retry=SponsorUi.textButton(actions, getString(R.string.sponsor_thanks_retry), ()->controller.retry());
		more=SponsorUi.textButton(actions, getString(R.string.sponsor_thanks_more), ()->controller.more());
		footer=new LinearLayout(getActivity()); footer.setOrientation(LinearLayout.VERTICAL);
		footer.setPadding(V.dp(24), 0, V.dp(24), V.dp(12)); root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
		TextView explanation=SponsorUi.label(footer, getString(R.string.sponsor_thanks_sort));
		explanation.setGravity(Gravity.CENTER); explanation.setTextSize(11);
		Button support=SponsorUi.button(footer, getString(R.string.sponsor_thanks_support), this::openCenter);
		support.setTextSize(16); support.setMinHeight(V.dp(48));
		support.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_fluent_heart_24_regular, 0, 0, 0);
		support.setCompoundDrawableTintList(ColorStateList.valueOf(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnPrimary)));
		support.setCompoundDrawablePadding(V.dp(8));
		TextView voluntary=SponsorUi.label(footer, getString(R.string.sponsor_thanks_voluntary));
		voluntary.setGravity(Gravity.CENTER); voluntary.setTextSize(10);
		controller=new SponsorThanksController((offset, result)->{
			if(!showing || !topVisible()){ stop(); return ()->{}; }
			String fallback=getString(R.string.sponsor_thanks_error);
			var request=SponsorRequest.thanks(offset);
			request.setCallback(new Callback<ThanksPage>(){
				@Override public void onSuccess(ThanksPage page){ result.success(page); }
				@Override public void onError(ErrorResponse error){
					result.failure(error instanceof SponsorRequest.SponsorError e ? e.error : fallback);
				}
			}).exec(account);
			return request::cancel;
		}, new SponsorThanksController.Scheduler(){
			@Override public void postDelayed(Runnable task, long delay){ main.postDelayed(task, delay); }
			@Override public void remove(Runnable task){ main.removeCallbacks(task); }
		}, this::sessionValid, this::render);
		renderedRevision=-1; render();
		grid.addOnScrollListener(new RecyclerView.OnScrollListener(){
			@Override public void onScrolled(RecyclerView view, int dx, int dy){
				if(dy>0 && controller!=null && controller.error()==null && layout.findLastVisibleItemPosition()>=adapter.getItemCount()-12) controller.more();
			}
		});
		return root;
	}
	@Override protected boolean canGoBack(){ return true; }
	@Override public void onViewCreated(View view, Bundle state){
		super.onViewCreated(view, state); installTitle();
	}
	@Override public void onUpdateToolbar(){ super.onUpdateToolbar(); installTitle(); }
	private void installTitle(){
		Toolbar toolbar=getToolbar(); if(toolbar==null) return;
		if(centeredTitle==null || centeredTitle.getParent()!=toolbar){
			centeredTitle=new TextView(getActivity()); centeredTitle.setGravity(Gravity.CENTER);
			centeredTitle.setTextAppearance(R.style.m3_title_large);
			centeredTitle.setTextColor(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurface));
			toolbar.addView(centeredTitle, new Toolbar.LayoutParams(-2, -1, Gravity.CENTER));
		}
		centeredTitle.setText(R.string.sponsor_thanks_title); toolbar.setTitle("");
		toolbar.setBackgroundColor(SponsorUi.surface(getActivity()));
	}
	@Override public void onApplyWindowInsets(WindowInsets insets){
		if(root!=null) root.setPadding(insets.getSystemWindowInsetLeft(), 0, insets.getSystemWindowInsetRight(), 0);
		if(footer!=null) footer.setPadding(V.dp(24), 0, V.dp(24), V.dp(12)+insets.getSystemWindowInsetBottom());
		super.onApplyWindowInsets(insets.replaceSystemWindowInsets(0, insets.getSystemWindowInsetTop(), 0, 0));
	}
	@Override public void onResume(){
		resumed=true;
		super.onResume(); // AppKit may call onShown here; startIfVisible is deliberately idempotent.
		startIfVisible();
	}
	@Override public void onPause(){
		resumed=false;
		stop(); // Explicitly cancel before delegating, independent of AppKit's onHidden bridge.
		super.onPause();
	}
	@Override protected void onShown(){ super.onShown(); startIfVisible(); }
	private boolean topVisible(){
		if(!resumed || !isAdded() || isHidden() || getView()==null || getView().getVisibility()!=View.VISIBLE) return false;
		// AppKit adds the new container before hiding the old fragment at transition end.
		// The last non-removing fragment is therefore also authoritative during transitions.
		var fragments=getFragmentManager().getFragments();
		for(int i=fragments.size()-1;i>=0;i--){
			var fragment=fragments.get(i);
			if(fragment.isAdded() && !fragment.isRemoving() && !fragment.isHidden() && fragment.getView()!=null)
				return fragment==this;
		}
		return false;
	}
	private void startIfVisible(){
		if(showing || controller==null || !topVisible()) return;
		showing=true; controller.show(); render();
	}
	@Override protected void onHidden(){ stop(); super.onHidden(); }
	@Override public void onHiddenChanged(boolean hidden){
		super.onHiddenChanged(hidden);
		if(hidden) stop(); else startIfVisible();
	}
	private void stop(){
		showing=false;
		if(controller!=null) controller.hide();
		for(Holder holder:boundHolders) holder.cancelAvatar();
		if(grid!=null) grid.setAdapter(null);
		boundHolders.clear();
	}
	private void render(){
		if(controller==null || grid==null) return;
		if(showing && !topVisible()){ stop(); return; }
		if(showing && grid.getAdapter()==null) grid.setAdapter(adapter);
		if(renderedRevision!=controller.revision()){
			renderedRevision=controller.revision(); adapter.notifyDataSetChanged();
		}
		String error=controller.error();
		status.setText(error!=null ? error : controller.loading() ? getString(R.string.sponsor_thanks_loading)
				: controller.items().isEmpty() ? getString(R.string.sponsor_thanks_empty) : "");
		status.setVisibility(status.length()==0 ? View.GONE : View.VISIBLE);
		retry.setVisibility(error!=null || !controller.loading() && controller.items().isEmpty() ? View.VISIBLE : View.GONE);
		retry.setEnabled(!controller.loading());
		more.setVisibility(error==null && controller.hasMore() ? View.VISIBLE : View.GONE);
		more.setEnabled(!controller.loading());
	}
	@Subscribe public void onSponsorChanged(SponsorChangedEvent event){
		if(account!=null && account.equals(event.accountID) && controller!=null) controller.sponsorChanged();
	}
	private void openCenter(){
		if(!sessionValid() || !showing || !topVisible()) return;
		Bundle args=new Bundle(); args.putString("account", account);
		stop(); // Do not keep polling/images alive during the center's enter transition.
		Nav.go(getActivity(), SponsorCenterFragment.class, args);
	}
	@Override public void onDestroyView(){
		stop(); if(controller!=null) controller.destroy(); controller=null;
		main.removeCallbacksAndMessages(null);
		grid=null; adapter=null; root=null; footer=null; status=null; retry=null; more=null; centeredTitle=null;
		super.onDestroyView();
	}
	@Override public void onDestroy(){ E.unregister(this); super.onDestroy(); }

	private final class ThanksAdapter extends RecyclerView.Adapter<Holder>{
		@Override public int getItemCount(){ return controller==null ? 0 : controller.items().size(); }
		@Override public Holder onCreateViewHolder(ViewGroup parent, int type){ return new Holder(parent); }
		@Override public void onBindViewHolder(Holder holder, int position){
			boundHolders.add(holder); holder.bind(controller.items().get(position));
		}
		@Override public void onViewRecycled(Holder holder){ holder.cancelAvatar(); boundHolders.remove(holder); }
	}
	private static final class Holder extends RecyclerView.ViewHolder{
		private final ImageView avatar;
		private final TextView name, rank;
		private final FrameLayout portrait;
		private long binding;
		private ImageCache.PendingImageRequest avatarRequest;
		Holder(ViewGroup parent){
			super(new LinearLayout(parent.getContext()));
			LinearLayout cell=(LinearLayout)itemView; cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER_HORIZONTAL);
			cell.setPadding(V.dp(4), 0, V.dp(4), V.dp(20));
			cell.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
			portrait=new FrameLayout(parent.getContext()){
				@Override protected void onMeasure(int widthSpec, int heightSpec){
					int size=Math.min(V.dp(56), View.MeasureSpec.getSize(widthSpec));
					super.onMeasure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
							View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
				}
			};
			// Resolve against the actual cell width, including split windows.
			cell.addView(portrait, new LinearLayout.LayoutParams(-1, V.dp(56)));
			avatar=new ImageView(parent.getContext()); avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
			avatar.setOutlineProvider(OutlineProviders.OVAL); avatar.setClipToOutline(true);
			FrameLayout.LayoutParams imageParams=new FrameLayout.LayoutParams(-1, -1); imageParams.setMargins(V.dp(2), V.dp(2), V.dp(2), V.dp(2));
			portrait.addView(avatar, imageParams);
			// One listener per holder, never one per bind. Explicit cancellation also covers unattached/cached holders.
			avatar.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
				@Override public void onViewAttachedToWindow(View view){}
				@Override public void onViewDetachedFromWindow(View view){ cancelAvatar(); }
			});
			rank=new TextView(parent.getContext()); rank.setTextSize(10); rank.setGravity(Gravity.CENTER); rank.setTextColor(Color.WHITE);
			rank.setMinWidth(V.dp(22)); rank.setPadding(V.dp(4), 0, V.dp(4), 0);
			portrait.addView(rank, new FrameLayout.LayoutParams(-2, V.dp(20), Gravity.BOTTOM|Gravity.END));
			name=new TextView(parent.getContext()); name.setTextSize(12); name.setTextColor(UiUtils.getThemeColor(parent.getContext(), R.attr.colorM3OnSurfaceVariant));
			name.setGravity(Gravity.CENTER); name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END);
			LinearLayout.LayoutParams textParams=new LinearLayout.LayoutParams(-1, -2); textParams.topMargin=V.dp(10); cell.addView(name, textParams);
			cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
			avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
			name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); rank.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		}
		void cancelAvatar(){
			binding++;
			if(avatarRequest!=null){ avatarRequest.cancel(); avatarRequest=null; }
		}
		void bind(ThanksItem item){
			cancelAvatar();
			long token=binding;
			int medal=switch(item.rank){ case 1 -> 0xFFB88A20; case 2 -> 0xFF7C899E; case 3 -> 0xFFB17C54;
				default -> UiUtils.getThemeColor(itemView.getContext(), R.attr.colorM3Primary); };
			GradientDrawable border=new GradientDrawable(); border.setShape(GradientDrawable.OVAL); border.setColor(Color.TRANSPARENT);
			if(item.rank<=3) border.setStroke(V.dp(1.5f), medal);
			portrait.setBackground(border);
			GradientDrawable badge=SponsorUi.rounded(medal, 6); badge.setStroke(V.dp(1), SponsorUi.surface(itemView.getContext())); rank.setBackground(badge);
			rank.setText(String.valueOf(item.rank)); name.setText(item.name());
			itemView.setContentDescription(itemView.getContext().getString(R.string.sponsor_thanks_accessibility, item.rank, item.name()));
			avatar.setImageResource(R.drawable.default_avatar);
			String url=item.avatarStatic.isBlank() ? item.avatar : item.avatarStatic;
			if(!url.isBlank()) avatarRequest=ImageCache.getInstance(avatar.getContext()).get(
					new UrlImageLoaderRequest(url, V.dp(56), V.dp(56)), null, new ImageLoaderCallback(){
				@Override public void onImageLoaded(ImageLoaderRequest request, android.graphics.drawable.Drawable image){
					// ImageCache callbacks may arrive off-main or after cancel. Binding owns both the request and UI post.
					new Handler(Looper.getMainLooper()).post(()->{
						if(binding==token){ avatarRequest=null; avatar.setImageDrawable(image); }
					});
				}
			}, true);
		}
	}
}
