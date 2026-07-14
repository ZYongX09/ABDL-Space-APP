package org.joinmastodon.android.ui.sheets;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.media.MediaAlbum;
import org.joinmastodon.android.ui.media.MediaCameraPreviewView;
import org.joinmastodon.android.ui.media.MediaItem;
import org.joinmastodon.android.ui.media.MediaPickerConfig;
import org.joinmastodon.android.ui.media.MediaStoreLoader;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;
import me.grishka.appkit.views.BottomSheet;

public class MediaPickerSheet extends BottomSheet{
	public interface Listener{
		void onMediaSelected(ArrayList<Uri> uris);
		void onCameraRequested();
	}

	private static final Object SELECTION_PAYLOAD=new Object();
	private final Activity activity;
	private final MediaPickerConfig config;
	private final Listener listener;
	private final ArrayList<MediaAlbum> albums=new ArrayList<>();
	private final ArrayList<MediaItem> displayItems=new ArrayList<>();
	private final ArrayList<MediaCameraPreviewView> cameraPreviews=new ArrayList<>();
	private final HashMap<String, MediaItem> selected=new HashMap<>();
	private final ArrayList<MediaItem> selectedOrder=new ArrayList<>();
	private final GridAdapter adapter=new GridAdapter();
	private final TextView title;
	private final TextView subtitle;
	private final ImageButton send;
	private final RecyclerView grid;
	private final LinearLayout root;
	private final int collapsedGridHeight;
	private int selectedAlbumIndex;
	private boolean resultDelivered;
	private boolean forceDismiss;
	private boolean expanded;
	private float dragStartY;

	public MediaPickerSheet(Activity activity, MediaPickerConfig config, Listener listener){
		super(activity);
		this.activity=activity;
		this.config=config;
		this.listener=listener;

		root=new LinearLayout(activity);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundResource(R.drawable.bg_bottom_sheet);

		View handle=new View(activity);
		handle.setBackgroundResource(R.drawable.bg_bottom_sheet_handle);
		root.addView(handle, new LinearLayout.LayoutParams(-1, V.dp(28)));
		handle.setOnClickListener(v->setExpanded(!expanded));
		handle.setOnTouchListener((v, event)->{
			switch(event.getActionMasked()){
				case android.view.MotionEvent.ACTION_DOWN -> dragStartY=event.getRawY();
				case android.view.MotionEvent.ACTION_UP -> {
					float distance=event.getRawY()-dragStartY;
					if(distance<-V.dp(32))
						setExpanded(true);
					else if(distance>V.dp(32))
						setExpanded(false);
					else
						v.performClick();
				}
			}
			return true;
		});

		FrameLayout header=new FrameLayout(activity);
		root.addView(header, new LinearLayout.LayoutParams(-1, V.dp(52)));
		LinearLayout texts=new LinearLayout(activity);
		texts.setOrientation(LinearLayout.VERTICAL);
		texts.setGravity(Gravity.CENTER_VERTICAL);
		texts.setPadding(V.dp(20), 0, 0, 0);
		header.addView(texts, new FrameLayout.LayoutParams(-1, -1));
		title=new TextView(activity);
		title.setTextSize(18);
		title.setTextColor(color(R.attr.colorM3OnSurface));
		title.setText(R.string.media_picker_all_media);
		texts.addView(title);
		subtitle=new TextView(activity);
		subtitle.setTextSize(13);
		subtitle.setTextColor(color(R.attr.colorM3OnSurfaceVariant));
		subtitle.setVisibility(View.GONE);
		texts.addView(subtitle);
		texts.setOnClickListener(v->showAlbumMenu(texts));

		TextView more=new TextView(activity);
		more.setText("⋮");
		more.setTextSize(30);
		more.setGravity(Gravity.CENTER);
		more.setTextColor(color(R.attr.colorM3OnSurface));
		FrameLayout.LayoutParams moreParams=new FrameLayout.LayoutParams(V.dp(52), -1, Gravity.END);
		header.addView(more, moreParams);
		more.setOnClickListener(v->showSelectionMenu(more));

		grid=new RecyclerView(activity);
		grid.setLayoutManager(new GridLayoutManager(activity, 3));
		grid.setAdapter(adapter);
		grid.setClipToPadding(false);
		collapsedGridHeight=Math.min(activity.getResources().getDisplayMetrics().heightPixels*3/5, V.dp(570));
		root.addView(grid, new LinearLayout.LayoutParams(-1, collapsedGridHeight));

		FrameLayout actions=new FrameLayout(activity);
		actions.setPadding(V.dp(12), V.dp(8), V.dp(12), V.dp(10));
		root.addView(actions, new LinearLayout.LayoutParams(-1, V.dp(68)));
		send=new ImageButton(activity);
		send.setImageResource(R.drawable.ic_check_wght700_24px);
		send.setColorFilter(Color.WHITE);
		GradientDrawable sendBackground=new GradientDrawable();
		sendBackground.setShape(GradientDrawable.OVAL);
		sendBackground.setColor(0xff4f7cff);
		send.setBackground(sendBackground);
		send.setEnabled(false);
		send.setAlpha(0.5f);
		FrameLayout.LayoutParams sendParams=new FrameLayout.LayoutParams(V.dp(50), V.dp(50), Gravity.END|Gravity.CENTER_VERTICAL);
		actions.addView(send, sendParams);
		send.setOnClickListener(v->deliver());

		setContentView(root);
		setNavigationBarBackground(new ColorDrawable(color(R.attr.colorM3Surface)), !UiUtils.isDarkTheme());
		loadMedia();
	}

