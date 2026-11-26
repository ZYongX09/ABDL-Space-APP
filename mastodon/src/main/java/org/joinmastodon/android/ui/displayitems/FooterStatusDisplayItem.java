package org.joinmastodon.android.ui.displayitems;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.ComposeFragment;
import org.joinmastodon.android.fragments.ThreadFragment;
import org.joinmastodon.android.model.Status;
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
		private final TextView reply, favorite, bookmarkText;
		private final View replyBtn, favoriteBtn, bookmarkBtn, shareBtn;
		private final View spacer1, spacer2;

		private final View.AccessibilityDelegate buttonAccessibilityDelegate=new View.AccessibilityDelegate(){
			@Override
			public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info){
				super.onInitializeAccessibilityNodeInfo(host, info);
				info.setClassName(Button.class.getName());
				if(host.getId()!=R.id.share_btn)
					info.setText(host.getContext().getString(descriptionForId(host.getId())));
			}
		};

		public Holder(Activity activity, ViewGroup parent){
			super(activity, R.layout.display_item_footer, parent);
			reply=findViewById(R.id.reply);
			favorite=findViewById(R.id.favorite);
			bookmarkText=findViewById(R.id.bookmark);
			spacer1=findViewById(R.id.spacer1);
			spacer2=findViewById(R.id.spacer2);

			// 点赞按钮：点亮后变宝宝粉
			int babyPink=0xFFFF69B4;
			int outline=UiUtils.getThemeColor(activity, R.attr.colorM3Outline);
			ColorStateList favoriteColors=new ColorStateList(new int[][]{
					{android.R.attr.state_selected},
					{android.R.attr.state_enabled},
					{}
			}, new int[]{babyPink, outline, outline & 0x80FFFFFF});
			favorite.setTextColor(favoriteColors);
			favorite.setCompoundDrawableTintList(favoriteColors);

			if(Build.VERSION.SDK_INT<Build.VERSION_CODES.N){
				UiUtils.fixCompoundDrawableTintOnAndroid6(reply);
				UiUtils.fixCompoundDrawableTintOnAndroid6(favorite);
			}
			replyBtn=findViewById(R.id.reply_btn);
			favoriteBtn=findViewById(R.id.favorite_btn);
			bookmarkBtn=findViewById(R.id.bookmark_btn);
			shareBtn=findViewById(R.id.share_btn);
			replyBtn.setOnClickListener(this::onReplyClick);
			replyBtn.setOnLongClickListener(this::onReplyLongClick);
			replyBtn.setAccessibilityDelegate(buttonAccessibilityDelegate);
			favoriteBtn.setOnClickListener(this::onFavoriteClick);
			favoriteBtn.setOnLongClickListener(this::onFavoriteLongClick);
			favoriteBtn.setAccessibilityDelegate(buttonAccessibilityDelegate);
			bookmarkBtn.setOnClickListener(this::onBookmarkClick);
			bookmarkBtn.setOnLongClickListener(this::onBookmarkLongClick);
			bookmarkBtn.setAccessibilityDelegate(buttonAccessibilityDelegate);
			shareBtn.setOnClickListener(this::onShareClick);
			shareBtn.setAccessibilityDelegate(buttonAccessibilityDelegate);
		}

		@Override
		public void onBind(FooterStatusDisplayItem item){
			spacer1.setVisibility(item.fullWidth ? View.VISIBLE : View.GONE);
			spacer2.setVisibility(item.fullWidth ? View.VISIBLE : View.GONE);
			itemView.setPaddingRelative(V.dp(item.fullWidth ? 8 : 56), itemView.getPaddingTop(), itemView.getPaddingEnd(), itemView.getPaddingBottom());
			bindButton(reply, item.status.repliesCount);
			bindButton(favorite, item.status.favouritesCount);
			bindButton(bookmarkText, item.status.bookmarksCount);
			favoriteBtn.setSelected(item.status.favourited);
			bookmarkBtn.setSelected(item.status.bookmarked);

			boolean remoteOnly=item.status.id!=null && item.status.id.startsWith("nbw_");
			var capability=remoteOnly ? AccountSessionManager.get(item.accountID).getNBWForumOptionsRepository().getSnapshot() : null;
			boolean remoteWrites=capability==null || capability.canWrite();
			replyBtn.setEnabled(capability==null || capability.canRead());
			favoriteBtn.setEnabled(remoteWrites);
			bookmarkBtn.setEnabled(remoteWrites);
		}

		private void bindButton(TextView btn, long count){
			if(!item.hideCounts){
				btn.setText(UiUtils.abbreviateNumber(count));
				btn.setCompoundDrawablePadding(V.dp(6));
			}else{
				btn.setText("");
				btn.setCompoundDrawablePadding(0);
			}
		}

		private void onReplyClick(View v){
			// 跳转到帖子详情页（而非回复页面）
			Bundle args=new Bundle();
			args.putString("account", item.accountID);
			args.putParcelable("status", Parcels.wrap(item.status));
			Nav.go((Activity) item.context, ThreadFragment.class, args);
		}

		private void onFavoriteClick(View v){
			AccountSessionManager.getInstance().getAccount(item.accountID).getStatusInteractionController().setFavorited(item.status, !item.status.favourited, r->{}, item.context);
			favorite.setSelected(item.status.favourited);
			bindButton(favorite, item.status.favouritesCount);
		}

		private void onShareClick(View v){
			UiUtils.openSystemShareSheet(v.getContext(), item.status);
		}

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
						// Selection follows the controller, including unsupported replies and rollbacks.
						AccountSessionManager.getInstance().getAccount(item.accountID).getStatusInteractionController().setBookmarked(status, !status.bookmarked, item.context);
						bookmarkBtn.setSelected(status.bookmarked);
						bindButton(bookmarkText, status.bookmarksCount);
					});
		}

		private boolean onBookmarkLongClick(View v) {
			if(item.status.preview) return false;
			if (AccountSessionManager.getInstance().getLoggedInAccounts().size() < 2) return false;
			UiUtils.pickInteractAs(v.getContext(),
					item.accountID, item.status,
					s -> s.bookmarked,
					(ic, status, consumer) -> ic.setBookmarked(status, true, item.context),
					R.string.sk_bookmark_as,
					R.string.sk_bookmarked_as,
					R.string.sk_already_bookmarked,
					R.drawable.ic_fluent_bookmark_28_regular
			);
			return true;
		}

		private void openComposeView(Status status, String accountID) {
			item.callbacks.maybeShowPreReplySheet(status, () ->{
				Bundle args=new Bundle();
				args.putString("account", accountID);
				args.putParcelable("replyTo", Parcels.wrap(status));
				Nav.go((Activity) item.context, ComposeFragment.class, args);
			});
		}

		private void applyInteraction(View v, Consumer<Status> interactionConsumer) {
			if(!item.status.isRemote){
				interactionConsumer.accept(item.status);
				return;
			}
			UiUtils.lookupStatus(v.getContext(), item.status, item.accountID, null, interactionConsumer);
		}

		private boolean onFavoriteLongClick(View v) {
			if(item.status.preview) return false;
			if (AccountSessionManager.getInstance().getLoggedInAccounts().size() < 2) return false;
			UiUtils.pickInteractAs(v.getContext(),
					item.accountID, item.status,
					s -> s.favourited,
					(ic, status, consumer) -> ic.setFavorited(status, true, consumer, item.context),
					R.string.sk_favorite_as,
					R.string.sk_favorited_as,
					R.string.sk_already_favorited,
					R.drawable.ic_fluent_heart_20_regular
			);
			return true;
		}

		private int descriptionForId(int id){
			if(id==R.id.reply_btn)
				return R.string.button_reply;
			if(id==R.id.favorite_btn)
				return R.string.button_favorite;
			if(id==R.id.bookmark_btn)
				return R.string.button_bookmark;
			return 0;
		}
	}
}
