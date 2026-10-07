package org.joinmastodon.android.albums;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Fragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.albums.AlbumRequest;
import org.joinmastodon.android.api.requests.sponsors.SponsorRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.sponsors.SponsorCenterFragment;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.albums.AlbumModels;
import org.joinmastodon.android.model.sponsors.SponsorModels;
import org.joinmastodon.android.sponsors.SponsorUi;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.photoviewer.PhotoViewer;
import org.joinmastodon.android.ui.sheets.SponsorNoticeSheet;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.utils.V;

/** Selected-session adapter for the existing photo overlay; never a second image viewer. */
public final class AlbumPhotoViewer implements PhotoViewer.AlbumDelegate, Application.ActivityLifecycleCallbacks{
	private static final int COMMENTS_LIMIT=30;
	private final Activity activity;
	private final String accountID;
	private final AccountSession session;
	private final List<AlbumModels.Photo> photos;
	private final PhotoPolicyListener policyListener;
	private boolean policyDismissed;

	/** Keep the underlying detail policy synchronized while this separate window covers it. */
	public interface PhotoPolicyListener{
		void onPhotoPolicyChanged(AlbumModels.Photo photo);
		void onDismissed();
	}
	private final Handler main=new Handler(Looper.getMainLooper());
	private final Set<MastodonAPIRequest<?>> requests=new HashSet<>();
	private final SharedPreferences accountPrefs;
	private final SharedPreferences.OnSharedPreferenceChangeListener accountListener;
	private final Runnable sessionWatcher=this::watchSession;
	private final Runnable policyWatcher=this::watchPolicy;
	private PhotoViewer viewer;
	private boolean closed, paused, permissionWaiting, commentsOpen, likeBusy, commentsLoading, commentBusy;
	private long generation;
	private int currentIndex, commentsOffset;
	private long initializedGeneration=-1, previewRevision;
	private MastodonAPIRequest<?> previewRequest;
	private boolean previewRefreshing;
	private boolean hdLoaded, originalLoaded, commentsHasMore;
	private MastodonAPIRequest<?> commentsRequest;
	private long commentsGeneration;
	private MediaAction mediaAction;
	private SponsorNoticeSheet noticeSheet;
	private AlertDialog actionDialog;
	private PermissionFragment permissionFragment;
	private LinearLayout commentsPanel;
	private RecyclerView commentsList;
	private final List<AlbumModels.Comment> comments=new ArrayList<>();
	private CommentAdapter commentAdapter;
	private TextView commentsStatus;
	private Button moreComments, sendComment;
	private EditText commentInput;
	private CommentAction commentAction;
	private Runnable permissionDownload;
	private long permissionGeneration=-1;

	/** The only integration screens need. No global account or fixed API domain is installed. */
	public static void open(Activity activity, String accountID, List<AlbumModels.Photo> photos, int index){
		open(activity, accountID, photos, index, null);
	}

	public static void open(Activity activity, String accountID, List<AlbumModels.Photo> photos, int index, @Nullable PhotoPolicyListener listener){
		if(activity==null || activity.isFinishing() || activity.isDestroyed()) return;
		if(accountID==null || photos==null || photos.isEmpty() || index<0 || index>=photos.size()
				|| AccountSessionManager.getInstance().tryGetAccount(accountID)==null
				|| !accountID.equals(AccountSessionManager.getInstance().getLastActiveAccountID())){
			Toast.makeText(activity, R.string.album_viewer_invalid_session, Toast.LENGTH_LONG).show(); return;
		}
		for(AlbumModels.Photo photo:photos){
			if(photo==null || TextUtils.isEmpty(photo.id) || TextUtils.isEmpty(photo.previewUrl)){
				Toast.makeText(activity, R.string.album_viewer_image_error, Toast.LENGTH_LONG).show(); return;
			}
		}
		new AlbumPhotoViewer(activity, accountID, photos, index, listener).show();
	}

	private AlbumPhotoViewer(Activity activity, String accountID, List<AlbumModels.Photo> photos, int index){
		this(activity, accountID, photos, index, null);
	}

	private AlbumPhotoViewer(Activity activity, String accountID, List<AlbumModels.Photo> photos, int index, @Nullable PhotoPolicyListener listener){
		policyListener=listener;
		this.activity=activity;
		this.accountID=accountID;
		this.photos=new ArrayList<>(photos);
		currentIndex=index;
		session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		accountPrefs=activity.getSharedPreferences("account_manager", Context.MODE_PRIVATE);
		accountListener=(prefs,key)->{ if("lastActiveAccount".equals(key)) close(); };
	}

