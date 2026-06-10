package net.tref.xraytunnel;

final class TunnelProfile {
    final String name;
    final String sshHost;
    final int localPort;
    final String privateKeyAsset;
    final String publicKeyAsset;

    TunnelProfile(String name, String sshHost, int localPort,
            String privateKeyAsset, String publicKeyAsset) {
        this.name = name;
        this.sshHost = sshHost;
        this.localPort = localPort;
        this.privateKeyAsset = privateKeyAsset;
        this.publicKeyAsset = publicKeyAsset;
    }
}
