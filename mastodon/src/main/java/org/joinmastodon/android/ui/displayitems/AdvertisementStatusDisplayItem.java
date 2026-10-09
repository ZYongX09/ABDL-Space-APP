package org.joinmastodon.android.ui.displayitems;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.advertisements.RecordAdvertisementEvent;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Advertisement;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.imageloader.ImageLoaderViewHolder;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

/** Isolated timeline item for the optional advertisement extension. */
public class AdvertisementStatusDisplayItem extends StatusDisplayItem {
	public final Status status;
	public final Advertisement advertisement;
	private final String accountID;
	private final ImageLoaderRequest avatarRequest;
	private final ImageLoaderRequest imageRequest;
	private boolean impressionSent;

	public AdvertisementStatusDisplayItem(String parentID, Callbacks callbacks, Context context, Status status, String accountID){
		super(parentID, callbacks, context);
		this.status=status;
		this.advertisement=status.advertisement;
		this.accountID=accountID;
		String avatarUrl=advertisement.getAvatarUrl(status.account);
		avatarRequest=TextUtils.isEmpty(avatarUrl) ? null : new UrlImageLoaderRequest(avatarUrl, V.dp(40), V.dp(40));
		imageRequest=TextUtils.isEmpty(advertisement.imageUrl) ? null : new UrlImageLoaderRequest(advertisement.imageUrl, V.dp(640), V.dp(360));
	}

	@Override public Type getType(){ return Type.ADVERTISEMENT; }
	@Override public int getImageCount(){ return (avatarRequest==null ? 0 : 1)+(imageRequest==null ? 0 : 1); }
	@Override public ImageLoaderRequest getImageRequest(int index){ return index==0 && avatarRequest!=null ? avatarRequest : imageRequest; }

	private void report(RecordAdvertisementEvent.Type type){
		if(TextUtils.isEmpty(advertisement.id) || TextUtils.isEmpty(accountID)) return;
		try{
			new RecordAdvertisementEvent(advertisement.id, type).exec(accountID);
		}catch(Throwable ignored){
			// Telemetry is best effort and must never affect rendering or navigation.
		}
	}

	private void openTarget(RecordAdvertisementEvent.Type event){
		if(TextUtils.isEmpty(advertisement.targetUrl)) return;
		report(event);
		UiUtils.launchWebBrowser(context, advertisement.targetUrl);
	}

	public static class Holder extends StatusDisplayItem.Holder<AdvertisementStatusDisplayItem> implements ImageLoaderViewHolder {
		private final TextView label, name, username, text;
		private final ImageView avatar, image;

		public Holder(Activity activity, ViewGroup parent){
			super(activity, R.layout.display_item_advertisement, parent);
			label=findViewById(R.id.ad_label);
			name=findViewById(R.id.ad_name);
			username=findViewById(R.id.ad_username);
			text=findViewById(R.id.ad_text);
			avatar=findViewById(R.id.ad_avatar);
			image=findViewById(R.id.ad_image);
			itemView.setOnClickListener(v->item.openTarget(RecordAdvertisementEvent.Type.CLICK));
			findViewById(R.id.ad_header).setOnClickListener(v->item.openTarget(RecordAdvertisementEvent.Type.NAVIGATION));
			image.setOnClickListener(v->item.openTarget(RecordAdvertisementEvent.Type.IMAGE_VIEW));
			itemView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
				@Override public void onViewAttachedToWindow(View view){
					if(item!=null && !item.impressionSent){
						item.impressionSent=true;
						item.report(RecordAdvertisementEvent.Type.IMPRESSION);
					}
				}
				@Override public void onViewDetachedFromWindow(View view){}
			});
		}

		@Override public void onBind(AdvertisementStatusDisplayItem item){
			label.setText(item.advertisement.getLabel());
			Account fallback=item.status.account;
			name.setText(item.advertisement.getDisplayName(fallback));
			username.setText(item.advertisement.getUsername(fallback));
			text.setText(item.advertisement.getBody(item.status.content));
			String avatarUrl=item.advertisement.getAvatarUrl(fallback);
			avatar.setVisibility(TextUtils.isEmpty(avatarUrl) ? View.GONE : View.VISIBLE);
			image.setVisibility(item.imageRequest==null ? View.GONE : View.VISIBLE);
			itemView.setContentDescription(item.advertisement.getLabel());
		}

		@Override public void setImage(int index, Drawable drawable){
			if(item.avatarRequest!=null && index==0) avatar.setImageDrawable(drawable);
			else image.setImageDrawable(drawable);
		}
		@Override public void clearImage(int index){
			if(item.avatarRequest!=null && index==0) avatar.setImageDrawable(null);
			else image.setImageDrawable(null);
		}

		@Override public void onClick(){
			item.openTarget(RecordAdvertisementEvent.Type.CLICK);
		}

		@Override public void onClick(float x, float y){
			item.openTarget(RecordAdvertisementEvent.Type.CLICK);
		}
	}
}
