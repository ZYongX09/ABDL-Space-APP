package org.joinmastodon.android.ui.seamless;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewParent;

import androidx.recyclerview.widget.RecyclerView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
	final Rect bounds(boolean window){
		Rect bounds=screenBounds();
		View view=drawingView();
		if(bounds==null || view==null) return null;
		if(window){
			int[] screen=new int[2], local=new int[2];
			view.getLocationOnScreen(screen); view.getLocationInWindow(local);
			bounds.offset(local[0]-screen[0], local[1]-screen[1]);
		}
		return bounds;
	}
	final Bitmap snapshot(){
		if(!valid()) return null;
		Rect area=screenBounds(); View view=drawingView();
		if(area==null || view==null || (long)area.width()*area.height()>MAX_PIXELS) return null;
		Bitmap bitmap;
		try{ bitmap=Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888); }
		catch(OutOfMemoryError exhausted){ return null; }
		try{
			int[] location=new int[2]; view.getLocationOnScreen(location);
			Canvas canvas=new Canvas(bitmap); canvas.translate(location[0]-area.left, location[1]-area.top);
			view.draw(canvas);
			return bitmap;
		}catch(RuntimeException failure){ bitmap.recycle(); return null; }
	}
	final Integer backgroundColor(){
		View view=drawingView();
		// A card's own color is not the surrounding list color.
		if(this instanceof Card) view=view.getParent() instanceof View ? (View)view.getParent() : null;
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
		view.setTag(R.id.seamless_source_binding, new Binding(account, entity, permitted, status));
	}
	static SeamlessSource card(Activity activity, View view, String account, String entity){
		Object binding=view.getTag(R.id.seamless_source_binding);
		if(!(binding instanceof Binding key) || !key.permitted || !Objects.equals(account, key.account) || !Objects.equals(entity, key.entity)) return null;
		Card source=new Card(activity, view, key);
		return source.valid() ? source : null;
	}
	private record Binding(String account, String entity, boolean permitted, Status status){}
	private static final class Card extends SeamlessSource{
		private final WeakReference<View> view;
		private final Binding binding;
		Card(Activity owner, View view, Binding binding){ super(owner, binding.account, binding.entity); this.view=new WeakReference<>(view); this.binding=binding; }
		@Override View drawingView(){ return view.get(); }
		@Override boolean bindingValid(){ return view.get()!=null && view.get().getTag(R.id.seamless_source_binding)==binding
				&& (binding.status==null || contentAllowed(binding.status)); }
		@Override Rect screenBounds(){ return visible(view.get()); }
		@Override float radius(){ View v=view.get(); return v==null ? 0 : 12f*v.getResources().getDisplayMetrics().density; }
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
		return item.status; // Native novel/album/friend-card rows assign the inherited field.
	}

	static SeamlessSource post(Activity activity, RecyclerView list, String account, Status status){
		if(list==null || !contentAllowed(status) || status.quote!=null) return null; // Nested privacy/crop ambiguity uses ordinary navigation.
		List<Row> rows=new ArrayList<>(); String parent=null; boolean header=false, footer=false;
		for(int i=0;i<list.getChildCount();i++){
			View child=list.getChildAt(i);
			if(!(list.getChildViewHolder(child) instanceof StatusDisplayItem.Holder<?> holder)
					|| !(holder.getItem() instanceof StatusDisplayItem item) || item.isQuote || visible(child)==null) continue;
			Status rowStatus=statusOf(item);
			if(rowStatus==null || !Objects.equals(rowStatus.getContentStatus().id, status.id)) continue;
			if(parent!=null && !parent.equals(item.parentID)) return null; // Ambiguous duplicate/reblog occurrence.
			parent=item.parentID;
			header|=item.getType()==StatusDisplayItem.Type.HEADER || item.getType()==StatusDisplayItem.Type.HEADER_COMPACT;
			footer|=item.getType()==StatusDisplayItem.Type.FOOTER || item.getType()==StatusDisplayItem.Type.EXTENDED_FOOTER;
			if(item.getType()==StatusDisplayItem.Type.AUDIO || item.getType()==StatusDisplayItem.Type.SPOILER
					|| item.getType()==StatusDisplayItem.Type.FILTER_SPOILER) return null;
			rows.add(new Row(child, item));
		}
		// A post is several real rows, not a TEXT view masquerading as a whole card.
		if(rows.size()<2 || !header || !footer) return null;
		Post source=new Post(activity, list, account, status.id, parent, rows);
		return source.valid() ? source : null;
	}
	private static final class Row{
		final WeakReference<View> view;
		final WeakReference<StatusDisplayItem> item;
		final String content;
		final WeakReference<Status> status;
		Row(View view, StatusDisplayItem item){
			this.view=new WeakReference<>(view); this.item=new WeakReference<>(item);
			Status current=statusOf(item); status=new WeakReference<>(current); content=current.getContentStatus().content;
		}
	}
	private static final class Post extends SeamlessSource{
		final WeakReference<RecyclerView> list;
		final List<Row> rows;
		final String parentID;
		Post(Activity owner, RecyclerView list, String account, String entity, String parentID, List<Row> rows){ super(owner, account, entity); this.list=new WeakReference<>(list); this.parentID=parentID; this.rows=rows; }
		@Override View drawingView(){ return list.get(); }
		@Override boolean bitmapOnly(){ return true; }
		@Override boolean bindingValid(){
			RecyclerView parent=list.get();
			if(parent==null || parent.isComputingLayout() || parent.hasPendingAdapterUpdates()
					|| (parent.getItemAnimator()!=null && parent.getItemAnimator().isRunning())) return false;
			// RecyclerView.draw also includes disappearing neighbours; crop bounds alone cannot isolate their pixels.
			for(int i=0;i<parent.getChildCount();i++){
				View child=parent.getChildAt(i);
				if(parent.getChildViewHolder(child) instanceof StatusDisplayItem.Holder<?> holder
						&& holder.getItem() instanceof StatusDisplayItem current && parentID.equals(current.parentID)
						&& rows.stream().noneMatch(row->row.view.get()==child)) return false;
			}
			for(Row row:rows){
				View view=row.view.get(); StatusDisplayItem item=row.item.get();
				Status current=item==null ? null : statusOf(item);
				if(view==null || view.getParent()!=parent || item==null
						|| !(parent.getChildViewHolder(view) instanceof StatusDisplayItem.Holder<?> holder) || holder.getItem()!=item
						|| current==null || current!=row.status.get() || !contentAllowed(current)
						|| !Objects.equals(current.getContentStatus().id, entity)
						|| !Objects.equals(current.getContentStatus().content, row.content)) return false;
			}
			return true;
		}
		@Override Rect screenBounds(){
			Rect result=null;
			for(Row row:rows){
				Rect visible=visible(row.view.get()); if(visible==null) return null;
				if(result==null) result=visible; else result.union(visible);
			}
			return result;
		}
	}

	static Rect visible(View view){
		if(view==null || !view.isAttachedToWindow() || !view.isShown() || view.getWidth()<=0 || view.getHeight()<=0) return null;
		for(View current=view;current!=null;){
			if(current.getAlpha()!=1f || current.getScaleX()!=1f || current.getScaleY()!=1f || current.getRotation()!=0f) return null;
			ViewParent parent=current.getParent(); current=parent instanceof View ? (View)parent : null;
		}
		Rect rect=new Rect();
		if(!view.getLocalVisibleRect(rect) || rect.isEmpty()) return null;
		int[] screen=new int[2]; view.getLocationOnScreen(screen);
		rect.offset(screen[0], screen[1]);
		return rect;
	}
}