	private void setExpanded(boolean expanded){
		if(this.expanded==expanded)
			return;
		this.expanded=expanded;
		int screenHeight=activity.getResources().getDisplayMetrics().heightPixels;
		int expandedTopMargin=V.dp(8);
		int collapsedTopMargin=V.dp(72);
		int expandedHeight=Math.max(collapsedGridHeight, screenHeight-expandedTopMargin-V.dp(28+52+68));
		LinearLayout.LayoutParams gridParams=(LinearLayout.LayoutParams)grid.getLayoutParams();
		FrameLayout.LayoutParams sheetParams=(FrameLayout.LayoutParams)content.getLayoutParams();
		int startGridHeight=gridParams.height;
		int startTopMargin=sheetParams.topMargin;
		int targetGridHeight=expanded ? expandedHeight : collapsedGridHeight;
		int targetTopMargin=expanded ? expandedTopMargin : collapsedTopMargin;
		ValueAnimator animator=ValueAnimator.ofFloat(0f, 1f);
		animator.setDuration(220);
		animator.addUpdateListener(value->{
			float progress=(Float)value.getAnimatedValue();
			gridParams.height=startGridHeight+Math.round((targetGridHeight-startGridHeight)*progress);
			sheetParams.topMargin=startTopMargin+Math.round((targetTopMargin-startTopMargin)*progress);
			grid.setLayoutParams(gridParams);
			content.setLayoutParams(sheetParams);
		});
		animator.start();
	}

	private int color(int attr){
		return UiUtils.getThemeColor(activity, attr);
	}

	private void loadMedia(){
		new MediaStoreLoader(activity).load(config, loaded->{
			if(dismissed)
				return;
			albums.clear();
			albums.addAll(loaded);
			selectAlbum(0);
		});
	}

	private void selectAlbum(int index){
		selectedAlbumIndex=Math.max(0, Math.min(index, Math.max(0, albums.size()-1)));
		displayItems.clear();
		if(!albums.isEmpty()){
			MediaAlbum album=albums.get(selectedAlbumIndex);
			displayItems.addAll(album.items);
		}
		adapter.notifyDataSetChanged();
		updateSelectionState();
	}

	private void showAlbumMenu(View anchor){
		PopupMenu menu=new PopupMenu(activity, anchor);
		for(int i=0;i<albums.size();i++){
			MediaAlbum album=albums.get(i);
			menu.getMenu().add(0, i, i, album.id==0 ? activity.getString(R.string.media_picker_all_media) : album.name);
		}
		menu.setOnMenuItemClickListener(item->{ selectAlbum(item.getItemId()); return true; });
		menu.show();
	}

	private void showSelectionMenu(View anchor){
		PopupMenu menu=new PopupMenu(activity, anchor);
		menu.getMenu().add(R.string.media_picker_clear_selection);
		menu.setOnMenuItemClickListener(item->{ clearSelection(); return true; });
		menu.show();
	}

