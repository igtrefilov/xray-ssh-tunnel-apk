package net.tref.xraytunnel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import com.jcraft.jsch.SocketFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

final class UnderlyingNetworkSocketFactory implements SocketFactory {
    private static final String TAG = "XrayTunnel";
    private static final int CONNECT_TIMEOUT_MS = 15000;

    private final Context context;

    UnderlyingNetworkSocketFactory(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return socket;
        }

        IOException failure = null;
        InetSocketAddress address = new InetSocketAddress(host, port);
        for (Network network : orderedUnderlyingNetworks(manager)) {
            Socket socket = null;
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            try {
                socket = network.getSocketFactory().createSocket();
                socket.connect(address, CONNECT_TIMEOUT_MS);
                return socket;
            } catch (IOException e) {
                closeQuietly(socket);
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
                Log.w(TAG, "SSH connect via " + describeNetwork(caps, network) + " failed", e);
            }
        }

        Network activeNetwork = manager.getActiveNetwork();
        NetworkCapabilities activeCaps = manager.getNetworkCapabilities(activeNetwork);
        if (hasUnderlyingTransport(activeCaps) && isUsableNetwork(activeCaps, false)) {
            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(address, CONNECT_TIMEOUT_MS);
                return socket;
            } catch (IOException e) {
                closeQuietly(socket);
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
                Log.w(TAG, "SSH connect via default " + describeNetwork(activeCaps, activeNetwork) + " failed", e);
            }
        }

        if (failure != null) {
            throw failure;
        }
        throw new IOException("No usable non-VPN internet network is available");
    }

    @Override
    public InputStream getInputStream(Socket socket) throws IOException {
        return socket.getInputStream();
    }

    @Override
    public OutputStream getOutputStream(Socket socket) throws IOException {
        return socket.getOutputStream();
    }

    static Network preferredUnderlyingNetwork(ConnectivityManager manager) {
        List<Network> networks = orderedUnderlyingNetworks(manager);
        return networks.isEmpty() ? null : networks.get(0);
    }

    static String describeNetwork(ConnectivityManager manager, Network network) {
        return describeNetwork(manager.getNetworkCapabilities(network), network);
    }

    private static List<Network> orderedUnderlyingNetworks(ConnectivityManager manager) {
        ArrayList<Network> networks = new ArrayList<>();
        addNetworks(manager, networks, true, NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_ETHERNET);
        addNetworks(manager, networks, true, NetworkCapabilities.TRANSPORT_CELLULAR);
        addNetworks(manager, networks, false, NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_ETHERNET);
        addNetworks(manager, networks, false, NetworkCapabilities.TRANSPORT_CELLULAR);
        return networks;
    }

    private static void addNetworks(
            ConnectivityManager manager,
            List<Network> output,
            boolean requireValidated,
            int... transports) {
        for (Network network : manager.getAllNetworks()) {
            if (output.contains(network)) {
                continue;
            }
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (!isUsableNetwork(caps, requireValidated)) {
                continue;
            }
            for (int transport : transports) {
                if (caps.hasTransport(transport)) {
                    output.add(network);
                    break;
                }
            }
        }
    }

    private static boolean isUsableNetwork(NetworkCapabilities caps, boolean requireValidated) {
        if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return false;
        }
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false;
        }
        return !requireValidated || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private static boolean hasUnderlyingTransport(NetworkCapabilities caps) {
        return caps != null
                && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
    }

    private static String describeNetwork(NetworkCapabilities caps, Network network) {
        if (caps == null) {
            return "network " + network;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "Wi-Fi network " + network;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "Ethernet network " + network;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "cellular network " + network;
        }
        return "network " + network;
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
}
