package net.tref.xraytunnel;

final class TunnelProfile {
    final String sshHost;
    final String sshUser;
    final int sshPort;
    final String localHost;
    final int localPort;
    final String remoteHost;
    final int remotePort;
    final String privateKeyAsset;
    final boolean verifyHostKey;

    TunnelProfile(
            String sshHost,
            String sshUser,
            int sshPort,
            String localHost,
            int localPort,
            String remoteHost,
            int remotePort,
            String privateKeyAsset,
            boolean verifyHostKey) {
        this.sshHost = sshHost;
        this.sshUser = sshUser;
        this.sshPort = sshPort;
        this.localHost = localHost;
        this.localPort = localPort;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.privateKeyAsset = privateKeyAsset;
        this.verifyHostKey = verifyHostKey;
    }
}
