package org.joinmastodon.android.ui.displayitems;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.ComposeFragment;
import org.joinmastodon.android.fragments.account_list.StatusFavoritesListFragment;
import org.joinmastodon.android.fragments.account_list.StatusReblogsListFragment;
import org.joinmastodon.android.fragments.account_list.StatusRelatedAccountListFragment;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.QuoteApproval;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.model.viewmodel.ListItem;
import org.joinmastodon.android.ui.sheets.ListItemsSheet;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.parceler.Parcels;

import java.util.function.Consumer;

import me.grishka.appkit.Nav;
import me.grishka.appkit.utils.V;

public class FooterStatusDisplayItem extends StatusDisplayItem{
	public final Status status;
	private final String accountID;
	public boolean hideCounts;

	public FooterStatusDisplayItem(String parentID, Callbacks callbacks, Context context, Status status, String accountID){
		super(parentID, callbacks, context);
		this.status=status;
		this.accountID=accountID;
	}

	@Override
	public Type getType(){
		return Type.FOOTER;
	}

	public static class Holder extends StatusDisplayItem.Holder<FooterStatusDisplayItem>{
		private final TextView reply, boost, favorite;
		private final ImageView share;
		private ColorStateList defaultColors;
		private ColorStateList favoriteColors;
		private final View replyBtn, boostBtn, favoriteBtn, shareBtn /* MOSHIDON */, bookmarkBtn;
		private final PopupMenu boostLongTapMenu, favoriteLongTapMenu;
		private final View spacer1, spacer2 /* MOSHIDON */ ,spacer3;

		private final View.AccessibilityDelegate buttonAccessibilityDelegate=new View.AccessibilityDelegate(){
			@Override
			public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info){
				super.onInitializeAccessibilityNodeInfo(host, info);
				info.setClassName(Button.class.getName());
				info.setText(item.context.getString(descriptionForId(host.getId())));
			}
		};

