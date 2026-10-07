package org.joinmastodon.android.ui.displayitems;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.fragments.albums.AlbumDetailFragment;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.albums.AlbumModels.AlbumUpdate;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.Nav;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

public class AlbumUpdateStatusDisplayItem extends StatusDisplayItem{
	private final AlbumUpdate update;
	private final String accountID;
	private final ImageLoaderRequest coverRequest;

	public AlbumUpdateStatusDisplayItem(String parentID, Callbacks callbacks, Context context, Status status, String accountID){
		super(parentID, callbacks, context);
		this.update=status.albumUpdate;
		this.accountID=accountID;
		coverRequest=update.downloadProtected || update.coverUrl==null || update.coverUrl.isBlank() ? null : new UrlImageLoaderRequest(update.coverUrl, V.dp(360), V.dp(224));
	}

	@Override public Type getType(){ return Type.ALBUM_UPDATE; }
	@Override public int getImageCount(){ return update.downloadProtected || coverRequest==null ? 0 : 1; }
	@Override public ImageLoaderRequest getImageRequest(int index){ return update.downloadProtected ? null : coverRequest; }

	public static boolean supported(AlbumUpdate update){
		return update!=null && update.albumId!=null && !update.albumId.isBlank()
				&& update.albumName!=null && update.photoCount>0 && update.width>0 && update.height>0;
	}

	public static class Holder extends StatusDisplayItem.Holder<AlbumUpdateStatusDisplayItem> implements me.grishka.appkit.imageloader.ImageLoaderViewHolder{
		private final FrameLayout wrapper;
		private final LinearLayout card;
		private final ImageView cover;
		private final TextView count, name, protection;
		private boolean protectedCover;

		public Holder(Activity activity, ViewGroup parent){
			super(new FrameLayout(activity));
			wrapper=(FrameLayout)itemView;
			wrapper.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
			card=new LinearLayout(activity);
			card.setOrientation(LinearLayout.VERTICAL);
			card.setBackgroundResource(R.drawable.bg_card_with_border);
			card.setOutlineProvider(OutlineProviders.roundedRect(12));
			card.setClipToOutline(true);
			wrapper.addView(card, new FrameLayout.LayoutParams(-1, -2, Gravity.START));
			cover=new ImageView(activity);
			cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
			cover.setBackgroundColor(UiUtils.getThemeColor(activity, R.attr.colorM3SurfaceVariant));
			cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
			card.addView(cover, new LinearLayout.LayoutParams(-1, V.dp(180)));
			count=text(activity, 13, R.attr.colorM3OnSurfaceVariant);
			count.setPadding(V.dp(14), V.dp(12), V.dp(14), V.dp(4));
			card.addView(count);
			name=text(activity, 16, R.attr.colorM3OnSurface);
			name.setPadding(V.dp(14), 0, V.dp(14), V.dp(12));
			card.addView(name);
			View divider=new View(activity);
			divider.setBackgroundColor(UiUtils.getThemeColor(activity, R.attr.colorM3OutlineVariant));
			card.addView(divider, new LinearLayout.LayoutParams(-1, V.dp(1)));
			TextView brand=text(activity, 12, R.attr.colorM3OnSurfaceVariant);
			brand.setText(R.string.baby_albums_brand);
			brand.setPadding(V.dp(14), V.dp(10), V.dp(14), V.dp(10));
			card.addView(brand);
			protection=text(activity, 13, R.attr.colorM3OnSurfaceVariant);
			protection.setText(R.string.album_protection_enabled); protection.setPadding(V.dp(14), V.dp(8), V.dp(14), V.dp(12));
			protection.setVisibility(View.GONE); card.addView(protection);
			card.setOnClickListener(v->onClick());
		}

		private static TextView text(Context context, int size, int color){
			TextView view=new TextView(context);
			view.setTextSize(size);
			view.setTextColor(UiUtils.getThemeColor(context, color));
			view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
			return view;
		}

		@Override public void onBind(AlbumUpdateStatusDisplayItem item){
			wrapper.setPaddingRelative(V.dp(item.fullWidth ? 16 : 64), 0, V.dp(16), V.dp(8));
			int available=wrapper.getResources().getDisplayMetrics().widthPixels-wrapper.getPaddingStart()-wrapper.getPaddingEnd();
			int cardWidth=Math.max(V.dp(120), Math.min(V.dp(360), available));
			card.getLayoutParams().width=cardWidth;
			double aspect=(double)item.update.width/item.update.height;
			cover.getLayoutParams().height=Math.max(V.dp(96), Math.min(V.dp(224), (int)Math.round(cardWidth/aspect)));
			count.setText(wrapper.getContext().getString(R.string.baby_albums_post_count, item.update.photoCount));
			name.setText(wrapper.getContext().getString(R.string.baby_albums_post_name, item.update.albumName));
			card.setContentDescription(wrapper.getContext().getString(R.string.baby_albums_open_album)+" · "+item.update.albumName);
			protectedCover=item.update.downloadProtected;
			protection.setVisibility(protectedCover ? View.VISIBLE : View.GONE);
			if(protectedCover) card.setContentDescription(card.getContentDescription()+" · "+wrapper.getContext().getString(R.string.album_protection_enabled));
			clearImage(0);
			card.requestLayout();
		}

		@Override public void setImage(int index, Drawable drawable){ if(!protectedCover) cover.setImageDrawable(drawable); }
		@Override public void clearImage(int index){
			if(!protectedCover){ cover.setImageDrawable(null); return; }
			Drawable lock=wrapper.getContext().getDrawable(R.drawable.ic_lock_24px).mutate();
			lock.setTint(UiUtils.getThemeColor(wrapper.getContext(), R.attr.colorM3OnSurfaceVariant));
			android.graphics.drawable.LayerDrawable placeholder=new android.graphics.drawable.LayerDrawable(new Drawable[]{lock});
			placeholder.setLayerSize(0, V.dp(40), V.dp(40)); placeholder.setLayerGravity(0, Gravity.CENTER);
			cover.setImageDrawable(placeholder);
		}
		@Override public void onClick(){
			if(item==null || !(item.context instanceof Activity activity)) return;
			Bundle args=new Bundle();
			args.putString("account", item.accountID);
			args.putString("albumId", item.update.albumId);
			Nav.go(activity, AlbumDetailFragment.class, args);
		}
	}
}
