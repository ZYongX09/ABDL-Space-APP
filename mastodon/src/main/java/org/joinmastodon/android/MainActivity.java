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

import org.joinmastodon.android.api.ObjectValidationException;
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
import org.joinmastodon.android.model.Notification;
import org.joinmastodon.android.model.SearchResults;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.updater.GithubSelfUpdater;
import org.parceler.Parcels;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import androidx.annotation.Nullable;
import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

public class MainActivity extends FragmentStackActivity{
	private static final String TAG="MainActivity";
	private ChatRealtimeClient chatWsClient;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState){
		AccountSession session=getCurrentSession();
		UiUtils.setUserPreferredTheme(this, /* MOSHIDON: this is for per account user themes */ session);
		super.onCreate(savedInstanceState);

		if(savedInstanceState==null){
			restartHomeFragment();
			connectChatWebSocket();
			refreshChatConversations();
		}

		if(BuildConfig.BUILD_TYPE.startsWith("appcenter")){
			// Call the appcenter SDK wrapper through reflection because it is only present in beta builds
			try{
				Class.forName("org.joinmastodon.android.AppCenterWrapper").getMethod("init", Application.class).invoke(null, getApplication());
			}catch(ClassNotFoundException|NoSuchMethodException|IllegalAccessException|InvocationTargetException ignore){}
		}else if(GithubSelfUpdater.needSelfUpdating()){
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

	@Override
	protected void onNewIntent(Intent intent){
		super.onNewIntent(intent);
		setIntent(intent);
		if(intent.getBooleanExtra("fromNotification", false)){
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
		}/*else if(intent.hasExtra(PackageInstaller.EXTRA_STATUS) && GithubSelfUpdater.needSelfUpdating()){
			GithubSelfUpdater.getInstance().handleIntentFromInstaller(intent, this);
		}*/
	}

	public void handleURL(Uri uri, String accountID){
		if(uri==null)
			return;
		if(!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))
			return;

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
		if(chatWsClient!=null) chatWsClient.connect();
	}

	@Override
	protected void onPause(){
		super.onPause();
		if(chatWsClient!=null) chatWsClient.disconnect();
	}

	@Override
	protected void onDestroy(){
		super.onDestroy();
		if(chatWsClient!=null){
			chatWsClient.disconnect();
			chatWsClient=null;
		}
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
			// 无账户时直接进入新的验证码登录页
			org.joinmastodon.android.fragments.auth.LoginEmailFragment loginEmailFragment = new org.joinmastodon.android.fragments.auth.LoginEmailFragment();
			showFragmentClearingBackStack(loginEmailFragment);
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
				getWindow().getDecorView().post(()->hf.setCurrentTab(R.id.tab_search));
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
