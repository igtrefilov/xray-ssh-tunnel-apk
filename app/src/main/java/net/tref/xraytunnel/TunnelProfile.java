package net.tref.xraytunnel;

final class TunnelProfile {
    final String name;
    final String sshHost;
    final int localPort;

    TunnelProfile(String name, String sshHost, int localPort) {
        this.name = name;
        this.sshHost = sshHost;
        this.localPort = localPort;
    }
}
