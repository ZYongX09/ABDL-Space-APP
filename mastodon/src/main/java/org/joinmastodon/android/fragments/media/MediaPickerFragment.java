package org.joinmastodon.android.fragments.media;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.media.MediaAlbum;
import org.joinmastodon.android.ui.media.MediaItem;
import org.joinmastodon.android.ui.media.MediaPickerConfig;
import org.joinmastodon.android.ui.media.MediaPickerResult;
import org.joinmastodon.android.ui.media.MediaStoreLoader;

import java.util.ArrayList;
import java.util.HashMap;

import me.grishka.appkit.fragments.ToolbarFragment;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

public class MediaPickerFragment extends ToolbarFragment{
	public static final String KEY_ALBUM_ID="media_picker_album_id";
	private MediaPickerConfig config;
	private long albumId;
	private MediaAlbum album;
	private final HashMap<String, MediaItem> selected=new HashMap<>();
	private final ArrayList<MediaItem> selectedOrder=new ArrayList<>();
	private PickerAdapter adapter;
	private TextView confirm;

	@Override public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		config=MediaPickerConfig.fromBundle(getArguments());
		albumId=getArguments().getLong(KEY_ALBUM_ID);
		setTitle(R.string.media_picker_title);
	}

	@Override public View onCreateContentView(android.view.LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState){
		FrameLayout root=new FrameLayout(getActivity());
		RecyclerView list=new RecyclerView(getActivity());
		int columns=getResources().getConfiguration().orientation==2 ? 4 : 3;
		list.setPadding(V.dp(4), V.dp(4), V.dp(4), V.dp(60));
		list.setClipToPadding(false);
		list.setLayoutManager(new GridLayoutManager(getActivity(), columns));
		adapter=new PickerAdapter();
		list.setAdapter(adapter);
		root.addView(list, new FrameLayout.LayoutParams(-1, -1));
		confirm=new TextView(getActivity());
		confirm.setGravity(Gravity.CENTER);
		confirm.setTextColor(Color.WHITE);
		confirm.setTextSize(16);
		confirm.setBackgroundColor(0xff4f8cc9);
		confirm.setVisibility(View.GONE);
		confirm.setOnClickListener(v->deliverResult());
		root.addView(confirm, new FrameLayout.LayoutParams(-1, V.dp(52), Gravity.BOTTOM));
		new MediaStoreLoader(getActivity()).load(config, albums->{
			if(getActivity()==null)
				return;
			for(MediaAlbum item:albums){
				if(item.id==albumId){
					album=item;
					break;
				}
			}
			if(album==null && albumId==0 && !albums.isEmpty())
				album=albums.get(0);
			adapter.notifyDataSetChanged();
		});
		return root;
	}

	private void toggle(MediaItem item){
		String key=item.getKey();
		if(selected.containsKey(key)){
			selected.remove(key);
			selectedOrder.remove(item);
		}else if(selectedOrder.size()<config.maxCount){
			selected.put(key, item);
			selectedOrder.add(item);
		}
		confirm.setVisibility(selectedOrder.isEmpty() ? View.GONE : View.VISIBLE);
		if(!selectedOrder.isEmpty())
			confirm.setText(getString(R.string.media_picker_confirm, selectedOrder.size()));
		adapter.notifyDataSetChanged();
	}

	private void deliverResult(){
		ArrayList<android.net.Uri> uris=new ArrayList<>();
		for(MediaItem item:selectedOrder)
			uris.add(item.uri);
		setResult(true, MediaPickerResult.create(uris));
		me.grishka.appkit.Nav.finish(this, false);
	}

	private class PickerAdapter extends RecyclerView.Adapter<PickerHolder>{
		@Override public PickerHolder onCreateViewHolder(ViewGroup parent, int type){
			FrameLayout cell=new FrameLayout(parent.getContext());
			return new PickerHolder(cell);
		}
		@Override public void onBindViewHolder(PickerHolder holder, int position){ holder.bind(album.items.get(position)); }
		@Override public int getItemCount(){ return album==null ? 0 : album.items.size(); }
	}

	private class PickerHolder extends RecyclerView.ViewHolder{
		private final ImageView image;
		private final TextView badge;
		private MediaItem currentItem;
		PickerHolder(FrameLayout cell){
			super(cell);
			int size=(getResources().getDisplayMetrics().widthPixels-V.dp(8)-V.dp(8))/3;
			image=new ImageView(cell.getContext());
			image.setScaleType(ImageView.ScaleType.CENTER_CROP);
			cell.addView(image, new FrameLayout.LayoutParams(size, size));
			badge=new TextView(cell.getContext());
			badge.setGravity(Gravity.CENTER);
			badge.setTextColor(Color.WHITE);
			badge.setTextSize(12);
			badge.setBackgroundColor(0xcc4f8cc9);
			FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(V.dp(26), V.dp(26), Gravity.RIGHT|Gravity.TOP);
			bp.setMargins(0, V.dp(4), V.dp(4), 0);
			cell.addView(badge, bp);
			cell.setOnClickListener(v->{
				int pos=getBindingAdapterPosition();
				if(pos!=RecyclerView.NO_POSITION && album!=null)
					toggle(album.items.get(pos));
			});
		}
		void bind(MediaItem item){
			currentItem=item;
			image.setImageBitmap(null);
			if(item.video)
				loadVideoThumbnail(item);
			else
				ViewImageLoader.load(image, null, new UrlImageLoaderRequest(item.uri, 400, 400));
			int index=selectedOrder.indexOf(item);
			badge.setVisibility(index<0 ? View.GONE : View.VISIBLE);
			if(index>=0)
				badge.setText(String.valueOf(index+1));
		}
		private void loadVideoThumbnail(MediaItem item){
			new Thread(()->{
				try{
					Activity activity=getActivity();
					if(activity==null)
						return;
					MediaMetadataRetriever retriever=new MediaMetadataRetriever();
					retriever.setDataSource(activity, item.uri);
					android.graphics.Bitmap bitmap=retriever.getFrameAtTime(0);
					retriever.release();
					image.post(()->{
						if(currentItem==item)
							image.setImageBitmap(bitmap);
					});
				}catch(Exception ignored){ }
			}, "media-video-thumbnail").start();
		}
	}
}
