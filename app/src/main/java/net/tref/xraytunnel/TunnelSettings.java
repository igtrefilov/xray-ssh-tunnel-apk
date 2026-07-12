package net.tref.xraytunnel;

import android.content.Context;
import android.content.SharedPreferences;

final class TunnelSettings {
    static final String PREFS = "tunnel";
    static final String KEY_STATUS = "status";
    static final String KEY_VPS_REACHABILITY = "vps_reachability";
    static final int REACHABILITY_UNKNOWN = 0;
    static final int REACHABILITY_REACHABLE = 1;
    static final int REACHABILITY_UNREACHABLE = 2;
    static final int REACHABILITY_DEGRADED = 3;

    private static final String KEY_SSH_HOST = "ssh_host";
    private static final String KEY_SSH_USER = "ssh_user";
    private static final String KEY_SSH_PORT = "ssh_port";
    private static final String KEY_LOCAL_HOST = "local_host";
    private static final String KEY_LOCAL_PORT = "local_port";
    private static final String KEY_REMOTE_HOST = "remote_host";
    private static final String KEY_REMOTE_PORT = "remote_port";
    private static final String KEY_VERIFY_HOST_KEY = "verify_host_key";

    private TunnelSettings() {
    }

    static Values defaultValues() {
        return new Values(
                TunnelConfig.DEFAULT_SSH_HOST,
                TunnelConfig.DEFAULT_SSH_USER,
                TunnelConfig.DEFAULT_SSH_PORT,
                TunnelConfig.DEFAULT_LOCAL_HOST,
                TunnelConfig.DEFAULT_LOCAL_PORT,
                TunnelConfig.DEFAULT_REMOTE_HOST,
                TunnelConfig.DEFAULT_REMOTE_PORT,
                true);
    }

    static Values loadValues(Context context) {
        SharedPreferences prefs = prefs(context);
        Values defaults = defaultValues();
        return new Values(
                readString(prefs, KEY_SSH_HOST, defaults.sshHost),
                readString(prefs, KEY_SSH_USER, defaults.sshUser),
                readPort(prefs, KEY_SSH_PORT, defaults.sshPort),
                readString(prefs, KEY_LOCAL_HOST, defaults.localHost),
                readPort(prefs, KEY_LOCAL_PORT, defaults.localPort),
                readString(prefs, KEY_REMOTE_HOST, defaults.remoteHost),
                readPort(prefs, KEY_REMOTE_PORT, defaults.remotePort),
                prefs.getBoolean(KEY_VERIFY_HOST_KEY, defaults.verifyHostKey));
    }

    static void saveValues(Context context, Values values) {
        prefs(context)
                .edit()
                .putString(KEY_SSH_HOST, values.sshHost)
                .putString(KEY_SSH_USER, values.sshUser)
                .putInt(KEY_SSH_PORT, values.sshPort)
                .putString(KEY_LOCAL_HOST, values.localHost)
                .putInt(KEY_LOCAL_PORT, values.localPort)
                .putString(KEY_REMOTE_HOST, values.remoteHost)
                .putInt(KEY_REMOTE_PORT, values.remotePort)
                .putBoolean(KEY_VERIFY_HOST_KEY, values.verifyHostKey)
                .apply();
    }

    static TunnelProfile[] profiles(Context context) {
        return new TunnelProfile[] {loadValues(context).toProfile()};
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String readString(SharedPreferences prefs, String key, String defaultValue) {
        String value = prefs.getString(key, defaultValue);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static int readPort(SharedPreferences prefs, String key, int defaultValue) {
        int value = prefs.getInt(key, defaultValue);
        if (isValidPort(value)) {
            return value;
        }
        return defaultValue;
    }

    static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    static final class Values {
        final String sshHost;
        final String sshUser;
        final int sshPort;
        final String localHost;
        final int localPort;
        final String remoteHost;
        final int remotePort;
        final boolean verifyHostKey;

        Values(
                String sshHost,
                String sshUser,
                int sshPort,
                String localHost,
                int localPort,
                String remoteHost,
                int remotePort,
                boolean verifyHostKey) {
            this.sshHost = sshHost;
            this.sshUser = sshUser;
            this.sshPort = sshPort;
            this.localHost = localHost;
            this.localPort = localPort;
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
            this.verifyHostKey = verifyHostKey;
        }

        TunnelProfile toProfile() {
            return new TunnelProfile(
                    sshHost,
                    sshUser,
                    sshPort,
                    localHost,
                    localPort,
                    remoteHost,
                    remotePort,
                    TunnelConfig.PRIVATE_KEY_ASSET,
                    verifyHostKey);
        }
    }
}