	private void show(){
		List<Attachment> attachments=new ArrayList<>();
		for(AlbumModels.Photo photo:photos) attachments.add(attachment(photo));
		viewer=new PhotoViewer(activity, null, attachments, currentIndex, null, accountID, new PhotoViewer.Listener(){
			@Override public void setPhotoViewVisibility(int index, boolean visible){}
			@Override public boolean startPhotoViewTransition(int index, @NonNull Rect rect, @NonNull int[] radius){ return false; }
			@Override public void setTransitioningViewTransform(float x, float y, float scale){}
			@Override public void endPhotoViewTransition(){}
			@Override public Drawable getPhotoViewCurrentDrawable(int index){ return null; }
			@Override public void photoViewerDismissed(){}
			@Override public void onRequestPermissions(String[] permissions){}
		}, this);
		if(supportsComments()){
			createCommentsPanel();
			viewer.setAlbumCommentsPanel(commentsPanel);
		}
		accountPrefs.registerOnSharedPreferenceChangeListener(accountListener);
		activity.getApplication().registerActivityLifecycleCallbacks(this);
		main.post(sessionWatcher);
		main.postDelayed(policyWatcher, 45_000);
		main.post(()->onPhotoChanged(currentIndex));
	}

	static Attachment attachment(AlbumModels.Photo photo){
		Attachment attachment=new Attachment();
		attachment.id=photo.id;
		attachment.type=Attachment.Type.IMAGE;
		// An authorized original/HD URL must never be installed in an Attachment or ImageCache.
		attachment.url=photo.previewUrl;
		attachment.previewUrl=photo.previewUrl;
		attachment.description=photo.description;
		attachment.meta=new Attachment.Metadata();
		attachment.meta.width=photo.width;
		attachment.meta.height=photo.height;
		return attachment;
	}

	static boolean mayAutoLoadHd(AlbumModels.Photo photo){
		return photo!=null && photo.isOwner && photo.ownerSponsor && !TextUtils.isEmpty(photo.hdUrl);
	}

	static boolean mayUseLossless(AlbumModels.Photo photo){ return photo!=null && photo.isOwner && photo.originalAvailable; }

	static boolean protectedNonowner(AlbumModels.Photo photo){ return photo!=null && photo.downloadProtected && !photo.isOwner; }

	static boolean mayDownload(AlbumModels.Photo photo){
		return photo!=null && (photo.isOwner || !photo.downloadProtected && !Boolean.FALSE.equals(photo.canDownload));
	}

	static Map<String,Object> authorizationBody(String variant, String operation, Integer noticeVersion){
		return authorizationBody(variant, operation, noticeVersion, false);
	}

	static Map<String,Object> authorizationBody(String variant, String operation, Integer noticeVersion, boolean download){
		if(!"hd".equals(variant) && !"original".equals(variant)) throw new IllegalArgumentException("Unknown variant");
		UUID.fromString(operation);
		Map<String,Object> body=new HashMap<>();
		body.put("variant", variant);
		body.put("operation_id", operation);
		body.put("intent", download ? "download" : "view");
		if(noticeVersion!=null) body.put("notice_version", noticeVersion);
		return body;
	}

	private boolean sessionValid(){
		return !closed && !activity.isFinishing() && !activity.isDestroyed() && session!=null
				&& AccountSessionManager.getInstance().tryGetAccount(accountID)==session
				&& accountID.equals(AccountSessionManager.getInstance().getLastActiveAccountID());
	}

	private boolean live(long token){ return token==generation && !paused && sessionValid() && viewer!=null && viewer.isAlbumHostValid(); }

	private void watchSession(){
		if(closed) return;
		if(!sessionValid()){ close(); return; }
		main.postDelayed(sessionWatcher, 250);
	}

	private void watchPolicy(){
		if(closed || paused) return;
		if(live(generation) && !previewRefreshing){
			long policyGeneration=generation;
			refreshPreview(currentIndex, new PhotoViewer.AlbumPreviewCallback(){
				@Override public void onRefreshed(String url, String description, int width, int height){
					// Policy-only polling must not downgrade a selected HD/original or reset zoom.
					if(policyGeneration!=generation) main.post(()->{ if(live(generation)) viewer.reloadAlbumPreview(); });
				}
				@Override public void onFailed(){}
				@Override public void onAccessDenied(){ close(); }
			});
		}
		main.postDelayed(policyWatcher, 45_000);
	}

	@Override public PhotoViewer.AlbumInfo getInfo(int position){
		AlbumModels.Photo photo=photos.get(position);
		PhotoViewer.AlbumInfo info=new PhotoViewer.AlbumInfo();
		info.isOwner=photo.isOwner;
		info.downloadProtected=photo.downloadProtected;
		info.canDownload=mayDownload(photo);
		info.originalAvailable=photo.originalAvailable;
		info.liked=photo.liked;
		info.likesCount=photo.likesCount;
		info.commentsCount=photo.commentsCount;
		info.hdLoaded=position==currentIndex && hdLoaded;
		info.originalLoaded=position==currentIndex && originalLoaded;
		info.mediaBusy=mediaAction!=null || permissionWaiting || previewRefreshing;
		info.likeBusy=likeBusy;
		return info;
	}

	@Override public void onPhotoChanged(int position){
		if(viewer==null || closed || paused || !live(generation)) return;
		if(position!=currentIndex) cancelPending();
		if(initializedGeneration==generation && position==currentIndex) return;
		initializedGeneration=generation;
		currentIndex=position;
		hdLoaded=originalLoaded=false;
		comments.clear(); commentsOffset=0; commentsHasMore=false;
		if(commentInput!=null) commentInput.setText("");
		if(commentAdapter!=null) commentAdapter.notifyDataSetChanged();
		if(supportsComments() && commentsOpen) loadComments(true);
		viewer.refreshAlbumControls();
	}

