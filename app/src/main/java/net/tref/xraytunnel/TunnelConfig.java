package net.tref.xraytunnel;

final class TunnelConfig {
    static final String SSH_USER = "ilya";
    static final String LOCAL_HOST = "127.0.0.1";
    static final String REMOTE_HOST = "127.0.0.1";
    static final int SSH_PORT = 22;
    static final int REMOTE_PORT = 443;

    static final TunnelProfile[] PROFILES = new TunnelProfile[] {
            new TunnelProfile(
                    "151",
                    "151.245.140.102",
                    34443,
                    "phone_tunnel_151_key",
                    "phone_tunnel_151_key.pub")
    };

    private TunnelConfig() {
    }
}
