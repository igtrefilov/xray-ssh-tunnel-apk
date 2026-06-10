package net.tref.xraytunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class TunnelService extends Service {
    public static final String ACTION_START = "net.tref.xraytunnel.START";
    public static final String ACTION_STOP = "net.tref.xraytunnel.STOP";
    public static final String PREFS = "tunnel";
    public static final String KEY_STATUS = "status";

    private static final String TAG = "XrayTunnel";
    private static final String CHANNEL_ID = "tunnel";
    private static final int CHANNEL_CONNECT_TIMEOUT_MS = 10000;
    private static final int LOCAL_BACKLOG = 256;
    private static final int MAX_FORWARD_CONNECTIONS = 256;

    private final ExecutorService profileExecutor =
            Executors.newFixedThreadPool(TunnelConfig.PROFILES.length);
    private final ExecutorService forwardExecutor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Session[] sessions = new Session[TunnelConfig.PROFILES.length];
    private final LocalForwarder[] forwarders = new LocalForwarder[TunnelConfig.PROFILES.length];
    private final String[] statuses = new String[TunnelConfig.PROFILES.length];
    private final Object networkLock = new Object();

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network currentPreferredNetwork;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

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
        profileExecutor.shutdownNow();
        forwardExecutor.shutdownNow();
        super.onDestroy();
    }

    private void startTunnel() {
        if (!running.compareAndSet(false, true)) {
            updateStatus("running or connecting");
            return;
        }
        createNotificationChannel();
        showForegroundNotification("connecting");
        acquirePowerLocks();
        registerNetworkCallback();
        for (int i = 0; i < TunnelConfig.PROFILES.length; i++) {
            final int profileIndex = i;
            setProfileStatus(profileIndex, "connecting");
            profileExecutor.execute(() -> runLoop(profileIndex));
        }
    }

    private void stopTunnel() {
        running.set(false);
        unregisterNetworkCallback();
        disconnectAll();
        releasePowerLocks();
        updateStatus("stopped");
        stopForeground(true);
        stopSelf();
    }

    private void runLoop(int profileIndex) {
        TunnelProfile profile = TunnelConfig.PROFILES[profileIndex];
        while (running.get()) {
            try {
                setProfileStatus(profileIndex,
                        "connecting to " + profile.sshHost + ":" + TunnelConfig.SSH_PORT);

                JSch jsch = new JSch();
                jsch.addIdentity(
                        profile.name + "-generated",
                        SshKeyStore.privateKey(this, profile),
                        null,
                        null);
                try (InputStream knownHosts = getAssets().open("known_hosts")) {
                    jsch.setKnownHosts(knownHosts);
                }

                Session nextSession = jsch.getSession(
                        TunnelConfig.SSH_USER,
                        profile.sshHost,
                        TunnelConfig.SSH_PORT);
                nextSession.setSocketFactory(new UnderlyingNetworkSocketFactory(this));
                Properties config = new Properties();
                config.put("StrictHostKeyChecking", "yes");
                config.put("PreferredAuthentications", "publickey");
                nextSession.setConfig(config);
                nextSession.setServerAliveInterval(30000);
                nextSession.setServerAliveCountMax(3);
                nextSession.connect(15000);
                sessions[profileIndex] = nextSession;

                LocalForwarder forwarder = new LocalForwarder(profileIndex, profile, nextSession);
                forwarder.start();
                forwarders[profileIndex] = forwarder;

                setProfileStatus(profileIndex,
                        "listening on " + TunnelConfig.LOCAL_HOST + ":" + profile.localPort);

                while (running.get() && nextSession.isConnected() && forwarder.isRunning()) {
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

    private synchronized void disconnectAll() {
        for (int i = 0; i < sessions.length; i++) {
            disconnect(i);
        }
    }

    private synchronized void disconnect(int profileIndex) {
        LocalForwarder forwarder = forwarders[profileIndex];
        forwarders[profileIndex] = null;
        if (forwarder != null) {
            forwarder.close();
        }

        Session current = sessions[profileIndex];
        sessions[profileIndex] = null;
        if (current != null) {
            current.disconnect();
        }
    }

    private void registerNetworkCallback() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return;
        }

        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                reconnectIfPreferredNetworkChanged();
            }

            @Override
            public void onLost(Network network) {
                reconnectIfPreferredNetworkChanged();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                reconnectIfPreferredNetworkChanged();
            }
        };

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();

        synchronized (networkLock) {
            if (networkCallback != null) {
                return;
            }
            connectivityManager = manager;
            currentPreferredNetwork = UnderlyingNetworkSocketFactory.preferredUnderlyingNetwork(manager);
            networkCallback = callback;
        }

        manager.registerNetworkCallback(request, callback);
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager manager;
        ConnectivityManager.NetworkCallback callback;
        synchronized (networkLock) {
            manager = connectivityManager;
            callback = networkCallback;
            connectivityManager = null;
            networkCallback = null;
            currentPreferredNetwork = null;
        }
        if (manager != null && callback != null) {
            try {
                manager.unregisterNetworkCallback(callback);
            } catch (RuntimeException ignored) {
                // The callback may already be gone during service teardown.
            }
        }
    }

    private synchronized void acquirePowerLocks() {
        if (wakeLock == null || !wakeLock.isHeld()) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                PowerManager.WakeLock nextWakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        TAG + ":tunnel");
                nextWakeLock.setReferenceCounted(false);
                try {
                    nextWakeLock.acquire();
                    wakeLock = nextWakeLock;
                } catch (RuntimeException e) {
                    Log.w(TAG, "Failed to acquire tunnel wake lock", e);
                }
            }
        }

        if (wifiLock == null || !wifiLock.isHeld()) {
            WifiManager wifiManager =
                    (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                int lockMode = android.os.Build.VERSION.SDK_INT >= 29
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                WifiManager.WifiLock nextWifiLock =
                        wifiManager.createWifiLock(lockMode, TAG + ":wifi");
                nextWifiLock.setReferenceCounted(false);
                try {
                    nextWifiLock.acquire();
                    wifiLock = nextWifiLock;
                } catch (RuntimeException e) {
                    Log.w(TAG, "Failed to acquire tunnel Wi-Fi lock", e);
                }
            }
        }
    }

    private synchronized void releasePowerLocks() {
        if (wifiLock != null) {
            try {
                if (wifiLock.isHeld()) {
                    wifiLock.release();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to release tunnel Wi-Fi lock", e);
            } finally {
                wifiLock = null;
            }
        }

        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to release tunnel wake lock", e);
            } finally {
                wakeLock = null;
            }
        }
    }

    private void reconnectIfPreferredNetworkChanged() {
        if (!running.get()) {
            return;
        }

        ConnectivityManager manager;
        Network previousNetwork;
        Network nextNetwork;
        synchronized (networkLock) {
            manager = connectivityManager;
            if (manager == null) {
                return;
            }
            previousNetwork = currentPreferredNetwork;
            nextNetwork = UnderlyingNetworkSocketFactory.preferredUnderlyingNetwork(manager);
            if (Objects.equals(previousNetwork, nextNetwork)) {
                return;
            }
            currentPreferredNetwork = nextNetwork;
        }

        Log.i(TAG, "Underlying network changed from "
                + describeNetwork(manager, previousNetwork) + " to "
                + describeNetwork(manager, nextNetwork)
                + "; reconnecting SSH sessions");
        disconnectAll();
    }

    private String describeNetwork(ConnectivityManager manager, Network network) {
        if (network == null) {
            return "none";
        }
        return UnderlyingNetworkSocketFactory.describeNetwork(manager, network);
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
        statuses[profileIndex] = TunnelConfig.PROFILES[profileIndex].name + ": " + status;
        String summary = buildStatusSummary();
        updateStatus(summary);
        if (running.get()) {
            showForegroundNotification(summary.replace('\n', ' '));
        }
    }

    private void showForegroundNotification(String text) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    1,
                    notification(text),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification(text));
        }
    }

    private String buildStatusSummary() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < TunnelConfig.PROFILES.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(statuses[i] == null
                    ? TunnelConfig.PROFILES[i].name + ": stopped"
                    : statuses[i]);
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

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
    }

    private final class LocalForwarder implements Closeable {
        private final int profileIndex;
        private final TunnelProfile profile;
        private final Session session;
        private final AtomicBoolean accepting = new AtomicBoolean(false);
        private final AtomicInteger activeConnections = new AtomicInteger(0);
        private ServerSocket serverSocket;

        LocalForwarder(int profileIndex, TunnelProfile profile, Session session) {
            this.profileIndex = profileIndex;
            this.profile = profile;
            this.session = session;
        }

        void start() throws IOException {
            ServerSocket nextServerSocket = new ServerSocket();
            nextServerSocket.setReuseAddress(true);
            nextServerSocket.bind(
                    new InetSocketAddress(
                            InetAddress.getByName(TunnelConfig.LOCAL_HOST),
                            profile.localPort),
                    LOCAL_BACKLOG);
            serverSocket = nextServerSocket;
            accepting.set(true);
            forwardExecutor.execute(this::acceptLoop);
        }

        boolean isRunning() {
            return accepting.get();
        }

        @Override
        public void close() {
            accepting.set(false);
            closeQuietly(serverSocket);
        }

        private void acceptLoop() {
            try {
                while (running.get() && accepting.get() && session.isConnected()) {
                    Socket socket = serverSocket.accept();
                    int active = activeConnections.incrementAndGet();
                    if (active > MAX_FORWARD_CONNECTIONS) {
                        activeConnections.decrementAndGet();
                        closeQuietly(socket);
                        Log.w(TAG, profile.name
                                + " rejected local connection: too many active channels ("
                                + active + "/" + MAX_FORWARD_CONNECTIONS + ")");
                        continue;
                    }
                    forwardExecutor.execute(() -> forward(socket));
                }
            } catch (SocketException e) {
                if (accepting.get() && running.get()) {
                    Log.w(TAG, profile.name + " local listener stopped", e);
                    setProfileStatus(profileIndex, "listener error: " + cleanMessage(e));
                }
            } catch (IOException e) {
                if (accepting.get() && running.get()) {
                    Log.w(TAG, profile.name + " local listener failed", e);
                    setProfileStatus(profileIndex, "listener error: " + cleanMessage(e));
                }
            } finally {
                accepting.set(false);
                closeQuietly(serverSocket);
            }
        }

        private void forward(Socket socket) {
            ChannelDirectTCPIP channel = null;
            try {
                socket.setTcpNoDelay(true);
                channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
                channel.setHost(TunnelConfig.REMOTE_HOST);
                channel.setPort(TunnelConfig.REMOTE_PORT);
                channel.setOrgIPAddress(socket.getInetAddress().getHostAddress());
                channel.setOrgPort(socket.getPort());
                channel.setInputStream(socket.getInputStream());
                channel.setOutputStream(socket.getOutputStream());
                channel.connect(CHANNEL_CONNECT_TIMEOUT_MS);

                while (running.get()
                        && accepting.get()
                        && session.isConnected()
                        && !socket.isClosed()
                        && !channel.isClosed()) {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.w(TAG, profile.name + " forward failed", e);
            } finally {
                if (channel != null) {
                    channel.disconnect();
                }
                closeQuietly(socket);
                activeConnections.decrementAndGet();
            }
        }
    }

}
