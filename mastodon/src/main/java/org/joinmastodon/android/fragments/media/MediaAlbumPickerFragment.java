package org.joinmastodon.android.fragments.media;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
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

import me.grishka.appkit.Nav;
import me.grishka.appkit.fragments.ToolbarFragment;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

public class MediaAlbumPickerFragment extends ToolbarFragment{
	private static final int PERMISSION_REQUEST=701;
	private static final int ALBUM_RESULT=702;
	private MediaPickerConfig config;
	private ArrayList<MediaAlbum> albums=new ArrayList<>();
	private AlbumAdapter adapter;
	private TextView emptyView;
	private boolean loading;

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		config=MediaPickerConfig.fromBundle(getArguments());
		setTitle(R.string.media_picker_albums);
	}

	@Override
	public View onCreateContentView(android.view.LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState){
		FrameLayout root=new FrameLayout(getActivity());
		RecyclerView list=new RecyclerView(getActivity());
		list.setPadding(V.dp(8), V.dp(8), V.dp(8), V.dp(8));
		list.setClipToPadding(false);
		list.setLayoutManager(new GridLayoutManager(getActivity(), 2));
		adapter=new AlbumAdapter();
		list.setAdapter(adapter);
		root.addView(list, new FrameLayout.LayoutParams(-1, -1));
		emptyView=new TextView(getActivity());
		emptyView.setGravity(Gravity.CENTER);
		emptyView.setTextColor(getThemeColor(R.attr.colorM3OnSurfaceVariant));
		emptyView.setVisibility(View.GONE);
		root.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
		loadAlbums();
		return root;
	}

	private int getThemeColor(int attr){
		android.util.TypedValue value=new android.util.TypedValue();
		getActivity().getTheme().resolveAttribute(attr, value, true);
		return value.data;
	}

	private void loadAlbums(){
		loading=true;
		if(!new MediaStoreLoader(getActivity()).hasPermission(config)){
			requestMediaPermission();
			return;
		}
		new MediaStoreLoader(getActivity()).load(config, result->{
			if(getActivity()==null)
				return;
			loading=false;
			albums=result;
			emptyView.setText(result.isEmpty() ? R.string.media_picker_no_media : R.string.media_picker_no_media);
			emptyView.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
			adapter.notifyDataSetChanged();
		});
	}

	private void requestMediaPermission(){
		if(Build.VERSION.SDK_INT<23)
			return;
		if(Build.VERSION.SDK_INT>=33){
			ArrayList<String> permissions=new ArrayList<>();
			if(config.allowImages)
				permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
			if(config.allowVideos)
				permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
			requestPermissions(permissions.toArray(new String[0]), PERMISSION_REQUEST);
		}else{
			requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if(requestCode==PERMISSION_REQUEST)
			loadAlbums();
	}

	@Override
	public void onFragmentResult(int reqCode, boolean success, Bundle result){
		super.onFragmentResult(reqCode, success, result);
		if(reqCode==ALBUM_RESULT && success){
			setResult(true, result);
			Nav.finish(this, false);
		}
	}

	private class AlbumAdapter extends RecyclerView.Adapter<AlbumHolder>{
		@Override public AlbumHolder onCreateViewHolder(ViewGroup parent, int viewType){
			FrameLayout cell=new FrameLayout(parent.getContext());
			cell.setPadding(0, 0, V.dp(4), V.dp(8));
			return new AlbumHolder(cell);
		}
		@Override public void onBindViewHolder(AlbumHolder holder, int position){ holder.bind(albums.get(position)); }
		@Override public int getItemCount(){ return albums.size(); }
	}

	private class AlbumHolder extends RecyclerView.ViewHolder{
		private final ImageView image;
		private final TextView title;
		private final TextView count;
		AlbumHolder(FrameLayout cell){
			super(cell);
			image=new ImageView(cell.getContext());
			image.setScaleType(ImageView.ScaleType.CENTER_CROP);
			cell.addView(image, new FrameLayout.LayoutParams(-1, V.dp(150)));
			title=new TextView(cell.getContext());
			title.setTextColor(Color.WHITE);
			title.setTextSize(14);
			title.setGravity(Gravity.BOTTOM);
			title.setPadding(V.dp(8), 0, V.dp(8), V.dp(8));
			FrameLayout.LayoutParams titleParams=new FrameLayout.LayoutParams(-1, V.dp(48), Gravity.BOTTOM);
			cell.addView(title, titleParams);
			count=new TextView(cell.getContext());
			count.setTextColor(Color.WHITE);
			count.setTextSize(12);
			count.setGravity(Gravity.RIGHT|Gravity.BOTTOM);
			count.setPadding(0, 0, V.dp(8), V.dp(8));
			cell.addView(count, titleParams);
			cell.setOnClickListener(v->{
				MediaAlbum album=albums.get(getBindingAdapterPosition());
				Bundle args=config.toBundle();
				args.putLong(MediaPickerFragment.KEY_ALBUM_ID, album.id);
				Nav.goForResult(getActivity(), MediaPickerFragment.class, args, ALBUM_RESULT, MediaAlbumPickerFragment.this);
			});
		}
		void bind(MediaAlbum album){
			title.setText(album.name);
			count.setText(String.valueOf(album.items.size()));
			MediaItem cover=album.cover;
			if(cover!=null)
				ViewImageLoader.load(image, null, new UrlImageLoaderRequest(cover.uri, V.dp(300), V.dp(300)));
		}
	}
}
