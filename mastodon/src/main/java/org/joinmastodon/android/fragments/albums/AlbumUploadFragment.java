package org.joinmastodon.android.fragments.albums;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.albums.AlbumUploader;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.albums.AlbumRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.albums.AlbumModels.*;
import org.joinmastodon.android.fragments.sponsors.SponsorCenterFragment;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.utils.SimpleTextWatcher;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.ToolbarFragment;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

/** Retained editor. SAF grants only selected images; processing/network work never holds the view. */
public class AlbumUploadFragment extends ToolbarFragment{
	private static final int PICK_PHOTOS=7820, SELECT_ALBUM=7821;
	private String accountId;
	private AccountSession session;
	private AlbumUploadDraft draft;
	private Album selected;
	private StorageQuota quota;
	private List<AlbumUploadDraft.SourceInfo> sourceInfo;
	private boolean checkingSources;
	private int sourceGeneration;
	private AlbumUploader uploader;
	private AlbumUploader.Failure uploadFailure;
	private AlbumUploader.Stage uploadStage;
	private final List<MastodonAPIRequest<?>> requests=new ArrayList<>();
	private final Handler main=new Handler(Looper.getMainLooper());
	private View body, choose, pick, permissionRow, qualityRow, timeRow, sponsorRow, help;
	private LinearLayout editor, uploadBar;
	private FrameLayout header;
	private EditText description;
	private TextView albumName, permissionText, qualityValue, timeValue, quotaText, count, status;
	private ImageView albumCover;
	private Button upload, discard;
	private RadioButton dialogOriginal;
	private String statusDetails;
	private int sideInsetLeft, sideInsetRight;
	private boolean darkDesign;
	private ProgressBar progress;
	private RecyclerView previews;
	private PreviewAdapter adapter;
	private AlertDialog dialog;
	private SharedPreferences preferences;
	private boolean refreshing, selecting, acknowledging, discarding;
	private int generation;
	private final Runnable confirmBack=this::showBackgroundChoice;

