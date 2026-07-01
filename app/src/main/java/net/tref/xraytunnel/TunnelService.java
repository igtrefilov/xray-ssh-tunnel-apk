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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class TunnelService extends Service {
    public static final String ACTION_START = "net.tref.xraytunnel.START";
    public static final String ACTION_STOP = "net.tref.xraytunnel.STOP";
    public static final String PREFS = TunnelSettings.PREFS;
    public static final String KEY_STATUS = TunnelSettings.KEY_STATUS;
    public static final String KEY_VPS_REACHABILITY = TunnelSettings.KEY_VPS_REACHABILITY;
    public static final int REACHABILITY_UNKNOWN = TunnelSettings.REACHABILITY_UNKNOWN;
    public static final int REACHABILITY_REACHABLE = TunnelSettings.REACHABILITY_REACHABLE;
    public static final int REACHABILITY_UNREACHABLE = TunnelSettings.REACHABILITY_UNREACHABLE;

    private static final String TAG = "XrayTunnel";
    private static final String CHANNEL_ID = "tunnel";
    private static final int CHANNEL_CONNECT_TIMEOUT_MS = 10000;
    private static final int VPS_PROBE_CONNECT_TIMEOUT_MS = 1000;
    private static final long HEALTH_CHECK_INTERVAL_MS = 60000;
    private static final long VPS_PROBE_REACHABLE_INTERVAL_MS = 2000;
    private static final long VPS_PROBE_UNREACHABLE_INTERVAL_MS = 500;
    private static final long RETRY_WAIT_MS = 500;
    private static final int LOCAL_BACKLOG = 256;
    private static final int MAX_FORWARD_CONNECTIONS = 256;
    private static final String STATUS_CHECKING = "Checking";
    private static final String STATUS_CONNECTING = "Connecting";
    private static final String STATUS_OFFLINE = "Offline";
    private static final String STATUS_ONLINE = "Online";
    private static final String STATUS_RETRYING = "Retrying";
    private static final String STATUS_STOPPED = "Stopped";

    private final ExecutorService profileExecutor =
            Executors.newFixedThreadPool(TunnelConfig.PROFILE_COUNT);
    private final ExecutorService forwardExecutor = Executors.newCachedThreadPool();
    private final ExecutorService reachabilityExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean foregroundStarted = new AtomicBoolean(false);
    private final AtomicBoolean reachabilityMonitorRunning = new AtomicBoolean(false);
    private final AtomicInteger reachabilityState =
            new AtomicInteger(TunnelSettings.REACHABILITY_UNKNOWN);
    private final Session[] sessions = new Session[TunnelConfig.PROFILE_COUNT];
    private final LocalForwarder[] forwarders = new LocalForwarder[TunnelConfig.PROFILE_COUNT];
    private final String[] statuses = new String[TunnelConfig.PROFILE_COUNT];
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
        super.onDestroy();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "Foreground service timed out; stopping tunnel");
        stopTunnel();
    }

    private void startTunnel() {
        if (!running.compareAndSet(false, true)) {
            updateStatus(STATUS_CONNECTING);
            return;
        }
        createNotificationChannel();
        profiles = TunnelSettings.profiles(this);
        setReachabilityState(REACHABILITY_UNKNOWN, true);
        showForegroundNotification(STATUS_CHECKING);
        acquirePowerLocks();
        registerNetworkCallback();
        startReachabilityMonitor();
        for (int i = 0; i < profiles.length; i++) {
            final int profileIndex = i;
            setProfileStatus(profileIndex, STATUS_CHECKING);
            profileExecutor.execute(() -> runLoop(profileIndex));
        }
    }

    private void stopTunnel() {
        running.set(false);
        signalReachabilityMonitor();
        unregisterNetworkCallback();
        disconnectAll();
        releasePowerLocks();
        updateStatus(STATUS_STOPPED);
        setReachabilityState(REACHABILITY_UNKNOWN, true);
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
                setProfileStatus(profileIndex, STATUS_CONNECTING);

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
                nextSession.setServerAliveInterval(30000);
                nextSession.setServerAliveCountMax(3);
                nextSession.connect(15000);
                sessions[profileIndex] = nextSession;

                forwarder = new LocalForwarder(profileIndex, profile, nextSession);
                forwarder.start();
                forwarders[profileIndex] = forwarder;

                setProfileStatus(profileIndex, STATUS_ONLINE);

                long nextHealthCheckAt = System.currentTimeMillis() + HEALTH_CHECK_INTERVAL_MS;
                while (running.get() && nextSession.isConnected() && forwarder.isRunning()) {
                    Thread.sleep(2000);
                    long now = System.currentTimeMillis();
                    if (now >= nextHealthCheckAt) {
                        verifyForwardChannel(profile, nextSession);
                        nextHealthCheckAt = now + HEALTH_CHECK_INTERVAL_MS;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.w(TAG, statusName(profileIndex) + " tunnel error", e);
                setProfileStatus(profileIndex, STATUS_RETRYING);
                waitBeforeRetry();
            } finally {
                disconnect(profileIndex);
            }
        }
        setProfileStatus(profileIndex, STATUS_STOPPED);
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
                TunnelProfile profile = profiles.length == 0 ? null : profiles[0];
                boolean reachable = profile != null && isVpsReachable(profile);
                int previousState = reachabilityState.get();
                int nextState = reachable
                        ? REACHABILITY_REACHABLE
                        : REACHABILITY_UNREACHABLE;
                setReachabilityState(nextState, false);

                if (!reachable) {
                    disconnectAll();
                } else if (previousState != REACHABILITY_REACHABLE) {
                    signalReachabilityMonitor();
                }

                waitForNextReachabilityProbe(reachable
                        ? VPS_PROBE_REACHABLE_INTERVAL_MS
                        : VPS_PROBE_UNREACHABLE_INTERVAL_MS);
            }
        } finally {
            reachabilityMonitorRunning.set(false);
        }
    }

    private boolean isVpsReachable(TunnelProfile profile) {
        Socket socket = null;
        try {
            socket = new UnderlyingNetworkSocketFactory(
                    this,
                    VPS_PROBE_CONNECT_TIMEOUT_MS,
                    false)
                    .createSocket(profile.sshHost, profile.sshPort);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(socket);
        }
    }

    private boolean waitForReachableServer(int profileIndex) throws InterruptedException {
        while (running.get()
                && reachabilityState.get() != REACHABILITY_REACHABLE) {
            String status = reachabilityState.get() == REACHABILITY_UNREACHABLE
                    ? STATUS_OFFLINE
                    : STATUS_CHECKING;
            setProfileStatus(profileIndex, status);
            waitOnReachabilitySignal(VPS_PROBE_UNREACHABLE_INTERVAL_MS);
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
        setProfileStatus(profileIndex, STATUS_RETRYING);
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
        if (running.get()) {
            if (state == REACHABILITY_UNREACHABLE) {
                updateAllProfileStatuses(STATUS_OFFLINE);
            } else if (state == REACHABILITY_REACHABLE
                    && previous != REACHABILITY_REACHABLE) {
                updateAllProfileStatuses(STATUS_CONNECTING);
            } else if (state == REACHABILITY_UNKNOWN) {
                updateAllProfileStatuses(STATUS_CHECKING);
            }
            showForegroundNotification(notificationStatusText());
        }
    }

    private synchronized void updateAllProfileStatuses(String status) {
        for (int i = 0; i < profiles.length; i++) {
            statuses[i] = profileStatusText(i, status);
        }
        updateStatus(buildStatusSummary());
    }

    private synchronized void setProfileStatus(int profileIndex, String status) {
        statuses[profileIndex] = profileStatusText(profileIndex, status);
        String summary = buildStatusSummary();
        updateStatus(summary);
        if (running.get()) {
            showForegroundNotification(notificationStatusText());
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

    private String buildStatusSummary() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < profiles.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(statuses[i] == null
                    ? profileStatusText(i, STATUS_STOPPED)
                    : statuses[i]);
        }
        return builder.toString();
    }

    private String notificationStatusText() {
        return buildStatusSummary().replace('\n', ' ');
    }

    private String profileStatusText(int profileIndex, String status) {
        if (profiles.length <= 1) {
            return status;
        }
        return statusName(profileIndex) + ": " + status;
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
                    setProfileStatus(profileIndex, STATUS_RETRYING);
                }
            } catch (IOException e) {
                if (accepting.get() && running.get()) {
                    Log.w(TAG, profile.sshHost + " local listener failed", e);
                    setProfileStatus(profileIndex, STATUS_RETRYING);
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

}