	static AlbumRequest<AlbumModels.PhotoDetailResponse> previewRequest(String photoId){
		return AlbumRequest.get("/photos/"+pathId(photoId), AlbumModels.PhotoDetailResponse.class);
	}

	/** Renew ACL and signed preview with GET only: never authorize/debit from a page entry or retry. */
	@Override public void refreshPreview(int position, PhotoViewer.AlbumPreviewCallback callback){
		if(!live(generation) || position!=currentIndex){ callback.onFailed(); return; }
		if(previewRequest!=null){ previewRequest.cancel(); requests.remove(previewRequest); }
		long token=generation, revision=++previewRevision;
		previewRefreshing=true;
		AlbumModels.Photo previous=photos.get(position);
		AlbumRequest<AlbumModels.PhotoDetailResponse> request=previewRequest(previous.id);
		previewRequest=request;
		viewer.refreshAlbumControls();
		execute(request, token, result->{
			if(revision!=previewRevision || position!=currentIndex) return;
			previewRequest=null; previewRefreshing=false;
			AlbumModels.Photo photo=result==null ? null : result.photo;
			if(photo==null || !previous.id.equals(photo.id) || TextUtils.isEmpty(photo.previewUrl)){
				callback.onFailed(); Toast.makeText(activity, R.string.album_viewer_image_error, Toast.LENGTH_LONG).show(); return;
			}
			photos.set(position, photo); // Owner/sponsor/original availability and social metadata come from renewed ACL DTO.
			revokeChangedPolicy(previous, photo);
			viewer.refreshAlbumControls();
			try{
				if(policyListener!=null) policyListener.onPhotoPolicyChanged(photo);
			}catch(RuntimeException error){
				cancelPending(); viewer.blockAlbumDismissal(); callback.onFailed();
				Toast.makeText(activity, R.string.error, Toast.LENGTH_LONG).show(); return;
			}
			callback.onRefreshed(photo.previewUrl, photo.description, photo.width, photo.height);
			if(permissionDownload!=null && live(token) && mayDownload(photo)){
				Runnable download=permissionDownload; permissionDownload=null; main.post(download);
				return;
			}
			if(live(token) && revision==previewRevision && mayAutoLoadHd(photo) && !hdLoaded && mediaAction==null){
				MediaAction action=new MediaAction(position, "hd", false, token);
				mediaAction=action; viewer.refreshAlbumControls();
				loadAuthorized(action, photo.hdUrl, true);
			}
		}, error->{
			if(revision!=previewRevision || position!=currentIndex) return;
			previewRequest=null; previewRefreshing=false;
			if(error instanceof MastodonErrorResponse response && (response.httpStatus==403 || response.httpStatus==404)){
				callback.onAccessDenied(); return;
			}
			viewer.refreshAlbumControls(); callback.onFailed(); error.showToast(activity);
		});
	}

	private void revokeChangedPolicy(AlbumModels.Photo previous, AlbumModels.Photo photo){
		if(!(!protectedNonowner(previous) && protectedNonowner(photo)
				|| mayDownload(previous) && !mayDownload(photo)
				|| mayUseLossless(previous) && !mayUseLossless(photo))) return;
		// Invalidate authorization/retry/permission generations and in-flight byte saves.
		cancelPending();
		permissionWaiting=false; permissionGeneration=-1;
		if(permissionFragment!=null){ permissionFragment.completed=null; permissionFragment=null; }
		viewer.revokeAlbumQuality();
	}

	@Override public void onView(int position, String variant){
		if(!live(generation) || position!=currentIndex || protectedNonowner(photos.get(position))
				|| mediaAction!=null || permissionWaiting || previewRefreshing) return;
		if("original".equals(variant) && !mayUseLossless(photos.get(position))) return;
		beginMedia(position, variant, false);
	}

	@Override public void onDownload(int position){
		if(!live(generation) || position!=currentIndex || !mayDownload(photos.get(position))
				|| mediaAction!=null || permissionWaiting || previewRefreshing) return;
		long token=generation;
		if(mayUseLossless(photos.get(position))){
			actionDialog=new M3AlertDialogBuilder(activity).setTitle(R.string.album_viewer_download_quality)
					.setItems(new String[]{activity.getString(R.string.album_viewer_download_hd), activity.getString(R.string.album_viewer_download_lossless)}, (dialog,which)->{
						actionDialog=null;
						if(live(token) && position==currentIndex) requestDownload(position, which==0 ? "hd" : "original");
					}).setNegativeButton(R.string.cancel, null).show();
		}else requestDownload(position, "hd");
	}

