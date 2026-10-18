package org.joinmastodon.android.ui.displayitems;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.fragments.friendcards.FriendCardDetailFragment;
import org.joinmastodon.android.friendcards.FriendCardGames;
import org.joinmastodon.android.model.FriendCard;
import org.joinmastodon.android.model.FriendCardAttachment;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.views.FlowLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import me.grishka.appkit.Nav;
import me.grishka.appkit.imageloader.ImageLoaderViewHolder;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

/** A body enhancement, never a replacement for the status header, CW, attachments or footer. */
public class FriendCardStatusDisplayItem extends StatusDisplayItem{
	public enum Presentation{ NONE, PUBLICATION, LINK, UNAVAILABLE_PUBLICATION, UNAVAILABLE_LINK }

	public final FriendCardAttachment attachment;
	public final Presentation presentation;
	private final String accountID;
	private final ImageLoaderRequest coverRequest;

	public FriendCardStatusDisplayItem(String parentID, Callbacks callbacks, Context context, Status status, String accountID, boolean hideCover){
		super(parentID, callbacks, context);
		this.status=status;
		this.attachment=status.friendCard;
		this.presentation=presentation(attachment);
		this.accountID=accountID;
		FriendCard card=attachment==null ? null : attachment.card;
		// Only the API's owned-cover projection is used, never the attachment's user-facing URL.
		// Do not request an unavailable/private/sensitive cover, including recycled late images.
		String cover=presentation==Presentation.PUBLICATION && !hideCover && !status.sensitive && card!=null ? card.coverUrl : null;
		coverRequest=hasText(cover) && (cover.startsWith("https://") || cover.startsWith("http://"))
				? new UrlImageLoaderRequest(cover, V.dp(440), V.dp(200)) : null;
	}

	public static Presentation presentation(FriendCardAttachment extension){
		if(extension==null || extension.schemaVersion!=1 || !hasText(extension.id)) return Presentation.NONE;
		boolean publication="publication".equals(extension.mode);
		if(!publication && !"link".equals(extension.mode)) return Presentation.NONE;
		if(!extension.available) return publication ? Presentation.UNAVAILABLE_PUBLICATION : Presentation.UNAVAILABLE_LINK;
		if(!publication) return hasText(extension.title) ? Presentation.LINK : Presentation.NONE;
		FriendCard card=extension.card;
		if(card==null || card.schemaVersion!=1 || !Objects.equals(extension.id, card.id)
				|| !hasText(card.title) || !"active".equals(card.state)) return Presentation.NONE;
		return Presentation.PUBLICATION;
	}

	public static boolean replacesBody(Presentation presentation){
		return presentation==Presentation.PUBLICATION;
	}

	/** Server-provided HTML suffix includes its separator; remove exactly once at the exact end. */
	public static String contentWithoutFallback(String content, FriendCardAttachment extension){
		Presentation presentation=presentation(extension);
		if((presentation!=Presentation.LINK && presentation!=Presentation.UNAVAILABLE_LINK)
				|| content==null || !hasText(extension.fallbackSuffix)) return content;
		return content.endsWith(extension.fallbackSuffix) ? content.substring(0, content.length()-extension.fallbackSuffix.length()) : content;
	}

	private static boolean hasText(String value){ return value!=null && !value.isBlank(); }
	private static boolean isPublic(FriendCard card, String field){ return card.publicFields!=null && card.publicFields.contains(field); }
	private static void add(List<String> values, FriendCard card, String field, String value){
		if(isPublic(card, field) && hasText(value)) values.add(value);
	}

	/** Timeline summaries intentionally omit school and interaction habits, even when public. */
	public static List<String> aboutTags(FriendCard card){
		List<String> values=new ArrayList<>();
		add(values, card, "gender", card.gender);
		add(values, card, "age", card.age);
		add(values, card, "region", card.region);
		add(values, card, "mbti", card.mbti);
		add(values, card, "zodiac", card.zodiac);
		return values;
	}

	public static String about(FriendCard card){
		return tagSummary(aboutTags(card));
	}

	public static List<String> interestTags(FriendCard card){
		List<String> values=new ArrayList<>();
		if(isPublic(card, "interests") && card.interests!=null)
			for(String value:card.interests) if(hasText(value)) values.add(value);
		if(isPublic(card, "games") && card.games!=null)
			for(String value:new java.util.LinkedHashSet<>(card.games)) if(hasText(value)) values.add(FriendCardGames.gameName(value));
		return values;
	}

	public static String interests(FriendCard card){ return tagSummary(interestTags(card)); }

	private static String tagSummary(List<String> values){
		int visible=Math.min(4, values.size());
		String result=TextUtils.join(" · ", values.subList(0, visible));
		if(values.size()>visible) result+="   +"+(values.size()-visible);
		return result;
	}

	@Override public Type getType(){ return Type.FRIEND_CARD; }
	@Override public int getImageCount(){ return coverRequest==null ? 0 : 1; }
	@Override public ImageLoaderRequest getImageRequest(int index){ return index==0 ? coverRequest : null; }

