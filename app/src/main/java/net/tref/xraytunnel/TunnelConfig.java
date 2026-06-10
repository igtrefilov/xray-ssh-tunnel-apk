package net.tref.xraytunnel;

final class TunnelConfig {
    static final String SSH_USER = "ilya";
    static final String LOCAL_HOST = "127.0.0.1";
    static final String REMOTE_HOST = "127.0.0.1";
    static final int SSH_PORT = 22;
    static final int REMOTE_PORT = 443;

    static final TunnelProfile[] PROFILES = new TunnelProfile[] {
            new TunnelProfile("107", "107.161.82.52", 24443),
            new TunnelProfile("151", "151.245.140.102", 34443)
    };

    private TunnelConfig() {
    }
}