	private void toggle(int adapterPosition){
		if(adapterPosition==0 && showCamera()){
			if(selectedOrder.isEmpty()){
				openCameraAndDismiss();
			}else{
				new M3AlertDialogBuilder(activity)
						.setTitle(R.string.media_picker_discard_title)
						.setMessage(R.string.media_picker_discard_message)
						.setNegativeButton(R.string.cancel, null)
						.setPositiveButton(R.string.discard, (dialog, which)->openCameraAndDismiss())
						.show();
			}
			return;
		}
		int itemPosition=adapterPosition-(showCamera() ? 1 : 0);
		if(itemPosition<0 || itemPosition>=displayItems.size())
			return;
		MediaItem item=displayItems.get(itemPosition);
		int oldIndex=selectedOrder.indexOf(item);
		if(oldIndex>=0){
			selected.remove(item.getKey());
			selectedOrder.remove(oldIndex);
		}else if(selectedOrder.size()<config.maxCount){
			selected.put(item.getKey(), item);
			selectedOrder.add(item);
		}else{
			return;
		}
		adapter.notifyItemChanged(adapterPosition, SELECTION_PAYLOAD);
		if(oldIndex>=0){
			for(int i=oldIndex;i<selectedOrder.size();i++){
				int pos=displayItems.indexOf(selectedOrder.get(i));
				if(pos>=0)
					adapter.notifyItemChanged(pos+(showCamera() ? 1 : 0), SELECTION_PAYLOAD);
			}
		}
		updateSelectionState();
	}

	private void openCameraAndDismiss(){
		releaseGridResources();
		forceDismiss=true;
		super.dismiss();
		grid.postDelayed(listener::onCameraRequested, 300);
	}

	private void releaseGridResources(){
		for(MediaCameraPreviewView preview:cameraPreviews)
			preview.setPreviewEnabled(false);
		cameraPreviews.clear();
		for(int i=0;i<grid.getChildCount();i++){
			RecyclerView.ViewHolder holder=grid.getChildViewHolder(grid.getChildAt(i));
			if(holder instanceof GridHolder gridHolder)
				gridHolder.image.setImageDrawable(null);
		}
		grid.setAdapter(null);
		displayItems.clear();
		albums.clear();
	}

	private void clearSelection(){
		ArrayList<Integer> positions=new ArrayList<>();
		for(MediaItem item:selectedOrder){
			int pos=displayItems.indexOf(item);
			if(pos>=0)
				positions.add(pos+(showCamera() ? 1 : 0));
		}
		selected.clear();
		selectedOrder.clear();
		for(int pos:positions)
			adapter.notifyItemChanged(pos, SELECTION_PAYLOAD);
		updateSelectionState();
	}

	private void updateSelectionState(){
		int count=selectedOrder.size();
		if(count==0){
			if(albums.isEmpty()){
				title.setText(R.string.media_picker_all_media);
			}else{
				MediaAlbum album=albums.get(selectedAlbumIndex);
				title.setText(album.id==0 ? activity.getString(R.string.media_picker_all_media) : album.name);
			}
			subtitle.setVisibility(View.GONE);
		}else{
			title.setText(activity.getResources().getQuantityString(R.plurals.media_picker_selected, count, count));
			subtitle.setText(R.string.media_picker_tap_album);
			subtitle.setVisibility(View.VISIBLE);
		}
		send.setEnabled(count>0);
		send.setAlpha(count>0 ? 1f : 0.5f);
	}

	private boolean showCamera(){
		return selectedAlbumIndex==0 && config.allowImages;
	}

	private void deliver(){
		ArrayList<Uri> uris=new ArrayList<>();
		for(MediaItem item:selectedOrder)
			uris.add(item.uri);
		resultDelivered=true;
		listener.onMediaSelected(uris);
		forceDismiss=true;
		super.dismiss();
	}

	@Override public void dismiss(){
		if(forceDismiss || resultDelivered || selectedOrder.isEmpty()){
			super.dismiss();
			return;
		}
		new M3AlertDialogBuilder(activity)
				.setTitle(R.string.media_picker_discard_title)
				.setMessage(R.string.media_picker_discard_message)
				.setNegativeButton(R.string.cancel, null)
				.setPositiveButton(R.string.discard, (dialog, which)->{
					forceDismiss=true;
					MediaPickerSheet.super.dismiss();
				})
				.show();
	}

	private class GridAdapter extends RecyclerView.Adapter<GridHolder>{
		@Override public GridHolder onCreateViewHolder(ViewGroup parent, int type){
			return new GridHolder(new FrameLayout(parent.getContext()));
		}
		@Override public void onBindViewHolder(GridHolder holder, int position){
			holder.bind(position);
		}
		@Override public void onBindViewHolder(GridHolder holder, int position, List<Object> payloads){
			if(payloads.contains(SELECTION_PAYLOAD))
				holder.bindSelection(position);
			else
				holder.bind(position);
		}
		@Override public void onViewRecycled(GridHolder holder){
			holder.cameraPreview.setPreviewEnabled(false);
			holder.image.setImageDrawable(null);
			super.onViewRecycled(holder);
		}
		@Override public int getItemCount(){ return displayItems.size()+(showCamera() ? 1 : 0); }
	}

