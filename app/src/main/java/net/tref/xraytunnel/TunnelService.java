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
    private static final int SSH_PORT = 22;
    private static final String SSH_USER = "ilya";
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final String REMOTE_HOST = "127.0.0.1";
    private static final int REMOTE_PORT = 443;
    private static final TunnelProfile[] PROFILES = new TunnelProfile[] {
            new TunnelProfile(
                    "107",
                    "107.161.82.52",
                    24443,
                    new String[] {"phone_tunnel_107_ed25519_key", "phone_tunnel_key"}),
            new TunnelProfile(
                    "151",
                    "151.245.140.102",
                    34443,
                    new String[] {"phone_tunnel_151_key", "phone_tunnel_151_ed25519_key"})
    };

    private final ExecutorService executor = Executors.newFixedThreadPool(PROFILES.length);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Session[] sessions = new Session[PROFILES.length];
    private final String[] statuses = new String[PROFILES.length];

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
        for (int i = 0; i < PROFILES.length; i++) {
            final int profileIndex = i;
            setProfileStatus(profileIndex, "connecting");
            executor.execute(() -> runLoop(profileIndex));
        }
    }

    private void stopTunnel() {
        running.set(false);
        disconnectAll();
        updateStatus("stopped");
        stopForeground(true);
        stopSelf();
    }

    private void runLoop(int profileIndex) {
        TunnelProfile profile = PROFILES[profileIndex];
        while (running.get()) {
            try {
                setProfileStatus(profileIndex, "connecting to " + profile.sshHost + ":" + SSH_PORT);

                JSch jsch = new JSch();
                for (String keyAsset : profile.keyAssets) {
                    jsch.addIdentity(profile.name + "-" + keyAsset, readAsset(keyAsset), null, null);
                }
                try (InputStream knownHosts = getAssets().open("known_hosts")) {
                    jsch.setKnownHosts(knownHosts);
                }

                Session nextSession = jsch.getSession(SSH_USER, profile.sshHost, SSH_PORT);
                nextSession.setSocketFactory(new UnderlyingNetworkSocketFactory(this));
                Properties config = new Properties();
                config.put("StrictHostKeyChecking", "yes");
                config.put("PreferredAuthentications", "publickey");
                nextSession.setConfig(config);
                nextSession.setServerAliveInterval(30000);
                nextSession.setServerAliveCountMax(3);
                nextSession.connect(15000);
                nextSession.setPortForwardingL(LOCAL_HOST, profile.localPort, REMOTE_HOST, REMOTE_PORT);
                sessions[profileIndex] = nextSession;

                setProfileStatus(profileIndex, "listening on " + LOCAL_HOST + ":" + profile.localPort);

                while (running.get() && nextSession.isConnected()) {
                    Thread.sleep(2000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                setProfileStatus(profileIndex, "error: " + cleanMessage(e));
                sleepBeforeRetry();
            } finally {
                disconnect(profileIndex);
            }
        }
        setProfileStatus(profileIndex, "stopped");
    }

    private void disconnectAll() {
        for (int i = 0; i < sessions.length; i++) {
            disconnect(i);
        }
    }

    private void disconnect(int profileIndex) {
        Session current = sessions[profileIndex];
        sessions[profileIndex] = null;
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

    private synchronized void setProfileStatus(int profileIndex, String status) {
        statuses[profileIndex] = PROFILES[profileIndex].name + ": " + status;
        String summary = buildStatusSummary();
        updateStatus(summary);
        if (running.get()) {
            startForeground(1, notification(summary.replace('\n', ' ')));
        }
    }

    private String buildStatusSummary() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < PROFILES.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(statuses[i] == null ? PROFILES[i].name + ": stopped" : statuses[i]);
        }
        return builder.toString();
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

    private static final class TunnelProfile {
        final String name;
        final String sshHost;
        final int localPort;
        final String[] keyAssets;

        TunnelProfile(String name, String sshHost, int localPort, String[] keyAssets) {
            this.name = name;
            this.sshHost = sshHost;
            this.localPort = localPort;
            this.keyAssets = keyAssets;
        }
    }
}
