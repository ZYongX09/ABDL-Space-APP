package org.joinmastodon.android;

import android.Manifest;
import android.app.Application;
import android.app.Fragment;
import android.app.assist.AssistContent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BadParcelableException;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.api.requests.announcements.GetServiceNotice;
import org.joinmastodon.android.api.requests.search.GetSearchResults;
import org.joinmastodon.android.api.requests.accounts.GetAccountByID;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.chat.ChatController;
import org.joinmastodon.android.chat.ChatEvents;
import org.joinmastodon.android.chat.ChatRealtimeClient;
import org.joinmastodon.android.chat.ui.ConversationsFragment;
import org.joinmastodon.android.chat.ui.ChatFragment;
import org.joinmastodon.android.fragments.AssistContentProviderFragment;
import org.joinmastodon.android.fragments.ComposeFragment;
import org.joinmastodon.android.fragments.HomeFragment;
import org.joinmastodon.android.fragments.ProfileFragment;
import org.joinmastodon.android.fragments.SplashFragment;
import org.joinmastodon.android.fragments.ThreadFragment;
import org.joinmastodon.android.fragments.onboarding.AccountActivationFragment;
import org.joinmastodon.android.fragments.onboarding.CustomWelcomeFragment;
import org.joinmastodon.android.fragments.settings.ComposeAboutActivity;
import org.joinmastodon.android.fragments.settings.OpenSourceLicensesFragment;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Notification;
import org.joinmastodon.android.model.ServiceNotice;
import org.joinmastodon.android.model.SearchResults;
import org.joinmastodon.android.model.verification.VerificationLink;
import org.joinmastodon.android.fragments.settings.BabyVerificationResultFragment;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.updater.GithubSelfUpdater;
import org.parceler.Parcels;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.ViewModelStore;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.savedstate.SavedStateRegistry;
import androidx.savedstate.SavedStateRegistryController;
import androidx.savedstate.SavedStateRegistryOwner;
import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.utils.V;
import org.joinmastodon.android.ui.compose.ComposeLifecycleHelperKt;
import org.joinmastodon.android.ui.utils.LocationUtils;
import androidx.core.app.ActivityCompat;

