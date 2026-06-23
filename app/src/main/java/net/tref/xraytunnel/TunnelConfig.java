package net.tref.xraytunnel;

final class TunnelConfig {
    static final int PROFILE_COUNT = 1;

    static final String DEFAULT_SSH_HOST = "151.245.140.102";
    static final String DEFAULT_SSH_USER = "ilya";
    static final int DEFAULT_SSH_PORT = 22;
    static final String DEFAULT_LOCAL_HOST = "127.0.0.1";
    static final int DEFAULT_LOCAL_PORT = 34443;
    static final String DEFAULT_REMOTE_HOST = "127.0.0.1";
    static final int DEFAULT_REMOTE_PORT = 443;
    static final String PRIVATE_KEY_ASSET = "phone_tunnel_151_key";

    private TunnelConfig() {
    }
}