		public Holder(Activity activity, ViewGroup parent){
			super(activity, R.layout.display_item_footer, parent);
			reply=findViewById(R.id.reply);
			boost=findViewById(R.id.boost);
			favorite=findViewById(R.id.favorite);
			share=findViewById(R.id.share);
			spacer1=findViewById(R.id.spacer1);
			spacer2=findViewById(R.id.spacer2);

			// MOSHIDON:
			bookmarkBtn=findViewById(R.id.bookmark_btn);
			spacer3=findViewById(R.id.spacer3);

			float[] hsb={0, 0, 0};
			Color.colorToHSV(UiUtils.getThemeColor(activity, R.attr.colorM3Primary), hsb);
			hsb[1]+=0.1f;
			hsb[2]+=0.16f;

			// 默认按钮颜色
			ColorStateList defaultColors=new ColorStateList(new int[][]{
					{android.R.attr.state_selected},
					{android.R.attr.state_enabled},
					{}
			}, new int[]{
					Color.HSVToColor(hsb),
					UiUtils.getThemeColor(activity, R.attr.colorM3Outline),
					UiUtils.getThemeColor(activity, R.attr.colorM3Outline) & 0x80FFFFFF
			});

			// 点赞按钮：点亮后变宝宝粉
			int babyPink=0xFFFF69B4;
			ColorStateList favoriteColors=new ColorStateList(new int[][]{
					{android.R.attr.state_selected},
					{android.R.attr.state_enabled},
					{}
			}, new int[]{
					babyPink,
					UiUtils.getThemeColor(activity, R.attr.colorM3Outline),
					UiUtils.getThemeColor(activity, R.attr.colorM3Outline) & 0x80FFFFFF
			});

			boost.setTextColor(defaultColors);
			boost.setCompoundDrawableTintList(defaultColors);
			favorite.setTextColor(favoriteColors);
			favorite.setCompoundDrawableTintList(favoriteColors);

			if(Build.VERSION.SDK_INT<Build.VERSION_CODES.N){
				UiUtils.fixCompoundDrawableTintOnAndroid6(reply);
				UiUtils.fixCompoundDrawableTintOnAndroid6(boost);
				UiUtils.fixCompoundDrawableTintOnAndroid6(favorite);
			}
			replyBtn=findViewById(R.id.reply_btn);
			// MOSHIDON:
			replyBtn.setOnLongClickListener(this::onReplyLongClick);
			boostBtn=findViewById(R.id.boost_btn);
			favoriteBtn=findViewById(R.id.favorite_btn);
			shareBtn=findViewById(R.id.share_btn);
			replyBtn.setOnClickListener(this::onReplyClick);
			replyBtn.setAccessibilityDelegate(buttonAccessibilityDelegate);
			boostBtn.setOnClickListener(this::onBoostClick);
			boostBtn.setOnLongClickListener(this::onBoostLongClick);
			boostBtn.setAccessibilityDelegate(buttonAccessibilityDelegate);
			favoriteBtn.setOnClickListener(this::onFavoriteClick);
			favoriteBtn.setOnLongClickListener(this::onFavoriteLongClick);
			favoriteBtn.setAccessibilityDelegate(buttonAccessibilityDelegate);
			shareBtn.setOnClickListener(this::onShareClick);
			shareBtn.setAccessibilityDelegate(buttonAccessibilityDelegate);

			// MOSHIDON:
			// bookmarkBtn.setOnTouchListener(this::onButtonTouch);
			bookmarkBtn.setOnClickListener(this::onBookmarkClick);
			bookmarkBtn.setOnLongClickListener(this::onBookmarkLongClick);
			bookmarkBtn.setAccessibilityDelegate(buttonAccessibilityDelegate);

			favoriteLongTapMenu=new PopupMenu(activity, favoriteBtn);
			favoriteLongTapMenu.inflate(R.menu.favorite_longtap);
			favoriteLongTapMenu.setOnMenuItemClickListener(this::onLongTapMenuItemSelected);
			boostLongTapMenu=new PopupMenu(activity, boostBtn);
			boostLongTapMenu.inflate(R.menu.boost_longtap);
			boostLongTapMenu.setOnMenuItemClickListener(this::onLongTapMenuItemSelected);
		}

		@Override
		public void onBind(FooterStatusDisplayItem item){
			spacer1.setVisibility(item.fullWidth ? View.VISIBLE : View.GONE);
			spacer2.setVisibility(item.fullWidth ? View.VISIBLE : View.GONE);

			// MOSHIDON
			spacer3.setVisibility(item.fullWidth ? View.VISIBLE : View.GONE);

			itemView.setPaddingRelative(V.dp(item.fullWidth ? 8 : 56), itemView.getPaddingTop(), itemView.getPaddingEnd(), itemView.getPaddingBottom());
			bindButton(reply, item.status.repliesCount);
			bindButton(boost, item.status.reblogsCount);
			bindButton(favorite, item.status.favouritesCount);
			boostBtn.setSelected(item.status.reblogged);
			favoriteBtn.setSelected(item.status.favourited);
			boolean isOwn=item.status.account.id.equals(AccountSessionManager.getInstance().getAccount(item.accountID).self.id);
			boostBtn.setEnabled(item.status.visibility==StatusPrivacy.PUBLIC || item.status.visibility==StatusPrivacy.UNLISTED
					|| (item.status.visibility==StatusPrivacy.PRIVATE && isOwn));

			// MOSHIDON:
			bookmarkBtn.setSelected(item.status.bookmarked);

			Drawable d=itemView.getResources().getDrawable(switch(item.status.visibility){
				case PUBLIC, UNLISTED, LOCAL -> R.drawable.ic_boost;
				case PRIVATE -> isOwn ? R.drawable.ic_boost_private : R.drawable.ic_boost_disabled_24px;
				case DIRECT -> R.drawable.ic_boost_disabled_24px;
			}, itemView.getContext().getTheme());
			d.setBounds(0, 0, V.dp(20), V.dp(20));
			boost.setCompoundDrawablesRelative(d, null, null, null);
		}

