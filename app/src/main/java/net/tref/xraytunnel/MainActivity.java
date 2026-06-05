package net.tref.xraytunnel;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int REQUEST_POST_NOTIFICATIONS = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private String pendingStartAction;

    private final Runnable refreshStatus = new Runnable() {
        @Override
        public void run() {
            SharedPreferences prefs = getSharedPreferences(TunnelService.PREFS, MODE_PRIVATE);
            statusView.setText(prefs.getString(TunnelService.KEY_STATUS, "stopped"));
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Xray SSH Tunnel");
        title.setTextSize(22);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView config = new TextView(this);
        config.setText("107: 127.0.0.1:24443 -> 107.161.82.52:127.0.0.1:443\n"
                + "151: 127.0.0.1:34443 -> 151.245.140.102:127.0.0.1:443");
        config.setTextSize(14);
        config.setPadding(0, dp(12), 0, dp(20));
        root.addView(config, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setPadding(0, 0, 0, dp(20));
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Button start = new Button(this);
        start.setText("Start");
        start.setOnClickListener(v -> startService(TunnelService.ACTION_START));
        root.addView(start, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> startService(TunnelService.ACTION_STOP));
        root.addView(stop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Button autostart = new Button(this);
        autostart.setText("Autostart");
        autostart.setOnClickListener(v -> openAutostartSettings());
        root.addView(autostart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Button battery = new Button(this);
        battery.setText("Battery");
        battery.setOnClickListener(v -> openBatterySettings());
        root.addView(battery, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshStatus);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshStatus);
        super.onPause();
    }

    private void startService(String action) {
        if (shouldRequestNotifications()) {
            pendingStartAction = action;
            requestPermissions(
                    new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATIONS);
            return;
        }
        Intent intent = new Intent(this, TunnelService.class).setAction(action);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_POST_NOTIFICATIONS || pendingStartAction == null) {
            return;
        }
        String action = pendingStartAction;
        pendingStartAction = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startService(action);
        } else {
            Toast.makeText(this, "Notification permission is required", Toast.LENGTH_LONG).show();
        }
    }

    private boolean shouldRequestNotifications() {
        return android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void openAutostartSettings() {
        Intent miuiAutostartAction = new Intent("miui.intent.action.OP_AUTO_START")
                .setPackage("com.miui.securitycenter");

        Intent miuiAutostart = new Intent()
                .setComponent(new ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"));

        Intent miuiPermissions = new Intent()
                .setComponent(new ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity"))
                .putExtra("extra_pkgname", getPackageName());

        Intent appSettings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + getPackageName()));

        if (!startFirstAvailable(miuiAutostartAction, miuiAutostart, miuiPermissions, appSettings)) {
            Toast.makeText(this, "Autostart settings not available", Toast.LENGTH_LONG).show();
        }
    }

    private void openBatterySettings() {
        Intent requestIgnore = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + getPackageName()));
        Intent batteryOptimization = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        Intent appSettings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + getPackageName()));

        if (!startFirstAvailable(requestIgnore, batteryOptimization, appSettings)) {
            Toast.makeText(this, "Battery settings not available", Toast.LENGTH_LONG).show();
        }
    }

    private boolean startFirstAvailable(Intent... intents) {
        for (Intent intent : intents) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (RuntimeException ignored) {
                // Try the next vendor or platform settings screen.
            }
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
