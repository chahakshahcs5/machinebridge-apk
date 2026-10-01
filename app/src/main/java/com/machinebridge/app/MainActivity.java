package com.machinebridge.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "MachineBridgePrefs";
    private static final String PREF_PORT = "port";
    private static final String PREF_HOST = "host";
    private static final String PREF_API_KEY = "apiKey";
    private static final String PREF_SHELL = "shell";
    private static final String PREF_WORKSPACE = "workspace";
    private static final String PREF_TUNNEL = "tunnel";
    private static final String PREF_TUNNEL_TOKEN = "tunnelToken";
    private static final String PREF_TUNNEL_PROTOCOL = "tunnelProtocol";
    private static final String PREF_LOG_LEVEL = "logLevel";
    private static final String PREF_MAX_SESSIONS = "maxSessions";
    private static final String PREF_SESSION_TTL = "sessionTtl";
    private static final String PREF_VERBOSE = "verbose";

    // Scroll Views
    private ScrollView svMain;
    private ScrollView svConsoleLog;

    // Badges & Status
    private TextView tvStatusBadge;
    private TextView tvEnvBadge;
    private TextView tvTunnelBadge;
    private TextView tvServerUrl;
    private Button btnCopyUrl;

    // Tunnel Card
    private LinearLayout llTunnelCard;
    private TextView tvTunnelUrl;
    private Button btnCopyTunnelUrl;

    // Action Buttons
    private Button btnStartStop;
    private Button btnRunSelfTest;

    // Server Config
    private EditText etPort;
    private EditText etHost;
    private TextView tvKeyStatus;
    private EditText etApiKey;
    private Button btnToggleApiKey;
    private Button btnCopyKey;
    private Button btnRegenKey;
    private EditText etShell;
    private Button btnPresetSh;
    private Button btnPresetTermux;
    private EditText etWorkspace;
    private Button btnResetWorkspace;

    // Tunnel Config
    private CheckBox cbTunnel;
    private Spinner spTunnelProtocol;
    private EditText etTunnelToken;

    // Advanced Config
    private LinearLayout llAdvancedToggle;
    private TextView tvAdvancedTitle;
    private TextView tvAdvancedChevron;
    private LinearLayout llAdvancedContainer;
    private Spinner spLogLevel;
    private EditText etMaxSessions;
    private EditText etSessionTtl;
    private CheckBox cbVerboseLog;

    // Console
    private Button btnCopyLogs;
    private Button btnClearLogs;
    private TextView tvConsoleLog;

    private boolean mApiKeyVisible = false;
    private boolean mAdvancedExpanded = false;
    private MachineBridgeService.ServerState mCurrentState = MachineBridgeService.ServerState.STOPPED;

    private final MachineBridgeService.StateListener mServiceStateListener = state -> {
        runOnUiThread(() -> applyState(state));
    };

    private static final String[] TUNNEL_PROTOCOLS = {"quic", "http2"};
    private static final String[] TUNNEL_PROTOCOL_LABELS = {
        "QUIC (UDP - Low Latency, Default)",
        "HTTP/2 (TCP - Fallback if UDP Blocked)"
    };

    private static final String[] LOG_LEVELS = {"info", "debug", "warn", "error"};

    private final Handler mPollHandler = new Handler(Looper.getMainLooper());
    private final Runnable mLogAndTunnelPollRunnable = new Runnable() {
        @Override
        public void run() {
            // 1. Drain native log ring buffer
            try {
                String newLogs = NativeBridge.nativeGetRecentLogs();
                if (newLogs != null && !newLogs.isEmpty()) {
                    appendLog(newLogs);
                }
            } catch (Throwable ignored) {}

            // 2. Check native and service running states to keep UI completely in sync
            boolean nativeRunning = false;
            try {
                nativeRunning = NativeBridge.nativeIsServerRunning();
            } catch (Throwable ignored) {}

            MachineBridgeService.ServerState svcState = MachineBridgeService.getState();
            if (nativeRunning || svcState == MachineBridgeService.ServerState.RUNNING) {
                if (mCurrentState != MachineBridgeService.ServerState.RUNNING) {
                    applyState(MachineBridgeService.ServerState.RUNNING);
                }
                updateTunnelState();
            } else if (svcState == MachineBridgeService.ServerState.STOPPED && mCurrentState != MachineBridgeService.ServerState.STOPPED && mCurrentState != MachineBridgeService.ServerState.STARTING) {
                applyState(MachineBridgeService.ServerState.STOPPED);
            }

            // Continuous polling every 500ms
            mPollHandler.postDelayed(this, 500);
        }
    };

    private final BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String stateName = intent.getStringExtra(MachineBridgeService.EXTRA_STATE);
            if (stateName != null) {
                try {
                    MachineBridgeService.ServerState state = MachineBridgeService.ServerState.valueOf(stateName);
                    applyState(state);
                    return;
                } catch (Exception ignored) {}
            }
            updateUiState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupSpinners();

        etPort.clearFocus();

        View.OnTouchListener logTouchListener = (v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                if (svMain != null) {
                    svMain.requestDisallowInterceptTouchEvent(true);
                } else if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (svMain != null) {
                    svMain.requestDisallowInterceptTouchEvent(false);
                } else if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
            }
            return false;
        };

        if (svConsoleLog != null) {
            svConsoleLog.setOnTouchListener(logTouchListener);
        }
        if (tvConsoleLog != null) {
            tvConsoleLog.setOnTouchListener(logTouchListener);
        }

        // Initialize Native Bridge
        NativeBridge.init(getApplicationContext());

        loadPreferences();
        setupListeners();
        updateUiState();
        inspectEnvironment();
    }

    private void bindViews() {
        svMain = findViewById(R.id.svMain);
        svConsoleLog = findViewById(R.id.svConsoleLog);

        tvStatusBadge = findViewById(R.id.tvStatusBadge);
        tvEnvBadge = findViewById(R.id.tvEnvBadge);
        tvTunnelBadge = findViewById(R.id.tvTunnelBadge);
        tvServerUrl = findViewById(R.id.tvServerUrl);
        btnCopyUrl = findViewById(R.id.btnCopyUrl);

        llTunnelCard = findViewById(R.id.llTunnelCard);
        tvTunnelUrl = findViewById(R.id.tvTunnelUrl);
        btnCopyTunnelUrl = findViewById(R.id.btnCopyTunnelUrl);

        btnStartStop = findViewById(R.id.btnStartStop);
        btnRunSelfTest = findViewById(R.id.btnRunSelfTest);

        etPort = findViewById(R.id.etPort);
        etHost = findViewById(R.id.etHost);
        tvKeyStatus = findViewById(R.id.tvKeyStatus);
        etApiKey = findViewById(R.id.etApiKey);
        btnToggleApiKey = findViewById(R.id.btnToggleApiKey);
        btnCopyKey = findViewById(R.id.btnCopyKey);
        btnRegenKey = findViewById(R.id.btnRegenKey);

        etShell = findViewById(R.id.etShell);
        btnPresetSh = findViewById(R.id.btnPresetSh);
        btnPresetTermux = findViewById(R.id.btnPresetTermux);

        etWorkspace = findViewById(R.id.etWorkspace);
        btnResetWorkspace = findViewById(R.id.btnResetWorkspace);

        cbTunnel = findViewById(R.id.cbTunnel);
        spTunnelProtocol = findViewById(R.id.spTunnelProtocol);
        etTunnelToken = findViewById(R.id.etTunnelToken);

        llAdvancedToggle = findViewById(R.id.llAdvancedToggle);
        tvAdvancedTitle = findViewById(R.id.tvAdvancedTitle);
        tvAdvancedChevron = findViewById(R.id.tvAdvancedChevron);
        llAdvancedContainer = findViewById(R.id.llAdvancedContainer);
        spLogLevel = findViewById(R.id.spLogLevel);
        etMaxSessions = findViewById(R.id.etMaxSessions);
        etSessionTtl = findViewById(R.id.etSessionTtl);
        cbVerboseLog = findViewById(R.id.cbVerboseLog);

        btnCopyLogs = findViewById(R.id.btnCopyLogs);
        btnClearLogs = findViewById(R.id.btnClearLogs);
        tvConsoleLog = findViewById(R.id.tvConsoleLog);
    }

    private void setupSpinners() {
        ArrayAdapter<String> protocolAdapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            TUNNEL_PROTOCOL_LABELS
        );
        spTunnelProtocol.setAdapter(protocolAdapter);

        ArrayAdapter<String> logAdapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            LOG_LEVELS
        );
        spLogLevel.setAdapter(logAdapter);
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        etPort.setText(String.valueOf(prefs.getInt(PREF_PORT, 8080)));
        etHost.setText(prefs.getString(PREF_HOST, "0.0.0.0"));

        String defaultWorkspace = getFilesDir().getAbsolutePath() + "/workspace";
        etWorkspace.setText(prefs.getString(PREF_WORKSPACE, defaultWorkspace));
        etShell.setText(prefs.getString(PREF_SHELL, "/system/bin/sh"));

        boolean tunnelEnabled = prefs.getBoolean(PREF_TUNNEL, true);
        cbTunnel.setChecked(tunnelEnabled);
        etTunnelToken.setText(prefs.getString(PREF_TUNNEL_TOKEN, ""));
        etTunnelToken.setEnabled(tunnelEnabled);
        spTunnelProtocol.setEnabled(tunnelEnabled);

        String savedProtocol = prefs.getString(PREF_TUNNEL_PROTOCOL, "quic");
        if ("http2".equalsIgnoreCase(savedProtocol)) {
            spTunnelProtocol.setSelection(1);
        } else {
            spTunnelProtocol.setSelection(0);
        }

        String savedLogLevel = prefs.getString(PREF_LOG_LEVEL, "info");
        for (int i = 0; i < LOG_LEVELS.length; ++i) {
            if (LOG_LEVELS[i].equalsIgnoreCase(savedLogLevel)) {
                spLogLevel.setSelection(i);
                break;
            }
        }

        etMaxSessions.setText(String.valueOf(prefs.getInt(PREF_MAX_SESSIONS, 8)));
        etSessionTtl.setText(String.valueOf(prefs.getInt(PREF_SESSION_TTL, 3600)));
        cbVerboseLog.setChecked(prefs.getBoolean(PREF_VERBOSE, true));

        String apiKey = prefs.getString(PREF_API_KEY, "");
        if (apiKey.isEmpty()) {
            apiKey = generateRandomApiKey();
            prefs.edit().putString(PREF_API_KEY, apiKey).apply();
        }
        etApiKey.setText(apiKey);
        mApiKeyVisible = false;
        updateApiKeyVisibility();
    }

    private void updateApiKeyVisibility() {
        if (mApiKeyVisible) {
            etApiKey.setTransformationMethod(null);
            btnToggleApiKey.setText("Hide Key");
            if (tvKeyStatus != null) {
                tvKeyStatus.setText("VISIBLE");
                tvKeyStatus.setTextColor(Color.parseColor("#3FB950"));
            }
        } else {
            etApiKey.setTransformationMethod(PasswordTransformationMethod.getInstance());
            btnToggleApiKey.setText("Show Key");
            if (tvKeyStatus != null) {
                tvKeyStatus.setText("PROTECTED");
                tvKeyStatus.setTextColor(Color.parseColor("#58A6FF"));
            }
        }
        etApiKey.setSelection(etApiKey.getText().length());
    }

    private void savePreferences() {
        try {
            int port = Integer.parseInt(etPort.getText().toString().trim());
            int maxSessions = Integer.parseInt(etMaxSessions.getText().toString().trim());
            int sessionTtl = Integer.parseInt(etSessionTtl.getText().toString().trim());

            String proto = (spTunnelProtocol.getSelectedItemPosition() == 1) ? "http2" : "quic";
            String logLevel = LOG_LEVELS[spLogLevel.getSelectedItemPosition()];

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit()
                .putInt(PREF_PORT, port)
                .putString(PREF_HOST, etHost.getText().toString().trim())
                .putString(PREF_API_KEY, etApiKey.getText().toString().trim())
                .putString(PREF_SHELL, etShell.getText().toString().trim())
                .putString(PREF_WORKSPACE, etWorkspace.getText().toString().trim())
                .putBoolean(PREF_TUNNEL, cbTunnel.isChecked())
                .putString(PREF_TUNNEL_TOKEN, etTunnelToken.getText().toString().trim())
                .putString(PREF_TUNNEL_PROTOCOL, proto)
                .putString(PREF_LOG_LEVEL, logLevel)
                .putInt(PREF_MAX_SESSIONS, maxSessions)
                .putInt(PREF_SESSION_TTL, sessionTtl)
                .putBoolean(PREF_VERBOSE, cbVerboseLog.isChecked())
                .apply();
        } catch (Exception ignored) {}
    }

    private String generateRandomApiKey() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder("mb_");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void setupListeners() {
        btnStartStop.setOnClickListener(v -> {
            boolean isRunning = (mCurrentState == MachineBridgeService.ServerState.RUNNING)
                || (MachineBridgeService.getState() == MachineBridgeService.ServerState.RUNNING)
                || NativeBridge.nativeIsServerRunning();
            if (isRunning) {
                stopServer();
            } else {
                startServer();
            }
        });

        cbTunnel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            etTunnelToken.setEnabled(isChecked);
            spTunnelProtocol.setEnabled(isChecked);
        });

        btnToggleApiKey.setOnClickListener(v -> {
            mApiKeyVisible = !mApiKeyVisible;
            updateApiKeyVisibility();
        });

        btnCopyKey.setOnClickListener(v -> {
            String key = etApiKey.getText().toString().trim();
            copyToClipboard("Machine Bridge API Key", key);
            Toast.makeText(this, "Copied API Key: " + key, Toast.LENGTH_SHORT).show();
        });

        btnRegenKey.setOnClickListener(v -> {
            String newKey = generateRandomApiKey();
            etApiKey.setText(newKey);
            savePreferences();
            updateApiKeyVisibility();
            Toast.makeText(this, "Generated New API Key", Toast.LENGTH_SHORT).show();
        });

        btnPresetSh.setOnClickListener(v -> {
            etShell.setText("/system/bin/sh");
        });

        btnPresetTermux.setOnClickListener(v -> {
            etShell.setText("/data/data/com.termux/files/usr/bin/bash");
        });

        btnResetWorkspace.setOnClickListener(v -> {
            etWorkspace.setText(getFilesDir().getAbsolutePath() + "/workspace");
        });

        btnCopyUrl.setOnClickListener(v -> {
            String url = tvServerUrl.getText().toString();
            copyToClipboard("Machine Bridge URL", url);
            Toast.makeText(this, "Copied URL: " + url, Toast.LENGTH_SHORT).show();
        });

        btnCopyTunnelUrl.setOnClickListener(v -> {
            String url = tvTunnelUrl.getText().toString();
            copyToClipboard("Machine Bridge Tunnel URL", url);
            Toast.makeText(this, "Copied Tunnel URL: " + url, Toast.LENGTH_SHORT).show();
        });

        llAdvancedToggle.setOnClickListener(v -> {
            mAdvancedExpanded = !mAdvancedExpanded;
            llAdvancedContainer.setVisibility(mAdvancedExpanded ? View.VISIBLE : View.GONE);
            tvAdvancedChevron.setText(mAdvancedExpanded ? "▲" : "▼");
            tvAdvancedTitle.setText(mAdvancedExpanded ? "ADVANCED CONFIGURATION (TAP TO HIDE)" : "ADVANCED CONFIGURATION (TAP TO SHOW)");
        });

        btnCopyLogs.setOnClickListener(v -> {
            copyToClipboard("Machine Bridge Logs", tvConsoleLog.getText().toString());
            Toast.makeText(this, "Copied all logs to clipboard", Toast.LENGTH_SHORT).show();
        });

        btnClearLogs.setOnClickListener(v -> {
            tvConsoleLog.setText("");
            if (svConsoleLog != null) {
                svConsoleLog.scrollTo(0, 0);
            }
        });

        btnRunSelfTest.setOnClickListener(v -> runPtySelfTest());
    }

    private void startServer() {
        savePreferences();
        int port = 8080;
        int maxSessions = 8;
        int sessionTtl = 3600;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
            maxSessions = Integer.parseInt(etMaxSessions.getText().toString().trim());
            sessionTtl = Integer.parseInt(etSessionTtl.getText().toString().trim());
        } catch (Exception ignored) {}

        boolean tunnel = cbTunnel.isChecked();
        String tunnelToken = etTunnelToken.getText().toString().trim();
        String tunnelProtocol = (spTunnelProtocol.getSelectedItemPosition() == 1) ? "http2" : "quic";
        String logLevel = LOG_LEVELS[spLogLevel.getSelectedItemPosition()];
        boolean verbose = cbVerboseLog.isChecked();

        Intent serviceIntent = new Intent(this, MachineBridgeService.class);
        serviceIntent.setAction(MachineBridgeService.ACTION_START);
        serviceIntent.putExtra(MachineBridgeService.EXTRA_PORT, port);
        serviceIntent.putExtra(MachineBridgeService.EXTRA_HOST, etHost.getText().toString().trim());
        serviceIntent.putExtra(MachineBridgeService.EXTRA_API_KEY, etApiKey.getText().toString().trim());
        serviceIntent.putExtra(MachineBridgeService.EXTRA_WORKSPACE, etWorkspace.getText().toString().trim());
        serviceIntent.putExtra(MachineBridgeService.EXTRA_SHELL, etShell.getText().toString().trim());
        serviceIntent.putExtra(MachineBridgeService.EXTRA_VERBOSE, verbose);
        serviceIntent.putExtra(MachineBridgeService.EXTRA_TUNNEL, tunnel);
        serviceIntent.putExtra(MachineBridgeService.EXTRA_TUNNEL_TOKEN, tunnelToken);
        serviceIntent.putExtra(MachineBridgeService.EXTRA_TUNNEL_PROTOCOL, tunnelProtocol);
        serviceIntent.putExtra(MachineBridgeService.EXTRA_LOG_LEVEL, logLevel);
        serviceIntent.putExtra(MachineBridgeService.EXTRA_MAX_SESSIONS, maxSessions);
        serviceIntent.putExtra(MachineBridgeService.EXTRA_SESSION_TTL, sessionTtl);

        applyState(MachineBridgeService.ServerState.STARTING);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        appendLog("[Info] Starting server on port " + port + " (protocol=" + tunnelProtocol + ")...\n");
    }

    private void stopServer() {
        Intent serviceIntent = new Intent(this, MachineBridgeService.class);
        serviceIntent.setAction(MachineBridgeService.ACTION_STOP);
        startService(serviceIntent);

        applyState(MachineBridgeService.ServerState.STOPPING);
        appendLog("[Info] Stopping server...\n");
    }

    private void applyState(MachineBridgeService.ServerState state) {
        mCurrentState = state;
        int port = 8080;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (Exception ignored) {}
        String ip = NetworkUtils.getLocalIpAddress();
        tvServerUrl.setText("http://" + ip + ":" + port);

        switch (state) {
            case STARTING:
                tvStatusBadge.setText("STARTING");
                tvStatusBadge.setBackgroundColor(Color.parseColor("#EF6C00"));
                btnStartStop.setText("STARTING...");
                btnStartStop.setEnabled(false);
                btnStartStop.setBackgroundColor(Color.parseColor("#6E7681"));
                setConfigInputsEnabled(false);
                break;

            case RUNNING:
                tvStatusBadge.setText("RUNNING");
                tvStatusBadge.setBackgroundColor(Color.parseColor("#238636"));
                btnStartStop.setText("STOP SERVER");
                btnStartStop.setEnabled(true);
                btnStartStop.setBackgroundColor(Color.parseColor("#DA3633"));
                setConfigInputsEnabled(false);
                updateTunnelState();
                break;

            case STOPPING:
                tvStatusBadge.setText("STOPPING");
                tvStatusBadge.setBackgroundColor(Color.parseColor("#C62828"));
                btnStartStop.setText("STOPPING...");
                btnStartStop.setEnabled(false);
                btnStartStop.setBackgroundColor(Color.parseColor("#6E7681"));
                setConfigInputsEnabled(false);
                break;

            case STOPPED:
            default:
                mCurrentState = MachineBridgeService.ServerState.STOPPED;
                tvStatusBadge.setText("STOPPED");
                tvStatusBadge.setBackgroundColor(Color.parseColor("#424242"));
                btnStartStop.setText("START SERVER");
                btnStartStop.setEnabled(true);
                btnStartStop.setBackgroundColor(Color.parseColor("#238636"));
                setConfigInputsEnabled(true);

                tvTunnelBadge.setText("TUNNEL OFF");
                tvTunnelBadge.setBackgroundColor(Color.parseColor("#21262D"));
                llTunnelCard.setVisibility(View.GONE);
                break;
        }
    }

    private void setConfigInputsEnabled(boolean enabled) {
        etPort.setEnabled(enabled);
        etHost.setEnabled(enabled);
        etApiKey.setEnabled(enabled);
        btnRegenKey.setEnabled(enabled);
        etShell.setEnabled(enabled);
        btnPresetSh.setEnabled(enabled);
        btnPresetTermux.setEnabled(enabled);
        etWorkspace.setEnabled(enabled);
        btnResetWorkspace.setEnabled(enabled);
        cbTunnel.setEnabled(enabled);
        spTunnelProtocol.setEnabled(enabled && cbTunnel.isChecked());
        etTunnelToken.setEnabled(enabled && cbTunnel.isChecked());
        spLogLevel.setEnabled(enabled);
        etMaxSessions.setEnabled(enabled);
        etSessionTtl.setEnabled(enabled);
        cbVerboseLog.setEnabled(enabled);
    }

    private void updateTunnelState() {
        if (!cbTunnel.isChecked()) {
            tvTunnelBadge.setText("TUNNEL OFF");
            tvTunnelBadge.setBackgroundColor(Color.parseColor("#21262D"));
            llTunnelCard.setVisibility(View.GONE);
            return;
        }

        llTunnelCard.setVisibility(View.VISIBLE);
        try {
            String url = NativeBridge.nativeGetTunnelUrl();
            String err = NativeBridge.nativeGetTunnelError();
            if (url != null && !url.trim().isEmpty()) {
                tvTunnelUrl.setText(url.trim());
                tvTunnelBadge.setText("TUNNEL LIVE");
                tvTunnelBadge.setBackgroundColor(Color.parseColor("#00897B"));
                btnCopyTunnelUrl.setEnabled(true);
            } else if (err != null && !err.trim().isEmpty()) {
                tvTunnelUrl.setText("Failed: " + err.trim());
                tvTunnelBadge.setText("TUNNEL FAILED");
                tvTunnelBadge.setBackgroundColor(Color.parseColor("#C62828"));
                btnCopyTunnelUrl.setEnabled(false);
            } else {
                tvTunnelUrl.setText("Connecting to Cloudflare edge...");
                tvTunnelBadge.setText("TUNNEL STARTING");
                tvTunnelBadge.setBackgroundColor(Color.parseColor("#EF6C00"));
                btnCopyTunnelUrl.setEnabled(false);
            }
        } catch (Exception e) {
            tvTunnelUrl.setText("Error: " + e.getMessage());
            tvTunnelBadge.setText("TUNNEL ERROR");
            tvTunnelBadge.setBackgroundColor(Color.parseColor("#C62828"));
            btnCopyTunnelUrl.setEnabled(false);
        }
    }

    private void updateUiState() {
        MachineBridgeService.ServerState state = MachineBridgeService.getState();
        if (state == MachineBridgeService.ServerState.STOPPED && NativeBridge.nativeIsServerRunning()) {
            state = MachineBridgeService.ServerState.RUNNING;
        }
        applyState(state);
    }

    private void inspectEnvironment() {
        try {
            String statusJson = NativeBridge.nativeGetStatus();
            if (statusJson.contains("\"privileged_shell_available\": true")) {
                tvEnvBadge.setText("ROOT AVAILABLE");
                tvEnvBadge.setBackgroundColor(Color.parseColor("#1F6FEB"));
            } else {
                tvEnvBadge.setText("NON-ROOT SANDBOX");
                tvEnvBadge.setBackgroundColor(Color.parseColor("#30363D"));
            }
        } catch (Exception e) {
            tvEnvBadge.setText("SANDBOX");
        }
    }

    private void runPtySelfTest() {
        btnRunSelfTest.setEnabled(false);
        appendLog("\n========================================\n");
        appendLog(" [PHASE 0 GATE] Running In-App PTY Test...\n");
        appendLog(" Testing /system/bin/sh in App Sandbox UID\n");
        appendLog("========================================\n");

        new Thread(() -> {
            try {
                NativeBridge.init(getApplicationContext());
                final String reportJson = NativeBridge.nativeRunPtySelfTest();
                runOnUiThread(() -> {
                    appendLog(reportJson + "\n");
                    appendLog("========================================\n");
                    if (reportJson.contains("\"passed\": false") || reportJson.contains("\"passed\":false")) {
                        appendLog(" [RESULT] ❌ PTY TEST FAILED - Check logs\n");
                    } else {
                        appendLog(" [RESULT] ✅ ALL PTY TESTS PASSED!\n");
                    }
                    appendLog("========================================\n\n");
                    btnRunSelfTest.setEnabled(true);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    appendLog(" [ERROR] Exception executing PTY test: " + t.getMessage() + "\n");
                    btnRunSelfTest.setEnabled(true);
                });
            }
        }).start();
    }

    private void appendLog(String text) {
        if (text == null || text.isEmpty()) return;

        // Check if the user is currently scrolled near the bottom before appending new text
        boolean isNearBottom = true;
        if (svConsoleLog != null && tvConsoleLog != null) {
            int scrollY = svConsoleLog.getScrollY();
            int visibleHeight = svConsoleLog.getHeight();
            int contentHeight = tvConsoleLog.getHeight();
            // If the content exceeds viewport and user has scrolled up by more than 120 pixels, keep user's position
            if (contentHeight > visibleHeight && (contentHeight - (scrollY + visibleHeight)) > 120) {
                isNearBottom = false;
            }
        }

        tvConsoleLog.append(text);

        // Smoothly auto-scroll down ONLY if user was already at the bottom following new logs
        if (isNearBottom && svConsoleLog != null) {
            svConsoleLog.post(() -> svConsoleLog.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        MachineBridgeService.registerListener(mServiceStateListener);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mStatusReceiver, new IntentFilter(MachineBridgeService.ACTION_STATUS_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mStatusReceiver, new IntentFilter(MachineBridgeService.ACTION_STATUS_CHANGED));
        }
        updateUiState();
        mPollHandler.removeCallbacks(mLogAndTunnelPollRunnable);
        mPollHandler.post(mLogAndTunnelPollRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        MachineBridgeService.unregisterListener(mServiceStateListener);
        mPollHandler.removeCallbacks(mLogAndTunnelPollRunnable);
        try {
            unregisterReceiver(mStatusReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        MachineBridgeService.unregisterListener(mServiceStateListener);
    }
}