		private void bindButton(TextView btn, long count){
			if(count>0 && !item.hideCounts){
				btn.setText(UiUtils.abbreviateNumber(count));
				btn.setCompoundDrawablePadding(V.dp(6));
			}else{
				btn.setText("");
				btn.setCompoundDrawablePadding(0);
			}
		}

		private void onReplyClick(View v){
			item.callbacks.maybeShowPreReplySheet(item.status, ()->{
				Bundle args=new Bundle();
				args.putString("account", item.accountID);
				args.putParcelable("replyTo", Parcels.wrap(item.status));
				Nav.go((Activity) item.context, ComposeFragment.class, args);
			});
		}

		private void onBoostClick(View v){
			Instance instance=AccountSessionManager.get(item.accountID).getInstanceInfo();
			if(instance.supportsQuotePostAuthoring()){
				ListItemsSheet sheet=new ListItemsSheet(itemView.getContext());
				sheet.add(new ListItem<>(item.status.reblogged ? R.string.undo_reblog : R.string.button_reblog, 0, R.drawable.ic_repeat_24px, o->{
					doBoost();
					sheet.dismiss();
				}));
				if(item.status.quoteApproval==null || item.status.quoteApproval.currentUser==QuoteApproval.CurrentUserPolicy.UNKNOWN || item.status.quoteApproval.currentUser==QuoteApproval.CurrentUserPolicy.DENIED){
					sheet.add(new ListItem<>(R.string.create_quote,
							item.status.quoteApproval!=null && item.status.quoteApproval.automatic.contains(QuoteApproval.Policy.FOLLOWERS) ? R.string.cannot_quote_post_followers_only : R.string.cannot_quote_post,
							R.drawable.ic_format_quote_off_fill1_24px, null));
				}else{
					sheet.add(new ListItem<>(item.status.quoteApproval.currentUser==QuoteApproval.CurrentUserPolicy.MANUAL ? R.string.create_quote_manual_approval : R.string.create_quote,
							item.status.quoteApproval.currentUser==QuoteApproval.CurrentUserPolicy.MANUAL ? R.string.create_quote_manual_approval_subtitle : 0,
							R.drawable.ic_format_quote_fill1_24px, o->{
						sheet.dismiss();
						Bundle args=new Bundle();
						args.putString("account", item.accountID);
						args.putParcelable("quote", Parcels.wrap(item.status));
						Nav.go((Activity) item.context, ComposeFragment.class, args);
					}));
				}
				sheet.show();
			}else{
				if(GlobalUserPreferences.confirmBoost){
					PopupMenu menu=new PopupMenu(itemView.getContext(), boost);
					menu.getMenu().add(R.string.button_reblog);
					menu.setOnMenuItemClickListener(item->{
						doBoost();
						return true;
					});
					menu.show();
				}else{
					doBoost();
				}
			}
		}

		private void doBoost(){
			AccountSessionManager.getInstance().getAccount(item.accountID).getStatusInteractionController().setReblogged(item.status, !item.status.reblogged, StatusPrivacy.PUBLIC, r->{});
			boost.setSelected(item.status.reblogged);
			bindButton(boost, item.status.reblogsCount);
		}

		private void onFavoriteClick(View v){
			AccountSessionManager.getInstance().getAccount(item.accountID).getStatusInteractionController().setFavorited(item.status, !item.status.favourited);
			favorite.setSelected(item.status.favourited);
			bindButton(favorite, item.status.favouritesCount);
		}

		private void onShareClick(View v){
			UiUtils.openSystemShareSheet(v.getContext(), item.status);
		}

		private void boostConsumer(View v, Status r) {
			UiUtils.opacityIn(v);
			bindButton(boost, r.reblogsCount);
		}

// 		private boolean onBoostLongClick(View v){
// 			MenuItem boost=boostLongTapMenu.getMenu().findItem(R.id.boost);
// 			boost.setTitle(item.status.reblogged ? R.string.undo_reblog : R.string.button_reblog);
// 			boostLongTapMenu.show();
// 			return true;
// 		}

