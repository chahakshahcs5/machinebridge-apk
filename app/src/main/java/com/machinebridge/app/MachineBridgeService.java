package com.machinebridge.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MachineBridgeService extends Service {
    private static final String TAG = "MachineBridgeService";
    public static final String CHANNEL_ID = "machinebridge_channel";
    public static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START = "com.machinebridge.app.START";
    public static final String ACTION_STOP = "com.machinebridge.app.STOP";
    public static final String ACTION_STATUS_CHANGED = "com.machinebridge.app.STATUS_CHANGED";

    public enum ServerState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    public interface StateListener {
        void onStateChanged(ServerState state);
    }

    private static final List<StateListener> sListeners = new CopyOnWriteArrayList<>();

    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_API_KEY = "apiKey";
    public static final String EXTRA_WORKSPACE = "workspace";
    public static final String EXTRA_SHELL = "shell";
    public static final String EXTRA_VERBOSE = "verbose";
    public static final String EXTRA_TUNNEL = "tunnel";
    public static final String EXTRA_TUNNEL_TOKEN = "tunnelToken";
    public static final String EXTRA_TUNNEL_PROTOCOL = "tunnelProtocol";
    public static final String EXTRA_LOG_LEVEL = "logLevel";
    public static final String EXTRA_MAX_SESSIONS = "maxSessions";
    public static final String EXTRA_SESSION_TTL = "sessionTtl";
    public static final String EXTRA_STATE = "state";

    private static volatile ServerState sState = ServerState.STOPPED;

    public static ServerState getState() {
        return sState;
    }

    public static boolean isRunning() {
        return sState == ServerState.RUNNING;
    }

    public static void registerListener(StateListener listener) {
        if (listener != null && !sListeners.contains(listener)) {
            sListeners.add(listener);
            listener.onStateChanged(sState);
        }
    }

    public static void unregisterListener(StateListener listener) {
        if (listener != null) {
            sListeners.remove(listener);
        }
    }

    public static void notifyStopped() {
        synchronized (sStateLock) {
            sState = ServerState.STOPPED;
        }
        dispatchState(ServerState.STOPPED, null);
    }

    private static void dispatchState(ServerState state, Context context) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (StateListener l : sListeners) {
                try {
                    l.onStateChanged(state);
                } catch (Throwable t) {
                    Log.e(TAG, "Error in state listener", t);
                }
            }
        });

        if (context != null) {
            try {
                Intent intent = new Intent(ACTION_STATUS_CHANGED);
                intent.putExtra(EXTRA_STATE, state.name());
                intent.setPackage(context.getPackageName());
                context.sendBroadcast(intent);
            } catch (Throwable ignored) {}
        }
    }

    private void broadcastState(ServerState state) {
        dispatchState(state, getApplicationContext());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private static final Object sStateLock = new Object();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopServer();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            final int port = intent.getIntExtra(EXTRA_PORT, 8080);
            final String host = intent.getStringExtra(EXTRA_HOST) != null ? intent.getStringExtra(EXTRA_HOST) : "0.0.0.0";
            final String apiKey = intent.getStringExtra(EXTRA_API_KEY) != null ? intent.getStringExtra(EXTRA_API_KEY) : "";
            final String workspace = intent.getStringExtra(EXTRA_WORKSPACE) != null ? intent.getStringExtra(EXTRA_WORKSPACE) : "";
            final String shell = intent.getStringExtra(EXTRA_SHELL) != null ? intent.getStringExtra(EXTRA_SHELL) : "/system/bin/sh";
            final boolean verbose = intent.getBooleanExtra(EXTRA_VERBOSE, true);
            final boolean tunnel = intent.getBooleanExtra(EXTRA_TUNNEL, true);
            final String tunnelToken = intent.getStringExtra(EXTRA_TUNNEL_TOKEN) != null ? intent.getStringExtra(EXTRA_TUNNEL_TOKEN) : "";
            final String tunnelProtocol = intent.getStringExtra(EXTRA_TUNNEL_PROTOCOL) != null ? intent.getStringExtra(EXTRA_TUNNEL_PROTOCOL) : "quic";
            final String logLevel = intent.getStringExtra(EXTRA_LOG_LEVEL) != null ? intent.getStringExtra(EXTRA_LOG_LEVEL) : "info";
            final int maxSessions = intent.getIntExtra(EXTRA_MAX_SESSIONS, 8);
            final int sessionTtl = intent.getIntExtra(EXTRA_SESSION_TTL, 3600);

            synchronized (sStateLock) {
                if (sState == ServerState.RUNNING || sState == ServerState.STARTING) {
                    Log.i(TAG, "Server already running or starting, broadcasting current state: " + sState);
                    broadcastState(sState);
                    return START_STICKY;
                }
                sState = ServerState.STARTING;
            }
            broadcastState(ServerState.STARTING);

            startForeground(NOTIFICATION_ID, buildNotification("Starting server on port " + port + "..."));

            new Thread(() -> {
                NativeBridge.init(getApplicationContext());
                boolean ok = NativeBridge.nativeStartServer(
                    port, host, apiKey, workspace, shell, verbose,
                    tunnel, tunnelToken, tunnelProtocol, logLevel, maxSessions, sessionTtl
                );
                synchronized (sStateLock) {
                    sState = ok ? ServerState.RUNNING : ServerState.STOPPED;
                }
                if (ok) {
                    Log.i(TAG, "Server running on " + host + ":" + port + " (tunnel=" + tunnel + ", proto=" + tunnelProtocol + ")");
                    updateNotification("Running on port " + port + " (" + NetworkUtils.getLocalIpAddress() + ")" + (tunnel ? " [Tunnel active]" : ""));
                } else {
                    Log.e(TAG, "Failed to start server");
                    updateNotification("Failed to start server on port " + port);
                    stopForeground(true);
                    stopSelf();
                }
                broadcastState(sState);
            }).start();
        }

        return START_STICKY;
    }

    private void stopServer() {
        synchronized (sStateLock) {
            if (sState == ServerState.STOPPED || sState == ServerState.STOPPING) {
                Log.i(TAG, "Server already stopped or stopping: " + sState);
                return;
            }
            sState = ServerState.STOPPING;
        }
        broadcastState(ServerState.STOPPING);
        updateNotification("Stopping server...");

        new Thread(() -> {
            try {
                NativeBridge.nativeStopServer();
            } catch (Throwable t) {
                Log.e(TAG, "Error stopping native server", t);
            }
            synchronized (sStateLock) {
                sState = ServerState.STOPPED;
            }
            Log.i(TAG, "Server stopped");
            broadcastState(ServerState.STOPPED);
            stopForeground(true);
            stopSelf();
        }).start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Machine Bridge Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows active Machine Bridge server status");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Intent stopIntent = new Intent(this, MachineBridgeService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("Machine Bridge Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true);

        return builder.build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "onTaskRemoved: App removed from recents, keeping foreground server service active");
    }

    @Override
    public void onDestroy() {
        synchronized (sStateLock) {
            if (sState == ServerState.RUNNING) {
                try {
                    NativeBridge.nativeStopServer();
                } catch (Throwable ignored) {}
                sState = ServerState.STOPPED;
                broadcastState(ServerState.STOPPED);
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
