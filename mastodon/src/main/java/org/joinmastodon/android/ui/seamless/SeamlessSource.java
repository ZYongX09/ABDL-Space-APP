package org.joinmastodon.android.ui.seamless;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewParent;

import androidx.recyclerview.widget.RecyclerView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.model.FriendCardAttachment;
import org.joinmastodon.android.model.FriendCard;
import org.joinmastodon.android.ui.displayitems.FriendCardStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.HeaderStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.CompactHeaderStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.FooterStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.ExtendedFooterStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.TextStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.MediaGridStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.PollOptionStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.PollFooterStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.AudioStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.SpoilerStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.InlineStatusStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.FriendRequestStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.NestedQuoteStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.TranslationStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.LinkCardStatusDisplayItem;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import me.grishka.appkit.utils.BindableViewHolder;

/** Live source identity and geometry only. Never reads image caches, downloads, or persists pixels. */
abstract class SeamlessSource{
	static final int MAX_PIXELS=1024*1024;
	final WeakReference<Activity> owner;
	final String account, entity;
	SeamlessSource(Activity owner, String account, String entity){ this.owner=new WeakReference<>(owner); this.account=account; this.entity=entity; }
	abstract View drawingView();
	abstract Rect screenBounds();
	abstract boolean bindingValid();
	boolean bitmapOnly(){ return false; }
	float radius(){ return 0; }