		// MOSHIDON:
		private boolean onReplyLongClick(View v) {
			if(item.status.preview) return false;
			if (AccountSessionManager.getInstance().getLoggedInAccounts().size() < 2) return false;
			UiUtils.pickAccount(v.getContext(), item.accountID, R.string.sk_reply_as, R.drawable.ic_fluent_arrow_reply_28_regular, session -> {
				String accountID = session.getID();
				UiUtils.lookupStatus(v.getContext(), item.status, accountID, item.accountID, status -> {
					if (status == null) return;
					openComposeView(status, accountID);
				});
			}, null);
			return true;
		}

		private void onBookmarkClick(View v){
			if(item.status.preview) return;
			applyInteraction(v,
					status -> {
						if(status == null)
							return;
						bookmarkBtn.setSelected(!status.bookmarked);
						// vibrateForAction(bookmark, !status.bookmarked);
						AccountSessionManager.getInstance().getAccount(item.accountID).getStatusInteractionController().setBookmarked(status, !status.bookmarked
						//		, r->{
						//	UiUtils.opacityIn(v);
						//}
						);
					});
		}

		private boolean onBookmarkLongClick(View v) {
			if(item.status.preview) return false;
			if (AccountSessionManager.getInstance().getLoggedInAccounts().size() < 2) return false;
			UiUtils.pickInteractAs(v.getContext(),
					item.accountID, item.status,
					s -> s.bookmarked,
					(ic, status, consumer) -> ic.setBookmarked(status, true /*, consumer */),
					R.string.sk_bookmark_as,
					R.string.sk_bookmarked_as,
					R.string.sk_already_bookmarked,
					R.drawable.ic_fluent_bookmark_28_regular
			);
			return true;
		}


		// MOSHIDON:
		private void openComposeView(Status status, String accountID) {
			item.callbacks.maybeShowPreReplySheet(status, () ->{
				Bundle args=new Bundle();
				args.putString("account", accountID);
				args.putParcelable("replyTo", Parcels.wrap(status));
				Nav.go((Activity) item.context, ComposeFragment.class, args);
			});
		}

