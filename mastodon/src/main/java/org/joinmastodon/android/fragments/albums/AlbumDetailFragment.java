package org.joinmastodon.android.fragments.albums;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.joinmastodon.android.R;
import org.joinmastodon.android.albums.AlbumPhotoViewer;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.albums.AlbumRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.albums.AlbumModels.*;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.LoaderFragment;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

/** Album metadata and local-day/second photo groups, revalidated each time the page becomes visible. */
public class AlbumDetailFragment extends LoaderFragment{
	private String accountId, albumId;
	private AccountSession session;
	private Album album;
	private final List<Photo> photos=new ArrayList<>();
	private final List<Row> rows=new ArrayList<>();
	private final List<MastodonAPIRequest<?>> requests=new ArrayList<>();
	private RecyclerView grid;
	private PhotoAdapter adapter;
	private ImageView cover;
	private TextView name, meta, stateText;
	private Button group, settings, invite, members, upload, more;
	private View body;
	private AlertDialog dialog;
	private int generation, offset;
	private boolean bySecond, hasMore, pageLoading, busy, fresh;

	@Override public void onCreate(Bundle state){
		super.onCreate(state);
		Bundle args=getArguments()==null ? new Bundle() : getArguments();
		accountId=args.getString("account"); albumId=args.getString("albumId", args.getString("album_id"));
		session=AccountSessionManager.getInstance().tryGetAccount(accountId);
		bySecond=state!=null && state.getBoolean("bySecond"); setTitle(R.string.album_title);
	}
	@Override public void onSaveInstanceState(Bundle state){ super.onSaveInstanceState(state); state.putBoolean("bySecond", bySecond); }
	@Override public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle state){
		body=inflater.inflate(R.layout.album_detail, container, false);
		cover=body.findViewById(R.id.album_cover); AlbumUi.rounded(cover);
		name=body.findViewById(R.id.album_name); meta=body.findViewById(R.id.album_meta); stateText=body.findViewById(R.id.album_state);
		group=body.findViewById(R.id.album_group); settings=body.findViewById(R.id.album_settings); invite=body.findViewById(R.id.album_invite);
		members=body.findViewById(R.id.album_members); upload=body.findViewById(R.id.album_upload); more=body.findViewById(R.id.album_more);
		grid=body.findViewById(R.id.album_grid); GridLayoutManager manager=new GridLayoutManager(getActivity(), 3);
		manager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup(){ @Override public int getSpanSize(int position){ return position<rows.size() && rows.get(position).header!=null ? 3 : 1; } });
		grid.setLayoutManager(manager); adapter=new PhotoAdapter(); grid.setAdapter(adapter);
		group.setOnClickListener(v->{ bySecond=!bySecond; rebuildRows(); render(); });
		settings.setOnClickListener(v->edit()); invite.setOnClickListener(v->confirmInvite()); members.setOnClickListener(v->manageMembers());
		upload.setOnClickListener(v->openUpload()); more.setOnClickListener(v->loadPhotos(false)); body.findViewById(R.id.album_refresh).setOnClickListener(v->refresh());
		grid.addOnScrollListener(new RecyclerView.OnScrollListener(){ @Override public void onScrolled(RecyclerView list, int dx, int dy){
			if(dy>0 && hasMore && !pageLoading && fresh && !busy && manager.findLastVisibleItemPosition()>=rows.size()-9) loadPhotos(false);
		} });
		render(); return body;
	}
	@Override protected void onShown(){ super.onShown(); if(grid!=null && !pageLoading && !busy) refresh(); }
	@Override protected void onHidden(){
		super.onHidden();
		// No authorization or private previews are treated as a persistent membership grant.
		invalidateData();
	}
	@Override public void onRefresh(){ refresh(); }
	public void refresh(){ if(grid!=null && !pageLoading && !busy) loadData(); }
	@Override protected void doLoadData(){
		if(grid==null || pageLoading || busy) return;
		invalidateData();
		if(!sessionValid() || albumId==null || !albumId.matches("[A-Za-z0-9_-]{1,128}")){
			dataLoading=false; onError(new MastodonErrorResponse(getString(R.string.album_session_changed), 401, null)); return;
		}
		pageLoading=true; dataLoading=true; render(); int token=generation;
		execute(AlbumRequest.get("/"+albumId, DetailResponse.class), token, result->{
			if(!validAlbum(result.album)){ fail(invalid()); return; }
			album=result.album; fresh=true; pageLoading=false; render(); loadPhotos(true);
		}, this::fail);
	}
	private void loadPhotos(boolean first){
		if(pageLoading || busy || !fresh || !sessionValid() || grid==null) return;
		pageLoading=true; if(first){ offset=0; photos.clear(); rows.clear(); adapter.notifyDataSetChanged(); }
		int requestedOffset=offset, token=generation; render();
		execute(AlbumRequest.get("/"+albumId+"/photos", PhotoResponse.class).query("limit", 60).query("offset", requestedOffset), token, result->{
			if(result.photos==null || result.photos.size()>60 || (result.hasMore && result.photos.isEmpty())){ fail(invalid()); return; }
			for(Photo photo:result.photos) if(photo==null || photo.id==null || !albumId.equals(photo.albumId) || photo.previewUrl==null || photo.sortAt<0){ fail(invalid()); return; }
			Set<String> ids=new HashSet<>(); for(Photo previous:photos) ids.add(previous.id);
			for(Photo photo:result.photos) if(ids.add(photo.id)) photos.add(photo);
			offset=requestedOffset+result.photos.size(); hasMore=result.hasMore; pageLoading=false; dataLoading=false; dataLoaded(); rebuildRows(); render();
		}, this::fail);
	}
	private void fail(ErrorResponse error){
		pageLoading=false; dataLoading=false;
		if(error instanceof MastodonErrorResponse response && (response.httpStatus==401 || response.httpStatus==403 || response.httpStatus==404)){
			invalidateData(); onError(error); return;
		}
		render(); if(photos.isEmpty()) onError(error); else{ error.showToast(getActivity()); more.setVisibility(View.VISIBLE); more.setText(R.string.album_retry); }
	}
	private void invalidateData(){
		generation++; for(MastodonAPIRequest<?> request:requests) request.cancel(); requests.clear();
		pageLoading=false; busy=false; dataLoading=false; fresh=false; album=null; photos.clear(); rows.clear(); hasMore=false; offset=0;
		if(adapter!=null) adapter.notifyDataSetChanged();
		if(dialog!=null){ dialog.dismiss(); dialog=null; }
		render();
	}
	private boolean sessionValid(){ return session!=null && accountId.equals(AccountSessionManager.getInstance().getLastActiveAccountID()) && AccountSessionManager.getInstance().tryGetAccount(accountId)==session; }
	private boolean live(int token){ return token==generation && grid!=null && getActivity()!=null && sessionValid(); }
	private boolean owner(){ return fresh && album!=null && album.isOwner && sessionValid(); }
	private boolean validAlbum(Album result){ return result!=null && albumId.equals(result.id) && result.name!=null && AlbumUi.VISIBILITIES.contains(result.visibility) && result.photoCount>=0; }
	private ErrorResponse invalid(){ return new MastodonErrorResponse(getString(R.string.album_invalid_response), 0, null); }
	private void render(){
		if(grid==null) return;
		name.setText(album==null ? getString(R.string.album_title) : album.name); meta.setText(album==null ? "" : getString(R.string.album_count, album.photoCount, AlbumUi.visibility(getActivity(), album.visibility)));
		cover.setImageDrawable(AlbumUi.gradient(getActivity(), albumId));
		if(album!=null && album.coverUrl!=null) ViewImageLoader.loadWithoutAnimation(cover, cover.getDrawable(), new UrlImageLoaderRequest(album.coverUrl, V.dp(80), V.dp(80)));
		group.setText(bySecond ? R.string.album_group_second : R.string.album_group_day); group.setEnabled(fresh);
		settings.setVisibility(owner() ? View.VISIBLE : View.GONE); settings.setEnabled(!busy && !pageLoading);
		invite.setVisibility(owner() && "shared".equals(album.visibility) ? View.VISIBLE : View.GONE); invite.setEnabled(!busy && !pageLoading);
		members.setVisibility(fresh && album!=null && (owner() || "shared".equals(album.visibility)) ? View.VISIBLE : View.GONE);
		members.setText(owner() ? R.string.album_members : R.string.album_leave); members.setEnabled(!busy && !pageLoading);
		upload.setVisibility(owner() && album.canUpload ? View.VISIBLE : View.GONE); upload.setEnabled(!busy && !pageLoading);
		stateText.setText(pageLoading ? R.string.album_loading : owner() ? R.string.album_empty_photos : R.string.album_empty_photos_visitor); stateText.setVisibility(photos.isEmpty() ? View.VISIBLE : View.GONE);
		more.setVisibility(hasMore ? View.VISIBLE : View.GONE); more.setEnabled(!pageLoading && !busy && fresh); more.setText(pageLoading ? R.string.album_loading : R.string.album_more);
	}
	private void rebuildRows(){
		rows.clear(); String previous=null;
		for(int i=0;i<photos.size();i++){
			Photo photo=photos.get(i); long time=photo.sortAt>0 ? photo.sortAt : photo.capturedAt!=null ? photo.capturedAt : photo.uploadedAt;
			String heading=groupLabel(time, bySecond, ZoneId.systemDefault());
			if(!heading.equals(previous)){ rows.add(new Row(heading, -1)); previous=heading; }
			rows.add(new Row(null, i));
		}
		if(adapter!=null) adapter.notifyDataSetChanged();
	}
	static String groupLabel(long seconds, boolean bySecond, ZoneId zone){
		return (bySecond ? DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") : DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
				.withZone(zone).format(Instant.ofEpochSecond(seconds));
	}
	private void openUpload(){
		if(!owner() || !album.canUpload || busy || pageLoading) return;
		Bundle args=new Bundle(); args.putString("account", accountId); args.putString("albumId", album.id); Nav.go(getActivity(), AlbumUploadFragment.class, args);
	}
	private void edit(){
		if(!owner() || busy || pageLoading) return;
		dialog=AlbumUi.edit(getActivity(), album, values->{
			busy=true; render(); int token=generation;
			execute(AlbumRequest.patch("/"+albumId, DetailResponse.class, Map.of("name", values.name, "visibility", values.visibility)), token, result->{
				busy=false;
				if(!validAlbum(result.album)){ invalid().showToast(getActivity()); render(); return; }
				refresh();
			}, error->{ busy=false; render(); error.showToast(getActivity()); });
		}, this::confirmDeleteAlbum);
	}
	private void confirmDeleteAlbum(){
		if(!owner() || album.isDefault || busy || pageLoading) return;
		dialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_delete_album).setMessage(R.string.album_delete_album_help)
				.setPositiveButton(R.string.album_delete_action, (d, which)->deleteAlbum()).setNegativeButton(R.string.cancel, null).show();
	}
	private void deleteAlbum(){
		if(!owner() || album.isDefault || busy) return;
		busy=true; render(); int token=generation;
		execute(AlbumRequest.delete("/"+albumId, DeleteResponse.class), token, result->{
			busy=false; if(!result.deleted){ invalid().showToast(getActivity()); render(); return; }
			invalidateData(); Nav.finish(this);
		}, error->{ busy=false; render(); error.showToast(getActivity()); });
	}
	private void confirmDeletePhoto(Photo photo){
		if(!owner() || !photo.isOwner || busy || pageLoading) return;
		dialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_delete_photo).setMessage(R.string.album_delete_photo_help)
				.setPositiveButton(R.string.album_delete_action, (d, which)->deletePhoto(photo)).setNegativeButton(R.string.cancel, null).show();
	}
	private void deletePhoto(Photo photo){
		if(!owner() || !photo.isOwner || busy || !photos.contains(photo)) return;
		busy=true; render(); int token=generation;
		execute(AlbumRequest.delete("/photos/"+photo.id, DeleteResponse.class), token, result->{
			busy=false; if(!result.deleted){ invalid().showToast(getActivity()); render(); return; } refresh();
		}, error->{ busy=false; render(); error.showToast(getActivity()); });
	}
	private void confirmInvite(){
		if(!owner() || !"shared".equals(album.visibility) || busy) return;
		dialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_invite).setMessage(R.string.album_invite_help)
				.setPositiveButton(R.string.album_invite_rotate, (d, which)->createInvite()).setNegativeButton(R.string.cancel, null).show();
	}
	private void createInvite(){
		if(!owner() || busy || !"shared".equals(album.visibility)) return;
		busy=true; render(); int token=generation;
		execute(AlbumRequest.post("/"+albumId+"/invites", Invite.class, Map.of()), token, result->{
			busy=false; render();
			if(result.url==null || result.token==null || !result.token.equals(AlbumUi.inviteToken(result.url)) || result.expiresAt<=System.currentTimeMillis()/1000){ invalid().showToast(getActivity()); return; }
			showInvite(result);
		}, error->{ busy=false; render(); error.showToast(getActivity()); });
	}
	private void showInvite(Invite invitation){
		LinearLayout root=AlbumUi.column(getActivity()); root.setPadding(V.dp(24), 0, V.dp(24), 0);
		ImageView qr=new ImageView(getActivity()); qr.setScaleType(ImageView.ScaleType.FIT_CENTER); qr.setContentDescription(getString(R.string.album_invite));
		try{
			BitMatrix matrix=new QRCodeWriter().encode(invitation.url, BarcodeFormat.QR_CODE, 512, 512, Map.of(EncodeHintType.MARGIN, 2));
			int[] pixels=new int[512*512]; for(int y=0;y<512;y++) for(int x=0;x<512;x++) pixels[y*512+x]=matrix.get(x,y) ? 0xff000000 : 0xffffffff;
			Bitmap bitmap=Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888); qr.setImageBitmap(bitmap);
		}catch(WriterException error){ invalid().showToast(getActivity()); return; }
		root.addView(qr, new LinearLayout.LayoutParams(-1, V.dp(240)));
		AlbumUi.label(root, getString(R.string.album_invite_expires, AlbumUi.date(invitation.expiresAt)), false);
		AlbumUi.button(root, R.string.album_copy, ()->{
			if(!inviteValid(invitation)) return;
			ClipboardManager clipboard=getActivity().getSystemService(ClipboardManager.class); if(clipboard!=null) clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.album_invite), invitation.url));
			Toast.makeText(getActivity(), R.string.album_copied, Toast.LENGTH_SHORT).show();
		});
		AlbumUi.button(root, R.string.album_share, ()->{
			if(!inviteValid(invitation)) return;
			Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invitation.url);
			try{ startActivity(Intent.createChooser(share, getString(R.string.album_share))); }catch(RuntimeException unavailable){ invalid().showToast(getActivity()); }
		});
		dialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_invite).setView(root).setPositiveButton(R.string.ok, null).show();
	}
	private boolean inviteValid(Invite invitation){
		if(!owner() || !"shared".equals(album.visibility) || invitation.expiresAt<=System.currentTimeMillis()/1000){ Toast.makeText(getActivity(), R.string.album_invite_expired, Toast.LENGTH_LONG).show(); return false; } return true;
	}
	private void manageMembers(){
		if(!fresh || album==null || busy) return;
		if(!owner()){
			dialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_leave).setMessage(R.string.album_leave_help)
					.setPositiveButton(R.string.album_leave, (d, which)->removeMember(Long.parseLong(session.self.id), true)).setNegativeButton(R.string.cancel, null).show(); return;
		}
		busy=true; render(); int token=generation;
		execute(AlbumRequest.get("/"+albumId+"/members", MembersResponse.class), token, result->{
			busy=false; render();
			if(result.members==null){ invalid().showToast(getActivity()); return; }
			List<Member> removable=new ArrayList<>(); for(Member member:result.members) if(member!=null && member.userId!=album.ownerId && member.username!=null) removable.add(member);
			if(removable.isEmpty()){ dialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_members).setMessage(R.string.album_members_empty).setPositiveButton(R.string.ok, null).show(); return; }
			String[] names=new String[removable.size()]; for(int i=0;i<names.length;i++) names[i]=removable.get(i).username;
			dialog=new M3AlertDialogBuilder(getActivity()).setTitle(getString(R.string.album_members_count, names.length)).setItems(names, (d, index)->{
				Member selected=removable.get(index);
				dialog=new M3AlertDialogBuilder(getActivity()).setTitle(getString(R.string.album_member_remove, selected.username)).setMessage(R.string.album_member_remove_help)
						.setPositiveButton(R.string.album_member_remove_action, (confirmation, which)->removeMember(selected.userId, false)).setNegativeButton(R.string.cancel, null).show();
			}).setNegativeButton(R.string.cancel, null).show();
		}, error->{ busy=false; render(); error.showToast(getActivity()); });
	}
	private void removeMember(long userId, boolean leaving){
		if(busy || !fresh || !sessionValid() || (!leaving && !owner())) return;
		busy=true; render(); int token=generation;
		execute(AlbumRequest.delete("/"+albumId+"/members/"+userId, Object.class), token, result->{
			busy=false;
			if(leaving){ invalidateData(); Nav.finish(this); } else refresh();
		}, error->{ busy=false; render(); error.showToast(getActivity()); });
	}
	private <T> void execute(AlbumRequest<T> request, int token, Consumer<T> success, Consumer<ErrorResponse> error){
		requests.add(request); request.setCallback(new Callback<>(){
			@Override public void onSuccess(T result){ requests.remove(request); if(live(token)) success.accept(result); }
			@Override public void onError(ErrorResponse response){ requests.remove(request); if(live(token)) error.accept(response); }
		}).exec(accountId);
	}
	@Override public void onApplyWindowInsets(WindowInsets insets){
		if(grid!=null) grid.setPadding(V.dp(6)+insets.getSystemWindowInsetLeft(), 0, V.dp(6)+insets.getSystemWindowInsetRight(), V.dp(88)+insets.getSystemWindowInsetBottom());
		if(upload!=null){ FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)upload.getLayoutParams(); params.bottomMargin=V.dp(16)+insets.getSystemWindowInsetBottom(); upload.setLayoutParams(params); }
		super.onApplyWindowInsets(insets.replaceSystemWindowInsets(0, insets.getSystemWindowInsetTop(), 0, 0));
	}
	@Override public void onDestroyView(){
		invalidateData(); if(grid!=null) grid.setAdapter(null);
		grid=null; adapter=null; body=null; cover=null; name=null; meta=null; stateText=null; group=null; settings=null; invite=null; members=null; upload=null; more=null;
		super.onDestroyView();
	}
	private static class Row{ final String header; final int photoIndex; Row(String header, int index){ this.header=header; photoIndex=index; } }
	private class PhotoAdapter extends RecyclerView.Adapter<PhotoHolder>{
		@Override public int getItemViewType(int position){ return rows.get(position).header==null ? 1 : 0; }
		@Override public PhotoHolder onCreateViewHolder(ViewGroup parent, int type){
			if(type==0){ TextView heading=new TextView(parent.getContext()); heading.setTextAppearance(R.style.m3_title_small); heading.setTextColor(UiUtils.getThemeColor(parent.getContext(), R.attr.colorM3OnSurface)); heading.setPadding(V.dp(10), V.dp(18), V.dp(10), V.dp(10)); heading.setLayoutParams(new RecyclerView.LayoutParams(-1, -2)); return new PhotoHolder(heading); }
			return new PhotoHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.album_photo, parent, false));
		}
		@Override public void onBindViewHolder(PhotoHolder holder, int position){
			Row row=rows.get(position);
			if(row.header!=null){ ((TextView)holder.itemView).setText(row.header); return; }
			Photo photo=photos.get(row.photoIndex); holder.image.setImageDrawable(AlbumUi.gradient(getActivity(), photo.id));
			ViewImageLoader.loadWithoutAnimation(holder.image, holder.image.getDrawable(), new UrlImageLoaderRequest(photo.previewUrl, V.dp(140), V.dp(140)));
			holder.itemView.setContentDescription(getString(R.string.album_photo_accessibility, groupLabel(photo.sortAt, true, ZoneId.systemDefault()), row.photoIndex+1));
				holder.itemView.setOnClickListener(v->{ if(fresh && sessionValid()) AlbumPhotoViewer.open(getActivity(), accountId, new ArrayList<>(photos), row.photoIndex); });
				holder.itemView.setOnLongClickListener(v->{ if(!owner() || !photo.isOwner) return false; confirmDeletePhoto(photo); return true; });
		}
		@Override public int getItemCount(){ return rows.size(); }
	}
	private static class PhotoHolder extends RecyclerView.ViewHolder{
		final ImageView image;
		PhotoHolder(View view){ super(view); image=view.findViewById(R.id.album_photo); if(image!=null) AlbumUi.rounded(view); }
	}
}