	private void requestDownload(int position, String variant){
		if(!live(generation) || position!=currentIndex || !mayDownload(photos.get(position))) return;
		if(Build.VERSION.SDK_INT<29 && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
			// Permission precedes authorization, so opening the system prompt can never consume quota.
			permissionWaiting=true;
			permissionGeneration=generation;
			viewer.refreshAlbumControls();
			permissionFragment=new PermissionFragment();
			permissionFragment.completed=granted->{
				permissionWaiting=false; permissionFragment=null;
					if(!sessionValid() || position!=currentIndex || permissionGeneration!=generation || !mayDownload(photos.get(position))) return;
				long token=permissionGeneration;
				if(granted){
					permissionDownload=()->{ if(live(token) && position==currentIndex) beginMedia(position, variant, true); };
						if(!paused) viewer.reloadAlbumPreview(); // Fresh GET completes before a permission-delayed authorization.
				}else Toast.makeText(activity, R.string.storage_permission_to_download, Toast.LENGTH_LONG).show();
				viewer.refreshAlbumControls();
			};
			try{
				activity.getFragmentManager().beginTransaction().add(permissionFragment, "album-viewer-permission-"+UUID.randomUUID()).commit();
				activity.getFragmentManager().executePendingTransactions();
				permissionFragment.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PhotoViewer.PERMISSION_REQUEST);
			}catch(RuntimeException error){ permissionWaiting=false; Toast.makeText(activity, R.string.error, Toast.LENGTH_LONG).show(); }
		}else beginMedia(position, variant, true);
	}

	private void beginMedia(int position, String variant, boolean download){
		if(!live(generation) || position!=currentIndex || mediaAction!=null || previewRefreshing
				|| protectedNonowner(photos.get(position)) || download && !mayDownload(photos.get(position))) return;
		if("original".equals(variant) && !mayUseLossless(photos.get(position))) return;
		MediaAction action=new MediaAction(position, variant, download, generation);
		mediaAction=action;
		viewer.refreshAlbumControls();
		authorize(action, null);
	}

	private boolean mediaLive(MediaAction action){
		return mediaAction==action && live(action.generation) && action.position==currentIndex
				&& !protectedNonowner(photos.get(action.position))
				&& (!action.download || mayDownload(photos.get(action.position)))
				&& (!"original".equals(action.variant) || mayUseLossless(photos.get(action.position)));
	}

	private void authorize(MediaAction action, Integer noticeVersion){
		if(!mediaLive(action)) return;
		action.noticeVersion=noticeVersion;
		AlbumRequest<AlbumModels.Authorization> request=AlbumRequest.post("/photos/"+pathId(photos.get(action.position).id)+"/authorize",
				AlbumModels.Authorization.class, authorizationBody(action.variant, action.operationId, noticeVersion, action.download));
		execute(request, action.generation, result->{
			if(!mediaLive(action)) return;
			if(result==null || TextUtils.isEmpty(result.url) || result.expiresAt<=System.currentTimeMillis()/1000){
				retryMedia(action, ()->authorize(action, action.noticeVersion), null); return;
			}
			loadAuthorized(action, result.url, false);
		}, error->{
			if(!mediaLive(action)) return;
			if(error instanceof SponsorRequest.SponsorError sponsorError){
				if("notice_required".equals(sponsorError.code) && action.noticeRefreshes++<2){ loadSponsorCopy(action, sponsorError, false); return; }
				if("quota_exhausted".equals(sponsorError.code)){ loadSponsorCopy(action, sponsorError, true); return; }
				if("album_download_protected".equals(sponsorError.code)){
					finishMedia(action); viewer.reloadAlbumPreview(); error.showToast(activity); return;
				}
				// Disabled/unavailable lossless and expired operations are never local permissions.
				if(!Set.of("unknown", "network_error").contains(sponsorError.code)){ finishMedia(action); error.showToast(activity); return; }
			}
			if(error instanceof MastodonErrorResponse response && (response.httpStatus==403 || response.httpStatus==404)){
				finishMedia(action); viewer.reloadAlbumPreview(); error.showToast(activity); return;
			}
			retryMedia(action, ()->authorize(action, action.noticeVersion), error);
		});
	}

	private void loadSponsorCopy(MediaAction action, SponsorRequest.SponsorError error, boolean exhausted){
		execute(SponsorRequest.catalog(), action.generation, catalog->{
			if(!mediaLive(action)) return;
			if(catalog==null || catalog.config==null || !Boolean.TRUE.equals(catalog.config.enabled)){
				finishMedia(action); error.showToast(activity); return;
			}
			execute(SponsorRequest.me(), action.generation, me->{
				if(!mediaLive(action)) return;
				if(me==null || me.sponsor==null || me.quota==null || !me.quota.valid() || me.configVersion!=catalog.config.version
						|| !exhausted && error.noticeVersion!=null && error.noticeVersion!=catalog.config.noticeVersion){
					finishMedia(action); Toast.makeText(activity, R.string.error, Toast.LENGTH_LONG).show(); return;
				}
				SponsorModels.Config config=catalog.config;
				SponsorModels.Quota quota=error.quota==null ? me.quota : error.quota;
				String body=SponsorUi.render(exhausted ? (me.sponsor.isActive() ? config.sponsorExhaustedBody : config.exhaustedBody) : config.noticeBody, config, quota);
				boolean[] acted={false};
				noticeSheet=new SponsorNoticeSheet(activity, exhausted ? config.exhaustedTitle : config.noticeTitle, body,
						exhausted ? null : activity.getString(R.string.sponsor_ui_continue_free), exhausted ? null : ()->{
							acted[0]=true; noticeSheet=null;
							if(mediaLive(action)) authorize(action, config.noticeVersion);
						}, exhausted && !me.sponsor.isActive() ? ()->{
							acted[0]=true; noticeSheet=null;
							if(mediaLive(action)){ close(); Bundle args=new Bundle(); args.putString("account", accountID); Nav.go(activity, SponsorCenterFragment.class, args); }
						} : null);
				noticeSheet.setOnDismissListener(dialog->main.post(()->{ if(!acted[0] && mediaLive(action)){ noticeSheet=null; finishMedia(action); } }));
				noticeSheet.show();
			}, failed->{ if(mediaLive(action)){ finishMedia(action); failed.showToast(activity); } });
		}, failed->{ if(mediaLive(action)){ finishMedia(action); failed.showToast(activity); } });
	}

	private void loadAuthorized(MediaAction action, String url, boolean automatic){
		if(!mediaLive(action)) return;
		PhotoViewer.AlbumSourceCallback callback=new PhotoViewer.AlbumSourceCallback(){
			@Override public void onLoaded(){
				if(!mediaLive(action)) return;
				if(!action.download){ hdLoaded=true; originalLoaded="original".equals(action.variant); }
				finishMedia(action);
			}
			@Override public void onFailed(){
				if(!mediaLive(action)) return;
				if(automatic){
					// Stale DTO may outlive sponsorship: NEVER authorize/charge from an automatic load.
					finishMedia(action);
					Toast.makeText(activity, R.string.album_viewer_image_error, Toast.LENGTH_LONG).show();
				}else retryMedia(action, ()->authorize(action, action.noticeVersion), null);
			}
		};
		// URL lives only in this call. A retry replays the album operation to obtain a fresh URL.
		if(action.download) viewer.saveAlbumSource(action.position, url, callback);
		else viewer.updateAlbumSource(action.position, url, callback);
	}

	private void retryMedia(MediaAction action, Runnable retry, ErrorResponse error){
		if(!mediaLive(action)) return;
		if(error!=null) error.showToast(activity);
		boolean[] retried={false};
		actionDialog=new M3AlertDialogBuilder(activity).setMessage(R.string.album_viewer_retry_action)
				.setPositiveButton(R.string.album_viewer_retry, (dialog,which)->{ retried[0]=true; actionDialog=null; if(mediaLive(action)) retry.run(); })
				.setNegativeButton(R.string.cancel, null).show();
		actionDialog.setOnDismissListener(dialog->{ if(!retried[0] && mediaLive(action)){ actionDialog=null; finishMedia(action); } });
	}

	private void finishMedia(MediaAction action){
		if(mediaAction!=action) return;
		mediaAction=null;
		viewer.refreshAlbumControls();
	}

	@Override public void onLike(int position){
		if(!live(generation) || likeBusy || position!=currentIndex) return;
		AlbumModels.Photo photo=photos.get(position);
		likeBusy=true;
		viewer.refreshAlbumControls();
		execute(AlbumRequest.post("/photos/"+pathId(photo.id)+"/like", AlbumModels.LikeResponse.class, Map.of("liked", !photo.liked)), generation, result->{
			likeBusy=false;
			if(result!=null && result.likesCount>=0){ photo.liked=result.liked; photo.likesCount=result.likesCount; }
			else Toast.makeText(activity, R.string.error, Toast.LENGTH_LONG).show();
			viewer.refreshAlbumControls();
		}, error->{ likeBusy=false; viewer.refreshAlbumControls(); error.showToast(activity); });
	}

	/** Hide only the album entry; server comment data/APIs and ordinary post replies are preserved. */
	@Override public boolean supportsComments(){ return false; }

	@Override public void onComments(int position){
		if(!supportsComments() || !live(generation) || position!=currentIndex) return;
		commentsOpen=true;
		viewer.setAlbumCommentsVisible(true);
		commentsPanel.requestFocus();
		loadComments(true);
	}

	@Override public void onCommentsClosed(){
		commentsOpen=false;
		if(commentInput!=null) commentInput.clearFocus();
	}

	private void createCommentsPanel(){
		Context context=new ContextThemeWrapper(activity, R.style.Theme_Mastodon_Dark);
		commentsPanel=new LinearLayout(context);
		commentsPanel.setOrientation(LinearLayout.VERTICAL);
		commentsPanel.setBackgroundColor(0xff161616);
		commentsPanel.setPadding(V.dp(8), 0, V.dp(8), 0);
		commentsPanel.setFocusableInTouchMode(true);
		LinearLayout header=new LinearLayout(context); header.setGravity(Gravity.CENTER_VERTICAL);
		TextView title=new TextView(context); title.setText(R.string.album_viewer_comments); title.setTextAppearance(R.style.m3_title_medium); title.setTextColor(0xffffffff);
		if(Build.VERSION.SDK_INT>=28) title.setAccessibilityHeading(true);
		header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
		Button close=new Button(context, null, 0, R.style.Widget_Mastodon_M3_Button_Text);
		close.setText(R.string.back); close.setContentDescription(activity.getString(R.string.album_viewer_close_comments)); close.setMinHeight(V.dp(48));
		close.setOnClickListener(v->{ viewer.setAlbumCommentsVisible(false); onCommentsClosed(); });
		header.addView(close, new LinearLayout.LayoutParams(-2, -2));
		commentsPanel.addView(header, new LinearLayout.LayoutParams(-1, -2));
		commentsList=new RecyclerView(context);
		commentsList.setLayoutManager(new LinearLayoutManager(context));
		commentAdapter=new CommentAdapter(); commentsList.setAdapter(commentAdapter);
		commentsList.setContentDescription(activity.getString(R.string.album_viewer_comments));
		commentsList.addOnScrollListener(new RecyclerView.OnScrollListener(){
			@Override public void onScrolled(@NonNull RecyclerView recycler, int dx, int dy){
				if(dy>0 && !recycler.canScrollVertically(1) && commentsHasMore && !commentsLoading) loadComments(false);
			}
		});
		commentsPanel.addView(commentsList, new LinearLayout.LayoutParams(-1, 0, 1));
		commentsStatus=new TextView(context); commentsStatus.setTextColor(0xffdddddd); commentsStatus.setTextAppearance(R.style.m3_body_medium);
		commentsStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
		commentsStatus.setOnClickListener(v->loadComments(commentsOffset==0));
		commentsPanel.addView(commentsStatus, new LinearLayout.LayoutParams(-1, -2));
		moreComments=new Button(context, null, 0, R.style.Widget_Mastodon_M3_Button_Text);
		moreComments.setText(R.string.album_viewer_comment_more); moreComments.setMinHeight(V.dp(48));
		moreComments.setOnClickListener(v->loadComments(false));
		commentsPanel.addView(moreComments, new LinearLayout.LayoutParams(-1, -2));
		LinearLayout composer=new LinearLayout(context); composer.setGravity(Gravity.CENTER_VERTICAL);
		commentInput=new EditText(context); commentInput.setHint(R.string.album_viewer_comment_hint); commentInput.setTextColor(0xffffffff);
		commentInput.setTextSize(16); commentInput.setMinHeight(V.dp(48)); commentInput.setMaxLines(3);
		commentInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
		commentInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
		commentInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
		commentInput.setContentDescription(activity.getString(R.string.album_viewer_comment_hint));
		commentInput.setOnEditorActionListener((v,action,event)->{ if(action==EditorInfo.IME_ACTION_SEND){ addComment(); return true; } return false; });
		composer.addView(commentInput, new LinearLayout.LayoutParams(0, -2, 1));
		sendComment=new Button(context, null, 0, R.style.Widget_Mastodon_M3_Button_Text); sendComment.setText(R.string.album_viewer_comment_send); sendComment.setMinHeight(V.dp(48));
		sendComment.setOnClickListener(v->addComment()); composer.addView(sendComment, new LinearLayout.LayoutParams(-2, -2));
		commentsPanel.addView(composer, new LinearLayout.LayoutParams(-1, -2));
		updateCommentsControls();
	}

	private void loadComments(boolean reset){
		if(!supportsComments() || !live(generation) || !commentsOpen || !reset && commentsLoading) return;
		if(reset){
			if(commentsRequest!=null){ commentsRequest.cancel(); requests.remove(commentsRequest); commentsRequest=null; }
			commentsOffset=0; comments.clear(); commentAdapter.notifyDataSetChanged();
		}
		long commentToken=++commentsGeneration;
		commentsLoading=true;
		commentsStatus.setText(R.string.album_viewer_comment_loading);
		commentsStatus.setVisibility(View.VISIBLE); commentsStatus.setFocusable(false);
		updateCommentsControls();
		int requestedOffset=commentsOffset;
		AlbumRequest<AlbumModels.CommentsResponse> request=AlbumRequest.get("/photos/"+pathId(photos.get(currentIndex).id)+"/comments", AlbumModels.CommentsResponse.class)
				.query("limit", COMMENTS_LIMIT).query("offset", requestedOffset);
		commentsRequest=request;
		execute(request, generation, result->{
			if(commentToken!=commentsGeneration) return;
			commentsRequest=null;
			commentsLoading=false;
			if(result==null || result.comments==null){ commentsFailed(); return; }
			Set<String> ids=new HashSet<>(); for(AlbumModels.Comment comment:comments) ids.add(comment.id);
			for(AlbumModels.Comment comment:result.comments) if(comment!=null && comment.id!=null && ids.add(comment.id)) comments.add(comment);
			commentsOffset=requestedOffset+result.comments.size();
			commentsHasMore=result.hasMore && !result.comments.isEmpty();
			commentAdapter.notifyDataSetChanged();
			commentsStatus.setText(R.string.album_viewer_comment_empty);
			commentsStatus.setVisibility(comments.isEmpty() ? View.VISIBLE : View.GONE);
			commentsStatus.setFocusable(false);
			updateCommentsControls();
		}, error->{
			if(commentToken!=commentsGeneration) return;
			commentsRequest=null; commentsLoading=false; commentsFailed(); error.showToast(activity);
		});
	}

	private void commentsFailed(){
		commentsStatus.setText(R.string.album_viewer_comment_failed); commentsStatus.setVisibility(View.VISIBLE);
		commentsStatus.setMinHeight(V.dp(48)); commentsStatus.setFocusable(true);
		updateCommentsControls();
	}

	private void updateCommentsControls(){
		if(sendComment==null) return;
		sendComment.setEnabled(!commentBusy);
		commentInput.setEnabled(!commentBusy);
		moreComments.setVisibility(commentsHasMore ? View.VISIBLE : View.GONE);
		moreComments.setEnabled(!commentsLoading);
	}

	private void addComment(){
		if(!supportsComments() || !live(generation) || commentBusy) return;
		String content=commentInput.getText().toString().trim();
		if(content.isEmpty() || content.length()>2000){ Toast.makeText(activity, R.string.album_viewer_comment_invalid, Toast.LENGTH_LONG).show(); return; }
		if(commentAction==null || !commentAction.content.equals(content)) commentAction=new CommentAction(content);
		CommentAction action=commentAction;
		commentBusy=true; updateCommentsControls();
		AlbumModels.Photo photo=photos.get(currentIndex);
		execute(AlbumRequest.post("/photos/"+pathId(photo.id)+"/comments", AlbumModels.CommentResponse.class,
				Map.of("content", content, "operation_id", action.operationId)), generation, result->{
			commentBusy=false;
			if(result==null || result.comment==null){ Toast.makeText(activity, R.string.error, Toast.LENGTH_LONG).show(); updateCommentsControls(); return; }
			commentAction=null; commentInput.setText(""); photo.commentsCount++;
			viewer.refreshAlbumControls(); updateCommentsControls(); loadComments(true);
		}, error->{ commentBusy=false; updateCommentsControls(); error.showToast(activity); });
	}

	private boolean ownComment(AlbumModels.Comment comment){ return comment!=null && session!=null && session.self!=null && String.valueOf(comment.userId).equals(session.self.id); }

	private void deleteComment(AlbumModels.Comment comment){
		if(!supportsComments() || !live(generation) || commentBusy || !ownComment(comment)) return;
		long token=generation;
		actionDialog=new M3AlertDialogBuilder(activity).setMessage(R.string.album_viewer_comment_delete_confirm)
				.setPositiveButton(R.string.delete, (dialog,which)->{
					actionDialog=null;
					if(!live(token) || !ownComment(comment)) return;
					commentBusy=true; updateCommentsControls(); commentAdapter.notifyDataSetChanged();
					execute(AlbumRequest.delete("/comments/"+pathId(comment.id), AlbumModels.DeleteResponse.class), token, result->{
						commentBusy=false;
						if(result!=null && result.deleted){
							AlbumModels.Photo photo=photos.get(currentIndex); photo.commentsCount=Math.max(0, photo.commentsCount-1);
							viewer.refreshAlbumControls(); loadComments(true);
						}else Toast.makeText(activity, R.string.error, Toast.LENGTH_LONG).show();
						updateCommentsControls(); commentAdapter.notifyDataSetChanged();
					}, error->{ commentBusy=false; updateCommentsControls(); commentAdapter.notifyDataSetChanged(); error.showToast(activity); });
				}).setNegativeButton(R.string.cancel, null).show();
	}

	private final class CommentAdapter extends RecyclerView.Adapter<CommentHolder>{
		@Override public int getItemCount(){ return comments.size(); }
		@NonNull @Override public CommentHolder onCreateViewHolder(@NonNull ViewGroup parent, int type){ return new CommentHolder(parent.getContext()); }
		@Override public void onBindViewHolder(@NonNull CommentHolder holder, int position){ holder.bind(comments.get(position)); }
	}

	private final class CommentHolder extends RecyclerView.ViewHolder{
		final TextView author, content;
		final Button delete;
		CommentHolder(Context context){
			super(new LinearLayout(context));
			LinearLayout row=(LinearLayout)itemView; row.setOrientation(LinearLayout.VERTICAL); row.setPadding(V.dp(8), V.dp(8), V.dp(8), V.dp(8));
			row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
			author=new TextView(context); author.setTextAppearance(R.style.m3_label_large); author.setTextColor(0xffcccccc);
			content=new TextView(context); content.setTextAppearance(R.style.m3_body_medium); content.setTextColor(0xffffffff); content.setTextIsSelectable(true);
			delete=new Button(context, null, 0, R.style.Widget_Mastodon_M3_Button_Text); delete.setText(R.string.album_viewer_comment_delete); delete.setMinHeight(V.dp(48));
			row.addView(author); row.addView(content); row.addView(delete);
		}
		void bind(AlbumModels.Comment comment){
			String name=TextUtils.isEmpty(comment.displayName) ? comment.username : comment.displayName;
			String date="";
			try{ date=DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(comment.createdAt)); }
			catch(RuntimeException ignored){}
			author.setText(name+" · "+date); content.setText(comment.content);
			delete.setVisibility(ownComment(comment) ? View.VISIBLE : View.GONE); delete.setEnabled(!commentBusy);
			delete.setContentDescription(activity.getString(R.string.album_viewer_comment_delete)+"，"+comment.content);
			delete.setOnClickListener(v->deleteComment(comment));
		}
	}

	private <T> void execute(MastodonAPIRequest<T> request, long token, Consumer<T> success, Consumer<ErrorResponse> failure){
		if(!live(token)) return;
		requests.add(request);
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(T result){ main.post(()->{ requests.remove(request); if(live(token)) success.accept(result); }); }
			@Override public void onError(ErrorResponse error){ main.post(()->{ requests.remove(request); if(live(token)) failure.accept(error); }); }
		}).exec(accountID);
	}

	private static String pathId(String id){
		if(id==null || !id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid album identifier");
		return id;
	}

	@Override public void cancelPending(){
		generation++;
		permissionDownload=null;
		if(!paused || closed){
			permissionWaiting=false; permissionGeneration=-1;
			if(permissionFragment!=null){
				permissionFragment.completed=null;
				try{ activity.getFragmentManager().beginTransaction().remove(permissionFragment).commitAllowingStateLoss(); }catch(RuntimeException ignored){}
				permissionFragment=null;
			}
		}
		for(MastodonAPIRequest<?> request:new ArrayList<>(requests)) request.cancel();
		requests.clear();
		commentsRequest=null; commentsGeneration++;
		previewRequest=null; previewRevision++; previewRefreshing=false;
		mediaAction=null; likeBusy=commentsLoading=commentBusy=false;
		hdLoaded=originalLoaded=false;
		commentAction=null;
		if(noticeSheet!=null){ SponsorNoticeSheet sheet=noticeSheet; noticeSheet=null; sheet.dismiss(); }
		if(actionDialog!=null){ AlertDialog dialog=actionDialog; actionDialog=null; dialog.dismiss(); }
		if(viewer!=null){ viewer.cancelAlbumMedia(); viewer.refreshAlbumControls(); }
		updateCommentsControls();
	}

	private void close(){
		if(closed) return;
		if(viewer!=null) viewer.onDismissed();
		else onDismissed();
	}

	@Override public boolean onBeforeDismissed(){
		if(policyListener==null || policyDismissed) return true;
		try{
			policyListener.onPhotoPolicyChanged(photos.get(currentIndex));
			policyListener.onDismissed();
			policyDismissed=true;
			return true;
		}catch(RuntimeException error){
			cancelPending(); Toast.makeText(activity, R.string.error, Toast.LENGTH_LONG).show();
			return false;
		}
	}

	@Override public void onDismissed(){
		if(closed) return;
		closed=true;
		cancelPending();
		comments.clear();
		main.removeCallbacksAndMessages(null);
		accountPrefs.unregisterOnSharedPreferenceChangeListener(accountListener);
		activity.getApplication().unregisterActivityLifecycleCallbacks(this);
		if(permissionFragment!=null){
			permissionFragment.completed=null;
			try{ activity.getFragmentManager().beginTransaction().remove(permissionFragment).commitAllowingStateLoss(); }catch(RuntimeException ignored){}
			permissionFragment=null;
		}
	}

	@Override public void onActivityPaused(Activity host){
		if(host!=activity || closed) return;
		paused=true;
		main.removeCallbacks(policyWatcher);
		viewer.onPause(); // Cancels authorization, bytes, comments, likes, and all retry/notice dialogs.
		if(permissionWaiting) permissionGeneration=generation; // Only the permission prompt survives its own pause, before any authorization.
	}
	@Override public void onActivityResumed(Activity host){
		if(host!=activity || closed) return;
		paused=false;
		main.removeCallbacks(policyWatcher);
		if(!sessionValid()) close();
		else{
			main.postDelayed(policyWatcher, 45_000);
			if(permissionDownload!=null) viewer.reloadAlbumPreview();
			else viewer.resumeAlbum();
		}
	}
	@Override public void onActivityStopped(Activity host){ if(host==activity) close(); }
	@Override public void onActivityDestroyed(Activity host){ if(host==activity) close(); }
	@Override public void onActivityCreated(Activity host, Bundle state){}
	@Override public void onActivityStarted(Activity host){}
	@Override public void onActivitySaveInstanceState(Activity host, Bundle state){}

	/** No retained session/activity state after recreation, and no authorization during the prompt. */
	public static final class PermissionFragment extends Fragment{
		Consumer<Boolean> completed;
		@Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results){
			super.onRequestPermissionsResult(code, permissions, results);
			if(code!=PhotoViewer.PERMISSION_REQUEST) return;
			Consumer<Boolean> callback=completed; completed=null;
			if(callback!=null) callback.accept(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED);
			if(getFragmentManager()!=null) getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
		}
		@Override public void onCreate(Bundle state){
			super.onCreate(state);
			if(state!=null && getFragmentManager()!=null) getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
		}
	}

	static final class MediaAction{
		final int position;
		final String variant, operationId=UUID.randomUUID().toString();
		final boolean download;
		final long generation;
		Integer noticeVersion;
		int noticeRefreshes;
		MediaAction(int position, String variant, boolean download, long generation){ this.position=position; this.variant=variant; this.download=download; this.generation=generation; }
	}

	static final class CommentAction{
		final String content, operationId=UUID.randomUUID().toString();
		CommentAction(String content){ this.content=content; }
	}
}
