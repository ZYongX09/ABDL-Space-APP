package org.joinmastodon.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.gson.Gson;

import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LanDiscoveryService extends Service {
    private static final String TAG = "LanDiscoveryService";
    private static final int UDP_PORT = 9527;
    private static final String CHANNEL_ID = "lan_discovery";
    private static final String CHANNEL_NAME = "内网设备发现";
    private static final String API_BASE = "https://api.abdl-space.top";
    private static final long BROADCAST_INTERVAL = 3000;
    private static final long HEARTBEAT_INTERVAL = 30000;
    private static final long POLL_INTERVAL = 1000;

    private Thread broadcastThread;
    private Thread heartbeatThread;
    private Thread pollThread;
    private volatile boolean isRunning = false;
    private final OkHttpClient httpClient = new OkHttpClient();
    private volatile String lastPendingSessionId = null;
    private static volatile String sShownSessionId = null; // 静态标志，跨 Service 实例持久化

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AccountSession session = AccountSessionManager.getInstance().getLastActiveAccount();
        if (session == null || !session.activated) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(1, buildNotification());
        startBroadcasting();
        startHeartbeat();
        startPendingPolling();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (broadcastThread != null) broadcastThread.interrupt();
        if (heartbeatThread != null) heartbeatThread.interrupt();
        if (pollThread != null) pollThread.interrupt();
    }

    private void startBroadcasting() {
        if (isRunning) return;
        isRunning = true;
        broadcastThread = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                while (isRunning && !Thread.currentThread().isInterrupted()) {
                    AccountSession session = AccountSessionManager.getInstance().getLastActiveAccount();
                    if (session != null && session.activated && session.self != null) {
                        String broadcast = String.format(
                            "{\"action\":\"device_online\",\"userId\":\"%s\",\"username\":\"%s\",\"appId\":\"abdl_space\",\"timestamp\":%d}",
                            session.self.id,
                            session.self.username.replace("\"", "\\\""),
                            System.currentTimeMillis() / 1000
                        );
                        byte[] data = broadcast.getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), UDP_PORT));
                    }
                    Thread.sleep(BROADCAST_INTERVAL);
                }
            } catch (InterruptedException ignored) {} catch (Exception e) { Log.e(TAG, "Broadcast error", e); }
        }, "UDP-Broadcast");
        broadcastThread.setDaemon(true);
        broadcastThread.start();
    }

    private void startHeartbeat() {
        heartbeatThread = new Thread(() -> {
            while (isRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    AccountSession session = AccountSessionManager.getInstance().getLastActiveAccount();
                    if (session != null && session.activated && session.self != null) {
                        String json = new Gson().toJson(new HeartbeatRequest(session.self.id, session.self.username));
                        httpClient.newCall(new Request.Builder()
                                .url(API_BASE + "/api/auth/lan/heartbeat")
                                .post(RequestBody.create(MediaType.parse("application/json"), json))
                                .header("Authorization", "Bearer " + session.token.accessToken)
                                .build()).execute();
                    }
                    Thread.sleep(HEARTBEAT_INTERVAL);
                } catch (InterruptedException ignored) {} catch (Exception e) { Log.e(TAG, "Heartbeat error", e); }
            }
        }, "LAN-Heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void startPendingPolling() {
        pollThread = new Thread(() -> {
            while (isRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    AccountSession session = AccountSessionManager.getInstance().getLastActiveAccount();
                    if (session != null && session.activated && session.self != null) {
                        Response response = httpClient.newCall(new Request.Builder()
                                .url(API_BASE + "/api/auth/lan/pending")
                                .get()
                                .header("Authorization", "Bearer " + session.token.accessToken)
                                .build()).execute();

                        if (response.isSuccessful()) {
                            String body = response.body() != null ? response.body().string() : "";
                            PendingResponse pending = new Gson().fromJson(body, PendingResponse.class);

                            if (pending != null && pending.pending && pending.sessionId != null) {
                                if (!pending.sessionId.equals(sShownSessionId)) {
                                    sShownSessionId = pending.sessionId;
                                    lastPendingSessionId = pending.sessionId;
                                    showAuthorizationNotification(pending.sessionId);
                                }
                            } else {
                                lastPendingSessionId = null;
                            }
                        }
                    }
                    Thread.sleep(POLL_INTERVAL);
                } catch (InterruptedException ignored) {} catch (Exception e) { Log.e(TAG, "Pending polling error", e); }
            }
        }, "LAN-PendingPoll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void showAuthorizationNotification(String sessionId) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("lan_login_session", sessionId);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    /** Called by MainActivity when dialog is dismissed */
    public static void resetShownSession() {
        sShownSessionId = null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("内网设备发现服务");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ABDL Space").setContentText("内网设备发现服务运行中")
            .setSmallIcon(R.drawable.ic_tab_home).setOngoing(true).build();
    }

    private static class HeartbeatRequest {
        public String userId; public String username;
        public HeartbeatRequest(String userId, String username) { this.userId = userId; this.username = username; }
    }
    private static class PendingResponse {
        public boolean pending; public String sessionId; public long createdAt;
    }
}