public class MainActivity extends FragmentStackActivity implements LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
	private static final String TAG="MainActivity";
	public static final String EXTRA_OPEN_SOURCE_LICENSES="open_source_licenses";
	private final LifecycleRegistry lifecycleRegistry=new LifecycleRegistry(this);
	private final ViewModelStore viewModelStore=new ViewModelStore();
	private final SavedStateRegistryController savedStateController=SavedStateRegistryController.create(this);

	@Override public Lifecycle getLifecycle(){ return lifecycleRegistry; }
	@Override public ViewModelStore getViewModelStore(){ return viewModelStore; }
	@Override public SavedStateRegistry getSavedStateRegistry(){ return savedStateController.getSavedStateRegistry(); }

	private ChatRealtimeClient chatWsClient;
	private GestureDetector backGestureDetector;
	private static final int LOCATION_PERMISSION_REQUEST=7001;
	/** 服务公告弹窗引用，防止重复弹出 */
	private android.app.AlertDialog serviceNoticeDialog;
	private StartupPromptCoordinator startupPromptCoordinator;

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if(requestCode==7001){
			if(grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED){
				LocationUtils.fetchAndResolve(this, loc->{
					// 定位失败时用 IP 属地兜底
					if(loc==null) LocationUtils.fetchProvinceFromIP(this, null);
				});
			}else{
				LocationUtils.fetchProvinceFromIP(this, null);
			}
		}
	}

	@Override
	public void onBackPressed(){
		Fragment top=getTopmostFragment();
		if(top instanceof HomeFragment homeFragment && homeFragment.onBackPressed())
			return;
		super.onBackPressed();
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState){
		savedStateController.performRestore(savedInstanceState);
		lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);

		AccountSession session=getCurrentSession();
		UiUtils.setUserPreferredTheme(this, /* MOSHIDON: this is for per account user themes */ session);
		super.onCreate(savedInstanceState);

		ComposeLifecycleHelperKt.installComposeLifecycle(this);

		final float minVelocity=ViewConfiguration.get(this).getScaledMinimumFlingVelocity()*2f;
		final float maxFlingPathLength=V.dp(80);
		backGestureDetector=new GestureDetector(this, new GestureDetector.SimpleOnGestureListener(){
			@Override
			public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY){
				if(e1==null || e2==null)
					return false;
				float dx=e2.getX()-e1.getX();
				float dy=e2.getY()-e1.getY();
				if(dx>0 && Math.abs(dx)<=maxFlingPathLength && velocityX>minVelocity && Math.abs(velocityX)>Math.abs(velocityY)*1.5f){
					if(fragmentContainers!=null && fragmentContainers.size()>1){
						onBackPressed();
						return true;
					}
				}
				return false;
			}
		});

		if(savedInstanceState==null){
			restartHomeFragment();
			connectChatWebSocket();
			refreshChatConversations();
		}
		if(getIntent().getBooleanExtra(EXTRA_OPEN_SOURCE_LICENSES, false)){
			getWindow().getDecorView().post(()->openSourceLicenses(getIntent()));
		}

		// 冷启动提示统一串行：服务公告、认证驳回、新徽章。
		if(savedInstanceState==null){
			startupPromptCoordinator=new StartupPromptCoordinator(this);
			startupPromptCoordinator.start();
		}

		// 位置权限引导序列已迁移到 HomeFragment.onShown 的 showSetupGuideSequence
		// 此处仅保留无权限时的 IP 属地兜底（不阻塞 UI，不弹 sheet）
		if(savedInstanceState==null && !LocationUtils.hasLocationPermission(this)){
			LocationUtils.fetchProvinceFromIP(this, null);
		}

		if(BuildConfig.BUILD_TYPE.startsWith("appcenter")){
			// Call the appcenter SDK wrapper through reflection because it is only present in beta builds
			try{
				Class.forName("org.joinmastodon.android.AppCenterWrapper").getMethod("init", Application.class).invoke(null, getApplication());
			}catch(ClassNotFoundException|NoSuchMethodException|IllegalAccessException|InvocationTargetException ignore){}
		}else if(GithubSelfUpdater.isSupported()){
			GithubSelfUpdater.getInstance().maybeCheckForUpdates();
		}

		// 内网设备发现服务（已禁用）
		// if(session != null && session.activated){
		// 	startLanDiscoveryService();
		// }

		// LAN 登录通知处理（已禁用）
		// String lanSession=getIntent().getStringExtra("lan_login_session");
		// if(lanSession!=null && savedInstanceState==null){
		// 	getWindow().getDecorView().post(() -> showQRLoginDialog(lanSession, true));
		// }
	}

	public void restartStartupPrompts(){
		if(startupPromptCoordinator!=null) startupPromptCoordinator.stop();
		startupPromptCoordinator=new StartupPromptCoordinator(this);
		startupPromptCoordinator.start();
	}

	private void startLanDiscoveryService(){
		try{
			Intent serviceIntent = new Intent(this, LanDiscoveryService.class);
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
				startForegroundService(serviceIntent);
			}else{
				startService(serviceIntent);
			}
		}catch(Exception e){
			Log.w("MainActivity", "Failed to start LAN discovery service", e);
		}
	}

	/**
	 * 服务公告检查：冷启动时拉取后端公告（GET /api/broadcast/notice，零数据库端点）。
	 * 无公告/请求失败时静默；有公告且本地未看过则弹窗一次（每个公告 ID 只弹一次）。
	 */
	// ============ 新徽章通知 ============
	private java.util.ArrayDeque<Account.Badge> pendingNewBadgeQueue;
	private String newBadgeAccountId;

	private void checkNewBadges(){
		AccountSession session=AccountSessionManager.getInstance().getLastActiveAccount();
		if(session==null || !session.activated)
			return;
		newBadgeAccountId=session.getID();
		String selfId=session.self.id;
		String url="https://api.abdl-space.top/api/users/"+selfId+"/badges";
		okhttp3.Request request=new okhttp3.Request.Builder()
				.url(url)
				.header("Authorization", "Bearer "+session.token.accessToken)
				.get()
				.build();
		MastodonAPIController.getHttpClient().newCall(request).enqueue(new okhttp3.Callback(){
			@Override
			public void onFailure(okhttp3.Call call, java.io.IOException e){}

			@Override
			public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException{
				if(!response.isSuccessful()) return;
				String body=response.body()!=null ? response.body().string() : "";
				try{
					com.google.gson.JsonObject json=new com.google.gson.JsonParser().parse(body).getAsJsonObject();
					com.google.gson.JsonArray badges=json.getAsJsonArray("badges");
					if(badges==null || badges.size()==0) return;
					android.content.SharedPreferences prefs=getSharedPreferences("badge_popup", MODE_PRIVATE);
					java.util.ArrayDeque<Account.Badge> queue=new java.util.ArrayDeque<>();
					for(int i=badges.size()-1;i>=0;i--){
						com.google.gson.JsonObject badge=badges.get(i).getAsJsonObject();
						boolean acknowledged=badge.has("acknowledged") && badge.get("acknowledged").getAsBoolean();
						if(acknowledged) continue;
						Account.Badge b=new Account.Badge();
						b.key=badge.has("key") ? badge.get("key").getAsString() : "";
						b.name=badge.has("name") ? badge.get("name").getAsString() : "";
						b.color=badge.has("color") ? badge.get("color").getAsString() : "#7C4DFF";
						if(b.key.isEmpty()){
							// 无 key 的徽章无法确认，保持原有行为直接弹
							queue.add(b);
							continue;
						}
						// 获得徽章后的第二次启动才弹出一次：
						// 首次启动看到未确认徽章只记录不弹，第二次启动弹出，此后不再重复
						int launches=prefs.getInt("launches_"+b.key, 0);
						if(launches==0){
							prefs.edit().putInt("launches_"+b.key, 1).apply();
							continue;
						}
						if(launches>=2) continue;
						prefs.edit().putInt("launches_"+b.key, 2).apply();
						queue.add(b);
					}
					if(queue.isEmpty()) return;
					runOnUiThread(()->showNextNewBadge(queue));
				}catch(Exception ignored){}
			}
		});
	}

	private void showNextNewBadge(java.util.ArrayDeque<Account.Badge> queue){
		if(isFinishing() || isDestroyed() || queue.isEmpty())
			return;
		Account.Badge badge=queue.poll();
		new org.joinmastodon.android.ui.sheets.NewBadgeSheet(this, badge.name, null, badge.color, ()->
				acknowledgeNewBadge(newBadgeAccountId, badge, ()->showNextNewBadge(queue)))
				.show();
	}

	private void acknowledgeNewBadge(String accountId, Account.Badge badge, Runnable onDone){
		AccountSession session=AccountSessionManager.getInstance().getAccount(accountId);
		if(session==null || badge.key==null || badge.key.isEmpty()){
			onDone.run();
			return;
		}
		okhttp3.Request request=new okhttp3.Request.Builder()
				.url("https://api.abdl-space.top/api/users/badge/acknowledge")
				.header("Authorization", "Bearer "+session.token.accessToken)
				.post(okhttp3.RequestBody.create(
						okhttp3.MediaType.parse("application/json; charset=utf-8"),
						"{\"badge_keys\":[\""+badge.key.replace("\"", "")+"\"]}"))
				.build();
		MastodonAPIController.getHttpClient().newCall(request).enqueue(new okhttp3.Callback(){
			@Override
			public void onFailure(okhttp3.Call call, java.io.IOException e){ onDone.run(); }
			@Override
			public void onResponse(okhttp3.Call call, okhttp3.Response response){ onDone.run(); }
		});
	}

	private void maybeShowServiceNotice(){
		if(getCurrentSession()==null)
			return;
		MastodonAPIController.runInBackground(()->{
			ServiceNotice notice=GetServiceNotice.fetch();
			if(notice==null)
				return;
			final String seenKey="service_notice_"+notice.id;
			if(GlobalUserPreferences.alertSeen(seenKey))
				return;
			runOnUiThread(()->showServiceNoticeDialog(notice));
		});
	}

	private void showServiceNoticeDialog(ServiceNotice notice){
		if(serviceNoticeDialog!=null || isFinishing() || isDestroyed())
			return;
		final String seenKey="service_notice_"+notice.id;
		serviceNoticeDialog=new M3AlertDialogBuilder(this)
				.setTitle(notice.title!=null && !notice.title.isEmpty() ? notice.title : getString(R.string.service_notice_title))
				.setMessage(notice.content)
				.setPositiveButton(R.string.service_notice_ok, (dialog, which)->{
					GlobalUserPreferences.setAlertSeen(seenKey);
					dialog.dismiss();
				})
				.setCancelable(false)
				.create();
		serviceNoticeDialog.setOnDismissListener(dialog->{
			serviceNoticeDialog=null;
			GlobalUserPreferences.setAlertSeen(seenKey);
		});
		// 直接点击「知道了」时 setPositiveButton 已标记；此处兜底保证任何关闭路径都会记录
		serviceNoticeDialog.show();
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent ev){
		if(backGestureDetector!=null)
			backGestureDetector.onTouchEvent(ev);
		return super.dispatchTouchEvent(ev);
	}

	@Override
	protected void onNewIntent(Intent intent){
		super.onNewIntent(intent);
		setIntent(intent);
		if(intent.getBooleanExtra(EXTRA_OPEN_SOURCE_LICENSES, false)){
			openSourceLicenses(intent);
		}else if(intent.getBooleanExtra("fromNotification", false)){
			String accountID=intent.getStringExtra("accountID");
			AccountSession accountSession;
			try{
				accountSession=AccountSessionManager.getInstance().getAccount(accountID);
			}catch(IllegalStateException x){
				return;
			}
			if(intent.hasExtra("notification")){
				Notification notification=Parcels.unwrap(intent.getParcelableExtra("notification"));
				showFragmentForNotification(notification, accountID);
			}else{
				AccountSessionManager.getInstance().setLastActiveAccountID(accountID);
				Bundle args=new Bundle();
				args.putString("account", accountID);
				args.putString("tab", "notifications");
				Fragment fragment=new HomeFragment();
				fragment.setArguments(args);
				showFragmentClearingBackStack(fragment);
			}
		}else if(intent.getBooleanExtra("compose", false)){
			showCompose();
		}else if("conversations".equals(intent.getStringExtra("navigate_to"))){
			showChatConversations();
		}else if("chat".equals(intent.getStringExtra("navigate_to"))){
			long peerId=intent.getLongExtra("peer_id", 0);
			String peerName=intent.getStringExtra("peer_name");
			String peerAvatar=intent.getStringExtra("peer_avatar");
			if(peerId>0) showChatFragment(peerId, peerName!=null?peerName:"", peerAvatar);
		}else if(intent.hasExtra("lan_login_session")){
			// LAN 登录通知点击 - 显示授权弹窗
			String sessionId=intent.getStringExtra("lan_login_session");
			if(sessionId!=null){
				showQRLoginDialog(sessionId, true);
			}
		}else if(Intent.ACTION_VIEW.equals(intent.getAction())){
			handleURL(intent.getData(), null);
		}else if(intent.getBooleanExtra("explore", false)){
			restartHomeFragment();
		}/*else if(intent.hasExtra(PackageInstaller.EXTRA_STATUS) && GithubSelfUpdater.isSupported()){
			GithubSelfUpdater.getInstance().handleIntentFromInstaller(intent, this);
		}*/
	}

	private void openSourceLicenses(Intent intent){
		intent.removeExtra(EXTRA_OPEN_SOURCE_LICENSES);
		Bundle args=new Bundle();
		String accountID=intent.getStringExtra(ComposeAboutActivity.EXTRA_ACCOUNT_ID);
		if(accountID!=null)
			args.putString("account", accountID);
		Nav.go(this, OpenSourceLicensesFragment.class, args);
	}

	public void handleURL(Uri uri, String accountID){
		if(uri==null)
			return;
		if(!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))
			return;

		String verificationToken=VerificationLink.parseToken(uri);
		if(verificationToken!=null){
			Bundle args=new Bundle(); args.putString("token", verificationToken); args.putBoolean("_can_go_back", true);
			Nav.go(this, BabyVerificationResultFragment.class, args);
			return;
		}

		// QR 登录链接处理
		if("abdl-space.top".equals(uri.getHost()) && uri.getPath().startsWith("/qr-login")){
			String sessionId=uri.getQueryParameter("session");
			if(sessionId!=null){
			showQRLoginDialog(sessionId, false);
			return;
			}
		}

		// 处理个人主页链接 https://abdl-space.top/profile/userid
		String path=uri.getPath();
		if(path!=null && path.startsWith("/profile/")){
			String userIdStr=path.substring("/profile/".length());
			if(!userIdStr.isEmpty()){
				final String userId=userIdStr;
				// 直接通过用户ID跳转到个人主页
				new org.joinmastodon.android.api.requests.accounts.GetAccountByID(userId)
					.setCallback(new Callback<>(){
						@Override
						public void onSuccess(org.joinmastodon.android.model.Account result){
							Bundle args=new Bundle();
							args.putString("account", accountID);
							args.putParcelable("profileAccount", org.parceler.Parcels.wrap(result));
							Nav.go(MainActivity.this, ProfileFragment.class, args);
						}
						@Override
						public void onError(ErrorResponse error){
							error.showToast(MainActivity.this);
						}
					})
					.exec(accountID);
				return;
			}
		}

		// 处理 @username 格式
		if(path!=null && path.startsWith("/@")){
			String username=path.substring(2);
			if(!username.isEmpty()){
				// 通过用户名查找用户并跳转到个人主页
				final String uname=username;
				new org.joinmastodon.android.api.requests.search.GetSearchResults(uname, org.joinmastodon.android.api.requests.search.GetSearchResults.Type.ACCOUNTS, true, null, 0, 0)
					.setCallback(new Callback<>(){
						@Override
						public void onSuccess(org.joinmastodon.android.model.SearchResults result){
							if(result.accounts!=null && !result.accounts.isEmpty()){
								Bundle args=new Bundle();
								args.putString("account", accountID);
								args.putParcelable("profileAccount", org.parceler.Parcels.wrap(result.accounts.get(0)));
								Nav.go(MainActivity.this, ProfileFragment.class, args);
							}else{
								Toast.makeText(MainActivity.this, R.string.link_not_supported, Toast.LENGTH_SHORT).show();
							}
						}
						@Override
						public void onError(ErrorResponse error){
							error.showToast(MainActivity.this);
						}
					})
					.exec(accountID);
				return;
			}
		}

		AccountSession session;
		if(accountID==null)
			session=AccountSessionManager.getInstance().getLastActiveAccount();
		else
			session=AccountSessionManager.get(accountID);
		if(session==null || !session.activated)
			return;
		openSearchQuery(uri.toString(), session.getID(), R.string.opening_link, false, null);
	}

	private void showQRLoginDialog(String sessionId){
		showQRLoginDialog(sessionId, false);
	}

	private void showQRLoginDialog(String sessionId, boolean isLanMode){
		// 检查是否已登录
		AccountSession session=AccountSessionManager.getInstance().getLastActiveAccount();
		if(session==null || !session.activated){
			Toast.makeText(this, "请先登录后再授权", Toast.LENGTH_SHORT).show();
			return;
		}
		new org.joinmastodon.android.ui.sheets.QRLoginBottomSheet(this, sessionId, isLanMode).show();
	}

	public void openSearchQuery(String q, String accountID, int progressText, boolean fromSearch, GetSearchResults.Type type){
		new GetSearchResults(q, type, true, null, 0, 0)
				.setCallback(new Callback<>(){
					@Override
					public void onSuccess(SearchResults result){
						Bundle args=new Bundle();
						args.putString("account", accountID);
						if(result.statuses!=null && !result.statuses.isEmpty()){
							args.putParcelable("status", Parcels.wrap(result.statuses.get(0)));
							Nav.go(MainActivity.this, ThreadFragment.class, args);
						}else if(result.accounts!=null && !result.accounts.isEmpty()){
							args.putParcelable("profileAccount", Parcels.wrap(result.accounts.get(0)));
							Nav.go(MainActivity.this, ProfileFragment.class, args);
						}else{
							Toast.makeText(MainActivity.this, fromSearch ? R.string.no_search_results : R.string.link_not_supported, Toast.LENGTH_SHORT).show();
						}
					}

					@Override
					public void onError(ErrorResponse error){
						error.showToast(MainActivity.this);
					}
				})
				.wrapProgress(this, progressText, true)
				.exec(accountID);
	}

	private void showFragmentForNotification(Notification notification, String accountID){
		Fragment fragment;
		Bundle args=new Bundle();
		args.putString("account", accountID);
		args.putBoolean("_can_go_back", true);
		try{
			notification.postprocess();
		}catch(ObjectValidationException x){
			Log.w("MainActivity", x);
			return;
		}
		if(notification.status!=null){
			fragment=new ThreadFragment();
			args.putParcelable("status", Parcels.wrap(notification.status));
		}else{
			fragment=new ProfileFragment();
			args.putParcelable("profileAccount", Parcels.wrap(notification.account));
		}
		fragment.setArguments(args);
		showFragment(fragment);
		Intent intent=getIntent();
		intent.removeExtra("fromNotification");
		intent.removeExtra("notification");
		intent.removeExtra("accountID");
		setIntent(intent);
	}

	private void showCompose(){
		AccountSession session=AccountSessionManager.getInstance().getLastActiveAccount();
		if(session==null || !session.activated)
			return;
		ComposeFragment compose=new ComposeFragment();
		Bundle composeArgs=new Bundle();
		composeArgs.putString("account", session.getID());
		compose.setArguments(composeArgs);
		showFragment(compose);
	}

	@Override
	protected void onResume(){
		super.onResume();
		lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
		if(chatWsClient!=null) chatWsClient.connect();
	}

	@Override
	protected void onPause(){
		super.onPause();
		lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
		if(chatWsClient!=null) chatWsClient.disconnect();
	}

	@Override
	protected void onDestroy(){
		super.onDestroy();
		lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
		if(chatWsClient!=null){
			chatWsClient.disconnect();
			chatWsClient=null;
		}
		if(startupPromptCoordinator!=null){ startupPromptCoordinator.stop(); startupPromptCoordinator=null; }
	}

	private void connectChatWebSocket(){
		AccountSession session=AccountSessionManager.getInstance().getLastActiveAccount();
		if(session==null || !session.activated) return;
		chatWsClient=new ChatRealtimeClient(session.getID());
		chatWsClient.connect();
	}

	private void refreshChatConversations(){
		AccountSession session=AccountSessionManager.getInstance().getLastActiveAccount();
		if(session==null || !session.activated) return;
		ChatController.getInstance(session.getID()).loadConversations(true, new Callback<List<org.joinmastodon.android.chat.model.Conversation>>(){
			@Override public void onSuccess(List<org.joinmastodon.android.chat.model.Conversation> result){
				E.post(new ChatEvents.ConversationsUpdatedEvent());
			}

			@Override public void onError(ErrorResponse error){
				Log.w(TAG, "Conversation refresh failed: "+error);
			}
		});
	}

	private void showChatConversations(){
		AccountSession session=AccountSessionManager.getInstance().getLastActiveAccount();
		if(session==null) return;
		ConversationsFragment fragment=new ConversationsFragment();
		Bundle args=new Bundle();
		args.putString("account", session.getID());
		fragment.setArguments(args);
		showFragment(fragment);
	}

	private void showChatFragment(long peerId, String peerName, String peerAvatar){
		AccountSession session=AccountSessionManager.getInstance().getLastActiveAccount();
		if(session==null) return;
		ChatFragment fragment=new ChatFragment();
		Bundle args=new Bundle();
		args.putString("account", session.getID());
		args.putLong("peer_id", peerId);
		args.putString("peer_name", peerName);
		args.putString("peer_avatar", peerAvatar!=null ? peerAvatar : "");
		fragment.setArguments(args);
		showFragment(fragment);
	}

	private void maybeRequestNotificationsPermission(){
		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
			requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
		}
	}

	// MOSHIDON: we use this
	public void restartActivity(){
		finish();
		startActivity(new Intent(this, MainActivity.class));
	}

	public void restartHomeFragment(){
		if(AccountSessionManager.getInstance().getLoggedInAccounts().isEmpty()){
			Uri data=getIntent().getData(); String verificationToken=Intent.ACTION_VIEW.equals(getIntent().getAction()) ? VerificationLink.parseToken(data) : null;
			if(verificationToken!=null){
				BabyVerificationResultFragment fragment=new BabyVerificationResultFragment(); Bundle args=new Bundle(); args.putString("token", verificationToken); fragment.setArguments(args); showFragmentClearingBackStack(fragment);
			}else{
				// 无账户时直接进入新的验证码登录页
				org.joinmastodon.android.fragments.auth.LoginEmailFragment loginEmailFragment = new org.joinmastodon.android.fragments.auth.LoginEmailFragment();
				showFragmentClearingBackStack(loginEmailFragment);
			}
		}else{
			AccountSessionManager.getInstance().maybeUpdateLocalInfo();
			AccountSession session;
			Bundle args=new Bundle();
			Intent intent=getIntent();
			if(intent.getBooleanExtra("fromNotification", false)){
				String accountID=intent.getStringExtra("accountID");
				try{
					session=AccountSessionManager.getInstance().getAccount(accountID);
					if(!intent.hasExtra("notification"))
						args.putString("tab", "notifications");
				}catch(IllegalStateException x){
					session=AccountSessionManager.getInstance().getLastActiveAccount();
				}
			}else{
				session=AccountSessionManager.getInstance().getLastActiveAccount();
			}
			args.putString("account", session.getID());
			Fragment fragment=session.activated ? new HomeFragment() : new AccountActivationFragment();
			fragment.setArguments(args);
			showFragmentClearingBackStack(fragment);
			if(intent.getBooleanExtra("fromNotification", false) && intent.hasExtra("notification")){
				// Parcelables might not be compatible across app versions so this protects against possible crashes
				// when a notification was received, then the app was updated, and then the user opened the notification
				try{
					Notification notification=Parcels.unwrap(intent.getParcelableExtra("notification"));
					showFragmentForNotification(notification, session.getID());
				}catch(BadParcelableException x){
					Log.w(TAG, x);
				}
			}else if(intent.getBooleanExtra("compose", false)){
				showCompose();
			}else if(intent.getBooleanExtra("explore", false) && fragment instanceof HomeFragment hf){
				getWindow().getDecorView().post(()->hf.setCurrentTab(R.id.tab_messages));
			}else if(Intent.ACTION_VIEW.equals(intent.getAction())){
				handleURL(intent.getData(), null);
			}else{
				maybeRequestNotificationsPermission();
			}
		}
	}

	// MOSHIDON:
	public AccountSession getCurrentSession(){
		AccountSession session;
		Bundle args=new Bundle();
		Intent intent=getIntent();
		if(intent.hasExtra("fromExternalShare")) {
			return AccountSessionManager.getInstance()
					.getAccount(intent.getStringExtra("account"));
		}

		boolean fromNotification = intent.getBooleanExtra("fromNotification", false);
		boolean hasNotification = intent.hasExtra("notification");
		if(fromNotification){
			String accountID=intent.getStringExtra("accountID");
			try{
				session=AccountSessionManager.getInstance().getAccount(accountID);
				if(!hasNotification) args.putString("tab", "notifications");
			}catch(IllegalStateException x){
				session=AccountSessionManager.getInstance().getLastActiveAccount();
			}
		}else{
			session=AccountSessionManager.getInstance().getLastActiveAccount();
		}
		return session;
	}

	public Fragment getTopmostFragment(){
		if(fragmentContainers.isEmpty())
			return null;
		return getFragmentManager().findFragmentById(fragmentContainers.get(fragmentContainers.size()-1).getId());
	}

	@Override
	public void onProvideAssistContent(AssistContent outContent){
		if(getTopmostFragment() instanceof AssistContentProviderFragment provider){
			provider.onProvideAssistContent(outContent);
		}
	}
}
