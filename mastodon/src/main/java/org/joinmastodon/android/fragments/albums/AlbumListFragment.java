package org.joinmastodon.android.fragments.albums;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.albums.AlbumRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.googleservices.barcodescanner.Barcode;
import org.joinmastodon.android.googleservices.barcodescanner.BarcodeScanner;
import org.joinmastodon.android.model.albums.AlbumModels.*;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.utils.UiUtils;

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

/** Two-column profile tab and standalone invitation entry. Owner data is never inferred locally. */
public class AlbumListFragment extends LoaderFragment{
	private static final int SCAN_RESULT=7801;
	protected String accountId, ownerId;
	protected AccountSession session;
	private final List<Album> albums=new ArrayList<>();
	private final List<MastodonAPIRequest<?>> requests=new ArrayList<>();
	private RecyclerView recyclerView;
	private AlbumAdapter adapter;
	private TextView stateText, quotaText;
	private Button more, create, importButton, join;
	private int generation, offset;
	private boolean hasMore, loadingPage, busy, importedAutomatically, importRemaining, refreshPending;
	private boolean joining, profileVisible;
	private String pendingInvite;
	private AlertDialog dialog;

	@Override public void onCreate(Bundle state){
		super.onCreate(state);
		Bundle args=getArguments()==null ? new Bundle() : getArguments();
		accountId=args.getString("account"); session=AccountSessionManager.getInstance().tryGetAccount(accountId);
		Object owner=args.get("ownerId"); if(owner==null) owner=args.get("owner_id");
		ownerId=owner==null ? session==null || session.self==null ? "" : session.self.id : String.valueOf(owner);
		pendingInvite=args.getString("invite_token", args.getString("invite_url"));
		if(state!=null){ pendingInvite=state.getString("pendingInvite", pendingInvite); importedAutomatically=state.getBoolean("importAttempted"); }
		setTitle(isSelection() ? R.string.album_select_title : R.string.album_title);
	}
	@Override public void onSaveInstanceState(Bundle state){
		super.onSaveInstanceState(state); state.putString("pendingInvite", pendingInvite); state.putBoolean("importAttempted", importedAutomatically);
	}
	protected boolean isSelection(){ return false; }
	protected boolean includeAlbum(Album album){ return true; }
	protected boolean ownProfile(){ return session!=null && session.self!=null && session.self.id.equals(ownerId); }
	private boolean sessionValid(){ return session!=null && accountId.equals(AccountSessionManager.getInstance().getLastActiveAccountID()) && AccountSessionManager.getInstance().tryGetAccount(accountId)==session; }
	private boolean embedded(){ return getArguments()!=null && getArguments().getBoolean("__is_tab", false); }
	private boolean visibleForReads(){ return !embedded() || profileVisible; }
	private boolean live(int token){ return generation==token && visibleForReads() && recyclerView!=null && getActivity()!=null && sessionValid(); }
	/** Profile's custom pager must explicitly bridge parent visibility, not child Fragment.resume. */
	public void setProfileVisible(boolean visible){
		if(!embedded() || profileVisible==visible) return;
		profileVisible=visible;
		if(!visible) clearPrivateRows();
		else if(recyclerView!=null){ refreshPending=true; refresh(); }
	}
	public RecyclerView getRecyclerView(){ return recyclerView; }
	public void scrollToTop(){ if(recyclerView!=null) recyclerView.scrollToPosition(0); }
	public void refresh(){
		refreshPending=true;
		if(recyclerView!=null && visibleForReads() && !loadingPage && !busy){ refreshPending=false; loadData(); }
	}
	@Override public void onRefresh(){ refresh(); }
	@Override public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle state){
		View root=inflater.inflate(R.layout.album_list, container, false);
		recyclerView=root.findViewById(R.id.album_grid); recyclerView.setLayoutManager(new GridLayoutManager(getActivity(), 2));
		adapter=new AlbumAdapter(); recyclerView.setAdapter(adapter);
		stateText=root.findViewById(R.id.album_state); quotaText=root.findViewById(R.id.album_quota); more=root.findViewById(R.id.album_more);
		create=root.findViewById(R.id.album_new); importButton=root.findViewById(R.id.album_import); join=root.findViewById(R.id.album_join);
		root.findViewById(R.id.album_refresh).setOnClickListener(v->refresh()); create.setOnClickListener(v->createAlbum());
		join.setOnClickListener(v->showJoin()); importButton.setOnClickListener(v->confirmImport()); more.setOnClickListener(v->loadPage(false));
		create.setVisibility(ownProfile() ? View.VISIBLE : View.GONE); importButton.setVisibility(ownProfile() && !isSelection() ? View.VISIBLE : View.GONE);
		recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener(){
			@Override public void onScrolled(RecyclerView list, int dx, int dy){
				if(dy>0 && hasMore && !loadingPage && !busy && ((GridLayoutManager)list.getLayoutManager()).findLastVisibleItemPosition()>=albums.size()-6) loadPage(false);
			}
		});
		renderState(); return root;
	}
	@Override public void onViewCreated(View view, Bundle state){
		super.onViewCreated(view, state);
		if(getArguments()==null || !getArguments().getBoolean("noAutoLoad", false)) view.post(()->{ if(recyclerView!=null && visibleForReads() && !loaded && !dataLoading) loadData(); });
	}
	@Override protected void onShown(){
		super.onShown();
		if(recyclerView==null || !visibleForReads()) return;
		if(!loadingPage && !busy && (refreshPending || loaded || getArguments()==null || !getArguments().getBoolean("noAutoLoad", false))) refresh();
		if(pendingInvite!=null && !joining){ String invite=pendingInvite; pendingInvite=null; confirmJoin(invite); }
	}
	@Override protected void onHidden(){ super.onHidden(); if(!embedded()) clearPrivateRows(); }
	private void clearPrivateRows(){
		generation++; for(MastodonAPIRequest<?> request:requests) request.cancel(); requests.clear();
		loadingPage=false; busy=false; joining=false; dataLoading=false; refreshPending=true; albums.clear(); hasMore=false;
		if(recyclerView!=null){ recyclerView.setVisibility(View.INVISIBLE); recyclerView.setAdapter(null); recyclerView.setAdapter(adapter); }
		if(adapter!=null) adapter.notifyDataSetChanged(); if(quotaText!=null){ quotaText.setText(""); quotaText.setVisibility(View.GONE); }
		if(dialog!=null){ dialog.dismiss(); dialog=null; } renderState();
	}
	@Override protected void doLoadData(){ loadPage(true); }
	private void loadPage(boolean first){
		if(loadingPage || busy || recyclerView==null || !visibleForReads()) return;
		if(!sessionValid()){ dataLoading=false; onError(new MastodonErrorResponse(getString(R.string.album_session_changed), 401, null)); return; }
		if(!ownerId.matches("[0-9]+")){ dataLoading=false; onError(new MastodonErrorResponse(getString(R.string.album_invalid_response), 0, null)); return; }
		loadingPage=true; dataLoading=true;
		if(first){ generation++; offset=0; albums.clear(); adapter.notifyDataSetChanged(); } // Drop stale private rows immediately, before refresh.
		int token=generation, requestedOffset=offset;
		renderState();
		AlbumRequest<ListResponse> request=AlbumRequest.get("/", ListResponse.class).query("owner_id", ownerId).query("limit", 30).query("offset", requestedOffset);
		execute(request, token, result->{
			if(result.albums==null || result.albums.size()>30 || (result.hasMore && result.albums.isEmpty())){ failPage(invalid()); return; }
			for(Album album:result.albums) if(!valid(album)){ failPage(invalid()); return; }
			Set<String> ids=new HashSet<>(); for(Album existing:albums) ids.add(existing.id);
			for(Album album:result.albums) if(includeAlbum(album) && ids.add(album.id)) albums.add(album);
				offset=requestedOffset+result.albums.size(); hasMore=result.hasMore; loadingPage=false; dataLoading=false; dataLoaded(); recyclerView.setVisibility(View.VISIBLE); adapter.notifyDataSetChanged(); renderState();
			if(first && ownProfile()){
				loadQuota(token);
				if(!isSelection() && !importedAutomatically){ importedAutomatically=true; importHistory(false); }
			}
			if(refreshPending){ refreshPending=false; refresh(); }
		}, this::failPage);
	}
	private void failPage(ErrorResponse error){
		loadingPage=false; dataLoading=false; renderState();
		if(albums.isEmpty()) onError(error); else{ error.showToast(getActivity()); more.setText(R.string.album_retry); more.setVisibility(View.VISIBLE); }
	}
	private void loadQuota(int token){
		execute(AlbumRequest.get("/quota", StorageQuota.class), token, quota->{
			if(quota.limitBytes<0 || quota.usedBytes<0 || quota.reservedBytes<0 || quota.remainingBytes<0) return;
			quotaText.setText(getString(R.string.album_quota, UiUtils.formatFileSize(getActivity(), quota.usedBytes, false), UiUtils.formatFileSize(getActivity(), quota.limitBytes, false), UiUtils.formatFileSize(getActivity(), quota.remainingBytes, false)));
			quotaText.setVisibility(View.VISIBLE);
		}, error->error.showToast(getActivity()));
	}
	protected void onAlbumClicked(Album album){ openAlbum(album); }
	private void openAlbum(Album album){
		Bundle args=new Bundle(); args.putString("account", accountId); args.putString("albumId", album.id); Nav.go(getActivity(), AlbumDetailFragment.class, args);
	}
	private void createAlbum(){
		if(busy || !ownProfile() || !sessionValid()) return;
		dialog=AlbumUi.edit(getActivity(), null, values->{
			busy=true; renderState(); int token=generation;
			execute(AlbumRequest.post("/", DetailResponse.class, Map.of("name", values.name, "visibility", values.visibility)), token, result->{
				busy=false;
				if(!valid(result.album) || !result.album.isOwner){ invalid().showToast(getActivity()); renderState(); return; }
				if(isSelection()) onAlbumClicked(result.album); else refresh();
			}, error->{ busy=false; renderState(); error.showToast(getActivity()); });
		});
	}
	private void confirmImport(){
		if(busy || !ownProfile()) return;
		dialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_import).setMessage(R.string.album_import_help)
				.setPositiveButton(R.string.album_import, (d, which)->importHistory(true)).setNegativeButton(R.string.cancel, null).show();
	}
	private void importHistory(boolean manual){
		if(busy || !sessionValid() || !ownProfile() || isSelection()) return;
		busy=true; renderState(); int token=generation;
		execute(AlbumRequest.post("/import-history", ImportResponse.class, Map.of()), token, result->{
			busy=false; importRemaining=result.remaining;
			if(manual || result.imported>0 || result.remaining) Toast.makeText(getActivity(), getString(R.string.album_import_result, result.imported, result.skipped), Toast.LENGTH_LONG).show();
			renderState(); if(result.imported>0 || refreshPending) refresh();
		}, error->{ busy=false; renderState(); error.showToast(getActivity()); });
	}
	private void renderState(){
		if(recyclerView==null) return;
		create.setEnabled(!busy && !loadingPage && sessionValid()); join.setEnabled(!busy && !joining && sessionValid());
		importButton.setEnabled(!busy && !loadingPage && sessionValid()); importButton.setText(busy ? R.string.album_importing : importRemaining ? R.string.album_import_continue : R.string.album_import);
		stateText.setText(loadingPage ? R.string.album_loading : R.string.album_empty); stateText.setVisibility(albums.isEmpty() ? View.VISIBLE : View.GONE);
		more.setText(loadingPage ? R.string.album_loading : R.string.album_more); more.setEnabled(!loadingPage && !busy); more.setVisibility(hasMore ? View.VISIBLE : View.GONE);
	}
	private void showJoin(){
		if(busy || joining || !sessionValid()) return;
		LinearLayout root=AlbumUi.column(getActivity()); root.setPadding(V.dp(24), 0, V.dp(24), 0);
		AlbumUi.label(root, getString(R.string.album_join_help), false);
		EditText input=new EditText(getActivity(), null, 0, R.style.Widget_Mastodon_M3_EditText); input.setHint(R.string.album_invite_input); input.setSingleLine(true); input.setSaveEnabled(false); root.addView(input, new LinearLayout.LayoutParams(-1, V.dp(56)));
		AlbumUi.button(root, R.string.album_join_scan, ()->{ dialog.dismiss(); startScan(); });
		AlbumUi.button(root, R.string.album_join_clipboard, ()->input.setText(AlbumUi.clipboard(getActivity())));
		dialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_join).setView(root)
				.setPositiveButton(R.string.album_join, null).setNegativeButton(R.string.cancel, null).create();
		dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
			String token=AlbumUi.inviteToken(input.getText().toString()); if(token==null){ input.setError(getString(R.string.album_invite_invalid)); return; }
			dialog.dismiss(); confirmJoin(token);
		})); dialog.show();
	}
	private void startScan(){
		Intent intent=BarcodeScanner.createIntent(Barcode.FORMAT_QR_CODE, false, true);
		try{ startActivityForResult(intent, SCAN_RESULT); }catch(RuntimeException unavailable){ Toast.makeText(getActivity(), R.string.album_picker_unavailable, Toast.LENGTH_LONG).show(); }
	}
	@Override public void onActivityResult(int requestCode, int resultCode, Intent data){
		super.onActivityResult(requestCode, resultCode, data);
		if(requestCode==SCAN_RESULT && resultCode==Activity.RESULT_OK && BarcodeScanner.isValidResult(data)){
			Barcode result=BarcodeScanner.getResult(data); if(result!=null) confirmJoin(result.rawValue);
		}
	}
	private void confirmJoin(String value){
		String token=AlbumUi.inviteToken(value);
		if(token==null){ if(getActivity()!=null) Toast.makeText(getActivity(), R.string.album_invite_invalid, Toast.LENGTH_LONG).show(); return; }
		if(!sessionValid() || getActivity()==null) return;
		dialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_join).setMessage(R.string.album_join_confirm)
				.setPositiveButton(R.string.album_join, (d, which)->join(token)).setNegativeButton(R.string.cancel, null).show();
	}
	private void join(String inviteToken){
		if(joining || !sessionValid()) return;
		joining=true; renderState(); int token=generation;
		execute(AlbumRequest.post("/join", DetailResponse.class, Map.of("token", inviteToken)), token, result->{
			joining=false; renderState();
			if(!valid(result.album)){ invalid().showToast(getActivity()); return; }
			// Shared membership grants read access, never selection/upload privileges.
			openAlbum(result.album);
		}, error->{ joining=false; renderState(); error.showToast(getActivity()); });
	}
	private static boolean valid(Album album){ return album!=null && album.id!=null && album.id.matches("[A-Za-z0-9_-]{1,128}") && album.name!=null && AlbumUi.VISIBILITIES.contains(album.visibility) && album.photoCount>=0; }
	private ErrorResponse invalid(){ return new MastodonErrorResponse(getString(R.string.album_invalid_response), 0, null); }
	private <T> void execute(AlbumRequest<T> request, int token, Consumer<T> success, Consumer<ErrorResponse> error){
		requests.add(request);
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(T result){ requests.remove(request); if(live(token)) success.accept(result); }
			@Override public void onError(ErrorResponse response){ requests.remove(request); if(live(token)) error.accept(response); }
		}).exec(accountId);
	}
	@Override public void onApplyWindowInsets(WindowInsets insets){
		if(recyclerView!=null) recyclerView.setPadding(V.dp(8)+insets.getSystemWindowInsetLeft(), 0, V.dp(8)+insets.getSystemWindowInsetRight(), V.dp(16)+insets.getSystemWindowInsetBottom());
		super.onApplyWindowInsets(insets.replaceSystemWindowInsets(0, insets.getSystemWindowInsetTop(), 0, 0));
	}
	@Override public void onDestroyView(){
		generation++; for(MastodonAPIRequest<?> request:requests) request.cancel(); requests.clear();
		if(dialog!=null){ dialog.dismiss(); dialog=null; }
		loadingPage=false; dataLoading=false; busy=false; joining=false; refreshPending=true;
		if(recyclerView!=null) recyclerView.setAdapter(null);
		recyclerView=null; adapter=null; stateText=null; quotaText=null; more=null; create=null; importButton=null; join=null; albums.clear();
		super.onDestroyView();
	}
	private class AlbumAdapter extends RecyclerView.Adapter<AlbumHolder>{
		@Override public AlbumHolder onCreateViewHolder(ViewGroup parent, int type){ return new AlbumHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.album_card, parent, false)); }
		@Override public void onBindViewHolder(AlbumHolder holder, int position){
			Album album=albums.get(position); holder.name.setText(album.name); holder.meta.setText(getString(R.string.album_count, album.photoCount, AlbumUi.visibility(getActivity(), album.visibility)));
				boolean protectedCover=album.downloadProtected && !album.isOwner;
				holder.cover.setImageDrawable(protectedCover ? AlbumUi.protectedPlaceholder(getActivity()) : AlbumUi.gradient(getActivity(), album.id));
				if(protectedCover) holder.meta.append(" · "+getString(R.string.album_protection_enabled));
				if(!protectedCover && album.coverUrl!=null && !album.coverUrl.isBlank()) AlbumUi.loadImage(holder.cover, album.coverUrl, V.dp(180));
			holder.itemView.setContentDescription(album.name+", "+holder.meta.getText()); holder.itemView.setOnClickListener(v->{ if(!busy && sessionValid()) onAlbumClicked(album); });
		}
		@Override public int getItemCount(){ return albums.size(); }
	}
	private static class AlbumHolder extends RecyclerView.ViewHolder{
		final ImageView cover; final TextView name, meta;
		AlbumHolder(View view){ super(view); cover=view.findViewById(R.id.album_cover); name=view.findViewById(R.id.album_name); meta=view.findViewById(R.id.album_meta); AlbumUi.rounded(view); }
	}
}
