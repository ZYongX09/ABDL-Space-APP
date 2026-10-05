package org.joinmastodon.android.ui.photoviewer;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Property;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.StatusCountersUpdatedEvent;
import org.joinmastodon.android.fragments.BaseStatusListFragment;
import org.joinmastodon.android.fragments.ComposeFragment;
import org.joinmastodon.android.fragments.sponsors.SponsorCenterFragment;
import org.joinmastodon.android.sponsors.SponsorOriginalGate;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.Snackbar;
import org.joinmastodon.android.ui.drawables.VideoPlayerSeekBarThumbDrawable;
import org.joinmastodon.android.ui.utils.BlurHashDecoder;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.views.WindowRootFrameLayout;
import org.joinmastodon.android.utils.BroadcastCompat;
import org.parceler.Parcels;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.palette.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import me.grishka.appkit.Nav;
import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.ImageLoaderCallback;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.BindableViewHolder;
import me.grishka.appkit.utils.CubicBezierInterpolator;
import me.grishka.appkit.utils.V;
import me.grishka.appkit.views.BottomSheet;
import me.grishka.appkit.views.FragmentRootLinearLayout;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Sink;
import okio.Source;

public class PhotoViewer implements ZoomPanView.Listener{
	private static final String TAG="PhotoViewer";
	public static final int PERMISSION_REQUEST=926;

	private Activity activity;
	private List<Attachment> attachments;
	private int[] backgroundColors;
	private int currentIndex;
	private WindowManager wm;
	private Listener listener;
	private Status status;
	private String accountID;
	private BaseStatusListFragment<?> parentFragment;
	private final AlbumDelegate albumDelegate;
	private final AccountSession albumSession;
	private TextView albumLosslessButton, albumPreviewRetryButton;
	private LinearLayout albumQualityControls;
	private long albumPreviewGeneration=-1, albumPreviewRevision;
	private int albumPreviewPosition=-1, albumPreviewRenewals;
	private boolean albumPreviewRefreshing;
	private View albumCommentsPanel;
	private boolean albumCommentsVisible;
	private int albumSystemBottomInset, albumImeInset;
	private Drawable albumSourceDrawable;
	private int albumSourcePosition=-1;
	private Call albumMediaCall;
	private final Map<PhotoViewHolder, Call> albumPreviewCalls=new HashMap<>();
	private final Set<PhotoViewHolder> albumPhotoHolders=new HashSet<>();
	private final OkHttpClient albumHttpClient;
	private android.window.OnBackInvokedCallback albumBackCallback;

	private WindowRootFrameLayout windowView;
	private FragmentRootLinearLayout uiOverlay;
	private ViewPager2 pager;
	private ColorDrawable background=new ColorDrawable(0xff000000);
	private ArrayList<MediaPlayer> players=new ArrayList<>();
	private int screenOnRefCount=0;
	private View toolbarWrap;
	private SeekBar videoSeekBar;
	private TextView videoTimeView;
	private ImageButton videoPlayPauseButton;
	private View videoControls;
	private TextView altText;
	private ImageButton backButton, downloadButton;
	private TextView viewOriginalBtn;
	// “查看原图”：默认只显示预览图；点击后经 ImageCache 下载原图（带进度），
	// 完成后替换显示并隐藏按钮。已加载过的页在翻回时直接显示原图。
	private final Set<Integer> originalLoadedPositions=new HashSet<>();
	private int originalDownloadPosition=-1;
	private ImageCache.PendingImageRequest originalDownloadRequest;
	private SponsorOriginalGate originalGate;
	private long mediaGeneration;
	private boolean closing, dismissed;
	private Attachment pendingPermissionAttachment;
	private long pendingPermissionGeneration;
	private final android.content.SharedPreferences.OnSharedPreferenceChangeListener originalAccountListener=(prefs, key)->{
		if("lastActiveAccount".equals(key)) invalidateOriginalWork();
	};
	private View bottomBar;
	private View postActions;
	private View replyBtn, boostBtn, favoriteBtn, shareBtn, bookmarkBtn;
	private TextView replyText, boostText, favoriteText;
	private boolean uiVisible=true;
	private AudioManager.OnAudioFocusChangeListener audioFocusListener=this::onAudioFocusChanged;
	private Runnable uiAutoHider=()->{
		if(uiVisible)
			toggleUI();
	};
	private Animator currentUiVisibilityAnimation;

	private boolean videoPositionNeedsUpdating;
	private Runnable videoPositionUpdater=this::updateVideoPosition;
	private int videoDuration, videoInitialPosition, videoLastTimeUpdatePosition;
	private long videoInitialPositionTime;
	private long lastDownloadID;
	private boolean receiverRegistered;
	private int maxImageDimensions;

	private static final Property<FragmentRootLinearLayout, Integer> STATUS_BAR_COLOR_PROPERTY=new Property<>(Integer.class, "Fdsafdsa"){
		@Override
		public Integer get(FragmentRootLinearLayout object){
			return object.getStatusBarColor();
		}

		@Override
		public void set(FragmentRootLinearLayout object, Integer value){
			object.setStatusBarColor(value);
		}
	};