	@Override public void onCreate(Bundle state){
		super.onCreate(state); setRetainInstance(true); setTitle(R.string.album_upload_design_title);
		Bundle args=getArguments()==null ? new Bundle() : getArguments(); accountId=args.getString("account");
		session=AccountSessionManager.getInstance().tryGetAccount(accountId);
		preferences=getActivity().getSharedPreferences("album_upload_tasks", Context.MODE_PRIVATE);
		draft=AlbumUploadDraft.restore(state==null ? null : state.getBundle("draft"));
		if(state==null){
			draft.albumId=args.getString("albumId", args.getString("album_id")); draft.description=args.getString("description", "");
			ArrayList<String> sources=args.getStringArrayList("photoUris"); if(sources!=null) for(String source:sources) draft.addPhoto(source);
		}
		String operation=state==null ? preferences.getString(accountId, null) : state.getString("operation", preferences.getString(accountId, null));
		if(operation!=null && session!=null){
			try{
				uploader=AlbumUploader.resume(accountId, operation); AlbumUploader.BundleState previous=uploader.getDraftState();
				draft.albumId=previous.albumId; draft.description=previous.description; draft.quality=previous.quality; draft.photos=new ArrayList<>(previous.sources);
				if(previous.capturedAt!=null){ draft.customCapturedAt=true; draft.customTime=AlbumUploadDraft.CAPTURED_TIME.format(java.time.Instant.ofEpochSecond(previous.capturedAt).atZone(ZoneId.systemDefault())); }
			}catch(IOException unavailable){ preferences.edit().remove(accountId).apply(); }
		}
	}
	@Override public void onSaveInstanceState(Bundle state){
		super.onSaveInstanceState(state); saveEditor(); state.putBundle("draft", draft.save()); if(uploader!=null) state.putString("operation", uploader.getOperationId());
	}
	@Override public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle state){
		body=inflater.inflate(R.layout.album_upload, container, false); editor=body.findViewById(R.id.album_editor); uploadBar=body.findViewById(R.id.album_upload_bar);
		description=body.findViewById(R.id.album_description);
		albumName=body.findViewById(R.id.album_upload_album_name); albumCover=body.findViewById(R.id.album_upload_cover);
		permissionText=body.findViewById(R.id.album_permissions); qualityValue=body.findViewById(R.id.album_upload_quality_value); timeValue=body.findViewById(R.id.album_upload_time_value);
		quotaText=body.findViewById(R.id.album_quota); count=body.findViewById(R.id.album_pick_count); status=body.findViewById(R.id.album_upload_status); progress=body.findViewById(R.id.album_upload_progress);
		choose=body.findViewById(R.id.album_choose); permissionRow=body.findViewById(R.id.album_upload_permissions_row); qualityRow=body.findViewById(R.id.album_upload_quality_row);
		timeRow=body.findViewById(R.id.album_upload_time_row); sponsorRow=body.findViewById(R.id.album_upload_sponsor); help=body.findViewById(R.id.album_upload_help); discard=body.findViewById(R.id.album_upload_discard);
		description.setText(draft.description);
		description.addTextChangedListener(new SimpleTextWatcher(text->{ draft.description=text.toString(); description.setError(draft.descriptionValid() ? null : getString(R.string.album_description_limit)); updateActions(); }));
		choose.setOnClickListener(v->selectAlbum()); permissionRow.setOnClickListener(v->showPermissions()); qualityRow.setOnClickListener(v->showQuality()); timeRow.setOnClickListener(v->showTime(false));
		sponsorRow.setOnClickListener(v->openSponsorCenter()); help.setOnClickListener(v->showHelp()); status.setOnClickListener(v->showStatusDetails()); discard.setOnClickListener(v->confirmDiscard());
		for(View row:new View[]{choose, permissionRow, qualityRow, timeRow, sponsorRow}) accessibleButton(row);
		previews=body.findViewById(R.id.album_preview_grid); previews.setLayoutManager(new GridLayoutManager(getActivity(), 3)); previews.setAdapter(adapter=new PreviewAdapter());
		applyDesign(); renderSelection(); updateActions(); inspectSources(); return body;
	}
	@Override public void onViewCreated(View view, Bundle state){
		super.onViewCreated(view, state); installUploadToolbar(getToolbar());
	}
	@Override public void onUpdateToolbar(){ super.onUpdateToolbar(); installUploadToolbar(getToolbar()); }
	private void installUploadToolbar(android.widget.Toolbar toolbar){
		if(toolbar==null || body==null) return;
		toolbar.setTitle(""); toolbar.setSubtitle(null); toolbar.setNavigationIcon(null);
		toolbar.setContentInsetsAbsolute(0, 0); toolbar.setContentInsetsRelative(0, 0); toolbar.setContentInsetStartWithNavigation(0); toolbar.setContentInsetEndWithActions(0);
		toolbar.setPadding(0, 0, 0, 0); toolbar.setMinimumHeight(V.dp(56));
		toolbar.setBackgroundColor(designBackground()); setStatusBarColor(designBackground()); setNavigationBarColor(designBackground());
		if(header==null || header.getParent()!=toolbar){
			if(header!=null && header.getParent() instanceof ViewGroup parent) parent.removeView(header);
			header=new FrameLayout(toolbar.getContext()); header.setId(R.id.album_upload_header);
			TextView title=new TextView(toolbar.getContext()); title.setId(R.id.album_upload_header_title); title.setText(R.string.album_upload_design_title); title.setGravity(Gravity.CENTER);
			title.setTextAppearance(R.style.m3_title_large); title.setTextColor(UiUtils.getThemeColor(body.getContext(), R.attr.colorM3OnSurface)); title.setSingleLine(true);
			header.addView(title, new FrameLayout.LayoutParams(-1, -1));
			Button cancel=new Button(toolbar.getContext(), null, 0, R.style.Widget_Mastodon_AlbumUpload_HeaderButton); cancel.setId(R.id.album_upload_close); cancel.setText(R.string.album_upload_design_cancel);
			cancel.setOnClickListener(v->onToolbarNavigationClick()); header.addView(cancel, new FrameLayout.LayoutParams(V.dp(72), V.dp(48), Gravity.START|Gravity.CENTER_VERTICAL));
			upload=new Button(toolbar.getContext(), null, 0, R.style.Widget_Mastodon_AlbumUpload_UploadButton); upload.setId(R.id.album_upload); upload.setOnClickListener(v->uploadOrPause());
			header.addView(upload, new FrameLayout.LayoutParams(V.dp(72), V.dp(48), Gravity.END|Gravity.CENTER_VERTICAL));
			toolbar.addView(header, new android.widget.Toolbar.LayoutParams(-1, -1, Gravity.CENTER));
		}
		header.setPadding(V.dp(12)+sideInsetLeft, 0, V.dp(12)+sideInsetRight, 0); updateActions();
	}
	private static void accessibleButton(View view){
		view.setAccessibilityDelegate(new View.AccessibilityDelegate(){
			@Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info){ super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName()); }
		});
	}
	private int designBackground(){ return darkDesign ? getResources().getColor(R.color.album_upload_design_dark_background, getActivity().getTheme()) : UiUtils.getThemeColor(body.getContext(), R.attr.colorM3Surface); }
	private void applyDesign(){
		// Resolve the actual view theme, not global/system night mode (also supports explicit M3 themes).
		int surface=UiUtils.getThemeColor(body.getContext(), R.attr.colorM3Surface); darkDesign=Color.luminance(surface)<0.5f;
		body.setBackgroundColor(designBackground());
		for(int id:new int[]{R.id.album_description, R.id.album_upload_settings, R.id.album_upload_photos}){
			View card=body.findViewById(id); if(darkDesign && card.getBackground() instanceof GradientDrawable shape) shape.mutate().setTint(getResources().getColor(R.color.album_upload_design_dark_surface, getActivity().getTheme()));
			card.setOutlineProvider(OutlineProviders.roundedRect(12)); card.setClipToOutline(true);
		}
		int accent=getResources().getColor(darkDesign ? R.color.album_upload_design_dark_accent : R.color.album_upload_design_light_accent, getActivity().getTheme());
		for(View row:new View[]{choose, permissionRow, qualityRow, timeRow}) ((ImageView)((ViewGroup)row).getChildAt(0)).setImageTintList(ColorStateList.valueOf(accent));
		sponsorRow.getBackground().mutate().setTint(getResources().getColor(darkDesign ? R.color.album_upload_design_dark_gold_surface : R.color.album_upload_design_light_gold_surface, getActivity().getTheme()));
		int gold=getResources().getColor(darkDesign ? R.color.album_upload_design_dark_gold : R.color.album_upload_design_light_gold, getActivity().getTheme());
		((TextView)body.findViewById(R.id.album_upload_sponsor_text)).setTextColor(gold);
		((ImageView)body.findViewById(R.id.album_upload_sponsor_icon)).setImageTintList(ColorStateList.valueOf(gold));
		((ImageView)body.findViewById(R.id.album_upload_sponsor_chevron)).setImageTintList(ColorStateList.valueOf(gold));
		albumCover.setOutlineProvider(OutlineProviders.roundedRect(8)); albumCover.setClipToOutline(true);
	}
	@Override protected void onShown(){
		super.onShown();
		if(body==null) return;
		if(uploader!=null) attachUploader();
		refreshPermissions();
		if(sourceInfo==null && !checkingSources) inspectSources();
	}
	@Override protected void onHidden(){
		super.onHidden(); saveEditor(); generation++; sourceGeneration++; checkingSources=false; refreshing=false;
		for(MastodonAPIRequest<?> request:requests) request.cancel(); requests.clear();
		if(uploader!=null) uploader.setListener(null); if(dialog!=null){ dialog.dismiss(); dialog=null; }
		removeBackCallback(confirmBack);
		if(!sessionValid() && body!=null){ setEditorEnabled(false); if(previews!=null) previews.setVisibility(View.INVISIBLE); }
	}
	@Override public void onToolbarNavigationClick(){ if(uploader!=null && uploader.isRunning()) showBackgroundChoice(); else super.onToolbarNavigationClick(); }
	private boolean sessionValid(){ return session!=null && accountId.equals(AccountSessionManager.getInstance().getLastActiveAccountID()) && AccountSessionManager.getInstance().tryGetAccount(accountId)==session; }
	private boolean live(int token){ return token==generation && body!=null && getActivity()!=null && sessionValid(); }
	private boolean originalEligible(){ return sessionValid() && !refreshing && !checkingSources && sourceInfo!=null && sourceInfo.size()==draft.photos.size() && AlbumUploadDraft.originalAllowed(quota, sourceInfo); }
	private void inspectSources(){
		int token=++sourceGeneration; sourceInfo=null; checkingSources=true; List<String> sources=new ArrayList<>(draft.photos);
		Context context=getActivity().getApplicationContext(); updateActions();
		MastodonAPIController.runInBackground(()->{
			List<AlbumUploadDraft.SourceInfo> info=new ArrayList<>();
			for(String value:sources){
				Uri uri=Uri.parse(value); long size=-1; String mime=null;
				try{
					if("file".equals(uri.getScheme())){
						java.io.File file=new java.io.File(uri.getPath()); size=file.length();
						android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds=true; android.graphics.BitmapFactory.decodeFile(file.getPath(), bounds); mime=bounds.outMimeType;
					}else{
						mime=context.getContentResolver().getType(uri);
						try(android.database.Cursor cursor=context.getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)){
							if(cursor!=null && cursor.moveToFirst() && !cursor.isNull(0)) size=cursor.getLong(0);
						}
					}
				}catch(RuntimeException unavailable){}
				info.add(new AlbumUploadDraft.SourceInfo(size, mime));
			}
			main.post(()->{
				if(token!=sourceGeneration || body==null || !sources.equals(draft.photos)) return;
				sourceInfo=info; checkingSources=false;
				if(uploader==null && "original".equals(draft.quality) && !originalEligible()) draft.quality="hd";
				updateActions();
			});
		});
	}
	private void refreshPermissions(){
		if(refreshing || body==null || discarding) return;
		if(!sessionValid()){
			selected=null; quota=null; if(uploader!=null) uploader.cancel();
			setEditorEnabled(false); showStatus(getString(R.string.album_session_changed), getString(R.string.album_session_changed)); updateActions(); return;
		}
		refreshing=true; quota=null; selected=null; quotaText.setText(R.string.album_upload_design_capacity_loading); updateActions(); int token=++generation;
		execute(AlbumRequest.get("/quota", StorageQuota.class), token, result->{
			quota=result;
			quotaText.setText(getString(R.string.album_upload_design_capacity, UiUtils.formatFileSize(getActivity(), result.usedBytes, false), UiUtils.formatFileSize(getActivity(), result.limitBytes, false)));
			if(draft.albumId==null) loadDefault(token); else loadSelected(token);
		}, error->{ refreshing=false; updateActions(); error.showToast(getActivity()); });
	}
	private void loadDefault(int token){
		execute(AlbumRequest.get("/", ListResponse.class).query("owner_id", session.self.id).query("limit", 30).query("offset", 0), token, result->{
			Album album=result.albums==null ? null : result.albums.stream().filter(a->a!=null && a.isDefault && a.isOwner && a.canUpload).findFirst().orElse(null);
			refreshing=false;
			if(album!=null){ selected=album; draft.select(album.id, album.name, album.visibility); }
			renderSelection(); updateActions();
		}, error->{ refreshing=false; updateActions(); error.showToast(getActivity()); });
	}
	private void loadSelected(int token){
		execute(AlbumRequest.get("/"+draft.albumId, DetailResponse.class), token, result->{
			refreshing=false;
			if(result.album!=null && result.album.isOwner && result.album.canUpload && draft.select(result.album.id, result.album.name, result.album.visibility)) selected=result.album;
			else new MastodonErrorResponse(getString(R.string.album_pick_required), 403, null).showToast(getActivity());
			renderSelection(); updateActions();
		}, error->{ refreshing=false; renderSelection(); updateActions(); error.showToast(getActivity()); });
	}
	private void selectAlbum(){
		if(uploader!=null || !sessionValid() || selecting) return;
		selecting=true; saveEditor(); Bundle args=new Bundle(); args.putString("account", accountId); args.putString("ownerId", session.self.id);
		Nav.goForResult(getActivity(), AlbumSelectFragment.class, args, SELECT_ALBUM, this);
	}
	@Override public void onFragmentResult(int requestCode, boolean success, Bundle result){
		super.onFragmentResult(requestCode, success, result);
		if(requestCode==SELECT_ALBUM){
			selecting=false;
			if(success && result!=null && uploader==null){
				draft.select(result.getString(AlbumSelectFragment.RESULT_ALBUM_ID), result.getString(AlbumSelectFragment.RESULT_ALBUM_NAME), result.getString(AlbumSelectFragment.RESULT_VISIBILITY));
				if(body!=null){ renderSelection(); refreshPermissions(); }
			}
		}
	}
	private void renderSelection(){
		if(body==null) return;
		albumName.setText(draft.albumName==null ? getString(R.string.album_upload_design_choose) : draft.albumName);
		choose.setContentDescription(getString(R.string.album_upload_design_destination)+"，"+albumName.getText());
		permissionText.setText(draft.visibility==null ? getString(R.string.album_upload_design_choose) : AlbumUi.visibility(getActivity(), draft.visibility));
		permissionRow.setContentDescription(getString(R.string.album_upload_design_permissions)+"，"+permissionText.getText());
		albumCover.setImageDrawable(AlbumUi.gradient(body.getContext(), draft.albumId));
		if(selected!=null && selected.coverUrl!=null && !selected.coverUrl.isBlank()) ViewImageLoader.loadWithoutAnimation(albumCover, albumCover.getDrawable(), new UrlImageLoaderRequest(selected.coverUrl));
		count.setText(getString(R.string.album_upload_design_count, draft.photos.size())); if(adapter!=null) adapter.notifyDataSetChanged(); renderSettings();
	}
	private void renderSettings(){
		if(body==null) return;
		qualityValue.setText("original".equals(draft.quality) ? R.string.album_upload_design_original : R.string.album_upload_design_hd);
		timeValue.setText(draft.customCapturedAt ? draft.customTime : getString(R.string.album_time_auto));
		qualityRow.setContentDescription(getString(R.string.album_upload_design_quality)+"，"+qualityValue.getText());
		timeRow.setContentDescription(getString(R.string.album_upload_design_time)+"，"+timeValue.getText());
	}
	private boolean canEdit(){ return body!=null && uploader==null && sessionValid() && !discarding; }
	private LinearLayout dialogColumn(){ LinearLayout root=AlbumUi.column(getActivity()); root.setPadding(V.dp(24), V.dp(4), V.dp(24), V.dp(8)); return root; }
	private RadioButton dialogChoice(RadioGroup group, int id, int label){
		RadioButton choice=new RadioButton(getActivity()); choice.setId(id); choice.setText(label); choice.setTextAppearance(R.style.m3_body_large);
		choice.setTextColor(UiUtils.getThemeColor(body.getContext(), R.attr.colorM3OnSurface)); choice.setMinHeight(V.dp(48)); group.addView(choice, new RadioGroup.LayoutParams(-1, -2)); return choice;
	}
	private void showQuality(){
		if(!canEdit()) return;
		LinearLayout root=dialogColumn(); RadioGroup choices=new RadioGroup(getActivity()); choices.setId(R.id.album_quality); root.addView(choices);
		dialogChoice(choices, R.id.album_hd, R.string.album_upload_design_hd);
		dialogOriginal=dialogChoice(choices, R.id.album_original, R.string.album_upload_design_original); dialogOriginal.setEnabled(originalEligible());
		choices.check("original".equals(draft.quality) ? R.id.album_original : R.id.album_hd);
		AlbumUi.label(root, getString(R.string.album_upload_design_quality_help), false);
		if(!originalEligible()) AlbumUi.label(root, getString(refreshing || checkingSources || quota==null ? R.string.album_upload_design_original_checking : R.string.album_upload_design_original_unavailable), false);
		ScrollView scroll=new ScrollView(getActivity()); scroll.addView(root);
		AlertDialog qualityDialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_upload_design_quality).setView(scroll)
				.setPositiveButton(R.string.album_upload_design_done, null).setNegativeButton(R.string.cancel, null).create(); dialog=qualityDialog;
		qualityDialog.setOnShowListener(ignored->qualityDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
			if(!canEdit()){ qualityDialog.dismiss(); return; }
			if(choices.getCheckedRadioButtonId()==R.id.album_original && !originalEligible()){ choices.check(R.id.album_hd); dialogOriginal.setEnabled(false); Toast.makeText(getActivity(), R.string.album_sponsor_required, Toast.LENGTH_LONG).show(); return; }
			draft.quality=choices.getCheckedRadioButtonId()==R.id.album_original ? "original" : "hd"; renderSettings(); qualityDialog.dismiss();
		}));
		qualityDialog.setOnDismissListener(ignored->{ if(dialog==qualityDialog){ dialog=null; dialogOriginal=null; } }); qualityDialog.show();
	}
	private void showTime(boolean invalid){
		if(!canEdit()) return;
		LinearLayout root=dialogColumn(); RadioGroup choices=new RadioGroup(getActivity());
		dialogChoice(choices, R.id.album_time_auto, R.string.album_time_auto); dialogChoice(choices, R.id.album_custom_time, R.string.album_upload_design_time_custom);
		root.addView(choices); EditText input=new EditText(getActivity(), null, 0, R.style.Widget_Mastodon_M3_EditText); input.setId(R.id.album_time_input);
		input.setSingleLine(true); input.setHint(R.string.album_time_hint); input.setContentDescription(getString(R.string.album_time_custom)); input.setMinHeight(V.dp(56)); input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
		input.setText(draft.customTime.isBlank() ? AlbumUploadDraft.CAPTURED_TIME.format(LocalDateTime.now().withNano(0)) : draft.customTime); root.addView(input, new LinearLayout.LayoutParams(-1, -2));
		choices.check(draft.customCapturedAt ? R.id.album_custom_time : R.id.album_time_auto); input.setVisibility(draft.customCapturedAt ? View.VISIBLE : View.GONE);
		choices.setOnCheckedChangeListener((group, id)->input.setVisibility(id==R.id.album_custom_time ? View.VISIBLE : View.GONE));
		AlbumUi.label(root, getString(R.string.album_time_help), false);
		AlertDialog timeDialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_upload_design_time).setView(root)
				.setPositiveButton(R.string.album_save, null).setNegativeButton(R.string.cancel, null).create(); dialog=timeDialog;
		timeDialog.setOnShowListener(ignored->timeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
			if(!canEdit()){ timeDialog.dismiss(); return; }
			boolean custom=choices.getCheckedRadioButtonId()==R.id.album_custom_time; String value=input.getText().toString();
			if(custom){ AlbumUploadDraft candidate=new AlbumUploadDraft(); candidate.customCapturedAt=true; candidate.customTime=value;
				try{ candidate.capturedAt(ZoneId.systemDefault()); }catch(RuntimeException error){ input.setError(getString(R.string.album_time_invalid)); input.requestFocus(); return; } }
			// Modal edits are transactional; no detached/hidden EditText can overwrite this draft later.
			draft.customCapturedAt=custom; draft.customTime=value; renderSettings(); timeDialog.dismiss();
		})); timeDialog.setOnDismissListener(ignored->{ if(dialog==timeDialog) dialog=null; }); timeDialog.show();
		if(invalid){ input.setError(getString(R.string.album_time_invalid)); input.requestFocus(); }
	}
	private void showPermissions(){
		if(body==null || !sessionValid()) return;
		String message=draft.visibility==null ? getString(R.string.album_upload_design_choose) : getString(R.string.album_upload_design_permission_value, AlbumUi.visibility(getActivity(), draft.visibility));
		if(selected!=null && selected.isDefault) message+="\n\n"+getString(R.string.album_default_help);
		message+="\n\n"+getString(R.string.album_privacy_help)+"\n\n"+getString(R.string.album_upload_design_permissions_readonly);
		dialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_upload_design_permissions).setMessage(message).setPositiveButton(R.string.ok, null).show();
	}
	private void openSponsorCenter(){
		if(body==null || !sessionValid() || uploader!=null || discarding) return;
		saveEditor(); Bundle args=new Bundle(); args.putString("account", accountId); Nav.go(getActivity(), SponsorCenterFragment.class, args);
	}
	private void showHelp(){
		if(body==null || !sessionValid()) return;
		String capacity=quota==null ? getString(R.string.album_upload_design_quota_unavailable) : getString(R.string.album_quota, UiUtils.formatFileSize(getActivity(), quota.usedBytes, false), UiUtils.formatFileSize(getActivity(), quota.limitBytes, false), UiUtils.formatFileSize(getActivity(), quota.remainingBytes, false));
		dialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_upload_design_help).setMessage(capacity+"\n\n"+getString(R.string.album_description_limit)+"\n\n"+getString(R.string.album_pick_hint)+"\n\n"+getString(R.string.album_original_help)).setPositiveButton(R.string.ok, null).show();
	}
	private void showStatus(String summary, String details){
		if(body==null) return;
		statusDetails=details; status.setText(summary); status.setContentDescription(summary+"，"+getString(R.string.album_upload_design_error_details)); uploadBar.setVisibility(View.VISIBLE);
	}
	private void showStatusDetails(){
		if(body==null || !sessionValid()) return;
		String details=statusDetails!=null ? statusDetails : uploader!=null ? getString(R.string.album_upload_resume) : getString(R.string.album_description_limit);
		dialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_upload_design_error_details).setMessage(details).setPositiveButton(R.string.ok, null).show();
	}
	private void pickPhotos(){
		if(uploader!=null || !sessionValid()) return;
		if(draft.photos.size()>=AlbumUploader.MAX_PHOTOS){ Toast.makeText(getActivity(), R.string.album_pick_limit, Toast.LENGTH_LONG).show(); return; }
		Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
				.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
		try{ startActivityForResult(intent, PICK_PHOTOS); }catch(RuntimeException unavailable){ Toast.makeText(getActivity(), R.string.album_picker_unavailable, Toast.LENGTH_LONG).show(); }
	}
	@Override public void onActivityResult(int requestCode, int resultCode, Intent result){
		super.onActivityResult(requestCode, resultCode, result);
		if(requestCode!=PICK_PHOTOS || resultCode!=Activity.RESULT_OK || result==null || uploader!=null) return;
		ArrayList<Uri> sources=new ArrayList<>();
		if(result.getClipData()!=null) for(int i=0;i<result.getClipData().getItemCount();i++) sources.add(result.getClipData().getItemAt(i).getUri());
		else if(result.getData()!=null) sources.add(result.getData());
		boolean exceeded=false;
		for(Uri source:sources){
			if(source==null) continue;
			if(draft.photos.size()>=AlbumUploader.MAX_PHOTOS){ exceeded=true; break; }
			try{
				if((result.getFlags()&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0) getActivity().getContentResolver().takePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION);
				draft.addPhoto(source.toString());
			}catch(SecurityException denied){ Toast.makeText(getActivity(), R.string.album_picker_unavailable, Toast.LENGTH_LONG).show(); }
		}
		if(exceeded) Toast.makeText(getActivity(), R.string.album_pick_limit, Toast.LENGTH_LONG).show();
		renderSelection(); updateActions(); inspectSources();
	}
	private void uploadOrPause(){
		if(discarding || !sessionValid()) return;
		if(uploader!=null){
			if(uploader.isRunning()){ uploader.cancel(); return; }
			if(uploader.isComplete()){ attachUploader(); return; }
			if(uploadFailure!=null && !uploadFailure.retryable){ confirmDiscard(); return; }
			uploadFailure=null; uploader.start(); attachUploader(); updateActions(); return;
		}
		saveEditor();
		if(!draft.descriptionValid()){ description.setError(getString(R.string.album_description_limit)); return; }
		Long captured;
		try{ captured=draft.capturedAt(ZoneId.systemDefault()); }catch(RuntimeException invalid){ showTime(true); return; }
		if(selected==null || !selected.isOwner || !selected.canUpload || draft.photos.isEmpty()){ Toast.makeText(getActivity(), R.string.album_pick_required, Toast.LENGTH_LONG).show(); return; }
		if("original".equals(draft.quality) && !originalEligible()){ Toast.makeText(getActivity(), R.string.album_sponsor_required, Toast.LENGTH_LONG).show(); refreshPermissions(); return; }
		List<Uri> sources=new ArrayList<>(); for(String source:draft.photos) sources.add(Uri.parse(source));
		try{
			uploader=new AlbumUploader(accountId, selected.id, sources, draft.description, captured, draft.quality);
			if(!preferences.edit().putString(accountId, uploader.getOperationId()).commit()){ uploader.discard(); uploader=null; throw new IOException("无法保存上传任务，请重试"); }
			attachUploader(); uploader.start(); updateActions();
		}catch(IOException failure){ showStatus(getString(R.string.album_upload_design_failed_details), failure.getMessage()); }
	}
	private void attachUploader(){
		if(uploader==null || body==null) return;
		AlbumUploader task=uploader;
		task.setListener(new AlbumUploader.Listener(){
			@Override public void onProgress(AlbumUploader.Stage stage, int percent, long transferred, long total){
				if(body==null || uploader!=task) return;
				uploadStage=stage; uploadBar.setVisibility(View.VISIBLE); progress.setVisibility(View.VISIBLE); progress.setProgress(percent);
				int text=switch(stage){ case PREPARING -> R.string.album_stage_preparing; case AUTHORIZING -> R.string.album_stage_authorizing; case UPLOADING -> R.string.album_stage_uploading; case PUBLISHING -> R.string.album_stage_publishing; case COMPLETE -> R.string.album_stage_complete; case FAILED -> R.string.album_stage_failed; case CANCELED -> R.string.album_stage_canceled; default -> R.string.album_upload_resume; };
				showStatus(getString(R.string.album_upload_progress, getString(text), percent), getString(R.string.album_upload_resume)); updateActions();
			}
			@Override public void onSuccess(PublishResponse result){
				if(body==null || uploader!=task || acknowledging || !sessionValid()) return;
				acknowledging=true; preferences.edit().remove(accountId).apply(); task.setListener(null); task.discard();
				removeBackCallback(confirmBack); Toast.makeText(getActivity(), R.string.album_stage_complete, Toast.LENGTH_LONG).show();
				Bundle args=new Bundle(); args.putString("account", accountId); args.putString("albumId", result.albumId);
				Activity activity=getActivity(); setResult(true, args); Nav.finish(AlbumUploadFragment.this); if(activity!=null) Nav.go(activity, AlbumDetailFragment.class, args);
			}
			@Override public void onError(AlbumUploader.Failure error){
				if(body==null || uploader!=task) return;
				uploadFailure=error; showStatus(getString(R.string.album_upload_design_failed_details), error.getMessage()+(error.outcomeUnknown ? "\n\n"+getString(R.string.album_upload_unknown) : "")); updateActions();
			}
			@Override public void onCanceled(){ if(body!=null && uploader==task){ showStatus(getString(R.string.album_upload_design_paused), getString(R.string.album_stage_canceled)+"\n\n"+getString(R.string.album_upload_resume)); updateActions(); } }
		});
	}
	private void updateActions(){
		if(body==null) return;
		if(uploader==null && !refreshing && quota!=null && !checkingSources && sourceInfo!=null && "original".equals(draft.quality) && !originalEligible()) draft.quality="hd";
		if(previews!=null) previews.setVisibility(sessionValid() ? View.VISIBLE : View.INVISIBLE);
		boolean editable=canEdit(); setEditorEnabled(editable);
		if(dialogOriginal!=null) dialogOriginal.setEnabled(editable && originalEligible());
		choose.setEnabled(editable && !refreshing); if(pick!=null) pick.setEnabled(editable && draft.photos.size()<AlbumUploader.MAX_PHOTOS);
		if(upload!=null){
			upload.setEnabled(!discarding && sessionValid() && (uploader!=null || !refreshing && selected!=null && selected.isOwner && selected.canUpload && quota!=null && !draft.photos.isEmpty() && draft.descriptionValid()));
			upload.setText(uploader==null ? R.string.album_upload_design_upload : uploader.isRunning() ? R.string.album_upload_design_pause : uploadFailure!=null && !uploadFailure.retryable ? R.string.album_upload_design_discard : R.string.album_upload_design_resume);
			upload.setContentDescription(getString(uploader==null ? R.string.album_upload : uploader.isRunning() ? R.string.album_upload_cancel : uploadFailure!=null && !uploadFailure.retryable ? R.string.album_upload_discard : R.string.album_upload_retry));
		}
		discard.setVisibility(uploader!=null && !uploader.isRunning() && !uploader.isComplete() ? View.VISIBLE : View.GONE); discard.setEnabled(!discarding);
		if(uploader==null && !draft.descriptionValid()) showStatus(getString(R.string.album_upload_design_description_error), getString(R.string.album_description_limit));
		else if(uploader==null && getString(R.string.album_description_limit).equals(statusDetails)){ statusDetails=null; uploadBar.setVisibility(View.GONE); }
		renderSettings(); if(uploader!=null && uploader.isRunning()) addBackCallback(confirmBack); else removeBackCallback(confirmBack);
	}
	private void setEditorEnabled(boolean enabled){
		if(body==null) return;
		description.setEnabled(enabled); choose.setEnabled(enabled); qualityRow.setEnabled(enabled); timeRow.setEnabled(enabled); sponsorRow.setEnabled(enabled);
		permissionRow.setEnabled(sessionValid()); help.setEnabled(sessionValid()); if(pick!=null) pick.setEnabled(enabled);
		if(previews!=null) for(int i=0;i<previews.getChildCount();i++){
			View tile=previews.getChildAt(i); View remove=tile.findViewById(R.id.album_upload_remove); if(remove!=null) remove.setEnabled(enabled); tile.setLongClickable(enabled && remove!=null);
		}
	}
	private void saveEditor(){ if(description!=null) draft.description=description.getText().toString(); }
	private void showBackgroundChoice(){
		if(body==null || uploader==null || !uploader.isRunning()) return;
		dialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_upload_title).setMessage(R.string.album_upload_cancel_help)
				.setPositiveButton(R.string.album_upload_keep, (d, which)->{ removeBackCallback(confirmBack); Nav.finish(this); })
				.setNeutralButton(R.string.album_upload_cancel, (d, which)->uploader.cancel()).setNegativeButton(R.string.cancel, null).show();
	}
	private void confirmDiscard(){
		if(uploader==null || uploader.isRunning() || discarding) return;
		dialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_upload_discard).setMessage(R.string.album_upload_discard_help)
				.setPositiveButton(R.string.album_upload_discard, (d, which)->discardTask()).setNegativeButton(R.string.cancel, null).show();
	}
	private void discardTask(){
		if(uploader==null || uploader.isRunning() || !sessionValid()) return;
		discarding=true; updateActions(); AlbumUploader task=uploader; task.setListener(null);
		MastodonAPIController.runInBackground(()->{
			IOException failure=null; try{ task.cancelReservation(); }catch(IOException error){ failure=error; }
			IOException finalFailure=failure;
			main.post(()->{
				discarding=false; if(uploader!=task) return;
				if(task.isComplete()){ if(body!=null) attachUploader(); return; }
				if(finalFailure==null){ preferences.edit().remove(accountId).apply(); uploader=null; uploadFailure=null; uploadStage=null; statusDetails=null; if(body!=null){ uploadBar.setVisibility(View.GONE); progress.setVisibility(View.GONE); refreshPermissions(); updateActions(); } }
				else if(body!=null){ showStatus(getString(R.string.album_upload_design_failed_details), finalFailure.getMessage()); attachUploader(); updateActions(); }
			});
		});
	}
	private <T> void execute(AlbumRequest<T> request, int token, Consumer<T> success, Consumer<ErrorResponse> error){
		requests.add(request); request.setCallback(new Callback<>(){
			@Override public void onSuccess(T result){ requests.remove(request); if(live(token)) success.accept(result); }
			@Override public void onError(ErrorResponse response){ requests.remove(request); if(live(token)) error.accept(response); }
		}).exec(accountId);
	}
	@Override public void onApplyWindowInsets(WindowInsets insets){
		sideInsetLeft=insets.getSystemWindowInsetLeft(); sideInsetRight=insets.getSystemWindowInsetRight();
		if(uploadBar!=null) uploadBar.setPadding(V.dp(16)+sideInsetLeft, V.dp(8), V.dp(16)+sideInsetRight, V.dp(8));
		if(editor!=null) editor.setPadding(V.dp(16)+sideInsetLeft, V.dp(12), V.dp(16)+sideInsetRight, V.dp(16));
		if(header!=null) header.setPadding(V.dp(12)+sideInsetLeft, 0, V.dp(12)+sideInsetRight, 0);
		// AppKit's root owns top/bottom safe areas; apply horizontal ones exactly once to our content.
		super.onApplyWindowInsets(insets.replaceSystemWindowInsets(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom()));
	}
	@Override public void onDestroyView(){
		saveEditor(); generation++; sourceGeneration++; checkingSources=false; refreshing=false; for(MastodonAPIRequest<?> request:requests) request.cancel(); requests.clear();
		if(uploader!=null) uploader.setListener(null); removeBackCallback(confirmBack); if(dialog!=null){ dialog.dismiss(); dialog=null; }
		if(previews!=null) previews.setAdapter(null);
		body=null; editor=null; uploadBar=null; header=null; description=null; albumName=null; albumCover=null; permissionText=null; qualityValue=null; timeValue=null; quotaText=null; count=null; status=null; dialogOriginal=null;
		choose=null; pick=null; permissionRow=null; qualityRow=null; timeRow=null; sponsorRow=null; help=null; upload=null; discard=null; progress=null; previews=null; adapter=null; selected=null; quota=null;
		super.onDestroyView();
	}
	private void removePhoto(String source, boolean confirm){
		if(!canEdit() || !draft.photos.contains(source)) return;
		if(confirm){
			dialog=new M3AlertDialogBuilder(getActivity()).setTitle(R.string.album_upload_design_remove_title).setMessage(R.string.album_upload_design_remove_help)
					.setPositiveButton(R.string.album_upload_design_remove, (d, which)->removePhoto(source, false)).setNegativeButton(R.string.cancel, null).show(); return;
		}
		draft.photos.remove(source); renderSelection(); updateActions(); inspectSources();
	}
	private class PreviewAdapter extends RecyclerView.Adapter<PreviewHolder>{
		@Override public int getItemViewType(int position){ return position==draft.photos.size() ? 1 : 0; }
		@Override public PreviewHolder onCreateViewHolder(ViewGroup parent, int type){
			SquareTile tile=new SquareTile(parent.getContext()); tile.setPadding(V.dp(4), V.dp(4), V.dp(4), V.dp(4)); tile.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
			FrameLayout content=new FrameLayout(parent.getContext()); content.setBackgroundResource(R.drawable.album_upload_design_tile);
			if(darkDesign) content.getBackground().mutate().setTint(getResources().getColor(R.color.album_upload_design_dark_tile, getActivity().getTheme()));
			content.setOutlineProvider(OutlineProviders.roundedRect(8)); content.setClipToOutline(true); tile.addView(content, new FrameLayout.LayoutParams(-1, -1));
			if(type==1){
				content.setId(R.id.album_pick); content.setFocusable(true); content.setClickable(true); accessibleButton(content);
				LinearLayout stack=new LinearLayout(parent.getContext()); stack.setOrientation(LinearLayout.VERTICAL); stack.setGravity(Gravity.CENTER); stack.setPadding(V.dp(2), 0, V.dp(2), 0);
				ImageView icon=new ImageView(parent.getContext()); icon.setImageResource(R.drawable.ic_fluent_add_24_regular); icon.setImageTintList(ColorStateList.valueOf(UiUtils.getThemeColor(body.getContext(), R.attr.colorM3OnSurfaceVariant))); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
				stack.addView(icon, new LinearLayout.LayoutParams(V.dp(28), V.dp(28)));
				TextView label=new TextView(parent.getContext()); label.setText(R.string.album_upload_design_add); label.setTextAppearance(R.style.m3_body_small); label.setGravity(Gravity.CENTER); label.setTextColor(UiUtils.getThemeColor(body.getContext(), R.attr.colorM3OnSurfaceVariant)); label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
				stack.addView(label); content.addView(stack, new FrameLayout.LayoutParams(-1, -1)); return new PreviewHolder(tile, content, null, null);
			}
			ImageView image=new ImageView(parent.getContext()); image.setId(R.id.album_photo); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); content.addView(image, new FrameLayout.LayoutParams(-1, -1));
			ImageButton remove=new ImageButton(parent.getContext()); remove.setId(R.id.album_upload_remove); remove.setBackgroundResource(R.drawable.album_upload_design_remove); remove.setImageResource(R.drawable.ic_fluent_dismiss_24_regular); remove.setImageTintList(ColorStateList.valueOf(Color.WHITE)); remove.setPadding(V.dp(13), V.dp(13), V.dp(13), V.dp(13));
			content.addView(remove, new FrameLayout.LayoutParams(V.dp(48), V.dp(48), Gravity.TOP|Gravity.END)); return new PreviewHolder(tile, content, image, remove);
		}
		@Override public void onBindViewHolder(PreviewHolder holder, int position){
			if(getItemViewType(position)==1){
				pick=holder.content; pick.setEnabled(canEdit() && draft.photos.size()<AlbumUploader.MAX_PHOTOS); pick.setContentDescription(getString(R.string.album_upload_design_add)); pick.setOnClickListener(v->pickPhotos()); return;
			}
			String source=draft.photos.get(position); holder.image.setImageDrawable(AlbumUi.gradient(body.getContext(), source));
			ViewImageLoader.loadWithoutAnimation(holder.image, holder.image.getDrawable(), new UrlImageLoaderRequest(Uri.parse(source)));
			holder.content.setContentDescription(getString(R.string.album_upload_design_photo, position+1)); holder.content.setOnClickListener(null);
			holder.itemView.setOnLongClickListener(v->{ if(!canEdit()) return false; removePhoto(source, true); return true; });
			holder.remove.setContentDescription(getString(R.string.album_remove_photo, position+1)); holder.remove.setEnabled(canEdit()); holder.remove.setOnClickListener(v->removePhoto(source, false));
		}
		@Override public int getItemCount(){ return draft.photos.size()+1; }
	}
	private static class PreviewHolder extends RecyclerView.ViewHolder{
		final View content; final ImageView image; final ImageButton remove;
		PreviewHolder(View view, View content, ImageView image, ImageButton remove){ super(view); this.content=content; this.image=image; this.remove=remove; }
	}
	private static class SquareTile extends FrameLayout{
		SquareTile(Context context){ super(context); }
		@Override protected void onMeasure(int width, int height){ int side=View.MeasureSpec.getSize(width); super.onMeasure(View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY)); }
	}
}
