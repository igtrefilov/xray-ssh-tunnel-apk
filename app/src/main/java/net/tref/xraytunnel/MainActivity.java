package net.tref.xraytunnel;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int REQUEST_POST_NOTIFICATIONS = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private EditText sshHostInput;
    private EditText sshUserInput;
    private EditText sshPortInput;
    private EditText localHostInput;
    private EditText localPortInput;
    private EditText remoteHostInput;
    private EditText remotePortInput;
    private CheckBox verifyHostKeyInput;
    private String pendingNotificationAction;

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

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(22);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setPadding(0, dp(12), 0, dp(20));
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Button start = new Button(this);
        start.setText(R.string.button_start);
        start.setOnClickListener(v -> startTunnelService(TunnelService.ACTION_START));
        root.addView(start, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Button stop = new Button(this);
        stop.setText(R.string.button_stop);
        stop.setOnClickListener(v -> startTunnelService(TunnelService.ACTION_STOP));
        root.addView(stop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        addSettingsSection(root);

        setContentView(scroll);
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

    private void startTunnelService(String action) {
        if (shouldRequestNotifications()) {
            pendingNotificationAction = action;
            requestPermissions(
                    new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATIONS);
            return;
        }
        Intent intent = new Intent(this, TunnelService.class).setAction(action);
        if (TunnelService.ACTION_STOP.equals(action)) {
            startService(intent);
            return;
        }
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
        if (requestCode != REQUEST_POST_NOTIFICATIONS || pendingNotificationAction == null) {
            return;
        }
        String action = pendingNotificationAction;
        pendingNotificationAction = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startTunnelService(action);
        } else {
            Toast.makeText(this, R.string.notification_permission_required, Toast.LENGTH_LONG).show();
        }
    }

    private boolean shouldRequestNotifications() {
        return android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void addSettingsSection(LinearLayout root) {
        TunnelSettings.Values values = TunnelSettings.loadValues(this);

        TextView settingsTitle = new TextView(this);
        settingsTitle.setText(R.string.settings_title);
        settingsTitle.setTextSize(18);
        settingsTitle.setPadding(0, dp(24), 0, dp(8));
        root.addView(settingsTitle, fullWidthWrapContent());

        sshHostInput = addTextInput(root, R.string.settings_vps_ip, values.sshHost, textInputType());
        sshUserInput = addTextInput(root, R.string.settings_ssh_user, values.sshUser, textInputType());
        sshPortInput = addTextInput(
                root,
                R.string.settings_ssh_port,
                String.valueOf(values.sshPort),
                portInputType());
        localHostInput = addTextInput(root, R.string.settings_listen_ip, values.localHost, textInputType());
        localPortInput = addTextInput(
                root,
                R.string.settings_listen_port,
                String.valueOf(values.localPort),
                portInputType());
        remoteHostInput = addTextInput(root, R.string.settings_target_ip, values.remoteHost, textInputType());
        remotePortInput = addTextInput(
                root,
                R.string.settings_target_port,
                String.valueOf(values.remotePort),
                portInputType());

        verifyHostKeyInput = new CheckBox(this);
        verifyHostKeyInput.setText(R.string.settings_verify_host_key);
        verifyHostKeyInput.setChecked(values.verifyHostKey);
        root.addView(verifyHostKeyInput, fullWidthWrapContent());

        Button save = new Button(this);
        save.setText(R.string.settings_save);
        save.setOnClickListener(v -> saveSettings());
        root.addView(save, fullWidthWrapContent());

        Button reset = new Button(this);
        reset.setText(R.string.settings_reset);
        reset.setOnClickListener(v -> resetSettings());
        root.addView(reset, fullWidthWrapContent());
    }

    private EditText addTextInput(
            LinearLayout root,
            int labelResId,
            String value,
            int inputType) {
        TextView labelView = new TextView(this);
        labelView.setText(labelResId);
        labelView.setTextSize(12);
        labelView.setPadding(0, dp(8), 0, 0);
        root.addView(labelView, fullWidthWrapContent());

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setInputType(inputType);
        root.addView(input, fullWidthWrapContent());
        return input;
    }

    private void saveSettings() {
        try {
            TunnelSettings.saveValues(this, readSettingsFromInputs());
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void resetSettings() {
        TunnelSettings.Values defaults = TunnelSettings.defaultValues();
        populateSettings(defaults);
        TunnelSettings.saveValues(this, defaults);
        Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_LONG).show();
    }

    private TunnelSettings.Values readSettingsFromInputs() {
        return new TunnelSettings.Values(
                requiredText(sshHostInput, "VPS IP"),
                requiredText(sshUserInput, "SSH user"),
                parsePort(sshPortInput, "SSH port"),
                requiredText(localHostInput, "Listen IP"),
                parsePort(localPortInput, "Listen port"),
                requiredText(remoteHostInput, "Target IP"),
                parsePort(remotePortInput, "Target port"),
                verifyHostKeyInput.isChecked());
    }

    private void populateSettings(TunnelSettings.Values values) {
        sshHostInput.setText(values.sshHost);
        sshUserInput.setText(values.sshUser);
        sshPortInput.setText(String.valueOf(values.sshPort));
        localHostInput.setText(values.localHost);
        localPortInput.setText(String.valueOf(values.localPort));
        remoteHostInput.setText(values.remoteHost);
        remotePortInput.setText(String.valueOf(values.remotePort));
        verifyHostKeyInput.setChecked(values.verifyHostKey);
    }

    private String requiredText(EditText input, String label) {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private int parsePort(EditText input, String label) {
        String value = requiredText(input, label);
        try {
            int port = Integer.parseInt(value);
            if (TunnelSettings.isValidPort(port)) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // Fall through to a single validation message.
        }
        throw new IllegalArgumentException(label + " must be 1-65535");
    }

    private int textInputType() {
        return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
    }

    private int portInputType() {
        return InputType.TYPE_CLASS_NUMBER;
    }

    private LinearLayout.LayoutParams fullWidthWrapContent() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