	private final BroadcastReceiver downloadCompletedReceiver=new BroadcastReceiver(){
		@Override
		public void onReceive(Context context, Intent intent){
			long id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
			if(id==lastDownloadID){
				new Snackbar.Builder(activity)
						.setText(R.string.video_saved)
						.setAction(R.string.view_file, ()->activity.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)))
						.show();
				activity.unregisterReceiver(this);
				receiverRegistered=false;
			}
		}
	};

	public PhotoViewer(Activity activity, BaseStatusListFragment<?> parentFragment, List<Attachment> attachments, int index, Status status, String accountID, Listener listener){
		this(activity, parentFragment, attachments, index, status, accountID, listener, null);
	}

	/** Optional album hooks reuse the same pager, zoom, description and action controls. */
	public PhotoViewer(Activity activity, BaseStatusListFragment<?> parentFragment, List<Attachment> attachments, int index, Status status, String accountID, Listener listener, @Nullable AlbumDelegate albumDelegate){
		this.albumDelegate=albumDelegate;
		albumSession=albumDelegate==null ? null : AccountSessionManager.getInstance().tryGetAccount(accountID);
		albumHttpClient=albumDelegate==null ? null : MastodonAPIController.getHttpClient().newBuilder()
				.cache(null).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
				.callTimeout(60, TimeUnit.SECONDS).build();
		this.activity=activity;
		this.attachments=attachments.stream().filter(a->a.type==Attachment.Type.IMAGE || a.type==Attachment.Type.GIFV || a.type==Attachment.Type.VIDEO).collect(Collectors.toList());
		currentIndex=index;
		this.listener=listener;
		this.status=status;
		this.accountID=accountID;
		this.parentFragment=parentFragment;

		backgroundColors=new int[this.attachments.size()];
		int i=0;
		float[] hsl=new float[3];
		for(Attachment att:this.attachments){
			if(TextUtils.isEmpty(att.blurhash)){
				backgroundColors[i]=0xff000000;
			}else{
				ColorUtils.colorToHSL(BlurHashDecoder.decodeToSingleColor(att.blurhash) | 0xff000000, hsl);
				hsl[2]=Math.min(hsl[2], 0.15f);
				backgroundColors[i]=ColorUtils.HSLToColor(hsl);
			}
			i++;
		}

		wm=activity.getWindowManager();

		Point displaySize=new Point();
		Display display;
		if(Build.VERSION.SDK_INT<Build.VERSION_CODES.R)
			display=wm.getDefaultDisplay();
		else
			display=activity.getDisplay();
		display.getRealSize(displaySize);
		maxImageDimensions=Math.max(4096, Math.max(displaySize.x, displaySize.y));

		windowView=new WindowRootFrameLayout(activity);
		windowView.setDispatchKeyEventListener((v, keyCode, event)->{
			if(event.getKeyCode()==KeyEvent.KEYCODE_BACK){
				if(event.getAction()==KeyEvent.ACTION_DOWN){
					if(albumDelegate!=null) onAlbumBack();
					else onStartSwipeToDismissTransition(0f);
				}
				return true;
			}
			return false;
		});
		windowView.setDispatchApplyWindowInsetsListener((v, insets)->{
			if(albumDelegate!=null) return applyAlbumInsets(insets);
			int bottomInset=insets.getSystemWindowInsetBottom();
			bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(), bottomBar.getPaddingRight(), bottomInset>0 ? Math.max(bottomInset+V.dp(8), V.dp(40)) : V.dp(12));
			insets=insets.replaceSystemWindowInsets(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), 0);
			if(Build.VERSION.SDK_INT>=29){
				DisplayCutout cutout=insets.getDisplayCutout();
				Insets tappable=insets.getTappableElementInsets();
				if(cutout!=null){
					// Make controls extend beneath the cutout, and replace insets to avoid cutout insets being filled with "navigation bar color"
					int leftInset=Math.max(0, cutout.getSafeInsetLeft()-tappable.left);
					int rightInset=Math.max(0, cutout.getSafeInsetRight()-tappable.right);
					toolbarWrap.setPadding(leftInset, 0, rightInset, 0);
					bottomBar.setPadding(leftInset, bottomBar.getPaddingTop(), rightInset, bottomBar.getPaddingBottom());
				}else{
					toolbarWrap.setPadding(0, 0, 0, 0);
					bottomBar.setPadding(0, bottomBar.getPaddingTop(), 0, bottomBar.getPaddingBottom());
				}
				insets=insets.replaceSystemWindowInsets(tappable.left, tappable.top, tappable.right, bottomBar.getVisibility()==View.VISIBLE ? 0 : tappable.bottom);
			}
			uiOverlay.dispatchApplyWindowInsets(insets);
			return insets.consumeSystemWindowInsets();
		});
		windowView.setBackground(background);
		background.setAlpha(0);
		pager=new ViewPager2(activity);
		pager.setAdapter(new PhotoViewAdapter());
		pager.setCurrentItem(index, false);
		pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback(){
			@Override
			public void onPageSelected(int position){
				onPageChanged(position);
			}

			@Override
			public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels){
				updateBackgroundColor(position, positionOffset);
			}
		});
		windowView.addView(pager);
		pager.setMotionEventSplittingEnabled(false);

		uiOverlay=activity.getLayoutInflater().inflate(R.layout.photo_viewer_ui, windowView).findViewById(R.id.photo_viewer_overlay);
		uiOverlay.setStatusBarColor(0x80000000);
		uiOverlay.setNavigationBarColor(0x80000000);
		toolbarWrap=uiOverlay.findViewById(R.id.toolbar_wrap);
		backButton=uiOverlay.findViewById(R.id.btn_back);
		backButton.setOnClickListener(v->onStartSwipeToDismissTransition(0));
		downloadButton=uiOverlay.findViewById(R.id.btn_download);
		downloadButton.setOnClickListener(v->saveCurrentFile());
		viewOriginalBtn=uiOverlay.findViewById(R.id.btn_view_original);
		viewOriginalBtn.setOnClickListener(v->startOriginalDownload());
		bottomBar=uiOverlay.findViewById(R.id.bottom_bar);
		postActions=uiOverlay.findViewById(R.id.post_actions);
		
		replyBtn=uiOverlay.findViewById(R.id.reply_btn);
		boostBtn=uiOverlay.findViewById(R.id.boost_btn);
		favoriteBtn=uiOverlay.findViewById(R.id.favorite_btn);
		bookmarkBtn=uiOverlay.findViewById(R.id.bookmark_btn);
		shareBtn=uiOverlay.findViewById(R.id.share_btn);
		replyText=uiOverlay.findViewById(R.id.reply);
		boostText=uiOverlay.findViewById(R.id.boost);
		favoriteText=uiOverlay.findViewById(R.id.favorite);
		
		uiOverlay.setAlpha(0f);
		videoControls=uiOverlay.findViewById(R.id.video_player_controls);
		videoSeekBar=uiOverlay.findViewById(R.id.seekbar);
		videoTimeView=uiOverlay.findViewById(R.id.time);
		videoPlayPauseButton=uiOverlay.findViewById(R.id.play_pause_btn);
		if(attachments.get(index).type!=Attachment.Type.VIDEO){
			videoControls.setVisibility(View.GONE);
		}else{
			videoDuration=(int)Math.round(attachments.get(index).getDuration()*1000);
			videoLastTimeUpdatePosition=-1;
			updateVideoTimeText(0);
		}
		altText=uiOverlay.findViewById(R.id.alt_text);
		altText.setOnClickListener(v->showAltTextSheet());
		updateAltText();
		updateBackgroundColor(currentIndex, 0);
		updateViewOriginalButton();

		if(albumDelegate!=null){
			configureAlbumControls();
		}else if(status==null){
			bottomBar.setVisibility(View.GONE);
		}else{
			Paint paint=new Paint();
			paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
			postActions.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
			updatePostActions();

			replyBtn.setOnClickListener(this::onPostActionClick);
			boostBtn.setOnClickListener(this::onPostActionClick);
			favoriteBtn.setOnClickListener(this::onPostActionClick);
			bookmarkBtn.setOnClickListener(this::onPostActionClick);
			shareBtn.setOnClickListener(this::onPostActionClick);
		}

		WindowManager.LayoutParams wlp=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
		wlp.type=WindowManager.LayoutParams.TYPE_APPLICATION;
		wlp.flags=WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
				| WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
		wlp.format=PixelFormat.TRANSLUCENT;
		wlp.setTitle(activity.getString(R.string.media_viewer));
		if(albumDelegate!=null) wlp.softInputMode=(Build.VERSION.SDK_INT>=30 ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
				| WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
		if(Build.VERSION.SDK_INT>=28)
			wlp.layoutInDisplayCutoutMode=Build.VERSION.SDK_INT>=30 ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
		windowView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
		wm.addView(windowView, wlp);
		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU){
			if(albumDelegate!=null){
				albumBackCallback=this::onAlbumBack;
				activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, albumBackCallback);
			}else{
				activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, ()->onStartSwipeToDismissTransition(0));
			}
		}

		windowView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
			@Override
			public boolean onPreDraw(){
				windowView.getViewTreeObserver().removeOnPreDrawListener(this);

				Rect rect=new Rect();
				int[] radius=new int[4];
				if(listener.startPhotoViewTransition(index, rect, radius)){
					RecyclerView rv=(RecyclerView) pager.getChildAt(0);
					BaseHolder holder=(BaseHolder) rv.findViewHolderForAdapterPosition(index);
					holder.zoomPanView.animateIn(rect, radius);
				}else if(albumDelegate!=null){
					background.setAlpha(255);
					uiOverlay.setAlpha(1f);
				}

				return true;
			}
		});

		videoPlayPauseButton.setOnClickListener(v->{
			MediaPlayer player=findCurrentVideoPlayer();
			if(player!=null){
				if(player.isPlaying())
					pauseVideo();
				else
					resumeVideo();
				hideUiDelayed();
			}
		});
		videoSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser){
				if(fromUser){
					float p=progress/10000f;
					updateVideoTimeText(Math.round(p*videoDuration));

					// This moves the time view in sync with the seekbar thumb, but also makes sure it doesn't go off screen
					// (there must be at least 16dp between the time and the edge of the screen)
					float timeX=p*(seekBar.getWidth()-V.dp(32))+V.dp(16)-videoTimeView.getWidth()/2f;
					videoTimeView.setTranslationX(Math.max(-(videoTimeView.getLeft()-V.dp(16)), Math.min(timeX, videoControls.getWidth()-V.dp(16)-videoTimeView.getWidth()-videoTimeView.getLeft())));
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar){
				stopUpdatingVideoPosition();
				if(!uiVisible) // If dragging started during hide animation
					toggleUI();
				windowView.removeCallbacks(uiAutoHider);
				V.setVisibilityAnimated(videoTimeView, View.VISIBLE);
				postActions.animate().alpha(0f).setDuration(300).setInterpolator(CubicBezierInterpolator.DEFAULT).start();
				altText.animate().alpha(0f).setDuration(300).setInterpolator(CubicBezierInterpolator.DEFAULT).start();
				if(altText.getVisibility()==View.VISIBLE){
					videoTimeView.setTranslationY(seekBar.getHeight()+V.dp(12));
				}else{
					videoTimeView.setTranslationY(-videoTimeView.getHeight()-V.dp(12));
				}
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar){
				MediaPlayer player=findCurrentVideoPlayer();
				if(player!=null){
					float progress=seekBar.getProgress()/10000f;
					if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)
						player.seekTo(Math.round(progress*player.getDuration()), MediaPlayer.SEEK_CLOSEST);
					else
						player.seekTo(Math.round(progress*player.getDuration()));
				}
				hideUiDelayed();
				V.setVisibilityAnimated(videoTimeView, View.INVISIBLE);
				postActions.animate().alpha(1f).setDuration(300).setInterpolator(CubicBezierInterpolator.DEFAULT).start();
				altText.animate().alpha(1f).setDuration(300).setInterpolator(CubicBezierInterpolator.DEFAULT).start();
			}
		});
		videoSeekBar.setThumb(new VideoPlayerSeekBarThumbDrawable());

		if(albumDelegate==null){
			originalGate=new SponsorOriginalGate(activity, accountID, ()->!closing && !dismissed && windowView.isAttachedToWindow()
					&& (parentFragment==null || accountID.equals(parentFragment.getAccountID())), this::updateOriginalControls, ()->{
				onDismissed(); // Nav must not leave the full-screen WindowManager overlay over the center.
				Bundle args=new Bundle();
				args.putString("account", accountID);
				Nav.go(activity, SponsorCenterFragment.class, args);
			});
		}
		activity.getSharedPreferences("account_manager", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(originalAccountListener);
		E.register(this);
		if(albumDelegate!=null) windowView.post(()->{
			for(PhotoViewHolder holder:albumPhotoHolders){ int position=holder.getAbsoluteAdapterPosition(); if(position>=0) loadAlbumPreview(holder, this.attachments.get(position)); }
			updateAlbumViewport();
		});
	}

	private void configureAlbumControls(){
		bottomBar.setVisibility(View.VISIBLE);
		bottomBar.setPadding(0, V.dp(8), 0, V.dp(12));
		boostBtn.setVisibility(View.GONE);
		bookmarkBtn.setVisibility(View.GONE);
		shareBtn.setVisibility(View.GONE);
		replyBtn.setFocusable(true);
		favoriteBtn.setFocusable(true);
		replyBtn.setOnClickListener(v->albumDelegate.onComments(currentIndex));
		favoriteBtn.setOnClickListener(v->albumDelegate.onLike(currentIndex));
		ViewGroup.LayoutParams actionParams=postActions.getLayoutParams();
		actionParams.height=V.dp(48);
		postActions.setLayoutParams(actionParams);
		((ViewGroup)viewOriginalBtn.getParent()).removeView(viewOriginalBtn);
		albumQualityControls=new LinearLayout(uiOverlay.getContext());
		albumQualityControls.setPadding(V.dp(16), 0, V.dp(16), V.dp(8));
		albumQualityControls.setGravity(Gravity.CENTER);
		viewOriginalBtn.setMinHeight(V.dp(48));
		viewOriginalBtn.setFocusable(true);
		viewOriginalBtn.setGravity(Gravity.CENTER);
		albumQualityControls.addView(viewOriginalBtn, new LinearLayout.LayoutParams(0, -2, 1));
		albumLosslessButton=new TextView(uiOverlay.getContext());
		albumLosslessButton.setTextAppearance(R.style.m3_label_large);
		albumLosslessButton.setTextColor(0xffffffff);
		albumLosslessButton.setGravity(Gravity.CENTER);
		albumLosslessButton.setPadding(V.dp(8), V.dp(8), V.dp(8), V.dp(8));
		albumLosslessButton.setMinHeight(V.dp(48));
		albumLosslessButton.setBackgroundResource(R.drawable.bg_view_original_pill);
		albumLosslessButton.setFocusable(true);
		albumLosslessButton.setOnClickListener(v->albumDelegate.onView(currentIndex, "original"));
		LinearLayout.LayoutParams losslessParams=new LinearLayout.LayoutParams(0, -2, 1);
		losslessParams.leftMargin=V.dp(8);
		albumQualityControls.addView(albumLosslessButton, losslessParams);
		((LinearLayout)bottomBar).addView(albumQualityControls, 0);
		albumPreviewRetryButton=new TextView(uiOverlay.getContext());
		albumPreviewRetryButton.setText(R.string.album_viewer_preview_retry);
		albumPreviewRetryButton.setTextAppearance(R.style.m3_label_large);
		albumPreviewRetryButton.setTextColor(0xffffffff);
		albumPreviewRetryButton.setGravity(Gravity.CENTER);
		albumPreviewRetryButton.setMinHeight(V.dp(48));
		albumPreviewRetryButton.setFocusable(true);
		albumPreviewRetryButton.setVisibility(View.GONE);
		albumPreviewRetryButton.setOnClickListener(v->{ albumPreviewRenewals=0; renewAlbumPreview(currentIndex); });
		((LinearLayout)bottomBar).addView(albumPreviewRetryButton, 0, new LinearLayout.LayoutParams(-1, -2));
		bottomBar.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateAlbumViewport());
		toolbarWrap.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateAlbumViewport());
		windowView.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateAlbumViewport());
		backButton.setOnClickListener(v->onAlbumBack());
		refreshAlbumControls();
	}

	public boolean isAlbumHostValid(){
		return albumDelegate!=null && !closing && !dismissed && !activity.isFinishing() && !activity.isDestroyed()
				&& windowView.isAttachedToWindow() && albumSession!=null
				&& AccountSessionManager.getInstance().tryGetAccount(accountID)==albumSession
				&& accountID.equals(AccountSessionManager.getInstance().getLastActiveAccountID());
	}

	public int getCurrentIndex(){ return currentIndex; }

	public void refreshAlbumControls(){
		if(albumDelegate==null || closing || dismissed || albumLosslessButton==null) return;
		AlbumInfo info=albumDelegate.getInfo(currentIndex);
		bindActionButton(replyText, info.commentsCount);
		bindActionButton(favoriteText, info.likesCount);
		favoriteBtn.setSelected(info.liked);
		favoriteBtn.setEnabled(!info.likeBusy);
		favoriteBtn.setContentDescription(activity.getString(info.liked ? R.string.album_viewer_unlike : R.string.album_viewer_like, info.likesCount));
		replyBtn.setContentDescription(activity.getString(R.string.album_viewer_comment_count, info.commentsCount));
		viewOriginalBtn.setVisibility(info.hdLoaded ? View.GONE : View.VISIBLE);
		viewOriginalBtn.setText(info.mediaBusy ? R.string.album_viewer_busy : R.string.album_viewer_hd);
		viewOriginalBtn.setEnabled(!info.mediaBusy);
		albumLosslessButton.setVisibility(info.isOwner && info.originalAvailable && !info.originalLoaded ? View.VISIBLE : View.GONE);
		albumLosslessButton.setText(info.mediaBusy ? R.string.album_viewer_busy : R.string.album_viewer_lossless);
		albumLosslessButton.setEnabled(!info.mediaBusy);
		albumQualityControls.setVisibility(albumCommentsVisible || viewOriginalBtn.getVisibility()!=View.VISIBLE && albumLosslessButton.getVisibility()!=View.VISIBLE ? View.GONE : View.VISIBLE);
		postActions.setVisibility(albumCommentsVisible && albumImeInset>0 ? View.GONE : View.VISIBLE);
		downloadButton.setEnabled(!info.mediaBusy);
		updateAltText();
		if(albumCommentsVisible) altText.setVisibility(View.GONE);
	}

	/** A child of this overlay, not a dialog above the photo. */
	public void setAlbumCommentsPanel(View panel){
		if(albumDelegate==null || dismissed) return;
		if(albumCommentsPanel!=null) ((ViewGroup)albumCommentsPanel.getParent()).removeView(albumCommentsPanel);
		albumCommentsPanel=panel;
		panel.setVisibility(View.GONE);
		((LinearLayout)bottomBar).addView(panel, new LinearLayout.LayoutParams(-1, V.dp(280)));
	}

	public void setAlbumCommentsVisible(boolean visible){
		if(albumDelegate==null || albumCommentsPanel==null || dismissed) return;
		if(visible && !uiVisible) toggleUI();
		albumCommentsVisible=visible;
		albumCommentsPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
		if(!visible){
			activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(windowView.getWindowToken(), 0);
			windowView.clearFocus();
		}
		refreshAlbumControls();
		windowView.post(this::updateAlbumViewport);
	}

	private void onAlbumBack(){
		if(albumCommentsVisible){
			if(albumImeInset>0) activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(windowView.getWindowToken(), 0);
			else{ setAlbumCommentsVisible(false); albumDelegate.onCommentsClosed(); replyBtn.requestFocus(); }
		}else onStartSwipeToDismissTransition(0);
	}

	private WindowInsets applyAlbumInsets(WindowInsets insets){
		if(Build.VERSION.SDK_INT>=30){
			albumSystemBottomInset=insets.getInsets(WindowInsets.Type.systemBars()).bottom;
			albumImeInset=insets.getInsets(WindowInsets.Type.ime()).bottom;
		}else{
			int bottom=insets.getSystemWindowInsetBottom();
			if(bottom>V.dp(100)) albumImeInset=bottom;
			else{ albumImeInset=0; albumSystemBottomInset=bottom; }
		}
		bottomBar.setPadding(V.dp(8), V.dp(8), V.dp(8), albumImeInset>0 ? V.dp(8) : albumSystemBottomInset+V.dp(8));
		FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)bottomBar.getLayoutParams();
		params.bottomMargin=Build.VERSION.SDK_INT>=30 ? albumImeInset : 0; // Older windows resize themselves for IME.
		bottomBar.setLayoutParams(params);
		refreshAlbumControls();
		WindowInsets overlayInsets=insets.replaceSystemWindowInsets(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), 0);
		uiOverlay.dispatchApplyWindowInsets(overlayInsets);
		windowView.post(this::updateAlbumViewport);
		return insets.consumeSystemWindowInsets();
	}

	private void updateAlbumViewport(){
		if(albumDelegate==null || dismissed || windowView.getHeight()==0) return;
		if(albumCommentsPanel!=null && albumCommentsVisible){
			int available=Math.max(1, windowView.getHeight()-(Build.VERSION.SDK_INT>=30 ? albumImeInset : 0)-toolbarWrap.getHeight()-uiOverlay.getPaddingTop());
			int height=Math.min(V.dp(320), Math.max(V.dp(112), available/2));
			if(albumCommentsPanel.getLayoutParams().height!=height){
				ViewGroup.LayoutParams panelParams=albumCommentsPanel.getLayoutParams();
				panelParams.height=height;
				albumCommentsPanel.setLayoutParams(panelParams);
			}
		}
		// These controls share the pager's root: map layout bounds locally, independent of WindowManager location state.
		Rect toolbarBounds=new Rect(0, 0, toolbarWrap.getWidth(), toolbarWrap.getHeight());
		Rect bottomBounds=new Rect(0, 0, bottomBar.getWidth(), bottomBar.getHeight());
		windowView.offsetDescendantRectToMyCoords(toolbarWrap, toolbarBounds);
		windowView.offsetDescendantRectToMyCoords(bottomBar, bottomBounds);
		int toolbarBottom=uiVisible ? toolbarBounds.bottom : 0;
		int panelTop=uiVisible ? bottomBounds.top : windowView.getHeight()-albumSystemBottomInset;
		AlbumViewport viewport=albumViewport(windowView.getWidth(), windowView.getHeight(), toolbarBottom, panelTop, Build.VERSION.SDK_INT>=30 ? albumImeInset : 0);
		FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)pager.getLayoutParams();
		if(params.topMargin!=viewport.top || params.bottomMargin!=viewport.bottomInset){
			params.topMargin=viewport.top;
			params.bottomMargin=viewport.bottomInset;
			pager.setLayoutParams(params);
			// ZoomPanView's min scale is FIT_CENTER; changed viewport resets panning as well.
			for(PhotoViewHolder holder:albumPhotoHolders){
				if(holder.zoomPanView.isLaidOut() && holder.imageView.getWidth()>0) holder.zoomPanView.endAllAnimations();
				holder.zoomPanView.setFill(false); holder.zoomPanView.updateLayout();
			}
		}
		for(PhotoViewHolder holder:albumPhotoHolders) holder.zoomPanView.setSwipeToDismissEnabled(!albumCommentsVisible);
	}

	/** Geometry shared with tests: the viewport never extends beneath comments or IME. */
	public static AlbumViewport albumViewport(int width, int height, int toolbarBottom, int commentsTop, int imeBottom){
		int bottom=Math.max(0, Math.min(height, Math.min(commentsTop, height-Math.max(0, imeBottom))));
		int top=Math.max(0, Math.min(toolbarBottom, bottom));
		return new AlbumViewport(Math.max(0, width), top, bottom-top, height-bottom);
	}

	public static final class AlbumViewport{
		public final int width, top, height, bottomInset;
		private AlbumViewport(int width, int top, int height, int bottomInset){ this.width=width; this.top=top; this.height=height; this.bottomInset=bottomInset; }
		public float fitCenterScale(int imageWidth, int imageHeight){
			return imageWidth<=0 || imageHeight<=0 ? 0 : Math.min(width/(float)imageWidth, height/(float)imageHeight);
		}
	}

	/** Signed image bytes and drawables are transient; album media NEVER uses ImageCache. */
	public void updateAlbumSource(int position, String url, AlbumSourceCallback callback){
		if(!isAlbumHostValid() || position!=currentIndex || !isAcceptableImageUrl(url) || !url.startsWith("https://")){
			callback.onFailed(); return;
		}
		if(albumMediaCall!=null) albumMediaCall.cancel();
		long generation=mediaGeneration;
		Call call=albumHttpClient.newCall(new Request.Builder().url(url).cacheControl(new CacheControl.Builder().noStore().build()).build());
		albumMediaCall=call;
		call.enqueue(new okhttp3.Callback(){
			@Override public void onFailure(@NonNull Call c, @NonNull IOException error){
				activity.runOnUiThread(()->{ if(albumMediaLive(call, generation, position)){ albumMediaCall=null; callback.onFailed(); } });
			}
			@Override public void onResponse(@NonNull Call c, @NonNull Response response){
				Drawable drawable=null;
				try(response){
					if(!response.isSuccessful() || response.body()==null) throw new IOException("Image request failed");
					drawable=readAlbumDrawable(response.body());
				}catch(IOException | RuntimeException | OutOfMemoryError ignored){}
				Drawable result=drawable;
				activity.runOnUiThread(()->{
					if(!albumMediaLive(call, generation, position)) return;
					albumMediaCall=null;
					if(result==null){ callback.onFailed(); return; }
					albumSourcePosition=position;
					albumSourceDrawable=result;
					for(PhotoViewHolder holder:albumPhotoHolders){
						if(holder.getAbsoluteAdapterPosition()==position){
							Call preview=albumPreviewCalls.remove(holder); if(preview!=null) preview.cancel();
							holder.setImageDrawable(result); holder.zoomPanView.updateLayout();
						}
					}
					callback.onLoaded();
				});
			}
		});
	}

	private void renewAlbumPreview(int position){
		if(!isAlbumHostValid() || position!=currentIndex || albumPreviewRefreshing) return;
		albumPreviewRefreshing=true;
		albumPreviewGeneration=-1;
		albumPreviewPosition=-1;
		long generation=mediaGeneration, revision=++albumPreviewRevision;
		for(Call call:albumPreviewCalls.values()) call.cancel();
		albumPreviewCalls.clear();
		albumPreviewRetryButton.setVisibility(View.GONE);
		albumDelegate.refreshPreview(position, new AlbumPreviewCallback(){
			private boolean live(){ return generation==mediaGeneration && revision==albumPreviewRevision && position==currentIndex && isAlbumHostValid(); }
			@Override public void onRefreshed(String previewUrl, String description, int width, int height){
				if(!live()) return;
				if(TextUtils.isEmpty(previewUrl) || !isAcceptableImageUrl(previewUrl) || !previewUrl.startsWith("https://")){ onFailed(); return; }
				Attachment attachment=attachments.get(position);
				// Only renewed preview enters Attachment. HD/lossless remain transient separate sources.
				setAlbumPreviewMetadata(attachment, previewUrl, description, width, height);
				albumPreviewRefreshing=false;
				albumPreviewGeneration=generation;
				albumPreviewPosition=position;
				refreshAlbumControls();
				for(PhotoViewHolder holder:albumPhotoHolders){
					if(holder.getAbsoluteAdapterPosition()==position){
						FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)holder.imageView.getLayoutParams();
						params.width=attachment.getWidth(); params.height=attachment.getHeight(); holder.imageView.setLayoutParams(params);
						loadAlbumPreview(holder, attachment);
					}
				}
			}
			@Override public void onFailed(){
				if(!live()) return;
				albumPreviewRefreshing=false;
				albumPreviewRetryButton.setVisibility(View.VISIBLE);
				refreshAlbumControls();
			}
			@Override public void onAccessDenied(){
				if(!live()) return;
				Toast.makeText(activity, R.string.album_viewer_access_denied, Toast.LENGTH_LONG).show();
				onDismissed(); // Clears every drawable/request before returning to the album screen.
			}
		});
	}

	static void setAlbumPreviewMetadata(Attachment attachment, String previewUrl, String description, int width, int height){
		attachment.url=previewUrl;
		attachment.previewUrl=previewUrl;
		attachment.description=description;
		if(attachment.meta==null) attachment.meta=new Attachment.Metadata();
		attachment.meta.width=width; attachment.meta.height=height;
	}

	private void loadAlbumPreview(PhotoViewHolder holder, Attachment attachment){
		int position=holder.getAbsoluteAdapterPosition();
		if(position<0 || position!=currentIndex || !isAlbumHostValid()) return;
		if(albumPreviewRefreshing) return;
		if(albumPreviewGeneration!=mediaGeneration || albumPreviewPosition!=position){ renewAlbumPreview(position); return; }
		if(position==albumSourcePosition && albumSourceDrawable!=null){ holder.setImageDrawable(albumSourceDrawable); return; }
		Call previous=albumPreviewCalls.remove(holder); if(previous!=null) previous.cancel();
		if(TextUtils.isEmpty(attachment.previewUrl) || !isAcceptableImageUrl(attachment.previewUrl) || !attachment.previewUrl.startsWith("https://")){
			albumPreviewRetryButton.setVisibility(View.VISIBLE);
			Toast.makeText(activity, R.string.album_viewer_image_error, Toast.LENGTH_LONG).show();
			return;
		}
		long generation=mediaGeneration, revision=albumPreviewRevision;
		Call call=albumHttpClient.newCall(new Request.Builder().url(attachment.previewUrl).cacheControl(new CacheControl.Builder().noStore().build()).build());
		albumPreviewCalls.put(holder, call);
		call.enqueue(new okhttp3.Callback(){
			@Override public void onFailure(@NonNull Call c, @NonNull IOException error){ complete(null, false); }
			@Override public void onResponse(@NonNull Call c, @NonNull Response response){
				Drawable result=null;
				boolean expired=response.code()==403;
				try(response){ if(response.isSuccessful() && response.body()!=null) result=readAlbumDrawable(response.body()); }
				catch(IOException | RuntimeException | OutOfMemoryError ignored){}
				complete(result, expired);
			}
			private void complete(Drawable result, boolean expired){
				activity.runOnUiThread(()->{
					if(albumPreviewCalls.get(holder)!=call || generation!=mediaGeneration || revision!=albumPreviewRevision
							|| position!=currentIndex || !isAlbumHostValid() || holder.getAbsoluteAdapterPosition()!=position) return;
					albumPreviewCalls.remove(holder);
					if(result!=null){
						holder.setImageDrawable(result); holder.zoomPanView.updateLayout(); albumPreviewRetryButton.setVisibility(View.GONE);
					}else if(expired && albumPreviewRenewals++<1){
						renewAlbumPreview(position); // COS signature expiry rechecks ACL via GET, never quota authorization.
					}else{
						albumPreviewRetryButton.setVisibility(View.VISIBLE);
						Toast.makeText(activity, R.string.album_viewer_image_error, Toast.LENGTH_LONG).show();
					}
				});
			}
		});
	}

	private boolean albumMediaLive(Call call, long generation, int position){
		return albumMediaCall==call && generation==mediaGeneration && position==currentIndex && isAlbumHostValid();
	}

	private Drawable readAlbumDrawable(ResponseBody body) throws IOException{
		final int limit=20*1024*1024;
		if(body.contentLength()>limit) throw new IOException("Image too large");
		ByteArrayOutputStream bytes=new ByteArrayOutputStream();
		try(InputStream input=body.byteStream()){
			byte[] buffer=new byte[8192];
			int read;
			while((read=input.read(buffer))!=-1){
				if(bytes.size()+read>limit) throw new IOException("Image too large");
				bytes.write(buffer, 0, read);
			}
		}
		byte[] data=bytes.toByteArray();
		BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
		BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
		if(bounds.outWidth<=0 || bounds.outHeight<=0) throw new IOException("Invalid image");
		BitmapFactory.Options options=new BitmapFactory.Options(); options.inSampleSize=1;
		while(bounds.outWidth/options.inSampleSize>maxImageDimensions || bounds.outHeight/options.inSampleSize>maxImageDimensions
				|| (long)(bounds.outWidth/options.inSampleSize)*(bounds.outHeight/options.inSampleSize)>16_000_000) options.inSampleSize*=2;
		Bitmap bitmap=BitmapFactory.decodeByteArray(data, 0, data.length, options);
		if(bitmap==null) throw new IOException("Invalid image");
		return new BitmapDrawable(activity.getResources(), bitmap);
	}

	/** Saves fresh authorized bytes directly; never reuses the displayed image or a disk cache. */
	public void saveAlbumSource(int position, String url, AlbumSourceCallback callback){
		if(!isAlbumHostValid() || position!=currentIndex || !isAcceptableImageUrl(url) || !url.startsWith("https://")){
			callback.onFailed(); return;
		}
		if(albumMediaCall!=null) albumMediaCall.cancel();
		long generation=mediaGeneration;
		Call call=albumHttpClient.newCall(new Request.Builder().url(url).cacheControl(new CacheControl.Builder().noStore().build()).build());
		albumMediaCall=call;
		call.enqueue(new okhttp3.Callback(){
			@Override public void onFailure(@NonNull Call c, @NonNull IOException error){
				activity.runOnUiThread(()->{ if(albumMediaLive(call, generation, position)){ albumMediaCall=null; callback.onFailed(); } });
			}
			@Override public void onResponse(@NonNull Call c, @NonNull Response response){
				AlbumDestination destination=null;
				boolean copied=false;
				try(response){
					if(!response.isSuccessful() || response.body()==null) throw new IOException("Download failed");
					ResponseBody body=response.body();
					if(body.contentLength()>20*1024*1024) throw new IOException("Download too large");
					destination=new AlbumDestination(body.contentType()==null ? "image/jpeg" : body.contentType().toString());
					try(InputStream input=body.byteStream(); OutputStream output=destination.open()){
						byte[] buffer=new byte[8192]; long total=0; int count;
						while((count=input.read(buffer))!=-1){
							if(call.isCanceled() || (total+=count)>20*1024*1024) throw new IOException("Download canceled");
							output.write(buffer, 0, count);
						}
						if(total==0) throw new IOException("Empty download");
					}
					copied=true;
				}catch(IOException | RuntimeException ignored){}
				AlbumDestination target=destination;
				boolean success=copied;
				activity.runOnUiThread(()->{
					if(!albumMediaLive(call, generation, position)){
						if(target!=null) target.discard(); return;
					}
					albumMediaCall=null;
					if(success && target!=null){
						try{ target.publish(); callback.onLoaded(); Toast.makeText(activity, R.string.image_saved, Toast.LENGTH_LONG).show(); }
						catch(RuntimeException error){ target.discard(); callback.onFailed(); }
					}else{ if(target!=null) target.discard(); callback.onFailed(); }
				});
			}
		});
	}

	private final class AlbumDestination{
		private final Uri uri;
		private final File file;
		private final String mime;
		AlbumDestination(String contentType) throws IOException{
			mime=contentType.split(";")[0].trim();
			String ext=switch(mime){ case "image/png" -> ".png"; case "image/webp" -> ".webp"; case "image/gif" -> ".gif"; case "image/heif", "image/heic" -> ".heic"; case "image/avif" -> ".avif"; case "image/jpeg" -> ".jpg"; default -> throw new IOException("Unsupported image"); };
			String name="Album_"+java.util.UUID.randomUUID()+ext;
			if(Build.VERSION.SDK_INT>=29){
				ContentValues values=new ContentValues();
				values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
				values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
				values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
				values.put(MediaStore.MediaColumns.IS_PENDING, 1);
				uri=activity.getContentResolver().insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
				file=null;
				if(uri==null) throw new IOException("No download destination");
			}else{
				uri=null;
				File directory=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
				if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No download directory");
				file=new File(directory, name);
			}
		}
		OutputStream open() throws IOException{
			OutputStream result=uri==null ? new FileOutputStream(file) : activity.getContentResolver().openOutputStream(uri);
			if(result==null) throw new IOException("No download stream");
			return result;
		}
		void publish(){
			if(uri!=null){ ContentValues values=new ContentValues(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); activity.getContentResolver().update(uri, values, null, null); }
			else MediaScannerConnection.scanFile(activity, new String[]{file.getAbsolutePath()}, new String[]{mime}, null);
		}
		void discard(){
			try{ if(uri!=null) activity.getContentResolver().delete(uri, null, null); else if(file!=null) file.delete(); }
			catch(RuntimeException ignored){}
		}
	}

	public void reloadAlbumPreview(){
		if(!isAlbumHostValid()) return;
		albumPreviewRenewals=0;
		renewAlbumPreview(currentIndex);
	}

	public void resumeAlbum(){
		if(!isAlbumHostValid()) return;
		reloadAlbumPreview();
		albumDelegate.onPhotoChanged(currentIndex);
		refreshAlbumControls();
	}

	public void cancelAlbumMedia(){
		if(albumMediaCall!=null){ albumMediaCall.cancel(); albumMediaCall=null; }
		for(Call call:albumPreviewCalls.values()) call.cancel();
		albumPreviewCalls.clear();
	}

	public void removeMenu(){
		downloadButton.setVisibility(View.GONE);
		viewOriginalBtn.setVisibility(View.GONE);
	}

	@Override
	public void onTransitionAnimationUpdate(float translateX, float translateY, float scale){
		listener.setTransitioningViewTransform(translateX, translateY, scale);
	}

	@Override
	public void onTransitionAnimationFinished(){
		listener.endPhotoViewTransition();
	}

	@Override
	public void onSetBackgroundAlpha(float alpha){
		background.setAlpha(Math.round(alpha*255f));
		uiOverlay.setAlpha(Math.max(0f, alpha*2f-1f));
	}

	@Override
	public void onStartSwipeToDismiss(){
		listener.setPhotoViewVisibility(pager.getCurrentItem(), false);
		if(!uiVisible){
			windowView.setSystemUiVisibility(windowView.getSystemUiVisibility() & ~(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN));
		}else{
			windowView.removeCallbacks(uiAutoHider);
		}
	}

	@Override
	public void onStartSwipeToDismissTransition(float velocityY){
		if(closing || dismissed) return;
		closing=true;
		invalidateOriginalWork();
		pauseVideo();
		// stop receiving input events to allow the user to interact with the underlying UI while the animation is still running
		WindowManager.LayoutParams wlp=(WindowManager.LayoutParams) windowView.getLayoutParams();
		wlp.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
		windowView.setSystemUiVisibility(windowView.getSystemUiVisibility() | (activity.getWindow().getDecorView().getSystemUiVisibility() & (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)));
		wm.updateViewLayout(windowView, wlp);

		int index=pager.getCurrentItem();
		listener.setPhotoViewVisibility(index, true);
		Rect rect=new Rect();
		int[] radius=new int[4];
		if(listener.startPhotoViewTransition(index, rect, radius)){
			RecyclerView rv=(RecyclerView) pager.getChildAt(0);
			BaseHolder holder=(BaseHolder) rv.findViewHolderForAdapterPosition(index);
			holder.zoomPanView.animateOut(rect, radius, velocityY);
		}else{
			windowView.animate()
					.alpha(0)
					.setDuration(300)
					.setInterpolator(CubicBezierInterpolator.DEFAULT)
					.withEndAction(this::onDismissed)
					.start();
		}
	}

	@Override
	public void onSwipeToDismissCanceled(){
		listener.setPhotoViewVisibility(pager.getCurrentItem(), true);
		if(!uiVisible){
			windowView.setSystemUiVisibility(windowView.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN);
		}else if(attachments.get(currentIndex).type==Attachment.Type.VIDEO){
			hideUiDelayed();
		}
	}

	@Override
	public void onDismissed(){
		if(dismissed) return;
		dismissed=true;
		closing=true;
		invalidateOriginalWork();
		if(!players.isEmpty()){
			// MediaPlayer::release can block and cause an ANR sometimes, e.g. if called during DNS resolution, at least on some system versions.
			// This allows it to take its time to time out.
			new Thread(()->{
				for(MediaPlayer player:players){
					player.release();
				}
			}).start();
			activity.getSystemService(AudioManager.class).abandonAudioFocus(audioFocusListener);
		}
		listener.setPhotoViewVisibility(pager.getCurrentItem(), true);
		wm.removeView(windowView);
		listener.photoViewerDismissed();
		if(receiverRegistered){
			activity.unregisterReceiver(downloadCompletedReceiver);
		}
		activity.getSharedPreferences("account_manager", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(originalAccountListener);
		if(albumDelegate!=null){
			cancelAlbumMedia();
			albumSourceDrawable=null;
			for(PhotoViewHolder holder:albumPhotoHolders) holder.imageView.setImageDrawable(null);
			albumPhotoHolders.clear();
			pager.setAdapter(null);
			windowView.removeCallbacks(uiAutoHider);
			if(currentUiVisibilityAnimation!=null){ currentUiVisibilityAnimation.cancel(); currentUiVisibilityAnimation=null; }
			activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(windowView.getWindowToken(), 0);
			if(Build.VERSION.SDK_INT>=33 && albumBackCallback!=null) activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(albumBackCallback);
			albumDelegate.onDismissed();
		}
		E.unregister(this);
	}

	@Override
	public void onSingleTap(){
		toggleUI();
	}

	private void toggleUI(){
		if(albumDelegate!=null && albumCommentsVisible) return;
		if(currentUiVisibilityAnimation!=null)
			currentUiVisibilityAnimation.cancel();
		if(uiVisible){
			AnimatorSet set=new AnimatorSet();
			set.playTogether(
					ObjectAnimator.ofFloat(uiOverlay, View.ALPHA, 0f),
					ObjectAnimator.ofFloat(toolbarWrap, View.TRANSLATION_Y, V.dp(-32)),
					ObjectAnimator.ofFloat(bottomBar, View.TRANSLATION_Y, V.dp(32))
			);
			set.setInterpolator(CubicBezierInterpolator.DEFAULT);
			set.setDuration(250);
			set.addListener(new AnimatorListenerAdapter(){
				@Override
				public void onAnimationEnd(Animator animation){
					uiOverlay.setVisibility(View.GONE);
					currentUiVisibilityAnimation=null;
				}
			});
			currentUiVisibilityAnimation=set;
			set.start();
			windowView.setSystemUiVisibility(windowView.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN);
		}else{
			uiOverlay.setVisibility(View.VISIBLE);
			AnimatorSet set=new AnimatorSet();
			set.playTogether(
					ObjectAnimator.ofFloat(uiOverlay, View.ALPHA, 1f),
					ObjectAnimator.ofFloat(toolbarWrap, View.TRANSLATION_Y, 0),
					ObjectAnimator.ofFloat(bottomBar, View.TRANSLATION_Y, 0)
			);
			set.setInterpolator(CubicBezierInterpolator.DEFAULT);
			set.setDuration(300);
			set.addListener(new AnimatorListenerAdapter(){
				@Override
				public void onAnimationEnd(Animator animation){
					currentUiVisibilityAnimation=null;
				}
			});
			currentUiVisibilityAnimation=set;
			set.start();
			windowView.setSystemUiVisibility(windowView.getSystemUiVisibility() & ~(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN));
			if(attachments.get(currentIndex).type==Attachment.Type.VIDEO)
				hideUiDelayed(5000);
		}
		uiVisible=!uiVisible;
		if(albumDelegate!=null) windowView.postDelayed(this::updateAlbumViewport, 310);
	}

	private void hideUiDelayed(){
		hideUiDelayed(2000);
	}

	private void hideUiDelayed(long delay){
		windowView.removeCallbacks(uiAutoHider);
		windowView.postDelayed(uiAutoHider, delay);
	}

	private void onPageChanged(int index){
		if(currentIndex!=index) invalidateOriginalWork();
		currentIndex=index;
		Attachment att=attachments.get(index);
		V.setVisibilityAnimated(videoControls, att.type==Attachment.Type.VIDEO ? View.VISIBLE : View.GONE);
		if(att.type==Attachment.Type.VIDEO){
			videoSeekBar.setSecondaryProgress(0);
			videoDuration=(int)Math.round(att.getDuration()*1000);
			videoLastTimeUpdatePosition=-1;
			updateVideoTimeText(0);
		}
		updateAltText();
		updateViewOriginalButton();
		if(albumDelegate!=null){
			albumDelegate.onPhotoChanged(index);
			refreshAlbumControls();
			windowView.post(()->{
				for(PhotoViewHolder holder:albumPhotoHolders){ int position=holder.getAbsoluteAdapterPosition(); if(position>=0) loadAlbumPreview(holder, attachments.get(position)); }
			});
		}
	}

	private void updateAltText(){
		Attachment att=attachments.get(currentIndex);
		if(TextUtils.isEmpty(att.description)){
			altText.setVisibility(View.GONE);
		}else{
			altText.setVisibility(View.VISIBLE);
			altText.setText(att.description);
			altText.setMaxLines(att.type==Attachment.Type.VIDEO ? 3 : 4);
		}
	}

	/**
	 * “查看原图”药丸按钮的可见性与文案：仅图片页且原图尚未缓存/未加载时显示。
	 */
	private void updateViewOriginalButton(){
		if(albumDelegate!=null){ refreshAlbumControls(); return; }
		Attachment att=attachments.get(currentIndex);
		boolean show=att.type==Attachment.Type.IMAGE
				&& !originalLoadedPositions.contains(currentIndex)
				&& !isOriginalCached(att);
		V.setVisibilityAnimated(viewOriginalBtn, show ? View.VISIBLE : View.GONE);
		if(show){
			boolean downloading=originalDownloadPosition==currentIndex;
			// 授权校验（身份/额度网络请求）期间同样占用按钮：改文案提示正在通信，避免误以为卡住
			boolean verifying=originalGate!=null && originalGate.isBusy() && !downloading;
			viewOriginalBtn.setEnabled(!downloading && !verifying);
			viewOriginalBtn.setText(verifying ? R.string.verifying_original
					: downloading ? R.string.downloading_original
					: R.string.view_original);
		}
	}

	private boolean isOriginalCached(Attachment att){
		UrlImageLoaderRequest req=new UrlImageLoaderRequest(att.url);
		ImageCache cache=ImageCache.getInstance(activity);
		if(cache.isInTopCache(req))
			return true;
		try{
			return cache.isInCache(req);
		}catch(Exception x){
			return false;
		}
	}

	private void invalidateOriginalWork(){
		mediaGeneration++;
		pendingPermissionAttachment=null;
		if(originalDownloadRequest!=null){ originalDownloadRequest.cancel(); originalDownloadRequest=null; }
		originalDownloadPosition=-1;
		if(originalGate!=null) originalGate.cancel();
		if(albumDelegate!=null){
			cancelAlbumMedia();
			albumPreviewRevision++;
			albumPreviewGeneration=-1; albumPreviewPosition=-1; albumPreviewRefreshing=false; albumPreviewRenewals=0;
			if(albumPreviewRetryButton!=null) albumPreviewRetryButton.setVisibility(View.GONE);
			albumSourceDrawable=null;
			albumSourcePosition=-1;
			for(PhotoViewHolder holder:albumPhotoHolders) holder.imageView.setImageDrawable(null);
			albumDelegate.cancelPending();
		}
	}

	private void updateOriginalControls(){
		if(closing || dismissed) return;
		updateViewOriginalButton();
		boolean busy=originalDownloadPosition!=-1 || originalGate!=null && originalGate.isBusy();
		downloadButton.setEnabled(!busy);
		if(busy) viewOriginalBtn.setEnabled(false);
	}

	private boolean sameMedia(long generation, int position, Attachment att){
		return !closing && !dismissed && !activity.isFinishing() && !activity.isDestroyed() && generation==mediaGeneration
				&& pager.getCurrentItem()==position && attachments.get(position)==att
				&& accountID.equals(AccountSessionManager.getInstance().getLastActiveAccountID())
				&& AccountSessionManager.getInstance().tryGetAccount(accountID)!=null
				&& (parentFragment==null || accountID.equals(parentFragment.getAccountID()));
	}

	private void startOriginalDownload(){
		if(albumDelegate!=null){ albumDelegate.onView(currentIndex, "hd"); return; }
		int position=pager.getCurrentItem();
		Attachment att=attachments.get(position);
		if(att.type!=Attachment.Type.IMAGE || originalDownloadPosition!=-1 || originalGate==null || originalGate.isBusy()) return;
		if(!isAcceptableImageUrl(att.url)) return;
		long generation=mediaGeneration;
		Runnable download=()->{
			if(sameMedia(generation, position, att)) downloadOriginal(att, position, generation);
		};
		if(isOriginalCached(att)) download.run();
		else originalGate.authorize(att.id, att.url, download);
	}

	private void downloadOriginal(Attachment att, int position, long generation){
		originalDownloadPosition=position;
		updateOriginalControls();
		viewOriginalBtn.setText(R.string.downloading_original);
		UrlImageLoaderRequest req=new UrlImageLoaderRequest(att.url);
		// 与保存原文件的磁盘缓存为同一 ImageCache 键：下载原图后“保存”可直接复用
		originalDownloadRequest=ImageCache.getInstance(activity).get(req, (loaded, total)->windowView.post(()->{
			if(!sameMedia(generation, position, att) || originalDownloadPosition!=position) return;
			if(total>0) viewOriginalBtn.setText(activity.getString(R.string.downloading_original_percent, Math.round(loaded*100f/total)));
		}), new ImageLoaderCallback(){
			@Override
			public void onImageLoaded(ImageLoaderRequest r, Drawable d){
				windowView.post(()->{
					if(sameMedia(generation, position, att)) onOriginalLoaded(position, d);
				});
			}

			@Override
			public void onImageLoadingFailed(ImageLoaderRequest r, Throwable x){
				Log.w(TAG, "viewOriginal: download failed", x);
				windowView.post(()->{
					if(!sameMedia(generation, position, att)) return;
					originalDownloadPosition=-1;
					originalDownloadRequest=null;
					updateOriginalControls();
					Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show();
				});
			}
		}, true);
	}

	private void onOriginalLoaded(int position, Drawable d){
		originalDownloadPosition=-1;
		originalDownloadRequest=null;
		originalLoadedPositions.add(position);
		updateOriginalControls();
		if(position==currentIndex)
			V.setVisibilityAnimated(viewOriginalBtn, View.GONE);
		else
			updateViewOriginalButton();
		if(d==null)
			return;
		RecyclerView rv=(RecyclerView) pager.getChildAt(0);
		if(rv.findViewHolderForAdapterPosition(position) instanceof PhotoViewHolder holder){
			holder.setImageDrawable(d);
			holder.zoomPanView.updateLayout();
		}
	}

	/**
	 * 仅允许 http/https，且拒绝 localhost、环回、私有与保留地址（下载前的安全校验）。
	 */
	private static boolean isAcceptableImageUrl(String url){
		Uri uri=Uri.parse(url);
		String scheme=uri.getScheme();
		if(scheme==null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")))
			return false;
		String host=uri.getHost();
		if(TextUtils.isEmpty(host))
			return false;
		String h=host.toLowerCase(Locale.ROOT);
		if(h.equals("localhost") || h.endsWith(".localhost") || h.equals("0.0.0.0"))
			return false;
		if(h.contains(":")) // IPv6 字面量：仅拒绝环回与链路本地
			return !(h.equals("[::1]") || h.equals("::1") || h.startsWith("[fe80") || h.startsWith("fe80"));
		String[] parts=h.split("\\.");
		if(parts.length==4){
			try{
				int a=Integer.parseInt(parts[0]), b=Integer.parseInt(parts[1]);
				int c=Integer.parseInt(parts[2]), d=Integer.parseInt(parts[3]);
				if(a==0 || a==127 || a==10) return false;
				if(a==192 && b==168) return false;
				if(a==172 && b>=16 && b<=31) return false;
				if(a==169 && b==254) return false;
				if(a==100 && b>=64 && b<=127) return false;
				if(a>=224) return false;
				if(b<0 || b>255 || c<0 || c>255 || d<0 || d>255) return false;
			}catch(NumberFormatException x){
				return false;
			}
		}
		return true;
	}

	private void updateBackgroundColor(int position, float positionOffset){
		int color;
		if(positionOffset==0){
			color=backgroundColors[position];
		}else{
			color=UiUtils.alphaBlendColors(backgroundColors[position], backgroundColors[position+1], positionOffset);
		}
		int alpha=background.getAlpha();
		background.setColor(color);
		background.setAlpha(alpha);
		uiOverlay.setStatusBarColor(color & 0xe6ffffff);
		uiOverlay.setNavigationBarColor(color & 0xe6ffffff);
		bottomBar.setBackgroundTintList(ColorStateList.valueOf(color));
	}
	
	private void updatePostActions(){
		bindActionButton(replyText, status.repliesCount);
		bindActionButton(boostText, status.reblogsCount);
		bindActionButton(favoriteText, status.favouritesCount);
		boostBtn.setSelected(status.reblogged);
		favoriteBtn.setSelected(status.favourited);
		bookmarkBtn.setSelected(status.bookmarked);
		bookmarkBtn.setContentDescription(activity.getString(status.bookmarked ? R.string.remove_bookmark : R.string.add_bookmark));
		boolean isOwn=status.account.id.equals(AccountSessionManager.getInstance().getAccount(accountID).self.id);
		boostBtn.setEnabled(status.visibility==StatusPrivacy.PUBLIC || status.visibility==StatusPrivacy.UNLISTED
				|| (status.visibility==StatusPrivacy.PRIVATE && isOwn));
		boostBtn.setAlpha(boostBtn.isEnabled() ? 1 : 0.5f);
		Drawable d=activity.getResources().getDrawable(switch(status.visibility){
			case PUBLIC, UNLISTED, LOCAL -> R.drawable.ic_boost;
			case PRIVATE -> isOwn ? R.drawable.ic_boost_private : R.drawable.ic_boost_disabled_24px;
			case DIRECT -> R.drawable.ic_boost_disabled_24px;
		}, activity.getTheme());
		d.setBounds(0, 0, V.dp(20), V.dp(20));
		boostText.setCompoundDrawablesRelative(d, null, null, null);
	}

	private void bindActionButton(TextView btn, long count){
		if(count>0){
			btn.setText(UiUtils.abbreviateNumber(count));
			btn.setCompoundDrawablePadding(V.dp(6));
		}else{
			btn.setText("");
			btn.setCompoundDrawablePadding(0);
		}
	}

	private void onPostActionClick(View view){
		int id=view.getId();
		if(id==R.id.boost_btn){
			if(status!=null){
				AccountSessionManager.get(accountID).getStatusInteractionController().setReblogged(status, !status.reblogged, StatusPrivacy.PUBLIC, r->{});
			}
		}else if(id==R.id.favorite_btn){
			if(status!=null){
				AccountSessionManager.get(accountID).getStatusInteractionController().setFavorited(status, !status.favourited);
			}
		}else if(id==R.id.share_btn){
			if(status!=null){
				UiUtils.openSystemShareSheet(activity, status);
			}
		}else if(id==R.id.bookmark_btn){
			if(status!=null){
				AccountSessionManager.get(accountID).getStatusInteractionController().setBookmarked(status, !status.bookmarked);
			}
		}else if(id==R.id.reply_btn){
			parentFragment.maybeShowPreReplySheet(status, ()->{
				onDismissed();
				Bundle args=new Bundle();
				args.putString("account", accountID);
				args.putParcelable("replyTo", Parcels.wrap(status));
				Nav.go(activity, ComposeFragment.class, args);
			});
		}
	}

	@Subscribe
	public void onStatusCountersUpdated(StatusCountersUpdatedEvent ev){
		if(status!=null && ev.id.equals(status.id)){
			status.update(ev);
			updatePostActions();
		}
	}

	/**
	 * To be called when the list containing photo views is scrolled
	 * @param x
	 * @param y
	 */
	public void offsetView(float x, float y){
		pager.setTranslationX(pager.getTranslationX()+x);
		pager.setTranslationY(pager.getTranslationY()+y);
	}

	private void incKeepScreenOn(){
		if(screenOnRefCount==0){
			WindowManager.LayoutParams wlp=(WindowManager.LayoutParams) windowView.getLayoutParams();
			wlp.flags|=WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
			wm.updateViewLayout(windowView, wlp);
			activity.getSystemService(AudioManager.class).requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
		}
		screenOnRefCount++;
	}

	private void decKeepScreenOn(){
		screenOnRefCount--;
		if(screenOnRefCount<0)
			throw new IllegalStateException();
		if(screenOnRefCount==0){
			WindowManager.LayoutParams wlp=(WindowManager.LayoutParams) windowView.getLayoutParams();
			wlp.flags&=~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
			wm.updateViewLayout(windowView, wlp);
			activity.getSystemService(AudioManager.class).abandonAudioFocus(audioFocusListener);
		}
	}

	public void onPause(){
		if(albumDelegate!=null){ invalidateOriginalWork(); return; }
		// A system permission dialog may pause the host. Keep only its captured attachment;
		// page changes, account switches and close still invalidate it before the result is used.
		if(pendingPermissionAttachment==null) invalidateOriginalWork();
		else if(originalGate!=null) originalGate.cancel();
		pauseVideo();
	}

	private void saveCurrentFile(){
		if(albumDelegate!=null){ if(isAlbumHostValid()) albumDelegate.onDownload(currentIndex); return; }
		if(closing || dismissed || originalGate==null || originalGate.isBusy() || originalDownloadPosition!=-1) return;
		Attachment att=attachments.get(pager.getCurrentItem());
		if(Build.VERSION.SDK_INT<29 && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
			pendingPermissionAttachment=att;
			pendingPermissionGeneration=mediaGeneration;
			listener.onRequestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE});
		}else{
			doSaveCurrentFile(att);
		}
	}

	public void onRequestPermissionsResult(String[] permissions, int[] results){
		Attachment att=pendingPermissionAttachment;
		pendingPermissionAttachment=null;
		if(att==null || !sameMedia(pendingPermissionGeneration, pager.getCurrentItem(), att)) return;
		if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED){
			doSaveCurrentFile(att);
		}else if(!activity.shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)){
			new M3AlertDialogBuilder(activity)
					.setTitle(R.string.permission_required)
					.setMessage(R.string.storage_permission_to_download)
					.setPositiveButton(R.string.open_settings, (dialog, which)->activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.getPackageName(), null))))
					.setNegativeButton(R.string.cancel, null)
					.show();
		}
	}

	private String mimeTypeForFileName(String fileName){
		int extOffset=fileName.lastIndexOf('.');
		if(extOffset>0){
			return switch(fileName.substring(extOffset+1).toLowerCase()){
				case "jpg", "jpeg" -> "image/jpeg";
				case "png" -> "image/png";
				case "gif" -> "image/gif";
				case "webp" -> "image/webp";
				case "mp4" -> "video/mp4";
				case "webm" -> "video/webm";
				default -> null;
			};
		}
		return null;
	}

	private OutputStream destinationStreamForFile(Attachment att) throws IOException{
		String fileName=Uri.parse(att.url).getLastPathSegment();
		if(TextUtils.isEmpty(fileName))
			fileName="MastodonImage_"+System.currentTimeMillis()+".jpg";
		int dotIndex=fileName.lastIndexOf('.');
		if(dotIndex==-1 || dotIndex<fileName.length()-5){
			fileName+=".jpg";
		}
		if(Build.VERSION.SDK_INT>=29){
			Uri itemUri;
			try{
				itemUri=tryInsertImage(fileName);
			}catch(IllegalStateException x){
				// "Failed to build unique file"
				String ext=fileName.substring(fileName.lastIndexOf('.'));
				fileName="MastodonImage_"+System.currentTimeMillis()+ext;
				itemUri=tryInsertImage(fileName);
			}
			ContentResolver cr=activity.getContentResolver();
			return cr.openOutputStream(itemUri);
		}else{
			File file=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName);
			if(file.exists()){
				String ext=fileName.substring(fileName.lastIndexOf('.'));
				fileName="MastodonImage_"+System.currentTimeMillis()+ext;
				file=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName);
			}
			return new FileOutputStream(file);
		}
	}

	private Uri tryInsertImage(String fileName){
		ContentValues values=new ContentValues();
		values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
		values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
		String mime=mimeTypeForFileName(fileName);
		if(mime!=null)
			values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
		ContentResolver cr=activity.getContentResolver();
		return cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
	}

	private void doSaveCurrentFile(Attachment att){
		if(closing || dismissed || originalGate==null || originalGate.isBusy() || originalDownloadPosition!=-1) return;
		if(att.type==Attachment.Type.IMAGE){
			UrlImageLoaderRequest req=new UrlImageLoaderRequest(att.url);
			try{
				File file=ImageCache.getInstance(activity).getFile(req);
				if(file==null || !file.isFile() || file.length()==0){
					saveViaDownloadManager(att);
					return;
				}
				MastodonAPIController.runInBackground(()->{
					try(Source src=Okio.source(file); Sink sink=Okio.sink(destinationStreamForFile(att))){
						BufferedSink buf=Okio.buffer(sink);
						buf.writeAll(src);
						buf.flush();
						activity.runOnUiThread(()->{
							new Snackbar.Builder(activity)
									.setText(R.string.image_saved)
									.setAction(R.string.view_file, ()->activity.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)))
									.show();
						});
						if(Build.VERSION.SDK_INT<29){
							String fileName=Uri.parse(att.url).getLastPathSegment();
							File dstFile=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName);
							MediaScannerConnection.scanFile(activity, new String[]{dstFile.getAbsolutePath()}, new String[]{mimeTypeForFileName(fileName)}, null);
						}
					}catch(IOException x){
						Log.w(TAG, "doSaveCurrentFile: ", x);
						activity.runOnUiThread(()->{
							new Snackbar.Builder(activity)
									.setText(R.string.error_saving_file)
									.show();
						});
					}
				});
			}catch(IOException x){
				Log.w(TAG, "doSaveCurrentFile: ", x);
				new Snackbar.Builder(activity)
						.setText(R.string.error_saving_file)
						.show();
			}
		}else{
			saveViaDownloadManager(att);
		}
	}

	private void saveViaDownloadManager(Attachment att){
		if(closing || dismissed || !isAcceptableImageUrl(att.url)) return;
		if(att.type!=Attachment.Type.IMAGE){ enqueueDownload(att); return; }
		if(originalGate==null || originalGate.isBusy()) return;
		final int position=pager.getCurrentItem();
		final long generation=mediaGeneration;
		originalGate.authorize(att.id, att.url, ()->{
			if(sameMedia(generation, position, att)) enqueueDownload(att);
		});
	}

	private void enqueueDownload(Attachment att){
		if(closing || dismissed || !isAcceptableImageUrl(att.url)) return;
		Uri uri=Uri.parse(att.url);
		DownloadManager.Request req=new DownloadManager.Request(uri);
		req.allowScanningByMediaScanner();
		req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
		req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, uri.getLastPathSegment());
		if(!receiverRegistered){
			BroadcastCompat.registerSystemReceiver(activity, downloadCompletedReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
			receiverRegistered=true;
		}
		lastDownloadID=activity.getSystemService(DownloadManager.class).enqueue(req);
		new Snackbar.Builder(activity)
				.setText(R.string.downloading)
				.show();
	}

	private void onAudioFocusChanged(int change){
		if(change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){
			pauseVideo();
		}
	}

	private GifVViewHolder findCurrentVideoPlayerHolder(){
		RecyclerView rv=(RecyclerView) pager.getChildAt(0);
		if(rv.findViewHolderForAdapterPosition(pager.getCurrentItem()) instanceof GifVViewHolder vvh && vvh.playerReady){
			return vvh;
		}
		return null;
	}

	private MediaPlayer findCurrentVideoPlayer(){
		GifVViewHolder holder=findCurrentVideoPlayerHolder();
		return holder!=null ? holder.player : null;
	}

	private void pauseVideo(){
		GifVViewHolder holder=findCurrentVideoPlayerHolder();
		if(holder==null || !holder.player.isPlaying())
			return;
		holder.player.pause();
		videoPlayPauseButton.setImageResource(R.drawable.ic_play_24);
		videoPlayPauseButton.setContentDescription(activity.getString(R.string.play));
		stopUpdatingVideoPosition();
		windowView.removeCallbacks(uiAutoHider);
		// Some MediaPlayer implementations clear the texture when the app goes into background.
		// This makes sure the frame on which the video was paused is retained on the screen.
		holder.wrap.setBackground(new BitmapDrawable(holder.textureView.getBitmap()));
	}

	private void resumeVideo(){
		MediaPlayer player=findCurrentVideoPlayer();
		if(player==null || player.isPlaying())
			return;
		player.start();
		videoPlayPauseButton.setImageResource(R.drawable.ic_pause_24);
		videoPlayPauseButton.setContentDescription(activity.getString(R.string.pause));
		startUpdatingVideoPosition(player);
	}

	private void startUpdatingVideoPosition(MediaPlayer player){
		videoInitialPosition=player.getCurrentPosition();
		videoInitialPositionTime=SystemClock.uptimeMillis();
		videoDuration=player.getDuration();
		videoPositionNeedsUpdating=true;
		windowView.postOnAnimation(videoPositionUpdater);
	}

	private void stopUpdatingVideoPosition(){
		videoPositionNeedsUpdating=false;
		windowView.removeCallbacks(videoPositionUpdater);
	}

	private String formatTime(int timeSec, boolean includeHours){
		if(includeHours)
			return String.format(Locale.getDefault(), "%d:%02d:%02d", timeSec/3600, timeSec%3600/60, timeSec%60);
		else
			return String.format(Locale.getDefault(), "%d:%02d", timeSec/60, timeSec%60);
	}

	private void updateVideoPosition(){
		if(videoPositionNeedsUpdating){
			int currentPosition=videoInitialPosition+(int)(SystemClock.uptimeMillis()-videoInitialPositionTime);
			videoSeekBar.setProgress(Math.round((float)currentPosition/videoDuration*10000f));
			updateVideoTimeText(currentPosition);
			windowView.postOnAnimation(videoPositionUpdater);
		}
	}

	@SuppressLint("SetTextI18n")
	private void updateVideoTimeText(int currentPosition){
		int currentPositionSec=currentPosition/1000;
		if(currentPositionSec!=videoLastTimeUpdatePosition){
			videoLastTimeUpdatePosition=currentPositionSec;
			boolean includeHours=videoDuration>=3600_000;
			videoTimeView.setText(formatTime(currentPositionSec, includeHours)+" / "+formatTime(videoDuration/1000, includeHours));
		}
	}

	private void showAltTextSheet(){
		pauseVideo();
		BottomSheet sheet=new AltTextSheet(new ContextThemeWrapper(activity, UiUtils.getThemeForUserPreference(activity, GlobalUserPreferences.ThemePreference.DARK)),
				attachments.get(currentIndex));
		sheet.show();
		sheet.getWindow().getDecorView().setSystemUiVisibility(sheet.getWindow().getDecorView().getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
	}

	/** No album API/model dependency in the generic viewer. */
	public interface AlbumDelegate{
		AlbumInfo getInfo(int position);
		void onPhotoChanged(int position);
		void refreshPreview(int position, AlbumPreviewCallback callback);
		void onView(int position, String variant);
		void onDownload(int position);
		void onLike(int position);
		void onComments(int position);
		void onCommentsClosed();
		void cancelPending();
		void onDismissed();
	}

	public static final class AlbumInfo{
		public boolean isOwner, originalAvailable, liked, hdLoaded, originalLoaded, mediaBusy, likeBusy;
		public int likesCount, commentsCount;
	}

	public interface AlbumSourceCallback{
		void onLoaded();
		void onFailed();
	}

	public interface AlbumPreviewCallback{
		void onRefreshed(String previewUrl, String description, int width, int height);
		void onFailed();
		void onAccessDenied();
	}

	public interface Listener{
		void setPhotoViewVisibility(int index, boolean visible);

		/**
		 * Find a view for transition, save a reference to it until <code>{@link #endPhotoViewTransition()}</code> is called,
		 * and set up the view hierarchy for transition (the photo view may need to be drawn outside of the bounds of its parent).
		 * @param index the index of the photo/page
		 * @param outRect output: the rect of the photo view <b>in screen coordinates</b>
		 * @param outCornerRadius output: corner radiuses of the view [top-left, top-right, bottom-right, bottom-left]
		 * @return true if the view was found and outRect and outCornerRadius are valid
		 */
		boolean startPhotoViewTransition(int index, @NonNull Rect outRect, @NonNull int[] outCornerRadius);

		/**
		 * Update the transformation parameters of the transitioning photo view.
		 * Only called if a previous call to {@link #startPhotoViewTransition(int, Rect, int[])} returned true.
		 * @param translateX X translation
		 * @param translateY Y translation
		 * @param scale X and Y scale
		 */
		void setTransitioningViewTransform(float translateX, float translateY, float scale);

		/**
		 * End the transition, returning all transformations to their initial state.
		 */
		void endPhotoViewTransition();

		/**
		 * Get the current drawable that a photo view displays.
		 * @param index the index of the photo
		 * @return the drawable, or null if the view doesn't exist
		 */
		@Nullable
		Drawable getPhotoViewCurrentDrawable(int index);

		void photoViewerDismissed();
		void onRequestPermissions(String[] permissions);
	}

	private class PhotoViewAdapter extends RecyclerView.Adapter<BaseHolder>{

		@NonNull
		@Override
		public BaseHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType){
			return switch(viewType){
				case 0 -> new PhotoViewHolder();
				case 1 -> new GifVViewHolder();
				default -> throw new IllegalStateException("Unexpected value: "+viewType);
			};
		}

		@Override
		public void onBindViewHolder(@NonNull BaseHolder holder, int position){
			holder.bind(attachments.get(position));
		}

		@Override
		public int getItemCount(){
			return attachments.size();
		}

		@Override
		public int getItemViewType(int position){
			Attachment att=attachments.get(position);
			return switch(att.type){
				case IMAGE -> 0;
				case GIFV, VIDEO -> 1;
				default -> throw new IllegalStateException("Unexpected value: "+att.type);
			};
		}

		@Override
		public void onViewDetachedFromWindow(@NonNull BaseHolder holder){
			super.onViewDetachedFromWindow(holder);
			if(holder instanceof GifVViewHolder gifHolder){
				gifHolder.reset();
			}
		}

		@Override
		public void onViewRecycled(@NonNull BaseHolder holder){
			if(albumDelegate!=null && holder instanceof PhotoViewHolder photoHolder){
				Call call=albumPreviewCalls.remove(photoHolder); if(call!=null) call.cancel();
				albumPhotoHolders.remove(photoHolder);
				photoHolder.imageView.setImageDrawable(null);
			}
			super.onViewRecycled(holder);
		}

		@Override
		public void onViewAttachedToWindow(@NonNull BaseHolder holder){
			super.onViewAttachedToWindow(holder);
			if(albumDelegate!=null && holder instanceof PhotoViewHolder photoHolder){
				albumPhotoHolders.add(photoHolder);
				loadAlbumPreview(photoHolder, attachments.get(holder.getAbsoluteAdapterPosition()));
			}
			if(holder instanceof GifVViewHolder gifHolder){
				gifHolder.prepareAndStartPlayer();
			}
		}
	}

	private abstract class BaseHolder extends BindableViewHolder<Attachment>{
		public ZoomPanView zoomPanView;
		public BaseHolder(){
			super(new ZoomPanView(activity));
			zoomPanView=(ZoomPanView) itemView;
			zoomPanView.setListener(PhotoViewer.this);
			zoomPanView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		}

		@Override
		public void onBind(Attachment item){
			zoomPanView.setScrollDirections(getAbsoluteAdapterPosition()>0, getAbsoluteAdapterPosition()<attachments.size()-1);
		}
	}

	private class PhotoViewHolder extends BaseHolder implements ViewImageLoader.Target{
		public ImageView imageView;

		public PhotoViewHolder(){
			imageView=new ImageView(activity);
			if(albumDelegate!=null){ imageView.setScaleType(ImageView.ScaleType.FIT_CENTER); zoomPanView.setFill(false); }
			zoomPanView.addView(imageView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
		}

		@Override
		public void onBind(Attachment item){
			super.onBind(item);
			FrameLayout.LayoutParams params=(FrameLayout.LayoutParams) imageView.getLayoutParams();
			Drawable currentDrawable=listener.getPhotoViewCurrentDrawable(getAbsoluteAdapterPosition());
			if(item.hasKnownDimensions()){
				params.width=item.getWidth();
				params.height=item.getHeight();
			}else if(currentDrawable!=null){
				params.width=currentDrawable.getIntrinsicWidth();
				params.height=currentDrawable.getIntrinsicHeight();
			}else{
				params.width=1920;
				params.height=1080;
			}
			if(albumDelegate!=null){
				albumPhotoHolders.add(this);
				imageView.setContentDescription(activity.getString(R.string.album_viewer_photo_position, getAbsoluteAdapterPosition()+1, attachments.size()));
				loadAlbumPreview(this, item);
				return;
			}
			// 默认只加载预览图；用户点击“查看原图”完成后（或原图已在本会话加载过）才加载原图
			int position=getAbsoluteAdapterPosition();
			UrlImageLoaderRequest req;
				if(isOriginalCached(item)){
					req=new UrlImageLoaderRequest(item.url);
				}else{
					originalLoadedPositions.remove(position);
					// No preview must not silently fetch an uncached original without authorization.
					if(TextUtils.isEmpty(item.previewUrl)){
						setImageDrawable(currentDrawable);
						return;
					}
					req=new UrlImageLoaderRequest(item.previewUrl, maxImageDimensions, maxImageDimensions);
				}
				ViewImageLoader.load(this, currentDrawable, req, false);
		}

		@Override
		public void setImageDrawable(Drawable d){
			imageView.setImageDrawable(d);
		}

		@Override
		public View getView(){
			return imageView;
		}
	}

	private class GifVViewHolder extends BaseHolder implements MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener,
			MediaPlayer.OnVideoSizeChangedListener, MediaPlayer.OnBufferingUpdateListener, MediaPlayer.OnInfoListener, MediaPlayer.OnSeekCompleteListener, TextureView.SurfaceTextureListener{
		public TextureView textureView;
		public FrameLayout wrap;
		public MediaPlayer player;
		private Surface surface;
		private boolean playerReady;
		private boolean playerStarted;
		private boolean keepingScreenOn;
		private ProgressBar progressBar;

		public GifVViewHolder(){
			textureView=new TextureView(activity);
			wrap=new FrameLayout(activity);
			zoomPanView.addView(wrap, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
			wrap.addView(textureView);

			progressBar=new ProgressBar(activity);
			progressBar.setIndeterminateTintList(ColorStateList.valueOf(0xffffffff));
			zoomPanView.addView(progressBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

			textureView.setSurfaceTextureListener(this);
		}

		@Override
		public void onBind(Attachment item){
			super.onBind(item);
			playerReady=false;
			FrameLayout.LayoutParams params=(FrameLayout.LayoutParams) wrap.getLayoutParams();
			Drawable currentDrawable=listener.getPhotoViewCurrentDrawable(getAbsoluteAdapterPosition());
			if(item.hasKnownDimensions()){
				params.width=item.getWidth();
				params.height=item.getHeight();
			}else if(currentDrawable!=null){
				params.width=currentDrawable.getIntrinsicWidth();
				params.height=currentDrawable.getIntrinsicHeight();
			}else{
				params.width=1920;
				params.height=1080;
			}
			wrap.setBackground(currentDrawable);
			progressBar.setVisibility(item.type==Attachment.Type.VIDEO ? View.VISIBLE : View.GONE);
			if(itemView.isAttachedToWindow()){
				reset();
				prepareAndStartPlayer();
			}
		}

		@Override
		public void onPrepared(MediaPlayer mp){
			Log.d(TAG, "onPrepared() called with: mp = ["+mp+"]");
			playerReady=true;
			progressBar.setVisibility(View.GONE);
			if(surface!=null)
				startPlayer();
		}

		@Override
		public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height){
			this.surface=new Surface(surface);
			if(playerReady)
				startPlayer();
		}

		@Override
		public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height){

		}

		@Override
		public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface){
			this.surface=null;
			return true;
		}

		@Override
		public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface){
			// A new frame of video was rendered. Clear the thumbnail or paused frame, if any, to avoid overdraw and free up some memory.
			if(playerReady && playerStarted && wrap.getBackground()!=null){
				wrap.setBackground(null);
			}
		}

		private void startPlayer(){
			player.setSurface(surface);
			playerStarted=true;
			if(item.type==Attachment.Type.VIDEO){
				incKeepScreenOn();
				keepingScreenOn=true;
				if(getAbsoluteAdapterPosition()==currentIndex){
					player.start();
					startUpdatingVideoPosition(player);
					hideUiDelayed();
				}
			}else{
				keepingScreenOn=false;
				player.setLooping(true);
				player.start();
			}
		}

		@Override
		public boolean onError(MediaPlayer mp, int what, int extra){
			Log.e(TAG, "video player onError() called with: mp = ["+mp+"], what = ["+what+"], extra = ["+extra+"]");
			Toast.makeText(activity, R.string.error_playing_video, Toast.LENGTH_SHORT).show();
			onStartSwipeToDismissTransition(0f);
			return true;
		}

		public void prepareAndStartPlayer(){
			playerReady=false;
			playerStarted=false;
			player=new MediaPlayer();
			players.add(player);
			player.setOnPreparedListener(this);
			player.setOnErrorListener(this);
			player.setOnVideoSizeChangedListener(this);
			if(item.type==Attachment.Type.VIDEO){
				player.setOnBufferingUpdateListener(this);
				player.setOnInfoListener(this);
				player.setOnSeekCompleteListener(this);
				player.setOnCompletionListener(this);
			}
			try{
				player.setDataSource(activity, Uri.parse(item.url));
				player.prepareAsync();
			}catch(IOException x){
				Log.w(TAG, "Error initializing gif player", x);
				Toast.makeText(activity, R.string.error_playing_video, Toast.LENGTH_SHORT).show();
				onStartSwipeToDismissTransition(0f);
			}
		}

		public void reset(){
			playerReady=false;
			playerStarted=false;
			player.release();
			players.remove(player);
			player=null;
			if(keepingScreenOn){
				decKeepScreenOn();
				keepingScreenOn=false;
			}
		}

		@Override
		public void onVideoSizeChanged(MediaPlayer mp, int width, int height){
			if(width<=0 || height<=0)
				return;
			FrameLayout.LayoutParams params=(FrameLayout.LayoutParams) wrap.getLayoutParams();
			params.width=width;
			params.height=height;
			zoomPanView.updateLayout();
		}

		@Override
		public void onBufferingUpdate(MediaPlayer mp, int percent){
			if(getAbsoluteAdapterPosition()==currentIndex){
				videoSeekBar.setSecondaryProgress(percent*100);
			}
		}

		@Override
		public boolean onInfo(MediaPlayer mp, int what, int extra){
			return switch(what){
				case MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
					progressBar.setVisibility(View.VISIBLE);
					stopUpdatingVideoPosition();
					yield true;
				}
				case MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
					progressBar.setVisibility(View.GONE);
					startUpdatingVideoPosition(player);
					yield true;
				}
				default -> false;
			};
		}

		@Override
		public void onSeekComplete(MediaPlayer mp){
			if(getAbsoluteAdapterPosition()==currentIndex && player.isPlaying())
				startUpdatingVideoPosition(player);
		}

		@Override
		public void onCompletion(MediaPlayer mp){
			videoPlayPauseButton.setImageResource(R.drawable.ic_play_24);
			videoPlayPauseButton.setContentDescription(activity.getString(R.string.play));
			stopUpdatingVideoPosition();
			if(!uiVisible)
				toggleUI();
			windowView.removeCallbacks(uiAutoHider);
		}
	}
}
