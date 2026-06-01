package net.tref.xraytunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TunnelService extends Service {
    public static final String ACTION_START = "net.tref.xraytunnel.START";
    public static final String ACTION_STOP = "net.tref.xraytunnel.STOP";
    public static final String PREFS = "tunnel";
    public static final String KEY_STATUS = "status";

    private static final String CHANNEL_ID = "tunnel";
    private static final String SSH_HOST = "107.161.82.52";
    private static final int SSH_PORT = 22;
    private static final String SSH_USER = "ilya";
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final int LOCAL_PORT = 24443;
    private static final String REMOTE_HOST = "127.0.0.1";
    private static final int REMOTE_PORT = 443;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Session session;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTunnel();
            return START_NOT_STICKY;
        }
        startTunnel();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void startTunnel() {
        if (!running.compareAndSet(false, true)) {
            updateStatus("running or connecting");
            return;
        }
        createNotificationChannel();
        startForeground(1, notification("connecting"));
        executor.execute(this::runLoop);
    }

    private void stopTunnel() {
        running.set(false);
        disconnect();
        updateStatus("stopped");
        stopForeground(true);
        stopSelf();
    }

    private void runLoop() {
        while (running.get()) {
            try {
                updateStatus("connecting to " + SSH_HOST + ":" + SSH_PORT);
                startForeground(1, notification("connecting"));

                JSch jsch = new JSch();
                jsch.addIdentity("phone_tunnel", readAsset("phone_tunnel_key"), null, null);
                try (InputStream knownHosts = getAssets().open("known_hosts")) {
                    jsch.setKnownHosts(knownHosts);
                }

                Session nextSession = jsch.getSession(SSH_USER, SSH_HOST, SSH_PORT);
                nextSession.setSocketFactory(new UnderlyingNetworkSocketFactory(this));
                Properties config = new Properties();
                config.put("StrictHostKeyChecking", "yes");
                config.put("PreferredAuthentications", "publickey");
                nextSession.setConfig(config);
                nextSession.setServerAliveInterval(30000);
                nextSession.setServerAliveCountMax(3);
                nextSession.connect(15000);
                nextSession.setPortForwardingL(LOCAL_HOST, LOCAL_PORT, REMOTE_HOST, REMOTE_PORT);
                session = nextSession;

                String ready = "listening on " + LOCAL_HOST + ":" + LOCAL_PORT;
                updateStatus(ready);
                startForeground(1, notification(ready));

                while (running.get() && nextSession.isConnected()) {
                    Thread.sleep(2000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                updateStatus("error: " + cleanMessage(e));
                startForeground(1, notification("error"));
                sleepBeforeRetry();
            } finally {
                disconnect();
            }
        }
        updateStatus("stopped");
        stopForeground(true);
        stopSelf();
    }

    private void disconnect() {
        Session current = session;
        session = null;
        if (current != null) {
            current.disconnect();
        }
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String cleanMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : message;
    }

    private void updateStatus(String status) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_STATUS, status)
                .apply();
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Xray SSH Tunnel",
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Xray SSH Tunnel")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true)
                .build();
    }
}