	private class GridHolder extends RecyclerView.ViewHolder{
		private final ImageView image;
		private final MediaCameraPreviewView cameraPreview;
		private final ImageView cameraIcon;
		private final TextView badge;
		private MediaItem currentItem;

		GridHolder(FrameLayout cell){
			super(cell);
			int size=(activity.getResources().getDisplayMetrics().widthPixels-V.dp(4))/3;
			cell.setLayoutParams(new RecyclerView.LayoutParams(-1, size));
			image=new ImageView(activity);
			image.setScaleType(ImageView.ScaleType.CENTER_CROP);
			cell.addView(image, new FrameLayout.LayoutParams(-1, -1));
			cameraPreview=new MediaCameraPreviewView(activity);
			cameraPreviews.add(cameraPreview);
			cell.addView(cameraPreview, new FrameLayout.LayoutParams(-1, -1));
			cameraIcon=new ImageView(activity);
			cameraIcon.setImageResource(R.drawable.ic_fluent_camera_28_filled);
			cameraIcon.setColorFilter(Color.WHITE);
			cell.addView(cameraIcon, new FrameLayout.LayoutParams(V.dp(36), V.dp(36), Gravity.CENTER));
			badge=new TextView(activity);
			badge.setGravity(Gravity.CENTER);
			badge.setTextColor(Color.WHITE);
			badge.setTextSize(13);
			badge.setBackgroundColor(0xff4f7cff);
			FrameLayout.LayoutParams badgeParams=new FrameLayout.LayoutParams(V.dp(30), V.dp(30), Gravity.END|Gravity.TOP);
			badgeParams.setMargins(0, V.dp(5), V.dp(5), 0);
			cell.addView(badge, badgeParams);
			cell.setOnClickListener(v->{
				int pos=getBindingAdapterPosition();
				if(pos!=RecyclerView.NO_POSITION)
					toggle(pos);
			});
		}

		void bind(int adapterPosition){
			boolean camera=showCamera() && adapterPosition==0;
			cameraPreview.setPreviewEnabled(camera);
			cameraPreview.setVisibility(camera ? View.VISIBLE : View.GONE);
			cameraIcon.setVisibility(camera ? View.VISIBLE : View.GONE);
			image.setVisibility(camera ? View.GONE : View.VISIBLE);
			if(camera){
				currentItem=null;
				badge.setVisibility(View.GONE);
				return;
			}
			int itemPosition=adapterPosition-(showCamera() ? 1 : 0);
			MediaItem item=displayItems.get(itemPosition);
			currentItem=item;
			image.setImageDrawable(null);
			if(item.video)
				loadVideoThumb(item);
			else
				ViewImageLoader.load(image, null, new UrlImageLoaderRequest(item.uri, V.dp(300), V.dp(300)));
			bindSelection(adapterPosition);
		}

		void bindSelection(int adapterPosition){
			if(showCamera() && adapterPosition==0){
				badge.setVisibility(View.GONE);
				return;
			}
			int itemPosition=adapterPosition-(showCamera() ? 1 : 0);
			if(itemPosition<0 || itemPosition>=displayItems.size())
				return;
			int selectedIndex=selectedOrder.indexOf(displayItems.get(itemPosition));
			badge.setVisibility(View.VISIBLE);
			badge.setText(selectedIndex>=0 ? String.valueOf(selectedIndex+1) : "");
			GradientDrawable background=new GradientDrawable();
			background.setShape(GradientDrawable.OVAL);
			background.setColor(selectedIndex>=0 ? 0xff4f7cff : 0x22000000);
			background.setStroke(V.dp(2), Color.WHITE);
			badge.setBackground(background);
		}

		private void loadVideoThumb(MediaItem item){
			new Thread(()->{
				MediaMetadataRetriever retriever=null;
				try{
					retriever=new MediaMetadataRetriever();
					retriever.setDataSource(activity, item.uri);
					android.graphics.Bitmap bitmap=retriever.getFrameAtTime(0);
					image.post(()->{
						if(currentItem==item)
							image.setImageBitmap(bitmap);
					});
				}catch(Exception ignored){
				}finally{
					if(retriever!=null){
						try{
							retriever.release();
						}catch(Exception ignored){ }
					}
				}
			}, "media-picker-video-thumb").start();
		}
	}
}