	final boolean valid(){
		Activity activity=owner.get();
		return activity!=null && SeamlessTransitions.captureAllowed(activity, account) && bindingValid() && screenBounds()!=null;
	}
	final Rect bounds(boolean window){ return coordinates(screenBounds(), window); }
	final Rect coordinates(Rect screenBounds, boolean window){
		View view=drawingView();
		if(screenBounds==null || view==null) return null;
		Rect bounds=new Rect(screenBounds);
		if(window){
			int[] screen=new int[2], local=new int[2];
			view.getLocationOnScreen(screen); view.getLocationInWindow(local);
			bounds.offset(local[0]-screen[0], local[1]-screen[1]);
		}
		return bounds;
	}
	final Bitmap snapshot(){ return snapshot(screenBounds(), MAX_PIXELS); }
	final Bitmap snapshot(Rect area, int pixelBudget){
		if(!valid() || area==null || !area.equals(screenBounds()) || pixelBudget<=0) return null;
		int[] size=snapshotSize(area.width(), area.height(), Math.min(MAX_PIXELS, pixelBudget));
		if(size==null) return null;
		Bitmap bitmap;
		try{ bitmap=Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888); }
		catch(OutOfMemoryError exhausted){ return null; }
		try{
			Canvas canvas=new Canvas(bitmap);
			Integer color=backgroundColor(); if(color!=null) canvas.drawColor(color);
			canvas.scale((float)size[0]/area.width(), (float)size[1]/area.height());
			canvas.translate(-area.left, -area.top);
			draw(canvas, area);
			// A draw callback must not be able to replace a binding or change protection mid-capture.
			if(!valid() || !area.equals(screenBounds())){ bitmap.recycle(); return null; }
			return bitmap;
		}catch(RuntimeException | OutOfMemoryError failure){ bitmap.recycle(); return null; }
	}
	static int[] snapshotSize(int width, int height, int budget){
		if(width<=0 || height<=0 || budget<=0) return null;
		double scale=Math.min(1d, Math.sqrt((double)budget/((long)width*height)));
		int w=Math.max(1, (int)(width*scale)), h=Math.max(1, (int)(height*scale));
		// Also bounds extreme aspect ratios after rounding the shorter edge up to one pixel.
		w=Math.min(w, budget); h=Math.min(h, budget/w);
		return new int[]{w, h};
	}
	void draw(Canvas canvas, Rect area){
		View view=drawingView();
		int[] location=new int[2]; view.getLocationOnScreen(location);
		canvas.clipRect(area);
		canvas.translate(location[0], location[1]);
		view.draw(canvas);
	}
	final Integer backgroundColor(){
		View view=drawingView();
		// A card's own color is not the surrounding list color.
		if(this instanceof Card) view=view!=null && view.getParent() instanceof View ? (View)view.getParent() : null;
		while(view!=null){
			if(view.getBackground()!=null){
				if(view.getBackground() instanceof ColorDrawable solid && Color.alpha(solid.getColor())==255) return solid.getColor();
				return null; // Do not estimate gradients, composited colors or translucent surfaces.
			}
			view=view.getParent() instanceof View ? (View)view.getParent() : null;
		}
		return null;
	}

	static void bind(View view, String account, String entity, boolean permitted, Status status){
		view.setTag(R.id.seamless_source_binding, new Binding(account, entity, permitted, status, null));
	}
	static void bindFriendCard(View view, String account, String entity, boolean permitted, Status owner, Status content){
		view.setTag(R.id.seamless_source_binding, new Binding(account, entity, permitted, owner,
				content==null ? null : new FriendProjection(content)));
	}
	/** Small typed source fingerprint, not a DTO/body serialization or remote permission lookup. */
	private static final class FriendProjection{
		final Status content;
		final FriendCardAttachment attachment;
		final FriendCard card;
		final FriendCardAttachment.LinkPreview preview;
		final FriendCardStatusDisplayItem.Presentation presentation;
		final String id, cardID, ownerID, updatedAt, state, cover, coverUpload, previewOwner;
		final int schema, cardSchema, version;
		final List<String> publicFields;
		final float focusX, focusY;
		FriendProjection(Status content){
			this.content=content; attachment=content.friendCard;
			presentation=FriendCardStatusDisplayItem.presentation(attachment);
			id=attachment==null ? null : attachment.id; schema=attachment==null ? 0 : attachment.schemaVersion;
			card=attachment!=null && presentation==FriendCardStatusDisplayItem.Presentation.PUBLICATION ? attachment.card : null;
			preview=attachment!=null && presentation==FriendCardStatusDisplayItem.Presentation.LINK ? attachment.linkPreview : null;
			cardID=card==null ? null : card.id; cardSchema=card==null ? 0 : card.schemaVersion; version=card==null ? 0 : card.version;
			ownerID=card==null ? null : card.ownerId; updatedAt=card==null ? null : card.updatedAt; state=card==null ? null : card.state;
			publicFields=card==null || card.publicFields==null ? null : new ArrayList<>(card.publicFields);
			cover=card!=null ? card.coverUrl : preview!=null ? preview.coverUrl : null; coverUpload=card==null ? null : card.coverUploadId;
			focusX=card!=null ? card.coverFocusX : preview!=null ? preview.coverFocusX : 0;
			focusY=card!=null ? card.coverFocusY : preview!=null ? preview.coverFocusY : 0;
			previewOwner=preview==null ? null : preview.ownerId;
		}
		boolean valid(Status owner, String entity){
			return attachment!=null && Objects.equals(entity, id) && containsStatus(owner, content, 0) && contentAllowed(content) && valid();
		}
		boolean valid(){
			if(attachment==null) return content.friendCard==null;
			if(content.friendCard!=attachment || !Objects.equals(id, attachment.id) || schema!=attachment.schemaVersion
					|| presentation!=FriendCardStatusDisplayItem.presentation(attachment)) return false;
			if(presentation==FriendCardStatusDisplayItem.Presentation.PUBLICATION)
				return attachment.card==card && card.schemaVersion==cardSchema && card.version==version && Objects.equals(cardID, card.id)
						&& Objects.equals(ownerID, card.ownerId) && Objects.equals(updatedAt, card.updatedAt) && Objects.equals(state, card.state)
						&& Objects.equals(publicFields, card.publicFields) && Objects.equals(cover, card.coverUrl) && Objects.equals(coverUpload, card.coverUploadId)
						&& Float.compare(focusX, card.coverFocusX)==0 && Float.compare(focusY, card.coverFocusY)==0;
			if(presentation==FriendCardStatusDisplayItem.Presentation.LINK)
				return attachment.linkPreview==preview && (preview==null || (Objects.equals(cover, preview.coverUrl) && Objects.equals(previewOwner, preview.ownerId)
						&& Float.compare(focusX, preview.coverFocusX)==0 && Float.compare(focusY, preview.coverFocusY)==0));
			return false;
		}
	}
	static SeamlessSource card(Activity activity, View view, String account, String entity){
		if(view==null) return null;
		Object binding=view.getTag(R.id.seamless_source_binding);
		if(!(binding instanceof Binding key) || !key.permitted || !Objects.equals(account, key.account) || !Objects.equals(entity, key.entity)) return null;
		Card source=new Card(activity, view, key);
		return source.valid() ? source : null;
	}
	private record Binding(String account, String entity, boolean permitted, Status status, FriendProjection friend){}
	private static final class Card extends SeamlessSource{
		private final WeakReference<View> view;
		private final Binding binding;
		Card(Activity owner, View view, Binding binding){ super(owner, binding.account, binding.entity); this.view=new WeakReference<>(view); this.binding=binding; }
		@Override View drawingView(){ return view.get(); }
		@Override boolean bindingValid(){ return view.get()!=null && view.get().getTag(R.id.seamless_source_binding)==binding
				&& (binding.status==null || contentAllowed(binding.status))
				&& (binding.friend==null || binding.friend.valid(binding.status, binding.entity)) && stableRecycler(view.get()); }
		@Override Rect screenBounds(){ return visible(view.get()); }
		@Override float radius(){
			View v=view.get();
			if(v!=null && v.getOutlineProvider()!=null){
				Outline outline=new Outline(); v.getOutlineProvider().getOutline(v, outline);
				if(outline.getRadius()>=0) return outline.getRadius();
			}
			return 0;
		}
		@Override boolean bitmapOnly(){
			View v=view.get(); Rect area=screenBounds();
			return v==null || area==null || area.width()!=v.getWidth() || area.height()!=v.getHeight();
		}
	}

	static boolean contentAllowed(Status status){
		return contentAllowed(status, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 0);
	}
	private static boolean contentAllowed(Status status, java.util.Set<Status> seen, int depth){
		if(status==null || depth>8 || !seen.add(status) || status.sensitive || status.visibility==StatusPrivacy.PRIVATE
				|| status.visibility==StatusPrivacy.DIRECT || (status.spoilerText!=null && !status.spoilerText.isEmpty())
				|| (status.filtered!=null && status.filtered.stream().anyMatch(f->f!=null && f.filter!=null && f.filter.isActive()))
				|| (status.albumUpdate!=null && status.albumUpdate.downloadProtected)) return false;
		return (status.reblog==null || contentAllowed(status.reblog, seen, depth+1))
				&& (status.quote==null || status.quote.quotedStatus==null || contentAllowed(status.quote.quotedStatus, seen, depth+1));
	}

	/** Several legacy row models hide StatusDisplayItem.status; read their actual typed field. */
	static Status statusOf(StatusDisplayItem item){
		if(item instanceof HeaderStatusDisplayItem row) return row.status;
		if(item instanceof CompactHeaderStatusDisplayItem row) return row.status;
		if(item instanceof FooterStatusDisplayItem row) return row.status;
		if(item instanceof ExtendedFooterStatusDisplayItem row) return row.status;
		if(item instanceof TextStatusDisplayItem row) return row.status;
		if(item instanceof MediaGridStatusDisplayItem row) return row.status;
		if(item instanceof PollOptionStatusDisplayItem row) return row.status;
		if(item instanceof PollFooterStatusDisplayItem row) return row.status;
		if(item instanceof AudioStatusDisplayItem row) return row.status;
		if(item instanceof SpoilerStatusDisplayItem row) return row.status;
		if(item instanceof InlineStatusStatusDisplayItem row) return row.status;
		if(item instanceof FriendRequestStatusDisplayItem row) return row.status;
		if(item instanceof NestedQuoteStatusDisplayItem row) return row.status;
		if(item instanceof TranslationStatusDisplayItem row) return row.contentStatus;
		if(item instanceof LinkCardStatusDisplayItem row && row.getCard().parentObject instanceof Status status) return status;
		return item.status; // Native novel/album/friend-card rows assign the inherited field.
	}
	private static StatusDisplayItem itemOf(RecyclerView list, View child){
		// LinkCardHolder is a BindableViewHolder, not a StatusDisplayItem.Holder.
		return list.getChildViewHolder(child) instanceof BindableViewHolder<?> holder && holder.getItem() instanceof StatusDisplayItem item ? item : null;
	}

	/** Non-click callers may use only an unambiguous, non-quoted occurrence. */
	static SeamlessSource post(Activity activity, RecyclerView list, String account, Status status){
		if(list==null || !contentAllowed(status)) return null;
		String parent=null;
		for(int i=0;i<list.getChildCount();i++){
			StatusDisplayItem item=itemOf(list, list.getChildAt(i));
			Status current=item==null ? null : statusOf(item);
			if(item==null || item.isQuote || current!=status) continue;
			if(parent!=null && !Objects.equals(parent, item.parentID)) return null;
			parent=item.parentID;
		}
		// A stripped reblog/notification needs its original visibility owner from the click path.
		if(!Objects.equals(parent, status.id)) return null;
		return post(activity, list, account, status, parent, status, null);
	}
	static SeamlessSource post(Activity activity, RecyclerView list, String account, Status selected, String parentID, Status visibilityStatus, List<StatusDisplayItem> models){
		if(list==null || parentID==null || !contentAllowed(visibilityStatus) || !containsStatus(visibilityStatus, selected, 0)) return null;
		List<Row> rows=new ArrayList<>();
		boolean quote=visibilityStatus.getContentStatus()!=selected;
		for(int i=0;i<list.getChildCount();i++){
			View child=list.getChildAt(i);
			StatusDisplayItem item=itemOf(list, child);
			if(item==null || !Objects.equals(parentID, item.parentID) || (quote && !item.isQuote)) continue;
			if(models!=null && !models.contains(item)) return null;
			Rect crop=rowVisible(child, item);
			if(crop==null) continue; // Off-viewport attached rows are not pixels of this source.
			Status current=statusOf(item);
			if(!drawableRow(item) || (current!=null && !containsStatus(selected, current, 0))) return null;
			if(current==null && item.getType()!=StatusDisplayItem.Type.REBLOG_OR_REPLY_LINE && item.getType()!=StatusDisplayItem.Type.QUOTE_ERROR) return null;
			rows.add(new Row(child, item, crop));
		}
		if(rows.isEmpty()) return null;
		Post source=new Post(activity, list, account, selected, parentID, visibilityStatus, models, rows);
		return source.valid() ? source : null;
	}
	private static boolean containsStatus(Status root, Status value, int depth){
		return root!=null && value!=null && depth<=8 && (root==value || containsStatus(root.reblog, value, depth+1)
				|| (root.quote!=null && containsStatus(root.quote.quotedStatus, value, depth+1)));
	}
	private static boolean drawableRow(StatusDisplayItem item){
		return switch(item.getType()){
			case HEADER, HEADER_COMPACT, TEXT, TRANSLATION, MEDIA_GRID, POLL_OPTION, POLL_FOOTER,
					CARD_LARGE, CARD_COMPACT, FOOTER, EXTENDED_FOOTER, REBLOG_OR_REPLY_LINE, QUOTE_ERROR,
					NESTED_QUOTE, FRIEND_CARD, ALBUM_UPDATE, NOVEL, EMOJI_REACTIONS -> true;
			default -> false; // Audio/surface, hidden CW/filter, ads and unrelated list rows use ordinary navigation.
		};
	}
	private static Rect rowVisible(View view, StatusDisplayItem item){
		Rect crop=visible(view);
		if(crop!=null && item.isQuote){
			int[] location=new int[2]; view.getLocationOnScreen(location);
			float density=view.getResources().getDisplayMetrics().density;
			int start=Math.round((item.fullWidth ? 16 : 64)*density), end=Math.round(16*density);
			boolean rtl=view.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
			if(!crop.intersect(location[0]+(rtl ? end : start), crop.top, location[0]+view.getWidth()-(rtl ? start : end), crop.bottom)) return null;
		}
		return crop;
	}
	private static final class Row{
		final WeakReference<View> view;
		final WeakReference<StatusDisplayItem> item;
		final String content;
		final WeakReference<Status> status;
		final FriendProjection friend;
		final Rect localCrop;
		final int width, height;
		final boolean quote;
		Row(View view, StatusDisplayItem item, Rect crop){
			this.view=new WeakReference<>(view); this.item=new WeakReference<>(item);
			Status current=statusOf(item); status=new WeakReference<>(current); content=current==null ? null : current.getContentStatus().content;
			friend=current!=null && item instanceof FriendCardStatusDisplayItem ? new FriendProjection(current) : null;
			int[] location=new int[2]; view.getLocationOnScreen(location);
			localCrop=new Rect(crop); localCrop.offset(-location[0], -location[1]);
			width=view.getWidth(); height=view.getHeight(); quote=item.isQuote;
		}
		Rect bounds(){
			View v=view.get(); StatusDisplayItem model=item.get();
			Rect visible=model==null ? null : rowVisible(v, model);
			if(visible==null || v.getWidth()!=width || v.getHeight()!=height || model.isQuote!=quote) return null;
			int[] location=new int[2]; v.getLocationOnScreen(location);
			Rect bounds=new Rect(localCrop); bounds.offset(location[0], location[1]);
			return visible.contains(bounds) ? bounds : null;
		}
	}
	private static final class Post extends SeamlessSource{
		final WeakReference<RecyclerView> list;
		final WeakReference<List<StatusDisplayItem>> models;
		final WeakReference<Status> selected, visibilityStatus;
		final List<Row> rows;
		final String parentID;
		final boolean hasModels;
		Post(Activity owner, RecyclerView list, String account, Status selected, String parentID, Status visibilityStatus, List<StatusDisplayItem> models, List<Row> rows){
			super(owner, account, selected.id); this.list=new WeakReference<>(list); this.parentID=parentID; this.rows=rows;
			this.selected=new WeakReference<>(selected); this.visibilityStatus=new WeakReference<>(visibilityStatus);
			this.models=new WeakReference<>(models); hasModels=models!=null;
		}
		@Override View drawingView(){ return list.get(); }
		@Override boolean bitmapOnly(){ return true; }
		@Override boolean bindingValid(){
			RecyclerView parent=list.get(); Status root=visibilityStatus.get(), target=selected.get();
			List<StatusDisplayItem> currentModels=models.get();
			if(parent==null || !stableRecycler(parent) || !contentAllowed(root) || !containsStatus(root, target, 0) || (hasModels && currentModels==null)) return false;
			for(Row row:rows){
				View view=row.view.get(); StatusDisplayItem item=row.item.get();
				Status current=item==null ? null : statusOf(item);
				if(view==null || view.getParent()!=parent || item==null || itemOf(parent, view)!=item
						|| parent.getChildViewHolder(view).getAbsoluteAdapterPosition()==RecyclerView.NO_POSITION
						|| (hasModels && !currentModels.contains(item)) || !Objects.equals(parentID, item.parentID)
						|| current!=row.status.get() || (current!=null && (!contentAllowed(current) || !containsStatus(target, current, 0)
						|| !Objects.equals(current.getContentStatus().content, row.content)))
						|| (row.friend!=null && !row.friend.valid()) || row.bounds()==null) return false;
			}
			return true;
		}
		@Override Rect screenBounds(){
			Rect result=null;
			for(Row row:rows){
				Rect visible=row.bounds(); if(visible==null) return null;
				if(result==null) result=visible; else result.union(visible);
			}
			return result;
		}
		@Override void draw(Canvas canvas, Rect area){
			// Never RecyclerView.draw(): disappearing neighbours, selectors and decorations can
			// contain pixels from another (including private) post even inside a public crop.
			for(Row row:rows){
				View view=row.view.get(); Rect bounds=row.bounds();
				if(view==null || bounds==null) throw new IllegalStateException("Source detached");
				int save=canvas.save();
				canvas.clipRect(area); canvas.clipRect(bounds);
				int[] location=new int[2]; view.getLocationOnScreen(location);
				canvas.translate(location[0], location[1]);
				view.draw(canvas); // Its own children are clipped to this row's authorised visible ink.
				canvas.restoreToCount(save);
			}
		}
	}
	private static boolean stableRecycler(View view){
		for(View current=view; current!=null;){
			if(current instanceof RecyclerView list && (list.isComputingLayout() || list.hasPendingAdapterUpdates()
					|| (list.getItemAnimator()!=null && list.getItemAnimator().isRunning()))) return false;
			ViewParent parent=current.getParent(); current=parent instanceof View ? (View)parent : null;
		}
		return true;
	}

	static Rect visible(View view){
		if(view==null || !view.isAttachedToWindow() || !view.isShown() || view.getWidth()<=0 || view.getHeight()<=0) return null;
		for(View current=view;current!=null;){
			if(current.getAlpha()!=1f || current.getScaleX()!=1f || current.getScaleY()!=1f || current.getRotation()!=0f) return null;
			ViewParent parent=current.getParent(); current=parent instanceof View ? (View)parent : null;
		}
		Rect rect=new Rect();
		if(!view.getLocalVisibleRect(rect) || !rect.intersect(0, 0, view.getWidth(), view.getHeight()) || rect.isEmpty()) return null;
		int[] screen=new int[2]; view.getLocationOnScreen(screen);
		rect.offset(screen[0], screen[1]);
		return rect;
	}
}