		private boolean onBoostLongClick(View v){
			if(item.status.preview) return false;
			Context ctx = itemView.getContext();
			View menu = LayoutInflater.from(ctx).inflate(R.layout.item_boost_menu, null);
			Dialog dialog = new M3AlertDialogBuilder(ctx).setView(menu).create();
			AccountSession session = AccountSessionManager.getInstance().getAccount(item.accountID);

			Consumer<StatusPrivacy> doReblog = (visibility) -> {
				UiUtils.opacityOut(v);
				applyInteraction(v,status -> {
					session.getStatusInteractionController()
							.setReblogged(status, !status.reblogged, visibility, r->boostConsumer(v, r));
					boost.setSelected(status.reblogged);
					dialog.dismiss();
				});
			};

			View separator = menu.findViewById(R.id.separator);
			TextView reblogHeader = menu.findViewById(R.id.reblog_header);
			TextView undoReblog = menu.findViewById(R.id.delete_reblog);
			TextView reblogAs = menu.findViewById(R.id.reblog_as);
			TextView itemPublic = menu.findViewById(R.id.vis_public);
			TextView itemUnlisted = menu.findViewById(R.id.vis_unlisted);
			TextView itemFollowers = menu.findViewById(R.id.vis_followers);

			undoReblog.setVisibility(item.status.reblogged ? View.VISIBLE : View.GONE);
			separator.setVisibility(item.status.reblogged ? View.GONE : View.VISIBLE);
			reblogHeader.setVisibility(item.status.reblogged ? View.GONE : View.VISIBLE);
			reblogAs.setVisibility(AccountSessionManager.getInstance().getLoggedInAccounts().size() > 1 ? View.VISIBLE : View.GONE);

			itemPublic.setVisibility(item.status.reblogged ? View.GONE : View.VISIBLE);
			itemUnlisted.setVisibility(item.status.reblogged ? View.GONE : View.VISIBLE);
			itemFollowers.setVisibility(item.status.reblogged ? View.GONE : View.VISIBLE);

			Drawable checkMark = ctx.getDrawable(R.drawable.ic_fluent_checkmark_circle_20_regular);
			Drawable publicDrawable = ctx.getDrawable(R.drawable.ic_fluent_earth_24_regular);
			Drawable unlistedDrawable = ctx.getDrawable(R.drawable.ic_fluent_lock_open_24_regular);
			Drawable followersDrawable = ctx.getDrawable(R.drawable.ic_fluent_lock_closed_24_regular);

			StatusPrivacy defaultVisibility = session.preferences != null ? session.preferences.postingDefaultVisibility : null;
			itemPublic.setCompoundDrawablesWithIntrinsicBounds(publicDrawable, null, StatusPrivacy.PUBLIC.equals(defaultVisibility) ? checkMark : null, null);
			itemUnlisted.setCompoundDrawablesWithIntrinsicBounds(unlistedDrawable, null, StatusPrivacy.UNLISTED.equals(defaultVisibility) ? checkMark : null, null);
			itemFollowers.setCompoundDrawablesWithIntrinsicBounds(followersDrawable, null, StatusPrivacy.PRIVATE.equals(defaultVisibility) ? checkMark : null, null);

			undoReblog.setOnClickListener(c->doReblog.accept(null));
			itemPublic.setOnClickListener(c->doReblog.accept(StatusPrivacy.PUBLIC));
			itemUnlisted.setOnClickListener(c->doReblog.accept(StatusPrivacy.UNLISTED));
			itemFollowers.setOnClickListener(c->doReblog.accept(StatusPrivacy.PRIVATE));
			reblogAs.setOnClickListener(c->{
				dialog.dismiss();
				UiUtils.pickInteractAs(v.getContext(),
						item.accountID, item.status,
						s -> s.reblogged,
						(ic, status, consumer) ->
						{
							Instance instance=AccountSessionManager.get(item.accountID).getInstanceInfo();
							if(instance.supportsQuotePostAuthoring()){
								ListItemsSheet sheet=new ListItemsSheet(itemView.getContext());
								sheet.add(new ListItem<>(status.reblogged ? R.string.undo_reblog : R.string.button_reblog, 0, R.drawable.ic_repeat_24px, o->{
									ic.setReblogged(status, true, null, consumer);
								}));
								if(status.quoteApproval==null || status.quoteApproval.currentUser==QuoteApproval.CurrentUserPolicy.UNKNOWN || status.quoteApproval.currentUser==QuoteApproval.CurrentUserPolicy.DENIED){
									sheet.add(new ListItem<>(R.string.create_quote,
											item.status.quoteApproval!=null && item.status.quoteApproval.automatic.contains(QuoteApproval.Policy.FOLLOWERS) ? R.string.cannot_quote_post_followers_only : R.string.cannot_quote_post,
											R.drawable.ic_format_quote_off_fill1_24px, null));
								}else{
									sheet.add(new ListItem<>(item.status.quoteApproval.currentUser==QuoteApproval.CurrentUserPolicy.MANUAL ? R.string.create_quote_manual_approval : R.string.create_quote,
											item.status.quoteApproval.currentUser==QuoteApproval.CurrentUserPolicy.MANUAL ? R.string.create_quote_manual_approval_subtitle : 0,
											R.drawable.ic_format_quote_fill1_24px, o->{
										sheet.dismiss();
										Bundle args=new Bundle();
										args.putString("account", ic.getAccountID());
										args.putParcelable("quote", Parcels.wrap(status));
										Nav.go((Activity) item.context, ComposeFragment.class, args);
									}));
								}
								sheet.show();
							}else{
								doBoost();
							}
						}, R.string.sk_reblog_as,
						R.string.sk_reblogged_as,
						R.string.sk_already_reblogged,
						// TODO: replace once available: https://raw.githubusercontent.com/microsoft/fluentui-system-icons/main/android/library/src/main/res/drawable/ic_fluent_arrow_repeat_all_28_regular.xml
						R.drawable.ic_fluent_arrow_repeat_all_24_regular
				);
			});

			menu.findViewById(R.id.quote).setOnClickListener(c->{
				dialog.dismiss();
				UiUtils.opacityIn(v);
				Bundle args=new Bundle();
				args.putString("account", item.accountID);
				Instance instance=AccountSessionManager.get(item.accountID).getInstance().get();
				if(instance.isAkkoma() || instance.isIceshrimp() || (instance.supportsQuotePostAuthoring() && !(item.status.quoteApproval==null || item.status.quoteApproval.currentUser==QuoteApproval.CurrentUserPolicy.UNKNOWN || item.status.quoteApproval.currentUser==QuoteApproval.CurrentUserPolicy.DENIED))){
					args.putParcelable("quote", Parcels.wrap(item.status));
				}else{
					StringBuilder prefilledText = new StringBuilder().append("\n\n");
					String ownID = AccountSessionManager.getInstance().getAccount(item.accountID).self.id;
					if (!item.status.account.id.equals(ownID)) prefilledText.append('@').append(item.status.account.acct).append(' ');
					prefilledText.append(item.status.url);
					args.putString("prefilledText", prefilledText.toString());
					args.putInt("selectionStart", 0);
				}
				Nav.go((Activity) item.context, ComposeFragment.class, args);
			});

			dialog.show();
			return true;
		}

