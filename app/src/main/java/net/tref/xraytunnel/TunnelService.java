package net.tref.xraytunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class TunnelService extends Service {
    public static final String ACTION_START = "net.tref.xraytunnel.START";
    public static final String ACTION_STOP = "net.tref.xraytunnel.STOP";
    public static final String PREFS = TunnelSettings.PREFS;
    public static final String KEY_STATUS = TunnelSettings.KEY_STATUS;
    public static final String KEY_VPS_REACHABILITY = TunnelSettings.KEY_VPS_REACHABILITY;
    public static final int REACHABILITY_UNKNOWN = TunnelSettings.REACHABILITY_UNKNOWN;
    public static final int REACHABILITY_REACHABLE = TunnelSettings.REACHABILITY_REACHABLE;
    public static final int REACHABILITY_UNREACHABLE = TunnelSettings.REACHABILITY_UNREACHABLE;
    public static final int REACHABILITY_DEGRADED = TunnelSettings.REACHABILITY_DEGRADED;

    private static final String TAG = "XrayTunnel";
    private static final String CHANNEL_ID = "tunnel";
    private static final int CHANNEL_CONNECT_TIMEOUT_MS = 10000;
    private static final int VPS_PROBE_CONNECT_TIMEOUT_MS = 1000;
    private static final int INTERNET_PROBE_CONNECT_TIMEOUT_MS = 1000;
    private static final long HEALTH_CHECK_INTERVAL_MS = 60000;
    private static final long HEALTH_CHECK_LOOP_INTERVAL_MS = 2000;
    private static final long FORWARD_HEALTH_STALE_MS = 90000;
    private static final long DIAGNOSTIC_PROBE_INTERVAL_MS = 1000;
    private static final long DIAGNOSTIC_PROBE_WAIT_MS = 1200;
    private static final long RETRY_WAIT_MS = 500;
    private static final int LOCAL_BACKLOG = 256;
    private static final int MAX_FORWARD_CONNECTIONS = 256;
    private static final String STATUS_TUNNEL_DOWN = "Tunnel Down";
    private static final String STATUS_VPS_DOWN = "VPS Down";
    private static final String STATUS_CHEBURNET = "Cheburnet";
    private static final String STATUS_OFFLINE = "Offline";
    private static final String STATUS_ONLINE = "Online";
    private static final String STATUS_STOPPED = "Stopped";
    private static final String YA_HOST = "ya.ru";
    private static final String GOOGLE_HOST = "google.com";
    private static final int HTTPS_PORT = 443;

    private final ExecutorService profileExecutor =
            Executors.newFixedThreadPool(TunnelConfig.PROFILE_COUNT);
    private final ExecutorService forwardExecutor = Executors.newCachedThreadPool();
    private final ExecutorService reachabilityExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService diagnosticProbeExecutor = Executors.newFixedThreadPool(3);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean foregroundStarted = new AtomicBoolean(false);
    private final AtomicBoolean reachabilityMonitorRunning = new AtomicBoolean(false);
    private final AtomicBoolean tunnelOnline = new AtomicBoolean(false);
    private final AtomicBoolean vpsReachable = new AtomicBoolean(false);
    private final AtomicLong lastSuccessfulForwardCheckMs = new AtomicLong(0);
    private final AtomicInteger reachabilityState =
            new AtomicInteger(TunnelSettings.REACHABILITY_UNKNOWN);
    private final Session[] sessions = new Session[TunnelConfig.PROFILE_COUNT];
    private final LocalForwarder[] forwarders = new LocalForwarder[TunnelConfig.PROFILE_COUNT];
    private final Object networkLock = new Object();
    private final Object reachabilitySignal = new Object();

    private TunnelProfile[] profiles = new TunnelProfile[] {
            TunnelSettings.defaultValues().toProfile()
    };
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
        reachabilityExecutor.shutdownNow();
        diagnosticProbeExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "Foreground service timed out; stopping tunnel");
        stopTunnel();
    }

    private void startTunnel() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        createNotificationChannel();
        profiles = TunnelSettings.profiles(this);
        setReachabilityState(REACHABILITY_UNKNOWN, true);
        updateConnectionStatus(STATUS_OFFLINE, REACHABILITY_UNKNOWN);
        showForegroundNotification(STATUS_OFFLINE);
        acquirePowerLocks();
        registerNetworkCallback();
        startReachabilityMonitor();
        for (int i = 0; i < profiles.length; i++) {
            final int profileIndex = i;
            profileExecutor.execute(() -> runLoop(profileIndex));
        }
    }

    private void stopTunnel() {
        running.set(false);
        tunnelOnline.set(false);
        vpsReachable.set(false);
        signalReachabilityMonitor();
        unregisterNetworkCallback();
        disconnectAll();
        releasePowerLocks();
        updateConnectionStatus(STATUS_STOPPED, REACHABILITY_UNKNOWN);
        stopForeground(true);
        foregroundStarted.set(false);
        stopSelf();
    }

    private void runLoop(int profileIndex) {
        TunnelProfile profile = profiles[profileIndex];
        while (running.get()) {
            Session nextSession = null;
            LocalForwarder forwarder = null;
            try {
                if (!waitForReachableServer(profileIndex)) {
                    continue;
                }
                JSch jsch = new JSch();
                jsch.addIdentity(
                        profile.sshHost + "-bundled",
                        SshKeyStore.privateKey(this, profile),
                        null,
                        null);
                if (profile.verifyHostKey) {
                    try (InputStream knownHosts = getAssets().open("known_hosts")) {
                        jsch.setKnownHosts(knownHosts);
                    }
                }

                nextSession = jsch.getSession(
                        profile.sshUser,
                        profile.sshHost,
                        profile.sshPort);
                nextSession.setSocketFactory(new UnderlyingNetworkSocketFactory(this));
                Properties config = new Properties();
                config.put("StrictHostKeyChecking", profile.verifyHostKey ? "yes" : "no");
                config.put("PreferredAuthentications", "publickey");
                nextSession.setConfig(config);
                // Permit short radio/Wi-Fi stalls while the device sleeps. The session
                // is still actively checked below and immediately rebuilt on failures.
                nextSession.setServerAliveInterval(30000);
                nextSession.setServerAliveCountMax(3);
                nextSession.connect(15000);
                sessions[profileIndex] = nextSession;

                forwarder = new LocalForwarder(profileIndex, profile, nextSession);
                forwarder.start();
                forwarders[profileIndex] = forwarder;

                verifyForwardChannel(profile, nextSession);
                markTunnelOnline();

                long nextHealthCheckAt = SystemClock.elapsedRealtime() + HEALTH_CHECK_INTERVAL_MS;
                while (running.get() && nextSession.isConnected() && forwarder.isRunning()) {
                    Thread.sleep(HEALTH_CHECK_LOOP_INTERVAL_MS);
                    if (SystemClock.elapsedRealtime() >= nextHealthCheckAt) {
                        verifyForwardChannel(profile, nextSession);
                        lastSuccessfulForwardCheckMs.set(SystemClock.elapsedRealtime());
                        nextHealthCheckAt = SystemClock.elapsedRealtime() + HEALTH_CHECK_INTERVAL_MS;
                    }
                }
                if (running.get()) {
                    markTunnelUnavailable();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.w(TAG, statusName(profileIndex) + " tunnel error", e);
                markTunnelUnavailable();
                waitBeforeRetry();
            } finally {
                disconnect(profileIndex);
            }
        }
    }

    private void verifyForwardChannel(TunnelProfile profile, Session session) throws Exception {
        ChannelDirectTCPIP channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
        try {
            channel.setHost(profile.remoteHost);
            channel.setPort(profile.remotePort);
            channel.setOrgIPAddress(profile.localHost);
            channel.setOrgPort(profile.localPort);
            channel.setInputStream(new ByteArrayInputStream(new byte[0]));
            channel.setOutputStream(new ByteArrayOutputStream());
            channel.connect(CHANNEL_CONNECT_TIMEOUT_MS);
        } finally {
            channel.disconnect();
        }
    }

    private void startReachabilityMonitor() {
        if (!reachabilityMonitorRunning.compareAndSet(false, true)) {
            return;
        }
        reachabilityExecutor.execute(this::runReachabilityMonitor);
    }

    private void runReachabilityMonitor() {
        try {
            while (running.get()) {
                if (tunnelOnline.get()) {
                    if (isForwardHealthStale()) {
                        Log.w(TAG, "SSH forward health check became stale; reconnecting tunnel");
                        markTunnelUnavailable();
                        disconnectAll();
                    }
                    waitForNextReachabilityProbe(DIAGNOSTIC_PROBE_INTERVAL_MS);
                    continue;
                }
                TunnelProfile profile = profiles.length == 0 ? null : profiles[0];
                if (profile != null) {
                    updateDiagnosticStatus(probeConnectivity(profile));
                }
                waitForNextReachabilityProbe(DIAGNOSTIC_PROBE_INTERVAL_MS);
            }
        } finally {
            reachabilityMonitorRunning.set(false);
        }
    }

    private boolean isReachable(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = new UnderlyingNetworkSocketFactory(
                    this,
                    timeoutMs,
                    false)
                    .createSocket(host, port);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(socket);
        }
    }

    private DiagnosticResult probeConnectivity(TunnelProfile profile) {
        AtomicBoolean vps = new AtomicBoolean(false);
        AtomicBoolean ya = new AtomicBoolean(false);
        AtomicBoolean google = new AtomicBoolean(false);
        CountDownLatch completed = new CountDownLatch(3);

        diagnosticProbeExecutor.execute(() -> {
            vps.set(isReachable(profile.sshHost, profile.sshPort, VPS_PROBE_CONNECT_TIMEOUT_MS));
            completed.countDown();
        });
        diagnosticProbeExecutor.execute(() -> {
            ya.set(isReachable(YA_HOST, HTTPS_PORT, INTERNET_PROBE_CONNECT_TIMEOUT_MS));
            completed.countDown();
        });
        diagnosticProbeExecutor.execute(() -> {
            google.set(isReachable(GOOGLE_HOST, HTTPS_PORT, INTERNET_PROBE_CONNECT_TIMEOUT_MS));
            completed.countDown();
        });

        try {
            completed.await(DIAGNOSTIC_PROBE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new DiagnosticResult(vps.get(), ya.get(), google.get());
    }

    private void updateDiagnosticStatus(DiagnosticResult result) {
        if (!running.get() || tunnelOnline.get()) {
            return;
        }
        vpsReachable.set(result.vpsReachable);
        if (result.vpsReachable) {
            updateConnectionStatus(STATUS_TUNNEL_DOWN, REACHABILITY_UNREACHABLE);
            signalReachabilityMonitor();
            return;
        }
        if (result.yaReachable && result.googleReachable) {
            updateConnectionStatus(STATUS_VPS_DOWN, REACHABILITY_UNREACHABLE);
            return;
        }
        if (result.yaReachable && !result.googleReachable) {
            updateConnectionStatus(STATUS_CHEBURNET, REACHABILITY_DEGRADED);
            return;
        }
        if (!result.yaReachable && result.googleReachable) {
            updateConnectionStatus(STATUS_VPS_DOWN, REACHABILITY_UNREACHABLE);
            return;
        }
        updateConnectionStatus(STATUS_OFFLINE, REACHABILITY_UNKNOWN);
    }

    private void markTunnelOnline() {
        vpsReachable.set(true);
        tunnelOnline.set(true);
        lastSuccessfulForwardCheckMs.set(SystemClock.elapsedRealtime());
        updateConnectionStatus(STATUS_ONLINE, REACHABILITY_REACHABLE);
        signalReachabilityMonitor();
    }

    private void markTunnelUnavailable() {
        if (!running.get()) {
            return;
        }
        tunnelOnline.set(false);
        lastSuccessfulForwardCheckMs.set(0);
        updateConnectionStatus(STATUS_TUNNEL_DOWN, REACHABILITY_UNREACHABLE);
        signalReachabilityMonitor();
    }

    private boolean isForwardHealthStale() {
        long lastCheckAt = lastSuccessfulForwardCheckMs.get();
        return lastCheckAt == 0
                || SystemClock.elapsedRealtime() - lastCheckAt > FORWARD_HEALTH_STALE_MS;
    }

    private boolean waitForReachableServer(int profileIndex) throws InterruptedException {
        while (running.get()
                && !vpsReachable.get()) {
            waitOnReachabilitySignal(RETRY_WAIT_MS);
        }
        return running.get();
    }

    private void waitBeforeRetry() {
        try {
            waitOnReachabilitySignal(RETRY_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void waitForNextReachabilityProbe(long timeoutMs) {
        try {
            waitOnReachabilitySignal(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void waitOnReachabilitySignal(long timeoutMs) throws InterruptedException {
        synchronized (reachabilitySignal) {
            reachabilitySignal.wait(timeoutMs);
        }
    }

    private void signalReachabilityMonitor() {
        synchronized (reachabilitySignal) {
            reachabilitySignal.notifyAll();
        }
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

    private void reconnectProfile(int profileIndex, String reason) {
        if (!running.get()) {
            return;
        }
        Log.w(TAG, statusName(profileIndex) + " reconnecting: " + reason);
        markTunnelUnavailable();
        disconnect(profileIndex);
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
                signalReachabilityMonitor();
                reconnectIfPreferredNetworkChanged();
            }

            @Override
            public void onLost(Network network) {
                signalReachabilityMonitor();
                reconnectIfPreferredNetworkChanged();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                signalReachabilityMonitor();
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
        markTunnelUnavailable();
        disconnectAll();
        signalReachabilityMonitor();
    }

    private String describeNetwork(ConnectivityManager manager, Network network) {
        if (network == null) {
            return "none";
        }
        return UnderlyingNetworkSocketFactory.describeNetwork(manager, network);
    }

    private void setReachabilityState(int state, boolean force) {
        int previous = reachabilityState.getAndSet(state);
        if (!force && previous == state) {
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(KEY_VPS_REACHABILITY, state)
                .apply();
    }

    private synchronized void updateConnectionStatus(String status, int dotState) {
        setReachabilityState(dotState, false);
        updateStatus(status);
        if (running.get()) {
            showForegroundNotification(status);
        }
    }

    private void showForegroundNotification(String text) {
        Notification nextNotification = notification(text);
        if (foregroundStarted.compareAndSet(false, true)) {
            startForegroundNotification(nextNotification);
        } else {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(1, nextNotification);
            }
        }
    }

    private void startForegroundNotification(Notification nextNotification) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    1,
                    nextNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            return;
        }
        startForeground(1, nextNotification);
    }

    private String statusName(int profileIndex) {
        if (profileIndex >= 0 && profileIndex < profiles.length && profiles[profileIndex] != null) {
            return profiles[profileIndex].sshHost;
        }
        return TunnelConfig.DEFAULT_SSH_HOST;
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

        RemoteViews content = notificationContent(text);
        builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(contentIntent())
                .setSmallIcon(R.drawable.ic_notification_tunnel)
                .setOngoing(true);

        if (android.os.Build.VERSION.SDK_INT >= 24) {
            builder
                    .setCustomContentView(content)
                    .setCustomBigContentView(content)
                    .setStyle(new Notification.DecoratedCustomViewStyle());
            return builder.build();
        }

        Notification notification = builder.build();
        notification.contentView = content;
        return notification;
    }

    private RemoteViews notificationContent(String text) {
        RemoteViews views = new RemoteViews(getPackageName(), R.layout.notification_tunnel);
        views.setTextViewText(R.id.notification_title, getString(R.string.app_name));
        views.setTextViewText(R.id.notification_status, text);
        views.setImageViewResource(R.id.notification_dot, notificationDotDrawable());
        return views;
    }

    private int notificationDotDrawable() {
        int state = reachabilityState.get();
        if (state == REACHABILITY_REACHABLE) {
            return R.drawable.status_dot_green;
        }
        if (state == REACHABILITY_UNREACHABLE) {
            return R.drawable.status_dot_red;
        }
        return R.drawable.status_dot_gray;
    }

    private PendingIntent contentIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 0, intent, flags);
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
        private final Set<Socket> activeSockets = Collections.synchronizedSet(new HashSet<>());
        private final Set<ChannelDirectTCPIP> activeChannels =
                Collections.synchronizedSet(new HashSet<>());
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
                            InetAddress.getByName(profile.localHost),
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
            synchronized (activeChannels) {
                for (ChannelDirectTCPIP channel : activeChannels) {
                    channel.disconnect();
                }
            }
            synchronized (activeSockets) {
                for (Socket socket : activeSockets) {
                    closeQuietly(socket);
                }
            }
        }

        private void acceptLoop() {
            try {
                while (running.get() && accepting.get() && session.isConnected()) {
                    Socket socket = serverSocket.accept();
                    int active = activeConnections.incrementAndGet();
                    if (active > MAX_FORWARD_CONNECTIONS) {
                        activeConnections.decrementAndGet();
                        closeQuietly(socket);
                        Log.w(TAG, profile.sshHost
                                + " rejected local connection: too many active channels ("
                                + active + "/" + MAX_FORWARD_CONNECTIONS + ")");
                        continue;
                    }
                    forwardExecutor.execute(() -> forward(socket));
                }
            } catch (SocketException e) {
                if (accepting.get() && running.get()) {
                    Log.w(TAG, profile.sshHost + " local listener stopped", e);
                    markTunnelUnavailable();
                }
            } catch (IOException e) {
                if (accepting.get() && running.get()) {
                    Log.w(TAG, profile.sshHost + " local listener failed", e);
                    markTunnelUnavailable();
                }
            } finally {
                accepting.set(false);
                closeQuietly(serverSocket);
            }
        }

        private void forward(Socket socket) {
            ChannelDirectTCPIP channel = null;
            boolean channelConnected = false;
            activeSockets.add(socket);
            try {
                socket.setTcpNoDelay(true);
                channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
                activeChannels.add(channel);
                channel.setHost(profile.remoteHost);
                channel.setPort(profile.remotePort);
                channel.setOrgIPAddress(socket.getInetAddress().getHostAddress());
                channel.setOrgPort(socket.getPort());
                channel.setInputStream(socket.getInputStream());
                channel.setOutputStream(socket.getOutputStream());
                channel.connect(CHANNEL_CONNECT_TIMEOUT_MS);
                channelConnected = true;

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
                Log.w(TAG, profile.sshHost + " forward failed", e);
                if (!channelConnected && accepting.get() && running.get()) {
                    reconnectProfile(profileIndex, "forward channel failed");
                }
            } finally {
                if (channel != null) {
                    channel.disconnect();
                    activeChannels.remove(channel);
                }
                closeQuietly(socket);
                activeSockets.remove(socket);
                activeConnections.decrementAndGet();
            }
        }
    }

    private static final class DiagnosticResult {
        final boolean vpsReachable;
        final boolean yaReachable;
        final boolean googleReachable;

        DiagnosticResult(boolean vpsReachable, boolean yaReachable, boolean googleReachable) {
            this.vpsReachable = vpsReachable;
            this.yaReachable = yaReachable;
            this.googleReachable = googleReachable;
        }
    }

}
