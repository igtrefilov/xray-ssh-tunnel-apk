package net.tref.xraytunnel;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
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
    private static final int COLOR_BACKGROUND = 0xFFF4F6F8;
    private static final int COLOR_SURFACE = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF171A1F;
    private static final int COLOR_MUTED = 0xFF6F7682;
    private static final int COLOR_BORDER = 0xFFE1E5EA;
    private static final int COLOR_PRIMARY = 0xFF171A1F;
    private static final int COLOR_GREEN = 0xFF198754;
    private static final int COLOR_RED = 0xFFDC3545;
    private static final int COLOR_GRAY = 0xFF8A8F98;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private View statusDot;
    private TextView statusView;
    private TextView statusDetailView;
    private Button settingsToggle;
    private LinearLayout settingsPanel;
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
            statusView.setText(prefs.getString(TunnelService.KEY_STATUS, "Stopped"));
            updateStatusDot(prefs.getInt(
                    TunnelService.KEY_VPS_REACHABILITY,
                    TunnelService.REACHABILITY_UNKNOWN));
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BACKGROUND);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(18);
        root.setPadding(pad, dp(34), pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, fullWidthWrapContent());

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(22);
        title.setTextColor(COLOR_TEXT);
        header.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1));

        TextView version = new TextView(this);
        version.setText(versionText());
        version.setTextSize(13);
        version.setTextColor(COLOR_MUTED);
        version.setPadding(0, dp(2), 0, 0);
        root.addView(version, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout statusPanel = new LinearLayout(this);
        statusPanel.setOrientation(LinearLayout.VERTICAL);
        statusPanel.setPadding(dp(16), dp(14), dp(16), dp(14));
        statusPanel.setBackground(roundRect(COLOR_SURFACE, COLOR_BORDER, 1, 8));
        LinearLayout.LayoutParams statusPanelParams = fullWidthWrapContent();
        statusPanelParams.setMargins(0, dp(16), 0, dp(12));
        root.addView(statusPanel, statusPanelParams);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusPanel.addView(statusRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        statusDot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(14), dp(14));
        dotParams.setMarginEnd(dp(12));
        statusRow.addView(statusDot, dotParams);

        statusView = new TextView(this);
        statusView.setTextSize(24);
        statusView.setTextColor(COLOR_TEXT);
        statusRow.addView(statusView, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1));

        TunnelSettings.Values values = TunnelSettings.loadValues(this);
        statusDetailView = new TextView(this);
        statusDetailView.setText(values.localHost + ":" + values.localPort);
        statusDetailView.setTextSize(13);
        statusDetailView.setTextColor(COLOR_MUTED);
        statusDetailView.setPadding(dp(26), dp(4), 0, 0);
        statusPanel.addView(statusDetailView, fullWidthWrapContent());

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(actionRow, fullWidthWrapContent());

        Button start = styledButton(R.string.button_start, COLOR_GREEN, Color.WHITE, COLOR_GREEN);
        start.setOnClickListener(v -> startTunnelService(TunnelService.ACTION_START));
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(
                0,
                dp(48),
                1);
        startParams.setMarginEnd(dp(8));
        actionRow.addView(start, startParams);

        Button stop = styledButton(R.string.button_stop, Color.TRANSPARENT, COLOR_RED, COLOR_RED);
        stop.setOnClickListener(v -> startTunnelService(TunnelService.ACTION_STOP));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                0,
                dp(48),
                1);
        stopParams.setMarginStart(dp(8));
        actionRow.addView(stop, stopParams);

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

        settingsToggle = styledButton(
                R.string.settings_title,
                Color.TRANSPARENT,
                COLOR_PRIMARY,
                COLOR_BORDER);
        settingsToggle.setOnClickListener(v -> toggleSettings());
        LinearLayout.LayoutParams toggleParams = fullWidthWrapContent();
        toggleParams.setMargins(0, dp(12), 0, 0);
        root.addView(settingsToggle, toggleParams);

        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setPadding(dp(14), dp(12), dp(14), dp(14));
        settingsPanel.setBackground(roundRect(COLOR_SURFACE, COLOR_BORDER, 1, 8));
        settingsPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams panelParams = fullWidthWrapContent();
        panelParams.setMargins(0, dp(10), 0, 0);
        root.addView(settingsPanel, panelParams);

        sshHostInput = addTextInput(settingsPanel, R.string.settings_vps_ip, values.sshHost, textInputType());
        sshUserInput = addTextInput(settingsPanel, R.string.settings_ssh_user, values.sshUser, textInputType());
        sshPortInput = addTextInput(
                settingsPanel,
                R.string.settings_ssh_port,
                String.valueOf(values.sshPort),
                portInputType());
        localHostInput = addTextInput(settingsPanel, R.string.settings_listen_ip, values.localHost, textInputType());
        localPortInput = addTextInput(
                settingsPanel,
                R.string.settings_listen_port,
                String.valueOf(values.localPort),
                portInputType());
        remoteHostInput = addTextInput(settingsPanel, R.string.settings_target_ip, values.remoteHost, textInputType());
        remotePortInput = addTextInput(
                settingsPanel,
                R.string.settings_target_port,
                String.valueOf(values.remotePort),
                portInputType());

        verifyHostKeyInput = new CheckBox(this);
        verifyHostKeyInput.setText(R.string.settings_verify_host_key);
        verifyHostKeyInput.setTextColor(COLOR_TEXT);
        verifyHostKeyInput.setChecked(values.verifyHostKey);
        settingsPanel.addView(verifyHostKeyInput, fullWidthWrapContent());

        LinearLayout settingsActions = new LinearLayout(this);
        settingsActions.setOrientation(LinearLayout.HORIZONTAL);
        settingsActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams settingsActionsParams = fullWidthWrapContent();
        settingsActionsParams.setMargins(0, dp(10), 0, 0);
        settingsPanel.addView(settingsActions, settingsActionsParams);

        Button save = styledButton(R.string.settings_save, COLOR_PRIMARY, Color.WHITE, COLOR_PRIMARY);
        save.setOnClickListener(v -> saveSettings());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                0,
                dp(44),
                1);
        saveParams.setMarginEnd(dp(8));
        settingsActions.addView(save, saveParams);

        Button reset = styledButton(R.string.settings_reset, Color.TRANSPARENT, COLOR_MUTED, COLOR_BORDER);
        reset.setOnClickListener(v -> resetSettings());
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                0,
                dp(44),
                1);
        resetParams.setMarginStart(dp(8));
        settingsActions.addView(reset, resetParams);
    }

    private EditText addTextInput(
            LinearLayout root,
            int labelResId,
            String value,
            int inputType) {
        TextView labelView = new TextView(this);
        labelView.setText(labelResId);
        labelView.setTextSize(12);
        labelView.setTextColor(COLOR_MUTED);
        labelView.setPadding(0, dp(8), 0, dp(2));
        root.addView(labelView, fullWidthWrapContent());

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setTextColor(COLOR_TEXT);
        input.setTextSize(15);
        input.setInputType(inputType);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setBackground(roundRect(0xFFF8FAFC, COLOR_BORDER, 1, 6));
        root.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)));
        return input;
    }

    private void saveSettings() {
        try {
            TunnelSettings.Values values = readSettingsFromInputs();
            TunnelSettings.saveValues(this, values);
            statusDetailView.setText(values.localHost + ":" + values.localPort);
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
        statusDetailView.setText(values.localHost + ":" + values.localPort);
    }

    private String requiredText(EditText input, String label) {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " required");
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
        throw new IllegalArgumentException(label + ": 1-65535");
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

    private void toggleSettings() {
        boolean show = settingsPanel.getVisibility() != View.VISIBLE;
        settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        settingsToggle.setText(show ? R.string.settings_hide : R.string.settings_title);
    }

    private Button styledButton(int textResId, int backgroundColor, int textColor, int strokeColor) {
        Button button = new Button(this);
        button.setText(textResId);
        button.setAllCaps(false);
        button.setTextColor(textColor);
        button.setTextSize(15);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(roundRect(backgroundColor, strokeColor, 1, 8));
        return button;
    }

    private GradientDrawable roundRect(int fillColor, int strokeColor, int strokeDp, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), strokeColor);
        }
        return drawable;
    }

    private void updateStatusDot(int reachability) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(statusDotColor(reachability));
        statusDot.setBackground(drawable);
    }

    private int statusDotColor(int reachability) {
        if (reachability == TunnelService.REACHABILITY_REACHABLE) {
            return COLOR_GREEN;
        }
        if (reachability == TunnelService.REACHABILITY_UNREACHABLE) {
            return COLOR_RED;
        }
        return COLOR_GRAY;
    }

    private String versionText() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : "Version " + info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