	public static class Holder extends StatusDisplayItem.Holder<FriendCardStatusDisplayItem> implements ImageLoaderViewHolder{
		private final FrameLayout wrapper;
		private final LinearLayout card, details;
		private final CoverFrame coverPanel;
		private final FocusCoverView cover;
		private final TextView title, overlayTitle, about, interests, lookingFor, action;
		private final FlowLayout aboutChips, interestChips;
		private boolean acceptsCover;

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

			coverPanel=new CoverFrame(activity);
			card.addView(coverPanel, new LinearLayout.LayoutParams(-1, -2));
			cover=new FocusCoverView(activity);
			cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
			coverPanel.addView(cover, new FrameLayout.LayoutParams(-1, -1));
			View bottomShade=new View(activity);
			bottomShade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
			bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
					new int[]{Color.TRANSPARENT, 0xC9000000}));
			coverPanel.bottomShade=bottomShade;
			coverPanel.addView(bottomShade, new FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM));
			overlayTitle=text(activity, 17, R.attr.colorM3OnSurface, 2);
			overlayTitle.setTextColor(Color.WHITE);
			overlayTitle.setTypeface(overlayTitle.getTypeface(), Typeface.BOLD);
			overlayTitle.setPadding(0, 0, 0, 0);
			FrameLayout.LayoutParams overlayParams=new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
			overlayParams.setMargins(V.dp(14), 0, V.dp(14), V.dp(12));
			coverPanel.addView(overlayTitle, overlayParams);

			details=new LinearLayout(activity);
			details.setOrientation(LinearLayout.VERTICAL);
			card.addView(details, new LinearLayout.LayoutParams(-1, -2));
			title=text(activity, 17, R.attr.colorM3OnSurface, 2);
			title.setTypeface(title.getTypeface(), Typeface.BOLD);
			details.addView(title);
			about=text(activity, 13, R.attr.colorM3OnSurfaceVariant, 1);
			about.setText(R.string.friend_card_post_about);
			details.addView(about);
			aboutChips=new FlowLayout(activity);
			details.addView(aboutChips, new LinearLayout.LayoutParams(-1, -2));
			interests=text(activity, 13, R.attr.colorM3OnSurfaceVariant, 1);
			interests.setText(R.string.friend_card_post_interests);
			details.addView(interests);
			interestChips=new FlowLayout(activity);
			details.addView(interestChips, new LinearLayout.LayoutParams(-1, -2));
			lookingFor=text(activity, 14, R.attr.colorM3OnSurface, 2);
			details.addView(lookingFor);
			action=text(activity, 13, R.attr.colorM3Primary, 1);
			action.setPadding(V.dp(14), V.dp(10), V.dp(14), V.dp(12));
			action.setMinHeight(V.dp(48));
			action.setGravity(Gravity.CENTER_VERTICAL);
			card.addView(action, new LinearLayout.LayoutParams(-1, -2));
			card.setOnClickListener(v->onClick());
		}

		private static TextView text(Context context, int size, int color, int lines){
			TextView view=new TextView(context);
			view.setTextSize(size);
			view.setTextColor(UiUtils.getThemeColor(context, color));
			view.setMaxLines(lines);
			view.setEllipsize(TextUtils.TruncateAt.END);
			view.setPadding(0, 0, 0, V.dp(6));
			return view;
		}

		@Override public void onBind(FriendCardStatusDisplayItem item){
			wrapper.setPaddingRelative(V.dp(item.fullWidth ? 16 : 64), 0, V.dp(16), V.dp(8));
			boolean publication=item.presentation==Presentation.PUBLICATION;
			boolean available=publication || item.presentation==Presentation.LINK;
			FriendCard data=publication ? item.attachment.card : null;
			acceptsCover=publication && item.coverRequest!=null;
			cover.setImageDrawable(null);
			// Stay compact until an image actually arrives. A failed load leaves no empty banner.
			setCoverVisible(false);
			cover.setFocus(data==null ? .5f : data.coverFocusX, data==null ? .4f : data.coverFocusY);
			CharSequence heading=available ? (publication ? data.title : item.attachment.title)
					: wrapper.getContext().getString(R.string.friend_card_post_unavailable);
			title.setText(heading);
			overlayTitle.setText(heading);
			title.setMaxLines(publication ? 2 : 1);
			title.setTextColor(UiUtils.getThemeColor(wrapper.getContext(), publication ? R.attr.colorM3OnSurface : R.attr.colorM3Primary));
			bindTags(about, aboutChips, publication ? aboutTags(data) : List.of());
			bindTags(interests, interestChips, publication ? interestTags(data) : List.of());
			bindRow(lookingFor, publication && isPublic(data, "looking_for") ? data.lookingFor : "", R.string.friend_card_post_looking_for);
			details.setPadding(V.dp(14), V.dp(publication ? 12 : 8), V.dp(14), V.dp(publication ? 6 : 2));
			action.setVisibility(publication ? View.VISIBLE : View.GONE);
			action.setText(R.string.friend_card_post_details);
			card.setBackgroundResource(publication ? R.drawable.bg_card_with_border : R.drawable.bg_rect_4dp_ripple);
			card.setClickable(available);
			card.setFocusable(available);
			card.setContentDescription(heading);
		}

		private void setCoverVisible(boolean visible){
			coverPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
			title.setVisibility(visible ? View.GONE : View.VISIBLE);
		}

		private void bindTags(TextView label, FlowLayout chips, List<String> values){
			chips.removeAllViews();
			label.setVisibility(values.isEmpty() ? View.GONE : View.VISIBLE);
			chips.setVisibility(values.isEmpty() ? View.GONE : View.VISIBLE);
			int visible=Math.min(4, values.size());
			for(int i=0; i<visible; i++) addTag(chips, values.get(i));
			if(values.size()>visible) addTag(chips, "+"+(values.size()-visible));
		}

		private void addTag(FlowLayout chips, String value){
			TextView tag=text(wrapper.getContext(), 12, R.attr.colorM3OnSecondaryContainer, 1);
			tag.setText(value);
			tag.setMaxWidth(V.dp(160));
			tag.setPadding(V.dp(8), V.dp(3), V.dp(8), V.dp(3));
			GradientDrawable background=new GradientDrawable();
			background.setColor(UiUtils.getThemeColor(wrapper.getContext(), R.attr.colorM3SecondaryContainer));
			background.setCornerRadius(V.dp(8));
			tag.setBackground(background);
			FrameLayout space=new FrameLayout(wrapper.getContext());
			space.setPadding(0, 0, V.dp(6), V.dp(6));
			space.addView(tag, new FrameLayout.LayoutParams(-2, -2));
			chips.addView(space, new ViewGroup.LayoutParams(-2, -2));
		}

		private void bindRow(TextView view, String value, int label){
			view.setVisibility(hasText(value) ? View.VISIBLE : View.GONE);
			view.setText(hasText(value) ? wrapper.getContext().getString(label)+"  "+value : "");
		}

		@Override public void setImage(int index, Drawable image){
			if(index!=0 || !acceptsCover) return;
			cover.setImageDrawable(image);
			setCoverVisible(image!=null);
		}
		@Override public void clearImage(int index){
			if(index!=0) return;
			cover.setImageDrawable(null);
			setCoverVisible(false);
		}
		@Override public void onImageLoadingFailed(int index, Throwable error){ clearImage(index); }
		@Override public void onClick(){
			if(item==null || !(item.context instanceof Activity activity)
					|| (item.presentation!=Presentation.PUBLICATION && item.presentation!=Presentation.LINK)) return;
			Bundle args=new Bundle();
			args.putString("account", item.accountID);
			args.putString("card_id", item.attachment.id);
			Nav.go(activity, FriendCardDetailFragment.class, args);
		}
	}

	/** Only the lower 60% is shaded; the upper image is never tinted. */
	private static class CoverFrame extends FrameLayout{
		private View bottomShade;
		CoverFrame(Context context){ super(context); }
		@Override protected void onMeasure(int widthSpec, int heightSpec){
			int height=Math.round(MeasureSpec.getSize(widthSpec)/2.2f);
			if(bottomShade!=null) bottomShade.getLayoutParams().height=Math.round(height*.6f);
			super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
		}
	}

	/** Measures 2.2:1 at the actual row width, then crops using the server's normalized focus. */
	public static class FocusCoverView extends ImageView{
		private float focusX=.5f, focusY=.4f;
		public FocusCoverView(Context context){ super(context); setScaleType(ScaleType.MATRIX); }
		public void setFocus(float x, float y){
			focusX=Float.isFinite(x) ? Math.max(0, Math.min(1, x)) : .5f;
			focusY=Float.isFinite(y) ? Math.max(0, Math.min(1, y)) : .4f;
			updateMatrix();
		}
		@Override protected void onMeasure(int widthSpec, int heightSpec){
			int width=MeasureSpec.getSize(widthSpec);
			super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(Math.round(width/2.2f), MeasureSpec.EXACTLY));
		}
		@Override protected void onSizeChanged(int w, int h, int oldW, int oldH){ super.onSizeChanged(w,h,oldW,oldH); updateMatrix(); }
		@Override public void setImageDrawable(Drawable drawable){ super.setImageDrawable(drawable); updateMatrix(); }
		private void updateMatrix(){
			Drawable drawable=getDrawable();
			if(drawable==null || drawable.getIntrinsicWidth()<=0 || drawable.getIntrinsicHeight()<=0 || getWidth()==0 || getHeight()==0) return;
			float width=drawable.getIntrinsicWidth(), height=drawable.getIntrinsicHeight();
			float scale=Math.max(getWidth()/width, getHeight()/height);
			// Float rounding can make either nominal lower bound slightly positive.
			float lowerX=Math.min(0f, getWidth()-width*scale), lowerY=Math.min(0f, getHeight()-height*scale);
			float x=Math.max(lowerX, Math.min(0f, getWidth()/2f-width*scale*focusX));
			float y=Math.max(lowerY, Math.min(0f, getHeight()/2f-height*scale*focusY));
			Matrix matrix=new Matrix(); matrix.setScale(scale,scale); matrix.postTranslate(x,y); setImageMatrix(matrix);
		}
	}
}