		private void applyInteraction(View v, Consumer<Status> interactionConsumer) {
			if(!item.status.isRemote){
				interactionConsumer.accept(item.status);
				return;
			}
			UiUtils.lookupStatus(v.getContext(),
					item.status, item.accountID, null,
					interactionConsumer
			);
		}

		private boolean onFavoriteLongClick(View v) {
			if(item.status.preview) return false;
			if (AccountSessionManager.getInstance().getLoggedInAccounts().size() < 2) return false;
			UiUtils.pickInteractAs(v.getContext(),
					item.accountID, item.status,
					s -> s.favourited,
					(ic, status, consumer) -> ic.setFavorited(status, true, consumer),
					R.string.sk_favorite_as,
					R.string.sk_favorited_as,
					R.string.sk_already_favorited,
					GlobalUserPreferences.likeIcon ? R.drawable.ic_fluent_heart_28_regular : R.drawable.ic_fluent_heart_28_regular
			);
			return true;
		}

		private boolean onLongTapMenuItemSelected(MenuItem item){
			int id=item.getItemId();
			if(id==R.id.favorite){
				onFavoriteClick(null);
			}else if(id==R.id.boost){
				onBoostClick(null);
			}else if(id==R.id.bookmark){
				AccountSessionManager.getInstance().getAccount(this.item.accountID).getStatusInteractionController().setBookmarked(this.item.status, !this.item.status.bookmarked);
			}else if(id==R.id.view_favorites){
				startAccountListFragment(StatusFavoritesListFragment.class);
			}else if(id==R.id.view_boosts){
				startAccountListFragment(StatusReblogsListFragment.class);
			}
			return true;
		}

		private void startAccountListFragment(Class<? extends StatusRelatedAccountListFragment> cls){
			Bundle args=new Bundle();
			args.putString("account", item.accountID);
			args.putParcelable("status", Parcels.wrap(item.status));
			Nav.go((Activity) item.context, cls, args);
		}

		private int descriptionForId(int id){
			if(id==R.id.reply_btn)
				return R.string.button_reply;
			if(id==R.id.boost_btn)
				return R.string.button_reblog;
			if(id==R.id.favorite_btn)
				return R.string.button_favorite;
			if(id==R.id.share_btn)
				return R.string.button_share;
			if(id==R.id.bookmark_btn)
				return R.string.button_bookmark;
			return 0;
		}
	}
}
