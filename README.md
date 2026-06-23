# Xray SSH Tunnel

Small Android helper app that keeps an SSH local port forward for v2rayNG:

```text
127.0.0.1:34443 -> SSH 151.245.140.102 -> 127.0.0.1:443
```

v2rayNG then uses a local VLESS/Reality profile pointed at `127.0.0.1:34443`
for 151.

## Runtime Behavior

- `TunnelService` runs as a foreground service and keeps the SSH tunnel alive.
- `BootReceiver` starts the service on `BOOT_COMPLETED` and after APK replacement.
- The UI is intentionally limited to tunnel status plus `Start` and `Stop`.

## Device Setup

Release APKs are built for this deployment and should work immediately after
install. The deployment private key must be bundled as
`app/src/main/assets-bundled/phone_tunnel_151_key`; the app does not generate
fallback SSH keys on the phone.

For this deployment the server-side account should be restricted to port
forwarding only:

```text
xray-ssh-tunnel-151 -> 151.245.140.102
```

```text
restrict,port-forwarding,permitopen="127.0.0.1:443",permitopen="localhost:443" ssh-rsa ... xray-ssh-tunnel-151
```

## Build

This project was built locally with Gradle and Android SDK installed outside the repo.
Create `local.properties` locally:

```properties
sdk.dir=/path/to/android-sdk
```

Then build:

```bash
gradle assembleDebug
gradle assembleRelease
```

The APKs will be generated under:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

Release APKs must be signed before installation.

## ADB Install

On the tested Xiaomi/HyperOS device, direct `adb install` was blocked by user restrictions.
This worked:

```bash
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/xray-ssh-tunnel.apk
adb shell pm install -r -t -g /data/local/tmp/xray-ssh-tunnel.apk
```

Useful post-install allowances:

```bash
adb shell pm grant net.tref.xraytunnel android.permission.POST_NOTIFICATIONS
adb shell dumpsys deviceidle whitelist +net.tref.xraytunnel
adb shell cmd appops set net.tref.xraytunnel RUN_ANY_IN_BACKGROUND allow
adb shell cmd appops set net.tref.xraytunnel START_FOREGROUND allow
```
